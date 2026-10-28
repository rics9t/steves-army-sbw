package com.stevesarmy.vehicle;

import com.stevesarmy.StevesArmyMod;
import com.stevesarmy.compat.sbw.SbwVehicles;
import com.stevesarmy.entity.SoldierEntity;
import com.stevesarmy.entity.SoldierRole;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Server-side driver for one SBW vehicle. Only dedicated vehicle crew may
 * occupy the driver seat and operate the vehicle.
 *
 * Driving model (rewritten):
 *   - the route is a smoothed polyline from VehiclePathfinder, followed with
 *     pure pursuit on a speed scaled look ahead point, so steering is a curve
 *     instead of a visible chain of goals;
 *   - speed is governed by a measured deceleration estimate, so the hull stops
 *     on the point at 0.2 blocks/tick and at 1.4 blocks/tick alike;
 *   - every stop reason (destination, follow gap, friendly vehicle in the lane,
 *     corner) is expressed as "metres of road left", never as a latched brake flag;
 *   - friendly infantry never stop the hull: friendly fire is off, so the driver
 *     only eases around them and otherwise just keeps rolling;
 *   - a hull whose gunner cannot bring the main gun to bear (target inside the
 *     barrel dead zone, or outside the elevation limits) backs away from it.
 */
public final class VehicleDriver {

    /** Nominal gap kept from the followed entity. */
    private static final double FOLLOW_DISTANCE = 12.0;
    private static final double FOLLOW_SLACK = 3.0;
    private static final double MOVE_STOP_RADIUS = 2.0;
    private static final double ATTACK_STANDOFF = 24.0;

    /** Below this horizontal speed the hull counts as stopped. */
    private static final double STOPPED_SPEED = 0.035;

    private static final float STEER_DEADZONE_DEGREES = 2.5f;
    private static final float HARD_TURN_DEGREES = 62.0f;
    private static final float MAX_YAW_ASSIST = 2.0f;

    private static final int PATH_MAX_AGE_TICKS = 100;
    private static final int PATH_MIN_REPLAN_TICKS = 10;
    private static final int PATH_FAIL_BACKOFF_TICKS = 25;
    private static final double PATH_GOAL_DRIFT = 4.0;

    /** Input to engine response delay used by the stopping distance model. */
    private static final double REACTION_TICKS = 3.0;
    private static final double MIN_DECEL = 0.0040;
    private static final double MAX_DECEL = 0.2500;
    private static final double DEFAULT_DECEL = 0.0180;

    private static final int STUCK_TRIGGER_TICKS = 40;
    private static final int REVERSE_TICKS = 18;

    private final WeakReference<Entity> vehicleRef;
    private WeakReference<SoldierEntity> driverRef = new WeakReference<>(null);

    private VehicleDriveMode mode = VehicleDriveMode.IDLE;

    private WeakReference<Entity> followTarget = new WeakReference<>(null);
    private BlockPos destination;
    private UUID attackTargetId;
    private Vec3 attackTargetPos;

    /** Smoothed route. Index 0 is always the next point ahead of the hull. */
    private List<Vec3> path = new ArrayList<>();
    private int pathAge = 0;
    private int replanCooldown = 0;
    private Vec3 pathGoal;
    private boolean handedOver;

    private double decelEstimate = DEFAULT_DECEL;
    private double previousSpeed = 0.0;
    private boolean brakedLastTick;
    private float previousYaw;

    private int stallTicks = 0;
    private int reverseTicks = 0;
    private boolean throttleCommanded;

    /** True once this driver has written inputs the hull may still be holding. */
    private boolean controlsDirty;

    /** Backing away from a target the main gun cannot reach. */
    private boolean closeRetreating;
    private boolean retreatReversing;
    private boolean retreatReverseClear;
    private int retreatPathCooldown;

    VehicleDriver(Entity vehicle) {
        this.vehicleRef = new WeakReference<>(vehicle);
        this.previousYaw = vehicle.getYRot();
    }

    public Entity vehicle() {
        return vehicleRef.get();
    }

    public SoldierEntity driverSoldier() {
        return driverRef.get();
    }

    public void setDriverSoldier(SoldierEntity soldier) {
        this.driverRef = new WeakReference<>(soldier);
        this.handedOver = false;
    }

    public VehicleDriveMode mode() {
        return mode;
    }

    public void orderFollow(Entity target) {
        this.mode = VehicleDriveMode.FOLLOW;
        this.followTarget = new WeakReference<>(target);
        this.destination = null;
        clearAttack();
        clearPath();
    }

    public void orderGoTo(BlockPos pos) {
        this.mode = VehicleDriveMode.GO_TO;
        this.destination = pos;
        this.followTarget = new WeakReference<>(null);
        this.closeRetreating = false;
        clearAttack();
        clearPath();
    }

    public void orderAttack(UUID targetId, Vec3 pos) {
        this.mode = VehicleDriveMode.ATTACK;
        this.destination = null;
        this.followTarget = new WeakReference<>(null);
        this.attackTargetId = targetId;
        this.attackTargetPos = pos;
        clearPath();
    }

    public void orderHold() {
        this.mode = VehicleDriveMode.HOLD;
        this.destination = null;
        this.followTarget = new WeakReference<>(null);
        clearAttack();
        clearPath();
        Entity vehicle = vehicle();
        if (vehicle != null) brake(vehicle);
    }

    public void orderDismount() {
        this.mode = VehicleDriveMode.DISMOUNT;
        this.closeRetreating = false;
        clearAttack();
        clearPath();
        releaseControls();
    }

    public void clearAttack() {
        this.attackTargetId = null;
        this.attackTargetPos = null;
    }

    private void clearPath() {
        path = new ArrayList<>();
        pathGoal = null;
        pathAge = 0;
        replanCooldown = 0;
        stallTicks = 0;
        reverseTicks = 0;
    }

    // -- main tick ------------------------------------------------------------

    public boolean tick() {
        Entity vehicle = vehicle();
        if (vehicle == null || vehicle.isRemoved()) return false;
        if (vehicle.level().isClientSide) return true;

        if (SbwVehicles.isWrecked(vehicle)) {
            VehicleWreckHandler.ejectAll(vehicle);
            return false;
        }

        // Bug fix: a player anywhere in the hull owns the controls. The AI used
        // to keep ticking and push empty inputs into the vehicle every tick,
        // which zeroed the player's own keys one tick after they arrived and
        // made the vehicle refuse to move until it was re entered.
        if (carriesPlayer(vehicle)) {
            handOverControls(vehicle);
            return false;
        }

        SoldierEntity soldier = driverSoldier();
        if (soldier == null || !soldier.isAlive() || soldier.getVehicle() != vehicle) {
            Entity seat0 = SbwVehicles.driver(vehicle);
            if (seat0 instanceof SoldierEntity promoted
                && promoted.isAlive()
                && promoted.getRole() == SoldierRole.VEHICLE_CREW) {
                setDriverSoldier(promoted);
                soldier = promoted;
            } else {
                // Empty seat, or somebody who is not crew: release once, drop out.
                handOverControls(vehicle);
                return false;
            }
        }

        if (soldier.getRole() != SoldierRole.VEHICLE_CREW) {
            VehicleMountPolicy.enforceCrewDriverSeat(soldier, vehicle);
            handOverControls(vehicle);
            return false;
        }

        handedOver = false;
        SbwVehicles.ensureFuel(vehicle);
        // Weapons are not driven from here any more. SBW fires at the gunner's target
        // natively, and SbwVehicleGunnery keeps that target, the weapon choice and the
        // idle turret scan alive every tick, independent of any attack order.
        sampleDynamics(vehicle);

        // Reaching this point already proves the soldier is dedicated crew.
        if (soldier.hasValidPingMoveTarget()) {
            BlockPos target = soldier.getPingMoveTarget();
            if (mode != VehicleDriveMode.GO_TO || destination == null || !destination.equals(target)) {
                orderGoTo(target);
            }
        } else if (soldier.hasValidAttackTarget()) {
            BlockPos attackPos = soldier.getAttackTargetPos();
            Vec3 targetVec = Vec3.atCenterOf(attackPos);
            LivingEntity spottedTarget = soldier.getTarget();
            UUID spottedTargetId = spottedTarget != null && spottedTarget.isAlive()
                && !soldier.isFriendlyTo(spottedTarget) ? spottedTarget.getUUID() : null;
            if (mode != VehicleDriveMode.ATTACK || attackTargetPos == null
                || attackTargetPos.distanceToSqr(targetVec) > 4.0
                || !Objects.equals(attackTargetId, spottedTargetId)) {
                orderAttack(spottedTargetId, targetVec);
            }
        } else if (soldier.getSquadMode() == com.stevesarmy.squad.SquadMode.FOLLOW) {
            Entity owner = soldier.getOwner();
            if (owner != null && (mode != VehicleDriveMode.FOLLOW
                || (followTarget.get() != owner && followTarget.get() != null))) {
                orderFollow(owner);
            }
        } else if (soldier.getSquadMode() == com.stevesarmy.squad.SquadMode.HOLD) {
            if (mode != VehicleDriveMode.HOLD && mode != VehicleDriveMode.IDLE
                && mode != VehicleDriveMode.DISMOUNT) {
                orderHold();
            }
        }

        throttleCommanded = false;
        if (mode != VehicleDriveMode.DISMOUNT && mode != VehicleDriveMode.GO_TO
            && retreatFromUnreachableTarget(vehicle)) {
            trackStall(vehicle);
            return true;
        }
        switch (mode) {
            case FOLLOW -> tickFollow(vehicle);
            case GO_TO -> tickGoTo(vehicle);
            case ATTACK -> tickAttack(vehicle);
            case HOLD -> brake(vehicle);
            case DISMOUNT -> {
                releaseControls();
                if (isNearlyStopped(vehicle)) {
                    VehicleWreckHandler.ejectAll(vehicle);
                    return false;
                }
                brake(vehicle);
            }
            case IDLE -> releaseControls();
        }
        trackStall(vehicle);
        return true;
    }

    private static boolean carriesPlayer(Entity vehicle) {
        for (Entity passenger : vehicle.getPassengers()) {
            if (passenger instanceof Player) return true;
        }
        return false;
    }

    /**
     * Give the controls back to whoever sits in the hull, exactly once, and let
     * the registry drop this driver. Repeating the release every tick is what
     * used to brick a vehicle for a human driver.
     */
    private void handOverControls(Entity vehicle) {
        if (!handedOver) {
            handedOver = true;
            // Only zero the inputs when this driver actually put something there. A driver
            // that was just (re)registered for a hull a player is steering has written
            // nothing, and zeroing anyway wiped the player's keys, which also killed the
            // engine sound until the vehicle was re-entered.
            if (controlsDirty) {
                SbwVehicles.clearInputs(vehicle);
                controlsDirty = false;
            }
            VehicleWeaponController.forget(vehicle);
        }
        clearPath();
        closeRetreating = false;
        mode = VehicleDriveMode.IDLE;
    }

    // -- modes ----------------------------------------------------------------

    private void tickFollow(Entity vehicle) {
        Entity lead = followTarget.get();
        if (lead == null || !lead.isAlive()) {
            SoldierEntity soldier = driverSoldier();
            if (soldier != null && soldier.getOwner() != null) {
                Entity owner = soldier.getOwner();
                lead = owner.isPassenger() ? owner.getVehicle() : owner;
            }
        }
        if (lead == null) {
            brake(vehicle);
            return;
        }
        followTarget = new WeakReference<>(lead);

        // Whoever rides or stands on the hull is already exactly where we would drive to.
        // Chasing them (or backing off from them) is what made the tank slide away from a
        // player standing on it.
        if (lead == vehicle || lead.getVehicle() == vehicle || standingOnVehicle(lead, vehicle)) {
            clearPath();
            brake(vehicle);
            return;
        }

        Entity ahead = VehicleConvoy.vehicleAheadOf(vehicle, lead);
        Entity target = ahead != null ? ahead : lead;
        followTarget = new WeakReference<>(lead);

        double stopDistance = Math.max(FOLLOW_DISTANCE,
            VehicleConvoy.safeFollowDistance(vehicle, target));
        double gap = horizontalDistance(vehicle.position(), target.position());

        // Inside the dead band simply hold station: no path, no twitching, and never
        // reverse. Walking up to a tank must not make it drive away from you.
        if (gap <= stopDistance + FOLLOW_SLACK) {
            brake(vehicle);
            return;
        }
        driveAlong(vehicle, target.position(), stopDistance, gap > stopDistance + 14.0);
    }

    /** True for an entity standing on the roof or deck of the hull rather than riding in it. */
    private static boolean standingOnVehicle(Entity entity, Entity vehicle) {
        AABB hull = vehicle.getBoundingBox();
        return entity.getX() >= hull.minX - 0.5 && entity.getX() <= hull.maxX + 0.5
            && entity.getZ() >= hull.minZ - 0.5 && entity.getZ() <= hull.maxZ + 0.5
            && entity.getY() >= hull.minY + Math.min(1.0, hull.getYsize() * 0.3)
            && entity.getY() <= hull.maxY + 3.0;
    }

    private void tickGoTo(Entity vehicle) {
        if (destination == null) {
            mode = VehicleDriveMode.IDLE;
            releaseControls();
            return;
        }
        Vec3 goal = Vec3.atCenterOf(destination);
        boolean arrived = driveAlong(vehicle, goal, MOVE_STOP_RADIUS, true);
        if (arrived) arriveAtDestination(vehicle);
    }

    private void arriveAtDestination(Entity vehicle) {
        brake(vehicle);
        mode = VehicleDriveMode.HOLD;
        clearPath();
        SoldierEntity s = driverSoldier();
        if (s != null) s.completeGoToIfGeneration(s.getPingMoveGeneration());
    }

    private void tickAttack(Entity vehicle) {
        Vec3 targetPos = resolveAttackPosition(vehicle);
        if (targetPos == null) {
            mode = VehicleDriveMode.HOLD;
            clearAttack();
            clearPath();
            brake(vehicle);
            return;
        }
        double distance = horizontalDistance(vehicle.position(), targetPos);
        if (distance <= ATTACK_STANDOFF) {
            brake(vehicle);
            // Keep the hull pointed at the threat without driving into it.
            steer(vehicle, targetPos, false, false);
        } else {
            driveAlong(vehicle, targetPos, ATTACK_STANDOFF, distance > ATTACK_STANDOFF + 25.0);
        }
    }

    public Vec3 resolveAttackPosition(Entity vehicle) {
        if (attackTargetId != null && vehicle.level() instanceof ServerLevel level) {
            Entity target = level.getEntity(attackTargetId);
            if (target != null && target.isAlive()) {
                attackTargetPos = target.position();
                return attackTargetPos;
            }
            clearAttack();
            return null;
        }
        return attackTargetPos;
    }

    public UUID attackTargetId() {
        return attackTargetId;
    }

    // -- unreachable target handling -------------------------------------------

    /**
     * A turret cannot depress its barrel into an enemy that is right in front of the hull,
     * nor elevate it at one standing above it. Trying to aim through that dead zone just
     * leaves the gun idle while the enemy shoots, so the hull backs out of it instead.
     * Returns true while it owns the controls this tick.
     */
    private boolean retreatFromUnreachableTarget(Entity vehicle) {
        if (mode == VehicleDriveMode.FOLLOW) {
            Entity lead = followTarget.get();
            if (lead == null && driverSoldier() != null) lead = driverSoldier().getOwner();
            // Never shove off somebody who is riding or standing on the hull.
            if (lead != null && (lead.getVehicle() == vehicle || standingOnVehicle(lead, vehicle))) {
                closeRetreating = false;
                return false;
            }
        }
        if (SbwVehicles.kind(vehicle) != SbwVehicles.Kind.TRACKED) {
            closeRetreating = false;
            return false;
        }
        int turretSeat = SbwVehicles.turretControllerIndex(vehicle);
        Entity controller = SbwVehicles.passengerAt(vehicle, turretSeat);
        if (!(controller instanceof SoldierEntity gunner) || !SbwVehicles.seatHasWeapon(vehicle, turretSeat)) {
            closeRetreating = false;
            return false;
        }
        LivingEntity enemy = gunner.getTarget();
        if (enemy == null || !enemy.isAlive() || gunner.isFriendlyTo(enemy)
            || !gunner.isProactiveTarget(enemy) || enemy.getVehicle() == vehicle) {
            closeRetreating = false;
            return false;
        }

        Entity targetVehicle = enemy.getVehicle();
        Vec3 enemyPos = com.stevesarmy.compat.SbwCompat.isVehicle(targetVehicle)
            ? targetVehicle.getBoundingBox().getCenter() : enemy.getBoundingBox().getCenter();
        Vec3 muzzle = SbwVehicles.shootPos(vehicle, turretSeat);
        double separation = horizontalDistance(vehicle.position(), enemyPos);
        double minRange = Math.max(6.0, VehiclePathfinder.footprintRadius(vehicle) + 3.0);
        double muzzleDistance = horizontalDistance(muzzle, enemyPos);
        double elevation = Math.toDegrees(Math.atan2(enemyPos.y - muzzle.y, Math.max(0.1, muzzleDistance)));
        boolean outsidePitch = separation < 20.0
            && (elevation < SbwVehicles.turretMinPitch(vehicle) - 1.5
                || elevation > SbwVehicles.turretMaxPitch(vehicle) + 1.5);

        // Hysteresis: once backing off, keep going until there is a clear margin.
        if (separation >= minRange + (closeRetreating ? 3.0 : 0.0) && !outsidePitch) {
            if (closeRetreating) clearPath();
            closeRetreating = false;
            retreatReversing = false;
            return false;
        }

        if (!closeRetreating) {
            clearPath();
            retreatPathCooldown = 0;
            retreatReverseClear = false;
        }
        closeRetreating = true;

        Vec3 away = horizontal(vehicle.position().subtract(enemyPos));
        if (away.lengthSqr() < 0.01) away = horizontal(vehicle.getForward());
        if (away.lengthSqr() < 1.0E-4) return false;
        away = away.normalize();

        Vec3 forward = horizontal(vehicle.getForward());
        if (forward.lengthSqr() > 1.0E-4) {
            forward = forward.normalize();
            // Enemy ahead of the nose: reversing straight back is the quickest way out
            // and keeps the front armour toward it.
            if (forward.dot(away) < -0.4 && friendlyRoomAhead(vehicle, forward.scale(-1.0)) > 6.0) {
                if (--retreatPathCooldown <= 0) {
                    retreatPathCooldown = 12;
                    Vec3 back = vehicle.position().subtract(forward.scale(8.0));
                    retreatReverseClear = VehiclePathfinder.isClearLine(vehicle.level(), vehicle.position(),
                        back, VehiclePathfinder.footprintRadius(vehicle),
                        VehiclePathfinder.footprintHeight(vehicle));
                }
                if (retreatReverseClear) {
                    retreatReversing = true;
                    reverse(vehicle);
                    return true;
                }
            } else {
                retreatPathCooldown = 0;
            }
        }

        retreatReversing = false;
        Vec3 safePoint = vehicle.position().add(away.scale(12.0));
        driveAlong(vehicle, safePoint, 0.0, false);
        return true;
    }

    // -- driving --------------------------------------------------------------

    /**
     * Drive toward {@code goal}, coming to rest {@code stopShort} blocks before
     * it. Returns true once the hull is parked on that point.
     */
    private boolean driveAlong(Entity vehicle, Vec3 goal, double stopShort, boolean allowSprint) {
        if (SbwVehicles.isAirborne(vehicle)) {
            flyTo(vehicle, goal);
            return false;
        }
        if (reverseTicks > 0) {
            reverseTicks--;
            reverse(vehicle);
            return false;
        }

        ensurePath(vehicle, goal);

        double speed = vehicle.getDeltaMovement().horizontalDistance();
        double remaining = Math.max(0.0, pathRemaining(vehicle, goal) - stopShort);

        // Friendly vehicles in the lane become a closer stop point rather than an
        // on/off brake, which is what used to end in a rear end collision. Infantry
        // are deliberately not part of this: they are steered around, never waited for.
        double friendlyRoom = friendlyRoomAhead(vehicle, horizontal(vehicle.getForward()));
        if (friendlyRoom < remaining) remaining = friendlyRoom;

        if (remaining <= 0.25) {
            brake(vehicle);
            return speed <= STOPPED_SPEED;
        }

        Vec3 aim = lookAheadPoint(vehicle, goal, speed);
        double stopDistance = stoppingDistance(speed);

        // Corner handling: solve the distance needed to shed speed down to the
        // corner limit instead of stopping dead in front of every turn.
        double cornerLimit = cornerSpeedLimit(vehicle);
        boolean cornerBrake = speed > cornerLimit
            && distanceToCorner(vehicle) <= distanceToShed(speed, cornerLimit);

        boolean brakeZone = remaining <= stopDistance;
        boolean coastZone = !brakeZone && remaining <= stopDistance * 1.4 + 1.5;

        if (brakeZone) {
            // Steer while braking so the nose stays on the route.
            steer(vehicle, aim, false, false);
            brake(vehicle);
            return false;
        }
        if (cornerBrake) {
            steer(vehicle, aim, false, false);
            brake(vehicle);
            return false;
        }
        boolean sprint = allowSprint && !coastZone
            && remaining > stopDistance * 2.2 + 14.0;
        steer(vehicle, aim, !coastZone, sprint);
        return false;
    }

    private void flyTo(Entity vehicle, Vec3 goal) {
        controlsDirty = true;
        if (!steer(vehicle, goal, true, true)) {
            SbwVehicles.up(vehicle, false);
            SbwVehicles.down(vehicle, false);
            return;
        }
        double targetY = goal.y + 18.0;
        double dy = targetY - vehicle.getY();
        SbwVehicles.up(vehicle, dy > 1.5);
        SbwVehicles.down(vehicle, dy < -2.5);
    }

    /** Applies throttle and steering toward a point. Returns false when parked. */
    private boolean steer(Entity vehicle, Vec3 target, boolean accelerate, boolean allowSprint) {
        SbwVehicles.ensureFuel(vehicle);
        Vec3 delta = horizontal(target.subtract(vehicle.position()));
        double distance = delta.length();
        if (distance < 0.15) {
            brake(vehicle);
            return false;
        }
        Vec3 direction = delta.scale(1.0 / distance);

        float desiredYaw = (float) Math.toDegrees(Math.atan2(-direction.x, direction.z));
        float currentYaw = vehicle.getYRot();
        float error = Mth.wrapDegrees(desiredYaw - currentYaw);

        // Local avoidance: if the hull corridor is pinched on one side, bias the
        // steering away from it. Small and continuous, so it reads as a driver
        // easing around a rock rather than a new goal being chosen.
        error += avoidanceBias(vehicle, direction, (float) Math.abs(error));
        error += infantryBias(vehicle, direction, (float) Math.abs(error));
        error = Mth.wrapDegrees(error);

        boolean turnLeft = error < -STEER_DEADZONE_DEGREES;
        boolean turnRight = error > STEER_DEADZONE_DEGREES;
        boolean sharpTurn = Math.abs(error) > HARD_TURN_DEGREES;
        boolean forward = accelerate && !sharpTurn;
        boolean sprint = allowSprint && forward && Math.abs(error) < 9.0f && distance > 8.0;

        SbwVehicles.updateInputs(vehicle, forward, false, turnLeft, turnRight, sprint);
        controlsDirty = true;
        throttleCommanded = forward || sprint;
        brakedLastTick = false;

        applyYawAssist(vehicle, currentYaw, error);
        return true;
    }

    /**
     * SBW turns the hull from its own input handling. Only help when the native
     * yaw rate is not reacting, which happens when a tracked hull is pivoting
     * from standstill, and keep the nudge small so nothing jitters.
     */
    private void applyYawAssist(Entity vehicle, float currentYaw, float error) {
        float yawRate = Math.abs(Mth.wrapDegrees(currentYaw - previousYaw));
        previousYaw = currentYaw;
        if (Math.abs(error) < 8.0f) return;
        if (yawRate > 0.35f) return;
        float step = Mth.clamp(error * 0.18f, -MAX_YAW_ASSIST, MAX_YAW_ASSIST);
        vehicle.setYRot(Mth.wrapDegrees(currentYaw + step));
    }

    private void brake(Entity vehicle) {
        Vec3 delta = vehicle.getDeltaMovement();
        double along = delta.dot(vehicle.getForward());
        boolean movingForward = along > 0.04;
        boolean movingBack = along < -0.04;
        SbwVehicles.updateInputs(vehicle, movingBack, movingForward, false, false, false);
        controlsDirty = true;
        throttleCommanded = false;
        brakedLastTick = movingForward || movingBack;
    }

    private void reverse(Entity vehicle) {
        SbwVehicles.updateInputs(vehicle, false, true, false, false, false);
        controlsDirty = true;
        throttleCommanded = true;
        brakedLastTick = false;
    }

    private void releaseControls() {
        Entity vehicle = vehicle();
        if (vehicle != null) {
            SbwVehicles.clearInputs(vehicle);
            controlsDirty = true;
        }
        throttleCommanded = false;
    }

    // -- stopping distance model ---------------------------------------------

    /**
     * Learn how hard this hull actually brakes. SBW speed scales differ wildly
     * between a jeep and a tank, and a fixed coefficient is exactly why the
     * vehicle parked perfectly when crawling and overran the point at speed.
     */
    private void sampleDynamics(Entity vehicle) {
        double speed = vehicle.getDeltaMovement().horizontalDistance();
        if (brakedLastTick && previousSpeed > 0.08) {
            double dv = previousSpeed - speed;
            if (dv > 0.0004) {
                decelEstimate = Mth.clamp(decelEstimate * 0.88 + dv * 0.12, MIN_DECEL, MAX_DECEL);
            }
        }
        previousSpeed = speed;
    }

    private double stoppingDistance(double speed) {
        double v = Math.max(0.0, speed);
        return v * REACTION_TICKS + (v * v) / (2.0 * decelEstimate);
    }

    /** Distance needed to bleed from {@code from} down to {@code to}. */
    private double distanceToShed(double from, double to) {
        if (from <= to) return 0.0;
        return (from * from - to * to) / (2.0 * decelEstimate) + from * REACTION_TICKS;
    }

    // -- route management -----------------------------------------------------

    private void ensurePath(Entity vehicle, Vec3 goal) {
        pathAge++;
        if (replanCooldown > 0) replanCooldown--;

        boolean needsPath = path.isEmpty()
            || pathGoal == null
            || pathGoal.distanceToSqr(goal) > PATH_GOAL_DRIFT * PATH_GOAL_DRIFT
            || pathAge > PATH_MAX_AGE_TICKS
            || !nextLegClear(vehicle);

        if (!needsPath || replanCooldown > 0) {
            trimPath(vehicle);
            return;
        }

        double halfWidth = VehiclePathfinder.footprintRadius(vehicle);
        double height = VehiclePathfinder.footprintHeight(vehicle);
        List<Vec3> fresh = VehiclePathfinder.route(vehicle.level(), vehicle.position(), goal,
            halfWidth, height);
        replanCooldown = PATH_MIN_REPLAN_TICKS;
        pathAge = 0;
        if (fresh.isEmpty()) {
            // Never freeze on a failed search: keep the old route if it still
            // exists, otherwise push straight at the goal and let the local
            // avoidance and the stuck handler sort the rest out.
            replanCooldown = PATH_FAIL_BACKOFF_TICKS;
            if (path.isEmpty()) {
                path = new ArrayList<>(List.of(goal));
                pathGoal = goal;
            }
            return;
        }
        path = fresh;
        pathGoal = goal;
        trimPath(vehicle);
    }

    /** Drop points the hull has already driven past. */
    private void trimPath(Entity vehicle) {
        Vec3 position = vehicle.position();
        double radius = VehiclePathfinder.footprintRadius(vehicle) + 1.0;
        while (path.size() > 1) {
            Vec3 first = path.get(0);
            double d = horizontalDistance(position, first);
            if (d > radius) {
                // Also drop it when the hull is already beyond it on the route.
                Vec3 toNext = horizontal(path.get(1).subtract(first));
                Vec3 toSelf = horizontal(position.subtract(first));
                if (toNext.lengthSqr() < 1.0E-4 || toSelf.dot(toNext) <= 0.0) break;
                if (toSelf.dot(toNext.normalize()) < toNext.length() * 0.25) break;
            }
            path.remove(0);
        }
    }

    private boolean nextLegClear(Entity vehicle) {
        if (path.isEmpty()) return false;
        double halfWidth = VehiclePathfinder.footprintRadius(vehicle);
        double height = VehiclePathfinder.footprintHeight(vehicle);
        Vec3 from = vehicle.position();
        int checked = 0;
        for (Vec3 point : path) {
            if (!VehiclePathfinder.isClearLine(vehicle.level(), from, point, halfWidth, height)) {
                return false;
            }
            from = point;
            if (++checked >= 2) break;
        }
        return true;
    }

    private double pathRemaining(Entity vehicle, Vec3 goal) {
        if (path.isEmpty()) return horizontalDistance(vehicle.position(), goal);
        double total = horizontalDistance(vehicle.position(), path.get(0));
        for (int i = 1; i < path.size(); i++) {
            total += horizontalDistance(path.get(i - 1), path.get(i));
        }
        return total;
    }

    /**
     * Pure pursuit: pick the point on the route a look ahead distance away and
     * aim at that. Long look ahead at speed keeps the line flowing; short look
     * ahead when crawling keeps precision at the stop point.
     */
    private Vec3 lookAheadPoint(Entity vehicle, Vec3 goal, double speed) {
        if (path.isEmpty()) return goal;
        double lookAhead = Mth.clamp(3.5 + speed * 16.0, 3.5, 18.0);
        Vec3 previous = vehicle.position();
        double travelled = 0.0;
        for (Vec3 point : path) {
            double leg = horizontalDistance(previous, point);
            if (travelled + leg >= lookAhead) {
                double t = leg < 1.0E-4 ? 1.0 : (lookAhead - travelled) / leg;
                return previous.add(point.subtract(previous).scale(Mth.clamp(t, 0.0, 1.0)));
            }
            travelled += leg;
            previous = point;
        }
        return previous;
    }

    private double distanceToCorner(Entity vehicle) {
        if (path.size() < 2) return Double.MAX_VALUE;
        return horizontalDistance(vehicle.position(), path.get(0));
    }

    /** Speed cap for the next vertex, from the angle the route turns there. */
    private double cornerSpeedLimit(Entity vehicle) {
        if (path.size() < 2) return Double.MAX_VALUE;
        Vec3 corner = path.get(0);
        Vec3 incoming = horizontal(corner.subtract(vehicle.position()));
        Vec3 outgoing = horizontal(path.get(1).subtract(corner));
        if (incoming.lengthSqr() < 1.0E-4 || outgoing.lengthSqr() < 1.0E-4) return Double.MAX_VALUE;
        double cosine = Mth.clamp(incoming.normalize().dot(outgoing.normalize()), -1.0, 1.0);
        double turn = Math.toDegrees(Math.acos(cosine));
        if (turn < 25.0) return Double.MAX_VALUE;
        if (turn < 50.0) return 0.75;
        if (turn < 85.0) return 0.40;
        return 0.22;
    }

    // -- obstacles ------------------------------------------------------------

    /**
     * Blocks of clear road before the nearest friendly <em>vehicle</em> in the given
     * direction. Returned as a distance so the speed controller can ease off instead of
     * stamping the brake. Infantry are excluded on purpose: they cannot be hurt by the
     * hull (friendly fire is off) and waiting for them to leave the lane deadlocked tanks
     * in crowded squads.
     */
    private double friendlyRoomAhead(Entity vehicle, Vec3 direction) {
        SoldierEntity soldier = driverSoldier();
        if (soldier == null) return Double.MAX_VALUE;
        if (direction.lengthSqr() < 1.0E-4) return Double.MAX_VALUE;
        direction = direction.normalize();

        double speed = vehicle.getDeltaMovement().horizontalDistance();
        double lookAhead = Math.min(56.0, vehicle.getBbWidth() + stoppingDistance(speed) + 10.0);
        AABB corridor = vehicle.getBoundingBox()
            .expandTowards(direction.scale(lookAhead)).inflate(0.75, 2.0, 0.75);
        Vec3 origin = vehicle.getBoundingBox().getCenter();
        Vec3 lateralAxis = new Vec3(-direction.z, 0.0, direction.x);

        double closest = Double.MAX_VALUE;
        for (Entity candidate : vehicle.level().getEntities(vehicle, corridor)) {
            if (candidate == vehicle || candidate == soldier || candidate.getVehicle() == vehicle) continue;
            if (!isFriendlyVehicle(soldier, candidate)) continue;

            Vec3 offset = horizontal(candidate.getBoundingBox().getCenter().subtract(origin));
            double along = offset.dot(direction);
            if (along <= 0.0 || along > lookAhead) continue;
            if (Math.abs(candidate.getY() - vehicle.getY()) > 4.0) continue;

            double clearance = (vehicle.getBbWidth() + candidate.getBbWidth()) / 2.0 + 1.5;
            if (Math.abs(offset.dot(lateralAxis)) > clearance) continue;

            closest = Math.min(closest, along - clearance - 1.5);
        }
        return Math.max(0.0, closest);
    }

    /**
     * Gentle steering offset (degrees, positive = turn right) away from the nearest
     * friendly infantry in the lane. It never brakes and never blocks: if the soldier does
     * not move the hull just rolls on, which is acceptable because friendly fire is off.
     */
    private float infantryBias(Entity vehicle, Vec3 direction, float currentError) {
        if (currentError > 45.0f) return 0.0f;
        SoldierEntity soldier = driverSoldier();
        if (soldier == null) return 0.0f;

        double speed = vehicle.getDeltaMovement().horizontalDistance();
        double reach = Mth.clamp(6.0 + speed * 20.0, 6.0, 16.0);
        AABB hull = vehicle.getBoundingBox();
        AABB corridor = hull.expandTowards(direction.scale(reach)).inflate(1.0, 1.5, 1.0);
        Vec3 origin = hull.getCenter();
        // yaw + 90 degrees: positive offsets are on the hull's right.
        Vec3 rightAxis = new Vec3(-direction.z, 0.0, direction.x);

        double nearest = Double.MAX_VALUE;
        float bias = 0.0f;
        for (Entity candidate : vehicle.level().getEntities(vehicle, corridor)) {
            if (!(candidate instanceof LivingEntity living) || candidate == soldier
                || candidate.getVehicle() == vehicle || !soldier.isFriendlyTo(living)) continue;
            if (standingOnVehicle(candidate, vehicle)) continue;

            Vec3 offset = horizontal(candidate.getBoundingBox().getCenter().subtract(origin));
            double along = offset.dot(direction);
            if (along <= 0.0 || along > reach || along >= nearest) continue;
            if (Math.abs(candidate.getY() - vehicle.getY()) > 3.0) continue;

            double lateral = offset.dot(rightAxis);
            double clearance = vehicle.getBbWidth() / 2.0 + candidate.getBbWidth() / 2.0 + 0.75;
            if (Math.abs(lateral) > clearance) continue;

            nearest = along;
            double closeness = 1.0 - along / reach;
            float magnitude = (float) (10.0 + 14.0 * closeness);
            boolean passOnLeft = Math.abs(lateral) < 0.3
                ? vehicle.getId() % 2 == 0 : lateral > 0.0;
            bias = passOnLeft ? -magnitude : magnitude;
        }
        return bias;
    }

    /**
     * Three whisker probes. Returns a steering offset in degrees away from the
     * blocked side, scaled down when the hull is already turning hard.
     */
    private float avoidanceBias(Entity vehicle, Vec3 direction, float currentError) {
        if (currentError > 45.0f) return 0.0f;
        double halfWidth = VehiclePathfinder.footprintRadius(vehicle);
        double height = VehiclePathfinder.footprintHeight(vehicle);
        double speed = vehicle.getDeltaMovement().horizontalDistance();
        double reach = Mth.clamp(4.0 + speed * 14.0, 4.0, 14.0);
        Vec3 origin = vehicle.position();

        boolean left = !clearProbe(vehicle, origin, rotate(direction, 26.0), reach, halfWidth, height);
        boolean right = !clearProbe(vehicle, origin, rotate(direction, -26.0), reach, halfWidth, height);
        boolean centre = !clearProbe(vehicle, origin, direction, reach, halfWidth, height);

        float bias = 0.0f;
        if (left && !right) bias += 14.0f;
        if (right && !left) bias -= 14.0f;
        if (centre && !left && !right) bias += (vehicle.getId() % 2 == 0) ? 18.0f : -18.0f;
        return bias;
    }

    private static boolean clearProbe(Entity vehicle, Vec3 origin, Vec3 direction, double reach,
                                      double halfWidth, double height) {
        Vec3 end = origin.add(direction.scale(reach));
        return VehiclePathfinder.isClearLine(vehicle.level(), origin, end, halfWidth, height);
    }

    private static Vec3 rotate(Vec3 direction, double degrees) {
        double r = Math.toRadians(degrees);
        double cos = Math.cos(r);
        double sin = Math.sin(r);
        return new Vec3(direction.x * cos - direction.z * sin, 0.0,
            direction.x * sin + direction.z * cos).normalize();
    }

    private static boolean isFriendlyVehicle(SoldierEntity observer, Entity candidate) {
        if (!com.stevesarmy.compat.sbw.SbwCompat.isVehicle(candidate)) return false;
        if (com.stevesarmy.compat.SbwCompat.hasFriendlyOccupant(observer, candidate)) {
            return true;
        }
        VehicleDriver driver = VehicleDriverRegistry.get(candidate);
        SoldierEntity otherDriver = driver != null ? driver.driverSoldier() : null;
        return otherDriver != null && observer.isFriendlyTo(otherDriver);
    }

    // -- stuck handling -------------------------------------------------------

    private void trackStall(Entity vehicle) {
        if (!throttleCommanded) {
            stallTicks = 0;
            return;
        }
        if (vehicle.getDeltaMovement().horizontalDistance() > 0.045) {
            stallTicks = 0;
            return;
        }
        if (++stallTicks >= STUCK_TRIGGER_TICKS) {
            stallTicks = 0;
            if (closeRetreating && retreatReversing) {
                // Blocked while backing away: flee forwards instead of backing further.
                retreatReverseClear = false;
                retreatPathCooldown = 40;
                retreatReversing = false;
                return;
            }
            reverseTicks = REVERSE_TICKS;
            replanCooldown = 0;
            pathAge = PATH_MAX_AGE_TICKS + 1;
            StevesArmyMod.LOGGER.debug("[SBW] vehicle {} stalled, backing up and replanning", vehicle.getId());
        }
    }

    private boolean isNearlyStopped(Entity vehicle) {
        return vehicle.getDeltaMovement().horizontalDistanceSqr() < 0.01;
    }

    private static double horizontalDistance(Vec3 first, Vec3 second) {
        return horizontal(first.subtract(second)).length();
    }

    private static Vec3 horizontal(Vec3 value) {
        return new Vec3(value.x, 0.0, value.z);
    }
}

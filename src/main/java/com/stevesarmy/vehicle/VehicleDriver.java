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
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;
import java.util.UUID;

/**
 * Server-side driver for one SBW vehicle. Only dedicated vehicle crew may
 * occupy the driver seat and operate the vehicle.
 *
 * Uses native SBW inputs so acceleration, speed, and handling match the original mod.
 */
public final class VehicleDriver {

    private static final double FOLLOW_DISTANCE = 14.0;
    private static final double BRAKE_DISTANCE = 12.0;
    private static final double MOVE_SLOW_APPROACH_DISTANCE = 10.0;
    private static final double MOVE_STOP_RADIUS = 3.0;
    private static final double APPROACH_DECELERATION = 0.02;
    private static final double SPEED_CONTROL_MARGIN = 0.08;
    private static final double INERTIA_BRAKE_FACTOR = 8.0;
    private static final double WAYPOINT_TOLERANCE = 1.0;
    private static final float STEER_DEADZONE_DEGREES = 5.0f;
    private static final float HARD_TURN_DEGREES = 75.0f;

    private static final int REPATH_INTERVAL_TICKS = 20;
    private static final int STUCK_CHECK_TICKS = 30;
    private static final double STUCK_MIN_PROGRESS = 1.0;

    private final WeakReference<Entity> vehicleRef;
    private WeakReference<SoldierEntity> driverRef = new WeakReference<>(null);

    private VehicleDriveMode mode = VehicleDriveMode.IDLE;

    private WeakReference<Entity> followTarget = new WeakReference<>(null);
    private BlockPos destination;
    private UUID attackTargetId;
    private Vec3 attackTargetPos;

    private Deque<Vec3> waypoints = new ArrayDeque<>();
    private BlockPos plannedGoal;
    private int repathCooldown = 0;

    private Vec3 lastStuckSample = Vec3.ZERO;
    private int stuckTimer = 0;
    private int reverseTicks = 0;

    VehicleDriver(Entity vehicle) {
        this.vehicleRef = new WeakReference<>(vehicle);
        this.lastStuckSample = vehicle.position();
    }

    public Entity vehicle() {
        return vehicleRef.get();
    }

    public SoldierEntity driverSoldier() {
        return driverRef.get();
    }

    public void setDriverSoldier(SoldierEntity soldier) {
        this.driverRef = new WeakReference<>(soldier);
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
        followTarget = new WeakReference<>(null);
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
        clearAttack();
        clearPath();
        releaseControls();
    }

    public void clearAttack() {
        this.attackTargetId = null;
        this.attackTargetPos = null;
    }

    private void clearPath() {
        waypoints.clear();
        plannedGoal = null;
        repathCooldown = 0;
    }

    public boolean tick() {
        Entity vehicle = vehicle();
        if (vehicle == null || vehicle.isRemoved()) return false;
        if (vehicle.level().isClientSide) return true;

        if (SbwVehicles.isWrecked(vehicle)) {
            VehicleWreckHandler.ejectAll(vehicle);
            return false;
        }

        SoldierEntity soldier = driverSoldier();
        if (soldier == null || !soldier.isAlive() || soldier.getVehicle() != vehicle
            || !SbwVehicles.isDriver(vehicle, soldier)) {
            Entity seat0 = SbwVehicles.driver(vehicle);
            if (seat0 instanceof SoldierEntity promoted) {
                setDriverSoldier(promoted);
                soldier = promoted;
            } else {
                // Empty seats must not inherit stale AI throttle. A player in
                // seat 0 owns SBW's controls; clearing them here every tick
                // fights the player's inputs and leaves the vehicle stuck.
                if (seat0 == null || !(seat0 instanceof Player)) releaseControls();
                return false;
            }
        }

        if (soldier != null && soldier.getRole() != SoldierRole.VEHICLE_CREW) {
            VehicleMountPolicy.enforceCrewDriverSeat(soldier, vehicle);
            releaseControls();
            return false;
        }

        SbwVehicles.ensureFuel(vehicle);
        VehicleWeaponController.tick(this, vehicle);

        if (soldier != null && soldier.isAlive()) {
            boolean dedicatedCrew = soldier.getRole() == SoldierRole.VEHICLE_CREW;
            // Only dedicated vehicle crew can occupy and operate the driver seat.
            // Their move ping sends the vehicle; ordinary infantry remain taxi riders.
            if (dedicatedCrew && soldier.hasValidPingMoveTarget()) {
                BlockPos target = soldier.getPingMoveTarget();
                if (mode != VehicleDriveMode.GO_TO || destination == null || !destination.equals(target)) {
                    orderGoTo(target);
                }
            } else if (dedicatedCrew && soldier.hasValidAttackTarget()) {
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
            } else if (dedicatedCrew && soldier.getSquadMode() == com.stevesarmy.squad.SquadMode.FOLLOW) {
                Entity owner = soldier.getOwner();
                if (owner != null && (mode != VehicleDriveMode.FOLLOW || (followTarget.get() != owner && followTarget.get() != null))) {
                    orderFollow(owner);
                }
            } else if (dedicatedCrew && soldier.getSquadMode() == com.stevesarmy.squad.SquadMode.HOLD) {
                if (mode != VehicleDriveMode.HOLD && mode != VehicleDriveMode.IDLE && mode != VehicleDriveMode.DISMOUNT) {
                    orderHold();
                }
            }
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
        return true;
    }

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

        Entity ahead = VehicleConvoy.vehicleAheadOf(vehicle, lead);
        Entity target = ahead != null ? ahead : lead;
        followTarget = new WeakReference<>(lead);

        double distance = horizontalDistance(vehicle.position(), target.position());
        double stopDistance = Math.max(BRAKE_DISTANCE,
            VehicleConvoy.safeFollowDistance(vehicle, target));
        Vec3 leadDirection = horizontal(target.getDeltaMovement());
        if (leadDirection.lengthSqr() < 0.01) leadDirection = horizontal(target.getForward());
        if (leadDirection.lengthSqr() < 0.01) {
            leadDirection = horizontal(target.position().subtract(vehicle.position()));
        }
        if (leadDirection.lengthSqr() < 0.01) {
            brake(vehicle);
            return;
        }
        Vec3 followPoint = target.position().subtract(leadDirection.normalize().scale(stopDistance));
        double gap = horizontalDistance(vehicle.position(), followPoint);
        Vec3 towardTarget = horizontal(target.position().subtract(vehicle.position())).normalize();
        double leadSpeed = horizontal(target.getDeltaMovement()).dot(towardTarget);
        double desiredSpeed = Math.max(0.0, leadSpeed) + approachSpeed(gap);
        double speed = vehicle.getDeltaMovement().horizontalDistance();
        boolean accelerate = speed <= desiredSpeed + SPEED_CONTROL_MARGIN;
        if (gap <= 0.75 && Math.abs(speed - Math.max(0.0, leadSpeed)) <= 0.1) {
            SbwVehicles.updateInputs(vehicle, false, false, false, false, false);
            return;
        }
        driveTo(vehicle, BlockPos.containing(followPoint), followPoint,
            accelerate, accelerate && gap > FOLLOW_DISTANCE);
    }

    private void tickGoTo(Entity vehicle) {
        if (destination == null) {
            mode = VehicleDriveMode.IDLE;
            releaseControls();
            return;
        }
        Vec3 goal = Vec3.atCenterOf(destination);
        double distance = horizontalDistance(vehicle.position(), goal);
        double speed = vehicle.getDeltaMovement().horizontalDistance();
        if (distance <= MOVE_STOP_RADIUS && speed <= 0.1) {
            arriveAtDestination(vehicle);
            return;
        }
        double desiredSpeed = approachSpeed(Math.max(0.0, distance - MOVE_STOP_RADIUS));
        boolean accelerate = speed <= desiredSpeed + SPEED_CONTROL_MARGIN;
        driveTo(vehicle, destination, goal, accelerate,
            accelerate && distance > MOVE_SLOW_APPROACH_DISTANCE * 2.0);
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
        double distance = vehicle.position().distanceTo(targetPos);
        double standoff = 24.0;
        if (distance <= standoff) {
            brake(vehicle);
            steerToward(vehicle, targetPos, false);
        } else {
            driveTo(vehicle, BlockPos.containing(targetPos), targetPos);
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

    private void driveTo(Entity vehicle, BlockPos goalPos, Vec3 goalVec) {
        driveTo(vehicle, goalPos, goalVec, true);
    }

    private void driveTo(Entity vehicle, BlockPos goalPos, Vec3 goalVec, boolean allowSprint) {
        driveTo(vehicle, goalPos, goalVec, true, allowSprint);
    }

    private void driveTo(Entity vehicle, BlockPos goalPos, Vec3 goalVec,
                         boolean accelerate, boolean allowSprint) {
        if (SbwVehicles.isAirborne(vehicle)) {
            flyTo(vehicle, goalVec);
            return;
        }

        boolean movingGoal = plannedGoal != null && horizontalDistance(plannedGoal, goalPos) > 4.0;
        if (repathCooldown-- <= 0 || waypoints.isEmpty() || movingGoal) {
            repathCooldown = movingGoal ? 10 : REPATH_INTERVAL_TICKS;
            double halfWidth = VehiclePathfinder.footprintRadius(vehicle);
            double height = VehiclePathfinder.footprintHeight(vehicle);
            Deque<Vec3> planned = VehiclePathfinder.findPath(
                vehicle.level(), vehicle.blockPosition(), goalPos, goalVec, halfWidth, height);
            plannedGoal = goalPos;
            if (!planned.isEmpty()) {
                waypoints = planned;
            } else if (waypoints.isEmpty()) {
                repathCooldown = 10;
                brake(vehicle);
                return;
            }
        }

        Vec3 waypoint = waypoints.peek();
        while (waypoint != null) {
            double tolerance = waypoints.size() == 1 ? 1.25 : WAYPOINT_TOLERANCE;
            if (horizontalDistance(vehicle.position(), waypoint) > tolerance
                && !hasPassedWaypoint(vehicle, waypoint)) break;
            waypoints.poll();
            waypoint = waypoints.peek();
        }
        if (waypoint == null) {
            brake(vehicle);
            return;
        }

        if (shouldBrakeForCorner(vehicle, waypoint)) {
            brake(vehicle);
            return;
        }

        detectAndHandleStuck(vehicle);
        if (reverseTicks > 0) {
            Vec3 reverse = horizontal(vehicle.getForward()).scale(-1.0);
            if (shouldBrakeForFriendly(vehicle, reverse)) {
                reverseTicks = 0;
                brake(vehicle);
                return;
            }
            reverseTicks--;
            SbwVehicles.updateInputs(vehicle, false, true, false, false, false);
            return;
        }

        steerToward(vehicle, waypoint, accelerate, allowSprint);
    }

    private void flyTo(Entity vehicle, Vec3 goal) {
        if (!steerToward(vehicle, goal, true, true)) {
            SbwVehicles.up(vehicle, false);
            SbwVehicles.down(vehicle, false);
            return;
        }
        double targetY = goal.y + 18.0;
        double dy = targetY - vehicle.getY();
        SbwVehicles.up(vehicle, dy > 1.5);
        SbwVehicles.down(vehicle, dy < -2.5);
    }

    private boolean steerToward(Entity vehicle, Vec3 target, boolean accelerate) {
        return steerToward(vehicle, target, accelerate, accelerate);
    }

    private boolean steerToward(Entity vehicle, Vec3 target, boolean accelerate,
                                boolean allowSprint) {
        SbwVehicles.ensureFuel(vehicle);
        Vec3 delta = target.subtract(vehicle.position()).multiply(1, 0, 1);
        double distance = delta.length();
        if (distance < 0.1) {
            brake(vehicle);
            return false;
        }

        Vec3 driveDirection = delta.scale(1.0 / distance);
        if (shouldBrakeForFriendly(vehicle, driveDirection)) {
            brake(vehicle);
            return false;
        }

        double desiredYaw = Math.toDegrees(Math.atan2(-delta.x, delta.z));
        float currentYaw = vehicle.getYRot();
        float error = wrapDegrees((float) desiredYaw - currentYaw);

        boolean turnLeft = error < -STEER_DEADZONE_DEGREES;
        boolean turnRight = error > STEER_DEADZONE_DEGREES;
        boolean sharpTurn = Math.abs(error) > HARD_TURN_DEGREES;
        boolean forward = accelerate && !sharpTurn;
        boolean sprint = allowSprint && forward && Math.abs(error) < 10.0f
            && distance > 6.0;

        Vec3 velocity = vehicle.getDeltaMovement();
        double forwardSpeed = velocity.dot(vehicle.getForward());
        boolean counterForward = !forward && forwardSpeed < -0.05;
        boolean counterBack = !forward && forwardSpeed > 0.05;

        // Native SBW acceleration/speed integration
        SbwVehicles.updateInputs(vehicle, forward || counterForward, counterBack,
            turnLeft, turnRight, sprint);

        float maxTurn = 3.5f;
        float step = Mth.clamp(error * 0.25f, -maxTurn, maxTurn);
        vehicle.setYRot(currentYaw + step);
        return true;
    }

    /** Brake for allied people and allied vehicles in the vehicle's projected path. */
    private boolean shouldBrakeForFriendly(Entity vehicle, Vec3 direction) {
        SoldierEntity soldier = driverSoldier();
        if (soldier == null || direction.lengthSqr() < 0.01) return false;

        double speed = vehicle.getDeltaMovement().horizontalDistance();
        double brakingTravel = brakingTravel(speed);
        double lookAhead = Math.min(48.0, vehicle.getBbWidth() + brakingTravel + 8.0);
        Vec3 travel = direction.scale(lookAhead);
        AABB corridor = vehicle.getBoundingBox().expandTowards(travel).inflate(0.75, 1.5, 0.75);
        Vec3 origin = vehicle.getBoundingBox().getCenter();
        Vec3 lateralAxis = new Vec3(-direction.z, 0.0, direction.x);

        for (Entity candidate : vehicle.level().getEntities(vehicle, corridor)) {
            if (candidate == vehicle || candidate == soldier || candidate.getVehicle() == vehicle) continue;
            if (!isFriendlyObstacle(soldier, candidate)) continue;

            Vec3 offset = candidate.getBoundingBox().getCenter().subtract(origin)
                .multiply(1.0, 0.0, 1.0);
            double along = offset.dot(direction);
            if (along <= 0.0 || along > lookAhead) continue;
            double verticalGap = Math.abs(candidate.getY() - vehicle.getY());
            if (verticalGap > 4.0) continue;

            double lateral = Math.abs(offset.dot(lateralAxis));
            double clearance = (vehicle.getBbWidth() + candidate.getBbWidth()) / 2.0 + 1.5;
            if (lateral > clearance) continue;

            // Include a speed-based braking margin so the hull stops before the
            // friendly entity, then remains stopped until that lane is clear.
            double brakingGap = clearance + Math.max(3.0, brakingTravel);
            if (along <= brakingGap) return true;
        }
        return false;
    }

    private boolean shouldBrakeForCorner(Entity vehicle, Vec3 waypoint) {
        if (waypoints.size() < 2) return false;
        var iterator = waypoints.iterator();
        iterator.next();
        if (!iterator.hasNext()) return false;
        Vec3 next = iterator.next();

        Vec3 incoming = horizontal(waypoint.subtract(vehicle.position()));
        Vec3 outgoing = horizontal(next.subtract(waypoint));
        if (incoming.lengthSqr() < 0.01 || outgoing.lengthSqr() < 0.01) return false;
        double cosine = Mth.clamp(incoming.normalize().dot(outgoing.normalize()), -1.0, 1.0);
        double turnAngle = Math.toDegrees(Math.acos(cosine));
        if (turnAngle < 80.0) return false;

        double speed = vehicle.getDeltaMovement().horizontalDistance();
        double brakingDistance = VehiclePathfinder.footprintRadius(vehicle) + 2.0
            + brakingTravel(speed);
        return speed > 0.1 && horizontalDistance(vehicle.position(), waypoint) <= brakingDistance;
    }

    /**
     * Approximate travel needed for counter-input braking. The linear term
     * covers input and engine response; the quadratic term accounts for the
     * extra momentum carried at high SBW speed rates.
     */
    private static double brakingTravel(double speed) {
        double horizontalSpeed = Math.max(0.0, speed);
        return horizontalSpeed * 3.0
            + horizontalSpeed * horizontalSpeed * INERTIA_BRAKE_FACTOR;
    }

    /** Desired speed for a smooth stop within the remaining distance. */
    private static double approachSpeed(double distance) {
        return Math.sqrt(2.0 * APPROACH_DECELERATION * Math.max(0.0, distance));
    }

    private static boolean hasPassedWaypoint(Entity vehicle, Vec3 waypoint) {
        Vec3 toWaypoint = horizontal(waypoint.subtract(vehicle.position()));
        Vec3 velocity = horizontal(vehicle.getDeltaMovement());
        return velocity.lengthSqr() > 0.04 && toWaypoint.lengthSqr() <= 36.0
            && velocity.dot(toWaypoint) <= 0.0;
    }

    private static boolean isFriendlyObstacle(SoldierEntity observer, Entity candidate) {
        if (candidate instanceof LivingEntity living) {
            return observer.isFriendlyTo(living);
        }
        if (com.stevesarmy.compat.sbw.SbwCompat.isVehicle(candidate)) {
            if (com.stevesarmy.compat.SbwCompat.hasFriendlyOccupant(observer, candidate)) {
                return true;
            }
            VehicleDriver driver = VehicleDriverRegistry.get(candidate);
            SoldierEntity otherDriver = driver != null ? driver.driverSoldier() : null;
            return otherDriver != null && observer.isFriendlyTo(otherDriver);
        }
        return false;
    }

    private static double horizontalDistance(Vec3 first, Vec3 second) {
        return horizontal(first.subtract(second)).length();
    }

    private static double horizontalDistance(BlockPos first, BlockPos second) {
        double dx = first.getX() - second.getX();
        double dz = first.getZ() - second.getZ();
        return Math.sqrt(dx * dx + dz * dz);
    }

    private static Vec3 horizontal(Vec3 value) {
        return new Vec3(value.x, 0.0, value.z);
    }

    private void brake(Entity vehicle) {
        Vec3 delta = vehicle.getDeltaMovement();
        boolean movingForward = delta.dot(vehicle.getForward()) > 0.05;
        boolean movingBack = delta.dot(vehicle.getForward()) < -0.05;
        // Counter-input native brakes
        SbwVehicles.updateInputs(vehicle, movingBack, movingForward, false, false, false);
    }

    private void releaseControls() {
        Entity vehicle = vehicle();
        if (vehicle != null) {
            SbwVehicles.clearInputs(vehicle);
        }
    }

    private boolean isNearlyStopped(Entity vehicle) {
        return vehicle.getDeltaMovement().horizontalDistanceSqr() < 0.01;
    }

    private void detectAndHandleStuck(Entity vehicle) {
        if (++stuckTimer < STUCK_CHECK_TICKS) return;
        stuckTimer = 0;
        double moved = vehicle.position().distanceTo(lastStuckSample);
        lastStuckSample = vehicle.position();
        if (moved < STUCK_MIN_PROGRESS) {
            reverseTicks = 16;
            repathCooldown = 0;
            waypoints.clear();
            StevesArmyMod.LOGGER.debug("[SBW] vehicle {} stuck, reversing", vehicle.getId());
        }
    }

    private static float wrapDegrees(float degrees) {
        float d = degrees % 360.0f;
        if (d >= 180.0f) d -= 360.0f;
        if (d < -180.0f) d += 360.0f;
        return d;
    }
}

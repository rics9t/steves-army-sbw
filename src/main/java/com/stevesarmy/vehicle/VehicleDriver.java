package com.stevesarmy.vehicle;

import com.stevesarmy.StevesArmyMod;
import com.stevesarmy.compat.sbw.SbwVehicles;
import com.stevesarmy.entity.SoldierEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

import java.lang.ref.WeakReference;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.UUID;

/**
 * Server-side autonomous driver for one SBW vehicle.
 *
 * Uses native SBW inputs so acceleration, speed, and handling match the original mod.
 */
public final class VehicleDriver {

    private static final double FOLLOW_DISTANCE = 14.0;
    private static final double BRAKE_DISTANCE = 8.0;
    private static final double STOP_DISTANCE = 5.0;
    private static final double WAYPOINT_TOLERANCE = 3.5;
    private static final float STEER_DEADZONE_DEGREES = 5.0f;
    private static final float HARD_TURN_DEGREES = 45.0f;

    private static final int REPATH_INTERVAL_TICKS = 40;
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
        clearPath();
    }

    public void orderGoTo(BlockPos pos) {
        this.mode = VehicleDriveMode.GO_TO;
        this.destination = pos;
        clearPath();
    }

    public void orderAttack(UUID targetId, Vec3 pos) {
        this.mode = VehicleDriveMode.ATTACK;
        this.attackTargetId = targetId;
        this.attackTargetPos = pos;
        clearPath();
    }

    public void orderHold() {
        this.mode = VehicleDriveMode.HOLD;
        clearPath();
        releaseControls();
    }

    public void orderDismount() {
        this.mode = VehicleDriveMode.DISMOUNT;
        clearPath();
        releaseControls();
    }

    public void clearAttack() {
        this.attackTargetId = null;
        this.attackTargetPos = null;
    }

    private void clearPath() {
        waypoints.clear();
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
        if (soldier == null || !soldier.isAlive() || soldier.getVehicle() != vehicle) {
            Entity seat0 = SbwVehicles.driver(vehicle);
            if (seat0 instanceof SoldierEntity promoted) {
                setDriverSoldier(promoted);
                soldier = promoted;
            } else {
                releaseControls();
                return seat0 != null;
            }
        }

        SbwVehicles.ensureFuel(vehicle);
        VehicleWeaponController.tick(this, vehicle);

        if (soldier != null && soldier.isAlive()) {
            if (soldier.hasValidPingMoveTarget()) {
                BlockPos target = soldier.getPingMoveTarget();
                if (mode != VehicleDriveMode.GO_TO || destination == null || !destination.equals(target)) {
                    orderGoTo(target);
                }
            } else if (soldier.hasValidAttackTarget()) {
                BlockPos attackPos = soldier.getAttackTargetPos();
                Vec3 targetVec = Vec3.atCenterOf(attackPos);
                if (mode != VehicleDriveMode.ATTACK || attackTargetPos == null || attackTargetPos.distanceToSqr(targetVec) > 4.0) {
                    orderAttack(null, targetVec);
                }
            } else if (soldier.getSquadMode() == com.stevesarmy.squad.SquadMode.FOLLOW) {
                Entity owner = soldier.getOwner();
                if (owner != null && (mode != VehicleDriveMode.FOLLOW || (followTarget.get() != owner && followTarget.get() != null))) {
                    orderFollow(owner);
                }
            } else if (soldier.getSquadMode() == com.stevesarmy.squad.SquadMode.HOLD) {
                if (mode != VehicleDriveMode.HOLD && mode != VehicleDriveMode.IDLE && mode != VehicleDriveMode.DISMOUNT) {
                    orderHold();
                }
            }
        }

        switch (mode) {
            case FOLLOW -> tickFollow(vehicle);
            case GO_TO -> tickGoTo(vehicle);
            case ATTACK -> tickAttack(vehicle);
            case HOLD -> releaseControls();
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
        Entity target = followTarget.get();
        if (target == null || !target.isAlive()) {
            SoldierEntity soldier = driverSoldier();
            if (soldier != null && soldier.getOwner() != null) {
                Entity owner = soldier.getOwner();
                Entity lead = owner.isPassenger() ? owner.getVehicle() : owner;
                Entity ahead = VehicleConvoy.vehicleAheadOf(vehicle, lead);
                target = ahead != null ? ahead : lead;
                followTarget = new WeakReference<>(target);
            }
        }
        if (target == null) {
            brake(vehicle);
            return;
        }

        double distance = vehicle.distanceTo(target);
        if (distance <= BRAKE_DISTANCE) {
            brake(vehicle);
            return;
        }
        if (distance <= FOLLOW_DISTANCE) {
            steerToward(vehicle, target.position(), false);
            return;
        }
        driveTo(vehicle, BlockPos.containing(target.position()), target.position());
    }

    private void tickGoTo(Entity vehicle) {
        if (destination == null) {
            mode = VehicleDriveMode.IDLE;
            releaseControls();
            return;
        }
        Vec3 goal = Vec3.atCenterOf(destination);
        if (vehicle.position().distanceTo(goal) <= BRAKE_DISTANCE) {
            brake(vehicle);
            mode = VehicleDriveMode.HOLD;
            SoldierEntity s = driverSoldier();
            if (s != null) {
                s.completeGoToIfGeneration(s.getPingMoveGeneration());
            }
            return;
        }
        driveTo(vehicle, destination, goal);
    }

    private void tickAttack(Entity vehicle) {
        Vec3 targetPos = resolveAttackPosition(vehicle);
        if (targetPos == null) {
            mode = VehicleDriveMode.HOLD;
            releaseControls();
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
        }
        return attackTargetPos;
    }

    public UUID attackTargetId() {
        return attackTargetId;
    }

    private void driveTo(Entity vehicle, BlockPos goalPos, Vec3 goalVec) {
        if (SbwVehicles.isAirborne(vehicle)) {
            flyTo(vehicle, goalVec);
            return;
        }

        if (repathCooldown-- <= 0 || waypoints.isEmpty()) {
            repathCooldown = REPATH_INTERVAL_TICKS;
            double halfWidth = Math.max(1.0, vehicle.getBbWidth() / 2.0);
            waypoints = VehiclePathfinder.findPath(
                vehicle.level(), vehicle.blockPosition(), goalPos, halfWidth);
            if (waypoints.isEmpty()) {
                waypoints.add(goalVec);
            }
        }

        Vec3 waypoint = waypoints.peek();
        while (waypoint != null
            && vehicle.position().multiply(1, 0, 1)
                .distanceTo(waypoint.multiply(1, 0, 1)) <= WAYPOINT_TOLERANCE) {
            waypoints.poll();
            waypoint = waypoints.peek();
        }
        if (waypoint == null) {
            brake(vehicle);
            return;
        }

        detectAndHandleStuck(vehicle);
        if (reverseTicks > 0) {
            reverseTicks--;
            SbwVehicles.updateInputs(vehicle, false, true, false, false, false);
            return;
        }

        steerToward(vehicle, waypoint, true);
    }

    private void flyTo(Entity vehicle, Vec3 goal) {
        steerToward(vehicle, goal, true);
        double targetY = goal.y + 18.0;
        double dy = targetY - vehicle.getY();
        SbwVehicles.up(vehicle, dy > 1.5);
        SbwVehicles.down(vehicle, dy < -2.5);
    }

    private void steerToward(Entity vehicle, Vec3 target, boolean accelerate) {
        SbwVehicles.ensureFuel(vehicle);
        Vec3 delta = target.subtract(vehicle.position()).multiply(1, 0, 1);
        double distance = delta.length();
        if (distance < 0.1) {
            brake(vehicle);
            return;
        }

        double desiredYaw = Math.toDegrees(Math.atan2(-delta.x, delta.z));
        float currentYaw = vehicle.getYRot();
        float error = wrapDegrees((float) desiredYaw - currentYaw);

        boolean turnLeft = error < -STEER_DEADZONE_DEGREES;
        boolean turnRight = error > STEER_DEADZONE_DEGREES;
        boolean sharpTurn = Math.abs(error) > HARD_TURN_DEGREES;
        boolean forward = accelerate && !sharpTurn;
        boolean sprint = accelerate && !sharpTurn && Math.abs(error) < STEER_DEADZONE_DEGREES;

        // Native SBW acceleration/speed integration
        SbwVehicles.updateInputs(vehicle, forward, false, turnLeft, turnRight, sprint);

        float maxTurn = 3.5f;
        float step = Mth.clamp(error * 0.25f, -maxTurn, maxTurn);
        vehicle.setYRot(currentYaw + step);
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
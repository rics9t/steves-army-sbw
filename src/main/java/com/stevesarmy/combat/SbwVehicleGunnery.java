package com.stevesarmy.combat;

import com.stevesarmy.StevesArmyConfig;
import com.stevesarmy.compat.SbwCompat;
import com.stevesarmy.compat.sbw.SbwVehicles;
import com.stevesarmy.entity.SoldierEntity;
import com.stevesarmy.entity.SoldierRole;
import com.stevesarmy.vehicle.VehicleWeaponController;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Spotter AI for dedicated vehicle crew mounted on Superb Warfare vehicles. While a soldier is
 * a passenger, {@code VS2Compat.prepareSoldierAi} freezes its goal system — but
 * SBW's native mob-crew gunnery fires the vehicle's turret and seat weapons at
 * the riding Mob's target. Keep spotting and weapon selection alive while the
 * soldier's normal movement/cover AI is frozen. SBW handles the actual shots,
 * ballistic lead and RPM; this mod must not double-fire those weapons.
 * Ordinary infantry use {@link MountedSoldierGunnery} only in seats that permit
 * handheld weapons.
 *
 * <p>Called from the frozen-AI branch in {@code SoldierEntity.customServerAiStep},
 * the same structural pattern as {@link StationGunnerAI} (station AI runs outside
 * the goal system while the soldier sits).</p>
 */
public final class SbwVehicleGunnery {
    private static final int RETARGET_INTERVAL_TICKS = 10;
    private static final long PRUNE_INTERVAL_TICKS = 200;
    private static final Map<UUID, Long> lastRetargetBySoldier = new HashMap<>();
    private static long lastPruneGameTime = Long.MIN_VALUE;

    private SbwVehicleGunnery() {}

    /** Safe to call every tick from the frozen-AI branch; throttled and self-gating. */
    public static void tickMounted(SoldierEntity soldier) {
        if (soldier.getRole() != SoldierRole.VEHICLE_CREW) {
            return;
        }
        if (!(soldier.level() instanceof ServerLevel serverLevel)) {
            return;
        }
        Entity mount = soldier.getVehicle();
        if (!SbwCompat.isOperational(mount)) {
            return;
        }
        int seat = SbwVehicles.seatIndexOf(mount, soldier);
        boolean emptyPassengerStation = seat >= 0
            && seat == SbwVehicles.passengerWeaponStationControllerIndex(mount)
            && SbwVehicles.seatHasWeapon(mount, seat)
            && !SbwVehicles.seatHasLoadedWeapon(mount, seat)
            && SbwVehicles.seatAllowsRifleWhenWeaponEmpty(mount, seat);
        if (emptyPassengerStation) {
            // Let rifle targeting own this soldier while the station is empty;
            // otherwise the station muzzle target can repeatedly replace a target
            // that is visible to the rifleman but not from inside the hull.
            SbwVehicles.syncAutoTarget(mount, seat, null);
            MountedSoldierGunnery.tickMounted(soldier);
            return;
        }
        long gameTime = serverLevel.getGameTime();
        UUID soldierId = soldier.getUUID();

        // SBW keeps auto-firing a passenger's last target until its Mob target
        // changes. Clear dead, friendly, or hidden targets immediately instead
        // of leaving the turret trained on their last position between scans.
        LivingEntity current = soldier.getTarget();
        if (current != null && !isValidSpottingTarget(soldier, current)) {
            soldier.setTarget(null);
            current = null;
        }

        Long last = lastRetargetBySoldier.get(soldierId);
        if (last == null || gameTime - last >= RETARGET_INTERVAL_TICKS) {
            lastRetargetBySoldier.put(soldierId, gameTime);
            pruneIfNeeded(gameTime);

            if (current == null) {
                double range = StevesArmyConfig.VEHICLE_CREW_DETECTION_DISTANCE.get();
                List<LivingEntity> targets = serverLevel.getEntitiesOfClass(LivingEntity.class,
                    soldier.getBoundingBox().inflate(range),
                    t -> isValidSpottingTarget(soldier, t));
                current = targets.stream()
                    .min(Comparator.comparingDouble(soldier::distanceToSqr))
                    .orElse(null);
                if (current != null) soldier.setTarget(current);
            }
        }

        if (seat >= 0) {
            // Keep the Mob target for SBW's native fire/cooldown checks, but
            // clear SBW's UUID auto-aim so our controller can add a small,
            // bounded aiming drift instead of resetting the exact bearing each tick.
            SbwVehicles.syncAutoTarget(mount, seat, null);
            VehicleWeaponController.tickMounted(soldier, mount, seat, current);

        }
    }

    /** Spot from the vehicle's muzzle, not the crew's eye inside an enclosed hull. */
    private static boolean isValidSpottingTarget(SoldierEntity soldier, LivingEntity target) {
        Entity vehicle = soldier.getVehicle();
        if (vehicle == null || target == soldier || target.getVehicle() == vehicle
            || target.level() != soldier.level() || !TargetAcquisition.isValidTarget(soldier, target)
            || soldier.isFriendlyTo(target) || !soldier.isProactiveTarget(target)) return false;
        int seat = SbwVehicles.seatIndexOf(vehicle, soldier);
        Vec3 origin = seat >= 0 && SbwVehicles.seatHasWeapon(vehicle, seat)
            ? SbwVehicles.shootPos(vehicle, seat) : soldier.getEyePosition();
        Entity targetVehicle = target.getVehicle();
        Vec3 aimPoint = SbwCompat.isVehicle(targetVehicle)
            ? targetVehicle.getBoundingBox().getCenter() : target.getEyePosition();
        return VisibilityRay.trace(soldier.level(), origin, aimPoint, soldier).hasContact();
    }

    private static void pruneIfNeeded(long gameTime) {
        if (lastPruneGameTime != Long.MIN_VALUE && gameTime - lastPruneGameTime < PRUNE_INTERVAL_TICKS) {
            return;
        }
        lastPruneGameTime = gameTime;
        lastRetargetBySoldier.entrySet().removeIf(
            entry -> gameTime - entry.getValue() > RETARGET_INTERVAL_TICKS * 20);
    }
}

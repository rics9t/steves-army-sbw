package com.stevesarmy.vehicle;

import com.stevesarmy.compat.sbw.SbwVehicles;
import com.stevesarmy.entity.SoldierEntity;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.WeakHashMap;

/** Selects crew weapons and scans with an idle turret; SBW itself fires them. */
public final class VehicleWeaponController {
    private static final int MIN_SWITCH_TICKS = 14;
    private static final float MAX_SCAN_YAW = 150.0f;
    private static final Map<Entity, TurretState> STATES = new WeakHashMap<>();

    private VehicleWeaponController() {}

    public static void tickMounted(SoldierEntity gunner, Entity vehicle, int seat, LivingEntity target) {
        if (vehicle == null || vehicle.level().isClientSide || !SbwCompat.isVehicle(vehicle)
            || SbwVehicles.passengerAt(vehicle, seat) != gunner
            || seat != SbwVehicles.turretControllerIndex(vehicle)
            || !SbwVehicles.seatHasWeapon(vehicle, seat)) return;

        TurretState state = STATES.computeIfAbsent(vehicle, ignored -> new TurretState());
        if (target == null) {
            scanIdle(vehicle, seat, state);
        } else {
            chooseWeapon(vehicle, seat, target, state);
        }
    }

    private static void chooseWeapon(Entity vehicle, int seat, LivingEntity target, TurretState state) {
        List<String> weapons = SbwVehicles.seatWeapons(vehicle, seat);
        int primary = 0;
        for (int i = 0; i < weapons.size(); i++) {
            String name = weapons.get(i).toLowerCase(Locale.ROOT);
            if (name.contains("cannon") || name.contains("main_gun") || name.equals("main")
                || name.contains("laser")) {
                primary = i;
                break;
            }
        }

        boolean armored = com.stevesarmy.compat.SbwCompat.isVehicle(target.getVehicle());
        int backup = -1;
        for (int i = 0; i < weapons.size(); i++) {
            if (i == primary) continue;
            String name = weapons.get(i).toLowerCase(Locale.ROOT);
            boolean suitable = armored
                ? name.contains("missile") || name.contains("rocket") || name.contains("cannon")
                : name.contains("machinegun") || name.contains("machine_gun")
                    || name.contains("coax") || name.startsWith("mg");
            if (suitable && SbwVehicles.weaponReady(vehicle, seat, i)) {
                backup = i;
                break;
            }
        }

        long now = vehicle.level().getGameTime();
        boolean reloading = SbwVehicles.weaponReloading(vehicle, seat, primary);
        boolean ready = SbwVehicles.weaponReady(vehicle, seat, primary);
        if (reloading && !state.wasReloading) {
            state.useBackupThisReload = vehicle.level().random.nextFloat() < 0.65f;
        }
        if (ready) state.unavailableSince = -1;
        else if (state.unavailableSince < 0) state.unavailableSince = now;

        int preferred = primary;
        if (backup >= 0 && !ready && (reloading && state.useBackupThisReload
            || !reloading && now - state.unavailableSince >= 12)) {
            preferred = backup;
        }
        state.wasReloading = reloading;

        if (SbwVehicles.weaponIndex(vehicle, seat) != preferred
            && now - state.lastSwitchAt >= MIN_SWITCH_TICKS) {
            SbwVehicles.setWeaponIndex(vehicle, seat, preferred);
            state.lastSwitchAt = now;
        }
    }

    private static void scanIdle(Entity vehicle, int seat, TurretState state) {
        long now = vehicle.level().getGameTime();
        var random = vehicle.level().random;
        boolean moving = vehicle.getDeltaMovement().horizontalDistanceSqr() > 0.0036;

        if (!state.scanInitialized || now >= state.nextScanAt
            || (moving != state.wasMoving && random.nextFloat() < 0.35f)) {
            state.scanInitialized = true;
            state.wasMoving = moving;

            float target;
            if (moving) {
                target = (float) (random.nextGaussian() * 20.0);
                if (random.nextFloat() < 0.18f) {
                    target = (random.nextBoolean() ? 1.0f : -1.0f)
                        * (45.0f + random.nextFloat() * 40.0f);
                }
                state.nextScanAt = now + 28 + random.nextInt(58);
            } else if (random.nextFloat() < 0.24f) {
                target = (random.nextBoolean() ? 1.0f : -1.0f)
                    * (58.0f + random.nextFloat() * 55.0f);
                state.nextScanAt = now + 48 + random.nextInt(125);
            } else {
                target = (float) (random.nextGaussian() * 34.0);
                state.nextScanAt = now + 48 + random.nextInt(125);
            }

            state.scanTarget = Mth.clamp(target, -MAX_SCAN_YAW, MAX_SCAN_YAW);
            state.scanPitch = -1.0f - random.nextFloat() * 4.0f
                - (random.nextFloat() < 0.12f ? 6.0f : 0.0f);
            state.scanRate = 0.6f + random.nextFloat() * 0.9f;
        }

        float error = Mth.wrapDegrees(state.scanTarget - state.scanYaw);
        state.scanYaw = Mth.wrapDegrees(state.scanYaw
            + Mth.clamp(error, -state.scanRate, state.scanRate));
        Vec3 direction = Vec3.directionFromRotation(state.scanPitch,
            vehicle.getYRot() + state.scanYaw);
        SbwVehicles.aimWeapon(vehicle, seat, direction);
    }

    public static void forget(Entity vehicle) {
        if (vehicle != null) STATES.remove(vehicle);
    }

    public static void clearAll() {
        STATES.clear();
    }

    private static final class TurretState {
        private float scanYaw;
        private float scanTarget;
        private float scanPitch = -2.0f;
        private float scanRate = 1.0f;
        private long nextScanAt;
        private boolean scanInitialized;
        private boolean wasMoving;
        private long lastSwitchAt = Long.MIN_VALUE / 2;
        private long unavailableSince = -1;
        private boolean wasReloading;
        private boolean useBackupThisReload;
    }
}

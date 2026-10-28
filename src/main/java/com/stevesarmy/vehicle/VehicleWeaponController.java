package com.stevesarmy.vehicle;

import com.stevesarmy.compat.sbw.SbwCompat;
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
        if (now >= state.nextScanAt) {
            state.scanTarget = (vehicle.level().random.nextFloat() * 2.0f - 1.0f) * 28.0f;
            state.nextScanAt = now + 65 + vehicle.level().random.nextInt(90);
        }
        state.scanYaw = Mth.approach(state.scanYaw, state.scanTarget, 0.28f);
        Vec3 direction = Vec3.directionFromRotation(-3.0f, vehicle.getYRot() + state.scanYaw);
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
        private long nextScanAt;
        private long lastSwitchAt = Long.MIN_VALUE / 2;
        private long unavailableSince = -1;
        private boolean wasReloading;
        private boolean useBackupThisReload;
    }
}

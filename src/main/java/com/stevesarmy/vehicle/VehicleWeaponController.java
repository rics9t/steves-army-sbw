package com.stevesarmy.vehicle;

import com.stevesarmy.compat.sbw.SbwCompat;
import com.stevesarmy.compat.sbw.SbwVehicles;
import com.stevesarmy.StevesArmyMod;
import com.stevesarmy.entity.SoldierEntity;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.WeakHashMap;

/** Selects crew weapons and scans with an idle turret; SBW owns live target tracking and shots. */
public final class VehicleWeaponController {
    private static final int MIN_SWITCH_TICKS = 4;
    private static final float MAX_SCAN_YAW = 68.0f;
    private static final Map<Entity, Map<Integer, TurretState>> STATES = new WeakHashMap<>();

    private VehicleWeaponController() {}

    public static void tickMounted(SoldierEntity gunner, Entity vehicle, int seat, LivingEntity target) {
        if (vehicle == null || vehicle.level().isClientSide || !SbwCompat.isVehicle(vehicle)
            || SbwVehicles.passengerAt(vehicle, seat) != gunner
            || (seat != SbwVehicles.turretControllerIndex(vehicle)
                && seat != SbwVehicles.passengerWeaponStationControllerIndex(vehicle))
            || !SbwVehicles.seatHasWeapon(vehicle, seat)) return;

        TurretState state = STATES.computeIfAbsent(vehicle, ignored -> new java.util.HashMap<>())
            .computeIfAbsent(seat, ignored -> new TurretState());
        if (target == null) {
            if (seat == SbwVehicles.turretControllerIndex(vehicle)) {
                selectReadyIdleWeapon(vehicle, seat, state);
            }
            scanIdle(vehicle, seat, state);
        } else {
            if (seat == SbwVehicles.turretControllerIndex(vehicle)) {
                chooseWeapon(vehicle, seat, target, state);
            }
        }
    }

    /** Avoid leaving an idle crew on an empty or reloading weapon. */
    private static void selectReadyIdleWeapon(Entity vehicle, int seat, TurretState state) {
        List<String> weapons = SbwVehicles.seatWeapons(vehicle, seat);
        if (weapons.size() < 2) return;
        int current = SbwVehicles.weaponIndex(vehicle, seat);
        if (weaponAvailable(vehicle, seat, current)) return;

        for (int offset = 1; offset < weapons.size(); offset++) {
            int candidate = Math.floorMod(current + offset, weapons.size());
            if (weaponAvailable(vehicle, seat, candidate)) {
                switchWeapon(vehicle, seat, candidate, state, "current weapon empty or reloading");
                return;
            }
        }
    }

    private static void chooseWeapon(Entity vehicle, int seat, LivingEntity target, TurretState state) {
        List<String> weapons = SbwVehicles.seatWeapons(vehicle, seat);
        if (weapons.size() < 2) return;

        int primary = 0;
        for (int i = 0; i < weapons.size(); i++) {
            String name = weapons.get(i).toLowerCase(Locale.ROOT);
            if (name.contains("cannon") || name.contains("main_gun") || name.equals("main")
                || name.contains("laser")) {
                primary = i;
                break;
            }
        }

        boolean armored = SbwCompat.isVehicle(target)
            || SbwCompat.isVehicle(target.getVehicle());
        int machineGun = -1;
        for (int i = 0; i < weapons.size(); i++) {
            if (i == primary) continue;
            String name = weapons.get(i).toLowerCase(Locale.ROOT);
            if (name.contains("machinegun") || name.contains("machine_gun")
                || name.contains("coax") || name.startsWith("mg")) {
                machineGun = i;
                break;
            }
        }

        // Use the cannon/launcher against armor and the coaxial gun against
        // nearby infantry. At long range the cannon has the better hit chance.
        // If the preferred weapon is empty or reloading, use the other loaded
        // station weapon until it can take over again.
        double distance = vehicle.distanceTo(target);
        int current = SbwVehicles.weaponIndex(vehicle, seat);
        int preferred;
        if (armored || machineGun < 0 || distance >= 60.0) {
            preferred = primary;
        } else if (distance <= 50.0) {
            preferred = machineGun;
        } else {
            // Keep the current choice in the 10-block transition band so a
            // moving target does not make the crew flick between weapons.
            preferred = current == primary || current == machineGun ? current : primary;
        }
        int alternate = preferred == primary ? machineGun : primary;
        if (!weaponAvailable(vehicle, seat, preferred)) {
            if (alternate >= 0 && weaponAvailable(vehicle, seat, alternate)) {
                preferred = alternate;
            } else {
                return;
            }
        }

        switchWeapon(vehicle, seat, preferred, state,
            armored ? "armored target" : "target range and type");
    }

    private static void switchWeapon(Entity vehicle, int seat, int selected, TurretState state, String reason) {
        long now = vehicle.level().getGameTime();
        int current = SbwVehicles.weaponIndex(vehicle, seat);
        if (current == selected || now - state.lastSwitchAt < MIN_SWITCH_TICKS) return;
        if (SbwVehicles.setWeaponIndex(vehicle, seat, selected)) {
            state.lastSwitchAt = now;
            StevesArmyMod.LOGGER.debug("[VehicleGunnery] vehicle={} seat={} weapon {} -> {} ({})",
                vehicle.getId(), seat, current, selected, reason);
        }
    }

    private static boolean weaponAvailable(Entity vehicle, int seat, int weapon) {
        return weapon >= 0 && !SbwVehicles.weaponReloading(vehicle, seat, weapon)
            && (SbwVehicles.weaponHasAmmo(vehicle, seat, weapon)
                || SbwVehicles.weaponReady(vehicle, seat, weapon));
    }

    private static void scanIdle(Entity vehicle, int seat, TurretState state) {
        long now = vehicle.level().getGameTime();
        var random = vehicle.level().random;
        boolean moving = vehicle.getDeltaMovement().horizontalDistanceSqr() > 0.0036;

        if (!state.scanInitialized) {
            state.scanInitialized = true;
            state.wasMoving = moving;
        }
        if (now >= state.nextScanAt || moving != state.wasMoving) {
            state.wasMoving = moving;
            // Give SBW a stable bearing to track. Its own turret turn speed provides
            // the mechanical slew; slowly walking a synthetic bearing made this
            // movement too small to see and let each new idle target cancel the last.
            float target = (float) (random.nextGaussian() * (moving ? 24.0 : 31.0));
            if (random.nextFloat() < (moving ? 0.20f : 0.28f)) {
                target = (random.nextBoolean() ? 1.0f : -1.0f)
                    * (38.0f + random.nextFloat() * 28.0f);
            }
            state.scanTarget = Mth.clamp(target, -MAX_SCAN_YAW, MAX_SCAN_YAW);
            state.scanPitch = -1.0f - random.nextFloat() * 4.0f;
            state.nextScanAt = now + (moving ? 38 + random.nextInt(48) : 55 + random.nextInt(90));
        }

        Vec3 direction = Vec3.directionFromRotation(state.scanPitch,
            vehicle.getYRot() + state.scanTarget);
        SbwVehicles.aimWeapon(vehicle, seat, direction);
    }

    public static void forget(Entity vehicle) {
        if (vehicle != null) STATES.remove(vehicle);
    }

    public static void clearAll() {
        STATES.clear();
    }

    private static final class TurretState {
        private float scanTarget;
        private float scanPitch = -2.0f;
        private long nextScanAt;
        private boolean scanInitialized;
        private boolean wasMoving;
        private long lastSwitchAt = Long.MIN_VALUE / 2;
    }
}

package com.stevesarmy.vehicle;

import com.stevesarmy.compat.sbw.SbwCompat;
import com.stevesarmy.compat.sbw.SbwVehicles;
import com.stevesarmy.StevesArmyMod;
import com.stevesarmy.entity.SoldierEntity;
import com.stevesarmy.entity.SoldierRole;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;

/** Selects crew weapons and supplies deliberately imperfect live and idle aim to SBW. */
public final class VehicleWeaponController {
    private static final int MIN_SWITCH_TICKS = 4;
    private static final float MAX_SCAN_YAW = 48.0f;
    private static final Map<Entity, Map<Integer, TurretState>> STATES = new WeakHashMap<>();

    private VehicleWeaponController() {}

    public static void tickMounted(SoldierEntity gunner, Entity vehicle, int seat, LivingEntity target) {
        if (gunner == null || gunner.getRole() != SoldierRole.VEHICLE_CREW
            || vehicle == null || vehicle.level().isClientSide || !SbwCompat.isVehicle(vehicle)
            || SbwVehicles.passengerAt(vehicle, seat) != gunner
            || (seat != SbwVehicles.turretControllerIndex(vehicle)
                && seat != SbwVehicles.passengerWeaponStationControllerIndex(vehicle))
            || !SbwVehicles.seatHasWeapon(vehicle, seat)) return;

        TurretState state = STATES.computeIfAbsent(vehicle, ignored -> new java.util.HashMap<>())
            .computeIfAbsent(seat, ignored -> new TurretState());
        long now = vehicle.level().getGameTime();
        recordShotAndWeaponState(vehicle, seat, state, now);
        if (target == null) {
            state.attackTargetId = null;
            SbwVehicles.syncAutoTarget(vehicle, seat, null);
            if (seat == SbwVehicles.turretControllerIndex(vehicle)) {
                selectReadyIdleWeapon(vehicle, seat, state);
            }
            scanIdle(vehicle, seat, state);
        } else {
            // Keep SBW's native Mob-based fire cadence, but do our own ballistic
            // solution and small, slowly changing aim errors so tanks do not
            // snap to an exact entity UUID every tick.
            SbwVehicles.syncAutoTarget(vehicle, seat, null);
            if (seat == SbwVehicles.turretControllerIndex(vehicle)) {
                chooseWeapon(vehicle, seat, target, state);
            }
            aimAtTarget(vehicle, seat, target, state, now);
        }
    }

    /** Avoid leaving an idle crew on an empty or reloading weapon. */
    private static void selectReadyIdleWeapon(Entity vehicle, int seat, TurretState state) {
        List<String> weapons = SbwVehicles.seatWeapons(vehicle, seat);
        if (weapons.size() < 2) return;
        int current = SbwVehicles.weaponIndex(vehicle, seat);
        if (weaponAvailable(vehicle, seat, current)) return;

        for (int candidate = 0; candidate < weapons.size(); candidate++) {
            if (candidate == current) continue;
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
            String name = normalizedName(weapons.get(i));
            if (name.contains("cannon") || name.contains("maingun") || name.equals("main")
                || name.contains("laser")) {
                primary = i;
                break;
            }
        }

        // A visible passenger remains a person target even when riding a tank;
        // aim at the rider and use the coax rather than treating the whole
        // occupied vehicle as an armored hull.
        boolean armored = SbwCompat.isVehicle(target);
        int machineGun = -1;
        for (int i = 0; i < weapons.size(); i++) {
            if (i == primary) continue;
            String name = normalizedName(weapons.get(i));
            if (name.contains("machinegun") || name.contains("coax") || name.startsWith("mg")) {
                machineGun = i;
                break;
            }
        }
        // Some vehicle data calls the secondary weapon something generic. Keep
        // a useful alternate on tanks such as the T-90 (Cannon + MachineGun).
        if (machineGun < 0 && "TANK".equals(
            com.stevesarmy.compat.SbwCompat.getVehicleTypeName(vehicle))) {
            for (int i = 0; i < weapons.size(); i++) {
                if (i != primary) {
                    machineGun = i;
                    break;
                }
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

    private static String normalizedName(String name) {
        return name == null ? "" : name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    private static void recordShotAndWeaponState(Entity vehicle, int seat, TurretState state, long now) {
        int weapon = SbwVehicles.weaponIndex(vehicle, seat);
        int ammo = SbwVehicles.ammoCount(vehicle, seat);
        if (weapon == state.lastObservedWeapon && ammo >= 0 && state.lastObservedAmmo >= 0
            && ammo < state.lastObservedAmmo) {
            state.recoilDuration = weapon == 0 ? 6 : 3;
            state.recoilUntil = now + state.recoilDuration;
            state.recoilPitch = weapon == 0 ? 1.1f : 0.35f;
        }
        state.lastObservedWeapon = weapon;
        state.lastObservedAmmo = ammo;
    }

    private static void aimAtTarget(Entity vehicle, int seat, LivingEntity target,
                                    TurretState state, long now) {
        UUID targetId = target.getUUID();
        var random = vehicle.level().random;
        if (!targetId.equals(state.attackTargetId) || now >= state.nextAttackAimAt) {
            state.attackTargetId = targetId;
            state.attackYawOffset = Mth.clamp((float) (random.nextGaussian() * 0.55), -1.0f, 1.0f);
            state.attackPitchOffset = Mth.clamp((float) (random.nextGaussian() * 0.35), -0.7f, 0.7f);
            state.nextAttackAimAt = now + 20 + random.nextInt(27);
        }

        Vec3 aimPoint = target.getBoundingBox().getCenter();
        Vec3 velocity = SbwVehicles.firingSolution(vehicle, seat, aimPoint, target.getDeltaMovement());
        if (velocity.lengthSqr() < 1.0e-6) return;

        float yaw = (float) Math.toDegrees(Math.atan2(-velocity.x, velocity.z));
        double horizontal = Math.sqrt(velocity.x * velocity.x + velocity.z * velocity.z);
        float pitch = (float) -Math.toDegrees(Math.atan2(velocity.y, Math.max(0.01, horizontal)));
        float recoil = now < state.recoilUntil && state.recoilDuration > 0
            ? state.recoilPitch * (state.recoilUntil - now) / state.recoilDuration : 0.0f;
        Vec3 direction = Vec3.directionFromRotation(
            pitch + state.attackPitchOffset - recoil, yaw + state.attackYawOffset);
        SbwVehicles.aimWeapon(vehicle, seat, direction);
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
            float target = (float) (random.nextGaussian() * (moving ? 14.0 : 19.0));
            if (random.nextFloat() < (moving ? 0.12f : 0.18f)) {
                target = (random.nextBoolean() ? 1.0f : -1.0f)
                    * (29.0f + random.nextFloat() * 17.0f);
            }
            state.scanTarget = Mth.clamp(target, -MAX_SCAN_YAW, MAX_SCAN_YAW);
            state.scanPitch = -1.0f - random.nextFloat() * 2.5f;
            state.nextScanAt = now + (moving ? 48 + random.nextInt(45) : 65 + random.nextInt(80));
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
        private UUID attackTargetId;
        private float attackYawOffset;
        private float attackPitchOffset;
        private long nextAttackAimAt;
        private int lastObservedWeapon = -1;
        private int lastObservedAmmo = -1;
        private long recoilUntil;
        private int recoilDuration;
        private float recoilPitch;
        private long nextScanAt;
        private boolean scanInitialized;
        private boolean wasMoving;
        private long lastSwitchAt = Long.MIN_VALUE / 2;
    }
}

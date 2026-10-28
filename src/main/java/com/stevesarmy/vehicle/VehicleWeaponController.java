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
            scanIdle(vehicle, seat, state);
        } else {
            if (seat == SbwVehicles.turretControllerIndex(vehicle)) {
                chooseWeapon(vehicle, seat, target, state);
            }
            trackTarget(vehicle, seat, target, state);
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

        long now = vehicle.level().getGameTime();
        if (SbwVehicles.weaponIndex(vehicle, seat) != preferred
            && now - state.lastSwitchAt >= MIN_SWITCH_TICKS) {
            SbwVehicles.setWeaponIndex(vehicle, seat, preferred);
            state.lastSwitchAt = now;
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

        state.aimTargetId = null;
        Vec3 direction = Vec3.directionFromRotation(state.scanPitch,
            vehicle.getYRot() + state.scanTarget);
        SbwVehicles.aimWeapon(vehicle, seat, direction);
    }

    /** SBW still owns cooldowns and shots; this supplies a less mechanical aim direction. */
    private static void trackTarget(Entity vehicle, int seat, LivingEntity target, TurretState state) {
        long now = vehicle.level().getGameTime();
        var random = vehicle.level().random;
        int weapon = SbwVehicles.weaponIndex(vehicle, seat);
        int ammo = SbwVehicles.ammoCount(vehicle, seat);
        if (weapon != state.aimAmmoWeapon) {
            state.aimAmmoWeapon = weapon;
            state.lastAimAmmo = ammo;
        } else {
            if (state.lastAimAmmo >= 0 && ammo >= 0 && ammo < state.lastAimAmmo) {
                // A shot briefly breaks the sight picture. SBW still handles
                // firing and cooldowns; this is only the turret's recovery.
                state.recoilYaw += (float) (random.nextGaussian() * 1.1);
                state.recoilPitch -= 1.8f + random.nextFloat() * 1.8f;
            }
            state.lastAimAmmo = ammo;
        }

        boolean moving = vehicle.getDeltaMovement().horizontalDistanceSqr() > 0.01;
        if (!target.getUUID().equals(state.aimTargetId)) {
            state.aimTargetId = target.getUUID();
            float initialError = moving ? 5.0f : 3.5f;
            state.aimOffsetYaw = Mth.clamp((float) (random.nextGaussian() * initialError),
                -initialError * 2.0f, initialError * 2.0f);
            state.aimOffsetPitch = Mth.clamp((float) (random.nextGaussian() * initialError * 0.45),
                -4.0f, 4.0f);
            state.aimGoalYaw = (float) (random.nextGaussian() * 0.8);
            state.aimGoalPitch = (float) (random.nextGaussian() * 0.35);
            state.aimQuality = 0.04f;
            state.settleUntil = now + 18 + random.nextInt(25);
            state.nextAimWobbleAt = state.settleUntil + 8 + random.nextInt(13);
            state.lastTargetPosition = target.position();
            state.scanInitialized = false;
        }

        Vec3 targetMotion = target.position().subtract(state.lastTargetPosition);
        if (targetMotion.lengthSqr() > 0.64) {
            // A target that changes direction makes the gunner reacquire instead
            // of keeping a mathematically perfect lock on its old movement line.
            state.aimQuality = Math.max(0.12f, state.aimQuality - 0.12f);
            state.nextAimWobbleAt = Math.min(state.nextAimWobbleAt, now + 2);
        }
        state.lastTargetPosition = target.position();
        state.aimQuality = Math.min(moving ? 0.70f : 0.96f,
            state.aimQuality + (moving ? 0.012f : 0.026f));

        if (now >= state.settleUntil && now >= state.nextAimWobbleAt) {
            float sway = Mth.lerp(state.aimQuality, 2.8f, 0.65f);
            state.aimGoalYaw = Mth.clamp((float) (random.nextGaussian() * sway), -sway * 2.0f, sway * 2.0f);
            state.aimGoalPitch = Mth.clamp((float) (random.nextGaussian() * sway * 0.35), -1.4f, 1.4f);
            state.nextAimWobbleAt = now + 10 + random.nextInt(22);
        }
        // The initial correction takes time, then the sight makes small,
        // irregular corrections. A shot's recoil decays independently.
        float yawStep = now < state.settleUntil ? 0.18f : 0.11f;
        float pitchStep = now < state.settleUntil ? 0.11f : 0.065f;
        state.aimOffsetYaw += Mth.clamp(state.aimGoalYaw - state.aimOffsetYaw, -yawStep, yawStep);
        state.aimOffsetPitch += Mth.clamp(state.aimGoalPitch - state.aimOffsetPitch, -pitchStep, pitchStep);
        state.recoilYaw *= 0.78f;
        state.recoilPitch *= 0.78f;

        Entity targetVehicle = target.getVehicle();
        Entity aimEntity = targetVehicle != null && SbwCompat.isVehicle(targetVehicle)
            ? targetVehicle : target;
        Vec3 from = SbwVehicles.shootPos(vehicle, seat);
        Vec3 aimPoint = aimEntity.getBoundingBox().getCenter();
        Vec3 delta = aimPoint.subtract(from);
        double distance = delta.length();
        if (distance < 1.0e-4) return;

        // Light velocity lead keeps the mount moving toward the threat instead of
        // snapping to the exact current position every tick.
        double projectileSpeed = Math.max(8.0, SbwVehicles.projectileVelocity(vehicle, seat));
        double leadTicks = Mth.clamp(distance / projectileSpeed, 0.0, 1.25);
        aimPoint = aimPoint.add(aimEntity.getDeltaMovement().scale(leadTicks));
        delta = aimPoint.subtract(from);
        double horizontal = Math.sqrt(delta.x * delta.x + delta.z * delta.z);
        float yaw = (float) Math.toDegrees(Math.atan2(-delta.x, delta.z));
        float pitch = (float) -Math.toDegrees(Math.atan2(delta.y, Math.max(0.001, horizontal)));
        Vec3 direction = Vec3.directionFromRotation(
            pitch + state.aimOffsetPitch + state.recoilPitch,
            yaw + state.aimOffsetYaw + state.recoilYaw);
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
        private java.util.UUID aimTargetId;
        private long nextAimWobbleAt;
        private long settleUntil;
        private float aimOffsetYaw;
        private float aimOffsetPitch;
        private float aimGoalYaw;
        private float aimGoalPitch;
        private float aimQuality;
        private float recoilYaw;
        private float recoilPitch;
        private int aimAmmoWeapon = -1;
        private int lastAimAmmo = -1;
        private Vec3 lastTargetPosition = Vec3.ZERO;
    }
}

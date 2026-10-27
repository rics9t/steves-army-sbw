package com.stevesarmy.vehicle;

import com.stevesarmy.compat.sbw.SbwVehicles;
import com.stevesarmy.compat.sbw.SbwCompat;
import com.stevesarmy.entity.SoldierEntity;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class VehicleWeaponController {

    private static final Map<String, Long> NEXT_FIRE = new ConcurrentHashMap<>();
    private static final int HEAT_CUTOFF = 90;

    private VehicleWeaponController() {
    }

    public static void tick(VehicleDriver driver, Entity vehicle) {
        if (vehicle == null || vehicle.level().isClientSide) return;

        Vec3 orderedTargetPos = driver.resolveAttackPosition(vehicle);
        UUID targetId = driver.attackTargetId();

        boolean engaging = orderedTargetPos != null
            && (driver.mode() == VehicleDriveMode.ATTACK || driver.mode() == VehicleDriveMode.HOLD);

        if (!engaging) {
            SbwVehicles.fire(vehicle, false);
            aimIdleTurret(vehicle);
            return;
        }

        // A location order may outlive the target that originally attracted
        // the crew. Fire only while the spotter has a live hostile target, so
        // the cannon or MG never keeps shooting at the old aim point.
        SoldierEntity spotter = driver.driverSoldier();
        Entity tracked = targetId != null && vehicle.level() instanceof ServerLevel level
            ? level.getEntity(targetId)
            : spotter != null ? spotter.getTarget() : null;
        if (!(tracked instanceof LivingEntity target) || !target.isAlive()
            || spotter == null || spotter.isFriendlyTo(target)
            || !spotter.isProactiveTarget(target)) {
            SbwVehicles.fire(vehicle, false);
            aimIdleTurret(vehicle);
            return;
        }
        targetId = target.getUUID();
        Entity targetVehicle = target.getVehicle();
        Vec3 targetPos = SbwCompat.isVehicle(targetVehicle)
            ? targetVehicle.getBoundingBox().getCenter()
            : target.getBoundingBox().getCenter();

        // The driver seat owns the main turret's selectable weapons. Prefer
        // the cannon against hostile crew inside a vehicle and the coaxial MG
        // against exposed infantry; passenger stations keep their own loadout.
        selectMainWeapon(vehicle, target);

        long now = vehicle.level().getGameTime();
        int seats = SbwVehicles.maxPassengers(vehicle);

        for (int seatIndex = 0; seatIndex < seats; seatIndex++) {
            Entity occupant = SbwVehicles.passengerAt(vehicle, seatIndex);
            if (!(occupant instanceof SoldierEntity gunner)) continue;
            if (!SbwVehicles.seatHasWeapon(vehicle, seatIndex)) continue;

            String weaponName = SbwVehicles.getGunName(vehicle, seatIndex);
            if (weaponName == null) continue;

            if (SbwVehicles.ammoCount(vehicle, seatIndex) <= 0) continue;
            if (SbwVehicles.weaponHeat(vehicle, seatIndex) >= HEAT_CUTOFF) continue;

            String key = vehicle.getUUID() + ":" + seatIndex + ":" + weaponName;
            long next = NEXT_FIRE.getOrDefault(key, 0L);
            if (now < next) continue;

            Vec3 shootPos = SbwVehicles.shootPos(vehicle, seatIndex);
            Vec3 aimVec = targetPos.subtract(shootPos).normalize();
            SbwVehicles.aimWeapon(vehicle, seatIndex, aimVec);

            boolean fired = SbwVehicles.shoot(vehicle, gunner, targetId, targetPos);
            if (fired) {
                NEXT_FIRE.put(key, now + cooldownFor(vehicle, seatIndex, weaponName));
            }
        }
    }

    private static void selectMainWeapon(Entity vehicle, LivingEntity target) {
        int seat = SbwVehicles.turretControllerIndex(vehicle);
        if (seat < 0) return;
        boolean armoredTarget = SbwCompat.isVehicle(target.getVehicle());
        java.util.List<String> weapons = SbwVehicles.seatWeapons(vehicle, seat);
        for (int i = 0; i < weapons.size(); i++) {
            String name = weapons.get(i).toLowerCase(java.util.Locale.ROOT);
            boolean match = armoredTarget
                ? name.contains("cannon") || name.contains("main_gun") || name.equals("main")
                : name.contains("machinegun") || name.contains("machine_gun")
                    || name.contains("coax") || name.equals("mg");
            if (match && SbwVehicles.weaponIndex(vehicle, seat) != i) {
                SbwVehicles.setWeaponIndex(vehicle, seat, i);
            }
            if (match) return;
        }
    }

    private static int cooldownFor(Entity vehicle, int seatIndex, String weaponName) {
        String normalized = weaponName.toLowerCase(java.util.Locale.ROOT);
        if (normalized.contains("machinegun") || normalized.contains("machine_gun")
            || normalized.contains("coax") || normalized.equals("mg")) {
            return 2;
        }
        if (normalized.contains("missile") || normalized.contains("rocket")) return 40;
        SbwVehicles.Kind kind = SbwVehicles.kind(vehicle);
        if (seatIndex == 0 && kind == SbwVehicles.Kind.TRACKED) {
            return 80;
        }
        return kind == SbwVehicles.Kind.HELICOPTER || kind == SbwVehicles.Kind.AIRCRAFT ? 10 : 15;
    }

    private static void aimIdleTurret(Entity vehicle) {
        int seat = SbwVehicles.turretControllerIndex(vehicle);
        if (seat < 0 || !SbwVehicles.seatHasWeapon(vehicle, seat)) return;
        long time = vehicle.level().getGameTime();
        double phase = (time + vehicle.getId() * 19.0) * (Math.PI / 240.0);
        float yaw = vehicle.getYRot() + (float) (Math.sin(phase) * 28.0);
        Vec3 direction = Vec3.directionFromRotation(-3.0F, yaw);
        SbwVehicles.aimWeapon(vehicle, seat, direction);
    }

    public static void forget(Entity vehicle) {
        if (vehicle == null) return;
        String prefix = vehicle.getUUID().toString() + ":";
        NEXT_FIRE.keySet().removeIf(k -> k.startsWith(prefix));
    }
}

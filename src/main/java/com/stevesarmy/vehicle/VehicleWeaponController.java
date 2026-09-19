package com.stevesarmy.vehicle;

import com.stevesarmy.compat.sbw.SbwVehicles;
import com.stevesarmy.entity.SoldierEntity;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class VehicleWeaponController {

    private static final Map<String, Long> NEXT_FIRE = new ConcurrentHashMap<>();
    private static final int DEFAULT_COOLDOWN_TICKS = 40;
    private static final int HEAT_CUTOFF = 90;

    private VehicleWeaponController() {
    }

    public static void tick(VehicleDriver driver, Entity vehicle) {
        if (vehicle == null || vehicle.level().isClientSide) return;

        Vec3 targetPos = driver.resolveAttackPosition(vehicle);
        UUID targetId = driver.attackTargetId();

        boolean engaging = targetPos != null
            && (driver.mode() == VehicleDriveMode.ATTACK || driver.mode() == VehicleDriveMode.HOLD);

        if (!engaging) {
            SbwVehicles.fire(vehicle, false);
            return;
        }

        long now = vehicle.level().getGameTime();
        int seats = SbwVehicles.maxPassengers(vehicle);

        for (int seatIndex = 0; seatIndex < seats; seatIndex++) {
            Entity occupant = SbwVehicles.passengerAt(vehicle, seatIndex);
            if (!(occupant instanceof SoldierEntity gunner)) continue;
            if (!SbwVehicles.seatHasWeapon(vehicle, seatIndex)) continue;

            if (SbwVehicles.ammoCount(vehicle, seatIndex) <= 0) continue;
            if (SbwVehicles.weaponHeat(vehicle, seatIndex) >= HEAT_CUTOFF) continue;

            String key = vehicle.getUUID() + ":" + seatIndex;
            long next = NEXT_FIRE.getOrDefault(key, 0L);
            if (now < next) continue;

            faceTarget(gunner, targetPos);

            Vec3 shootPos = SbwVehicles.shootPos(vehicle, seatIndex);
            Vec3 aimVec = targetPos.subtract(shootPos).normalize();
            SbwVehicles.aimWeapon(vehicle, seatIndex, aimVec);

            boolean fired = SbwVehicles.shoot(vehicle, gunner, targetId, targetPos);
            if (fired) {
                NEXT_FIRE.put(key, now + cooldownFor(vehicle, seatIndex));
            }
        }
    }

    private static int cooldownFor(Entity vehicle, int seatIndex) {
        SbwVehicles.Kind kind = SbwVehicles.kind(vehicle);
        if (seatIndex == 0 && kind == SbwVehicles.Kind.TRACKED) {
            return 80;
        }
        if (kind == SbwVehicles.Kind.HELICOPTER || kind == SbwVehicles.Kind.AIRCRAFT) {
            return 10;
        }
        return seatIndex == 0 ? DEFAULT_COOLDOWN_TICKS : 15;
    }

    private static void faceTarget(SoldierEntity gunner, Vec3 target) {
        Vec3 delta = target.subtract(gunner.getEyePosition());
        double horizontal = Math.sqrt(delta.x * delta.x + delta.z * delta.z);
        float yaw = (float) (Math.toDegrees(Math.atan2(delta.z, delta.x)) - 90.0);
        float pitch = (float) -Math.toDegrees(Math.atan2(delta.y, horizontal));
        gunner.setYRot(yaw);
        gunner.yHeadRot = yaw;
        gunner.setXRot(Math.max(-90f, Math.min(90f, pitch)));
    }

    public static void forget(Entity vehicle) {
        if (vehicle == null) return;
        String prefix = vehicle.getUUID().toString() + ":";
        NEXT_FIRE.keySet().removeIf(k -> k.startsWith(prefix));
    }
}

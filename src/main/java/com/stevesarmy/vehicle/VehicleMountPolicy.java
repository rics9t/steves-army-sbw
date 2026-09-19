package com.stevesarmy.vehicle;

import com.stevesarmy.compat.VS2Compat;
import com.stevesarmy.compat.sbw.SbwCompat;
import com.stevesarmy.compat.sbw.SbwVehicles;
import com.stevesarmy.entity.SoldierEntity;
import net.minecraft.world.entity.Entity;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Single authorization policy for every soldier mount, covering SBW vehicles
 * and the existing VS2 / Create seats.
 */
public final class VehicleMountPolicy {

    private static final Map<UUID, UUID> AUTHORIZED = new ConcurrentHashMap<>();

    private VehicleMountPolicy() {
    }

    public static void authorize(SoldierEntity soldier, Entity vehicle) {
        if (soldier == null || vehicle == null) return;
        AUTHORIZED.put(soldier.getUUID(), vehicle.getUUID());
    }

    public static void revoke(SoldierEntity soldier) {
        if (soldier == null) return;
        AUTHORIZED.remove(soldier.getUUID());
        VS2Compat.clearAuthorizedMount(soldier);
    }

    public static boolean isAuthorized(SoldierEntity soldier, Entity vehicle) {
        if (soldier == null || vehicle == null) return false;

        if (SbwCompat.isVehicle(vehicle)) {
            return true;
        }

        UUID pending = AUTHORIZED.get(soldier.getUUID());
        if (pending != null && pending.equals(vehicle.getUUID())) {
            return true;
        }

        return VS2Compat.isAuthorizedMount(soldier, vehicle);
    }

    public static boolean canBoard(SoldierEntity soldier, Entity vehicle) {
        if (soldier == null || vehicle == null || !soldier.isAlive()) return false;
        if (!SbwCompat.isVehicle(vehicle)) return false;
        if (SbwVehicles.isWrecked(vehicle)) return false;
        if (soldier.isPassenger()) return false;
        return SbwVehicles.hasFreeSeat(vehicle);
    }

    public static boolean board(SoldierEntity soldier, Entity vehicle) {
        if (!canBoard(soldier, vehicle)) return false;
        authorize(soldier, vehicle);
        boolean ok = soldier.startRiding(vehicle, true);
        if (!ok) {
            revoke(soldier);
        }
        return ok;
    }
}

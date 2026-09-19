package com.stevesarmy.vehicle;

import com.stevesarmy.StevesArmyMod;
import com.stevesarmy.compat.sbw.SbwCompat;
import com.stevesarmy.entity.SoldierEntity;
import net.minecraft.world.entity.Entity;

import java.util.ArrayList;
import java.util.List;

public final class VehicleWreckHandler {

    private VehicleWreckHandler() {
    }

    public static void ejectAll(Entity vehicle) {
        if (vehicle == null) return;

        List<Entity> passengers = new ArrayList<>(vehicle.getPassengers());
        int ejected = 0;

        for (Entity passenger : passengers) {
            if (!(passenger instanceof SoldierEntity soldier)) continue;
            dismount(soldier);
            ejected++;
        }

        VehicleDriverRegistry.remove(vehicle);
        VehicleWeaponController.forget(vehicle);

        if (ejected > 0) {
            StevesArmyMod.LOGGER.info("[SBW] ejected {} soldiers from {}",
                ejected, vehicle.getName().getString());
        }
    }

    public static void dismount(SoldierEntity soldier) {
        if (soldier == null) return;
        Entity vehicle = soldier.getVehicle();
        soldier.stopRiding();
        VehicleMountPolicy.revoke(soldier);
        if (vehicle != null) {
            soldier.setPos(
                vehicle.getX() + (soldier.getRandom().nextDouble() - 0.5) * 3.0,
                vehicle.getY() + 1.0,
                vehicle.getZ() + (soldier.getRandom().nextDouble() - 0.5) * 3.0);
        }
    }

    public static int dismountAll(List<SoldierEntity> soldiers) {
        int count = 0;
        for (SoldierEntity soldier : soldiers) {
            Entity ride = soldier.getVehicle();
            if (ride == null || !SbwCompat.isVehicle(ride)) continue;
            VehicleDriver driver = VehicleDriverRegistry.get(ride);
            if (driver != null) {
                driver.orderDismount();
            }
            dismount(soldier);
            count++;
        }
        return count;
    }
}

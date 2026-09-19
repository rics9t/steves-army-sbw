package com.stevesarmy.vehicle;

import com.stevesarmy.compat.sbw.SbwCompat;
import com.stevesarmy.compat.sbw.SbwVehicles;
import net.minecraft.world.entity.Entity;

public final class VehicleConvoy {

    private VehicleConvoy() {
    }

    public static Entity vehicleAheadOf(Entity self, Entity lead) {
        if (self == null || lead == null) return null;

        double ownGap = self.distanceToSqr(lead);
        Entity best = null;
        double bestGap = Double.MAX_VALUE;

        for (VehicleDriver driver : VehicleDriverRegistry.all()) {
            Entity other = driver.vehicle();
            if (other == null || other == self || other == lead) continue;
            if (other.level() != self.level()) continue;
            if (!SbwCompat.isVehicle(other) || SbwVehicles.isWrecked(other)) continue;

            double gap = other.distanceToSqr(lead);
            if (gap >= ownGap) continue;
            if (gap < bestGap) {
                bestGap = gap;
                best = other;
            }
        }
        return best;
    }
}

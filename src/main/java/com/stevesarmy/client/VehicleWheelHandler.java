package com.stevesarmy.client;

import com.stevesarmy.StevesArmyMod;
import com.stevesarmy.network.NetworkHandler;
import com.stevesarmy.network.TransportOrderMessage;
import com.stevesarmy.transport.TransportOrder;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * Hover/selection logic for the vehicle page. It is reached by cycling pages while the
 * ping wheel is open (see WheelCycleController), and the ping wheel's release path sends
 * the selected vehicle action.
 */
public class VehicleWheelHandler {
    private static TransportOrder currentHoveredAction;
    private static final List<TransportOrder> VEHICLE_ACTIONS = List.of(
        TransportOrder.MOUNT,
        TransportOrder.DISMOUNT,
        TransportOrder.MOUNT_CREW,
        TransportOrder.DISMOUNT_CREW,
        TransportOrder.DRIVE_GO_TO,
        TransportOrder.DRIVE_ATTACK,
        TransportOrder.DRIVE_FOLLOW,
        TransportOrder.DRIVE_HOLD
    );

    public static List<TransportOrder> actionsForCurrentPage() {
        return VEHICLE_ACTIONS;
    }

    static TransportOrder getHoveredAction() {
        Minecraft mc = Minecraft.getInstance();
        currentHoveredAction = determineActionFromMouse();
        return currentHoveredAction;
    }

    /**
     * Sends the captured wheel action. The action is snapshotted before the ping wheel
     * release re-grabs the mouse (grabMouse recenters the cursor), so it must not be
     * recomputed here.
     */
    static void fireSelected(Minecraft mc, TransportOrder action) {
        if (action == null || mc.player == null) {
            return;
        }
        Vec3 aimPosition = PingWheelHandler.findCrosshairPosition(mc);
        if (aimPosition == null) {
            return;
        }
        if (action == TransportOrder.DRIVE_ATTACK) {
            Entity aimedEntity = PingWheelHandler.findCrosshairEntity(mc);
            if (aimedEntity != null) {
                aimPosition = aimedEntity.position();
            }
        }
        StevesArmyMod.LOGGER.info("Vehicle wheel fired: {} at {}", action, aimPosition);
        NetworkHandler.INSTANCE.sendToServer(
            new TransportOrderMessage(action, aimPosition, FireTeamScopeState.INSTANCE.getCurrentScope()));
    }

    /**
     * The page splits into equal sectors for its current action set. The center dead zone cancels.
     */
    private static TransportOrder determineActionFromMouse() {
        double deltaX = PingWheelHandler.getDeltaX();
        double deltaY = PingWheelHandler.getDeltaY();

        double distance = Math.sqrt(deltaX * deltaX + deltaY * deltaY);
        if (distance < 30) {
            return null;
        }

        double angle = Math.toDegrees(Math.atan2(deltaY, deltaX));
        double adjustedDegrees = angle + 90;
        if (adjustedDegrees < 0) adjustedDegrees += 360;
        if (adjustedDegrees >= 360) adjustedDegrees -= 360;

        List<TransportOrder> actions = actionsForCurrentPage();
        int count = actions.size();
        double sectorSize = 360.0 / count;
        int sector = ((int) (adjustedDegrees / sectorSize)) % count;
        return actions.get(sector);
    }
}

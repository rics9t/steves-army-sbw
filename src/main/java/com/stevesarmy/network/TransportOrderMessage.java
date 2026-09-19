package com.stevesarmy.network;

import com.stevesarmy.StevesArmyConfig;
import com.stevesarmy.compat.AnalogWarfareCompat;
import com.stevesarmy.compat.VS2Compat;
import com.stevesarmy.compat.sbw.SbwCompat;
import com.stevesarmy.compat.sbw.SbwVehicles;
import com.stevesarmy.entity.SoldierEntity;
import com.stevesarmy.StevesArmyMod;
import com.stevesarmy.squad.FireTeam;
import com.stevesarmy.squad.SquadTargeting;
import com.stevesarmy.transport.TransportOrder;
import com.stevesarmy.vehicle.VehicleDriverRegistry;
import com.stevesarmy.vehicle.VehicleMountPolicy;
import com.stevesarmy.vehicle.VehicleWreckHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/** Vehicle wheel order: seat the scoped soldiers on a vehicle, or release them from theirs. */
public class TransportOrderMessage {
    private final TransportOrder order;
    private final double x;
    private final double y;
    private final double z;
    private final FireTeam scope;

    public TransportOrderMessage(TransportOrder order, Vec3 aimPosition, FireTeam scope) {
        this.order = order;
        this.x = aimPosition.x;
        this.y = aimPosition.y;
        this.z = aimPosition.z;
        this.scope = scope;
    }

    public TransportOrderMessage(FriendlyByteBuf buf) {
        this.order = buf.readEnum(TransportOrder.class);
        this.x = buf.readDouble();
        this.y = buf.readDouble();
        this.z = buf.readDouble();
        this.scope = buf.readEnum(FireTeam.class);
    }

    public static void encode(TransportOrderMessage msg, FriendlyByteBuf buf) {
        buf.writeEnum(msg.order);
        buf.writeDouble(msg.x);
        buf.writeDouble(msg.y);
        buf.writeDouble(msg.z);
        buf.writeEnum(msg.scope);
    }

    public TransportOrder getOrder() { return order; }
    public Vec3 getAimPosition() { return new Vec3(x, y, z); }
    public FireTeam getScope() { return scope; }

    public static void handle(TransportOrderMessage msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer sender = ctx.get().getSender();
            if (sender == null) return;

            ServerLevel level = sender.serverLevel();

            switch (msg.getOrder()) {
                case MOUNT -> handleMount(sender, level,
                    SquadTargeting.resolveOrderedSoldiers(level, sender, msg.getScope()), msg.getAimPosition());
                case DISMOUNT -> handleDismount(sender,
                    SquadTargeting.resolveOrderedSoldiers(level, sender, msg.getScope()));
                case MOUNT_CREW -> handleMountCrew(sender, level, msg.getAimPosition());
            }
        });
        ctx.get().setPacketHandled(true);
    }

    private static void handleMountCrew(ServerPlayer sender, ServerLevel level, Vec3 aimPosition) {
        if (!VS2Compat.isEnabled()) {
            sender.displayClientMessage(Component.translatable("transport.steves_army.feedback.vs2_unavailable"), true);
            return;
        }
        Vec3 searchCenter = aimPosition;
        Object ship = VS2Compat.getShipAt(level, BlockPos.containing(aimPosition));
        if (ship == null) {
            searchCenter = sender.position();
            ship = VS2Compat.resolveMountShipNearPlayer(level, sender);
        }
        if (ship == null) {
            sender.displayClientMessage(Component.translatable("transport.steves_army.feedback.no_vehicle"), true);
            return;
        }
        List<SoldierEntity> crew = com.stevesarmy.transport.CrewAssignment.stationlessCrewNear(level, sender, searchCenter, 64);
        if (crew.isEmpty()) {
            sender.displayClientMessage(Component.translatable("transport.steves_army.feedback.no_crew"), true);
            return;
        }
        int seated = com.stevesarmy.transport.CrewAssignment.autoAssignNear(sender, level, ship, searchCenter);
        StevesArmyMod.LOGGER.info("[Transport] MOUNT_CREW by {}: crew={} seated={}",
            sender.getName().getString(), crew.size(), seated);
        sender.displayClientMessage(
            Component.translatable("transport.steves_army.feedback.seated", seated, crew.size()), true);
    }

    private static void handleDismount(ServerPlayer sender, List<SoldierEntity> soldiers) {
        if (soldiers.isEmpty()) {
            sender.displayClientMessage(Component.translatable("transport.steves_army.feedback.no_soldiers"), true);
            return;
        }
        int dismounted = VehicleWreckHandler.dismountAll(soldiers);

        boolean handlesAvailable = StevesArmyConfig.VEHICLE_HANDLES_ENABLED.get()
            && AnalogWarfareCompat.isAvailable();
        int handleExits = 0;
        for (SoldierEntity soldier : soldiers) {
            Entity vehicle = soldier.isPassenger() ? soldier.getVehicle() : null;
            net.minecraft.world.level.block.entity.BlockEntity handle = null;
            if (handlesAvailable && vehicle != null && soldier.level() instanceof ServerLevel serverLevel) {
                handle = AnalogWarfareCompat.findHandleForSoldier(serverLevel, soldier);
            }
            if (VS2Compat.releaseTransport(soldier)) {
                dismounted++;
                if (handle != null) {
                    if (AnalogWarfareCompat.teleportSoldierToHandle(soldier, handle)) {
                        AnalogWarfareCompat.forget(soldier.getUUID());
                        handleExits++;
                    }
                }
            }
        }
        StevesArmyMod.LOGGER.info("[Transport] DISMOUNT by {}: resolved={} dismounted={} handleExits={}",
            sender.getName().getString(), soldiers.size(), dismounted, handleExits);
        if (dismounted == 0) {
            sender.displayClientMessage(Component.translatable("transport.steves_army.feedback.none_mounted"), true);
        } else {
            sender.displayClientMessage(
                Component.translatable("transport.steves_army.feedback.dismounted", dismounted), true);
        }
    }

    private static void handleMount(ServerPlayer sender, ServerLevel level,
                                    List<SoldierEntity> soldiers, Vec3 aimPosition) {
        if (soldiers.isEmpty()) {
            sender.displayClientMessage(Component.translatable("transport.steves_army.feedback.no_soldiers"), true);
            return;
        }
        List<SoldierEntity> eligible = soldiers.stream()
            .filter(s -> !s.isPassenger())
            .toList();
        if (eligible.isEmpty()) {
            sender.displayClientMessage(Component.translatable("transport.steves_army.feedback.all_mounted"), true);
            return;
        }

        if (SbwCompat.isLoaded()) {
            Entity targetedVehicle = null;
            Vec3 eye = sender.getEyePosition();
            Vec3 look = sender.getViewVector(1.0f);
            Vec3 end = eye.add(look.scale(64.0));
            AABB rayBox = sender.getBoundingBox().expandTowards(look.scale(64.0)).inflate(4.0);
            List<Entity> vehicles = level.getEntitiesOfClass(Entity.class, rayBox, SbwCompat::isVehicle);
            for (Entity v : vehicles) {
                if (v.getBoundingBox().inflate(1.5).clip(eye, end).isPresent()) {
                    targetedVehicle = v;
                    break;
                }
            }
            if (targetedVehicle == null) {
                List<Entity> nearAim = level.getEntitiesOfClass(Entity.class, new AABB(aimPosition, aimPosition).inflate(8.0), SbwCompat::isVehicle);
                if (!nearAim.isEmpty()) {
                    targetedVehicle = nearAim.get(0);
                }
            }
            if (targetedVehicle != null && !SbwVehicles.isWrecked(targetedVehicle)) {
                int seated = 0;
                for (SoldierEntity soldier : eligible) {
                    if (SbwVehicles.hasFreeSeat(targetedVehicle)) {
                        soldier.getNavigation().stop();
                        soldier.cancelCoverMovement();
                        soldier.setDeltaMovement(Vec3.ZERO);
                        if (VehicleMountPolicy.board(soldier, targetedVehicle)) {
                            VehicleDriverRegistry.onBoarded(soldier, targetedVehicle);
                            seated++;
                        }
                    }
                }
                StevesArmyMod.LOGGER.info("[Transport] MOUNT by {}: eligible={} seated={} (via SBW)", sender.getName().getString(), eligible.size(), seated);
                if (seated == 0) {
                    sender.displayClientMessage(Component.translatable("transport.steves_army.feedback.no_free_seats"), true);
                } else {
                    sender.displayClientMessage(Component.translatable("transport.steves_army.feedback.seated", seated, eligible.size()), true);
                }
                return;
            }
        }

        if (!VS2Compat.isEnabled()) {
            sender.displayClientMessage(Component.translatable("transport.steves_army.feedback.vs2_unavailable"), true);
            return;
        }

        Vec3 searchCenter = aimPosition;
        Object ship = VS2Compat.getShipAt(level, BlockPos.containing(aimPosition));
        if (ship == null) {
            searchCenter = sender.position();
            ship = VS2Compat.resolveMountShipNearPlayer(level, sender);
        }
        if (ship == null) {
            sender.displayClientMessage(Component.translatable("transport.steves_army.feedback.no_vehicle"), true);
            return;
        }

        List<SoldierEntity> remaining = new ArrayList<>(eligible);
        int seated = 0;
        int viaHandles = 0;

        if (StevesArmyConfig.VEHICLE_HANDLES_ENABLED.get() && AnalogWarfareCompat.isAvailable()) {
            viaHandles = AnalogWarfareCompat.mountViaHandles(level, ship, searchCenter, remaining);
            seated += viaHandles;
        }

        if (!remaining.isEmpty()) {
            List<BlockPos> seats = VS2Compat.findFreeStaticSeats(level, ship, searchCenter, remaining.size());
            for (SoldierEntity soldier : remaining) {
                if (seats.isEmpty()) {
                    break;
                }
                soldier.getNavigation().stop();
                soldier.cancelCoverMovement();
                soldier.setDeltaMovement(Vec3.ZERO);
                if (VS2Compat.seatSoldierDirect(soldier, level, seats.get(0))) {
                    seats.remove(0);
                    seated++;
                }
            }
        }

        if (!remaining.isEmpty()) {
            Long shipId = VS2Compat.getShipIdOf(ship);
            if (shipId != null) {
                for (SoldierEntity soldier : new ArrayList<>(remaining)) {
                    Entity freeSeat = VS2Compat.findFreeSeatEntityNear(soldier, 16.0);
                    if (freeSeat != null) {
                        soldier.getNavigation().stop();
                        soldier.cancelCoverMovement();
                        soldier.setDeltaMovement(Vec3.ZERO);
                        if (VS2Compat.seatSoldierOnSeatEntity(soldier, freeSeat)) {
                            remaining.remove(soldier);
                            seated++;
                        }
                    }
                }
            }
        }

        StevesArmyMod.LOGGER.info("[Transport] MOUNT by {}: eligible={} seated={} (viaHandles={}, viaFallback={})",
            sender.getName().getString(), eligible.size(), seated, viaHandles, seated - viaHandles);
        if (seated == 0) {
            sender.displayClientMessage(Component.translatable("transport.steves_army.feedback.no_free_seats"), true);
        } else {
            sender.displayClientMessage(
                Component.translatable("transport.steves_army.feedback.seated", seated, eligible.size()), true);
        }
    }
}

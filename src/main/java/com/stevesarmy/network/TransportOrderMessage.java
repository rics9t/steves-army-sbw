package com.stevesarmy.network;

import com.stevesarmy.StevesArmyMod;
import com.stevesarmy.compat.VS2Compat;
import com.stevesarmy.compat.sbw.SbwCompat;
import com.stevesarmy.compat.sbw.SbwVehicles;
import com.stevesarmy.entity.SoldierEntity;
import com.stevesarmy.squad.FireTeam;
import com.stevesarmy.squad.SquadTargeting;
import com.stevesarmy.transport.CrewAssignment;
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
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.network.NetworkEvent;

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
        if (!VS2Compat.isEnabled() && !com.stevesarmy.compat.SbwCompat.isEnabled()) {
            sender.displayClientMessage(Component.translatable("transport.steves_army.feedback.vs2_unavailable"), true);
            return;
        }

        // Dedicated vehicle crew board separately from the ordinary infantry taxi order.
        Entity aimedVehicle = resolveAimedVehicle(sender);
        if (aimedVehicle != null && com.stevesarmy.compat.SbwCompat.isOperational(aimedVehicle)) {
            List<SoldierEntity> crew = CrewAssignment.stationlessCrewNear(level, sender, aimPosition, 64);
            if (crew.isEmpty()) {
                sender.displayClientMessage(Component.translatable("transport.steves_army.feedback.no_crew"), true);
                return;
            }
            int seated = CrewAssignment.mountCrewOnVehicle(level, aimedVehicle, crew);
            StevesArmyMod.LOGGER.info("[Transport] MOUNT_CREW by {}: vehicle={} type={} crew={} seated={}",
                sender.getName().getString(), aimedVehicle.getId(),
                com.stevesarmy.compat.SbwCompat.getVehicleTypeName(aimedVehicle), crew.size(), seated);
            sender.displayClientMessage(
                Component.translatable("transport.steves_army.feedback.seated", seated, crew.size()), true);
            return;
        }

        Vec3 searchCenter = aimPosition;
        Object ship = resolveAimedShip(sender, level, aimPosition);
        if (ship == null) {
            searchCenter = sender.position();
            ship = VS2Compat.resolveMountShipNearPlayer(level, sender);
        }
        if (ship == null) {
            sender.displayClientMessage(Component.translatable("transport.steves_army.feedback.no_vehicle"), true);
            return;
        }

        List<SoldierEntity> crew = CrewAssignment.stationlessCrewNear(level, sender, searchCenter, 64);
        if (crew.isEmpty()) {
            sender.displayClientMessage(Component.translatable("transport.steves_army.feedback.no_crew"), true);
            return;
        }

        int seated = CrewAssignment.mountCrewOnShip(level, ship, searchCenter, crew);
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

        int dismounted = 0;
        if (SbwCompat.isLoaded() && com.stevesarmy.compat.SbwCompat.isEnabled()) {
            dismounted += VehicleWreckHandler.dismountAll(soldiers);
        }

        for (SoldierEntity soldier : soldiers) {
            // releaseTransport exits at the hatch: the handle linked to the soldier's
            // seat first, then the nearest handle on their ship (no link required).
            if (VS2Compat.releaseTransport(soldier)) {
                dismounted++;
            }
        }
        StevesArmyMod.LOGGER.info("[Transport] DISMOUNT by {}: resolved={} dismounted={}",
            sender.getName().getString(), soldiers.size(), dismounted);

        if (dismounted == 0) {
            sender.displayClientMessage(Component.translatable("transport.steves_army.feedback.none_mounted"), true);
        } else {
            sender.displayClientMessage(
                Component.translatable("transport.steves_army.feedback.dismounted", dismounted), true);
        }
    }

    /** /vs get-ship clips 10 blocks from the crosshair. */
    private static final double GET_SHIP_PICK_DISTANCE = 10.0;

    /**
     * Plain-entity vehicle under the crosshair (Superb Warfare): an ENTITY pick
     * hit on an operational SBW vehicle. Null for every other hit type — ships
     * keep going through {@link #resolveAimedShip}.
     */
    private static Entity resolveAimedVehicle(ServerPlayer sender) {
        if (!SbwCompat.isLoaded() || !com.stevesarmy.compat.SbwCompat.isEnabled()) {
            return null;
        }
        HitResult clip = sender.pick(GET_SHIP_PICK_DISTANCE, 1.0F, false);
        if (clip.getType() == HitResult.Type.ENTITY && clip instanceof EntityHitResult entityHit
            && SbwCompat.isVehicle(entityHit.getEntity()) && !SbwVehicles.isWrecked(entityHit.getEntity())) {
            return entityHit.getEntity();
        }
        return null;
    }

    /**
     * Ship resolution in /vs get-ship order (the crew assign stick's order): the
     * vanilla clip's hit block is the authoritative crosshair pick, so the ship
     * managing that exact block wins; the geometric resolver only runs when the
     * hit block maps to nothing — overlapping sub-ships can defeat a raw
     * block-center lookup. Callers keep their near-player last resort.
     */
    private static Object resolveAimedShip(ServerPlayer sender, ServerLevel level, Vec3 aimPosition) {
        HitResult clip = sender.pick(GET_SHIP_PICK_DISTANCE, 1.0F, false);
        if (clip.getType() == HitResult.Type.BLOCK && clip instanceof BlockHitResult blockHit) {
            Object ship = VS2Compat.getShipObjectAtBlockPos(level, blockHit.getBlockPos());
            if (ship != null) {
                StevesArmyMod.LOGGER.info("[Transport] ship resolved via vs get-ship at hit block {}: shipId={}",
                    blockHit.getBlockPos(), VS2Compat.getShipIdOf(ship));
                return ship;
            }
        }
        return VS2Compat.resolveShipAtWorldAnchor(level, aimPosition);
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

        if (SbwCompat.hasVehicles()) {
            Entity targetedVehicle = resolveAimedVehicle(sender);
            if (targetedVehicle == null) {
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

        // Crosshair ship first (vs get-ship order); otherwise the ship the player
        // rides, else the nearest ship.
        Vec3 searchCenter = aimPosition;
        Object ship = resolveAimedShip(sender, level, aimPosition);
        if (ship == null) {
            searchCenter = sender.position();
            ship = VS2Compat.resolveMountShipNearPlayer(level, sender);
        }
        if (ship == null) {
            sender.displayClientMessage(Component.translatable("transport.steves_army.feedback.no_vehicle"), true);
            return;
        }

        // Crew soldiers board shipyard seats exactly like riflemen; their crew AI
        // keeps ticking once seated (crewSeated), so they still man their station.
        // One shared 4-tier routine — handles → Create contraption seats → static
        // SeatBlocks → raw seat entities (tallyho) — so the wheel boards whatever
        // the crew assign stick boards: ships whose only seats are seat entities
        // have no SeatBlocks for a block scan to find.
        int seated = CrewAssignment.mountCrewOnShip(level, ship, searchCenter, eligible);

        StevesArmyMod.LOGGER.info("[Transport] MOUNT by {}: eligible={} seated={}",
            sender.getName().getString(), eligible.size(), seated);

        if (seated == 0) {
            sender.displayClientMessage(Component.translatable("transport.steves_army.feedback.no_free_seats"), true);
        } else {
            sender.displayClientMessage(
                Component.translatable("transport.steves_army.feedback.seated", seated, eligible.size()), true);
        }
    }
}

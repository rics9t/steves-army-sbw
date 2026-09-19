package com.stevesarmy.vehicle;

import com.stevesarmy.StevesArmyMod;
import com.stevesarmy.compat.sbw.SbwCompat;
import com.stevesarmy.compat.sbw.SbwVehicles;
import com.stevesarmy.entity.SoldierEntity;
import com.stevesarmy.squad.FireTeam;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.event.entity.EntityMountEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Mod.EventBusSubscriber(modid = StevesArmyMod.MODID)
public final class VehicleBoardingManager {

    private static final double RECRUIT_RADIUS = 24.0;
    private static final double CONVOY_SEARCH_RADIUS = 40.0;

    private VehicleBoardingManager() {
    }

    @SubscribeEvent
    public static void onMount(EntityMountEvent event) {
        if (!event.isMounting()) return;
        if (event.getEntity().level().isClientSide) return;
        if (!SbwCompat.hasVehicles()) return;
        if (!(event.getEntityMounting() instanceof ServerPlayer player)) return;

        Entity vehicle = event.getEntityBeingMounted();
        if (!SbwCompat.isVehicle(vehicle)) return;
        if (!(player.level() instanceof ServerLevel level)) return;

        level.getServer().tell(new net.minecraft.server.TickTask(
            level.getServer().getTickCount() + 1, () -> {
                if (!player.isPassenger() || player.getVehicle() != vehicle) return;
                fillSeats(level, player, vehicle);
            }));
    }

    public static void fillSeats(ServerLevel level, Player owner, Entity vehicle) {
        List<SoldierEntity> candidates = nearbyOwned(level, owner, RECRUIT_RADIUS);
        if (candidates.isEmpty()) return;

        candidates.sort(Comparator.comparingDouble(s -> s.distanceToSqr(vehicle)));

        List<SoldierEntity> leftovers = new ArrayList<>();
        int boarded = 0;
        boolean playerAboard = vehicle.getPassengers().stream()
            .anyMatch(p -> p instanceof Player);

        for (SoldierEntity soldier : candidates) {
            if (playerAboard && SbwVehicles.driver(vehicle) == null) {
                leftovers.add(soldier);
                continue;
            }
            if (!SbwVehicles.hasFreeSeat(vehicle)) {
                leftovers.add(soldier);
                continue;
            }
            if (VehicleMountPolicy.board(soldier, vehicle)) {
                boarded++;
                VehicleDriverRegistry.onBoarded(soldier, vehicle);
            } else {
                leftovers.add(soldier);
            }
        }

        StevesArmyMod.LOGGER.info("[SBW] {} soldiers boarded {}; {} seeking alternates",
            boarded, vehicle.getName().getString(), leftovers.size());

        if (!leftovers.isEmpty()) {
            assignAlternateVehicles(level, owner, vehicle, leftovers);
        }
    }

    private static void assignAlternateVehicles(ServerLevel level, Player owner,
                                                Entity primary, List<SoldierEntity> soldiers) {
        List<Entity> alternates = usableVehiclesNear(level, primary, CONVOY_SEARCH_RADIUS);
        alternates.remove(primary);
        if (alternates.isEmpty()) return;

        for (SoldierEntity soldier : soldiers) {
            Entity best = alternates.stream()
                .filter(SbwVehicles::hasFreeSeat)
                .min(Comparator.comparingDouble(soldier::distanceToSqr))
                .orElse(null);
            if (best == null) break;
            if (VehicleMountPolicy.board(soldier, best)) {
                VehicleDriverRegistry.onBoarded(soldier, best);
                VehicleDriverRegistry.setFollowTarget(best, primary);
            }
        }
    }

    public static void autoBoardOrConvoy(SoldierEntity soldier) {
        if (soldier.getSquadMode() != com.stevesarmy.squad.SquadMode.FOLLOW) return;
        LivingEntity owner = soldier.getOwner();
        if (owner == null) return;
        Entity ownerVehicle = owner.getVehicle();
        if (!SbwCompat.isVehicle(ownerVehicle)) return;
        if (soldier.tickCount % 20 != 0) return;

        if (SbwVehicles.hasFreeSeat(ownerVehicle)) {
            if (VehicleMountPolicy.board(soldier, ownerVehicle)) {
                return;
            }
        }

        List<Entity> alternates = usableVehiclesNear((ServerLevel) soldier.level(), ownerVehicle, CONVOY_SEARCH_RADIUS);
        alternates.remove(ownerVehicle);
        for (Entity alt : alternates) {
            if (SbwVehicles.hasFreeSeat(alt)) {
                if (VehicleMountPolicy.board(soldier, alt)) {
                    VehicleDriverRegistry.onBoarded(soldier, alt);
                    VehicleDriverRegistry.setFollowTarget(alt, ownerVehicle);
                    return;
                }
            }
        }
    }

    public static List<SoldierEntity> nearbyOwned(ServerLevel level, Player owner, double radius) {
        return level.getEntitiesOfClass(
            SoldierEntity.class,
            owner.getBoundingBox().inflate(radius),
            s -> s.isAlive()
                && s.isOwnedBy(owner)
                && !s.isPassenger()
                && s.getFireTeam() != FireTeam.GARRISON);
    }

    public static List<Entity> usableVehiclesNear(ServerLevel level, Entity anchor, double radius) {
        List<Entity> out = new ArrayList<>();
        for (Entity e : level.getEntities(anchor, anchor.getBoundingBox().inflate(radius))) {
            if (!SbwCompat.isVehicle(e)) continue;
            if (SbwVehicles.isWrecked(e)) continue;
            if (!SbwVehicles.canDrive(e)) continue;
            if (!SbwVehicles.hasFreeSeat(e)) continue;
            out.add(e);
        }
        return out;
    }

    public static boolean takeVehicle(SoldierEntity soldier, Entity vehicle) {
        if (!VehicleMountPolicy.canBoard(soldier, vehicle)) return false;
        boolean wasEmpty = vehicle.getPassengers().isEmpty();
        if (!VehicleMountPolicy.board(soldier, vehicle)) return false;
        VehicleDriverRegistry.onBoarded(soldier, vehicle);
        if (wasEmpty) {
            StevesArmyMod.LOGGER.info("[SBW] soldier {} took driver seat of {}",
                soldier.getId(), vehicle.getName().getString());
        }
        return true;
    }
}

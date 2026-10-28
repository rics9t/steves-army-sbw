package com.stevesarmy.client;

import com.stevesarmy.StevesArmyMod;
import com.stevesarmy.compat.sbw.SbwCompat;
import com.stevesarmy.compat.sbw.SbwVehicles;
import com.stevesarmy.entity.SoldierEntity;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderLivingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** Mirrors SBW's seat HidePassenger setting for Steve's Army riders. */
@Mod.EventBusSubscriber(modid = StevesArmyMod.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class VehicleCrewRenderHandler {
    private VehicleCrewRenderHandler() {}

    @SubscribeEvent
    public static void onRenderLivingPre(RenderLivingEvent.Pre<?, ?> event) {
        if (!(event.getEntity() instanceof SoldierEntity soldier)) return;
        Entity vehicle = soldier.getVehicle();
        if (!SbwCompat.isVehicle(vehicle)) return;

        // Remote entity packets interpolate previous/current head rotations. Match
        // the server's direct pose write so a seated head does not ease a second time.
        soldier.yHeadRotO = soldier.getYHeadRot();
        soldier.xRotO = soldier.getXRot();
        if (SbwVehicles.hidesPassenger(vehicle, soldier)) event.setCanceled(true);
    }
}

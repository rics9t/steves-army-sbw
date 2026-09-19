package com.stevesarmy.client.renderer;

import com.mojang.blaze3d.vertex.PoseStack;
import com.stevesarmy.client.model.SoldierModel;
import com.stevesarmy.client.SoldierSkinLoader;
import com.stevesarmy.combat.GunIntegration;
import com.stevesarmy.compat.ysm.ISoldierGeoRenderer;
import com.stevesarmy.compat.ysm.YsmCompat;
import com.stevesarmy.entity.EnemySoldierEntity;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.HumanoidMobRenderer;
import net.minecraft.client.renderer.entity.layers.HumanoidArmorLayer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.client.extensions.common.IClientItemExtensions;
import com.mojang.math.Axis;
import net.minecraft.util.Mth;

public class EnemySoldierRenderer extends HumanoidMobRenderer<EnemySoldierEntity, SoldierModel<EnemySoldierEntity>> {
    private static final ResourceLocation TEXTURE = new ResourceLocation("minecraft", "textures/entity/player/wide/steve.png");

    private final ISoldierGeoRenderer geoRenderer;

    public EnemySoldierRenderer(EntityRendererProvider.Context context) {
        super(context, new SoldierModel<>(context.bakeLayer(ModelLayers.PLAYER)), 0.5F);
        this.geoRenderer = YsmCompat.createGeoRenderer(context);
        this.addLayer(new HumanoidArmorLayer<>(this, 
            new HumanoidModel<>(context.bakeLayer(ModelLayers.PLAYER_INNER_ARMOR)),
            new HumanoidModel<>(context.bakeLayer(ModelLayers.PLAYER_OUTER_ARMOR)),
            context.getModelManager()));
    }

    @Override
    public ResourceLocation getTextureLocation(EnemySoldierEntity entity) {
        ResourceLocation skin = SoldierSkinLoader.resolve(entity.getSkin());
        return skin != null ? skin : TEXTURE;
    }

    private HumanoidModel.ArmPose getArmPose(EnemySoldierEntity soldier, InteractionHand hand) {
        ItemStack itemstack = soldier.getItemInHand(hand);
        if (itemstack.isEmpty()) {
            return HumanoidModel.ArmPose.EMPTY;
        }

        HumanoidModel.ArmPose forgePose = IClientItemExtensions.of(itemstack).getArmPose(soldier, hand, itemstack);
        if (forgePose != null) {
            return forgePose;
        }

        if (GunIntegration.isAnyGun(itemstack)) {
            return HumanoidModel.ArmPose.CROSSBOW_HOLD;
        }

        if (soldier.getUsedItemHand() == hand && soldier.getUseItemRemainingTicks() > 0) {
            net.minecraft.world.item.UseAnim useAnim = itemstack.getUseAnimation();
            if (useAnim == net.minecraft.world.item.UseAnim.BLOCK) return HumanoidModel.ArmPose.BLOCK;
            if (useAnim == net.minecraft.world.item.UseAnim.BOW) return HumanoidModel.ArmPose.BOW_AND_ARROW;
            if (useAnim == net.minecraft.world.item.UseAnim.SPEAR) return HumanoidModel.ArmPose.THROW_SPEAR;
            if (useAnim == net.minecraft.world.item.UseAnim.CROSSBOW && hand == soldier.getUsedItemHand()) {
                return HumanoidModel.ArmPose.CROSSBOW_CHARGE;
            }
        } else if (!soldier.swinging && itemstack.getItem() instanceof net.minecraft.world.item.CrossbowItem && net.minecraft.world.item.CrossbowItem.isCharged(itemstack)) {
            return HumanoidModel.ArmPose.CROSSBOW_HOLD;
        }

        return HumanoidModel.ArmPose.ITEM;
    }

    @Override
    public void render(EnemySoldierEntity soldier, float entityYaw, float partialTick, 
                       PoseStack poseStack, MultiBufferSource bufferSource, int packedLight) {
        if (geoRenderer != null && soldier.hasYsmModel()
            && geoRenderer.renderSoldier(soldier, entityYaw, partialTick, poseStack, bufferSource, packedLight)) {
            return;
        }
        
        this.model.riding = soldier.isPassenger();
        this.model.crouching = soldier.isCrouching();

        if (soldier.getMainArm() == HumanoidArm.RIGHT) {
            this.model.rightArmPose = getArmPose(soldier, InteractionHand.MAIN_HAND);
            this.model.leftArmPose = getArmPose(soldier, InteractionHand.OFF_HAND);
        } else {
            this.model.rightArmPose = getArmPose(soldier, InteractionHand.OFF_HAND);
            this.model.leftArmPose = getArmPose(soldier, InteractionHand.MAIN_HAND);
        }

        if (this.model.rightArmPose == HumanoidModel.ArmPose.CROSSBOW_HOLD || this.model.rightArmPose == HumanoidModel.ArmPose.BOW_AND_ARROW) {
            if (this.model.leftArmPose == HumanoidModel.ArmPose.EMPTY) {
                this.model.leftArmPose = this.model.rightArmPose;
            }
        }
        if (this.model.leftArmPose == HumanoidModel.ArmPose.CROSSBOW_HOLD || this.model.leftArmPose == HumanoidModel.ArmPose.BOW_AND_ARROW) {
            if (this.model.rightArmPose == HumanoidModel.ArmPose.EMPTY) {
                this.model.rightArmPose = this.model.leftArmPose;
            }
        }

        super.render(soldier, entityYaw, partialTick, poseStack, bufferSource, packedLight);
    }

    @Override
    public Vec3 getRenderOffset(EnemySoldierEntity soldier, float partialTick) {
        return soldier.isCrouching() && !soldier.isPassenger() ? new Vec3(0.0D, -0.125D, 0.0D) : super.getRenderOffset(soldier, partialTick);
    }

    @Override
    protected void scale(EnemySoldierEntity entity, PoseStack poseStack, float partialTick) {
        poseStack.scale(0.9375F, 0.9375F, 0.9375F);
    }

    @Override
    protected void setupRotations(EnemySoldierEntity soldier, PoseStack poseStack, float ageInTicks, float bodyYaw, float partialTick) {
        float swim = soldier.getSwimAmount(partialTick);
        if (swim > 0.001F && soldier.isAlive() && !soldier.isPassenger()) {
            super.setupRotations(soldier, poseStack, ageInTicks, bodyYaw, partialTick);
            float rotX = Mth.lerp(swim, 0.0F, -90.0F);
            poseStack.mulPose(Axis.XP.rotationDegrees(rotX));
            poseStack.translate(
                0.0D,
                Mth.lerp(swim, 0.0D, -1.0D),
                Mth.lerp(swim, 0.0D, 0.3D));
            return;
        }
        super.setupRotations(soldier, poseStack, ageInTicks, bodyYaw, partialTick);
    }
}
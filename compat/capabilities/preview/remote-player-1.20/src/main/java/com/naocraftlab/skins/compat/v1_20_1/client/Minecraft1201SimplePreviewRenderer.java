package com.naocraftlab.skins.compat.v1_20_1.client;

import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.naocraftlab.skins.client.OuterLayerPart;
import com.naocraftlab.skins.client.OuterLayerVisibility;
import com.naocraftlab.skins.client.PlayerPreviewLighting;
import com.naocraftlab.skins.client.PreviewRenderer;
import com.naocraftlab.skins.client.SkinModel;
import com.naocraftlab.skins.client.TextureRegistry;
import com.naocraftlab.skins.client.VanillaPlayerModelTransform;
import java.util.Objects;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.model.ElytraModel;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;
import org.joml.Quaternionf;
import org.joml.Vector3f;


public final class Minecraft1201SimplePreviewRenderer implements PreviewRenderer<GuiGraphics> {
    private static final int FULL_BRIGHT = 0x00F000F0;
    private static final float MODEL_HEIGHT = 2.125F;
    private static final float FIT_PADDING = 0.97F;
    private static final float ANCHOR_Y = 0.88F;
    private static final float GUI_DEPTH = 120.0F;
    private static final PlayerPreviewLighting.Rig LIGHTING =
            PlayerPreviewLighting.centeredFront();
    private static final Vector3f PRIMARY_LIGHT = lightDirection(LIGHTING.primary());
    private static final Vector3f FILL_LIGHT = lightDirection(LIGHTING.fill());
    private static final VanillaPlayerModelTransform.Operations<PoseStack> POSE_OPERATIONS =
            new VanillaPlayerModelTransform.Operations<>() {
                @Override
                public void scale(PoseStack pose, float x, float y, float z) {
                    pose.scale(x, y, z);
                }

                @Override
                public void rotateZThenX(PoseStack pose, float zRadians, float xRadians) {
                    pose.mulPose(new Quaternionf().rotateZ(zRadians).rotateX(xRadians));
                }

                @Override
                public void rotateY(PoseStack pose, float radians) {
                    pose.mulPose(new Quaternionf().rotateY(radians));
                }

                @Override
                public void translate(PoseStack pose, float x, float y, float z) {
                    pose.translate(x, y, z);
                }
            };

    private final PlayerModel<LivingEntity> classicModel;
    private final PlayerModel<LivingEntity> slimModel;
    private final ModelPart classicCloak;
    private final ModelPart slimCloak;
    private final ElytraModel<LivingEntity> elytraModel;

    public Minecraft1201SimplePreviewRenderer() {
        Minecraft minecraft = Minecraft.getInstance();
        ModelPart classicRoot = minecraft.getEntityModels().bakeLayer(ModelLayers.PLAYER);
        ModelPart slimRoot = minecraft.getEntityModels().bakeLayer(ModelLayers.PLAYER_SLIM);
        classicModel = new PlayerModel<>(classicRoot, false);
        slimModel = new PlayerModel<>(slimRoot, true);
        classicCloak = classicRoot.getChild("cloak");
        slimCloak = slimRoot.getChild("cloak");
        elytraModel = new ElytraModel<>(minecraft.getEntityModels().bakeLayer(ModelLayers.ELYTRA));
    }

    @Override
    public void render(GuiGraphics graphics, PreviewRequest request) {
        Objects.requireNonNull(graphics, "graphics");
        Objects.requireNonNull(request, "request");
        PreviewAppearance appearance = request.appearance();
        PlayerModel<LivingEntity> model = appearance.model() == SkinModel.SLIM
                ? slimModel
                : classicModel;
        configureLayers(model, appearance.outerLayerVisibility());

        PoseStack pose = graphics.pose();
        pose.pushPose();
        try {
            graphics.enableScissor(
                    request.left(),
                    request.top(),
                    request.left() + request.width(),
                    request.top() + request.height());
            float scale = FIT_PADDING * request.height() / MODEL_HEIGHT * request.scale();
            pose.translate(
                    request.left() + request.width() / 2.0F,
                    request.top() + request.height() * ANCHOR_Y,
                    GUI_DEPTH);
            VanillaPlayerModelTransform.apply(
                    pose,
                    scale,
                    request.yawDegrees(),
                    request.pitchDegrees(),
                    POSE_OPERATIONS);

            RenderSystem.setShaderLights(PRIMARY_LIGHT, FILL_LIGHT);
            MultiBufferSource.BufferSource buffers = graphics.bufferSource();
            ResourceLocation skin = location(appearance.skin());
            model.renderToBuffer(
                    pose,
                    buffers.getBuffer(model.renderType(skin)),
                    FULL_BRIGHT,
                    OverlayTexture.NO_OVERLAY,
                    1.0F,
                    1.0F,
                    1.0F,
                    1.0F);

            appearance.cape().ifPresent(capeHandle -> renderBackEquipment(
                    pose,
                    buffers,
                    model,
                    model == slimModel ? slimCloak : classicCloak,
                    appearance.capeMode(),
                    location(capeHandle)));
            buffers.endBatch();
        } finally {
            graphics.disableScissor();
            pose.popPose();
            Lighting.setupFor3DItems();
        }
    }

    private void renderBackEquipment(
            PoseStack pose,
            MultiBufferSource.BufferSource buffers,
            PlayerModel<LivingEntity> model,
            ModelPart cloak,
            CapeMode capeMode,
            ResourceLocation capeTexture) {
        if (capeMode == CapeMode.CAPE) {
            cloak.visible = true;
            cloak.render(
                    pose,
                    buffers.getBuffer(model.renderType(capeTexture)),
                    FULL_BRIGHT,
                    OverlayTexture.NO_OVERLAY);
            cloak.visible = false;
        } else if (capeMode == CapeMode.ELYTRA) {
            elytraModel.renderToBuffer(
                    pose,
                    buffers.getBuffer(elytraModel.renderType(capeTexture)),
                    FULL_BRIGHT,
                    OverlayTexture.NO_OVERLAY,
                    1.0F,
                    1.0F,
                    1.0F,
                    1.0F);
        }
    }

    private static void configureLayers(PlayerModel<?> model, OuterLayerVisibility outerLayer) {
        model.head.resetPose();
        model.body.resetPose();
        model.rightArm.resetPose();
        model.leftArm.resetPose();
        model.rightLeg.resetPose();
        model.leftLeg.resetPose();
        model.hat.resetPose();
        model.jacket.resetPose();
        model.rightSleeve.resetPose();
        model.leftSleeve.resetPose();
        model.rightPants.resetPose();
        model.leftPants.resetPose();
        model.setAllVisible(true);
        model.crouching = false;
        model.riding = false;
        model.young = false;
        model.hat.visible = outerLayer.visible(OuterLayerPart.HEAD);
        model.jacket.visible = outerLayer.visible(OuterLayerPart.BODY);
        model.leftSleeve.visible = outerLayer.visible(OuterLayerPart.LEFT_ARM);
        model.rightSleeve.visible = outerLayer.visible(OuterLayerPart.RIGHT_ARM);
        model.leftPants.visible = outerLayer.visible(OuterLayerPart.LEFT_LEG);
        model.rightPants.visible = outerLayer.visible(OuterLayerPart.RIGHT_LEG);
    }

    private static ResourceLocation location(TextureRegistry.TextureHandle handle) {
        ResourceLocation location = ResourceLocation.tryParse(handle.location());
        if (location == null) {
            throw new IllegalArgumentException("Invalid texture location");
        }
        return location;
    }

    private static Vector3f lightDirection(PlayerPreviewLighting.Direction direction) {
        return new Vector3f(direction.x(), direction.y(), direction.z());
    }
}

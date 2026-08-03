package com.naocraftlab.skins.mc1211;

import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.naocraftlab.skins.client.OuterLayerPart;
import com.naocraftlab.skins.client.OuterLayerVisibility;
import com.naocraftlab.skins.client.PreviewRenderer;
import com.naocraftlab.skins.client.SkinModel;
import com.naocraftlab.skins.client.VanillaPlayerModelTransform;
import java.util.Objects;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.model.ElytraModel;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import org.joml.Quaternionf;


public final class Minecraft1211SimplePreviewRenderer implements PreviewRenderer<GuiGraphics> {
    private static final float MODEL_HEIGHT = 2.125F;
    private static final float FIT_PADDING = 0.97F;
    private static final float ANCHOR_Y = 0.88F;
    private static final float GUI_DEPTH = 120.0F;
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

    private final PlayerModel<?> classic;
    private final PlayerModel<?> slim;
    private final ElytraModel<?> elytra;

    public Minecraft1211SimplePreviewRenderer(Minecraft minecraft) {
        Objects.requireNonNull(minecraft, "minecraft");
        classic = new PlayerModel<>(minecraft.getEntityModels().bakeLayer(ModelLayers.PLAYER), false);
        slim = new PlayerModel<>(minecraft.getEntityModels().bakeLayer(ModelLayers.PLAYER_SLIM), true);
        elytra = new ElytraModel<>(minecraft.getEntityModels().bakeLayer(ModelLayers.ELYTRA));
    }

    @Override
    public void render(GuiGraphics graphics, PreviewRequest request) {
        Objects.requireNonNull(graphics, "graphics");
        Objects.requireNonNull(request, "request");
        ResourceLocation skin = parseTexture(request.appearance().skin().location());
        ResourceLocation cape = request.appearance().cape()
                .map(handle -> parseTexture(handle.location()))
                .orElse(null);
        PlayerModel<?> player = request.appearance().model() == SkinModel.SLIM ? slim : classic;

        configurePlayerModel(player, request.appearance().outerLayerVisibility());
        PoseStack pose = graphics.pose();
        pose.pushPose();
        try {
            graphics.enableScissor(
                    request.left(),
                    request.top(),
                    request.left() + request.width(),
                    request.top() + request.height());
            float modelScale = FIT_PADDING * request.height() / MODEL_HEIGHT * request.scale();
            pose.translate(
                    request.left() + request.width() / 2.0F,
                    request.top() + request.height() * ANCHOR_Y,
                    GUI_DEPTH);
            VanillaPlayerModelTransform.apply(
                    pose,
                    modelScale,
                    request.yawDegrees(),
                    request.pitchDegrees(),
                    POSE_OPERATIONS);

            Lighting.setupForEntityInInventory();
            MultiBufferSource.BufferSource buffers = graphics.bufferSource();

            if (cape != null && request.appearance().capeMode() == CapeMode.CAPE) {
                VertexConsumer capeBuffer = buffers.getBuffer(RenderType.entitySolid(cape));
                player.renderCloak(
                        pose, capeBuffer, LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY);
            } else if (cape != null && request.appearance().capeMode() == CapeMode.ELYTRA) {
                VertexConsumer capeBuffer = buffers.getBuffer(RenderType.entitySolid(cape));
                elytra.renderToBuffer(
                        pose,
                        capeBuffer,
                        LightTexture.FULL_BRIGHT,
                        OverlayTexture.NO_OVERLAY,
                        0xFFFFFFFF);
            }

            VertexConsumer skinBuffer = buffers.getBuffer(RenderType.entityTranslucent(skin));
            player.renderToBuffer(
                    pose,
                    skinBuffer,
                    LightTexture.FULL_BRIGHT,
                    OverlayTexture.NO_OVERLAY,
                    0xFFFFFFFF);
            graphics.flush();
        } finally {
            graphics.disableScissor();
            pose.popPose();
            Lighting.setupFor3DItems();
        }
    }

    private static void configurePlayerModel(
            PlayerModel<?> player, OuterLayerVisibility outerLayer) {
        player.head.resetPose();
        player.body.resetPose();
        player.rightArm.resetPose();
        player.leftArm.resetPose();
        player.rightLeg.resetPose();
        player.leftLeg.resetPose();
        player.hat.resetPose();
        player.jacket.resetPose();
        player.rightSleeve.resetPose();
        player.leftSleeve.resetPose();
        player.rightPants.resetPose();
        player.leftPants.resetPose();
        player.setAllVisible(true);
        player.crouching = false;
        player.riding = false;
        player.young = false;

        player.rightArm.zRot = 0.06F;
        player.leftArm.zRot = -0.06F;
        player.rightLeg.zRot = 0.01F;
        player.leftLeg.zRot = -0.01F;
        player.hat.visible = outerLayer.visible(OuterLayerPart.HEAD);
        player.jacket.visible = outerLayer.visible(OuterLayerPart.BODY);
        player.rightSleeve.visible = outerLayer.visible(OuterLayerPart.RIGHT_ARM);
        player.leftSleeve.visible = outerLayer.visible(OuterLayerPart.LEFT_ARM);
        player.rightPants.visible = outerLayer.visible(OuterLayerPart.RIGHT_LEG);
        player.leftPants.visible = outerLayer.visible(OuterLayerPart.LEFT_LEG);
    }

    private static ResourceLocation parseTexture(String value) {
        ResourceLocation location = ResourceLocation.tryParse(value);
        if (location == null) {
            throw new IllegalArgumentException("Invalid preview texture location");
        }
        return location;
    }
}

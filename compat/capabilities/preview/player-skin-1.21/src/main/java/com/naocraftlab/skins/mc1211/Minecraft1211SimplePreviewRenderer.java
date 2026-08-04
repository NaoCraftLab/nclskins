package com.naocraftlab.skins.mc1211;

import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.naocraftlab.skins.client.BackEquipmentPreviewRenderer;
import com.naocraftlab.skins.client.CenteredPlayerPreviewGeometry;
import com.naocraftlab.skins.client.OuterLayerPart;
import com.naocraftlab.skins.client.OuterLayerVisibility;
import com.naocraftlab.skins.client.PreviewRenderer;
import com.naocraftlab.skins.client.SkinModel;
import com.naocraftlab.skins.client.VanillaBackEquipmentTransform;
import com.naocraftlab.skins.client.VanillaPlayerModelTransform;
import java.util.Objects;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.model.ElytraModel;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import org.joml.Quaternionf;


public final class Minecraft1211SimplePreviewRenderer
        implements PreviewRenderer<GuiGraphics>, BackEquipmentPreviewRenderer<GuiGraphics> {
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
    private static final VanillaBackEquipmentTransform.Operations<PoseStack>
            BACK_EQUIPMENT_OPERATIONS = new VanillaBackEquipmentTransform.Operations<>() {
                @Override
                public void scale(PoseStack pose, float x, float y, float z) {
                    pose.scale(x, y, z);
                }

                @Override
                public void rotateZThenX(
                        PoseStack pose, float zRadians, float xRadians) {
                    pose.mulPose(new Quaternionf().rotateZ(zRadians).rotateX(xRadians));
                }

                @Override
                public void rotateX(PoseStack pose, float radians) {
                    pose.mulPose(new Quaternionf().rotateX(radians));
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
    private final ModelPart classicCloak;
    private final ModelPart slimCloak;
    private final ModelPart elytraRoot;
    private final ElytraModel<?> elytra;

    public Minecraft1211SimplePreviewRenderer(Minecraft minecraft) {
        Objects.requireNonNull(minecraft, "minecraft");
        ModelPart classicRoot = minecraft.getEntityModels().bakeLayer(ModelLayers.PLAYER);
        ModelPart slimRoot = minecraft.getEntityModels().bakeLayer(ModelLayers.PLAYER_SLIM);
        classic = new PlayerModel<>(classicRoot, false);
        slim = new PlayerModel<>(slimRoot, true);
        classicCloak = classicRoot.getChild("cloak");
        slimCloak = slimRoot.getChild("cloak");
        elytraRoot = minecraft.getEntityModels().bakeLayer(ModelLayers.ELYTRA);
        elytra = new ElytraModel<>(elytraRoot);
    }

    @Override
    public void render(GuiGraphics graphics, PreviewRequest request) {
        Objects.requireNonNull(graphics, "graphics");
        Objects.requireNonNull(request, "request");
        ResourceLocation skin = parseTexture(request.appearance().skin().location());
        ResourceLocation cape = request.appearance().cape()
                .map(handle -> parseTexture(handle.location()))
                .orElse(null);
        boolean slimModel = request.appearance().model() == SkinModel.SLIM;
        PlayerModel<?> player = slimModel ? slim : classic;
        ModelPart cloak = slimModel ? slimCloak : classicCloak;

        configurePlayerModel(player, request.appearance().outerLayerVisibility());
        PoseStack pose = graphics.pose();
        pose.pushPose();
        try {
            CenteredPlayerPreviewGeometry.Layout layout =
                    CenteredPlayerPreviewGeometry.fit(
                            request.left(),
                            request.top(),
                            request.width(),
                            request.height(),
                            request.scale());
            pose.translate(
                    layout.centerX(),
                    layout.centerY(),
                    GUI_DEPTH);
            VanillaPlayerModelTransform.applyCentered(
                    pose,
                    layout.scale(),
                    request.yawDegrees(),
                    request.pitchDegrees(),
                    POSE_OPERATIONS);

            Lighting.setupForEntityInInventory();
            MultiBufferSource.BufferSource buffers = graphics.bufferSource();

            VertexConsumer skinBuffer = buffers.getBuffer(RenderType.entityTranslucent(skin));
            player.renderToBuffer(
                    pose,
                    skinBuffer,
                    LightTexture.FULL_BRIGHT,
                    OverlayTexture.NO_OVERLAY,
                    0xFFFFFFFF);
            if (cape != null) {
                renderBackEquipment(
                        pose,
                        buffers,
                        cloak,
                        request.appearance().capeMode(),
                        cape,
                        false);
            }
            graphics.flush();
        } finally {
            pose.popPose();
            Lighting.setupFor3DItems();
        }
    }

    @Override
    public void render(GuiGraphics graphics, BackEquipmentPreviewRenderer.Request request) {
        Objects.requireNonNull(graphics, "graphics");
        Objects.requireNonNull(request, "request");
        PoseStack pose = graphics.pose();
        pose.pushPose();
        try {
            float scale = VanillaBackEquipmentTransform.fitScale(
                    request.width(), request.height());
            pose.translate(
                    request.left() + request.width() / 2.0F,
                    request.top() + request.height() / 2.0F,
                    GUI_DEPTH);
            VanillaBackEquipmentTransform.applyStandalone(
                    pose, scale, BACK_EQUIPMENT_OPERATIONS);

            Lighting.setupForEntityInInventory();
            MultiBufferSource.BufferSource buffers = graphics.bufferSource();
            ResourceLocation texture = parseTexture(request.texture().location());
            renderBackEquipment(
                    pose,
                    buffers,
                    classicCloak,
                    request.mode() == BackEquipmentPreviewRenderer.Mode.CAPE
                            ? CapeMode.CAPE
                            : CapeMode.ELYTRA,
                    texture,
                    true);
            graphics.flush();
        } finally {
            pose.popPose();
            Lighting.setupFor3DItems();
        }
    }

    private void renderBackEquipment(
            PoseStack pose,
            MultiBufferSource.BufferSource buffers,
            ModelPart cloak,
            CapeMode capeMode,
            ResourceLocation texture,
            boolean standalone) {
        if (capeMode == CapeMode.CAPE) {
            pose.pushPose();
            try {
                if (standalone) {
                    VanillaBackEquipmentTransform.applyStandaloneCapeAttachment(
                            pose, BACK_EQUIPMENT_OPERATIONS);
                } else {
                    VanillaBackEquipmentTransform.applyCapeAttachment(
                            pose, BACK_EQUIPMENT_OPERATIONS);
                }
                cloak.resetPose();
                cloak.visible = true;
                cloak.render(
                        pose,
                        buffers.getBuffer(RenderType.entitySolid(texture)),
                        LightTexture.FULL_BRIGHT,
                        OverlayTexture.NO_OVERLAY);
                cloak.visible = false;
            } finally {
                pose.popPose();
            }
        } else if (capeMode == CapeMode.ELYTRA) {
            pose.pushPose();
            try {
                VanillaBackEquipmentTransform.applyElytraAttachment(
                        pose, BACK_EQUIPMENT_OPERATIONS);
                configureElytra();
                elytra.renderToBuffer(
                        pose,
                        buffers.getBuffer(RenderType.armorCutoutNoCull(texture)),
                        LightTexture.FULL_BRIGHT,
                        OverlayTexture.NO_OVERLAY,
                        0xFFFFFFFF);
            } finally {
                pose.popPose();
            }
        }
    }

    private void configureElytra() {
        elytraRoot.getAllParts().forEach(ModelPart::resetPose);
        elytra.young = false;
        elytra.riding = false;
        elytra.attackTime = 0.0F;
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

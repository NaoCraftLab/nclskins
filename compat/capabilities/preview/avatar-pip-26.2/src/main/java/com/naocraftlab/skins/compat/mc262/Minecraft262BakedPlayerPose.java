package com.naocraftlab.skins.compat.mc262;

import com.mojang.blaze3d.vertex.PoseStack;
import com.naocraftlab.skins.client.CenteredPipPreviewTransform;
import com.naocraftlab.skins.client.OuterLayerPart;
import com.naocraftlab.skins.client.OuterLayerVisibility;
import com.naocraftlab.skins.client.PreviewRenderer;
import net.minecraft.client.model.Model;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.entity.state.HumanoidRenderState;
import org.joml.Quaternionf;

public final class Minecraft262BakedPlayerPose {
    private static final CenteredPipPreviewTransform.Operations<PoseStack> OPERATIONS =
            new CenteredPipPreviewTransform.Operations<>() {
                @Override
                public void translate(PoseStack pose, float x, float y, float z) {
                    pose.translate(x, y, z);
                }

                @Override
                public void rotateY(PoseStack pose, float radians) {
                    pose.mulPose(new Quaternionf().rotateY(radians));
                }
            };

    private Minecraft262BakedPlayerPose() {
    }

    public static void configure(NclBakedPlayerRenderState state) {
        Model<?> player = state.playerModel() != null
                ? state.playerModel()
                : state.simplePlayerModel();
        if (player != null) {
            configureOuterLayer(player.root(), state.outerLayerVisibility());
        }
        Model<?> attachment = state.attachmentPlayerModel() != null
                ? state.attachmentPlayerModel()
                : state.simpleAttachmentModel();
        if (attachment != null) {
            attachment.resetPose();
            if (state.capePoseModel() != null) {
                state.capePoseModel().setupAnim(new AvatarRenderState());
            } else if (state.elytraPoseModel() != null) {
                HumanoidRenderState neutral = new HumanoidRenderState();
                neutral.elytraRotX = CenteredPipPreviewTransform.ELYTRA_ROT_X;
                neutral.elytraRotY = CenteredPipPreviewTransform.ELYTRA_ROT_Y;
                neutral.elytraRotZ = CenteredPipPreviewTransform.ELYTRA_ROT_Z;
                state.elytraPoseModel().setupAnim(neutral);
            }
        }
    }

    public static void applyPitch(PoseStack pose, float pitchDegrees) {
        pose.mulPose(new Quaternionf().rotateX(
                CenteredPipPreviewTransform.modelPitchRadians(pitchDegrees)));
    }

    public static void applyPlayer(PoseStack pose, NclBakedPlayerRenderState state) {
        CenteredPipPreviewTransform.applyPlayerPose(pose, state.yawDegrees(), OPERATIONS);
    }

    public static void applyAttachment(PoseStack pose, PreviewRenderer.CapeMode mode) {
        CenteredPipPreviewTransform.applyAttachment(pose, mode, OPERATIONS);
    }

    public static void applyStandaloneEquipment(PoseStack pose, NclBakedPlayerRenderState state) {
        CenteredPipPreviewTransform.applyStandaloneEquipmentPose(
                pose, state.yawDegrees(), OPERATIONS);
    }

    private static void configureOuterLayer(
            ModelPart root, OuterLayerVisibility visible) {
        root.resetPose();
        root.getChild("head").getChild("hat").visible = visible.visible(OuterLayerPart.HEAD);
        root.getChild("body").getChild("jacket").visible = visible.visible(OuterLayerPart.BODY);
        root.getChild("left_arm").getChild("left_sleeve").visible =
                visible.visible(OuterLayerPart.LEFT_ARM);
        root.getChild("right_arm").getChild("right_sleeve").visible =
                visible.visible(OuterLayerPart.RIGHT_ARM);
        root.getChild("left_leg").getChild("left_pants").visible =
                visible.visible(OuterLayerPart.LEFT_LEG);
        root.getChild("right_leg").getChild("right_pants").visible =
                visible.visible(OuterLayerPart.RIGHT_LEG);
    }
}

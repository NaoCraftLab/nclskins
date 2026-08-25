package com.naocraftlab.skins.compat.client.identifier.submission;

import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.vertex.PoseStack;
import com.naocraftlab.skins.client.CenteredPipPreviewTransform;
import com.naocraftlab.skins.client.OuterLayerPart;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.render.pip.PictureInPictureRenderer;
import net.minecraft.client.gui.render.state.GuiRenderState;
import net.minecraft.client.model.Model;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.entity.state.HumanoidRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import org.joml.Quaternionf;


public final class BakedPreviewRenderer
        extends PictureInPictureRenderer<BakedPreviewRenderState> {
    private final Set<BakedPreviewTarget> targets =
            Collections.newSetFromMap(new WeakHashMap<>());

    public BakedPreviewRenderer(
            MultiBufferSource.BufferSource bufferSource) {
        super(bufferSource);
    }

    @Override
    public Class<BakedPreviewRenderState> getRenderStateClass() {
        return BakedPreviewRenderState.class;
    }

    @Override
    public void prepare(
            BakedPreviewRenderState state,
            GuiRenderState guiRenderState,
            int guiScale) {
        BakedPreviewTarget target = state.target();
        TargetRenderer renderer = target.acquire(
                this,
                () -> {
                    targets.add(target);
                    return new TargetRenderer(bufferSource);
                },
                TargetRenderer.class);
        renderer.prepare(state, guiRenderState, guiScale);
    }

    @Override
    protected float getTranslateY(int textureHeight, int guiScale) {
        return textureHeight / 2.0F;
    }

    @Override
    protected void renderToTexture(
            BakedPreviewRenderState state, PoseStack pose) {
        throw new IllegalStateException("Dispatcher render target must not be used");
    }

    @Override
    public void close() {
        for (BakedPreviewTarget target : new ArrayList<>(targets)) {
            target.release(this);
        }
        targets.clear();
        super.close();
    }

    @Override
    protected String getTextureLabel() {
        return "NCL Skins 1.21.11 baked preview dispatcher";
    }

    private static final class TargetRenderer
            extends PictureInPictureRenderer<BakedPreviewRenderState> {
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

        private TargetRenderer(MultiBufferSource.BufferSource bufferSource) {
            super(bufferSource);
        }

        @Override
        public Class<BakedPreviewRenderState> getRenderStateClass() {
            return BakedPreviewRenderState.class;
        }

        @Override
        protected float getTranslateY(int textureHeight, int guiScale) {
            return textureHeight / 2.0F;
        }

        @Override
        protected void renderToTexture(
                BakedPreviewRenderState state, PoseStack pose) {
            Minecraft.getInstance().gameRenderer.getLighting().setupFor(Lighting.Entry.PLAYER_SKIN);
            configure(state);
            pose.pushPose();
            try {
                pose.mulPose(new Quaternionf().rotateX(
                        CenteredPipPreviewTransform.modelPitchRadians(state.pitchDegrees())));
                if (state.standaloneEquipment()) {
                    CenteredPipPreviewTransform.applyStandaloneEquipmentPose(
                            pose, state.yawDegrees(), OPERATIONS);
                } else {
                    CenteredPipPreviewTransform.applyPlayerPose(
                            pose, state.yawDegrees(), OPERATIONS);
                }
                if (state.attachmentModel() != null) {
                    pose.pushPose();
                    try {
                        CenteredPipPreviewTransform.applyAttachment(
                                pose, state.capeMode(), OPERATIONS);
                        Model<?> attachment = state.attachmentModel();
                        attachment.renderToBuffer(
                                pose,
                                bufferSource.getBuffer(
                                        attachment.renderType(state.attachmentTexture())),
                                0x00F000F0,
                                OverlayTexture.NO_OVERLAY);
                    } finally {
                        pose.popPose();
                    }
                }
                if (!state.standaloneEquipment()) {
                    PlayerModel player = state.playerModel();
                    player.renderToBuffer(
                            pose,
                            bufferSource.getBuffer(player.renderType(state.skinTexture())),
                            0x00F000F0,
                            OverlayTexture.NO_OVERLAY);
                }
                bufferSource.endBatch();
            } finally {
                pose.popPose();
            }
        }

        private static void configure(BakedPreviewRenderState state) {
            PlayerModel player = state.playerModel();
            if (player != null) {
                player.resetPose();
                player.setAllVisible(true);
                player.hat.visible = state.outerLayerVisibility().visible(OuterLayerPart.HEAD);
                player.jacket.visible = state.outerLayerVisibility().visible(OuterLayerPart.BODY);
                player.leftSleeve.visible =
                        state.outerLayerVisibility().visible(OuterLayerPart.LEFT_ARM);
                player.rightSleeve.visible =
                        state.outerLayerVisibility().visible(OuterLayerPart.RIGHT_ARM);
                player.leftPants.visible =
                        state.outerLayerVisibility().visible(OuterLayerPart.LEFT_LEG);
                player.rightPants.visible =
                        state.outerLayerVisibility().visible(OuterLayerPart.RIGHT_LEG);
            }
            Model<?> attachment = state.attachmentModel();
            if (attachment == null) {
                return;
            }
            attachment.resetPose();
            if (attachment instanceof PlayerModel playerAttachment) {
                playerAttachment.setAllVisible(true);
            }
            if (state.capePoseModel() != null) {
                state.capePoseModel().setupAnim(new AvatarRenderState());
            } else {
                HumanoidRenderState neutral = new HumanoidRenderState();
                neutral.elytraRotX = CenteredPipPreviewTransform.ELYTRA_ROT_X;
                neutral.elytraRotY = CenteredPipPreviewTransform.ELYTRA_ROT_Y;
                neutral.elytraRotZ = CenteredPipPreviewTransform.ELYTRA_ROT_Z;
                state.elytraPoseModel().setupAnim(neutral);
            }
        }

        @Override
        protected String getTextureLabel() {
            return "NCL Skins 1.21.11 baked preview target";
        }
    }
}

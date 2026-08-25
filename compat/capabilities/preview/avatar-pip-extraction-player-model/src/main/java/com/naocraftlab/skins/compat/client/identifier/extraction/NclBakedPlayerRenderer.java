package com.naocraftlab.skins.compat.client.identifier.extraction;

import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.vertex.PoseStack;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.render.pip.PictureInPictureRenderer;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.state.gui.GuiRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;

public final class NclBakedPlayerRenderer
        extends PictureInPictureRenderer<NclBakedPlayerRenderState> {
    private final Set<NclBakedPlayerTarget> targets =
            Collections.newSetFromMap(new WeakHashMap<>());

    public NclBakedPlayerRenderer(MultiBufferSource.BufferSource bufferSource) {
        super(bufferSource);
    }

    @Override
    public Class<NclBakedPlayerRenderState> getRenderStateClass() {
        return NclBakedPlayerRenderState.class;
    }

    @Override
    public void prepare(NclBakedPlayerRenderState state, GuiRenderState guiRenderState, int guiScale) {
        NclBakedPlayerTarget target = state.target();
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
    protected void renderToTexture(NclBakedPlayerRenderState state, PoseStack pose) {
        throw new IllegalStateException("Dispatcher render target must not be used");
    }

    @Override
    public void close() {
        for (NclBakedPlayerTarget target : new ArrayList<>(targets)) {
            target.release(this);
        }
        targets.clear();
        super.close();
    }

    @Override
    protected String getTextureLabel() {
        return "NCL Skins baked player dispatcher";
    }

    private static final class TargetRenderer
            extends PictureInPictureRenderer<NclBakedPlayerRenderState> {
        private TargetRenderer(MultiBufferSource.BufferSource bufferSource) {
            super(bufferSource);
        }

        @Override
        public Class<NclBakedPlayerRenderState> getRenderStateClass() {
            return NclBakedPlayerRenderState.class;
        }

        @Override
        protected float getTranslateY(int textureHeight, int guiScale) {
            return textureHeight / 2.0F;
        }

        @Override
        protected void renderToTexture(NclBakedPlayerRenderState state, PoseStack pose) {
            Minecraft.getInstance().gameRenderer.getLighting().setupFor(Lighting.Entry.PLAYER_SKIN);
            BakedPlayerPose.configure(state);
            pose.pushPose();
            try {
                BakedPlayerPose.applyPitch(pose, state.pitchDegrees());
                if (state.standaloneEquipment()) {
                    BakedPlayerPose.applyStandaloneEquipment(pose, state);
                } else {
                    BakedPlayerPose.applyPlayer(pose, state);
                }
                PlayerModel attachment = state.attachmentPlayerModel();
                if (attachment != null) {
                    pose.pushPose();
                    try {
                        BakedPlayerPose.applyAttachment(pose, state.capeMode());
                        attachment.renderToBuffer(
                                pose,
                                bufferSource.getBuffer(attachment.renderType(state.attachmentTexture())),
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

        @Override
        protected String getTextureLabel() {
            return "NCL Skins baked player target";
        }
    }
}

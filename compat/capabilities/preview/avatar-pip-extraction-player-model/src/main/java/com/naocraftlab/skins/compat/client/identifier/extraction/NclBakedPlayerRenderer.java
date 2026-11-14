package com.naocraftlab.skins.compat.client.identifier.extraction;

import com.naocraftlab.skins.compat.client.identifier.foil.CapeFoilBuffer;
import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.render.pip.PictureInPictureRenderer;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.state.gui.GuiRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;

public final class NclBakedPlayerRenderer
        extends PictureInPictureRenderer<NclBakedPlayerRenderState> {
    private final NclBakedPlayerTarget.ContextLease context = new NclBakedPlayerTarget.ContextLease();
    private boolean closed;

    public NclBakedPlayerRenderer(MultiBufferSource.BufferSource bufferSource) {
        super(bufferSource);
    }

    @Override
    public Class<NclBakedPlayerRenderState> getRenderStateClass() {
        return NclBakedPlayerRenderState.class;
    }

    @Override
    public void prepare(NclBakedPlayerRenderState state, GuiRenderState guiRenderState, int guiScale) {
        var owner = context.join(guiRenderState);
        var completion = state.completion();
        if (completion != null && !completion.current()) return;
        NclBakedPlayerTarget target = state.target();
        try {
        TargetRenderer renderer = owner.acquire(
                target, () -> new TargetRenderer(bufferSource), TargetRenderer.class);
        target.retain();
        renderer.primaryRendered = false;
        if (completion != null && completion.reusesConfirmedOutput()) {
            if (target.owns(owner, renderer) && renderer.hasPreparedOutput()) renderer.blitTexture(state, guiRenderState);
            return;
        }
        renderer.outputReady = false;
        renderer.prepare(state, guiRenderState, guiScale);
        if (completion != null && renderer.primaryRendered && renderer.hasPreparedOutput()) completion.accept(
                new com.naocraftlab.skins.client.PreviewModelFrames.Body(
                        com.naocraftlab.skins.client.PreviewRenderer.BodyStatus.ORDINARY,
                        () -> target.owns(owner, renderer) && renderer.hasPreparedOutput()));
        } catch (RuntimeException | Error failure) {
            if (completion != null) completion.accept(new com.naocraftlab.skins.client.PreviewModelFrames.Body(
                    com.naocraftlab.skins.client.PreviewRenderer.BodyStatus.FAILED, () -> true));
            throw failure;
        }
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
        if (closed) return;
        closed = true;
        Throwable failure = null;
        try {
            context.close();
        } catch (RuntimeException | Error error) {
            failure = error;
        }
        try {
            super.close();
        } catch (RuntimeException | Error error) {
            if (failure == null) failure = error;
            else if (failure != error) failure.addSuppressed(error);
        }
        if (failure instanceof RuntimeException error) throw error;
        if (failure instanceof Error error) throw error;
    }

    @Override
    protected String getTextureLabel() {
        return "NCL Skins baked player dispatcher";
    }

    private static final class TargetRenderer
            extends PictureInPictureRenderer<NclBakedPlayerRenderState> {
        private boolean primaryRendered;
        private boolean outputReady;
        private boolean hasPreparedOutput() {
            return outputReady;
        }
        private TargetRenderer(MultiBufferSource.BufferSource bufferSource) {
            super(bufferSource);
        }

        @Override
        public Class<NclBakedPlayerRenderState> getRenderStateClass() {
            return NclBakedPlayerRenderState.class;
        }

        @Override
        protected boolean textureIsReadyToBlit(NclBakedPlayerRenderState state) {
            var completion = state.completion();
            if (completion != null && completion.reusesConfirmedOutput())
                return hasPreparedOutput() && completion.current();
            return (completion == null || !completion.needsOwnInputPreparation())
                    && super.textureIsReadyToBlit(state);
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
                                CapeFoilBuffer.buffer(bufferSource,
                                        state.attachmentTexture(), state.capeGlint(),
                                        attachment.renderType(state.attachmentTexture())),
                                0x00F000F0,
                                OverlayTexture.NO_OVERLAY);
                        if (state.standaloneEquipment()) primaryRendered = true;
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
                    primaryRendered = true;
                }
                bufferSource.endBatch();
            } finally {
                pose.popPose();
            }
        }

        @Override
        protected void blitTexture(NclBakedPlayerRenderState state, GuiRenderState guiRenderState) {
            var completion = state.completion();
            if (completion != null && completion.reusesConfirmedOutput()) {
                if (!hasPreparedOutput() || !completion.current()) return;
            } else if (!primaryRendered || completion != null && (!completion.current()
                    || state.attachmentTexture() != null
                            && !completion.currentInput(state.attachmentTexture().toString()))) return;
            super.blitTexture(state, guiRenderState);
            outputReady = true;
        }

        @Override
        protected String getTextureLabel() {
            return "NCL Skins baked player target";
        }
    }
}

package com.naocraftlab.skins.compat.client.identifier.submission;

import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.vertex.PoseStack;
import com.naocraftlab.skins.client.EditorPreviewLayerGuard;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.render.pip.PictureInPictureRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import net.minecraft.client.renderer.state.CameraRenderState;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;


public final class LivePreviewRenderer extends PictureInPictureRenderer<LivePreviewRenderState> {
    private final java.util.Set<com.naocraftlab.skins.client.ScreenOwnedRenderTarget> targets =
            java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>());
    private final com.naocraftlab.skins.client.ScreenOwnedRenderTarget legacyTarget =
            new com.naocraftlab.skins.client.ScreenOwnedRenderTarget();

    public LivePreviewRenderer(MultiBufferSource.BufferSource bufferSource) { super(bufferSource); }

    @Override public Class<LivePreviewRenderState> getRenderStateClass() { return LivePreviewRenderState.class; }

    @Override public void prepare(LivePreviewRenderState state,
            net.minecraft.client.gui.render.state.GuiRenderState guiRenderState, int guiScale) {
        if (!state.previewContext().current()) return;
        var target = state.target() == null ? legacyTarget : state.target();
        try {
            var renderer = target.acquire(this, () -> {
                targets.add(target);
                return new TargetRenderer(bufferSource);
            }, TargetRenderer.class);
            com.naocraftlab.skins.compat.client.identifier.preview.PreviewConsumerFrame.retain(target);
            renderer.candidate = null;
            renderer.prepare(state, guiRenderState, guiScale);
            var candidate = renderer.candidate;
            if (candidate != null) state.previewContext().displayed(
                    new com.naocraftlab.skins.client.PreviewModelFrames.Body(candidate.status(),
                            () -> target.owns(this, renderer) && candidate.current().getAsBoolean()));
        } catch (RuntimeException | Error failure) {
            state.previewContext().displayed(new com.naocraftlab.skins.client.PreviewModelFrames.Body(
                    com.naocraftlab.skins.client.PreviewRenderer.BodyStatus.FAILED, () -> true));
            throw failure;
        }
    }

    @Override protected float getTranslateY(int textureHeight, int guiScale) { return textureHeight / 2.0F; }
    @Override protected void renderToTexture(LivePreviewRenderState state, PoseStack pose) {
        throw new IllegalStateException("Dispatcher render target must not be used");
    }
    @Override protected String getTextureLabel() { return "NCL Skins live preview dispatcher"; }
    @Override public void close() {
        for (var target : new java.util.ArrayList<>(targets)) target.release(this);
        targets.clear(); legacyTarget.close(); super.close();
    }

    private static final class TargetRenderer extends PictureInPictureRenderer<LivePreviewRenderState> {
        private com.naocraftlab.skins.client.PreviewModelFrames.Body candidate;
        private TargetRenderer(MultiBufferSource.BufferSource buffers) { super(buffers); }
        @Override public Class<LivePreviewRenderState> getRenderStateClass() { return LivePreviewRenderState.class; }
        @Override protected float getTranslateY(int textureHeight, int guiScale) { return textureHeight / 2.0F; }
        @Override
        protected void renderToTexture(LivePreviewRenderState state, PoseStack pose) {
            Minecraft minecraft = Minecraft.getInstance();
            if (!state.previewContext().current()) return;
            var candidate = new com.naocraftlab.skins.client.PreviewModelFrames.Body(
                    com.naocraftlab.skins.client.PreviewRenderer.BodyStatus.ORDINARY, () -> true);
            boolean completed = false;
            boolean[] layersSucceeded = {true};
            try {
                try (PreviewScope scope =
                                state.previewContext().open(minecraft);
                        EditorPreviewLayerGuard ignoredLayers =
                                EditorPreviewLayerGuard.open(failure -> {
                                    layersSucceeded[0] = false;
                                    state.layerFailureSink().onFailure(failure);
                                })) {
                    SubmissionPreviewRenderer.PreviewPlayer renderPlayer = state.previewPlayer();
                    renderPlayer.tickCount = Math.max(0, (int) Math.floor(state.previewAge()));
                    renderPlayer.avatarState().tick(renderPlayer.position(), Vec3.ZERO);
                    float partialTick = minecraft.getDeltaTracker().getGameTimeDeltaPartialTick(false);
                    AvatarRenderState avatarState = (AvatarRenderState)
                            minecraft.getEntityRenderDispatcher().extractEntity(renderPlayer, partialTick);
                    SubmissionPreviewRenderer.configure(avatarState, state.request());
                    avatarState.ageInTicks = state.previewAge();

                    minecraft.gameRenderer.getLighting().setupFor(Lighting.Entry.ENTITY_IN_UI);
                    pose.translate(
                            state.translation().x,
                            state.translation().y,
                            state.translation().z);
                    pose.mulPose(state.rotation());

                    CameraRenderState cameraState = new CameraRenderState();
                    cameraState.orientation = new Quaternionf(state.overrideCameraAngle())
                            .conjugate()
                            .rotateY((float) Math.PI);
                    FeatureRenderDispatcher features =
                            minecraft.gameRenderer.getFeatureRenderDispatcher();
                    minecraft.getEntityRenderDispatcher().submit(
                            avatarState,
                            cameraState,
                            0.0,
                            0.0,
                            0.0,
                            pose,
                            features.getSubmitNodeStorage());
                    features.renderAllFeatures();
                    candidate = scope.body();
                }
                completed = layersSucceeded[0];
            } catch (RuntimeException failure) {
                if (state.previewContext().current()) state.failureSink().onFailure(failure);
            } finally {
                this.candidate = completed ? candidate
                        : new com.naocraftlab.skins.client.PreviewModelFrames.Body(
                                com.naocraftlab.skins.client.PreviewRenderer.BodyStatus.FAILED, () -> true);
            }
        }


        @Override protected String getTextureLabel() { return "NCL Skins live preview target"; }
    }
}

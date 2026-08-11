package com.naocraftlab.skins.compat.mc12111;

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


public final class Minecraft12111LivePreviewRenderer
        extends PictureInPictureRenderer<Minecraft12111LivePreviewRenderState> {
    public Minecraft12111LivePreviewRenderer(MultiBufferSource.BufferSource bufferSource) {
        super(bufferSource);
    }

    @Override
    public Class<Minecraft12111LivePreviewRenderState> getRenderStateClass() {
        return Minecraft12111LivePreviewRenderState.class;
    }

    @Override
    protected float getTranslateY(int textureHeight, int guiScale) {
        return textureHeight / 2.0F;
    }

    @Override
    protected void renderToTexture(Minecraft12111LivePreviewRenderState state, PoseStack pose) {
        Minecraft minecraft = Minecraft.getInstance();
        try (Minecraft12111PreviewScope ignoredContext =
                        state.previewContext().open(minecraft);
                EditorPreviewLayerGuard ignoredLayers =
                        EditorPreviewLayerGuard.open(state.layerFailureSink()::onFailure)) {
            Minecraft12111PreviewRenderer.PreviewPlayer renderPlayer = state.previewPlayer();
            renderPlayer.tickCount = Math.max(0, (int) Math.floor(state.previewAge()));
            renderPlayer.avatarState().tick(renderPlayer.position(), Vec3.ZERO);
            float partialTick = minecraft.getDeltaTracker().getGameTimeDeltaPartialTick(false);
            AvatarRenderState avatarState = (AvatarRenderState)
                    minecraft.getEntityRenderDispatcher().extractEntity(renderPlayer, partialTick);
            Minecraft12111PreviewRenderer.configure(avatarState, state.request());
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
        } catch (RuntimeException failure) {
            state.failureSink().onFailure(failure);
        }
    }

    @Override
    protected String getTextureLabel() {
        return "NCL Skins 1.21.11 live editor preview";
    }
}

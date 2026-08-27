package com.naocraftlab.skins.compat.client.identifier.extraction.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.naocraftlab.skins.compat.client.identifier.extraction.NclSkinsDepthEnvelopeState;
import com.naocraftlab.skins.client.PreviewDepthEnvelope;
import net.minecraft.client.gui.render.pip.PictureInPictureRenderer;
import net.minecraft.client.renderer.Projection;
import net.minecraft.client.renderer.state.gui.GuiRenderState;
import net.minecraft.client.renderer.state.gui.pip.GuiEntityRenderState;
import net.minecraft.client.renderer.state.gui.pip.PictureInPictureRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(PictureInPictureRenderer.class)
abstract class PictureInPictureRendererMixin {
    @Unique private static final ThreadLocal<Float> NCLSKINS_DEPTH_EXTENT =
            ThreadLocal.withInitial(() -> 0.0F);

    @WrapMethod(
            method = "prepare(Lnet/minecraft/client/renderer/state/gui/pip/PictureInPictureRenderState;Lnet/minecraft/client/renderer/state/gui/GuiRenderState;I)V",
            require = 1, expect = 1, allow = 1)
    private void nclskins$withDepthScope(
            PictureInPictureRenderState state, GuiRenderState guiRenderState, int guiScale,
            Operation<Void> original) {
        float previous = NCLSKINS_DEPTH_EXTENT.get();
        NCLSKINS_DEPTH_EXTENT.set(nclskins$physicalDepthExtent(state, guiScale));
        try {
            original.call(state, guiRenderState, guiScale);
        } finally {
            nclskins$restoreDepth(previous);
        }
    }

    @WrapOperation(
            method = "prepareTexturesAndProjection",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/Projection;setupOrtho(FFFFZ)V"),
            require = 1, expect = 1, allow = 1)
    private void nclskins$expandEditorDepth(
            Projection projection, float near, float far, float width, float height,
            boolean flipY, Operation<Void> original) {
        float depthExtent = NCLSKINS_DEPTH_EXTENT.get();
        original.call(projection,
                depthExtent > 0.0F ? -depthExtent : near,
                depthExtent > 0.0F ? depthExtent : far,
                width, height, flipY);
    }

    @Unique
    private static float nclskins$depthExtent(PictureInPictureRenderState state) {
        if (state instanceof NclSkinsDepthEnvelopeState depthState) {
            return depthState.nclskins$depthExtent();
        }
        return state instanceof GuiEntityRenderState entityState
                && entityState.renderState() instanceof NclSkinsDepthEnvelopeState depthState
                ? depthState.nclskins$depthExtent()
                : 0.0F;
    }

    @Unique
    private static float nclskins$physicalDepthExtent(
            PictureInPictureRenderState state, int guiScale) {
        float logicalExtent = nclskins$depthExtent(state);
        if (logicalExtent <= 0.0F) {
            return 0.0F;
        }
        return Math.max(
                PreviewDepthEnvelope.UPSTREAM_MINIMUM,
                Math.min(PreviewDepthEnvelope.MAXIMUM, logicalExtent * Math.max(1, guiScale)));
    }

    @Unique
    private static void nclskins$restoreDepth(float previous) {
        if (previous > 0.0F) NCLSKINS_DEPTH_EXTENT.set(previous);
        else NCLSKINS_DEPTH_EXTENT.remove();
    }
}

package com.naocraftlab.skins.compat.client.identifier.extraction.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.naocraftlab.skins.compat.client.identifier.extraction.NclSkinsDepthEnvelopeState;
import com.naocraftlab.skins.client.PreviewDepthEnvelope;
import com.naocraftlab.skins.client.PreviewModelFrames;
import com.naocraftlab.skins.compat.client.identifier.extraction.AvatarPreviewContext;
import com.naocraftlab.skins.compat.client.identifier.extraction.NclSkinsWideDepthState;
import com.naocraftlab.skins.compat.client.identifier.extraction.PreviewRenderSystemState;
import org.joml.Matrix4fStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.render.pip.PictureInPictureRenderer;
import net.minecraft.client.renderer.Projection;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
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
    @Unique private static final ThreadLocal<Matrix4fStack[]> NCLSKINS_MODEL_VIEW_SCOPE = new ThreadLocal<>();

    @WrapMethod(
            method = "prepare(Lnet/minecraft/client/renderer/state/gui/pip/PictureInPictureRenderState;Lnet/minecraft/client/renderer/state/gui/GuiRenderState;Lnet/minecraft/client/renderer/feature/FeatureRenderDispatcher;I)V",
            require = 1, expect = 1, allow = 1)
    private void nclskins$withDepthScope(
            PictureInPictureRenderState state, GuiRenderState guiRenderState,
            FeatureRenderDispatcher featureRenderDispatcher, int guiScale,
            Operation<Void> original) {
        NclSkinsWideDepthState owned = state instanceof GuiEntityRenderState guiState
                && guiState.renderState() instanceof NclSkinsWideDepthState previewState ? previewState : null;
        AvatarPreviewContext context = owned == null ? null : owned.nclskins$previewContext();
        if (context != null && !context.current()) return;
        if (context != null && context.target() != null
                && !com.naocraftlab.skins.compat.client.identifier.extraction.PreviewTargetLease.delegating()) {
            com.naocraftlab.skins.compat.client.identifier.extraction.PreviewTargetLease.prepare(
                    context.target(), this, (GuiEntityRenderState) state, guiRenderState, featureRenderDispatcher, guiScale);
            return;
        }
        float previous = NCLSKINS_DEPTH_EXTENT.get();
        NCLSKINS_DEPTH_EXTENT.set(nclskins$physicalDepthExtent(state, guiScale));
        AvatarPreviewContext.Attempt attempt = null;
        PreviewModelFrames.Body candidate = null;
        boolean completed = false;
        var previousModelViewScope = NCLSKINS_MODEL_VIEW_SCOPE.get();
        Matrix4fStack[] modelViewScope = context == null ? null : new Matrix4fStack[1];
        Runnable restoreOutput = context == null ? null : PreviewRenderSystemState.exceptionalOutputRestore();
        if (modelViewScope != null) NCLSKINS_MODEL_VIEW_SCOPE.set(modelViewScope);
        else NCLSKINS_MODEL_VIEW_SCOPE.remove();
        Throwable primary = null;
        try {
            try (var scope = context == null ? null
                    : context.begin(state, Minecraft.getInstance(), owned.nclskins$layerFailureSink())) {
                attempt = scope;
                original.call(state, guiRenderState, featureRenderDispatcher, guiScale);
                if (scope != null) candidate = scope.body();
            }
            completed = true;
        } catch (RuntimeException | Error failure) {
            primary = failure;
            throw failure;
        } finally {
            boolean nativeRestored = false;
            try {
                try {
                    if (modelViewScope != null && modelViewScope[0] != null) modelViewScope[0].popMatrix();
                } finally {
                    if (!completed && restoreOutput != null) restoreOutput.run();
                }
                nativeRestored = true;
            } catch (RuntimeException | Error cleanup) {
                if (primary != null) primary.addSuppressed(cleanup);
                else throw cleanup;
            } finally {
                if (previousModelViewScope == null) NCLSKINS_MODEL_VIEW_SCOPE.remove();
                else NCLSKINS_MODEL_VIEW_SCOPE.set(previousModelViewScope);
                boolean restored = false;
                try {
                    nclskins$restoreDepth(previous);
                    restored = true;
                } finally {
                    if (attempt != null) attempt.displayed(completed && nativeRestored && restored, candidate);
                    else if (context != null && !completed) context.failed();
                }
            }
        }
    }

    @WrapOperation(method = "prepare", at = @At(value = "INVOKE",
            target = "Lorg/joml/Matrix4fStack;pushMatrix()Lorg/joml/Matrix4fStack;"),
            require = 1, expect = 1, allow = 1)
    private Matrix4fStack nclskins$trackModelViewPush(Matrix4fStack stack, Operation<Matrix4fStack> original) {
        var result = original.call(stack);
        var scope = NCLSKINS_MODEL_VIEW_SCOPE.get();
        if (scope != null) scope[0] = stack;
        return result;
    }

    @WrapOperation(method = "prepare", at = @At(value = "INVOKE",
            target = "Lorg/joml/Matrix4fStack;popMatrix()Lorg/joml/Matrix4fStack;"),
            require = 1, expect = 1, allow = 1)
    private Matrix4fStack nclskins$trackModelViewPop(Matrix4fStack stack, Operation<Matrix4fStack> original) {
        var result = original.call(stack);
        var scope = NCLSKINS_MODEL_VIEW_SCOPE.get();
        if (scope != null && scope[0] == stack) scope[0] = null;
        return result;
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

package com.naocraftlab.skins.compat.mc262.mixin;

import com.naocraftlab.skins.compat.mc262.NclSkinsWideDepthState;
import net.minecraft.client.gui.render.pip.PictureInPictureRenderer;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import net.minecraft.client.renderer.state.gui.GuiRenderState;
import net.minecraft.client.renderer.state.gui.pip.GuiEntityRenderState;
import net.minecraft.client.renderer.state.gui.pip.PictureInPictureRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Group;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArgs;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;

@Mixin(PictureInPictureRenderer.class)
abstract class PictureInPictureRendererMixin {
    @Unique
    private static final float NCLSKINS_EDITOR_DEPTH = 32_768.0F;

    @Unique
    private boolean nclskins$wideDepth;

    @Inject(
            method = "prepare(Lnet/minecraft/client/renderer/state/gui/pip/PictureInPictureRenderState;Lnet/minecraft/client/renderer/state/gui/GuiRenderState;I)V",
            at = @At("HEAD"),
            require = 0)
    @Group(name = "nclskins$captureDepthMode", min = 1, max = 1)
    private void nclskins$captureDepthMode261(
            PictureInPictureRenderState state,
            GuiRenderState guiRenderState,
            int guiScale,
            CallbackInfo callbackInfo) {
        nclskins$captureDepthMode(state);
    }

    @Inject(
            method = "prepare(Lnet/minecraft/client/renderer/state/gui/pip/PictureInPictureRenderState;Lnet/minecraft/client/renderer/state/gui/GuiRenderState;Lnet/minecraft/client/renderer/feature/FeatureRenderDispatcher;I)V",
            at = @At("HEAD"),
            require = 0)
    @Group(name = "nclskins$captureDepthMode", min = 1, max = 1)
    private void nclskins$captureDepthMode262(
            PictureInPictureRenderState state,
            GuiRenderState guiRenderState,
            FeatureRenderDispatcher featureRenderDispatcher,
            int guiScale,
            CallbackInfo callbackInfo) {
        nclskins$captureDepthMode(state);
    }

    @Unique
    private void nclskins$captureDepthMode(PictureInPictureRenderState state) {
        nclskins$wideDepth = state instanceof GuiEntityRenderState entityState
                && entityState.renderState() instanceof NclSkinsWideDepthState depthState
                && depthState.nclskins$usesWideDepth();
    }

    @ModifyArgs(
            method = "prepareTexturesAndProjection",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/Projection;setupOrtho(FFFFZ)V"))
    private void nclskins$expandEditorDepth(Args arguments) {
        if (nclskins$wideDepth) {
            arguments.set(0, -NCLSKINS_EDITOR_DEPTH);
            arguments.set(1, NCLSKINS_EDITOR_DEPTH);
        }
    }
}

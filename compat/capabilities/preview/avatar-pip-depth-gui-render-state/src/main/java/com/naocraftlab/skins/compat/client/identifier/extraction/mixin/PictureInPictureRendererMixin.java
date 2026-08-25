package com.naocraftlab.skins.compat.client.identifier.extraction.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.naocraftlab.skins.compat.client.identifier.extraction.NclSkinsWideDepthState;
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
    @Unique private static final float NCLSKINS_EDITOR_DEPTH = 32_768.0F;
    @Unique private static final ThreadLocal<Boolean> NCLSKINS_WIDE_DEPTH =
            ThreadLocal.withInitial(() -> false);

    @WrapMethod(
            method = "prepare(Lnet/minecraft/client/renderer/state/gui/pip/PictureInPictureRenderState;Lnet/minecraft/client/renderer/state/gui/GuiRenderState;I)V",
            require = 1, expect = 1, allow = 1)
    private void nclskins$withDepthScope(
            PictureInPictureRenderState state, GuiRenderState guiRenderState, int guiScale,
            Operation<Void> original) {
        boolean previous = NCLSKINS_WIDE_DEPTH.get();
        NCLSKINS_WIDE_DEPTH.set(nclskins$usesWideDepth(state));
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
        boolean wideDepth = NCLSKINS_WIDE_DEPTH.get();
        original.call(projection,
                wideDepth ? -NCLSKINS_EDITOR_DEPTH : near,
                wideDepth ? NCLSKINS_EDITOR_DEPTH : far,
                width, height, flipY);
    }

    @Unique
    private static boolean nclskins$usesWideDepth(PictureInPictureRenderState state) {
        return state instanceof GuiEntityRenderState entityState
                && entityState.renderState() instanceof NclSkinsWideDepthState depthState
                && depthState.nclskins$usesWideDepth();
    }

    @Unique
    private static void nclskins$restoreDepth(boolean previous) {
        if (previous) NCLSKINS_WIDE_DEPTH.set(true);
        else NCLSKINS_WIDE_DEPTH.remove();
    }
}

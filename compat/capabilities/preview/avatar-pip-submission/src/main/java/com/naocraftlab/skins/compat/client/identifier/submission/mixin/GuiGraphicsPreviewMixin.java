package com.naocraftlab.skins.compat.client.identifier.submission.mixin;

import com.naocraftlab.skins.compat.client.identifier.submission.BakedPreviewSubmission;
import com.naocraftlab.skins.compat.client.identifier.submission.LivePreviewSubmission;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.render.state.GuiRenderState;
import net.minecraft.client.gui.render.state.pip.PictureInPictureRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;


@Mixin(GuiGraphics.class)
abstract class GuiGraphicsPreviewMixin {
    @WrapOperation(
            method = "submitSkinRenderState",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/render/state/GuiRenderState;submitPicturesInPictureState(Lnet/minecraft/client/gui/render/state/pip/PictureInPictureRenderState;)V"),
            require = 1, expect = 1, allow = 1)
    private void nclskins$submitCenteredBakedPreview(
            GuiRenderState guiRenderState,
            PictureInPictureRenderState vanillaState,
            Operation<Void> original) {
        original.call(guiRenderState,
                BakedPreviewSubmission.replace(this, vanillaState));
    }

    @WrapOperation(
            method = "submitEntityRenderState",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/render/state/GuiRenderState;submitPicturesInPictureState(Lnet/minecraft/client/gui/render/state/pip/PictureInPictureRenderState;)V"),
            require = 1, expect = 1, allow = 1)
    private void nclskins$submitLivePreview(
            GuiRenderState guiRenderState,
            PictureInPictureRenderState vanillaState,
            Operation<Void> original) {
        original.call(guiRenderState,
                LivePreviewSubmission.replace(this, vanillaState));
    }
}

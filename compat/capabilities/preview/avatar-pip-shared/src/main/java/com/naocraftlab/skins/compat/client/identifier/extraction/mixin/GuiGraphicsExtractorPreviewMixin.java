package com.naocraftlab.skins.compat.client.identifier.extraction.mixin;

import com.naocraftlab.skins.compat.client.identifier.extraction.NclBakedPlayerSubmission;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.state.gui.GuiRenderState;
import net.minecraft.client.renderer.state.gui.pip.PictureInPictureRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(GuiGraphicsExtractor.class)
abstract class GuiGraphicsExtractorPreviewMixin {
    @WrapOperation(
            method = "skin",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/state/gui/GuiRenderState;addPicturesInPictureState(Lnet/minecraft/client/renderer/state/gui/pip/PictureInPictureRenderState;)V"),
            require = 1, expect = 1, allow = 1)
    private void nclskins$submitCompositePlayer(
            GuiRenderState guiRenderState,
            PictureInPictureRenderState vanillaState,
            Operation<Void> original) {
        original.call(guiRenderState, NclBakedPlayerSubmission.replace(this, vanillaState));
    }
}

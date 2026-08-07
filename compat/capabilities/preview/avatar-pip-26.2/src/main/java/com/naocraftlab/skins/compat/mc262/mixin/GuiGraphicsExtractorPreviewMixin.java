package com.naocraftlab.skins.compat.mc262.mixin;

import com.naocraftlab.skins.compat.mc262.NclBakedPlayerSubmission;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.state.gui.GuiRenderState;
import net.minecraft.client.renderer.state.gui.pip.PictureInPictureRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(GuiGraphicsExtractor.class)
abstract class GuiGraphicsExtractorPreviewMixin {
    @Redirect(
            method = "skin",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/state/gui/GuiRenderState;addPicturesInPictureState(Lnet/minecraft/client/renderer/state/gui/pip/PictureInPictureRenderState;)V"))
    private void nclskins$submitCompositePlayer(
            GuiRenderState guiRenderState, PictureInPictureRenderState vanillaState) {
        guiRenderState.addPicturesInPictureState(
                NclBakedPlayerSubmission.replace(this, vanillaState));
    }
}

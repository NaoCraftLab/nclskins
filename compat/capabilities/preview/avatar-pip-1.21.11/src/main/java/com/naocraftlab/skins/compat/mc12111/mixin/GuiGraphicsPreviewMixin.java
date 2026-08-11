package com.naocraftlab.skins.compat.mc12111.mixin;

import com.naocraftlab.skins.compat.mc12111.Minecraft12111BakedPreviewSubmission;
import com.naocraftlab.skins.compat.mc12111.Minecraft12111LivePreviewSubmission;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.render.state.GuiRenderState;
import net.minecraft.client.gui.render.state.pip.PictureInPictureRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;


@Mixin(GuiGraphics.class)
abstract class GuiGraphicsPreviewMixin {
    @Redirect(
            method = "submitSkinRenderState",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/render/state/GuiRenderState;submitPicturesInPictureState(Lnet/minecraft/client/gui/render/state/pip/PictureInPictureRenderState;)V"))
    private void nclskins$submitCenteredBakedPreview(
            GuiRenderState guiRenderState, PictureInPictureRenderState vanillaState) {
        guiRenderState.submitPicturesInPictureState(
                Minecraft12111BakedPreviewSubmission.replace(this, vanillaState));
    }

    @Redirect(
            method = "submitEntityRenderState",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/render/state/GuiRenderState;submitPicturesInPictureState(Lnet/minecraft/client/gui/render/state/pip/PictureInPictureRenderState;)V"))
    private void nclskins$submitLivePreview(
            GuiRenderState guiRenderState, PictureInPictureRenderState vanillaState) {
        guiRenderState.submitPicturesInPictureState(
                Minecraft12111LivePreviewSubmission.replace(this, vanillaState));
    }
}

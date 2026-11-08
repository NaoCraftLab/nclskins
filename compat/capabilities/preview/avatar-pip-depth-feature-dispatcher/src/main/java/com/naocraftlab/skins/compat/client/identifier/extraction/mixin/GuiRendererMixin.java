package com.naocraftlab.skins.compat.client.identifier.extraction.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.naocraftlab.skins.compat.client.identifier.preview.PreviewConsumerFrame;
import net.minecraft.client.gui.render.GuiRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(GuiRenderer.class)
abstract class GuiRendererMixin {
    @WrapMethod(method = "render()V", require = 1, expect = 1, allow = 1)
    private void nclskins$withConsumerFrame(Operation<Void> original) {
        try (var frame = PreviewConsumerFrame.open()) { original.call(); }
    }

    @WrapOperation(method = "render()V", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/render/GuiRenderer;draw()V"),
            require = 1, expect = 1, allow = 1)
    private void nclskins$afterConsumer(GuiRenderer renderer, Operation<Void> original) {
        try { original.call(renderer); }
        catch (RuntimeException | Error failure) {
            try { PreviewConsumerFrame.submitted(); }
            catch (RuntimeException | Error cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
        PreviewConsumerFrame.submitted();
    }
}

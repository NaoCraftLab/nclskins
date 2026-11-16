package com.naocraftlab.skins.compat.client.identifier.preview.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.naocraftlab.skins.compat.client.identifier.preview.PreviewConsumerFrame;
import net.minecraft.client.gui.render.GuiRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(GuiRenderer.class)
abstract class GuiRendererMixin {
    @WrapMethod(method = "close()V", require = 1, expect = 1, allow = 1)
    private void nclskins$closeOwner(Operation<Void> original) {
        Throwable cleanup = null;
        try { PreviewConsumerFrame.closeOwner(this); }
        catch (RuntimeException | Error failure) { cleanup = failure; }
        try { original.call(); }
        catch (RuntimeException | Error failure) {
            if (cleanup != null && cleanup != failure) failure.addSuppressed(cleanup);
            throw failure;
        }
        if (cleanup instanceof RuntimeException failure) throw failure;
        if (cleanup instanceof Error failure) throw failure;
    }

    @WrapMethod(method = "render(Lcom/mojang/blaze3d/buffers/GpuBufferSlice;)V", require = 1, expect = 1, allow = 1)
    private void nclskins$withConsumerFrame(GpuBufferSlice buffer, Operation<Void> original) {
        try (var frame = PreviewConsumerFrame.open(this)) { original.call(buffer); }
    }

    @WrapOperation(method = "render(Lcom/mojang/blaze3d/buffers/GpuBufferSlice;)V", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/render/GuiRenderer;draw(Lcom/mojang/blaze3d/buffers/GpuBufferSlice;)V"),
            require = 1, expect = 1, allow = 1)
    private void nclskins$afterConsumer(GuiRenderer renderer, GpuBufferSlice buffer, Operation<Void> original) {
        try { original.call(renderer, buffer); }
        catch (RuntimeException | Error failure) {
            try { PreviewConsumerFrame.failed(); }
            catch (RuntimeException | Error cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
        PreviewConsumerFrame.submitted();
    }
}

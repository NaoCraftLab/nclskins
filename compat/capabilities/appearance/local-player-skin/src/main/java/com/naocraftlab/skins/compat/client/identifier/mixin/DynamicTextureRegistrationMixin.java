package com.naocraftlab.skins.compat.client.identifier.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.naocraftlab.skins.client.NativeTextureRegistration;
import java.util.concurrent.CancellationException;
import java.util.function.Supplier;
import net.minecraft.client.renderer.texture.DynamicTexture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(DynamicTexture.class)
abstract class DynamicTextureRegistrationMixin {
    @WrapOperation(method = "<init>(Ljava/util/function/Supplier;Lcom/mojang/blaze3d/platform/NativeImage;)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/texture/DynamicTexture;createTexture(Ljava/util/function/Supplier;)V"),
            require = 1, expect = 1, allow = 1)
    private void nclskins$createOwned(DynamicTexture texture, Supplier<String> label, Operation<Void> original) {
        NativeTextureRegistration owner = NativeTextureRegistration.forImage(texture.getPixels());
        if (owner != null && !owner.isCurrent()) throw new CancellationException();
        original.call(texture, label);
    }
}

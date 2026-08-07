package com.naocraftlab.skins.compat.v1_20_1.client.mixin;

import com.mojang.blaze3d.platform.NativeImage;
import com.naocraftlab.skins.client.NativeTextureUploadTracker;
import net.minecraft.client.renderer.texture.HttpTexture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(HttpTexture.class)
abstract class HttpTextureUploadMixin {
    @Inject(
            method = "upload(Lcom/mojang/blaze3d/platform/NativeImage;)V",
            at = @At("TAIL"))
    private void nclskins$markNativePlayerSkinReady(NativeImage image, CallbackInfo callback) {
        NativeTextureUploadTracker.uploaded(this);
    }
}

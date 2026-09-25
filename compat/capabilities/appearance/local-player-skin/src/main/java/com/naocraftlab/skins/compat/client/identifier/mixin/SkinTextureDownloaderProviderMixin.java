package com.naocraftlab.skins.compat.client.identifier.mixin;

import com.naocraftlab.skins.compat.client.identifier.SneakyUnmodifiedSkinPixels;
import com.mojang.blaze3d.platform.NativeImage;
import com.naocraftlab.skins.runtime.CapeProjection;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.renderer.texture.SkinTextureDownloader;
import net.minecraft.core.ClientAsset;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(SkinTextureDownloader.class)
abstract class SkinTextureDownloaderProviderMixin {
    @Inject(method = "registerTextureInManager", at = @At("HEAD"),
            require = 1, expect = 1, allow = 1)
    private void nclskins$captureSkinPixels(ClientAsset.Texture texture, NativeImage image,
            CallbackInfoReturnable<CompletableFuture<ClientAsset.Texture>> ci) {
        if (image != null) {
            int[] argb = ((SneakyUnmodifiedSkinPixels) (Object) image).nclskins$takeUnmodifiedSkinPixels();
            if (argb != null) CapeProjection.skinTextureReady(texture.texturePath().toString(), argb, texture);
        }
    }

    @Inject(method = "processLegacySkin", at = @At("HEAD"),
            require = 1, expect = 1, allow = 1)
    private static void nclskins$rememberUnmodifiedSkinPixels(NativeImage image, String url,
            CallbackInfoReturnable<NativeImage> ci) {
        if (image != null && image.getWidth() == 64 && image.getHeight() == 64) {
            int[] argb = new int[64 * 64];
            for (int y = 0; y < 64; y++) {
                for (int x = 0; x < 64; x++) {
                    argb[y * 64 + x] = image.getPixel(x, y);
                }
            }
            ((SneakyUnmodifiedSkinPixels) (Object) image).nclskins$rememberUnmodifiedSkinPixels(argb);
        }
    }
}

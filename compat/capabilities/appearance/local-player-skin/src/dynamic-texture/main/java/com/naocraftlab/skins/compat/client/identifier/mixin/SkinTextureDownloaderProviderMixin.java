package com.naocraftlab.skins.compat.client.identifier.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.blaze3d.platform.NativeImage;
import com.naocraftlab.skins.compat.client.identifier.SneakyUnmodifiedSkinPixels;
import net.minecraft.client.renderer.texture.SkinTextureDownloader;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(SkinTextureDownloader.class)
abstract class SkinTextureDownloaderProviderMixin {
    @WrapMethod(method = "processLegacySkin(Lcom/mojang/blaze3d/platform/NativeImage;Ljava/lang/String;)Lcom/mojang/blaze3d/platform/NativeImage;",
            require = 1, expect = 1, allow = 1)
    private static NativeImage nclskins$raw(NativeImage image, String url, Operation<NativeImage> original) {
        nclskins$rememberUnmodifiedSkinPixels(image);
        return original.call(image, url);
    }

    private static void nclskins$rememberUnmodifiedSkinPixels(NativeImage image) {
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

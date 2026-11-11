package com.naocraftlab.skins.compat.client.identifier.mixin;

import com.naocraftlab.skins.compat.client.identifier.SneakyUnmodifiedSkinPixels;
import com.naocraftlab.skins.compat.client.identifier.SkinTextureRegistrationOwner;
import com.naocraftlab.skins.client.NativeTextureRegistration;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import org.spongepowered.asm.mixin.Unique;
import com.mojang.blaze3d.platform.NativeImage;
import com.naocraftlab.skins.runtime.CapeProjection;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.renderer.texture.SkinTextureDownloader;
import net.minecraft.core.ClientAsset;
import org.spongepowered.asm.mixin.Mixin;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;

@Mixin(SkinTextureDownloader.class)
abstract class SkinTextureDownloaderProviderMixin implements SkinTextureRegistrationOwner {
    @Unique
    private NativeTextureRegistration nclskins$registration;

    @Override
    public void nclskins$textureRegistration(NativeTextureRegistration registration) {
        if (nclskins$registration != null) throw new IllegalStateException("Texture registration is already bound");
        nclskins$registration = Objects.requireNonNull(registration, "registration");
    }

    @WrapMethod(method = "registerTextureInManager(Lnet/minecraft/core/ClientAsset$Texture;Lcom/mojang/blaze3d/platform/NativeImage;)Ljava/util/concurrent/CompletableFuture;",
            require = 1, expect = 1, allow = 1)
    private CompletableFuture<ClientAsset.Texture> nclskins$captureSkinPixels(ClientAsset.Texture texture, NativeImage image,
            Operation<CompletableFuture<ClientAsset.Texture>> original) {
        var registration = nclskins$registration;
        if (registration != null) {
            if (!registration.isCurrent()) {
                image.close();
                return CompletableFuture.failedFuture(new CancellationException());
            }
            registration.bind(image, image::close);
        }
        try {
            if (image != null) {
                int[] argb = ((SneakyUnmodifiedSkinPixels) (Object) image).nclskins$takeUnmodifiedSkinPixels();
                if (argb != null) CapeProjection.skinTextureReady(texture.texturePath().toString(), argb, texture);
            }
            CompletableFuture<ClientAsset.Texture> result = original.call(texture, image);
            return registration == null ? result : result.whenComplete((ignored, failure) -> registration.complete());
        } catch (RuntimeException | Error failure) {
            if (registration != null) registration.complete();
            throw failure;
        }
    }

    @WrapMethod(method = "processLegacySkin(Lcom/mojang/blaze3d/platform/NativeImage;Ljava/lang/String;)Lcom/mojang/blaze3d/platform/NativeImage;")
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

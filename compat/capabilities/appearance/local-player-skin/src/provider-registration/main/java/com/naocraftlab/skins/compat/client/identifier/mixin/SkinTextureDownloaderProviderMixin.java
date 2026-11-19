package com.naocraftlab.skins.compat.client.identifier.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Share;
import com.llamalad7.mixinextras.sugar.ref.LocalRef;
import com.mojang.blaze3d.platform.NativeImage;
import com.naocraftlab.skins.compat.client.identifier.FeatureSkinTextureDownloader;
import com.naocraftlab.skins.compat.client.identifier.SneakyUnmodifiedSkinPixels;
import com.naocraftlab.skins.runtime.CapeProjection;
import net.minecraft.client.renderer.texture.SkinTextureDownloader;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.client.renderer.texture.TextureProvider;
import net.minecraft.core.ClientAsset;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(SkinTextureDownloader.class)
abstract class SkinTextureDownloaderProviderMixin {
    @Shadow @Final private TextureManager textureManager;

    @WrapOperation(method = "lambda$registerTextureInManager$0(Lcom/mojang/blaze3d/platform/NativeImage;Lnet/minecraft/core/ClientAsset$Texture;)Lnet/minecraft/core/ClientAsset$Texture;",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/texture/TextureManager;registerProvider(Lnet/minecraft/resources/Identifier;Lnet/minecraft/client/renderer/texture/TextureProvider;)V"),
            require = 1, expect = 1, allow = 1)
    private void ncl$registerSkinProvider(TextureManager manager, Identifier location, TextureProvider<?, ?> provider,
            Operation<Void> original, @Share("ncl$skinProvider") LocalRef<TextureProvider<?, ?>> registered) {
        original.call(manager, location, provider);
        registered.set(provider);
    }

    @ModifyReturnValue(method = "lambda$registerTextureInManager$0(Lcom/mojang/blaze3d/platform/NativeImage;Lnet/minecraft/core/ClientAsset$Texture;)Lnet/minecraft/core/ClientAsset$Texture;",
            at = @At("RETURN"), require = 1, expect = 1, allow = 1)
    private ClientAsset.Texture ncl$publishRegisteredSkin(ClientAsset.Texture texture, NativeImage image,
            @Share("ncl$skinProvider") LocalRef<TextureProvider<?, ?>> registered) {
        TextureProvider<?, ?> provider = registered.get();
        if (image == null || provider == null) return texture;
        int[] argb = ((SneakyUnmodifiedSkinPixels) (Object) image).nclskins$takeUnmodifiedSkinPixels();
        if (argb == null) return texture;
        Identifier location = texture.texturePath();
        Object entry = ((TextureManagerProviderAccessor) textureManager).ncl$getEntries().get(location);
        if (!(entry instanceof TextureManagerEntryProviderAccessor access) || access.ncl$getProvider() != provider) {
            return texture;
        }
        if ((Object) this instanceof FeatureSkinTextureDownloader feature && !feature.mayPublish(location)) {
            return texture;
        }
        CapeProjection.skinTextureReady(location.toString(), argb, provider);
        return texture;
    }

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

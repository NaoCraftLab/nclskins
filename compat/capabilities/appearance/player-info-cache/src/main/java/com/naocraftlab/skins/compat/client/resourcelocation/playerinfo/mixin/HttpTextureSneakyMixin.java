package com.naocraftlab.skins.compat.client.resourcelocation.playerinfo.mixin;

import com.naocraftlab.skins.compat.client.resourcelocation.playerinfo.RegisteredSkinTexture;
import com.mojang.blaze3d.platform.NativeImage;
import com.naocraftlab.skins.runtime.CapeProjection;
import net.minecraft.client.renderer.texture.HttpTexture;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;

@Mixin(value = HttpTexture.class, priority = 2000)
abstract class HttpTextureSneakyMixin implements RegisteredSkinTexture {
    @Shadow private boolean processLegacySkin;
    @Unique private String nclskins$skinLocation;

    @Override
    public void nclskins$registeredSkinLocation(ResourceLocation location) {
        nclskins$skinLocation = location.toString();
    }

    @WrapMethod(method = "processLegacySkin(Lcom/mojang/blaze3d/platform/NativeImage;)Lcom/mojang/blaze3d/platform/NativeImage;")
    private NativeImage nclskins$raw(NativeImage image, Operation<NativeImage> original) {
        nclskins$capture(image);
        return original.call(image);
    }

    private void nclskins$capture(NativeImage image) {
        if (!processLegacySkin || nclskins$skinLocation == null || image == null
                || image.getWidth() != 64 || image.getHeight() != 64) return;
        int[] argb = new int[64 * 64];
        for (int y = 0; y < 64; y++) {
            for (int x = 0; x < 64; x++) {
                int abgr = image.getPixelRGBA(x, y);
                argb[y * 64 + x] = (abgr & 0xff00ff00)
                        | ((abgr & 0x00ff0000) >>> 16) | ((abgr & 0x000000ff) << 16);
            }
        }
        CapeProjection.skinTextureReady(nclskins$skinLocation, argb, this);
    }
}

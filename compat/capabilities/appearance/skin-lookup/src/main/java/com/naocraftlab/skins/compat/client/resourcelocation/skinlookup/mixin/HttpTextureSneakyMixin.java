package com.naocraftlab.skins.compat.client.resourcelocation.skinlookup.mixin;

import com.naocraftlab.skins.compat.client.resourcelocation.skinlookup.RegisteredSkinTexture;
import com.mojang.blaze3d.platform.NativeImage;
import com.naocraftlab.skins.runtime.CapeProjection;
import net.minecraft.client.renderer.texture.HttpTexture;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(HttpTexture.class)
abstract class HttpTextureSneakyMixin implements RegisteredSkinTexture {
    @Shadow private boolean processLegacySkin;
    @Unique private String nclskins$skinLocation;

    @Override
    public void nclskins$registeredSkinLocation(ResourceLocation location) {
        nclskins$skinLocation = location.toString();
    }

    @Inject(method = "processLegacySkin", at = @At("HEAD"), require = 1, expect = 1, allow = 1)
    private void nclskins$capture(NativeImage image, CallbackInfoReturnable<NativeImage> ci) {
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

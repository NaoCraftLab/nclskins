package com.naocraftlab.skins.compat.client.identifier.mixin;

import com.naocraftlab.skins.compat.client.identifier.SneakyUnmodifiedSkinPixels;
import com.mojang.blaze3d.platform.NativeImage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(NativeImage.class)
abstract class NativeImageSneakyPixelsMixin implements SneakyUnmodifiedSkinPixels {
    @Unique private int[] nclskins$unmodifiedSkinPixels;

    @Override
    public void nclskins$rememberUnmodifiedSkinPixels(int[] argb) {
        nclskins$unmodifiedSkinPixels = argb;
    }

    @Override
    public int[] nclskins$takeUnmodifiedSkinPixels() {
        int[] pixels = nclskins$unmodifiedSkinPixels;
        nclskins$unmodifiedSkinPixels = null;
        return pixels;
    }
}

package com.naocraftlab.skins.compat.client.identifier.submission.mixin;

import com.naocraftlab.skins.compat.client.identifier.measurement.PipDepthProjection;
import net.minecraft.client.renderer.CachedOrthoProjectionMatrixBuffer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(CachedOrthoProjectionMatrixBuffer.class)
abstract class CachedOrthoProjectionDepthMixin implements PipDepthProjection.Buffer {
    @Shadow @Final @Mutable private float zNear;
    @Shadow @Final @Mutable private float zFar;
    @Shadow private float width;

    @Override public void nclskins$depthExtent(float extent) {
        if (!Float.isFinite(extent) || extent <= 0) throw new IllegalArgumentException("Invalid preview depth");
        if (zNear == -extent && zFar == extent) return;
        zNear = -extent;
        zFar = extent;
        width = Float.NaN;
    }
}

package com.naocraftlab.skins.compat.client.resourcelocation.skinlookup.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.platform.NativeImage;
import com.naocraftlab.skins.client.NativePlayerSkinLifecycle;
import com.naocraftlab.skins.compat.client.resourcelocation.skinlookup.RegisteredSkinTexture;
import com.naocraftlab.skins.runtime.CapeProjection;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.HttpTexture;
import net.minecraft.client.renderer.texture.SimpleTexture;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(value = HttpTexture.class, priority = 2000)
abstract class HttpTextureSneakyMixin extends SimpleTexture implements RegisteredSkinTexture {
    @Shadow private boolean processLegacySkin;
    @Unique private String nclskins$skinLocation;
    @Unique private NativePlayerSkinLifecycle.Registration nclskins$registration;
    @Unique private boolean nclskins$retired;

    private HttpTextureSneakyMixin(ResourceLocation location) {
        super(location);
    }

    @Override
    public void nclskins$skinRegistration(NativePlayerSkinLifecycle.Registration registration) {
        nclskins$registration = registration;
    }

    @Override
    public void close() {
        nclskins$retired = true;
        super.close();
    }

    @Unique
    private boolean nclskins$isCurrent() {
        return nclskins$registration == null || (!nclskins$retired
                && nclskins$registration.state() != NativePlayerSkinLifecycle.State.FAILED
                && NativePlayerSkinLifecycle.registration(nclskins$registration.location())
                        .orElse(null) == nclskins$registration);
    }

    @WrapOperation(
            method = "load(Lnet/minecraft/server/packs/resources/ResourceManager;)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Minecraft;execute(Ljava/lang/Runnable;)V"),
            require = 1, expect = 1, allow = 1)
    private void nclskins$fenceFallback(Minecraft client, Runnable task, Operation<Void> original) {
        original.call(client, (Runnable) () -> {
            if (nclskins$isCurrent()) task.run();
        });
    }

    @WrapMethod(
            method = "upload(Lcom/mojang/blaze3d/platform/NativeImage;)V",
            require = 1, expect = 1, allow = 1)
    private void nclskins$fenceUpload(NativeImage image, Operation<Void> original) {
        if (!nclskins$isCurrent()) {
            image.close();
            return;
        }
        original.call(image);
    }

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

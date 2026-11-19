package com.naocraftlab.skins.compat.client.identifier.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.blaze3d.platform.NativeImage;
import com.naocraftlab.skins.client.NativePlayerSkinLifecycle;
import com.naocraftlab.skins.compat.client.identifier.SneakyUnmodifiedSkinPixels;
import com.naocraftlab.skins.runtime.CapeProjection;
import java.util.Map;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(TextureManager.class)
abstract class TextureManagerRegistrationMixin {
    @Shadow @Final private Map<Identifier, AbstractTexture> byPath;

    @WrapMethod(method = "register(Lnet/minecraft/resources/Identifier;Lnet/minecraft/client/renderer/texture/AbstractTexture;)V",
            require = 1, expect = 1, allow = 1)
    private void nclskins$publishRegisteredSkin(Identifier location, AbstractTexture texture, Operation<Void> original) {
        original.call(location, texture);
        if (!(texture instanceof DynamicTexture dynamic) || byPath.get(location) != texture) return;
        NativeImage image = dynamic.getPixels();
        if (image == null) return;
        int[] argb = ((SneakyUnmodifiedSkinPixels) (Object) image).nclskins$takeUnmodifiedSkinPixels();
        if (argb == null) return;
        String path = location.toString();
        if (location.getNamespace().equals("nclskins")) {
            var state = NativePlayerSkinLifecycle.managedState(path).orElse(NativePlayerSkinLifecycle.State.RETIRED);
            if (state != NativePlayerSkinLifecycle.State.PENDING && state != NativePlayerSkinLifecycle.State.READY) return;
        }
        CapeProjection.skinTextureReady(path, argb, texture);
    }
}

package com.naocraftlab.skins.compat.client.resourcelocation.playerinfo.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.naocraftlab.skins.compat.client.resourcelocation.playerinfo.MinecraftProviderVisibility;
import com.mojang.authlib.minecraft.MinecraftProfileTexture;
import net.minecraft.client.resources.SkinManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.resources.ResourceLocation;

@Mixin(SkinManager.class)
abstract class SkinManagerProviderMixin {
    @WrapOperation(method = "registerTexture(Lcom/mojang/authlib/minecraft/MinecraftProfileTexture;Lcom/mojang/authlib/minecraft/MinecraftProfileTexture$Type;Lnet/minecraft/client/resources/SkinManager$SkinTextureCallback;)Lnet/minecraft/resources/ResourceLocation;", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/texture/TextureManager;register(Lnet/minecraft/resources/ResourceLocation;Lnet/minecraft/client/renderer/texture/AbstractTexture;)V"), require = 1, expect = 1, allow = 1)
    private void nclskins$register(TextureManager manager, ResourceLocation location, AbstractTexture texture, Operation<Void> original,
            MinecraftProfileTexture profileTexture, MinecraftProfileTexture.Type type, SkinManager.SkinTextureCallback callback) {
        var visibility = MinecraftProviderVisibility.current();
        if (!(callback instanceof MinecraftProviderVisibility.ProviderCallback)
                || (type == MinecraftProfileTexture.Type.SKIN ? visibility.skin() : visibility.cape())) original.call(manager, location, texture);
    }
}

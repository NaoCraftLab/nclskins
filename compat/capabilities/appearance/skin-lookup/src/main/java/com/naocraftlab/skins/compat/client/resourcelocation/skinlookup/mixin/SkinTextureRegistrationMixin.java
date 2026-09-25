package com.naocraftlab.skins.compat.client.resourcelocation.skinlookup.mixin;

import com.naocraftlab.skins.compat.client.resourcelocation.skinlookup.RegisteredSkinTexture;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(targets = "net.minecraft.client.resources.SkinManager$TextureCache")
abstract class SkinTextureRegistrationMixin {
    @WrapOperation(method = "registerTexture(Lcom/mojang/authlib/minecraft/MinecraftProfileTexture;)Ljava/util/concurrent/CompletableFuture;",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/texture/TextureManager;register(Lnet/minecraft/resources/ResourceLocation;Lnet/minecraft/client/renderer/texture/AbstractTexture;)V"),
            require = 1, expect = 1, allow = 1)
    private void nclskins$register(TextureManager manager, ResourceLocation location,
            AbstractTexture texture, Operation<Void> original) {
        if (texture instanceof RegisteredSkinTexture registered) {
            registered.nclskins$registeredSkinLocation(location);
        }
        original.call(manager, location, texture);
    }
}

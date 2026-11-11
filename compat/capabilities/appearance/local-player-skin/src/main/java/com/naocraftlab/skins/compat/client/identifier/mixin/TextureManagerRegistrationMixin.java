package com.naocraftlab.skins.compat.client.identifier.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.naocraftlab.skins.client.NativeTextureRegistration;
import com.naocraftlab.skins.compat.client.identifier.RegisteredTextureAccess;
import java.util.Map;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(TextureManager.class)
abstract class TextureManagerRegistrationMixin implements RegisteredTextureAccess {
    @Shadow @Final private Map<Identifier, AbstractTexture> byPath;

    @Override
    public boolean nclskins$isRegistered(Identifier location, AbstractTexture texture) {
        return byPath.get(location) == texture;
    }

    @WrapMethod(method = "register(Lnet/minecraft/resources/Identifier;Lnet/minecraft/client/renderer/texture/AbstractTexture;)V",
            require = 1, expect = 1, allow = 1)
    private void nclskins$registerOwned(Identifier location, AbstractTexture texture, Operation<Void> original) {
        NativeTextureRegistration owner = texture instanceof DynamicTexture dynamic
                ? NativeTextureRegistration.forImage(dynamic.getPixels()) : null;
        if (owner == null) {
            original.call(location, texture);
            return;
        }
        owner.acquire(() -> original.call(location, texture), () -> {
            if (nclskins$isRegistered(location, texture)) ((TextureManager) (Object) this).release(location);
        }, texture::close);
    }
}

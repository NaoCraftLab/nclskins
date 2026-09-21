package com.naocraftlab.skins.compat.client.resourcelocation.skinlookup.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.naocraftlab.skins.compat.client.resourcelocation.skinlookup.MinecraftProviderVisibility;
import com.mojang.authlib.GameProfile;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.resources.DefaultPlayerSkin;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import net.minecraft.client.resources.PlayerSkin;
import java.util.function.Supplier;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.resources.SkinManager;

@Mixin(PlayerInfo.class)
abstract class PlayerInfoProviderMixin implements MinecraftProviderVisibility.PlayerLookup {
    @org.spongepowered.asm.mixin.Shadow
    private Supplier<PlayerSkin> skinLookup;

    @org.spongepowered.asm.mixin.gen.Invoker("createSkinLookup")
    private static Supplier<PlayerSkin> nclskins$createLookup(GameProfile profile) {
        throw new AssertionError();
    }

    @Override
    public void nclskins$resetProviderLookup() {
        GameProfile profile = ((PlayerInfo) (Object) this).getProfile();
        skinLookup = new Supplier<>() {
            private Supplier<PlayerSkin> delegate;

            @Override
            public PlayerSkin get() {
                if (!MinecraftProviderVisibility.current().any()) return DefaultPlayerSkin.get(profile);
                if (delegate == null) delegate = nclskins$createLookup(profile);
                return delegate.get();
            }
        };
    }

    @WrapOperation(method = "getSkin", at = @At(value = "INVOKE", target = "Ljava/util/function/Supplier;get()Ljava/lang/Object;"), require = 1, expect = 1, allow = 1)
    private Object nclskins$lookup(Supplier<?> supplier, Operation<Object> original) {
        PlayerInfo info = (PlayerInfo) (Object) this;
        if (!MinecraftProviderVisibility.current().any() && !Minecraft.getInstance().isLocalPlayer(info.getProfile().getId())) return DefaultPlayerSkin.get(info.getProfile());
        return original.call(supplier);
    }

    @WrapOperation(method = "createSkinLookup", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/resources/SkinManager;getOrLoad(Lcom/mojang/authlib/GameProfile;)Ljava/util/concurrent/CompletableFuture;"), require = 1, expect = 1, allow = 1)
    private static CompletableFuture<PlayerSkin> nclskins$components(SkinManager manager, GameProfile profile, Operation<CompletableFuture<PlayerSkin>> original) {
        var visibility = MinecraftProviderVisibility.current();
        return visibility.skin() && visibility.cape() ? original.call(manager, profile) : MinecraftProviderVisibility.lookup(manager, profile);
    }

    @ModifyReturnValue(method = "getSkin", at = @At("RETURN"), require = 1, expect = 1, allow = 1)
    private PlayerSkin nclskins$visible(PlayerSkin original) {
        PlayerInfo info = (PlayerInfo) (Object) this;
        if (Minecraft.getInstance().isLocalPlayer(info.getProfile().getId())) return original;
        var visibility = MinecraftProviderVisibility.current();
        if (visibility.skin() && visibility.cape()) return original;
        PlayerSkin fallback = DefaultPlayerSkin.get(info.getProfile());
        return new PlayerSkin(visibility.skin() ? original.texture() : fallback.texture(),
                visibility.skin() ? original.textureUrl() : fallback.textureUrl(),
                visibility.cape() ? original.capeTexture() : null, visibility.cape() ? original.elytraTexture() : null,
                visibility.skin() ? original.model() : fallback.model(), original.secure());
    }
}

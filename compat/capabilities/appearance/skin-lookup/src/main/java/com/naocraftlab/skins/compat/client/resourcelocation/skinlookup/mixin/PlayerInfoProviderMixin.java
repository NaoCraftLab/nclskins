package com.naocraftlab.skins.compat.client.resourcelocation.skinlookup.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.naocraftlab.skins.compat.client.resourcelocation.skinlookup.MinecraftProviderVisibility;
import com.naocraftlab.skins.runtime.CapeProjection;
import com.mojang.authlib.GameProfile;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.resources.DefaultPlayerSkin;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import net.minecraft.client.resources.PlayerSkin;
import net.minecraft.resources.ResourceLocation;
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
        boolean self = Minecraft.getInstance().isLocalPlayer(info.getProfile().getId());
        var visibility = MinecraftProviderVisibility.current();
        if (!self) CapeProjection.visibleSkin(info.getProfile().getId(), info.getProfile().getName(),
                visibility.skin() ? original.texture().toString() : null);
        ResourceLocation officialCape = !self && visibility.cape() ? original.capeTexture() : null;
        ResourceLocation officialElytra = !self && visibility.cape() ? original.elytraTexture() : null;
        CapeProjection.Candidate official = officialCape == null ? null :
                new CapeProjection.Candidate(officialCape.toString(),
                        officialElytra == null ? null : officialElytra.toString(), officialElytra != null);
        CapeProjection.Result resolved = CapeProjection.resolve(info.getProfile().getId(),
                info.getProfile().getName(), null, official, self);
        ResourceLocation cape = nclskins$location(resolved.capeLocation());
        ResourceLocation elytra = cape == null ? null : nclskins$location(resolved.hasElytra()
                ? resolved.elytraLocation() : "minecraft:textures/entity/elytra.png");
        PlayerSkin fallback = self || visibility.skin() ? original : DefaultPlayerSkin.get(info.getProfile());
        return new PlayerSkin(fallback.texture(), fallback.textureUrl(), cape, elytra,
                fallback.model(), original.secure());
    }

    private static ResourceLocation nclskins$location(String location) {
        return location == null ? null : ResourceLocation.tryParse(location);
    }
}

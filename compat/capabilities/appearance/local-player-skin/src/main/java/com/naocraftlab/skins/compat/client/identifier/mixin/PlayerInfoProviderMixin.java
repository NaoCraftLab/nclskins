package com.naocraftlab.skins.compat.client.identifier.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.naocraftlab.skins.compat.client.identifier.MinecraftProviderVisibility;
import com.mojang.authlib.GameProfile;
import java.util.function.Supplier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.client.resources.SkinManager;
import net.minecraft.world.entity.player.PlayerSkin;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

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

    @WrapOperation(method = "createSkinLookup", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/resources/SkinManager;createLookup(Lcom/mojang/authlib/GameProfile;Z)Ljava/util/function/Supplier;"), require = 1, expect = 1, allow = 1)
    private static Supplier<PlayerSkin> nclskins$lookup(SkinManager manager, GameProfile profile, boolean secure, Operation<Supplier<PlayerSkin>> original) {
        var visibility = MinecraftProviderVisibility.current();
        if (visibility.skin() && visibility.cape()) return original.call(manager, profile, secure);
        if (visibility.any()) {
            var future = MinecraftProviderVisibility.lookup(manager, profile);
            PlayerSkin fallback = DefaultPlayerSkin.get(profile);
            return () -> {
                PlayerSkin loaded = future.getNow(fallback);
                return secure && !loaded.secure() ? fallback : loaded;
            };
        }
        return new Supplier<>() {
            private Supplier<PlayerSkin> resumed;

            @Override
            public PlayerSkin get() {
                if (!MinecraftProviderVisibility.current().any()) return DefaultPlayerSkin.get(profile);
                if (resumed == null) resumed = original.call(manager, profile, secure);
                return resumed.get();
            }
        };
    }

    @ModifyReturnValue(method = "getSkin", at = @At("RETURN"), require = 1, expect = 1, allow = 1)
    private PlayerSkin nclskins$visible(PlayerSkin original) {
        PlayerInfo info = (PlayerInfo) (Object) this;
        if (Minecraft.getInstance().isLocalPlayer(info.getProfile().id())) return original;
        var visibility = MinecraftProviderVisibility.current();
        if (visibility.skin() && visibility.cape()) return original;
        PlayerSkin fallback = DefaultPlayerSkin.get(info.getProfile());
        return new PlayerSkin(visibility.skin() ? original.body() : fallback.body(),
                visibility.cape() ? original.cape() : null, visibility.cape() ? original.elytra() : null,
                visibility.skin() ? original.model() : fallback.model(), original.secure());
    }
}

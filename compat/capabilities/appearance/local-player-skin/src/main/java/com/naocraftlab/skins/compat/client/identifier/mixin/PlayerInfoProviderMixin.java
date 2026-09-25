package com.naocraftlab.skins.compat.client.identifier.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.naocraftlab.skins.compat.client.identifier.MinecraftProviderVisibility;
import com.naocraftlab.skins.runtime.CapeProjection;
import com.mojang.authlib.GameProfile;
import java.util.function.Supplier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.client.resources.SkinManager;
import net.minecraft.core.ClientAsset;
import net.minecraft.world.entity.player.PlayerSkin;
import net.minecraft.resources.Identifier;
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
        boolean self = Minecraft.getInstance().isLocalPlayer(info.getProfile().id());
        var visibility = MinecraftProviderVisibility.current();
        ClientAsset.Texture officialCape = !self && visibility.cape() ? original.cape() : null;
        ClientAsset.Texture officialElytra = !self && visibility.cape() ? original.elytra() : null;
        CapeProjection.Candidate official = officialCape == null ? null :
                new CapeProjection.Candidate(officialCape.texturePath().toString(),
                        officialElytra == null ? null : officialElytra.texturePath().toString(), officialElytra != null);
        CapeProjection.Result resolved = CapeProjection.resolve(info.getProfile().id(),
                info.getProfile().name(), null, official, self);
        ClientAsset.Texture cape = nclskins$texture(resolved.capeLocation());
        ClientAsset.Texture elytra = cape == null ? null : nclskins$texture(resolved.hasElytra()
                ? resolved.elytraLocation() : "minecraft:textures/entity/equipment/wings/elytra.png");
        PlayerSkin fallback = self || visibility.skin() ? original : DefaultPlayerSkin.get(info.getProfile());
        return new PlayerSkin(fallback.body(), cape, elytra, fallback.model(), original.secure());
    }

    private static ClientAsset.Texture nclskins$texture(String location) {
        if (location == null) return null;
        Identifier id = Identifier.parse(location);
        return new ClientAsset.ResourceTexture(id, id);
    }
}

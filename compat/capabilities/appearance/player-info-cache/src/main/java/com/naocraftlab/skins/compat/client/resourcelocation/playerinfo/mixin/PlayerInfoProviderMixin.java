package com.naocraftlab.skins.compat.client.resourcelocation.playerinfo.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.naocraftlab.skins.compat.client.resourcelocation.playerinfo.MinecraftProviderVisibility;
import com.naocraftlab.skins.compat.client.resourcelocation.playerinfo.OfficialCapeSource;
import com.naocraftlab.skins.runtime.CapeProjection;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.resources.DefaultPlayerSkin;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.injection.At;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.client.resources.SkinManager;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.minecraft.MinecraftProfileTexture;
import java.util.Map;

@Mixin(PlayerInfo.class)
abstract class PlayerInfoProviderMixin implements MinecraftProviderVisibility.PlayerLookup, OfficialCapeSource {
    @Shadow @Final
    private Map<MinecraftProfileTexture.Type, ResourceLocation> textureLocations;

    @org.spongepowered.asm.mixin.Shadow
    private boolean pendingTextures;

    @Override
    public void nclskins$resetProviderLookup() {
        pendingTextures = false;
    }

    private boolean nclskins$local() {
        return Minecraft.getInstance().getUser().getProfileId().equals(((PlayerInfo) (Object) this).getProfile().getId());
    }

    @Override
    public CapeProjection.Candidate nclskins$officialCape() {
        if (!MinecraftProviderVisibility.current().cape()) return null;
        ResourceLocation cape = textureLocations.get(MinecraftProfileTexture.Type.CAPE);
        ResourceLocation elytra = textureLocations.get(MinecraftProfileTexture.Type.ELYTRA);
        return cape == null ? null : new CapeProjection.Candidate(cape.toString(),
                elytra == null ? null : elytra.toString(), elytra != null);
    }

    private CapeProjection.Result nclskins$resolvedCape() {
        GameProfile profile = ((PlayerInfo) (Object) this).getProfile();
        return CapeProjection.resolve(profile.getId(), profile.getName(), null,
                nclskins$local() ? null : nclskins$officialCape(), nclskins$local());
    }

    private static ResourceLocation nclskins$location(String location) {
        return location == null ? null : ResourceLocation.tryParse(location);
    }

    @WrapOperation(method = "registerTextures", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/resources/SkinManager;registerSkins(Lcom/mojang/authlib/GameProfile;Lnet/minecraft/client/resources/SkinManager$SkinTextureCallback;Z)V"), require = 1, expect = 1, allow = 1)
    private void nclskins$providerRequest(SkinManager manager, GameProfile profile, SkinManager.SkinTextureCallback callback, boolean secure, Operation<Void> original) {
        if (!MinecraftProviderVisibility.current().any()) return;
        MinecraftProviderVisibility.ProviderCallback scoped = callback::onSkinTextureAvailable;
        original.call(manager, profile, scoped, secure);
    }

    @WrapOperation(method = "getSkinLocation", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/multiplayer/PlayerInfo;registerTextures()V"), require = 1, expect = 1, allow = 1)
    private void nclskins$getSkinLocationLookup(PlayerInfo info, Operation<Void> original) {
        if (MinecraftProviderVisibility.current().any() || nclskins$local()) original.call(info);
    }

    @ModifyReturnValue(method = "getSkinLocation", at = @At("RETURN"), require = 1, expect = 1, allow = 1)
    private ResourceLocation nclskins$getSkinLocation(ResourceLocation original) {
        GameProfile profile = ((PlayerInfo) (Object) this).getProfile();
        if (!nclskins$local()) CapeProjection.visibleSkin(profile.getId(), profile.getName(),
                MinecraftProviderVisibility.current().skin() && original != null ? original.toString() : null);
        if (nclskins$local() || MinecraftProviderVisibility.current().skin()) return original;
        return DefaultPlayerSkin.getDefaultSkin(((PlayerInfo) (Object) this).getProfile().getId());
    }

    @WrapOperation(method = "getCapeLocation", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/multiplayer/PlayerInfo;registerTextures()V"), require = 1, expect = 1, allow = 1)
    private void nclskins$getCapeLocationLookup(PlayerInfo info, Operation<Void> original) {
        if (MinecraftProviderVisibility.current().any() || nclskins$local()) original.call(info);
    }

    @ModifyReturnValue(method = "getCapeLocation", at = @At("RETURN"), require = 1, expect = 1, allow = 1)
    private ResourceLocation nclskins$getCapeLocation(ResourceLocation original) {
        return nclskins$location(nclskins$resolvedCape().capeLocation());
    }

    @WrapOperation(method = "getElytraLocation", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/multiplayer/PlayerInfo;registerTextures()V"), require = 1, expect = 1, allow = 1)
    private void nclskins$getElytraLocationLookup(PlayerInfo info, Operation<Void> original) {
        if (MinecraftProviderVisibility.current().any() || nclskins$local()) original.call(info);
    }

    @ModifyReturnValue(method = "getElytraLocation", at = @At("RETURN"), require = 1, expect = 1, allow = 1)
    private ResourceLocation nclskins$getElytraLocation(ResourceLocation original) {
        CapeProjection.Result resolved = nclskins$resolvedCape();
        if (resolved.capeLocation() == null) return null;
        return nclskins$location(resolved.hasElytra()
                ? resolved.elytraLocation() : "minecraft:textures/entity/elytra.png");
    }

    @ModifyReturnValue(method = "getModelName", at = @At("TAIL"), require = 1, expect = 1, allow = 1)
    private String nclskins$model(String original) {
        return nclskins$local() || MinecraftProviderVisibility.current().skin() ? original
                : DefaultPlayerSkin.getSkinModelName(((PlayerInfo) (Object) this).getProfile().getId());
    }
}

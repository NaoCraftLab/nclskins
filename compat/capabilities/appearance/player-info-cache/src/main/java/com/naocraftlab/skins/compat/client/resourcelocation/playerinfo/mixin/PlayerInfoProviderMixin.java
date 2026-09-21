package com.naocraftlab.skins.compat.client.resourcelocation.playerinfo.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.naocraftlab.skins.compat.client.resourcelocation.playerinfo.MinecraftProviderVisibility;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.resources.DefaultPlayerSkin;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.client.resources.SkinManager;
import com.mojang.authlib.GameProfile;

@Mixin(PlayerInfo.class)
abstract class PlayerInfoProviderMixin implements MinecraftProviderVisibility.PlayerLookup {
    @org.spongepowered.asm.mixin.Shadow
    private boolean pendingTextures;

    @Override
    public void nclskins$resetProviderLookup() {
        pendingTextures = false;
    }

    private boolean nclskins$local() {
        return Minecraft.getInstance().getUser().getProfileId().equals(((PlayerInfo) (Object) this).getProfile().getId());
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
        if (nclskins$local() || MinecraftProviderVisibility.current().skin()) return original;
        return DefaultPlayerSkin.getDefaultSkin(((PlayerInfo) (Object) this).getProfile().getId());
    }

    @WrapOperation(method = "getCapeLocation", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/multiplayer/PlayerInfo;registerTextures()V"), require = 1, expect = 1, allow = 1)
    private void nclskins$getCapeLocationLookup(PlayerInfo info, Operation<Void> original) {
        if (MinecraftProviderVisibility.current().any() || nclskins$local()) original.call(info);
    }

    @ModifyReturnValue(method = "getCapeLocation", at = @At("RETURN"), require = 1, expect = 1, allow = 1)
    private ResourceLocation nclskins$getCapeLocation(ResourceLocation original) {
        if (nclskins$local() || MinecraftProviderVisibility.current().cape()) return original;
        return null;
    }

    @WrapOperation(method = "getElytraLocation", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/multiplayer/PlayerInfo;registerTextures()V"), require = 1, expect = 1, allow = 1)
    private void nclskins$getElytraLocationLookup(PlayerInfo info, Operation<Void> original) {
        if (MinecraftProviderVisibility.current().any() || nclskins$local()) original.call(info);
    }

    @ModifyReturnValue(method = "getElytraLocation", at = @At("RETURN"), require = 1, expect = 1, allow = 1)
    private ResourceLocation nclskins$getElytraLocation(ResourceLocation original) {
        if (nclskins$local() || MinecraftProviderVisibility.current().cape()) return original;
        return null;
    }

    @ModifyReturnValue(method = "getModelName", at = @At("TAIL"), require = 1, expect = 1, allow = 1)
    private String nclskins$model(String original) {
        return nclskins$local() || MinecraftProviderVisibility.current().skin() ? original
                : DefaultPlayerSkin.getSkinModelName(((PlayerInfo) (Object) this).getProfile().getId());
    }
}

package com.naocraftlab.skins.compat.client.resourcelocation.skinlookup;

import com.naocraftlab.skins.client.ProviderVisibility;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.minecraft.MinecraftProfileTextures;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.client.resources.SkinManager;
import net.minecraft.client.resources.PlayerSkin;
import com.naocraftlab.skins.compat.client.resourcelocation.skinlookup.mixin.SkinManagerProviderMixin;

public final class MinecraftProviderVisibility {
    private static volatile ProviderVisibility current = ProviderVisibility.ALL;

    private MinecraftProviderVisibility() {}

    public static ProviderVisibility current() {
        return current;
    }

    public static CompletableFuture<PlayerSkin> lookup(SkinManager manager, GameProfile profile) {
        ProviderVisibility visibility = current;
        PlayerSkin fallback = DefaultPlayerSkin.get(profile);
        if (!visibility.any()) return CompletableFuture.completedFuture(fallback);
        try {
            var service = Minecraft.getInstance().getMinecraftSessionService();
            var packed = service.getPackedTextures(profile);
            var textures = packed == null ? MinecraftProfileTextures.EMPTY : service.unpackTextures(packed);
            var filtered = new MinecraftProfileTextures(visibility.skin() ? textures.skin() : null,
                    visibility.cape() ? textures.cape() : null, visibility.cape() ? textures.elytra() : null,
                    textures.signatureState());
            return ((SkinManagerProviderMixin) manager).nclskins$registerProviderTextures(profile.getId(), filtered)
                    .exceptionally(failure -> fallback);
        } catch (RuntimeException unavailable) {
            return CompletableFuture.completedFuture(fallback);
        }
    }

    public interface PlayerLookup {
        void nclskins$resetProviderLookup();
    }

    static void set(ProviderVisibility visibility) {
        if (current.equals(visibility)) return;
        current = visibility;
        var minecraft = net.minecraft.client.Minecraft.getInstance();
        var connection = minecraft.getConnection();
        if (connection != null) {
            for (var info : connection.getOnlinePlayers()) {
                if (!minecraft.getUser().getProfileId().equals(info.getProfile().getId())) {
                    ((PlayerLookup) info).nclskins$resetProviderLookup();
                }
            }
        }
    }
}

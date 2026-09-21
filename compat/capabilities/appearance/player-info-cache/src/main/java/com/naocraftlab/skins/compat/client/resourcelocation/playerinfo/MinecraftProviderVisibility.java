package com.naocraftlab.skins.compat.client.resourcelocation.playerinfo;

import com.naocraftlab.skins.client.ProviderVisibility;

public final class MinecraftProviderVisibility {
    private static volatile ProviderVisibility current = ProviderVisibility.ALL;

    private MinecraftProviderVisibility() {}

    public static ProviderVisibility current() {
        return current;
    }

    public interface ProviderCallback extends net.minecraft.client.resources.SkinManager.SkinTextureCallback {}

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

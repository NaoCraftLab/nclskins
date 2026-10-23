package com.naocraftlab.skins.buildlogic.probe.wavey;

import java.util.Optional;
import java.util.UUID;
import net.minecraft.client.User;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.resources.ResourceLocation;
import static com.naocraftlab.skins.buildlogic.probe.wavey.ImmediateCapeHost.*;

public final class ImmediateAppearanceInputs {
    public static User currentUser(UUID identity) {
        return new User("Self", identity.toString(), "", Optional.empty(), Optional.empty(), User.Type.MSA);
    }

    public static PlayerInfo create(UUID identity) {
        PlayerInfo info = allocate(PlayerInfo.class);
        field(info, PlayerInfo.class, "profile", new com.mojang.authlib.GameProfile(identity, "Self"));
        field(info, PlayerInfo.class, "textureLocations", new java.util.HashMap<com.mojang.authlib.minecraft.MinecraftProfileTexture.Type, ResourceLocation>());
        field(info, PlayerInfo.class, "pendingTextures", true);
        return info;
    }
    public static ResourceLocation cape(PlayerInfo info) { return info.getCapeLocation(); }
}

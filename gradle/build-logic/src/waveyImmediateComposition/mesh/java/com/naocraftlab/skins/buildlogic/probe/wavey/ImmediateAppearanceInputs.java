package com.naocraftlab.skins.buildlogic.probe.wavey;

import java.util.Optional;
import java.util.UUID;
import net.minecraft.client.User;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.resources.ResourceLocation;
import static com.naocraftlab.skins.buildlogic.probe.wavey.ImmediateCapeHost.*;

public final class ImmediateAppearanceInputs {
    public static User currentUser(UUID identity) {
        return new User("Self", identity, "", Optional.empty(), Optional.empty(), User.Type.MSA);
    }

    public static PlayerInfo create(UUID identity) {
        PlayerInfo info = allocate(PlayerInfo.class);
        field(info, PlayerInfo.class, "profile", new com.mojang.authlib.GameProfile(identity, "Self"));
        var raw = new net.minecraft.client.resources.PlayerSkin(ResourceLocation.tryParse("nclskins:raw-skin"), "", null, null,
                net.minecraft.client.resources.PlayerSkin.Model.WIDE, false);
        field(info, PlayerInfo.class, "skinLookup", (java.util.function.Supplier<net.minecraft.client.resources.PlayerSkin>) () -> raw);
        return info;
    }
    public static ResourceLocation cape(PlayerInfo info) { return info.getSkin().capeTexture(); }
}

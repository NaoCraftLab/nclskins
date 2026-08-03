package com.naocraftlab.skins.compat.server;

import com.mojang.authlib.properties.Property;
import com.naocraftlab.skins.server.SignedTexturesProperty;
import java.util.Collection;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;


final class MinecraftProfilePropertyAccess implements ProfilePropertyAccess {
    private static final String TEXTURES = "textures";

    MinecraftProfilePropertyAccess(MinecraftServer server) {
        Objects.requireNonNull(server, "server");
    }

    @Override
    public CurrentProfileTextures currentTextures(ServerPlayer player) {
        Collection<Property> textures = player.getGameProfile().getProperties().get(TEXTURES);
        if (textures.isEmpty()) {
            return CurrentProfileTextures.accountDefault();
        }
        if (textures.size() != 1) {
            return CurrentProfileTextures.invalid();
        }
        Property property = textures.iterator().next();
        if (!TEXTURES.equals(property.name())
                || !property.hasSignature()
                || property.value() == null
                || property.signature() == null) {
            return CurrentProfileTextures.invalid();
        }
        return CurrentProfileTextures.signed(new SignedTexturesProperty(
                property.value(), property.signature()));
    }

    @Override
    public void installTextures(
            ServerPlayer player, Optional<SignedTexturesProperty> officialTextures) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(officialTextures, "officialTextures");
        player.getGameProfile().getProperties().removeAll(TEXTURES);
        officialTextures.ifPresent(textures -> player.getGameProfile().getProperties().put(
                TEXTURES,
                new Property(TEXTURES, textures.value(), textures.signature())));
    }
}

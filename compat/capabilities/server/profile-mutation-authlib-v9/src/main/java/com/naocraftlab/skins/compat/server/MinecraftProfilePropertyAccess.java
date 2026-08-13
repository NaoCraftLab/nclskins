package com.naocraftlab.skins.compat.server;

import com.google.common.collect.ImmutableListMultimap;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import com.mojang.authlib.properties.PropertyMap;
import com.naocraftlab.skins.compat.server.mixin.PlayerGameProfileAccessor;
import com.naocraftlab.skins.server.SignedTexturesProperty;
import java.util.Collection;
import java.util.Map;
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
        Collection<Property> textures = player.getGameProfile().properties().get(TEXTURES);
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
        GameProfile current = player.getGameProfile();
        ImmutableListMultimap.Builder<String, Property> replacement =
                ImmutableListMultimap.builder();
        for (Map.Entry<String, Property> entry : current.properties().entries()) {
            if (!TEXTURES.equals(entry.getKey())) {
                replacement.put(entry);
            }
        }
        officialTextures.ifPresent(textures -> replacement.put(
                TEXTURES,
                new Property(TEXTURES, textures.value(), textures.signature())));
        GameProfile replacementProfile = new GameProfile(
                current.id(), current.name(), new PropertyMap(replacement.build()));
        ((PlayerGameProfileAccessor) (Object) player)
                .nclskins$setGameProfile(replacementProfile);
    }
}

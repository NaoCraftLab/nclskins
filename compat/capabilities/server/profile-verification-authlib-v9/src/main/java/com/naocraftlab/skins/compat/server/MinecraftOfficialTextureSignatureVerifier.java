package com.naocraftlab.skins.compat.server;

import com.mojang.authlib.minecraft.MinecraftSessionService;
import com.mojang.authlib.properties.Property;
import com.naocraftlab.skins.server.OfficialTextureSignatureVerifier;
import com.naocraftlab.skins.server.ServerPlayerIdentity;
import com.naocraftlab.skins.server.SignedTexturesProperty;
import com.naocraftlab.skins.server.TextureAppearance;
import com.naocraftlab.skins.server.runtime.OfficialTextureAppearanceParser;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.server.MinecraftServer;


public final class MinecraftOfficialTextureSignatureVerifier
        implements OfficialTextureSignatureVerifier {
    private static final String TEXTURES = "textures";

    private final MinecraftSessionService sessionService;

    public MinecraftOfficialTextureSignatureVerifier(MinecraftServer server) {
        sessionService = Objects.requireNonNull(server, "server").services().sessionService();
    }

    @Override
    public Optional<TextureAppearance> verify(
            SignedTexturesProperty textures,
            ServerPlayerIdentity expectedIdentity) {
        Objects.requireNonNull(textures, "textures");
        Objects.requireNonNull(expectedIdentity, "expectedIdentity");
        Property property = new Property(TEXTURES, textures.value(), textures.signature());
        try {
            String verifiedPayload = sessionService.getSecurePropertyValue(property);
            if (!textures.value().equals(verifiedPayload)) {
                return Optional.empty();
            }
            return OfficialTextureAppearanceParser.parseVerified(
                    verifiedPayload, expectedIdentity);
        } catch (RuntimeException invalidSignature) {
            return Optional.empty();
        }
    }
}

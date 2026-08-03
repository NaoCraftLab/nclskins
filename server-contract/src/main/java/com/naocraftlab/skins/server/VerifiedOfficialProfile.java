package com.naocraftlab.skins.server;

import java.util.Objects;
import java.util.Optional;


public final class VerifiedOfficialProfile {
    private final ServerPlayerIdentity identity;
    private final TextureAppearance appearance;
    private final Optional<SignedTexturesProperty> textures;

    public VerifiedOfficialProfile(
            ServerPlayerIdentity identity,
            TextureAppearance appearance,
            Optional<SignedTexturesProperty> textures) {
        this.identity = Objects.requireNonNull(identity, "identity");
        this.appearance = Objects.requireNonNull(appearance, "appearance");
        this.textures = Objects.requireNonNull(textures, "textures");
        if (appearance.isUnknown()) {
            throw new IllegalArgumentException("Official profile appearance must be verified");
        }
        if (appearance.isAccountDefault() != textures.isEmpty()) {
            throw new IllegalArgumentException(
                    "Account-default profiles must not contain a textures property");
        }
    }

    public ServerPlayerIdentity identity() {
        return identity;
    }

    public TextureAppearance appearance() {
        return appearance;
    }

    public Optional<SignedTexturesProperty> textures() {
        return textures;
    }

    @Override
    public String toString() {
        return "VerifiedOfficialProfile[redacted]";
    }
}

package com.naocraftlab.skins.core.importing;

import com.naocraftlab.skins.core.api.PublicSkinImportException;
import com.naocraftlab.skins.core.model.SkinVariant;
import java.util.Objects;
import java.util.Optional;

@FunctionalInterface
public interface PublicProfileLookup {
    Observation lookup(String playerNameOrUuid) throws PublicSkinImportException;

    interface VerifiedTexture {}

    record Observation(String canonicalName, Optional<VerifiedTexture> texture,
            SkinVariant variant, Optional<String> defaultSkinId) {
        public Observation {
            Objects.requireNonNull(canonicalName, "canonicalName");
            Objects.requireNonNull(texture, "texture");
            Objects.requireNonNull(variant, "variant");
            Objects.requireNonNull(defaultSkinId, "defaultSkinId");
            if (texture.isPresent() == defaultSkinId.isPresent()) {
                throw new IllegalArgumentException("observation must be custom or default");
            }
        }
    }
}

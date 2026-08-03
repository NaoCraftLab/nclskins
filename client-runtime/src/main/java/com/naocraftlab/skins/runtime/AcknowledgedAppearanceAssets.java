package com.naocraftlab.skins.runtime;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;


public record AcknowledgedAppearanceAssets(
        Optional<Asset> skin,
        Optional<Asset> cape) {
    public AcknowledgedAppearanceAssets {
        skin = Objects.requireNonNull(skin, "skin");
        cape = Objects.requireNonNull(cape, "cape");
    }


    public record Asset(String sha256, Path path) {
        public Asset {
            Objects.requireNonNull(sha256, "sha256");
            path = Objects.requireNonNull(path, "path").toAbsolutePath().normalize();
            if (!sha256.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException(
                        "sha256 must contain 64 lowercase hexadecimal characters");
            }
        }
    }
}

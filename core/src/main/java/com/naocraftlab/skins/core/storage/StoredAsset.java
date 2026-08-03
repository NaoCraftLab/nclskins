package com.naocraftlab.skins.core.storage;

import com.naocraftlab.skins.core.png.PngInfo;
import java.nio.file.Path;
import java.util.Objects;

public record StoredAsset(String sha256, Path path, PngInfo pngInfo, boolean alreadyPresent) {
    public StoredAsset {
        Objects.requireNonNull(sha256, "sha256");
        path = Objects.requireNonNull(path, "path").toAbsolutePath().normalize();
        Objects.requireNonNull(pngInfo, "pngInfo");
    }
}

package com.naocraftlab.skins.core.storage;

import java.net.URI;
import java.nio.file.Path;
import java.util.Objects;

public record CachedTexture(URI source, Path path, boolean cacheHit, int width, int height) {
    public CachedTexture {
        Objects.requireNonNull(source, "source");
        path = Objects.requireNonNull(path, "path").toAbsolutePath().normalize();
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("texture dimensions must be positive");
        }
    }
}

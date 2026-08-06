package com.naocraftlab.skins.core.importing;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;


public sealed interface SkinLocator permits
        SkinLocator.EmbeddedPng,
        SkinLocator.LocalPng,
        SkinLocator.PublicUrl,
        SkinLocator.PublicPlayer,
        SkinLocator.MinecraftResource {

    record EmbeddedPng(byte[] pngBytes) implements SkinLocator {
        public EmbeddedPng {
            pngBytes = Objects.requireNonNull(pngBytes, "pngBytes").clone();
            if (pngBytes.length == 0) {
                throw new IllegalArgumentException("embedded PNG must not be empty");
            }
        }

        @Override
        public byte[] pngBytes() {
            return pngBytes.clone();
        }
    }

    record LocalPng(Path path) implements SkinLocator {
        public LocalPng {
            path = Objects.requireNonNull(path, "path").toAbsolutePath().normalize();
        }
    }

    record PublicUrl(String url, Optional<Path> localCache) implements SkinLocator {
        public PublicUrl {
            url = requireText(url, "url");
            localCache = Objects.requireNonNull(localCache, "localCache")
                    .map(path -> path.toAbsolutePath().normalize());
        }
    }

    record PublicPlayer(String nameOrUuid) implements SkinLocator {
        public PublicPlayer {
            nameOrUuid = requireText(nameOrUuid, "nameOrUuid");
        }
    }

    record MinecraftResource(String identifier) implements SkinLocator {
        public MinecraftResource {
            identifier = requireText(identifier, "identifier");
        }
    }

    private static String requireText(String value, String field) {
        String trimmed = Objects.requireNonNull(value, field).trim();
        if (trimmed.isEmpty() || trimmed.length() > 2048) {
            throw new IllegalArgumentException(field + " must contain between 1 and 2048 characters");
        }
        return trimmed;
    }
}

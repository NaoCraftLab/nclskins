package com.naocraftlab.skins.core.storage;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Locale;


final class PlatformDataRoot {
    private static final String UNRESOLVED_MESSAGE =
            "NCL Skins (nclskins) cannot resolve a per-user directory for its state because "
                    + "neither the platform data directory nor user.home is available.";

    private PlatformDataRoot() {}

    static Path resolve(
            String osName,
            String userHome,
            String appData,
            String xdgDataHome) {
        String normalizedOs = osName == null ? "" : osName.toLowerCase(Locale.ROOT);
        Path base;
        if (normalizedOs.startsWith("windows")) {
            base = absoluteOrNull(appData);
            if (base == null) {
                base = requiredHome(userHome).resolve("AppData").resolve("Roaming");
            }
        } else if (normalizedOs.contains("mac") || normalizedOs.contains("darwin")) {
            base = requiredHome(userHome).resolve("Library").resolve("Application Support");
        } else {
            base = absoluteOrNull(xdgDataHome);
            if (base == null) {
                base = requiredHome(userHome).resolve(".local").resolve("share");
            }
        }
        return base.resolve("NaoCraftLab").resolve("Skins").toAbsolutePath().normalize();
    }

    private static Path requiredHome(String userHome) {
        Path home = absoluteOrNull(userHome);
        if (home == null) {
            throw new IllegalStateException(UNRESOLVED_MESSAGE);
        }
        return home;
    }

    private static Path absoluteOrNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            Path path = Path.of(value);
            return path.isAbsolute() ? path.normalize() : null;
        } catch (InvalidPathException invalidPath) {
            return null;
        }
    }
}

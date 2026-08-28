package com.naocraftlab.skins.core.config;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Objects;


public record ClientConfiguration(
        MenuPreview menuPreview,
        Compatibility compatibility,
        Storage storage) {
    public ClientConfiguration {
        Objects.requireNonNull(menuPreview, "menuPreview");
        Objects.requireNonNull(compatibility, "compatibility");
        Objects.requireNonNull(storage, "storage");
    }

    public ClientConfiguration(MenuPreview menuPreview, Storage storage) {
        this(menuPreview, new Compatibility(false, false), storage);
    }

    public static ClientConfiguration defaults() {
        return new ClientConfiguration(
                new MenuPreview(true, true),
                new Compatibility(false, false),
                new Storage(""));
    }

    public Path dataRoot(Path operatingSystemDefault) {
        Path fallback = Objects.requireNonNull(operatingSystemDefault, "operatingSystemDefault");
        return storage.dataDirectory().isEmpty()
                ? fallback
                : Path.of(storage.dataDirectory());
    }

    public ClientConfiguration withTitleScreenPreview(boolean enabled) {
        return new ClientConfiguration(
                new MenuPreview(enabled, menuPreview.pauseMenu()), compatibility, storage);
    }

    public ClientConfiguration withPauseMenuPreview(boolean enabled) {
        return new ClientConfiguration(
                new MenuPreview(menuPreview.titleScreen(), enabled), compatibility, storage);
    }

    public ClientConfiguration withHideIncompatibleCatalogSkins(boolean enabled) {
        return new ClientConfiguration(
                menuPreview,
                new Compatibility(enabled, compatibility.hideIncompatibleGalleryLooks()),
                storage);
    }

    public ClientConfiguration withHideIncompatibleGalleryLooks(boolean enabled) {
        return new ClientConfiguration(
                menuPreview,
                new Compatibility(compatibility.hideIncompatibleCatalogSkins(), enabled),
                storage);
    }

    public ClientConfiguration withDataDirectory(String directory) {
        return new ClientConfiguration(menuPreview, compatibility, new Storage(directory));
    }

    public record MenuPreview(boolean titleScreen, boolean pauseMenu) {
    }

    public record Compatibility(
            boolean hideIncompatibleCatalogSkins,
            boolean hideIncompatibleGalleryLooks) {
    }

    public record Storage(String dataDirectory) {
        public Storage {
            dataDirectory = normalizeDataDirectory(dataDirectory);
        }
    }

    public static boolean validDataDirectory(String value) {
        try {
            normalizeDataDirectory(value);
            return true;
        } catch (IllegalArgumentException invalid) {
            return false;
        }
    }

    public static String normalizeDataDirectory(String value) {
        String checked = Objects.requireNonNull(value, "dataDirectory");
        if (checked.isEmpty()) {
            return checked;
        }
        try {
            Path path = Path.of(checked);
            if (!path.isAbsolute()) {
                throw new IllegalArgumentException("Data directory must be empty or absolute");
            }
            return path.normalize().toString();
        } catch (InvalidPathException invalid) {
            throw new IllegalArgumentException("Data directory is invalid", invalid);
        }
    }
}

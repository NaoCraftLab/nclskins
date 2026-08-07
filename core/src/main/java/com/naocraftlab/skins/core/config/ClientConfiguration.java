package com.naocraftlab.skins.core.config;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Objects;


public record ClientConfiguration(MenuPreview menuPreview, Storage storage) {
    public ClientConfiguration {
        Objects.requireNonNull(menuPreview, "menuPreview");
        Objects.requireNonNull(storage, "storage");
    }

    public static ClientConfiguration defaults() {
        return new ClientConfiguration(new MenuPreview(true, true), new Storage(""));
    }

    public Path dataRoot(Path operatingSystemDefault) {
        Path fallback = Objects.requireNonNull(operatingSystemDefault, "operatingSystemDefault");
        return storage.dataDirectory().isEmpty()
                ? fallback
                : Path.of(storage.dataDirectory());
    }

    public ClientConfiguration withTitleScreenPreview(boolean enabled) {
        return new ClientConfiguration(
                new MenuPreview(enabled, menuPreview.pauseMenu()), storage);
    }

    public ClientConfiguration withPauseMenuPreview(boolean enabled) {
        return new ClientConfiguration(
                new MenuPreview(menuPreview.titleScreen(), enabled), storage);
    }

    public ClientConfiguration withDataDirectory(String directory) {
        return new ClientConfiguration(menuPreview, new Storage(directory));
    }

    public record MenuPreview(boolean titleScreen, boolean pauseMenu) {
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

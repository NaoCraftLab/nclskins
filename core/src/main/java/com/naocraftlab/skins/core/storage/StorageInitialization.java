package com.naocraftlab.skins.core.storage;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

public record StorageInitialization(Path root, List<StorageWarning> warnings) {
    public StorageInitialization {
        root = Objects.requireNonNull(root, "root").toAbsolutePath().normalize();
        warnings = List.copyOf(Objects.requireNonNull(warnings, "warnings"));
    }
}

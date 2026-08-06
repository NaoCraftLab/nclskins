package com.naocraftlab.skins.core.importing;

import java.nio.file.Path;
import java.util.Objects;


public record ExternalImportContext(String profileName, Path currentGameDirectory) {
    public ExternalImportContext {
        profileName = Objects.requireNonNull(profileName, "profileName").trim();
        if (profileName.isEmpty()) {
            throw new IllegalArgumentException("profileName must not be blank");
        }
        currentGameDirectory = Objects.requireNonNull(
                currentGameDirectory, "currentGameDirectory").toAbsolutePath().normalize();
    }
}

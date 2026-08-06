package com.naocraftlab.skins.core.importing;

import java.nio.file.Path;
import java.util.Objects;
import java.util.UUID;


public record ExternalImportContext(
        UUID profileId,
        String profileName,
        Path currentGameDirectory) {
    public ExternalImportContext {
        profileId = Objects.requireNonNull(profileId, "profileId");
        profileName = Objects.requireNonNull(profileName, "profileName").trim();
        if (profileName.isEmpty()) {
            throw new IllegalArgumentException("profileName must not be blank");
        }
        currentGameDirectory = Objects.requireNonNull(
                currentGameDirectory, "currentGameDirectory").toAbsolutePath().normalize();
    }
}

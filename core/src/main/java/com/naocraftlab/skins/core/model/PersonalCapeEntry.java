package com.naocraftlab.skins.core.model;

import java.time.Instant;
import java.util.Objects;

public record PersonalCapeEntry(LocalCapeReference texture, String renderSha256, String name, Instant addedAt) {
    public PersonalCapeEntry {
        Objects.requireNonNull(texture, "texture");
        Objects.requireNonNull(texture.entryId(), "entryId");
        Objects.requireNonNull(renderSha256, "renderSha256");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(addedAt, "addedAt");
        name = name.trim();
        if (!renderSha256.matches("[0-9a-f]{64}") || name.isEmpty() || name.length() > 128) {
            throw new IllegalArgumentException("Invalid personal cape entry");
        }
    }

    public PersonalCapeEntry renamed(String value) {
        return new PersonalCapeEntry(texture, renderSha256, value, addedAt);
    }
}

package com.naocraftlab.skins.core.importing;

import com.naocraftlab.skins.core.model.SkinVariant;

import java.util.Objects;
import java.util.Optional;


public record ExternalAppearanceRecord(
        String sourceEntryId,
        String displayName,
        Optional<SkinVariant> declaredVariant,
        SkinLocator skinLocator,
        Optional<String> externalCapeId,
        int sourceOrder) {
    public ExternalAppearanceRecord {
        sourceEntryId = requireText(sourceEntryId, "sourceEntryId", 256);
        displayName = requireText(displayName, "displayName", 512);
        declaredVariant = Objects.requireNonNull(declaredVariant, "declaredVariant");
        skinLocator = Objects.requireNonNull(skinLocator, "skinLocator");
        externalCapeId = Objects.requireNonNull(externalCapeId, "externalCapeId")
                .map(String::trim)
                .filter(value -> !value.isEmpty() && value.length() <= 256);
        if (sourceOrder < 0) {
            throw new IllegalArgumentException("sourceOrder must not be negative");
        }
    }

    private static String requireText(String value, String field, int maximum) {
        String trimmed = Objects.requireNonNull(value, field).trim();
        if (trimmed.isEmpty() || trimmed.length() > maximum) {
            throw new IllegalArgumentException(
                    field + " must contain between 1 and " + maximum + " characters");
        }
        return trimmed;
    }
}

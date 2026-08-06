package com.naocraftlab.skins.core.importing;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;


public record ExternalImportBatch(
        ExternalImportSource source,
        List<ExternalAppearanceRecord> records,
        List<String> warnings) {
    public ExternalImportBatch {
        source = Objects.requireNonNull(source, "source");
        records = Objects.requireNonNull(records, "records").stream()
                .map(record -> Objects.requireNonNull(record, "records contains null"))
                .sorted(Comparator.comparingInt(ExternalAppearanceRecord::sourceOrder))
                .toList();
        warnings = Objects.requireNonNull(warnings, "warnings").stream()
                .map(warning -> Objects.requireNonNull(warning, "warnings contains null"))
                .filter(warning -> !warning.isBlank())
                .toList();
    }
}

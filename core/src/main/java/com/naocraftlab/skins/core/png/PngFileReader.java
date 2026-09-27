package com.naocraftlab.skins.core.png;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

public final class PngFileReader {
    private final PngValidator validator;
    public PngFileReader(PngValidator validator) { this.validator = Objects.requireNonNull(validator); }
    public PngInfo validate(Path path) throws IOException, PngValidationException {
        try (InputStream input = Files.newInputStream(path)) { return validator.validate(input); }
    }
    public byte[] normalizeSkin(Path path) throws IOException, PngValidationException { return projectImport(path).pngBytes(); }
    public NormalizedSkin normalizeSkinWithVariant(Path path) throws IOException, PngValidationException { return projectImport(path); }
    public NormalizedSkin projectImport(Path path) throws IOException, PngValidationException {
        try (InputStream input = Files.newInputStream(path)) { return validator.projectImport(input); }
    }
    public NormalizedSkin projectStandardImport(Path path) throws IOException, PngValidationException {
        try (InputStream input = Files.newInputStream(path)) { return validator.projectStandardImport(input); }
    }
}

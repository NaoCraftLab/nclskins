package com.naocraftlab.skins.runtime;

import com.naocraftlab.skins.core.importing.ExternalAppearanceRecord;
import com.naocraftlab.skins.core.importing.ExternalImportAdapter;
import com.naocraftlab.skins.core.importing.ExternalImportBatch;
import com.naocraftlab.skins.core.importing.ExternalImportContext;
import com.naocraftlab.skins.core.importing.ExternalImportSource;
import com.naocraftlab.skins.core.importing.SkinLocator;
import com.naocraftlab.skins.core.model.SkinVariant;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;


public final class SkinSwapperFamilyImportAdapter implements ExternalImportAdapter {
    private static final String TYPES_FILE = "types.json";
    private static final Comparator<Path> FILE_ORDER = Comparator
            .comparing((Path path) -> path.getFileName().toString().toLowerCase(Locale.ROOT))
            .thenComparing(path -> path.getFileName().toString());

    @Override
    public ExternalImportSource source() {
        return ExternalImportSource.SKIN_SWAPPER_FAMILY;
    }

    @Override
    public boolean probe(Path root, ExternalImportContext context) throws IOException {
        return findSkinsDirectory(root.toAbsolutePath().normalize()).isPresent();
    }

    @Override
    public ExternalImportBatch discover(Path root, ExternalImportContext context) throws IOException {
        Path skins = findSkinsDirectory(root.toAbsolutePath().normalize())
                .orElseThrow(() -> new IOException(
                        "SimpleSkinSwapper / Skin Swapper skins directory was not found"));
        List<Path> pngFiles = pngFiles(skins);
        List<String> warnings = new ArrayList<>();
        Map<String, Object> declaredTypes = readTypes(skins.resolve(TYPES_FILE), warnings);
        List<ExternalAppearanceRecord> records = new ArrayList<>();
        boolean warnedUnknownModel = false;
        int limit = Math.min(pngFiles.size(), ExternalImportFiles.MAX_RECORDS);
        for (int order = 0; order < limit; order++) {
            Path png = pngFiles.get(order);
            String fileName = png.getFileName().toString();
            Optional<SkinVariant> variant = Optional.empty();
            Object rawVariant = declaredTypes.get(fileName);
            if (rawVariant instanceof String value) {
                variant = ExternalImportFiles.variant(value);
                if (variant.isEmpty() && !warnedUnknownModel) {
                    warnings.add("unknown_model");
                    warnedUnknownModel = true;
                }
            }
            records.add(new ExternalAppearanceRecord(
                    "skin-swapper-" + order,
                    displayName(fileName, order),
                    variant,
                    new SkinLocator.LocalPng(png),
                    Optional.empty(),
                    order));
        }
        if (pngFiles.size() > ExternalImportFiles.MAX_RECORDS) {
            warnings.add("record_limit");
        }
        return new ExternalImportBatch(source(), records, warnings);
    }

    private static Optional<Path> findSkinsDirectory(Path root) throws IOException {
        List<Path> candidates = "skins".equalsIgnoreCase(String.valueOf(root.getFileName()))
                ? List.of(root)
                : List.of(root.resolve("skins"));
        for (Path candidate : candidates) {
            if (Files.isDirectory(candidate, LinkOption.NOFOLLOW_LINKS)
                    && !pngFiles(candidate).isEmpty()) {
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }

    private static List<Path> pngFiles(Path directory) throws IOException {
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            return List.of();
        }
        try (var children = Files.list(directory)) {
            return children
                    .filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                    .filter(SkinSwapperFamilyImportAdapter::isPng)
                    .sorted(FILE_ORDER)
                    .toList();
        }
    }

    private static Map<String, Object> readTypes(Path path, List<String> warnings) {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            return Map.of();
        }
        try {
            return ExternalImportFiles.readJsonObject(path);
        } catch (IOException | RuntimeException invalid) {
            warnings.add("invalid_model_metadata");
            return Map.of();
        }
    }

    private static boolean isPng(Path path) {
        return path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".png");
    }

    private static String displayName(String fileName, int order) {
        String name = fileName.substring(0, fileName.length() - 4).trim();
        return name.isEmpty() ? "Imported skin " + (order + 1) : name;
    }
}

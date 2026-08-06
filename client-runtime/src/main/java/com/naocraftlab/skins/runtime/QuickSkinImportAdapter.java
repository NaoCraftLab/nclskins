package com.naocraftlab.skins.runtime;

import com.naocraftlab.skins.core.importing.ExternalAppearanceRecord;
import com.naocraftlab.skins.core.importing.ExternalImportAdapter;
import com.naocraftlab.skins.core.importing.ExternalImportBatch;
import com.naocraftlab.skins.core.importing.ExternalImportContext;
import com.naocraftlab.skins.core.importing.ExternalImportSource;
import com.naocraftlab.skins.core.importing.SkinLocator;
import com.naocraftlab.skins.core.model.SkinVariant;
import com.naocraftlab.skins.core.png.PngValidator;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;


public final class QuickSkinImportAdapter implements ExternalImportAdapter {
    static final int MAX_SCAN_DEPTH = 16;
    private static final String PREFERENCES_FILE = "skin-preferences.json";
    private static final Comparator<Path> FILE_ORDER = Comparator
            .comparing((Path path) -> path.toString().toLowerCase(Locale.ROOT))
            .thenComparing(Path::toString);

    @Override
    public ExternalImportSource source() {
        return ExternalImportSource.QUICK_SKIN;
    }

    @Override
    public boolean probe(Path root, ExternalImportContext context) throws IOException {
        return findLayout(root.toAbsolutePath().normalize()).isPresent();
    }

    @Override
    public ExternalImportBatch discover(Path root, ExternalImportContext context) throws IOException {
        Layout layout = findLayout(root.toAbsolutePath().normalize())
                .orElseThrow(() -> new IOException("Quick Skin uploads directory was not found"));
        List<Path> pngFiles = pngFiles(layout.skinsDirectory());
        List<String> warnings = new ArrayList<>();
        Map<String, SkinVariant> preferences = readPreferences(layout.preferencesFile(), warnings);
        List<ExternalAppearanceRecord> records = new ArrayList<>();
        int limit = Math.min(pngFiles.size(), ExternalImportFiles.MAX_RECORDS);
        for (int order = 0; order < limit; order++) {
            Path png = pngFiles.get(order);
            Optional<SkinVariant> variant = rawSha1(png).map(preferences::get);
            String fileName = png.getFileName().toString();
            records.add(new ExternalAppearanceRecord(
                    "quick-skin-" + order,
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

    private static Optional<Layout> findLayout(Path root) throws IOException {
        Set<Layout> candidates = new LinkedHashSet<>();
        candidates.add(new Layout(
                root.resolve("quickskin/uploads/skins"),
                root.resolve("config").resolve(PREFERENCES_FILE)));
        if ("quickskin".equalsIgnoreCase(String.valueOf(root.getFileName()))) {
            candidates.add(new Layout(root.resolve("uploads/skins"), siblingConfig(root)));
        }
        if ("uploads".equalsIgnoreCase(String.valueOf(root.getFileName()))) {
            candidates.add(new Layout(root.resolve("skins"), siblingConfig(root)));
        }
        if ("skins".equalsIgnoreCase(String.valueOf(root.getFileName()))) {
            candidates.add(new Layout(root, configForDirectSkins(root)));
        }
        for (Layout candidate : candidates) {
            if (Files.isDirectory(candidate.skinsDirectory(), LinkOption.NOFOLLOW_LINKS)
                    && !pngFiles(candidate.skinsDirectory()).isEmpty()) {
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }

    private static Path siblingConfig(Path root) {
        Path base = root == null ? Path.of(".").toAbsolutePath().normalize() : root;
        if ("quickskin".equalsIgnoreCase(String.valueOf(base.getFileName()))) {
            Path game = base.getParent();
            return (game == null ? base : game).resolve("config").resolve(PREFERENCES_FILE);
        }
        if ("uploads".equalsIgnoreCase(String.valueOf(base.getFileName()))
                && base.getParent() != null
                && "quickskin".equalsIgnoreCase(String.valueOf(base.getParent().getFileName()))) {
            Path game = base.getParent().getParent();
            return (game == null ? base : game).resolve("config").resolve(PREFERENCES_FILE);
        }
        return base.resolve("config").resolve(PREFERENCES_FILE);
    }

    private static Path configForDirectSkins(Path skins) {
        Path uploads = skins.getParent();
        Path quickskin = uploads == null ? null : uploads.getParent();
        Path game = quickskin == null ? null : quickskin.getParent();
        if (uploads != null
                && quickskin != null
                && "uploads".equalsIgnoreCase(String.valueOf(uploads.getFileName()))
                && "quickskin".equalsIgnoreCase(String.valueOf(quickskin.getFileName()))
                && game != null) {
            return game.resolve("config").resolve(PREFERENCES_FILE);
        }
        return skins.resolve(PREFERENCES_FILE);
    }

    private static List<Path> pngFiles(Path directory) throws IOException {
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            return List.of();
        }
        try (var files = Files.walk(directory, MAX_SCAN_DEPTH)) {
            return files
                    .filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                    .filter(QuickSkinImportAdapter::isPng)
                    .sorted(FILE_ORDER)
                    .toList();
        }
    }

    private static Map<String, SkinVariant> readPreferences(Path path, List<String> warnings) {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            return Map.of();
        }
        try {
            Map<String, Object> root = ExternalImportFiles.readJsonObject(path);
            Optional<Map<String, Object>> rawPreferences = ExternalImportFiles.object(root, "preferences");
            if (rawPreferences.isEmpty()) {
                warnings.add("invalid_model_metadata");
                return Map.of();
            }
            java.util.HashMap<String, SkinVariant> parsed = new java.util.HashMap<>();
            boolean unknown = false;
            for (Map.Entry<String, Object> entry : rawPreferences.orElseThrow().entrySet()) {
                Optional<Map<String, Object>> preference = ExternalImportFiles.asObject(entry.getValue());
                Optional<String> model = preference.flatMap(value ->
                        ExternalImportFiles.string(value, "modelType"));
                if (model.isEmpty() || "auto".equalsIgnoreCase(model.orElseThrow())) {
                    continue;
                }
                Optional<SkinVariant> variant = model.flatMap(ExternalImportFiles::variant);
                if (variant.isPresent()) {
                    parsed.put(entry.getKey().toLowerCase(Locale.ROOT), variant.orElseThrow());
                } else {
                    unknown = true;
                }
            }
            if (unknown) {
                warnings.add("unknown_model");
            }
            return Map.copyOf(parsed);
        } catch (IOException | RuntimeException invalid) {
            warnings.add("invalid_model_metadata");
            return Map.of();
        }
    }

    private static Optional<String> rawSha1(Path path) {
        try (InputStream input = Files.newInputStream(path)) {
            byte[] bytes = input.readNBytes(PngValidator.DEFAULT_MAX_BYTES + 1);
            if (bytes.length > PngValidator.DEFAULT_MAX_BYTES) {
                return Optional.empty();
            }
            return Optional.of(HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-1").digest(bytes)));
        } catch (IOException | NoSuchAlgorithmException failure) {
            return Optional.empty();
        }
    }

    private static boolean isPng(Path path) {
        return path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".png");
    }

    private static String displayName(String fileName, int order) {
        String name = fileName.substring(0, fileName.length() - 4).trim();
        return name.isEmpty() ? "Imported skin " + (order + 1) : name;
    }

    private record Layout(Path skinsDirectory, Path preferencesFile) {
        private Layout {
            skinsDirectory = skinsDirectory.toAbsolutePath().normalize();
            preferencesFile = preferencesFile.toAbsolutePath().normalize();
        }
    }
}

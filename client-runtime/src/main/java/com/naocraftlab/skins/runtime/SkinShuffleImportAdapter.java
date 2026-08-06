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
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;


public final class SkinShuffleImportAdapter implements ExternalImportAdapter {
    @Override
    public ExternalImportSource source() {
        return ExternalImportSource.SKIN_SHUFFLE;
    }

    @Override
    public boolean probe(Path root, ExternalImportContext context) {
        try {
            Path data = findDataDirectory(root.toAbsolutePath().normalize());
            boolean multiAccount = Files.isRegularFile(data.resolve("config.json"))
                    && ExternalImportFiles.bool(
                    ExternalImportFiles.readJsonObject(data.resolve("config.json")),
                    "enableMultiAccountSupport");
            return Files.isRegularFile(multiAccount
                    ? accountPresets(data, context.profileName())
                    : data.resolve("presets.json"));
        } catch (IOException | RuntimeException failure) {
            return false;
        }
    }

    @Override
    public ExternalImportBatch discover(Path root, ExternalImportContext context) throws IOException {
        Path data = findDataDirectory(root.toAbsolutePath().normalize());
        boolean multiAccount = Files.isRegularFile(data.resolve("config.json"))
                && ExternalImportFiles.bool(
                ExternalImportFiles.readJsonObject(data.resolve("config.json")),
                "enableMultiAccountSupport");
        Path presets = multiAccount
                ? accountPresets(data, context.profileName())
                : data.resolve("presets.json");
        if (!Files.isRegularFile(presets)) {
            throw new IOException(multiAccount
                    ? "Skin Shuffle has no presets for the current account"
                    : "Skin Shuffle preset metadata was not found");
        }

        Map<String, Object> object = ExternalImportFiles.readJsonObject(presets);
        Optional<List<Object>> loaded = ExternalImportFiles.array(object, "loadedPresets");
        if (loaded.isEmpty()) {
            throw new IOException("Skin Shuffle preset metadata has an unsupported format");
        }
        List<ExternalAppearanceRecord> records = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        Path gameRoot = gameRoot(data, context.currentGameDirectory());
        int order = 0;
        for (Object element : loaded.orElseThrow()) {
            if (order >= ExternalImportFiles.MAX_RECORDS) {
                warnings.add("record_limit");
                break;
            }
            int sourceOrder = order++;
            Optional<Map<String, Object>> parsedPreset = ExternalImportFiles.asObject(element);
            if (parsedPreset.isEmpty()) {
                warnings.add("malformed_record");
                continue;
            }
            Map<String, Object> preset = parsedPreset.orElseThrow();
            if (preset.containsKey("cape")) {
                warnings.add("unsupported_cape_provider");
            }
            Optional<String> name = ExternalImportFiles.string(preset, "name");
            Optional<Map<String, Object>> skinElement = ExternalImportFiles.object(preset, "skin");
            if (name.isEmpty() || skinElement.isEmpty()) {
                warnings.add("malformed_record");
                continue;
            }
            Map<String, Object> skin = skinElement.orElseThrow();
            Optional<SkinLocator> locator = locator(data, gameRoot, skin);
            if (locator.isEmpty()) {
                warnings.add("unsupported_skin_source");
                continue;
            }
            Optional<SkinVariant> variant = ExternalImportFiles.string(skin, "model")
                    .flatMap(ExternalImportFiles::variant);
            if (ExternalImportFiles.string(skin, "model").isPresent() && variant.isEmpty()) {
                warnings.add("unknown_model");
            }
            records.add(new ExternalAppearanceRecord(
                    "skinshuffle-" + sourceOrder,
                    name.orElseThrow(),
                    variant,
                    locator.orElseThrow(),
                    Optional.empty(),
                    sourceOrder));
        }
        return new ExternalImportBatch(source(), records, warnings);
    }

    private static Path findDataDirectory(Path root) throws IOException {
        List<Path> candidates = List.of(
                root,
                root.resolve("config").resolve("skinshuffle"),
                root.resolve("skinshuffle"));
        for (Path candidate : candidates) {
            if (Files.isRegularFile(candidate.resolve("presets.json"))
                    || Files.isRegularFile(candidate.resolve("config.json"))
                    || hasAccountPreset(candidate)) {
                return candidate;
            }
        }
        throw new IOException("Skin Shuffle data directory was not found");
    }

    private static boolean hasAccountPreset(Path directory) {
        if (!Files.isDirectory(directory)) {
            return false;
        }
        try (var files = Files.list(directory)) {
            return files.anyMatch(path -> {
                String name = path.getFileName().toString();
                return name.startsWith("presets-") && name.endsWith(".json") && Files.isRegularFile(path);
            });
        } catch (IOException failure) {
            return false;
        }
    }

    private static Path accountPresets(Path data, String profileName) throws IOException {
        if (profileName.indexOf('/') >= 0 || profileName.indexOf('\\') >= 0) {
            throw new IOException("Current account name cannot identify Skin Shuffle presets");
        }
        return data.resolve("presets-" + profileName + ".json").normalize();
    }

    private static Path gameRoot(Path data, Path fallback) {
        Path parent = data.getParent();
        if (parent != null
                && "skinshuffle".equalsIgnoreCase(String.valueOf(data.getFileName()))
                && "config".equalsIgnoreCase(String.valueOf(parent.getFileName()))
                && parent.getParent() != null) {
            return parent.getParent().toAbsolutePath().normalize();
        }
        return fallback.toAbsolutePath().normalize();
    }

    private static Optional<SkinLocator> locator(
            Path data, Path gameRoot, Map<String, Object> skin) {
        Optional<String> rawType = ExternalImportFiles.string(skin, "type");
        if (rawType.isEmpty()) {
            return Optional.empty();
        }
        String type = rawType.orElseThrow().toLowerCase(Locale.ROOT);
        int separator = type.indexOf(':');
        if (separator >= 0) {
            type = type.substring(separator + 1);
        }
        return switch (type) {
            case "config" -> ExternalImportFiles.string(skin, "skin_name")
                    .flatMap(name -> ExternalImportFiles.safeChild(data.resolve("skins"), name + ".png"))
                    .map(SkinLocator.LocalPng::new);
            case "file" -> ExternalImportFiles.string(skin, "path").flatMap(path -> {
                try {
                    Path parsed = Path.of(path);
                    if (parsed.isAbsolute()) {
                        return Optional.of(new SkinLocator.LocalPng(parsed));
                    }
                    return ExternalImportFiles.safeChild(gameRoot, path).map(SkinLocator.LocalPng::new);
                } catch (RuntimeException invalid) {
                    return Optional.empty();
                }
            });
            case "url" -> ExternalImportFiles.string(skin, "url").map(url ->
                    new SkinLocator.PublicUrl(
                            url,
                            Optional.of(data.resolve("skins").resolve("downloaded")
                                    .resolve(Integer.toString(url.hashCode()) + ".png"))));
            case "username" -> ExternalImportFiles.string(skin, "username")
                    .map(SkinLocator.PublicPlayer::new);
            case "uuid" -> ExternalImportFiles.string(skin, "uuid")
                    .map(SkinLocator.PublicPlayer::new);
            case "resource" -> ExternalImportFiles.string(skin, "texture")
                    .map(SkinLocator.MinecraftResource::new);
            default -> Optional.empty();
        };
    }
}

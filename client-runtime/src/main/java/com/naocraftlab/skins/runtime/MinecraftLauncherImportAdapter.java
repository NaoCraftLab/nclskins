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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;


public final class MinecraftLauncherImportAdapter implements ExternalImportAdapter {
    public static final String CURRENT_FILE = "launcher_custom_skins.json";
    public static final String LEGACY_FILE = "launcher_skins.json";

    @Override
    public ExternalImportSource source() {
        return ExternalImportSource.MINECRAFT_LAUNCHER;
    }

    @Override
    public boolean probe(Path root, ExternalImportContext context) {
        Path normalized = root.toAbsolutePath().normalize();
        if (Files.isRegularFile(normalized)) {
            String fileName = String.valueOf(normalized.getFileName());
            return CURRENT_FILE.equals(fileName) || LEGACY_FILE.equals(fileName);
        }
        return Files.isRegularFile(normalized.resolve(CURRENT_FILE))
                || Files.isRegularFile(normalized.resolve(LEGACY_FILE));
    }

    @Override
    public ExternalImportBatch discover(Path root, ExternalImportContext context) throws IOException {
        Path normalized = root.toAbsolutePath().normalize();
        Path metadata = Files.isRegularFile(normalized)
                ? normalized
                : Files.isRegularFile(normalized.resolve(CURRENT_FILE))
                ? normalized.resolve(CURRENT_FILE)
                : normalized.resolve(LEGACY_FILE);
        if (!Files.isRegularFile(metadata)
                || !(CURRENT_FILE.equals(metadata.getFileName().toString())
                || LEGACY_FILE.equals(metadata.getFileName().toString()))) {
            throw new IOException("Minecraft Launcher skin metadata was not found");
        }

        Map<String, Object> rootObject = ExternalImportFiles.readJsonObject(metadata);
        Map<String, Object> skins = ExternalImportFiles.object(rootObject, "customSkins")
                .orElse(rootObject);
        List<ExternalAppearanceRecord> records = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        int order = 0;
        for (Map.Entry<String, Object> entry : skins.entrySet()) {
            if (order >= ExternalImportFiles.MAX_RECORDS) {
                warnings.add("record_limit");
                break;
            }
            int sourceOrder = order++;
            Optional<Map<String, Object>> parsedSkin = ExternalImportFiles.asObject(entry.getValue());
            if (parsedSkin.isEmpty()) {
                warnings.add("malformed_record");
                continue;
            }
            Map<String, Object> skin = parsedSkin.orElseThrow();
            Optional<byte[]> png = decodeSkinImage(ExternalImportFiles.string(skin, "skinImage"));
            if (png.isEmpty()) {
                warnings.add("missing_skin_image");
                continue;
            }
            String name = ExternalImportFiles.string(skin, "name").orElse("Imported skin");
            Optional<SkinVariant> variant = skin.containsKey("slim")
                    ? Optional.of(ExternalImportFiles.bool(skin, "slim")
                    ? SkinVariant.SLIM : SkinVariant.CLASSIC)
                    : Optional.empty();
            records.add(new ExternalAppearanceRecord(
                    "launcher-" + sourceOrder,
                    name,
                    variant,
                    new SkinLocator.EmbeddedPng(png.orElseThrow()),
                    ExternalImportFiles.string(skin, "capeId"),
                    sourceOrder));
        }
        return new ExternalImportBatch(source(), records, warnings);
    }

    private static Optional<byte[]> decodeSkinImage(Optional<String> encoded) {
        if (encoded.isEmpty()) {
            return Optional.empty();
        }
        String value = encoded.orElseThrow();
        int comma = value.indexOf(',');
        if (value.startsWith("data:")) {
            if (comma < 0 || !value.substring(0, comma).toLowerCase().contains(";base64")) {
                return Optional.empty();
            }
            value = value.substring(comma + 1);
        }
        if (value.length() > (PngValidator.DEFAULT_MAX_BYTES * 4 / 3) + 16) {
            return Optional.empty();
        }
        try {
            byte[] bytes = Base64.getDecoder().decode(value);
            return bytes.length == 0 || bytes.length > PngValidator.DEFAULT_MAX_BYTES
                    ? Optional.empty()
                    : Optional.of(bytes);
        } catch (IllegalArgumentException malformed) {
            return Optional.empty();
        }
    }
}

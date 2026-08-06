package com.naocraftlab.skins.runtime;

import com.naocraftlab.skins.core.importing.ExternalJson;
import com.naocraftlab.skins.core.model.SkinVariant;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;


final class ExternalImportFiles {
    static final int MAX_RECORDS = 2048;
    private static final int MAX_JSON_BYTES = 4 * 1024 * 1024;

    private ExternalImportFiles() {
    }

    static Map<String, Object> readJsonObject(Path path) throws IOException {
        return ExternalJson.readObject(path, MAX_JSON_BYTES);
    }

    static String readBoundedText(Path path) throws IOException {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("External import configuration is unavailable");
        }
        try (InputStream input = Files.newInputStream(path)) {
            byte[] bytes = input.readNBytes(1024 * 1024 + 1);
            if (bytes.length > 1024 * 1024) {
                throw new IOException("External import configuration is too large");
            }
            return new String(bytes, StandardCharsets.UTF_8);
        }
    }

    static Optional<String> string(Map<String, Object> object, String key) {
        Object element = object.get(key);
        if (!(element instanceof String value)) {
            return Optional.empty();
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? Optional.empty() : Optional.of(trimmed);
    }

    static boolean bool(Map<String, Object> object, String key) {
        Object element = object.get(key);
        if (element instanceof Boolean booleanValue) {
            return booleanValue;
        }
        return element instanceof String stringValue && Boolean.parseBoolean(stringValue);
    }

    static Optional<Map<String, Object>> object(Map<String, Object> object, String key) {
        return asObject(object.get(key));
    }

    static Optional<Map<String, Object>> asObject(Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            return Optional.empty();
        }
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (!(entry.getKey() instanceof String)) {
                return Optional.empty();
            }
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> typed = (Map<String, Object>) map;
        return Optional.of(typed);
    }

    static Optional<List<Object>> array(Map<String, Object> object, String key) {
        Object value = object.get(key);
        if (!(value instanceof List<?> list)) {
            return Optional.empty();
        }
        @SuppressWarnings("unchecked")
        List<Object> typed = (List<Object>) list;
        return Optional.of(typed);
    }

    static Optional<SkinVariant> variant(String value) {
        if (value == null) {
            return Optional.empty();
        }
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "slim", "narrow" -> Optional.of(SkinVariant.SLIM);
            case "classic", "wide", "default" -> Optional.of(SkinVariant.CLASSIC);
            default -> Optional.empty();
        };
    }

    static Optional<Path> safeChild(Path root, String child) {
        try {
            if (child == null || child.isBlank()) {
                return Optional.empty();
            }
            Path normalizedRoot = root.toAbsolutePath().normalize();
            Path candidate = normalizedRoot.resolve(child).normalize();
            return candidate.startsWith(normalizedRoot) ? Optional.of(candidate) : Optional.empty();
        } catch (RuntimeException invalid) {
            return Optional.empty();
        }
    }
}

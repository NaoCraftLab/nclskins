package com.naocraftlab.skins.core.importing;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;


public final class ExternalJson {
    private static final int MAX_DEPTH = 64;
    private static final int MAX_NODES = 100_000;

    private ExternalJson() {
    }

    public static Map<String, Object> readObject(Path path, int maxBytes) throws IOException {
        Objects.requireNonNull(path, "path");
        if (maxBytes < 1 || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("External import metadata is unavailable");
        }
        byte[] bytes;
        try (InputStream input = Files.newInputStream(path)) {
            bytes = input.readNBytes(maxBytes + 1);
        }
        if (bytes.length > maxBytes) {
            throw new IOException("External import metadata is too large");
        }
        try {
            JsonElement parsed = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8));
            if (!parsed.isJsonObject()) {
                throw new IOException("External import metadata has an unsupported root");
            }
            int[] nodes = {0};
            Object converted = convert(parsed, 0, nodes);
            @SuppressWarnings("unchecked")
            Map<String, Object> object = (Map<String, Object>) converted;
            return object;
        } catch (IOException failure) {
            throw failure;
        } catch (RuntimeException malformed) {
            throw new IOException("External import metadata is malformed", malformed);
        }
    }

    private static Object convert(JsonElement element, int depth, int[] nodes) throws IOException {
        if (depth > MAX_DEPTH || ++nodes[0] > MAX_NODES) {
            throw new IOException("External import metadata is too complex");
        }
        if (element.isJsonObject()) {
            Map<String, Object> object = new LinkedHashMap<>();
            for (Map.Entry<String, JsonElement> entry : element.getAsJsonObject().entrySet()) {
                object.put(entry.getKey(), convert(entry.getValue(), depth + 1, nodes));
            }
            return Collections.unmodifiableMap(object);
        }
        if (element.isJsonArray()) {
            List<Object> array = new ArrayList<>();
            for (JsonElement child : element.getAsJsonArray()) {
                array.add(convert(child, depth + 1, nodes));
            }
            return List.copyOf(array);
        }
        if (element.isJsonNull()) {
            return NullValue.INSTANCE;
        }
        if (element.getAsJsonPrimitive().isBoolean()) {
            return element.getAsBoolean();
        }
        if (element.getAsJsonPrimitive().isString()) {
            return element.getAsString();
        }
        return element.getAsString();
    }

    public enum NullValue {
        INSTANCE
    }
}

package com.naocraftlab.skins.core.storage;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.naocraftlab.skins.core.model.FullPreviewModelFit;
import com.naocraftlab.skins.core.model.SkinVariant;

import java.io.IOException;
import java.io.StringReader;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

final class PreviewModelFitJson {
    private static final Gson GSON = new Gson();
    private static final Set<String> FIELDS = Set.of("referenceHeight", "normalization", "bodyHeight",
            "centerX", "centerY", "centerZ", "halfWidth", "halfDepth", "referenceCenterX", "referenceCenterY", "referenceCenterZ");

    byte[] encode(UUID account, String hash, SkinVariant variant, FullPreviewModelFit fit) {
        JsonObject record = new JsonObject();
        record.addProperty("schemaVersion", 1);
        record.addProperty("accountId", account.toString());
        record.addProperty("sha256", hash);
        record.addProperty("variant", variant.name());
        record.addProperty("units", "entity");
        record.add("fit", GSON.toJsonTree(fit));
        return GSON.toJson(record).getBytes(StandardCharsets.UTF_8);
    }

    FullPreviewModelFit decode(byte[] bytes, UUID account, String hash, SkinVariant variant) throws IOException {
        String json = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
        try (JsonReader reader = new JsonReader(new StringReader(json))) {
            Set<String> seen = new HashSet<>();
            FullPreviewModelFit fit = null;
            reader.beginObject();
            while (reader.hasNext()) {
                String field = reader.nextName();
                if (!seen.add(field)) throw new IOException("Duplicate fit record field");
                switch (field) {
                    case "schemaVersion" -> {
                        if (reader.peek() != JsonToken.NUMBER
                                || new BigDecimal(reader.nextString()).intValueExact() != 1) {
                            throw new IOException("Unsupported fit record schema");
                        }
                    }
                    case "accountId" -> requireString(reader, account.toString());
                    case "sha256" -> requireString(reader, hash);
                    case "variant" -> requireString(reader, variant.name());
                    case "units" -> requireString(reader, "entity");
                    case "fit" -> fit = readFit(reader);
                    default -> throw new IOException("Unknown fit record field");
                }
            }
            reader.endObject();
            if (seen.size() != 6 || fit == null || reader.peek() != JsonToken.END_DOCUMENT) {
                throw new IOException("Incomplete fit record");
            }
            return fit;
        }
    }

    private static void requireString(JsonReader reader, String expected) throws IOException {
        if (reader.peek() != JsonToken.STRING || !expected.equals(reader.nextString())) {
            throw new IOException("Foreign fit record identity or units");
        }
    }

    private static FullPreviewModelFit readFit(JsonReader reader) throws IOException {
        Map<String, Double> numbers = new HashMap<>();
        reader.beginObject();
        while (reader.hasNext()) {
            String field = reader.nextName();
            if (!FIELDS.contains(field) || numbers.containsKey(field) || reader.peek() != JsonToken.NUMBER) {
                throw new IOException("Invalid fit number");
            }
            String encoded = reader.nextString();
            double value = Double.parseDouble(encoded);
            if (!Double.isFinite(value) || (value == 0 && new BigDecimal(encoded).signum() != 0)) {
                throw new IOException("Unrepresentable fit number");
            }
            numbers.put(field, value);
        }
        reader.endObject();
        if (numbers.size() != FIELDS.size()) throw new IOException("Incomplete fit numbers");
        return new FullPreviewModelFit(numbers.get("referenceHeight"), numbers.get("normalization"),
                numbers.get("bodyHeight"), numbers.get("centerX"), numbers.get("centerY"),
                numbers.get("centerZ"), numbers.get("halfWidth"), numbers.get("halfDepth"),
                numbers.get("referenceCenterX"), numbers.get("referenceCenterY"), numbers.get("referenceCenterZ"));
    }
}

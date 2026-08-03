package com.naocraftlab.skins.core.storage;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.naocraftlab.skins.core.model.AccountAppearanceState;
import com.naocraftlab.skins.core.model.AppearanceSyncStatus;
import com.naocraftlab.skins.client.OuterLayerPart;
import com.naocraftlab.skins.client.OuterLayerVisibility;
import com.naocraftlab.skins.core.model.SkinVariant;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

final class AccountAppearanceStateJson {
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create();

    byte[] encode(AccountAppearanceState state) {
        JsonObject root = new JsonObject();
        root.addProperty("schemaVersion", state.schemaVersion());
        root.addProperty("accountId", state.accountId().toString());
        root.addProperty("intentRevision", state.intentRevision());
        state.optionalActivePresetId().ifPresent(id -> root.addProperty("activePresetId", id.toString()));
        state.optionalSkinSha256().ifPresent(hash -> root.addProperty("skinSha256", hash));
        state.optionalSkinVariant().ifPresent(variant -> root.addProperty("skinVariant", variant.name()));
        state.optionalCapeId().ifPresent(cape -> root.addProperty("capeId", cape));
        state.optionalOuterLayerVisibility().ifPresent(visibility -> {
            com.google.gson.JsonArray outerLayer = new com.google.gson.JsonArray();
            for (OuterLayerPart part : OuterLayerPart.values()) {
                if (visibility.visible(part)) {
                    outerLayer.add(part.name());
                }
            }
            root.add("outerLayer", outerLayer);
        });
        root.addProperty("syncStatus", state.syncStatus().name());
        root.addProperty("settledRevision", state.settledRevision());
        root.addProperty("updatedAt", state.updatedAt().toString());
        return (GSON.toJson(root) + System.lineSeparator()).getBytes(StandardCharsets.UTF_8);
    }

    Decoded decode(byte[] bytes) throws StorageException {
        try {
            JsonObject root = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8))
                    .getAsJsonObject();
            int schema = required(root, "schemaVersion").getAsInt();
            if (schema != 1 && schema != AccountAppearanceState.CURRENT_SCHEMA_VERSION) {
                throw new StorageException(
                        StorageException.Code.UNSUPPORTED_SCHEMA,
                        "Unsupported appearance state schema: " + schema);
            }
            long intentRevision = required(root, "intentRevision").getAsLong();
            AccountAppearanceState state = new AccountAppearanceState(
                    AccountAppearanceState.CURRENT_SCHEMA_VERSION,
                    UUID.fromString(required(root, "accountId").getAsString()),
                    intentRevision,
                    optional(root, "activePresetId") == null
                            ? null
                            : UUID.fromString(optional(root, "activePresetId").getAsString()),
                    optionalString(root, "skinSha256"),
                    optionalString(root, "skinVariant") == null
                            ? null
                            : SkinVariant.valueOf(optionalString(root, "skinVariant")),
                    optionalString(root, "capeId"),
                    schema == 1
                            ? intentRevision == 0 ? null : OuterLayerVisibility.allVisible()
                            : decodeOuterLayer(root, intentRevision),
                    AppearanceSyncStatus.valueOf(required(root, "syncStatus").getAsString()),
                    required(root, "settledRevision").getAsLong(),
                    Instant.parse(required(root, "updatedAt").getAsString()));
            return new Decoded(state, schema != AccountAppearanceState.CURRENT_SCHEMA_VERSION);
        } catch (StorageException exception) {
            throw exception;
        } catch (JsonParseException | IllegalArgumentException | IllegalStateException exception) {
            throw new StorageException(
                    StorageException.Code.INVALID_STATE,
                    "Appearance state is malformed",
                    exception);
        }
    }

    private static OuterLayerVisibility decodeOuterLayer(JsonObject root, long intentRevision) {
        if (intentRevision == 0) {
            return null;
        }
        JsonElement element = root.get("outerLayer");
        if (element == null || !element.isJsonArray()) {
            throw new JsonParseException("Missing array: outerLayer");
        }
        java.util.EnumSet<OuterLayerPart> visible = java.util.EnumSet.noneOf(OuterLayerPart.class);
        for (JsonElement part : element.getAsJsonArray()) {
            visible.add(OuterLayerPart.valueOf(part.getAsString()));
        }
        return OuterLayerVisibility.of(visible);
    }

    private static JsonElement required(JsonObject object, String member) {
        JsonElement value = object.get(member);
        if (value == null || value.isJsonNull() || !value.isJsonPrimitive()) {
            throw new JsonParseException("Missing primitive: " + member);
        }
        return value;
    }

    private static JsonElement optional(JsonObject object, String member) {
        JsonElement value = object.get(member);
        return value == null || value.isJsonNull() ? null : value;
    }

    private static String optionalString(JsonObject object, String member) {
        JsonElement value = optional(object, member);
        return value == null ? null : value.getAsString();
    }

    record Decoded(AccountAppearanceState state, boolean migrated) {}
}

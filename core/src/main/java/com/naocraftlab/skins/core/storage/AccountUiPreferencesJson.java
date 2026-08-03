package com.naocraftlab.skins.core.storage;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.naocraftlab.skins.core.model.AccountUiPreferences;
import com.naocraftlab.skins.core.model.AddSourceTab;
import com.naocraftlab.skins.core.model.SkinVariant;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

final class AccountUiPreferencesJson {
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create();

    byte[] encode(AccountUiPreferences preferences) {
        JsonObject root = new JsonObject();
        root.addProperty("schemaVersion", preferences.schemaVersion());
        root.addProperty("accountId", preferences.accountId().toString());
        root.addProperty("selectedAddSourceTab", preferences.selectedAddSourceTab().name());
        preferences.preferredSkinVariant()
                .ifPresent(variant -> root.addProperty("preferredSkinVariant", variant.name()));
        JsonArray collapsedCollectionIds = new JsonArray();
        preferences.collapsedCollectionIds().stream()
                .sorted()
                .forEach(collapsedCollectionIds::add);
        root.add("collapsedCollectionIds", collapsedCollectionIds);
        return (GSON.toJson(root) + System.lineSeparator()).getBytes(StandardCharsets.UTF_8);
    }

    AccountUiPreferences decode(byte[] bytes) throws StorageException {
        try {
            JsonObject root = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8))
                    .getAsJsonObject();
            int schemaVersion = required(root, "schemaVersion").getAsInt();
            if (schemaVersion != AccountUiPreferences.CURRENT_SCHEMA_VERSION) {
                throw new StorageException(
                        StorageException.Code.UNSUPPORTED_SCHEMA,
                        "Unsupported UI preferences schema: " + schemaVersion);
            }
            Set<String> collapsedCollectionIds = new LinkedHashSet<>();
            JsonElement collapsed = root.get("collapsedCollectionIds");
            if (collapsed == null || !collapsed.isJsonArray()) {
                throw new JsonParseException("Missing array: collapsedCollectionIds");
            }
            for (JsonElement element : collapsed.getAsJsonArray()) {
                if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
                    throw new JsonParseException("Collection id must be a string");
                }
                collapsedCollectionIds.add(element.getAsString());
            }
            JsonElement preferred = root.get("preferredSkinVariant");
            Optional<SkinVariant> preferredSkinVariant;
            if (preferred == null || preferred.isJsonNull()) {
                preferredSkinVariant = Optional.empty();
            } else if (preferred.isJsonPrimitive() && preferred.getAsJsonPrimitive().isString()) {
                preferredSkinVariant = Optional.of(SkinVariant.valueOf(preferred.getAsString()));
            } else {
                throw new JsonParseException("preferredSkinVariant must be a string");
            }
            return new AccountUiPreferences(
                    schemaVersion,
                    UUID.fromString(required(root, "accountId").getAsString()),
                    AddSourceTab.valueOf(required(root, "selectedAddSourceTab").getAsString()),
                    preferredSkinVariant,
                    collapsedCollectionIds);
        } catch (StorageException exception) {
            throw exception;
        } catch (JsonParseException | IllegalArgumentException | IllegalStateException exception) {
            throw new StorageException(
                    StorageException.Code.INVALID_STATE,
                    "UI preferences are malformed",
                    exception);
        }
    }

    private static JsonElement required(JsonObject object, String member) {
        JsonElement value = object.get(member);
        if (value == null || value.isJsonNull() || !value.isJsonPrimitive()) {
            throw new JsonParseException("Missing primitive: " + member);
        }
        return value;
    }
}

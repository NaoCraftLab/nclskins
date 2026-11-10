package com.naocraftlab.skins.core.storage;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.naocraftlab.skins.core.model.AccountUiPreferences;
import com.naocraftlab.skins.core.model.CapePreviewMode;
import com.naocraftlab.skins.core.model.AddSourceTab;
import com.naocraftlab.skins.core.model.EditorTab;
import com.naocraftlab.skins.core.model.SkinVariant;
import com.naocraftlab.skins.core.provider.AppearanceProviders;

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
        root.addProperty("selectedEditorTab", preferences.selectedEditorTab().name());
        root.addProperty("selectedProvidersTab", preferences.selectedProvidersTab().name());
        root.addProperty("preferredCapeMode", preferences.preferredCapeMode().name());
        preferences.preferredSkinVariant()
                .ifPresent(variant -> root.addProperty("preferredSkinVariant", variant.name()));
        JsonArray collapsedCollectionIds = new JsonArray();
        preferences.collapsedCollectionIds().stream()
                .sorted()
                .forEach(collapsedCollectionIds::add);
        root.add("collapsedCollectionIds", collapsedCollectionIds);
        JsonArray capeCollections = new JsonArray();
        preferences.collapsedCapeCollections().stream().sorted().forEach(capeCollections::add);
        root.add("collapsedCapeCollections", capeCollections);
        JsonArray gallerySections = new JsonArray();
        preferences.collapsedGallerySections().stream().sorted().forEach(gallerySections::add);
        root.add("collapsedGallerySections", gallerySections);
        return (GSON.toJson(root) + System.lineSeparator()).getBytes(StandardCharsets.UTF_8);
    }

    AccountUiPreferences decode(byte[] bytes) throws StorageException {
        try {
            JsonObject root = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8))
                    .getAsJsonObject();
            JsonElement schema = required(root, "schemaVersion");
            if (!schema.getAsJsonPrimitive().isNumber()) {
                throw new JsonParseException("UI preferences schema must be a number");
            }
            int schemaVersion;
            try {
                schemaVersion = schema.getAsBigDecimal().intValueExact();
            } catch (ArithmeticException invalidSchema) {
                throw new JsonParseException("UI preferences schema must be an exact integer", invalidSchema);
            }
            if (schemaVersion != 1 && schemaVersion != AccountUiPreferences.CURRENT_SCHEMA_VERSION) {
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
            Set<String> capeCollections = new LinkedHashSet<>();
            if (root.has("collapsedCapeCollections")) root.getAsJsonArray("collapsedCapeCollections").forEach(value -> capeCollections.add(value.getAsString()));
            Set<String> gallerySections = new LinkedHashSet<>();
            if (schemaVersion >= 2 && root.has("collapsedGallerySections")) {
                JsonElement sections = root.get("collapsedGallerySections");
                if (!sections.isJsonArray()) {
                    throw new JsonParseException("Gallery sections must be an array");
                }
                for (JsonElement value : sections.getAsJsonArray()) {
                    if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
                        throw new JsonParseException("Gallery section id must be a string");
                    }
                    gallerySections.add(value.getAsString());
                }
            }
            return new AccountUiPreferences(
                    AccountUiPreferences.CURRENT_SCHEMA_VERSION,
                    UUID.fromString(required(root, "accountId").getAsString()),
                    AddSourceTab.valueOf(required(root, "selectedAddSourceTab").getAsString()),
                    optionalEditorTab(root),
                    preferredSkinVariant,
                    collapsedCollectionIds, capeCollections, optionalProvidersTab(root), gallerySections,
                    optionalCapeMode(root));
        } catch (StorageException exception) {
            throw exception;
        } catch (JsonParseException | IllegalArgumentException | IllegalStateException
                | NullPointerException | ClassCastException exception) {
            throw new StorageException(
                    StorageException.Code.INVALID_STATE,
                    "UI preferences are malformed",
                    exception);
        }
    }

    private static CapePreviewMode optionalCapeMode(JsonObject root) {
        JsonElement value = root.get("preferredCapeMode");
        if (value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) {
            try {
                return CapePreviewMode.valueOf(value.getAsString());
            } catch (IllegalArgumentException ignored) {
                return CapePreviewMode.CAPE;
            }
        }
        return CapePreviewMode.CAPE;
    }

    private static AppearanceProviders.Component optionalProvidersTab(JsonObject root) {
        JsonElement value = root.get("selectedProvidersTab");
        if (value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) {
            try {
                return AppearanceProviders.Component.valueOf(value.getAsString());
            } catch (IllegalArgumentException ignored) {
                return AppearanceProviders.Component.SKIN;
            }
        }
        return AppearanceProviders.Component.SKIN;
    }

    private static EditorTab optionalEditorTab(JsonObject root) {
        JsonElement value = root.get("selectedEditorTab");
        if (value == null || value.isJsonNull()) {
            return EditorTab.APPEARANCE;
        }
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            throw new JsonParseException("selectedEditorTab must be a string");
        }
        try {
            return EditorTab.valueOf(value.getAsString());
        } catch (IllegalArgumentException unknownTab) {
            return EditorTab.APPEARANCE;
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

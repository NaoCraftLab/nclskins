package com.naocraftlab.skins.core.storage;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.naocraftlab.skins.core.model.AccountState;
import com.naocraftlab.skins.core.model.AppearancePreset;
import com.naocraftlab.skins.client.OuterLayerPart;
import com.naocraftlab.skins.client.OuterLayerVisibility;
import com.naocraftlab.skins.core.model.CatalogOrigin;
import com.naocraftlab.skins.core.model.PersonalSkinEntry;
import com.naocraftlab.skins.core.model.PersonalSkinSource;
import com.naocraftlab.skins.core.model.SkinAsset;
import com.naocraftlab.skins.core.model.SkinReference;
import com.naocraftlab.skins.core.model.SkinSource;
import com.naocraftlab.skins.core.model.SkinVariant;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;

final class AccountStateJson {
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create();

    byte[] encode(AccountState state) {
        JsonObject root = new JsonObject();
        root.addProperty("schemaVersion", state.schemaVersion());
        root.addProperty("accountId", state.accountId().toString());
        root.addProperty("updatedAt", state.updatedAt().toString());

        JsonArray assets = new JsonArray();
        for (SkinAsset asset : state.skinAssets()) {
            JsonObject json = new JsonObject();
            json.addProperty("id", asset.id().toString());
            json.addProperty("name", asset.name());
            json.addProperty("sha256", asset.sha256());
            json.addProperty("variant", asset.variant().name());
            json.addProperty("source", asset.source().name());
            asset.catalogOrigin().ifPresent(origin -> {
                JsonObject catalogOrigin = new JsonObject();
                catalogOrigin.addProperty("sourceId", origin.sourceId());
                catalogOrigin.addProperty("collectionId", origin.collectionId());
                catalogOrigin.addProperty("skinId", origin.skinId());
                origin.description().ifPresent(value -> catalogOrigin.addProperty("description", value));
                origin.authors().ifPresent(value -> catalogOrigin.addProperty("authors", value));
                json.add("catalogOrigin", catalogOrigin);
            });
            json.addProperty("createdAt", asset.createdAt().toString());
            json.addProperty("updatedAt", asset.updatedAt().toString());
            assets.add(json);
        }
        root.add("skinAssets", assets);

        JsonArray personalSkins = new JsonArray();
        for (PersonalSkinEntry personalSkin : state.personalSkins()) {
            JsonObject json = new JsonObject();
            json.addProperty("sha256", personalSkin.sha256());
            json.addProperty("displayName", personalSkin.displayName());
            json.addProperty("source", personalSkin.source().name());
            json.addProperty("addedAt", personalSkin.addedAt().toString());
            json.addProperty("updatedAt", personalSkin.updatedAt().toString());
            json.addProperty("visible", personalSkin.visible());
            JsonObject variants = new JsonObject();
            for (SkinVariant variant : SkinVariant.values()) {
                personalSkin.optionalAssetId(variant)
                        .ifPresent(assetId -> variants.addProperty(variant.name(), assetId.toString()));
            }
            json.add("variants", variants);
            personalSkins.add(json);
        }
        root.add("personalSkins", personalSkins);

        JsonArray presets = new JsonArray();
        for (AppearancePreset preset : state.presets()) {
            JsonObject json = new JsonObject();
            json.addProperty("id", preset.id().toString());
            json.addProperty("name", preset.name());
            JsonObject skin = new JsonObject();
            skin.addProperty("kind", preset.skin().kind().name());
            preset.skin().optionalAssetId().ifPresent(assetId -> skin.addProperty("assetId", assetId.toString()));
            json.add("skin", skin);
            preset.optionalCapeId().ifPresent(capeId -> json.addProperty("capeId", capeId));
            JsonArray outerLayer = new JsonArray();
            for (OuterLayerPart part : OuterLayerPart.values()) {
                if (preset.outerLayerVisibility().visible(part)) {
                    outerLayer.add(part.name());
                }
            }
            json.add("outerLayer", outerLayer);
            json.addProperty("createdAt", preset.createdAt().toString());
            json.addProperty("updatedAt", preset.updatedAt().toString());
            presets.add(json);
        }
        root.add("presets", presets);
        return (GSON.toJson(root) + System.lineSeparator()).getBytes(StandardCharsets.UTF_8);
    }

    Decoded decode(byte[] bytes) throws StorageException {
        try {
            JsonElement parsed = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8));
            JsonObject root = parsed.getAsJsonObject();
            boolean migrated = false;
            int schemaVersion;
            if (root.has("schemaVersion")) {
                schemaVersion = requiredInt(root, "schemaVersion");
            } else if (root.has("version")) {
                schemaVersion = requiredInt(root, "version");
                migrated = true;
            } else {
                throw invalid("Account state has no schema version");
            }
            if (schemaVersion != 1 && schemaVersion != 2 && schemaVersion != 3
                    && schemaVersion != AccountState.CURRENT_SCHEMA_VERSION) {
                throw new StorageException(
                        StorageException.Code.UNSUPPORTED_SCHEMA,
                        "Unsupported account state schema: " + schemaVersion);
            }
            if (schemaVersion != AccountState.CURRENT_SCHEMA_VERSION) {
                migrated = true;
            }
            UUID accountId = UUID.fromString(requiredString(root, "accountId"));
            Instant updatedAt = instant(root, "updatedAt");

            String assetsMember = root.has("skinAssets") ? "skinAssets" : "skins";
            if ("skins".equals(assetsMember)) {
                migrated = true;
            }
            List<SkinAsset> assets = decodeAssets(requiredArray(root, assetsMember));
            List<PersonalSkinEntry> personalSkins = schemaVersion == 1
                    ? migratePersonalSkins(assets)
                    : decodePersonalSkins(requiredArray(root, "personalSkins"));
            List<AppearancePreset> presets = decodePresets(requiredArray(root, "presets"), schemaVersion);
            return new Decoded(
                    new AccountState(
                            AccountState.CURRENT_SCHEMA_VERSION,
                            accountId,
                            assets,
                            personalSkins,
                            presets,
                            updatedAt),
                    migrated);
        } catch (StorageException exception) {
            throw exception;
        } catch (JsonParseException | IllegalArgumentException | IllegalStateException | DateTimeParseException exception) {
            throw invalid("Account state is malformed", exception);
        }
    }

    private static List<SkinAsset> decodeAssets(JsonArray array) {
        List<SkinAsset> assets = new ArrayList<>(array.size());
        for (JsonElement element : array) {
            JsonObject json = element.getAsJsonObject();
            JsonObject catalogOrigin = optionalObject(json, "catalogOrigin");
            assets.add(new SkinAsset(
                    UUID.fromString(requiredString(json, "id")),
                    requiredString(json, "name"),
                    requiredString(json, "sha256"),
                    SkinVariant.valueOf(requiredString(json, "variant")),
                    SkinSource.valueOf(requiredString(json, "source")),
                    instant(json, "createdAt"),
                    instant(json, "updatedAt"),
                    catalogOrigin == null
                            ? Optional.empty()
                            : Optional.of(new CatalogOrigin(
                                    requiredString(catalogOrigin, "sourceId"),
                                    requiredString(catalogOrigin, "collectionId"),
                                    requiredString(catalogOrigin, "skinId"),
                                    Optional.ofNullable(optionalString(catalogOrigin, "description")),
                                    Optional.ofNullable(optionalString(catalogOrigin, "authors"))))));
        }
        return List.copyOf(assets);
    }

    private static List<PersonalSkinEntry> decodePersonalSkins(JsonArray array) {
        List<PersonalSkinEntry> personalSkins = new ArrayList<>(array.size());
        for (JsonElement element : array) {
            JsonObject json = element.getAsJsonObject();
            JsonObject variantsJson = requiredObject(json, "variants");
            EnumMap<SkinVariant, UUID> variants = new EnumMap<>(SkinVariant.class);
            for (Map.Entry<String, JsonElement> variant : variantsJson.entrySet()) {
                variants.put(
                        SkinVariant.valueOf(variant.getKey()),
                        UUID.fromString(variant.getValue().getAsString()));
            }
            personalSkins.add(new PersonalSkinEntry(
                    requiredString(json, "sha256"),
                    requiredString(json, "displayName"),
                    PersonalSkinSource.valueOf(requiredString(json, "source")),
                    instant(json, "addedAt"),
                    instant(json, "updatedAt"),
                    variants,
                    requiredBoolean(json, "visible")));
        }
        return List.copyOf(personalSkins);
    }


    private static List<PersonalSkinEntry> migratePersonalSkins(List<SkinAsset> assets) {
        Comparator<SkinAsset> deterministicAssetOrder = Comparator
                .comparing(SkinAsset::createdAt)
                .thenComparing(asset -> asset.id().toString());
        Map<String, List<SkinAsset>> assetsByHash = new TreeMap<>();
        for (SkinAsset asset : assets) {
            boolean imported = asset.source() == SkinSource.IMPORTED
                    || asset.source() == SkinSource.DUPLICATED;
            if (imported && asset.catalogOrigin().isEmpty()) {
                assetsByHash.computeIfAbsent(asset.sha256(), ignored -> new ArrayList<>())
                        .add(asset);
            }
        }

        List<PersonalSkinEntry> personalSkins = new ArrayList<>();
        for (Map.Entry<String, List<SkinAsset>> group : assetsByHash.entrySet()) {
            List<SkinAsset> candidates = group.getValue().stream()
                    .sorted(deterministicAssetOrder)
                    .toList();
            SkinAsset earliest = candidates.get(0);
            EnumMap<SkinVariant, UUID> variants = new EnumMap<>(SkinVariant.class);
            for (SkinVariant variant : SkinVariant.values()) {
                candidates.stream()
                        .filter(asset -> asset.variant() == variant)
                        .findFirst()
                        .ifPresent(asset -> variants.put(variant, asset.id()));
            }
            Instant updatedAt = candidates.stream()
                    .map(SkinAsset::updatedAt)
                    .max(Comparator.naturalOrder())
                    .orElse(earliest.updatedAt());
            personalSkins.add(new PersonalSkinEntry(
                    group.getKey(),
                    earliest.name(),
                    PersonalSkinSource.FILE,
                    earliest.createdAt(),
                    updatedAt,
                    variants,
                    true));
        }
        personalSkins.sort(Comparator.comparing(PersonalSkinEntry::addedAt)
                .thenComparing(PersonalSkinEntry::sha256));
        return List.copyOf(personalSkins);
    }

    private static List<AppearancePreset> decodePresets(JsonArray array, int schemaVersion) {
        List<AppearancePreset> presets = new ArrayList<>(array.size());
        for (JsonElement element : array) {
            JsonObject json = element.getAsJsonObject();
            JsonObject skinJson = requiredObject(json, "skin");
            SkinReference.Kind kind = SkinReference.Kind.valueOf(requiredString(skinJson, "kind"));
            SkinReference skin = kind == SkinReference.Kind.ACCOUNT_DEFAULT
                    ? SkinReference.accountDefault()
                    : SkinReference.asset(UUID.fromString(requiredString(skinJson, "assetId")));
            String capeId = optionalString(json, "capeId");
            OuterLayerVisibility outerLayer = schemaVersion < 3
                    ? OuterLayerVisibility.allVisible()
                    : decodeOuterLayer(requiredArray(json, "outerLayer"));
            presets.add(new AppearancePreset(
                    UUID.fromString(requiredString(json, "id")),
                    requiredString(json, "name"),
                    skin,
                    capeId,
                    outerLayer,
                    instant(json, "createdAt"),
                    instant(json, "updatedAt")));
        }
        return List.copyOf(presets);
    }

    private static OuterLayerVisibility decodeOuterLayer(JsonArray array) {
        java.util.EnumSet<OuterLayerPart> visible = java.util.EnumSet.noneOf(OuterLayerPart.class);
        for (JsonElement element : array) {
            visible.add(OuterLayerPart.valueOf(element.getAsString()));
        }
        return OuterLayerVisibility.of(visible);
    }

    private static JsonArray requiredArray(JsonObject object, String member) {
        JsonElement value = object.get(member);
        if (value == null || !value.isJsonArray()) {
            throw new JsonParseException("Missing array: " + member);
        }
        return value.getAsJsonArray();
    }

    private static JsonObject requiredObject(JsonObject object, String member) {
        JsonElement value = object.get(member);
        if (value == null || !value.isJsonObject()) {
            throw new JsonParseException("Missing object: " + member);
        }
        return value.getAsJsonObject();
    }

    private static JsonObject optionalObject(JsonObject object, String member) {
        JsonElement value = object.get(member);
        if (value == null || value.isJsonNull()) {
            return null;
        }
        if (!value.isJsonObject()) {
            throw new JsonParseException("Expected object: " + member);
        }
        return value.getAsJsonObject();
    }

    private static String requiredString(JsonObject object, String member) {
        JsonElement value = object.get(member);
        if (value == null || value.isJsonNull() || !value.isJsonPrimitive()) {
            throw new JsonParseException("Missing string: " + member);
        }
        return value.getAsString();
    }

    private static String optionalString(JsonObject object, String member) {
        JsonElement value = object.get(member);
        return value == null || value.isJsonNull() ? null : value.getAsString();
    }

    private static int requiredInt(JsonObject object, String member) {
        JsonElement value = object.get(member);
        if (value == null || !value.isJsonPrimitive()) {
            throw new JsonParseException("Missing number: " + member);
        }
        return value.getAsInt();
    }

    private static boolean requiredBoolean(JsonObject object, String member) {
        JsonElement value = object.get(member);
        if (value == null || !value.isJsonPrimitive()) {
            throw new JsonParseException("Missing boolean: " + member);
        }
        return value.getAsBoolean();
    }

    private static Instant instant(JsonObject object, String member) {
        return Instant.parse(requiredString(object, member));
    }

    private static StorageException invalid(String message) {
        return new StorageException(StorageException.Code.INVALID_STATE, message);
    }

    private static StorageException invalid(String message, Throwable cause) {
        return new StorageException(StorageException.Code.INVALID_STATE, message, cause);
    }

    record Decoded(AccountState state, boolean migrated) {}
}

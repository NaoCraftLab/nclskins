package com.naocraftlab.skins.core.storage;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.naocraftlab.skins.core.model.OwnedCapeEntry;
import com.naocraftlab.skins.core.model.OwnedCapeInventory;
import com.naocraftlab.skins.core.model.RemoteAssetState;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

final class OwnedCapeInventoryJson {
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create();

    byte[] encode(OwnedCapeInventory inventory) {
        JsonObject root = new JsonObject();
        root.addProperty("schemaVersion", inventory.schemaVersion());
        root.addProperty("accountId", inventory.accountId().toString());
        root.addProperty("verifiedAt", inventory.verifiedAt().toString());
        JsonArray capes = new JsonArray();
        for (OwnedCapeEntry cape : inventory.capes()) {
            JsonObject json = new JsonObject();
            json.addProperty("id", cape.id());
            cape.optionalAlias().ifPresent(alias -> json.addProperty("alias", alias));
            json.addProperty("state", cape.state().name());
            cape.optionalTextureCacheKey().ifPresent(key -> json.addProperty("textureCacheKey", key));
            capes.add(json);
        }
        root.add("capes", capes);
        return (GSON.toJson(root) + System.lineSeparator()).getBytes(StandardCharsets.UTF_8);
    }

    OwnedCapeInventory decode(byte[] bytes) throws StorageException {
        try {
            JsonObject root = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
            int schema = root.get("schemaVersion").getAsInt();
            if (schema != OwnedCapeInventory.CURRENT_SCHEMA_VERSION) {
                throw new StorageException(
                        StorageException.Code.UNSUPPORTED_SCHEMA,
                        "Unsupported owned cape schema: " + schema);
            }
            UUID accountId = UUID.fromString(root.get("accountId").getAsString());
            Instant verifiedAt = Instant.parse(root.get("verifiedAt").getAsString());
            List<OwnedCapeEntry> capes = new ArrayList<>();
            for (JsonElement element : root.getAsJsonArray("capes")) {
                JsonObject json = element.getAsJsonObject();
                capes.add(new OwnedCapeEntry(
                        json.get("id").getAsString(),
                        json.has("alias") ? json.get("alias").getAsString() : null,
                        RemoteAssetState.valueOf(json.get("state").getAsString()),
                        json.has("textureCacheKey") ? json.get("textureCacheKey").getAsString() : null));
            }
            return new OwnedCapeInventory(schema, accountId, capes, verifiedAt);
        } catch (StorageException exception) {
            throw exception;
        } catch (JsonParseException | IllegalArgumentException | IllegalStateException exception) {
            throw new StorageException(
                    StorageException.Code.INVALID_STATE,
                    "Owned cape inventory is malformed",
                    exception);
        }
    }
}

package com.naocraftlab.skins.core.storage;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

final class IdentityRegistryJson {
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create();

    byte[] encode(IdentityRegistryState state) {
        JsonObject root = new JsonObject();
        root.addProperty("schemaVersion", state.schemaVersion());
        root.addProperty("updatedAt", state.updatedAt().toString());
        root.add("observedNames", uuidStringMap(state.observedNames()));
        root.add("uuidAliases", uuidUuidMap(state.uuidAliases()));
        JsonObject names = new JsonObject();
        state.nameAliases().forEach((name, id) -> names.addProperty(name, id.toString()));
        root.add("nameAliases", names);
        JsonObject verified = new JsonObject();
        state.verifiedAccounts().forEach(id -> verified.addProperty(id.toString(), true));
        root.add("verifiedAccounts", verified);
        return (GSON.toJson(root) + System.lineSeparator()).getBytes(StandardCharsets.UTF_8);
    }

    IdentityRegistryState decode(byte[] bytes) throws StorageException {
        try {
            JsonObject root = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8))
                    .getAsJsonObject();
            int schema = primitive(root, "schemaVersion").getAsInt();
            if (schema != IdentityRegistryState.CURRENT_SCHEMA_VERSION) {
                throw new StorageException(
                        StorageException.Code.UNSUPPORTED_SCHEMA,
                        "Unsupported identity registry schema: " + schema);
            }
            Map<UUID, String> observed = new HashMap<>();
            object(root, "observedNames").entrySet().forEach(entry ->
                    observed.put(UUID.fromString(entry.getKey()), entry.getValue().getAsString()));
            Map<UUID, UUID> aliases = new HashMap<>();
            object(root, "uuidAliases").entrySet().forEach(entry -> aliases.put(
                    UUID.fromString(entry.getKey()), UUID.fromString(entry.getValue().getAsString())));
            Map<String, UUID> names = new HashMap<>();
            object(root, "nameAliases").entrySet().forEach(entry ->
                    names.put(entry.getKey(), UUID.fromString(entry.getValue().getAsString())));
            Set<UUID> verified = new HashSet<>();
            object(root, "verifiedAccounts").entrySet().stream()
                    .filter(entry -> entry.getValue().getAsBoolean())
                    .forEach(entry -> verified.add(UUID.fromString(entry.getKey())));
            return new IdentityRegistryState(
                    schema,
                    observed,
                    aliases,
                    names,
                    verified,
                    Instant.parse(primitive(root, "updatedAt").getAsString()));
        } catch (StorageException exception) {
            throw exception;
        } catch (JsonParseException | IllegalArgumentException | IllegalStateException exception) {
            throw new StorageException(
                    StorageException.Code.INVALID_STATE,
                    "Identity registry is malformed",
                    exception);
        }
    }

    private static JsonObject uuidStringMap(Map<UUID, String> values) {
        JsonObject result = new JsonObject();
        values.forEach((id, value) -> result.addProperty(id.toString(), value));
        return result;
    }

    private static JsonObject uuidUuidMap(Map<UUID, UUID> values) {
        JsonObject result = new JsonObject();
        values.forEach((id, value) -> result.addProperty(id.toString(), value.toString()));
        return result;
    }

    private static JsonObject object(JsonObject root, String member) {
        JsonElement value = root.get(member);
        if (value == null || !value.isJsonObject()) {
            throw new JsonParseException("Missing object: " + member);
        }
        return value.getAsJsonObject();
    }

    private static JsonElement primitive(JsonObject root, String member) {
        JsonElement value = root.get(member);
        if (value == null || !value.isJsonPrimitive()) {
            throw new JsonParseException("Missing primitive: " + member);
        }
        return value;
    }
}

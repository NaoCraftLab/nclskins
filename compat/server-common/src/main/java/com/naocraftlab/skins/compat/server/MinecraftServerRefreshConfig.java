package com.naocraftlab.skins.compat.server;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.naocraftlab.skins.server.runtime.ServerRefreshPolicy;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Objects;
import java.util.Set;


public final class MinecraftServerRefreshConfig {
    static final String FILE_NAME = "nclskins-server.json";

    private static final String DEFAULT_JSON = "{\n  \"trustedProxyForwarding\": false\n}\n";
    private static final Set<String> ROOT_FIELDS = Set.of(
            "trustedProxyForwarding", "advanced");
    private static final Set<String> ADVANCED_FIELDS = Set.of(
            "maxConcurrentLookups", "lookupRatePerSecond", "lookupBurst");

    private final boolean trustedProxyForwarding;
    private final Integer maxConcurrentLookups;
    private final Double lookupRatePerSecond;
    private final Integer lookupBurst;

    private MinecraftServerRefreshConfig(
            boolean trustedProxyForwarding,
            Integer maxConcurrentLookups,
            Double lookupRatePerSecond,
            Integer lookupBurst) {
        this.trustedProxyForwarding = trustedProxyForwarding;
        this.maxConcurrentLookups = maxConcurrentLookups;
        this.lookupRatePerSecond = lookupRatePerSecond;
        this.lookupBurst = lookupBurst;
    }

    static MinecraftServerRefreshConfig load(Path configDirectory) throws ConfigException {
        Objects.requireNonNull(configDirectory, "configDirectory");
        Path file = configDirectory.resolve(FILE_NAME);
        try {
            Files.createDirectories(configDirectory);
            try {
                Files.writeString(
                        file,
                        DEFAULT_JSON,
                        StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE_NEW,
                        StandardOpenOption.WRITE);
                return defaults();
            } catch (FileAlreadyExistsException alreadyExists) {
                return parse(Files.readString(file, StandardCharsets.UTF_8));
            }
        } catch (IOException failure) {
            throw new ConfigException("Unable to read server refresh configuration");
        }
    }

    ServerRefreshPolicy policy(int serverMaxPlayers) throws ConfigException {
        ServerRefreshPolicy defaults = ServerRefreshPolicy.defaults(
                trustedProxyForwarding, serverMaxPlayers);
        try {
            return new ServerRefreshPolicy(
                    trustedProxyForwarding,
                    defaults.maxPendingConnections(),
                    maxConcurrentLookups == null
                            ? defaults.maxConcurrentLookups()
                            : maxConcurrentLookups,
                    lookupRatePerSecond == null
                            ? defaults.lookupRatePerSecond()
                            : lookupRatePerSecond,
                    lookupBurst == null ? defaults.lookupBurst() : lookupBurst,
                    defaults.attemptOffsets(),
                    defaults.maxQueueAge(),
                    defaults.lookupCycleDeadline(),
                    defaults.independentCycleCooldown(),
                    defaults.batchWindow(),
                    defaults.maxBatchActors(),
                    defaults.maxReconciliationAttempts());
        } catch (IllegalArgumentException invalidValue) {
            throw new ConfigException("Invalid server refresh configuration");
        }
    }

    private static MinecraftServerRefreshConfig defaults() {
        return new MinecraftServerRefreshConfig(false, null, null, null);
    }

    private static MinecraftServerRefreshConfig parse(String document) throws ConfigException {
        try {
            JsonElement parsed = JsonParser.parseString(Objects.requireNonNull(document, "document"));
            if (!parsed.isJsonObject()) {
                throw invalid();
            }
            JsonObject root = parsed.getAsJsonObject();
            requireOnly(root, ROOT_FIELDS);
            boolean trustedProxy = requireBoolean(root, "trustedProxyForwarding", false);
            JsonObject advanced = optionalObject(root, "advanced");
            if (advanced == null) {
                return new MinecraftServerRefreshConfig(trustedProxy, null, null, null);
            }
            requireOnly(advanced, ADVANCED_FIELDS);
            return new MinecraftServerRefreshConfig(
                    trustedProxy,
                    optionalInteger(advanced, "maxConcurrentLookups"),
                    optionalDouble(advanced, "lookupRatePerSecond"),
                    optionalInteger(advanced, "lookupBurst"));
        } catch (ConfigException failure) {
            throw failure;
        } catch (RuntimeException malformed) {
            throw invalid();
        }
    }

    private static boolean requireBoolean(
            JsonObject object, String name, boolean defaultValue) throws ConfigException {
        JsonElement value = object.get(name);
        if (value == null) {
            return defaultValue;
        }
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean()) {
            throw invalid();
        }
        return value.getAsBoolean();
    }

    private static JsonObject optionalObject(JsonObject object, String name)
            throws ConfigException {
        JsonElement value = object.get(name);
        if (value == null) {
            return null;
        }
        if (!value.isJsonObject()) {
            throw invalid();
        }
        return value.getAsJsonObject();
    }

    private static Integer optionalInteger(JsonObject object, String name)
            throws ConfigException {
        JsonElement value = object.get(name);
        if (value == null) {
            return null;
        }
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
            throw invalid();
        }
        return value.getAsInt();
    }

    private static Double optionalDouble(JsonObject object, String name)
            throws ConfigException {
        JsonElement value = object.get(name);
        if (value == null) {
            return null;
        }
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
            throw invalid();
        }
        return value.getAsDouble();
    }

    private static void requireOnly(JsonObject object, Set<String> allowed)
            throws ConfigException {
        for (String field : object.keySet()) {
            if (!allowed.contains(field)) {
                throw invalid();
            }
        }
    }

    private static ConfigException invalid() {
        return new ConfigException("Invalid server refresh configuration");
    }

    public static final class ConfigException extends Exception {
        private static final long serialVersionUID = 1L;

        private ConfigException(String message) {
            super(message);
        }
    }
}

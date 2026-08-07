package com.naocraftlab.skins.core.config;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;


public final class ConfigurationDescriptions {
    public static final String CLIENT_TITLE_SCREEN =
            "nclskins.config.client.menu_preview.title_screen.description";
    public static final String CLIENT_PAUSE_MENU =
            "nclskins.config.client.menu_preview.pause_menu.description";
    public static final String CLIENT_DATA_DIRECTORY =
            "nclskins.config.client.storage.data_directory.description";
    public static final String SERVER_ENABLED =
            "nclskins.config.server.realtime_refresh.enabled.description";
    public static final String SERVER_TRUSTED_PROXY =
            "nclskins.config.server.realtime_refresh.trusted_proxy_forwarding.description";
    public static final String SERVER_MAX_CONCURRENT =
            "nclskins.config.server.realtime_refresh.max_concurrent_lookups.description";
    public static final String SERVER_LOOKUP_RATE =
            "nclskins.config.server.realtime_refresh.lookup_rate_per_second.description";
    public static final String SERVER_LOOKUP_BURST =
            "nclskins.config.server.realtime_refresh.lookup_burst.description";

    private static final String ENGLISH_RESOURCE = "assets/nclskins/lang/en_us.json";
    private static final java.util.Set<String> REQUIRED_KEYS = java.util.Set.of(
            CLIENT_TITLE_SCREEN,
            CLIENT_PAUSE_MENU,
            CLIENT_DATA_DIRECTORY,
            SERVER_ENABLED,
            SERVER_TRUSTED_PROXY,
            SERVER_MAX_CONCURRENT,
            SERVER_LOOKUP_RATE,
            SERVER_LOOKUP_BURST);

    private final Map<String, String> descriptions;

    public ConfigurationDescriptions(Map<String, String> descriptions) {
        Objects.requireNonNull(descriptions, "descriptions");
        Map<String, String> checked = new LinkedHashMap<>();
        for (String key : REQUIRED_KEYS) {
            String value = descriptions.get(key);
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException("Missing English configuration description " + key);
            }
            checked.put(key, value);
        }
        this.descriptions = Map.copyOf(checked);
    }

    public static ConfigurationDescriptions loadEnglish(ClassLoader loader) {
        ClassLoader checkedLoader = Objects.requireNonNull(loader, "loader");
        try (InputStream stream = checkedLoader.getResourceAsStream(ENGLISH_RESOURCE)) {
            if (stream == null) {
                throw new ConfigurationException(
                        "Missing bundled English localization " + ENGLISH_RESOURCE);
            }
            JsonElement parsed = JsonParser.parseReader(
                    new InputStreamReader(stream, StandardCharsets.UTF_8));
            if (!parsed.isJsonObject()) {
                throw new ConfigurationException(
                        "Bundled English localization is not a JSON object");
            }
            JsonObject root = parsed.getAsJsonObject();
            Map<String, String> descriptions = new LinkedHashMap<>();
            for (String key : REQUIRED_KEYS) {
                JsonElement value = root.get(key);
                if (value == null || !value.isJsonPrimitive()
                        || !value.getAsJsonPrimitive().isString()) {
                    throw new ConfigurationException(
                            "Bundled English localization lacks " + key);
                }
                descriptions.put(key, value.getAsString());
            }
            return new ConfigurationDescriptions(descriptions);
        } catch (IOException failure) {
            throw new ConfigurationException(
                    "Unable to read bundled English configuration descriptions", failure);
        }
    }

    public String get(String key) {
        String value = descriptions.get(Objects.requireNonNull(key, "key"));
        if (value == null) {
            throw new IllegalArgumentException("Unknown configuration description " + key);
        }
        return value;
    }
}

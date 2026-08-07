package com.naocraftlab.skins.core.config;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;


final class ConfigurationLocalizationTest {
    private static final List<String> VISUAL_KEYS = List.of(
            "nclskins.config.client.storage.data_directory.default",
            "nclskins.config.client.storage.data_directory.picker_title",
            "nclskins.config.server.realtime_refresh.enabled.name",
            "nclskins.config.server.realtime_refresh.trusted_proxy_forwarding.name",
            "nclskins.config.server.realtime_refresh.max_concurrent_lookups.name",
            "nclskins.config.server.realtime_refresh.lookup_rate_per_second.name",
            "nclskins.config.server.realtime_refresh.lookup_burst.name");

    @Test
    void bothLanguagesContainEveryVisualConfigurationKey() throws IOException {
        for (String locale : List.of("en_us", "ru_ru")) {
            JsonObject translations = translations(locale);
            for (String key : VISUAL_KEYS) {
                JsonElement value = translations.get(key);
                assertTrue(value != null && value.isJsonPrimitive()
                        && value.getAsJsonPrimitive().isString()
                        && !value.getAsString().isBlank(), locale + " lacks " + key);
            }
            assertFalse(translations.has("nclskins.config.missing_yacl.title"));
            assertFalse(translations.has("nclskins.config.missing_yacl.message"));
            assertFalse(translations.has("nclskins.config.missing_yacl.open"));
            assertFalse(translations.has("nclskins.config.server.unavailable"));
        }
    }

    private JsonObject translations(String locale) throws IOException {
        String resource = "assets/nclskins/lang/" + locale + ".json";
        try (var stream = getClass().getClassLoader().getResourceAsStream(resource)) {
            if (stream == null) {
                throw new IOException("Missing " + resource);
            }
            return JsonParser.parseReader(
                    new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonObject();
        }
    }
}

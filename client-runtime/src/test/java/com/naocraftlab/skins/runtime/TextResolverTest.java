package com.naocraftlab.skins.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.naocraftlab.skins.client.CatalogText;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TextResolverTest {
    @Test
    void vanillaCatalogResolverDistinguishesMissingAndKeyValuedTranslations() {
        Map<String, String> language = Map.of(
                "nclskins.event.name", "Localized Event",
                "nclskins.literal_key.name", "nclskins.literal_key.name");
        TextResolver resolver = TextResolver.withCatalogTranslations(
                UiMessage::key,
                (key, fallback) -> language.getOrDefault(key, fallback));

        assertEquals("Localized Event", resolver.resolve(CatalogText.collectionName("event")));
        assertEquals(
                "nclskins.literal_key.name",
                resolver.resolve(CatalogText.collectionName("literal_key")));
        assertEquals(
                "Missing Event", resolver.resolve(CatalogText.collectionName("missing_event")));
        assertEquals("literal", resolver.resolve(CatalogText.literal("literal")));
    }
}

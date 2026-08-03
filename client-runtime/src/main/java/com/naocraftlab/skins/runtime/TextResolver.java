package com.naocraftlab.skins.runtime;

import com.naocraftlab.skins.client.CatalogText;
import java.util.Objects;
import java.util.function.BiFunction;


@FunctionalInterface
public interface TextResolver {
    String resolve(UiMessage message);


    static TextResolver withCatalogTranslations(
            TextResolver messages, BiFunction<String, String, String> catalogTranslations) {
        Objects.requireNonNull(messages, "messages");
        Objects.requireNonNull(catalogTranslations, "catalogTranslations");
        return new TextResolver() {
            @Override
            public String resolve(UiMessage message) {
                return messages.resolve(message);
            }

            @Override
            public String resolve(CatalogText text) {
                Objects.requireNonNull(text, "text");
                return text.translationKey()
                        .map(key -> Objects.requireNonNull(
                                catalogTranslations.apply(key, text.fallback()),
                                "resolved catalog text"))
                        .orElse(text.fallback());
            }
        };
    }


    default String resolve(CatalogText text) {
        Objects.requireNonNull(text, "text");
        if (text.translationKey().isEmpty()) {
            return text.fallback();
        }
        String key = text.translationKey().orElseThrow();
        String resolved = Objects.requireNonNull(
                resolve(UiMessage.info(key)), "resolved catalog text");
        return key.equals(resolved) ? text.fallback() : resolved;
    }
}

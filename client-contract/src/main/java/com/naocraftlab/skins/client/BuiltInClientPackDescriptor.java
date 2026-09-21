package com.naocraftlab.skins.client;

import java.util.Objects;
import java.util.regex.Pattern;

public record BuiltInClientPackDescriptor(
        String namespace,
        String packPath,
        String nestedSource,
        String displayTranslationKey,
        String reloadListenerPath,
        Activation activation,
        Position position) {
    private static final Pattern IDENTIFIER_PART = Pattern.compile("[a-z0-9_.-]+");
    private static final Pattern RESOURCE_PATH =
            Pattern.compile("[a-z0-9_.-]+(?:/[a-z0-9_.-]+)*");

    public static final BuiltInClientPackDescriptor MOJANG_COLLECTIONS =
            new BuiltInClientPackDescriptor(
                    "nclskins",
                    "mojang_collections",
                    "resourcepacks/mojang_collections",
                    "pack.nclskins.mojang_collections.name",
                    "cape_catalog_reload",
                    Activation.DEFAULT_ENABLED,
                    Position.BOTTOM);

    public BuiltInClientPackDescriptor {
        namespace = requireMatch(namespace, IDENTIFIER_PART, "namespace");
        packPath = requireMatch(packPath, IDENTIFIER_PART, "packPath");
        nestedSource = requireMatch(nestedSource, RESOURCE_PATH, "nestedSource");
        displayTranslationKey = requireText(displayTranslationKey, "displayTranslationKey");
        reloadListenerPath = requireMatch(
                reloadListenerPath, IDENTIFIER_PART, "reloadListenerPath");
        Objects.requireNonNull(activation, "activation");
        Objects.requireNonNull(position, "position");
    }

    public String packId() {
        return namespace + ':' + packPath;
    }

    public String reloadListenerId() {
        return namespace + ':' + reloadListenerPath;
    }

    public enum Activation {
        DEFAULT_ENABLED
    }

    public enum Position {
        BOTTOM
    }

    private static String requireMatch(String value, Pattern pattern, String name) {
        String checked = requireText(value, name);
        if (!pattern.matcher(checked).matches()) {
            throw new IllegalArgumentException("Invalid " + name);
        }
        return checked;
    }

    private static String requireText(String value, String name) {
        String checked = Objects.requireNonNull(value, name);
        if (checked.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return checked;
    }
}

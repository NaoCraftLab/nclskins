package com.naocraftlab.skins.core.model;

import java.util.Locale;


public enum SkinVariant {
    CLASSIC,
    SLIM;

    public String apiValue() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static SkinVariant fromApiValue(String value) {
        if (value == null) {
            return CLASSIC;
        }
        return switch (value.toUpperCase(Locale.ROOT)) {
            case "SLIM", "ALEX" -> SLIM;
            default -> CLASSIC;
        };
    }
}

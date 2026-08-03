package com.naocraftlab.skins.core.model;

import java.util.Locale;

public enum RemoteAssetState {
    ACTIVE,
    INACTIVE;

    public static RemoteAssetState fromApiValue(String value) {
        return "ACTIVE".equals(value == null ? null : value.toUpperCase(Locale.ROOT)) ? ACTIVE : INACTIVE;
    }
}

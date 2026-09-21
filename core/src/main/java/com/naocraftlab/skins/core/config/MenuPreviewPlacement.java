package com.naocraftlab.skins.core.config;

public enum MenuPreviewPlacement {
    RIGHT("right"),
    LEFT("left"),
    OFF("off");

    private final String serializedValue;

    MenuPreviewPlacement(String serializedValue) {
        this.serializedValue = serializedValue;
    }

    public String serializedValue() {
        return serializedValue;
    }
}

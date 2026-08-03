package com.naocraftlab.skins.core.png;

public record PngInfo(int width, int height) {
    public boolean legacyLayout() {
        return width == 64 && height == 32;
    }
}

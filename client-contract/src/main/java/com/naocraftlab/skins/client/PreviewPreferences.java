package com.naocraftlab.skins.client;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;


public final class PreviewPreferences {
    private static final AtomicReference<PreviewRenderer.CapeMode> CAPE_MODE =
            new AtomicReference<>(PreviewRenderer.CapeMode.CAPE);

    private PreviewPreferences() {
    }

    public static PreviewRenderer.CapeMode capeMode() {
        return CAPE_MODE.get();
    }

    public static void setCapeMode(PreviewRenderer.CapeMode capeMode) {
        Objects.requireNonNull(capeMode, "capeMode");
        if (capeMode == PreviewRenderer.CapeMode.OFF) {
            throw new IllegalArgumentException("OFF is selection state, not a cape preview preference");
        }
        CAPE_MODE.set(capeMode);
    }
}

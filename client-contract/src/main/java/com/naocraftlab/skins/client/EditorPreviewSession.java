package com.naocraftlab.skins.client;

import java.util.Objects;

public final class EditorPreviewSession {
    private boolean liveDisabled;

    public Path path(
            PreviewRenderer.PreviewIntent intent,
            boolean hasWorldAndLocalPlayer) {
        Objects.requireNonNull(intent, "intent");
        return intent == PreviewRenderer.PreviewIntent.EDITOR_DRAFT
                && hasWorldAndLocalPlayer
                && !liveDisabled
                ? Path.LIVE
                : Path.BAKED;
    }

    public boolean disableLive(RuntimeException failure) {
        Objects.requireNonNull(failure, "failure");
        if (liveDisabled) {
            return false;
        }
        liveDisabled = true;
        return true;
    }

    public enum Path {
        BAKED,
        LIVE
    }
}

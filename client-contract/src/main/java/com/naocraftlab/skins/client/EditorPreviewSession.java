package com.naocraftlab.skins.client;

import java.util.Objects;

public final class EditorPreviewSession {
    public static final int SETTLING_TICKS = 6;
    private static final float SETTLING_HEIGHT = 0.35F;

    private boolean liveDisabled;
    private float settlingStartAge = Float.NaN;
    private float greatestAge;

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

    public SettlingMotion capeSettling(
            PreviewRenderer.PreviewIntent intent,
            boolean hasWorldAndLocalPlayer,
            PreviewRenderer.CapeMode capeMode,
            boolean capeRenderable,
            float ageTicks) {
        Objects.requireNonNull(intent, "intent");
        Objects.requireNonNull(capeMode, "capeMode");
        if (!Float.isFinite(ageTicks) || ageTicks < 0.0F) {
            throw new IllegalArgumentException("Preview age must be finite and non-negative");
        }
        if (path(intent, hasWorldAndLocalPlayer) != Path.LIVE
                || capeMode == PreviewRenderer.CapeMode.OFF
                || !capeRenderable) {
            return SettlingMotion.NONE;
        }
        greatestAge = Math.max(greatestAge, ageTicks);
        if (Float.isNaN(settlingStartAge)) {
            settlingStartAge = greatestAge;
        }
        float elapsed = Math.min(SETTLING_TICKS, greatestAge - settlingStartAge);
        if (elapsed >= SETTLING_TICKS) {
            return SettlingMotion.NONE;
        }
        float current = offset(elapsed);
        float previous = offset(Math.max(0.0F, elapsed - 1.0F));
        return new SettlingMotion(current, previous, true);
    }

    private static float offset(float elapsed) {
        float remaining = 1.0F - elapsed / SETTLING_TICKS;
        return SETTLING_HEIGHT * remaining * remaining;
    }

    public enum Path {
        BAKED,
        LIVE
    }

    public record SettlingMotion(float currentYOffset, float previousYOffset, boolean active) {
        public static final SettlingMotion NONE = new SettlingMotion(0.0F, 0.0F, false);

        public SettlingMotion {
            if (!Float.isFinite(currentYOffset) || !Float.isFinite(previousYOffset)) {
                throw new IllegalArgumentException("Settling offsets must be finite");
            }
        }
    }
}

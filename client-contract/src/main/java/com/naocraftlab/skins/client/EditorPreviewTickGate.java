package com.naocraftlab.skins.client;

public final class EditorPreviewTickGate {
    private int lastTick = Integer.MIN_VALUE;

    public boolean shouldTick(float ageTicks, boolean settlingActive) {
        if (!Float.isFinite(ageTicks) || ageTicks < 0.0F) {
            throw new IllegalArgumentException("Preview age must be finite and non-negative");
        }
        if (!settlingActive) {
            return false;
        }
        int tick = Math.max(0, (int) Math.floor(ageTicks));
        if (tick == lastTick) {
            return false;
        }
        lastTick = tick;
        return true;
    }
}

package com.naocraftlab.skins.client;

public final class CapeAssetOwner {
    private volatile boolean active = true;
    private volatile String currentFrame;

    public CapeAssetOwner() {}

    public boolean isActive() { return active; }

    public boolean ownsFrame(String location) {
        return active && location != null && location.equals(currentFrame);
    }

    public void installFrame(String location) {
        if (!active) return;
        currentFrame = java.util.Objects.requireNonNull(location, "location");
    }

    public void retire() {
        active = false;
        currentFrame = null;
    }
}

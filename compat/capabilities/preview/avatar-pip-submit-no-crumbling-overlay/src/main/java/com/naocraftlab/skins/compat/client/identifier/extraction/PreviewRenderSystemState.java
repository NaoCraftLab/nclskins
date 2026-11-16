package com.naocraftlab.skins.compat.client.identifier.extraction;

public final class PreviewRenderSystemState {
    private PreviewRenderSystemState() {}

    public static Runnable exceptionalOutputRestore() {
        return () -> {};
    }
}

package com.naocraftlab.skins.compat.client.identifier.extraction;

public final class PreviewRenderSystemState {
    private PreviewRenderSystemState() {}

    public static Runnable exceptionalOutputRestore() {
        var color = com.mojang.blaze3d.systems.RenderSystem.outputColorTextureOverride;
        var depth = com.mojang.blaze3d.systems.RenderSystem.outputDepthTextureOverride;
        return () -> {
            com.mojang.blaze3d.systems.RenderSystem.outputColorTextureOverride = color;
            com.mojang.blaze3d.systems.RenderSystem.outputDepthTextureOverride = depth;
        };
    }
}

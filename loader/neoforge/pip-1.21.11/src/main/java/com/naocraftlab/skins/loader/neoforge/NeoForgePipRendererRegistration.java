package com.naocraftlab.skins.loader.neoforge;

import com.naocraftlab.skins.compat.mc12111.Minecraft12111BakedPreviewRenderState;
import com.naocraftlab.skins.compat.mc12111.Minecraft12111BakedPreviewRenderer;
import com.naocraftlab.skins.compat.mc12111.Minecraft12111LivePreviewRenderState;
import com.naocraftlab.skins.compat.mc12111.Minecraft12111LivePreviewRenderer;
import net.neoforged.neoforge.client.event.RegisterPictureInPictureRenderersEvent;


final class NeoForgePipRendererRegistration {
    private NeoForgePipRendererRegistration() {}

    static void register(RegisterPictureInPictureRenderersEvent event) {
        event.register(
                Minecraft12111BakedPreviewRenderState.class,
                Minecraft12111BakedPreviewRenderer::new);
        event.register(
                Minecraft12111LivePreviewRenderState.class,
                Minecraft12111LivePreviewRenderer::new);
    }
}

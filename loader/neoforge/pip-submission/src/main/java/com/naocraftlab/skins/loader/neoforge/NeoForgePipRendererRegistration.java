package com.naocraftlab.skins.loader.neoforge;

import com.naocraftlab.skins.compat.client.identifier.submission.BakedPreviewRenderState;
import com.naocraftlab.skins.compat.client.identifier.submission.BakedPreviewRenderer;
import com.naocraftlab.skins.compat.client.identifier.submission.LivePreviewRenderState;
import com.naocraftlab.skins.compat.client.identifier.submission.LivePreviewRenderer;
import net.neoforged.neoforge.client.event.RegisterPictureInPictureRenderersEvent;


final class NeoForgePipRendererRegistration {
    private NeoForgePipRendererRegistration() {}

    static void register(RegisterPictureInPictureRenderersEvent event) {
        event.register(
                BakedPreviewRenderState.class,
                BakedPreviewRenderer::new);
        event.register(
                LivePreviewRenderState.class,
                LivePreviewRenderer::new);
    }
}

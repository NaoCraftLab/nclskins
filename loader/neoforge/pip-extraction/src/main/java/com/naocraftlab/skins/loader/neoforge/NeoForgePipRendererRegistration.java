package com.naocraftlab.skins.loader.neoforge;

import com.naocraftlab.skins.compat.client.identifier.extraction.NclBakedPlayerRenderState;
import com.naocraftlab.skins.compat.client.identifier.extraction.NclBakedPlayerRenderer;
import net.neoforged.neoforge.client.event.RegisterPictureInPictureRenderersEvent;


final class NeoForgePipRendererRegistration {
    private NeoForgePipRendererRegistration() {}

    static void register(RegisterPictureInPictureRenderersEvent event) {
        event.register(NclBakedPlayerRenderState.class, NclBakedPlayerRenderer::new);
    }
}

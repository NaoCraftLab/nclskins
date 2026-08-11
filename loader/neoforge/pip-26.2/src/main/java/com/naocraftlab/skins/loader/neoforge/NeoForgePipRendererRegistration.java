package com.naocraftlab.skins.loader.neoforge;

import com.naocraftlab.skins.compat.mc262.NclBakedPlayerRenderState;
import com.naocraftlab.skins.compat.mc262.NclBakedPlayerRenderer;
import net.neoforged.neoforge.client.event.RegisterPictureInPictureRenderersEvent;


final class NeoForgePipRendererRegistration {
    private NeoForgePipRendererRegistration() {}

    static void register(RegisterPictureInPictureRenderersEvent event) {
        event.register(NclBakedPlayerRenderState.class, NclBakedPlayerRenderer::new);
    }
}

package com.naocraftlab.skins.loader.fabric;

import com.naocraftlab.skins.compat.client.identifier.extraction.NclBakedPlayerRenderer;
import net.fabricmc.fabric.api.client.rendering.v1.PictureInPictureRendererRegistry;


final class FabricPipRendererRegistration {
    private FabricPipRendererRegistration() {}

    static void register() {
        PictureInPictureRendererRegistry.register(
                context -> new NclBakedPlayerRenderer(context.bufferSource()));
    }
}

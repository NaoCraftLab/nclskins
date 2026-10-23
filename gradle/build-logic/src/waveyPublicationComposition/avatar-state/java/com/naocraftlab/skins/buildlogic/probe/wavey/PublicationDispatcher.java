package com.naocraftlab.skins.buildlogic.probe.wavey;

import net.minecraft.client.renderer.entity.*;
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.client.renderer.entity.state.*;

public final class PublicationDispatcher extends EntityRenderDispatcher {
    private PublicationDispatcher() { super(null, null, null, null, null, null, null, null, null, null, null); }
    @Override public AvatarRenderer<?> getRenderer(AvatarRenderState state) {
        return ExtractionPublicationProbe.selected(state);
    }
}

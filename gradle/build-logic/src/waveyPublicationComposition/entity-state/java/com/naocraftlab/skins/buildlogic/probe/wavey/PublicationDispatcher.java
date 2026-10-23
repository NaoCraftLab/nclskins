package com.naocraftlab.skins.buildlogic.probe.wavey;

import net.minecraft.client.renderer.entity.*;
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.client.renderer.entity.state.*;

public final class PublicationDispatcher extends EntityRenderDispatcher {
    private PublicationDispatcher() { super(null, null, null, null, null, null, null, null, null, null, null); }
    @SuppressWarnings({"unchecked", "rawtypes"})
    @Override public <S extends EntityRenderState> EntityRenderer<?, ? super S> getRenderer(S state) {
        return (EntityRenderer) ExtractionPublicationProbe.selected(state);
    }
}

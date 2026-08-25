package com.naocraftlab.skins.compat.client.identifier.extraction.mixin;

import com.naocraftlab.skins.compat.client.identifier.extraction.NclSkinsWideDepthState;
import com.naocraftlab.skins.compat.client.identifier.extraction.AvatarPreviewContext;
import com.naocraftlab.skins.compat.client.identifier.extraction.PreviewRenderFailureSink;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(AvatarRenderState.class)
abstract class AvatarRenderStateMixin implements NclSkinsWideDepthState {
    @Unique
    private boolean nclskins$wideDepth;

    @Unique
    private PreviewRenderFailureSink nclskins$failureSink;

    @Unique
    private PreviewRenderFailureSink nclskins$layerFailureSink;

    @Unique
    private AvatarPreviewContext nclskins$previewContext;

    @Override
    public boolean nclskins$usesWideDepth() {
        return nclskins$wideDepth;
    }

    @Override
    public void nclskins$setWideDepth(boolean wideDepth) {
        nclskins$wideDepth = wideDepth;
    }

    @Override
    public PreviewRenderFailureSink nclskins$failureSink() {
        return nclskins$failureSink;
    }

    @Override
    public void nclskins$setFailureSink(PreviewRenderFailureSink failureSink) {
        nclskins$failureSink = failureSink;
    }

    @Override
    public PreviewRenderFailureSink nclskins$layerFailureSink() {
        return nclskins$layerFailureSink;
    }

    @Override
    public void nclskins$setLayerFailureSink(PreviewRenderFailureSink failureSink) {
        nclskins$layerFailureSink = failureSink;
    }

    @Override
    public AvatarPreviewContext nclskins$previewContext() {
        return nclskins$previewContext;
    }

    @Override
    public void nclskins$setPreviewContext(AvatarPreviewContext previewContext) {
        nclskins$previewContext = previewContext;
    }
}

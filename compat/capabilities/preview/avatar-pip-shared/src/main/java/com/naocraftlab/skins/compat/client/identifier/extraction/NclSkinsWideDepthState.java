package com.naocraftlab.skins.compat.client.identifier.extraction;

public interface NclSkinsWideDepthState {
    boolean nclskins$usesWideDepth();

    void nclskins$setWideDepth(boolean wideDepth);

    PreviewRenderFailureSink nclskins$failureSink();

    void nclskins$setFailureSink(PreviewRenderFailureSink failureSink);

    PreviewRenderFailureSink nclskins$layerFailureSink();

    void nclskins$setLayerFailureSink(PreviewRenderFailureSink failureSink);

    AvatarPreviewContext nclskins$previewContext();

    void nclskins$setPreviewContext(AvatarPreviewContext previewContext);
}

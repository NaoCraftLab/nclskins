package com.naocraftlab.skins.compat.client.identifier.extraction;

public interface NclSkinsWideDepthState extends NclSkinsDepthEnvelopeState {
    @Override
    float nclskins$depthExtent();

    void nclskins$setDepthExtent(float depthExtent);

    PreviewRenderFailureSink nclskins$failureSink();

    void nclskins$setFailureSink(PreviewRenderFailureSink failureSink);

    PreviewRenderFailureSink nclskins$layerFailureSink();

    void nclskins$setLayerFailureSink(PreviewRenderFailureSink failureSink);

    AvatarPreviewContext nclskins$previewContext();

    void nclskins$setPreviewContext(AvatarPreviewContext previewContext);
}

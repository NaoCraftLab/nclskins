package com.naocraftlab.skins.compat.mc262;

public interface NclSkinsWideDepthState {
    boolean nclskins$usesWideDepth();

    void nclskins$setWideDepth(boolean wideDepth);

    PreviewRenderFailureSink nclskins$failureSink();

    void nclskins$setFailureSink(PreviewRenderFailureSink failureSink);

    PreviewRenderFailureSink nclskins$layerFailureSink();

    void nclskins$setLayerFailureSink(PreviewRenderFailureSink failureSink);

    Minecraft262PreviewContext nclskins$previewContext();

    void nclskins$setPreviewContext(Minecraft262PreviewContext previewContext);
}

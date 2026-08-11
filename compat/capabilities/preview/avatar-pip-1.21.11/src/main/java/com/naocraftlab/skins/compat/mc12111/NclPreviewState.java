package com.naocraftlab.skins.compat.mc12111;


public interface NclPreviewState {
    boolean nclskins$isEditorPreview();

    void nclskins$setEditorPreview(boolean value);

    Minecraft12111PreviewContext nclskins$previewContext();

    void nclskins$setPreviewContext(Minecraft12111PreviewContext context);

    Minecraft12111PreviewFailureSink nclskins$failureSink();

    void nclskins$setFailureSink(Minecraft12111PreviewFailureSink sink);

    Minecraft12111PreviewFailureSink nclskins$layerFailureSink();

    void nclskins$setLayerFailureSink(Minecraft12111PreviewFailureSink sink);
}

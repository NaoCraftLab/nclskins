package com.naocraftlab.skins.compat.client.identifier.extraction;

@FunctionalInterface
public interface PreviewRenderFailureSink {
    void onFailure(RuntimeException failure);
}

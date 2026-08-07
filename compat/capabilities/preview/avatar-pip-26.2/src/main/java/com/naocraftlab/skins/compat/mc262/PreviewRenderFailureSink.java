package com.naocraftlab.skins.compat.mc262;

@FunctionalInterface
public interface PreviewRenderFailureSink {
    void onFailure(RuntimeException failure);
}

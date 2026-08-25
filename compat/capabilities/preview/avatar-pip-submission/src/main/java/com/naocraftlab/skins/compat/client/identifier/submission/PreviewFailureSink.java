package com.naocraftlab.skins.compat.client.identifier.submission;

@FunctionalInterface
public interface PreviewFailureSink {
    void onFailure(RuntimeException failure);
}

package com.naocraftlab.skins.runtime;

public interface ServerAppearanceReadiness extends AutoCloseable {
    StartResult start();
    void close();

    public enum StartResult {
        STARTED,
        UNAVAILABLE,
        CLOSED
    }

}

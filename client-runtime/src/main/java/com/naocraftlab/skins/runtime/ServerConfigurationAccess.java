package com.naocraftlab.skins.runtime;


public enum ServerConfigurationAccess {
    BEFORE_SERVER_START(true, false),
    INTEGRATED_SERVER_RUNNING(true, true),
    REMOTE_SERVER(false, false);

    private final boolean visible;
    private final boolean restartRequired;

    ServerConfigurationAccess(boolean visible, boolean restartRequired) {
        this.visible = visible;
        this.restartRequired = restartRequired;
    }

    public static ServerConfigurationAccess from(
            boolean connectionPresent,
            boolean integratedServerRunning) {
        if (integratedServerRunning) {
            return INTEGRATED_SERVER_RUNNING;
        }
        return connectionPresent ? REMOTE_SERVER : BEFORE_SERVER_START;
    }

    public boolean visible() {
        return visible;
    }

    public boolean restartRequired() {
        return restartRequired;
    }
}

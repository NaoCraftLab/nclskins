package com.naocraftlab.skins.server;

import java.util.Objects;


public final class AppearanceRefreshSignalProtocol {
    public static final String NAMESPACE = "nclskins";
    public static final String PATH = "appearance_refresh_v1";
    public static final String CHANNEL = NAMESPACE + ':' + PATH;

    private AppearanceRefreshSignalProtocol() {
    }

    public static byte[] payload() {
        return new byte[0];
    }

    public static boolean accepts(Direction direction, byte[] payload) {
        return Objects.requireNonNull(direction, "direction") == Direction.CLIENT_TO_SERVER
                && payload != null
                && payload.length == 0;
    }

    public static boolean dispatch(
            Direction direction,
            byte[] payload,
            Runnable acceptedRequest) {
        Objects.requireNonNull(acceptedRequest, "acceptedRequest");
        if (!accepts(direction, payload)) {
            return false;
        }
        acceptedRequest.run();
        return true;
    }

    public enum Direction {
        CLIENT_TO_SERVER,
        SERVER_TO_CLIENT
    }
}

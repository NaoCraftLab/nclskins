package com.naocraftlab.skins.client;


public final class GameSessionTokenUnavailableException extends IllegalStateException {
    private static final long serialVersionUID = 1L;

    public GameSessionTokenUnavailableException() {
        super("Minecraft access token is unavailable");
    }
}

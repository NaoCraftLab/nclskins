package com.naocraftlab.skins.client;


public final class GameSessionIdentityChangedException extends IllegalStateException {
    private static final long serialVersionUID = 1L;

    public GameSessionIdentityChangedException() {
        super("Minecraft session identity changed during an operation");
    }
}

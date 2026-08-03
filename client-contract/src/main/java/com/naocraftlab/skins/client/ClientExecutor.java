package com.naocraftlab.skins.client;


public interface ClientExecutor {
    boolean isClientThread();

    void execute(Runnable action);
}

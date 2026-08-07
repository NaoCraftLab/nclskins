package com.naocraftlab.skins.client;

import java.util.Objects;

public final class ExactLocalPlayerScope implements AutoCloseable {
    private final Object player;
    private final Thread ownerThread;
    private boolean open = true;

    public ExactLocalPlayerScope(Object player) {
        this.player = Objects.requireNonNull(player, "player");
        ownerThread = Thread.currentThread();
    }

    public boolean appliesTo(Object candidate) {
        return open && Thread.currentThread() == ownerThread && candidate == player;
    }

    @Override
    public void close() {
        if (Thread.currentThread() != ownerThread) {
            throw new IllegalStateException("Local player preview scope must close on its owner thread");
        }
        open = false;
    }
}

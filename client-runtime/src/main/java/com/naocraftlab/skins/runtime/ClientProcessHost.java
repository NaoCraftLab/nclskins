package com.naocraftlab.skins.runtime;

import java.util.Objects;

public final class ClientProcessHost<C> implements ClientProcess<C> {

    private final Process process;
    private final AppearanceReconnectTracker<C> reconnects = new AppearanceReconnectTracker<>();
    private boolean closed;

    public ClientProcessHost(Process process) {
        this.process = Objects.requireNonNull(process, "process");
    }

    public void warmSession() {
        ensureOpen();
        process.warmSession();
    }

    public void tick(
            C connection,
            boolean playerReady) {
        ensureOpen();
        process.tick();
        if (connection == null) {
            reconnects.disconnected();
            return;
        }
        if (!playerReady || !reconnects.begin(connection)) {
            return;
        }
        Objects.requireNonNull(process.afterReconnect(), "reconnect future");
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        try {
            process.close();
        } finally {
            reconnects.disconnected();
        }
    }

    public boolean closed() {
        return closed;
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("Client process host is closed");
        }
    }

}

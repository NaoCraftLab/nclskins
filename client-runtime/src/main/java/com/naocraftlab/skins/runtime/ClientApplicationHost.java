package com.naocraftlab.skins.runtime;

import java.util.Objects;


public final class ClientApplicationHost<C> implements AutoCloseable {
    private final ClientRuntime runtime;
    private final ClientProcessHost<C> process;

    public ClientApplicationHost(
            ClientCapabilitySet capabilities,
            TextResolver textResolver,
            Runnable closeNativeResources) {
        Objects.requireNonNull(capabilities, "capabilities");
        runtime = capabilities.createRuntime(textResolver);
        process = new ClientProcessHost<>(runtime, closeNativeResources);
    }

    public ClientRuntime runtime() {
        return runtime;
    }

    public void verifyStorageAccess() {
        ensureOpen();
        runtime.verifyStorageAccess();
    }

    public void warmSession() {
        if (!process.closed()) {
            process.warmSession();
        }
    }

    public void tick(C connection, boolean playerReady) {
        if (!process.closed()) {
            process.tick(connection, playerReady);
        }
    }

    public boolean closed() {
        return process.closed();
    }

    @Override
    public void close() {
        process.close();
    }

    private void ensureOpen() {
        if (process.closed()) {
            throw new IllegalStateException("Client application host is closed");
        }
    }
}

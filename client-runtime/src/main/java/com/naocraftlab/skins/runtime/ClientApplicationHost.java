package com.naocraftlab.skins.runtime;

import java.util.Objects;

public final class ClientApplicationHost<C> implements AutoCloseable {
    private final ClientRuntime runtime;
    private final ClientProcess<C> process;

    public ClientApplicationHost(RuntimeServices services, ClientRuntime runtime, Runnable closeNativeResources) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        Objects.requireNonNull(closeNativeResources, "closeNativeResources");
        process = services.process(new ClientProcess.Process() {
            @Override
            public void warmSession() {
                runtime.warmSession();
            }

            @Override
            public void tick() {
                runtime.tick();
            }

            @Override
            public java.util.concurrent.CompletableFuture<AppearanceRefresh.Result> afterReconnect() {
                return runtime.afterReconnect();
            }

            @Override
            public void close() {
                try {
                    runtime.close();
                } finally {
                    closeNativeResources.run();
                }
            }
        });
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

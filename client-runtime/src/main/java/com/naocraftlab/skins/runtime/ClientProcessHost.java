package com.naocraftlab.skins.runtime;

import com.naocraftlab.skins.runtime.AppearanceRefreshCoordinator.Result;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;


public final class ClientProcessHost<C> implements AutoCloseable {
    public interface Process extends AutoCloseable {
        void warmSession();

        void tick();

        CompletableFuture<Result> afterReconnect();

        @Override
        void close();
    }

    private final Process process;
    private final AppearanceReconnectTracker<C> reconnects = new AppearanceReconnectTracker<>();
    private boolean closed;

    public ClientProcessHost(Process process) {
        this.process = Objects.requireNonNull(process, "process");
    }

    public ClientProcessHost(ClientRuntime runtime, Runnable closeNativeResources) {
        Objects.requireNonNull(runtime, "runtime");
        Objects.requireNonNull(closeNativeResources, "closeNativeResources");
        this.process = new Process() {
            @Override
            public void warmSession() {
                runtime.warmSession();
            }

            @Override
            public void tick() {
                runtime.tick();
            }

            @Override
            public CompletableFuture<Result> afterReconnect() {
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
        };
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

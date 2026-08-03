package com.naocraftlab.skins.runtime;

import com.naocraftlab.skins.client.ServerAppearanceRefreshNotifier;
import java.time.Duration;
import java.util.Objects;
import java.util.OptionalLong;


public final class ServerAppearanceReadinessCoordinator implements AutoCloseable {
    private final ServerAppearanceRefreshNotifier notifier;
    private boolean closed;

    public ServerAppearanceReadinessCoordinator(ServerAppearanceRefreshNotifier notifier) {
        this.notifier = Objects.requireNonNull(notifier, "notifier");
    }

    public StartResult start() {
        if (closed) {
            return StartResult.CLOSED;
        }
        OptionalLong connection = availableConnection();
        if (connection.isEmpty()) {
            return StartResult.UNAVAILABLE;
        }
        try {
            notifier.requestOfficialProfileRefresh();
            return StartResult.STARTED;
        } catch (RuntimeException unavailableConnection) {
            return StartResult.UNAVAILABLE;
        }
    }

    private OptionalLong availableConnection() {
        try {
            return Objects.requireNonNull(
                    notifier.activeConnectionGeneration(), "connection generation");
        } catch (RuntimeException unavailable) {
            return OptionalLong.empty();
        }
    }

    @Override
    public void close() {
        closed = true;
    }

    public enum StartResult {
        STARTED,
        UNAVAILABLE,
        CLOSED
    }


    @FunctionalInterface
    interface DelayScheduler {
        Cancellable schedule(Duration delay, Runnable action);

        static DelayScheduler system() {
            return (delay, action) -> () -> {};
        }
    }

    @FunctionalInterface
    interface Cancellable {
        void cancel();
    }

}

package com.naocraftlab.skins.runtime;

import java.time.Duration;

public interface ServerAppearanceReadiness extends AutoCloseable {
    StartResult start();
    void close();

    public enum StartResult {
        STARTED,
        UNAVAILABLE,
        CLOSED
    }

    public interface DelayScheduler {
        Cancellable schedule(Duration delay, Runnable action);

        static DelayScheduler system() {
            return (delay, action) -> () -> {};
        }
    }

    interface Cancellable {
        void cancel();
    }
}

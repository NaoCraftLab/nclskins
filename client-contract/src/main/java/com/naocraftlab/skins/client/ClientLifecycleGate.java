package com.naocraftlab.skins.client;

import java.util.Objects;
import java.util.function.Consumer;

public final class ClientLifecycleGate<R> implements AutoCloseable {
    private final Consumer<? super R> reload;
    private final Consumer<? super R> closer;
    private State state = State.NEW;
    private R runtime;
    private boolean installationInProgress;
    private boolean reloadPending;

    public ClientLifecycleGate(Consumer<? super R> reload, Consumer<? super R> closer) {
        this.reload = Objects.requireNonNull(reload, "reload");
        this.closer = Objects.requireNonNull(closer, "closer");
    }

    public synchronized boolean install(R requested, Consumer<? super R> initializer) {
        Objects.requireNonNull(requested, "runtime");
        Objects.requireNonNull(initializer, "initializer");
        if (state == State.CLOSED) {
            return false;
        }
        if (state == State.INSTALLED || installationInProgress) {
            throw new IllegalStateException("Client lifecycle is already installed");
        }
        installationInProgress = true;
        try {
            initializer.accept(requested);
        } catch (RuntimeException | Error failure) {
            installationInProgress = false;
            throw failure;
        }
        installationInProgress = false;
        if (state == State.CLOSED) {
            closer.accept(requested);
            return false;
        }
        runtime = requested;
        state = State.INSTALLED;
        if (reloadPending) {
            reloadPending = false;
            reload.accept(requested);
        }
        return true;
    }

    public synchronized boolean dispatch(Consumer<? super R> callback) {
        Objects.requireNonNull(callback, "callback");
        if (state != State.INSTALLED) {
            return false;
        }
        callback.accept(runtime);
        return true;
    }

    public synchronized void resourcesReloaded() {
        if (state == State.NEW) {
            reloadPending = true;
        } else if (state == State.INSTALLED) {
            reload.accept(runtime);
        }
    }

    public synchronized State state() {
        return state;
    }

    @Override
    public synchronized void close() {
        if (state == State.CLOSED) {
            return;
        }
        R current = runtime;
        runtime = null;
        reloadPending = false;
        state = State.CLOSED;
        if (current != null) {
            closer.accept(current);
        }
    }

    public enum State {
        NEW,
        INSTALLED,
        CLOSED
    }
}

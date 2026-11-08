package com.naocraftlab.skins.client;

import java.util.Objects;
import java.util.function.Supplier;

public final class ScreenOwnedRenderTarget implements AutoCloseable {
    private Object owner;
    private Held resource;
    private boolean closed;

    private static final class Held {
        private final AutoCloseable value;
        private int consumers;
        private boolean retired;
        private boolean closed;
        private Held(AutoCloseable value) { this.value = value; }
    }

    public final class Consumption implements AutoCloseable {
        private Held held;
        private Consumption(Held held) { this.held = held; }
        @Override public void close() {
            synchronized (ScreenOwnedRenderTarget.this) {
                if (held == null) return;
                Held released = held;
                held = null;
                released.consumers--;
                finish(released);
            }
        }
    }

    public synchronized Consumption retain() {
        if (closed || resource == null) throw new IllegalStateException("Render target is unavailable");
        resource.consumers++;
        return new Consumption(resource);
    }

    public synchronized <T extends AutoCloseable> T acquire(
            Object expectedOwner, Supplier<T> factory, Class<T> type) {
        Objects.requireNonNull(expectedOwner, "expectedOwner");
        Objects.requireNonNull(factory, "factory");
        Objects.requireNonNull(type, "type");
        if (closed) throw new IllegalStateException("Render target is closed");
        if (owner != null && owner != expectedOwner) retire();
        if (owner == null) {
            T created = Objects.requireNonNull(factory.get(), "resource");
            if (closed || owner != null) {
                Held rejected = new Held(created);
                rejected.retired = true;
                finish(rejected);
                throw new IllegalStateException("Render target changed during creation");
            }
            owner = expectedOwner;
            resource = new Held(created);
        }
        return type.cast(resource.value);
    }

    public synchronized void release(Object expectedOwner) {
        if (owner == expectedOwner) retire();
    }

    public synchronized boolean owns(Object expectedOwner, AutoCloseable expectedResource) {
        return !closed && owner == expectedOwner && resource != null && resource.value == expectedResource;
    }

    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        retire();
    }

    private void retire() {
        Held previous = resource;
        resource = null;
        owner = null;
        if (previous == null) return;
        previous.retired = true;
        finish(previous);
    }

    private void finish(Held held) {
        if (!held.retired || held.consumers != 0 || held.closed) return;
        held.closed = true;
        try {
            held.value.close();
        } catch (Exception error) {
            throw new IllegalStateException("Cannot close render target", error);
        }
    }
}

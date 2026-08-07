package com.naocraftlab.skins.client;

import java.util.Objects;
import java.util.function.Supplier;

public final class ScreenOwnedRenderTarget implements AutoCloseable {
    private Object owner;
    private AutoCloseable resource;
    private boolean closed;

    public synchronized <T extends AutoCloseable> T acquire(
            Object expectedOwner, Supplier<T> factory, Class<T> type) {
        Objects.requireNonNull(expectedOwner, "expectedOwner");
        Objects.requireNonNull(factory, "factory");
        Objects.requireNonNull(type, "type");
        if (closed) {
            throw new IllegalStateException("Render target is closed");
        }
        if (owner == null) {
            owner = expectedOwner;
            resource = Objects.requireNonNull(factory.get(), "resource");
        } else if (owner != expectedOwner) {
            throw new IllegalStateException("Render target belongs to another dispatcher");
        }
        return type.cast(resource);
    }

    public synchronized void release(Object expectedOwner) {
        if (owner != expectedOwner) {
            return;
        }
        closeResource();
        owner = null;
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        closeResource();
        owner = null;
    }

    private void closeResource() {
        if (resource == null) {
            return;
        }
        try {
            resource.close();
        } catch (Exception error) {
            throw new IllegalStateException("Cannot close render target", error);
        } finally {
            resource = null;
        }
    }
}

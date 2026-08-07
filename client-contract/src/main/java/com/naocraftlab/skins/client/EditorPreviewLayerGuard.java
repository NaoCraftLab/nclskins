package com.naocraftlab.skins.client;

import java.util.Objects;
import java.util.function.Consumer;

public final class EditorPreviewLayerGuard implements AutoCloseable {
    private static final ThreadLocal<EditorPreviewLayerGuard> ACTIVE = new ThreadLocal<>();

    private final Consumer<RuntimeException> failureSink;
    private boolean closed;

    private EditorPreviewLayerGuard(Consumer<RuntimeException> failureSink) {
        this.failureSink = failureSink;
    }

    public static EditorPreviewLayerGuard open(Consumer<RuntimeException> failureSink) {
        Objects.requireNonNull(failureSink, "failureSink");
        if (ACTIVE.get() != null) {
            throw new IllegalStateException("Editor preview layer guard is already active");
        }
        EditorPreviewLayerGuard guard = new EditorPreviewLayerGuard(failureSink);
        ACTIVE.set(guard);
        return guard;
    }

    public static boolean handle(RuntimeException failure) {
        Objects.requireNonNull(failure, "failure");
        EditorPreviewLayerGuard guard = ACTIVE.get();
        if (guard == null) {
            return false;
        }
        guard.failureSink.accept(failure);
        return true;
    }

    public static boolean isActive() {
        return ACTIVE.get() != null;
    }

    @Override
    public void close() {
        if (!closed) {
            closed = true;
            if (ACTIVE.get() != this) {
                throw new IllegalStateException("Editor preview layer guard closed out of order");
            }
            ACTIVE.remove();
        }
    }
}

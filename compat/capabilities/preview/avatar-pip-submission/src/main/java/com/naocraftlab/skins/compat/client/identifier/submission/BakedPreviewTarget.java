package com.naocraftlab.skins.compat.client.identifier.submission;

import com.naocraftlab.skins.client.ScreenOwnedRenderTarget;
import java.util.function.Supplier;


public final class BakedPreviewTarget implements AutoCloseable {
    private final ScreenOwnedRenderTarget target = new ScreenOwnedRenderTarget();
    private static final java.util.WeakHashMap<net.minecraft.client.gui.render.state.GuiRenderState, ContextOwner> CONTEXTS = new java.util.WeakHashMap<>();

    public static ContextOwner context(net.minecraft.client.gui.render.state.GuiRenderState state) {
        ContextOwner owner = CONTEXTS.computeIfAbsent(java.util.Objects.requireNonNull(state), ignored -> new ContextOwner());
        if (owner.closed) throw new IllegalStateException("GUI context is closed");
        com.naocraftlab.skins.compat.client.identifier.preview.PreviewConsumerFrame.own(owner);
        return owner;
    }

    public static final class ContextOwner implements AutoCloseable {
        private final java.util.Set<BakedPreviewTarget> targets = java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>());
        private boolean closed;

        public <T extends AutoCloseable> T acquire(BakedPreviewTarget target, Supplier<T> factory, Class<T> type) {
            if (closed) throw new IllegalStateException("GUI context is closed");
            targets.add(target);
            T resource = target.acquire(this, factory, type);
            if (closed) {
                target.release(this);
                throw new IllegalStateException("GUI context closed during creation");
            }
            return resource;
        }

        @Override
        public void close() {
            if (closed) return;
            closed = true;
            var retired = new java.util.ArrayList<>(targets);
            targets.clear();
            Throwable failure = null;
            for (var target : retired) {
                try { target.release(this); }
                catch (RuntimeException | Error error) {
                    if (failure == null) failure = error;
                    else if (failure != error) failure.addSuppressed(error);
                }
            }
            if (failure instanceof RuntimeException error) throw error;
            if (failure instanceof Error error) throw error;
        }
    }

    public <T extends AutoCloseable> T acquire(
            Object expectedOwner, Supplier<T> factory, Class<T> type) {
        return target.acquire(expectedOwner, factory, type);
    }

    public void retain() {
        com.naocraftlab.skins.compat.client.identifier.preview.PreviewConsumerFrame.retain(target);
    }

    public void release(Object expectedOwner) {
        target.release(expectedOwner);
    }

    public boolean owns(Object expectedOwner, AutoCloseable expectedResource) {
        return target.owns(expectedOwner, expectedResource);
    }

    @Override
    public void close() {
        target.close();
    }
}

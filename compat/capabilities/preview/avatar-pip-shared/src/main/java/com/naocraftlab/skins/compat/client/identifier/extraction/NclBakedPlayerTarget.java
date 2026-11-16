package com.naocraftlab.skins.compat.client.identifier.extraction;

import com.naocraftlab.skins.client.ScreenOwnedRenderTarget;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.function.Supplier;
import net.minecraft.client.renderer.state.gui.GuiRenderState;

public final class NclBakedPlayerTarget implements AutoCloseable {
    private final ScreenOwnedRenderTarget target = new ScreenOwnedRenderTarget();
    private static final WeakHashMap<GuiRenderState, ContextOwner> CONTEXTS = new WeakHashMap<>();

    public static final class ContextLease implements AutoCloseable {
        private ContextOwner owner;
        private boolean closed;

        public ContextOwner join(GuiRenderState state) {
            if (closed) throw new IllegalStateException("Dispatcher is closed");
            ContextOwner next = CONTEXTS.computeIfAbsent(java.util.Objects.requireNonNull(state), ignored -> new ContextOwner());
            if (next.closed) throw new IllegalStateException("GUI context is closed");
            com.naocraftlab.skins.compat.client.identifier.preview.PreviewConsumerFrame.own(next);
            if (owner != next) {
                ContextOwner previous = owner;
                next.participants++;
                owner = next;
                if (previous != null) previous.leave();
            }
            if (closed || owner != next || next.closed) throw new IllegalStateException("GUI context changed during admission");
            return next;
        }

        @Override
        public void close() {
            if (closed) return;
            closed = true;
            ContextOwner previous = owner;
            owner = null;
            if (previous != null) previous.leave();
        }
    }

    public static final class ContextOwner implements AutoCloseable {
        private final Set<NclBakedPlayerTarget> targets = Collections.newSetFromMap(new WeakHashMap<>());
        private int participants;
        private boolean closed;

        public <T extends AutoCloseable> T acquire(NclBakedPlayerTarget target, Supplier<T> factory, Class<T> type) {
            if (closed) throw new IllegalStateException("GUI context is closed");
            targets.add(target);
            T resource = target.acquire(this, factory, type);
            if (closed) {
                target.release(this);
                throw new IllegalStateException("GUI context closed during creation");
            }
            return resource;
        }

        private void leave() { participants--; }

        @Override
        public void close() {
            if (closed) return;
            closed = true;
            var retired = new ArrayList<>(targets);
            targets.clear();
            Throwable failure = null;
            for (var target : retired) {
                try {
                    target.release(this);
                } catch (RuntimeException | Error error) {
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

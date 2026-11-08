package com.naocraftlab.skins.compat.client.identifier.preview;

import com.mojang.blaze3d.systems.RenderSystem;
import com.naocraftlab.skins.client.ScreenOwnedRenderTarget;
import java.util.ArrayList;
import java.util.List;

public final class PreviewConsumerFrame implements AutoCloseable {
    private static final ThreadLocal<PreviewConsumerFrame> FRAME = new ThreadLocal<>();
    private final PreviewConsumerFrame previous;
    private List<ScreenOwnedRenderTarget.Consumption> consumers = new ArrayList<>();
    private boolean closed;

    private PreviewConsumerFrame() { previous = FRAME.get(); FRAME.set(this); }
    public static PreviewConsumerFrame open() { return new PreviewConsumerFrame(); }

    public static void retain(ScreenOwnedRenderTarget target) {
        PreviewConsumerFrame frame = FRAME.get();
        if (frame == null) throw new IllegalStateException("Preview target has no GUI consumer frame");
        frame.consumers.add(target.retain());
    }

    public static void submitted() {
        PreviewConsumerFrame frame = FRAME.get();
        if (frame != null) frame.fence();
    }

    private void fence() {
        if (consumers.isEmpty()) return;
        List<ScreenOwnedRenderTarget.Consumption> submitted = consumers;
        consumers = new ArrayList<>();
        try { RenderSystem.queueFencedTask(() -> release(submitted)); }
        catch (RuntimeException | Error failure) {
            consumers.addAll(0, submitted);
            throw failure;
        }
    }

    private static void release(List<ScreenOwnedRenderTarget.Consumption> submitted) {
        Throwable failure = null;
        for (var consumer : submitted) {
            try { consumer.close(); }
            catch (RuntimeException | Error error) {
                if (failure == null) failure = error; else failure.addSuppressed(error);
            }
        }
        submitted.clear();
        if (failure instanceof RuntimeException error) throw error;
        if (failure instanceof Error error) throw error;
    }

    @Override public void close() {
        if (closed) return;
        try { fence(); closed = true; }
        finally {
            if (previous == null) FRAME.remove(); else FRAME.set(previous);
        }
    }
}

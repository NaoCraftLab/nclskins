package com.naocraftlab.skins.compat.client.identifier.preview;

import com.mojang.blaze3d.systems.RenderSystem;
import com.naocraftlab.skins.client.ScreenOwnedRenderTarget;
import java.util.ArrayList;
import java.util.List;

public final class PreviewConsumerFrame implements AutoCloseable {
    private static final ThreadLocal<PreviewConsumerFrame> FRAME = new ThreadLocal<>();
    private final PreviewConsumerFrame previous;
    private List<ScreenOwnedRenderTarget.Consumption> consumers = new ArrayList<>();
    private List<Prepared> completions = new ArrayList<>();
    private boolean closed;
    private boolean closing;

    private record Prepared(com.naocraftlab.skins.client.PreviewModelFrames.Completion completion,
            com.naocraftlab.skins.client.PreviewModelFrames.Body body) {
        void finish(boolean success) {
            if (completion.current()) completion.accept(success ? body
                    : new com.naocraftlab.skins.client.PreviewModelFrames.Body(
                            com.naocraftlab.skins.client.PreviewRenderer.BodyStatus.FAILED, () -> true));
        }
    }

    public static com.naocraftlab.skins.client.PreviewModelFrames.Completion afterConsumption(
            com.naocraftlab.skins.client.PreviewModelFrames.Completion completion) {
        if (completion == null || !completion.requiresConsumption()) return completion;
        return new com.naocraftlab.skins.client.PreviewModelFrames.Completion() {
            public boolean current() { return completion.current(); }
            public boolean currentInput(String location) { return completion.currentInput(location); }
            public boolean needsOwnInputPreparation() { return completion.needsOwnInputPreparation(); }
            public boolean reusesConfirmedOutput() { return completion.reusesConfirmedOutput(); }
            public void accept(com.naocraftlab.skins.client.PreviewModelFrames.Body body) {
                PreviewConsumerFrame frame = FRAME.get();
                if (frame == null) throw new IllegalStateException("Preview completion has no GUI consumer frame");
                frame.completions.add(new Prepared(completion, body));
            }
        };
    }

    private PreviewConsumerFrame() { previous = FRAME.get(); FRAME.set(this); }
    public static PreviewConsumerFrame open() { return new PreviewConsumerFrame(); }

    public static void retain(ScreenOwnedRenderTarget target) {
        PreviewConsumerFrame frame = FRAME.get();
        if (frame == null) throw new IllegalStateException("Preview target has no GUI consumer frame");
        frame.consumers.add(target.retain());
    }

    public static void submitted() { submitted(true); }
    public static void failed() { submitted(false); }

    private static void submitted(boolean success) {
        PreviewConsumerFrame frame = FRAME.get();
        if (frame != null) {
            var ready = frame.completions;
            frame.completions = new ArrayList<>();
            if (success) frame.fence(ready);
            else {
                try { finish(ready, false); }
                finally { frame.fence(); }
            }
        }
    }

    private static void finish(List<Prepared> ready, boolean success) {
        Throwable failure = null;
        for (var prepared : ready) {
            try { prepared.finish(success); }
            catch (RuntimeException | Error error) {
                if (failure == null) failure = error; else failure.addSuppressed(error);
            }
        }
        ready.clear();
        if (failure instanceof RuntimeException error) throw error;
        if (failure instanceof Error error) throw error;
    }

    private void fence() { fence(new ArrayList<>()); }

    private void fence(List<Prepared> ready) {
        if (consumers.isEmpty() && ready.isEmpty()) return;
        List<ScreenOwnedRenderTarget.Consumption> submitted = consumers;
        consumers = new ArrayList<>();
        try { RenderSystem.queueFencedTask(() -> {
            try { release(submitted); }
            catch (RuntimeException | Error failure) {
                try { finish(ready, false); }
                catch (RuntimeException | Error cleanup) { failure.addSuppressed(cleanup); }
                throw failure;
            }
            finish(ready, true);
        }); }
        catch (RuntimeException | Error failure) {
            consumers.addAll(0, submitted);
            try { finish(ready, false); }
            catch (RuntimeException | Error cleanup) { failure.addSuppressed(cleanup); }
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
        if (closed || closing) return;
        closing = true;
        var abandoned = completions;
        completions = new ArrayList<>();
        Throwable failure = null;
        try {
            try { finish(abandoned, false); }
            catch (RuntimeException | Error error) { failure = error; }
            try { fence(); closed = true; }
            catch (RuntimeException | Error error) {
                if (failure == null) failure = error; else failure.addSuppressed(error);
            }
        } finally {
            closing = false;
            if (previous == null) FRAME.remove(); else FRAME.set(previous);
        }
        if (failure instanceof RuntimeException error) throw error;
        if (failure instanceof Error error) throw error;
    }
}

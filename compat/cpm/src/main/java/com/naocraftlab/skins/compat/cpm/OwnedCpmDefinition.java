package com.naocraftlab.skins.compat.cpm;

import com.naocraftlab.skins.runtime.EffectiveSkinSource;
import com.tom.cpm.shared.MinecraftClientAccess;
import com.tom.cpm.shared.config.Player;
import com.tom.cpm.shared.definition.ModelDefinition;
import com.tom.cpm.shared.definition.ModelDefinitionLoader;

public final class OwnedCpmDefinition extends ModelDefinition {
    private final com.naocraftlab.skins.client.EditorPreviewClock animationClock = new com.naocraftlab.skins.client.EditorPreviewClock();
    private final EffectiveSkinSource source;
    private volatile boolean retired;
    private boolean scheduled;
    private volatile ModelLoadingState publishedState;
    private volatile Throwable failure;
    private volatile Thread cleanupThread;
    private boolean freed;
    private int users = 1;
    private AutoCloseable retirement;

    public OwnedCpmDefinition(ModelDefinitionLoader<?> loader, Player<?> player, EffectiveSkinSource source) {
        super(loader, player);
        this.source = source;
        retirement = source.onRetirement(this::cleanup);
    }

    @SuppressWarnings("deprecation")
    public boolean selfResources() {
        if (retired || failure != null || !source.active() || !source.preview()
                || CpmIntegration.source(getPlayerObj()) != source) return false;
        Player<?> current = MinecraftClientAccess.get().getCurrentClientPlayer();
        return current != null && source.subject().equals(current.getUUID());
    }

    private boolean selfResourceKey(com.tom.cpm.shared.config.PlayerSpecificConfigKey<?> key) {
        return (key == com.tom.cpm.shared.config.ConfigKeys.MAX_CUBE_COUNT
                || key == com.tom.cpm.shared.config.ConfigKeys.MAX_TEX_SHEET_SIZE
                || key == com.tom.cpm.shared.config.ConfigKeys.MAX_LINK_SIZE) && selfResources();
    }

    @Override public <V> void check(com.tom.cpm.shared.config.PlayerSpecificConfigKey<V> key,
            java.util.function.Predicate<V> check, com.tom.cpm.shared.definition.SafetyException.BlockReason reason)
            throws com.tom.cpm.shared.definition.SafetyException {
        if (!selfResourceKey(key)) super.check(key, check, reason);
    }

    @Override public <V> void check(com.tom.cpm.shared.config.PlayerSpecificConfigKey<V> key,
            V value, com.tom.cpm.shared.definition.SafetyException.BlockReason reason)
            throws com.tom.cpm.shared.definition.SafetyException {
        if (!selfResourceKey(key)) super.check(key, value, reason);
    }

    public void decoded() { release(); }

    public long animationTime(long worldTime) {
        return source.preview() && doRender() && CpmFrame.accepts(this)
                ? (long) (animationClock.ageTicks(0) * 50) : worldTime;
    }

    public EffectiveSkinSource source() { return source; }

    @Override public void startResolve() {
        synchronized (this) {
            if (scheduled || retired || failure != null || !source.active()) return;
            scheduled = true;
            users++;
            resolveState = ModelLoadingState.RESOLVING;
            publishedState = ModelLoadingState.RESOLVING;
        }
        try {
            ModelDefinitionLoader.THREAD_POOL.execute(() -> {
                try {
                    if (!retired && source.active()) super.resolveAll();
                } catch (java.io.IOException | RuntimeException failure) { setError(failure); }
                catch (Error failure) { setError(failure); throw failure; }
                finally {
                    if (failure == null) publishedState = super.getResolveState();
                    release();
                }
            });
        } catch (RuntimeException | Error rejected) {
            cleanup();
            release();
            throw rejected;
        }
    }

    @Override public ModelLoadingState getResolveState() {
        if (retired || !source.active()) return ModelLoadingState.CLEANED_UP;
        ModelLoadingState published = publishedState;
        return published == null ? super.getResolveState() : published;
    }
    @Override public Throwable getError() { return failure == null ? super.getError() : failure; }
    @Override public void setError(Throwable error) {
        if (error instanceof java.util.concurrent.ExecutionException
                || error instanceof com.google.common.util.concurrent.UncheckedExecutionException) error = error.getCause();
        synchronized (this) {
            failure = error;
            publishedState = error instanceof com.tom.cpm.shared.definition.SafetyException
                    ? ModelLoadingState.SAFETY_BLOCKED : ModelLoadingState.ERRORRED;
        }
        freeIfUnused();
    }
    @Override public boolean doRender() { return !retired && failure == null && source.active() && getResolveState() == ModelLoadingState.LOADED && super.doRender(); }

    public synchronized com.naocraftlab.skins.client.PreviewModelFrames.Lease acquire() {
        if (retired || failure != null || !source.active()) return null;
        users++;
        return new com.naocraftlab.skins.client.PreviewModelFrames.Lease() {
            private boolean closed;
            @Override public synchronized void close() {
                if (!closed) { closed = true; release(); }
            }
        };
    }

    @Override public void cleanup() {
        if (cleanupThread == Thread.currentThread()) { super.cleanup(); return; }
        synchronized (this) { retired = true; }
        freeIfUnused();
    }

    private void release() {
        synchronized (this) { users--; }
        freeIfUnused();
    }

    private void freeIfUnused() {
        synchronized (this) {
            if ((!retired && failure == null) || users != 0 || freed) return;
            freed = true;
        }
        try { if (retirement != null) retirement.close(); }
        catch (Exception failure) { throw new IllegalStateException("CPM retirement registration failed", failure); }
        MinecraftClientAccess.get().executeOnGameThread(() -> {
            cleanupThread = Thread.currentThread();
            try {
                Throwable error = failure;
                if (error == null) super.cleanup(); else super.setError(error);
            } finally { cleanupThread = null; }
        });
    }
}

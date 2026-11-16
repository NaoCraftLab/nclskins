package com.naocraftlab.skins.compat.cpm;

import com.naocraftlab.skins.client.PreviewModelFrames;
import com.naocraftlab.skins.client.PreviewRenderer;
import com.naocraftlab.skins.runtime.EffectiveSkinSource;
import com.tom.cpl.util.Image;
import com.tom.cpm.shared.config.ConfigKeys;
import com.tom.cpm.shared.config.Player;
import com.tom.cpm.shared.definition.ModelDefinition;
import com.tom.cpm.shared.definition.ModelDefinitionLoader;
import java.util.concurrent.CompletableFuture;

public final class CpmNeutralBody implements PreviewModelFrames.NeutralBody {
    private static final ThreadLocal<Scope> CURRENT = new ThreadLocal<>();
    private final Player<?> player;
    private final OwnedCpmDefinition live;
    private final EffectiveSkinSource source;
    private final CompletableFuture<OwnedCpmDefinition> decoded;
    private volatile boolean closed;

    public CpmNeutralBody(Player<?> player, OwnedCpmDefinition live) {
        this.player = player;
        this.live = live;
        source = live.source();
        int[] pixels = source.pixels();
        decoded = CompletableFuture.supplyAsync(() -> {
            if (closed || !source.active() || pixels == null) return null;
            var definition = live.getLoader().loadModel(new Image(pixels, 64), player);
            if (!(definition instanceof OwnedCpmDefinition owned) || owned == live || owned.source() != source) {
                if (definition != null) definition.cleanup();
                return null;
            }
            if (closed || !source.active()) { owned.cleanup(); return null; }
            owned.startResolve();
            return owned;
        }, ModelDefinitionLoader.THREAD_POOL);
        decoded.whenComplete((definition, failure) -> { if (closed && definition != null) definition.cleanup(); });
    }

    private boolean current() {
        return !closed && source.active() && CpmIntegration.source(player) == source
                && player.getModelDefinition0() == live && live.doRender()
                && Player.isEnableRendering() && ConfigKeys.ENABLE_MODEL_LOADING.getValueFor(player);
    }

    @Override public PreviewModelFrames.Body body() {
        var status = PreviewRenderer.BodyStatus.PENDING;
        if (!current()) status = PreviewRenderer.BodyStatus.BLOCKED;
        else if (decoded.isCompletedExceptionally()) status = PreviewRenderer.BodyStatus.FAILED;
        else if (decoded.isDone()) {
            var definition = decoded.getNow(null);
            status = definition == null ? PreviewRenderer.BodyStatus.FAILED : switch (definition.getResolveState()) {
                case NEW, RESOLVING -> PreviewRenderer.BodyStatus.PENDING;
                case LOADED -> definition.doRender() ? PreviewRenderer.BodyStatus.READY : PreviewRenderer.BodyStatus.BLOCKED;
                case SAFETY_BLOCKED -> PreviewRenderer.BodyStatus.BLOCKED;
                default -> PreviewRenderer.BodyStatus.FAILED;
            };
        }
        return new PreviewModelFrames.Body(status, this::current);
    }

    @Override public PreviewModelFrames.Lease open(boolean reference) {
        if (body().status() != PreviewRenderer.BodyStatus.READY) throw new IllegalStateException("Neutral CPM body is not ready");
        var definition = decoded.getNow(null);
        var lease = definition.acquire();
        if (lease == null) throw new IllegalStateException("Neutral CPM body is retired");
        var previous = CURRENT.get();
        var scope = new Scope(this, definition, reference, Thread.currentThread());
        CURRENT.set(scope);
        return new PreviewModelFrames.Lease() {
            private boolean released;
            @Override public void close() {
                if (released) return;
                if (CURRENT.get() != scope || Thread.currentThread() != scope.thread())
                    throw new IllegalStateException("Neutral CPM scope closed out of order");
                released = true;
                try { if (previous == null) CURRENT.remove(); else CURRENT.set(previous); }
                finally { lease.close(); }
            }
        };
    }

    public static ModelDefinition select(Player<?> player, ModelDefinition definition) {
        var scope = CURRENT.get();
        if (scope == null || scope.owner().player != player || scope.owner().live != definition
                || !scope.owner().current()) return definition;
        return scope.reference() ? null : scope.definition();
    }

    public static boolean neutral(Player<?> player, ModelDefinition definition) {
        var scope = CURRENT.get();
        return scope != null && !scope.reference() && scope.thread() == Thread.currentThread()
                && scope.owner().player == player && scope.definition() == definition && scope.owner().current();
    }

    @Override public void close() {
        if (closed) return;
        closed = true;
        if (decoded.isDone() && !decoded.isCompletedExceptionally()) {
            var definition = decoded.getNow(null);
            if (definition != null) definition.cleanup();
        }
    }

    private record Scope(CpmNeutralBody owner, OwnedCpmDefinition definition, boolean reference, Thread thread) {}
}

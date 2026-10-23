package com.naocraftlab.skins.compat.cpm;

import com.naocraftlab.skins.client.PreviewModelFrames;
import java.util.ArrayList;
import java.util.List;

public final class CpmFrame implements PreviewModelFrames.Lease {
    private static final ThreadLocal<CpmFrame> CURRENT = new ThreadLocal<>();
    private final List<Runnable> cleanup = new ArrayList<>();
    private boolean closed;
    private com.naocraftlab.skins.runtime.EffectiveSkinSource source;
    private PreviewModelFrames.Body body;
    private final com.naocraftlab.skins.client.PreviewRenderer.PreviewAppearance appearance;

    private CpmFrame(com.naocraftlab.skins.client.PreviewRenderer.PreviewAppearance appearance) { this.appearance = appearance; }

    public static CpmFrame open(com.naocraftlab.skins.client.PreviewRenderer.PreviewAppearance appearance) {
        if (CURRENT.get() != null) throw new IllegalStateException("CPM preview frame is already active");
        CpmFrame frame = new CpmFrame(appearance);
        CURRENT.set(frame);
        return frame;
    }

    public static boolean accepts(OwnedCpmDefinition definition) {
        CpmFrame frame = CURRENT.get();
        if (!definition.source().preview()) return frame == null;
        return frame != null && frame.acceptSource(definition.source());
    }

    public static boolean accessories(com.naocraftlab.skins.runtime.EffectiveSkinSource source) {
        CpmFrame frame = CURRENT.get();
        return frame != null && frame.acceptSource(source)
                && frame.appearance.nativeAccessories()
                && frame.appearance.capeMode() != com.naocraftlab.skins.client.PreviewRenderer.CapeMode.OFF;
    }

    private boolean acceptSource(com.naocraftlab.skins.runtime.EffectiveSkinSource candidate) {
        if (!candidate.active() || !candidate.preview()
                || !candidate.location().equals(appearance.skin().location())
                || candidate.model() != appearance.model()) return false;
        if (source == null) source = candidate;
        return source == candidate;
    }

    public static <D, S, P, M> void bound(
            com.tom.cpm.shared.model.render.ModelRenderManager<D, S, P, M> manager,
            M model, String arg, OwnedCpmDefinition definition,
            com.tom.cpm.shared.config.Player<?> player, Runnable unbind) {
        CpmFrame frame = CURRENT.get();
        if (frame == null || !definition.source().preview()) return;
        if (frame.cleanup.size() >= 256) {
            unbind.run();
            throw new IllegalStateException("CPM preview frame exceeds its binding budget");
        }
        frame.cleanup.add(unbind);
        if (player != null && frame.acceptSource(definition.source())
                && manager.getHolderSafe(model, arg, holder -> holder.model == model && holder.swappedIn
                        && holder.def == definition && holder.playerObj == player
                        && holder.redirectRenderers.stream().anyMatch(renderer ->
                                renderer.getPart() == com.tom.cpm.shared.model.PlayerModelParts.BODY), false, false)) {
            var source = definition.source();
            frame.body = new PreviewModelFrames.Body(com.naocraftlab.skins.client.PreviewRenderer.BodyStatus.READY,
                    () -> source.active() && CpmIntegration.source(player) == source
                            && player.getModelDefinition0() == definition && definition.doRender()
                            && com.tom.cpm.shared.config.Player.isEnableRendering()
                            && com.tom.cpm.shared.config.ConfigKeys.ENABLE_MODEL_LOADING.getValueFor(player));
        }
    }

    @Override public PreviewModelFrames.Body body() {
        return !closed && body != null && body.current().getAsBoolean() ? body : PreviewModelFrames.Lease.super.body();
    }

    @Override public void close() {
        if (closed) return;
        if (CURRENT.get() != this) throw new IllegalStateException("CPM preview frame closed out of order");
        closed = true;
        try {
            Throwable failure = null;
            for (int i = cleanup.size() - 1; i >= 0; i--) {
                try { cleanup.get(i).run(); }
                catch (RuntimeException | Error problem) {
                    if (failure == null) failure = problem; else failure.addSuppressed(problem);
                }
            }
            if (failure instanceof RuntimeException problem) throw problem;
            if (failure instanceof Error problem) throw problem;
        } finally {
            cleanup.clear();
            CURRENT.remove();
        }
    }
}

package com.naocraftlab.skins.client;

import java.util.Objects;

public final class ExactLocalPlayerScope implements AutoCloseable {
    private static final ThreadLocal<ExactLocalPlayerScope> ACTIVE_PREVIEW = new ThreadLocal<>();
    private final Object player;
    private final Thread ownerThread;
    private final PreviewRenderer.PreviewAppearance appearance;
    private final PreviewModelFrames.Lease modelFrame;
    private boolean open = true;

    public ExactLocalPlayerScope(Object player) {
        this(player, null);
    }

    public ExactLocalPlayerScope(Object player, PreviewRenderer.PreviewAppearance appearance) {
        this.player = Objects.requireNonNull(player, "player");
        ownerThread = Thread.currentThread();
        this.appearance = appearance;
        if (appearance != null) {
            if (ACTIVE_PREVIEW.get() != null) {
                throw new IllegalStateException("Local player preview scope is already active");
            }
            modelFrame = PreviewModelFrames.open(appearance);
            ACTIVE_PREVIEW.set(this);
        } else {
            modelFrame = null;
        }
    }

    public PreviewModelFrames.Body body() {
        return open && Thread.currentThread() == ownerThread && modelFrame != null ? modelFrame.body()
                : new PreviewModelFrames.Body(PreviewRenderer.BodyStatus.ORDINARY, () -> true);
    }

    public boolean appliesTo(Object candidate) {
        return open && Thread.currentThread() == ownerThread && candidate == player;
    }

    public static Boolean capeGlint(Object player, String installedCape) {
        ExactLocalPlayerScope active = ACTIVE_PREVIEW.get();
        if (active == null || !active.appliesTo(player)) return null;
        return active.appearance.capeMode() == PreviewRenderer.CapeMode.CAPE
                && active.appearance.capeGlint()
                && active.appearance.cape().isPresent()
                && active.appearance.cape().orElseThrow().location().equals(installedCape);
    }

    public static TextureUseValidity capeValidity(Object player, String installedCape) {
        ExactLocalPlayerScope active = ACTIVE_PREVIEW.get();
        if (active == null || !active.appliesTo(player)) return null;
        return Boolean.TRUE.equals(capeGlint(player, installedCape))
                ? active.appearance.capeValidity() : TextureUseValidity.INVALID;
    }

    @Override
    public void close() {
        if (Thread.currentThread() != ownerThread) {
            throw new IllegalStateException("Local player preview scope must close on its owner thread");
        }
        open = false;
        if (appearance != null) {
            if (ACTIVE_PREVIEW.get() != this) {
                throw new IllegalStateException("Local player preview scope closed out of order");
            }
            ACTIVE_PREVIEW.remove();
            modelFrame.close();
        }
    }
}

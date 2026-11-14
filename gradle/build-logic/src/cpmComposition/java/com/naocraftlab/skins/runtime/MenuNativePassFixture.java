package com.naocraftlab.skins.runtime;

import com.naocraftlab.skins.client.*;
import java.lang.reflect.Proxy;
import java.util.*;

public final class MenuNativePassFixture implements AutoCloseable {
    public final ClientRuntime runtime;
    private CurrentPlayerAppearanceSource.PlayerAppearance current;
    private CapeProjection.Registration animation;
    private com.naocraftlab.skins.core.png.CapeTimeline timeline;
    private List<TextureRegistry.TextureHandle> frames;
    private com.naocraftlab.skins.client.CapeAssetOwner animationOwner =
            new com.naocraftlab.skins.client.CapeAssetOwner();
    private long elapsedMillis;
    private int installedFrame = -1;

    public MenuNativePassFixture(PreviewRenderer.PreviewAppearance appearance) {
        appearance(appearance);
        var operations = (ClientOperations) Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{ClientOperations.class},(p,m,a) -> {
            if (m.getName().equals("sessionIdentity")) return new GameSessionTokenSource.SessionIdentity(new UUID(0,901),"MenuFixture");
            if (Set.of("close", "onCapeObservation", "closeOptiFineCapes").contains(m.getName())) return null;
            throw new AssertionError("Unadmitted menu operation: " + m.getName());
        });
        ClientExecutor executor = new ClientExecutor() {
            public boolean isClientThread() { return true; }
            public void execute(Runnable action) { action.run(); }
        };
        runtime = new ClientRuntime(new DefaultRuntimeServices(), operations, executor,
                () -> java.util.concurrent.CompletableFuture.completedFuture(Optional.empty()),
                action -> { throw new AssertionError("Menu must not dispatch workers"); }, UiMessage::key,
                Optional.of((CurrentPlayerAppearanceSource) () -> current), Optional.empty(),
                Optional.of(new OuterLayerVisibilityController() {
                    public OuterLayerVisibility current() { return OuterLayerVisibility.allVisible(); }
                    public void applyDurable(OuterLayerVisibility value) { throw new AssertionError("Menu durable write"); }
                }), Optional.empty(), com.naocraftlab.skins.diagnostics.DiagnosticSinks.discarding());
    }

    public void appearance(PreviewRenderer.PreviewAppearance appearance) {
        current = appearance == null ? null : new CurrentPlayerAppearanceSource.PlayerAppearance(appearance.skin(),appearance.model(),appearance.cape());
    }
    public void animate(com.naocraftlab.skins.core.png.CapeTimeline timeline, List<TextureRegistry.TextureHandle> frames) {
        if (timeline.frameCount() != frames.size() || animation != null) throw new IllegalArgumentException();
        this.timeline = timeline;
        this.frames = List.copyOf(frames);
        var events = (CapeProjection.Events) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{CapeProjection.Events.class}, (p,m,a) -> {
                    if (m.getName().equals("capeFrameDemand")) publishFrame();
                    if (m.getName().equals("hasSkinTexture")) return true;
                    if (m.getName().equals("captureCapeTexture")) return TextureUseValidity.INVALID;
                    return null;
                });
        animation = CapeProjection.installEvents(events);
        publishFrame();
    }
    public void animationTime(long elapsedMillis) {
        if (elapsedMillis < 0 || animation == null) throw new IllegalArgumentException();
        this.elapsedMillis = elapsedMillis;
        publishFrame();
    }
    public void replaceCapeAsset(TextureRegistry.TextureHandle handle) {
        if (animation == null) throw new IllegalStateException("Menu animation is not installed");
        var replacement = new ArrayList<>(frames);
        replacement.set(installedFrame, Objects.requireNonNull(handle));
        frames = List.copyOf(replacement);
        animationOwner.retire();
        animationOwner = new com.naocraftlab.skins.client.CapeAssetOwner();
        installedFrame = -1;
        publishFrame();
    }
    private void publishFrame() {
        int frame = timeline.frameAt(elapsedMillis);
        if (installedFrame == frame) return;
        installedFrame = frame;
        animationOwner.installFrame(frames.get(frame).location());
        animation.publish(new CapeProjection.Snapshot(
                List.of(com.naocraftlab.skins.core.provider.BuiltinProvider.OFFLINE,
                        com.naocraftlab.skins.core.provider.BuiltinProvider.MINECRAFT),
                Map.of(), Map.of(), Map.of(), Map.of(),
                new CapeProjection.Identity(new UUID(0,901), "MenuFixture"),
                new CapeProjection.Candidate(frames.get(frame).location(), null, false,
                        false, false, false, null, null, animationOwner), null));
        var selected = CapeProjection.resolveSelf(new UUID(0,901), "MenuFixture").orElseThrow();
        if (selected.capeAssetOwner() != animationOwner || !animationOwner.ownsFrame(selected.capeLocation()))
            throw new AssertionError("Menu fixture projection must preserve its current animated asset owner");
    }
    @Override public void close() {
        animationOwner.retire();
        if (animation != null) animation.close();
        runtime.close();
    }
}

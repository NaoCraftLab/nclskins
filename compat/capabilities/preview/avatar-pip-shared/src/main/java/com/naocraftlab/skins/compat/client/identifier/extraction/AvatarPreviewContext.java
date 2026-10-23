package com.naocraftlab.skins.compat.client.identifier.extraction;

import com.naocraftlab.skins.client.PreviewRenderer;
import com.naocraftlab.skins.client.PreviewModelFrames;
import com.naocraftlab.skins.client.EditorPreviewLayerGuard;
import java.util.Objects;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.world.entity.player.PlayerSkin;
import net.minecraft.world.item.ItemStack;

public final class AvatarPreviewContext {
    private final AbstractClientPlayer player;
    private final PreviewRenderer.PreviewAppearance appearance;
    private final PlayerSkin skin;
    private final ItemStack chestEquipment;
    private final java.util.function.BooleanSupplier current;
    private final java.util.function.Consumer<PreviewModelFrames.Body> displayed;
    private Attempt attempt;

    AvatarPreviewContext(
            AbstractClientPlayer player,
            PreviewRenderer.PreviewAppearance appearance,
            PlayerSkin skin,
            ItemStack chestEquipment, java.util.function.BooleanSupplier current,
            java.util.function.Consumer<PreviewModelFrames.Body> displayed) {
        this.displayed = Objects.requireNonNull(displayed);
        this.current = Objects.requireNonNull(current);
        this.player = Objects.requireNonNull(player, "player");
        this.appearance = Objects.requireNonNull(appearance, "appearance");
        this.skin = Objects.requireNonNull(skin, "skin");
        this.chestEquipment = Objects.requireNonNull(chestEquipment, "chestEquipment").copy();
    }

    public boolean current() { return current.getAsBoolean(); }

    public Attempt begin(Object owner, Minecraft minecraft, PreviewRenderFailureSink layerSink) {
        if (attempt != null) {
            attempt.failed = true;
            throw new IllegalStateException("Editor preview attempt is already active");
        }
        AvatarPreviewScope scope = open(minecraft);
        try {
            attempt = new Attempt(owner, scope, layerSink);
            return attempt;
        } catch (RuntimeException | Error failure) {
            try { scope.close(); } catch (RuntimeException | Error cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }

    public Attempt submission(Object owner) {
        if (attempt == null) return null;
        if (attempt.owner != owner || attempt.submitting || attempt.submitted) {
            attempt.failed = true;
            throw new IllegalStateException("Editor preview submission does not own the active attempt");
        }
        attempt.submitting = true;
        return attempt;
    }

    private void displayed(PreviewModelFrames.Body body) {
        if (current()) displayed.accept(new PreviewModelFrames.Body(body.status(),
                () -> current() && body.current().getAsBoolean()));
    }

    public void failed() {
        if (attempt != null) attempt.failed = true;
        displayed(new PreviewModelFrames.Body(PreviewRenderer.BodyStatus.FAILED, () -> true));
    }

    public final class Attempt implements AutoCloseable {
        private final Object owner;
        private final AvatarPreviewScope scope;
        private final EditorPreviewLayerGuard layerGuard;
        private boolean submitting;
        private boolean submitted;
        private boolean failed;
        private boolean closed;

        private Attempt(Object owner, AvatarPreviewScope scope, PreviewRenderFailureSink layerSink) {
            this.owner = Objects.requireNonNull(owner);
            this.scope = scope;
            layerGuard = layerSink == null ? null : EditorPreviewLayerGuard.open(failure -> {
                failed = true;
                layerSink.onFailure(failure);
            });
        }

        public void submitted() { submitted = true; submitting = false; }
        public void failed() { failed = true; }

        public PreviewModelFrames.Body body() { return scope.body(); }

        public void displayed(boolean completed, PreviewModelFrames.Body body) {
            if (!completed || failed) {
                AvatarPreviewContext.this.displayed(new PreviewModelFrames.Body(PreviewRenderer.BodyStatus.FAILED, () -> true));
            } else if (submitted) {
                AvatarPreviewContext.this.displayed(body);
            }
        }

        @Override
        public void close() {
            if (closed) return;
            closed = true;
            try (scope) {
                if (layerGuard != null) layerGuard.close();
            } finally {
                attempt = null;
            }
        }
    }

    public AvatarPreviewScope open(Minecraft minecraft) {
        return AvatarPreviewScope.open(
                minecraft, player, appearance, skin, chestEquipment.copy());
    }
}

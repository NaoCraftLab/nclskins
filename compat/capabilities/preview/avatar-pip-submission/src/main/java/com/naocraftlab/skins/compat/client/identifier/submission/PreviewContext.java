package com.naocraftlab.skins.compat.client.identifier.submission;

import com.naocraftlab.skins.client.PreviewRenderer;
import java.util.Objects;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.world.entity.player.PlayerSkin;
import net.minecraft.world.item.ItemStack;

public final class PreviewContext {
    private final AbstractClientPlayer player;
    private final PreviewRenderer.PreviewAppearance appearance;
    private final PlayerSkin skin;
    private final ItemStack chestEquipment;
    private final java.util.function.BooleanSupplier current;
    private final java.util.function.Consumer<com.naocraftlab.skins.client.PreviewModelFrames.Body> displayed;
    private java.util.function.Function<net.minecraft.client.renderer.entity.state.AvatarRenderState,
            com.naocraftlab.skins.client.PreviewModelFrames.Lease> capture;
    private java.util.function.Consumer<Boolean> captured;
    private float depthExtent;

    void depthExtent(float value) { depthExtent = value; }
    float depthExtent() { return depthExtent; }

    void measurement(java.util.function.Function<net.minecraft.client.renderer.entity.state.AvatarRenderState,
            com.naocraftlab.skins.client.PreviewModelFrames.Lease> capture, java.util.function.Consumer<Boolean> captured) {
        this.capture = Objects.requireNonNull(capture);
        this.captured = Objects.requireNonNull(captured);
    }

    com.naocraftlab.skins.client.PreviewModelFrames.Lease capture(net.minecraft.client.renderer.entity.state.AvatarRenderState state) {
        return capture == null ? null : capture.apply(state);
    }

    void captured(boolean success) { if (captured != null) captured.accept(success && current()); }

    PreviewContext(
            AbstractClientPlayer player,
            PreviewRenderer.PreviewAppearance appearance,
            PlayerSkin skin,
            ItemStack chestEquipment, java.util.function.BooleanSupplier current,
            java.util.function.Consumer<com.naocraftlab.skins.client.PreviewModelFrames.Body> displayed) {
        this.displayed = displayed instanceof com.naocraftlab.skins.client.PreviewModelFrames.Completion completion
                ? com.naocraftlab.skins.compat.client.identifier.preview.PreviewConsumerFrame.afterConsumption(completion)
                : Objects.requireNonNull(displayed);
        this.current = Objects.requireNonNull(current);
        this.player = Objects.requireNonNull(player, "player");
        this.appearance = Objects.requireNonNull(appearance, "appearance");
        this.skin = Objects.requireNonNull(skin, "skin");
        this.chestEquipment = Objects.requireNonNull(chestEquipment, "chestEquipment").copy();
    }

    public boolean current() { return current.getAsBoolean(); }

    public void displayed(com.naocraftlab.skins.client.PreviewModelFrames.Body body) {
        if (captured != null) return;
        if (current()) displayed.accept(new com.naocraftlab.skins.client.PreviewModelFrames.Body(body.status(),
                () -> current() && body.current().getAsBoolean()));
    }

    public PreviewScope open(Minecraft minecraft) {
        return PreviewScope.open(
                minecraft, player, appearance, skin, chestEquipment.copy());
    }
}

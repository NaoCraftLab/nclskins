package com.naocraftlab.skins.compat.mc262;

import com.naocraftlab.skins.runtime.Bounds;
import com.naocraftlab.skins.runtime.NativeScrollSync;
import com.naocraftlab.skins.runtime.ViewSpec;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractScrollArea;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;

final class Minecraft262ScrollController extends AbstractScrollArea {
    private Optional<ViewSpec.ScrollSurface> surface = Optional.empty();
    private final NativeScrollSync synchronization = new NativeScrollSync();
    private int maximum;
    private double wheelStep = 32.0;

    Minecraft262ScrollController() {
        super(0, 0, 1, 1, Component.empty(), defaultSettings(0));
    }

    void synchronize(Optional<ViewSpec.ScrollSurface> desired) {
        Objects.requireNonNull(desired, "desired");
        NativeScrollSync.Decision decision = synchronization.synchronize(desired);
        if (!decision.active()) {
            surface = Optional.empty();
            maximum = 0;
            return;
        }
        ViewSpec.ScrollSurface next = desired.orElseThrow();
        surface = Optional.of(next);
        maximum = (int) Math.ceil(next.maximumPixels());
        wheelStep = next.wheelStepPixels();
        Bounds viewport = next.viewport();
        if (decision.geometryChanged()) {
            setRectangle(viewport.width(), viewport.height(), viewport.x(), viewport.y());
        }
        if (decision.resetOffset()) {
            setScrollAmount(next.offsetPixels());
        }
    }

    boolean forwardScroll(
            double mouseX,
            double mouseY,
            double horizontalAmount,
            double verticalAmount) {
        if (surface.isEmpty()) {
            return false;
        }
        double amount = dominantAmount(horizontalAmount, verticalAmount);
        return amount != 0.0 && super.mouseScrolled(mouseX, mouseY, 0.0, amount);
    }

    Optional<String> surfaceId() {
        return surface.map(ViewSpec.ScrollSurface::id);
    }

    double offsetPixels() {
        return scrollAmount();
    }

    void acceptedRuntimeOffset(double offsetPixels) {
        synchronization.acceptedRuntimeOffset(offsetPixels);
    }

    @Override
    protected void extractWidgetRenderState(
            GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        if (surface.isPresent()) {
            extractScrollbar(graphics, mouseX, mouseY);
        }
    }

    @Override
    protected void handleCursor(GuiGraphicsExtractor graphics) {
    }

    @Override
    protected int contentHeight() {
        return getHeight() + maximum;
    }

    @Override
    public int maxScrollAmount() {
        return maximum;
    }

    @Override
    protected double scrollRate() {
        return wheelStep;
    }

    @Override
    protected boolean scrollable() {
        return false;
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
    }

    private static double dominantAmount(double horizontalAmount, double verticalAmount) {
        return Math.abs(horizontalAmount) > Math.abs(verticalAmount)
                ? horizontalAmount
                : verticalAmount;
    }
}

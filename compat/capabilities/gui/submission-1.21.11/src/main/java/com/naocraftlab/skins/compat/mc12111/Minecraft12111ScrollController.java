package com.naocraftlab.skins.compat.mc12111;

import com.naocraftlab.skins.runtime.Bounds;
import com.naocraftlab.skins.runtime.NativeScrollSync;
import com.naocraftlab.skins.runtime.ViewSpec;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractScrollArea;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;


final class Minecraft12111ScrollController extends AbstractScrollArea {
    private Optional<ViewSpec.ScrollSurface> surface = Optional.empty();
    private final NativeScrollSync synchronization = new NativeScrollSync();
    private int maximum;
    private double wheelStep = 32.0;

    Minecraft12111ScrollController() {
        super(0, 0, 1, 1, Component.empty());
        visible = false;
    }

    void synchronize(Optional<ViewSpec.ScrollSurface> desired) {
        Objects.requireNonNull(desired, "desired");
        NativeScrollSync.Decision decision = synchronization.synchronize(desired);
        if (!decision.active()) {
            surface = Optional.empty();
            maximum = 0;
            visible = false;
            return;
        }
        ViewSpec.ScrollSurface next = desired.orElseThrow();
        surface = Optional.of(next);
        visible = true;
        maximum = (int) Math.ceil(next.maximumPixels());
        wheelStep = next.wheelStepPixels();
        if (decision.geometryChanged()) {
            Bounds viewport = next.viewport();
            setWidth(viewport.width());
            setHeight(viewport.height());
            setX(viewport.x());
            setY(viewport.y());
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
        double amount = Math.abs(horizontalAmount) > Math.abs(verticalAmount)
                ? horizontalAmount
                : verticalAmount;
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
    protected void renderWidget(
            GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        graphics.enableScissor(0, 0, 0, 0);
        try {
            renderScrollbar(graphics, mouseX, mouseY);
        } finally {
            graphics.disableScissor();
        }
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
    protected void updateWidgetNarration(NarrationElementOutput output) {
    }
}

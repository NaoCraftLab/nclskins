package com.naocraftlab.skins.mc1211;

import com.naocraftlab.skins.compat.gui.immediate.NativeScrollController;
import com.naocraftlab.skins.runtime.Bounds;
import com.naocraftlab.skins.runtime.NativeScrollSync;
import com.naocraftlab.skins.runtime.ViewSpec;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSelectionList;
import net.minecraft.client.gui.narration.NarrationElementOutput;


final class Minecraft1211ScrollController
        extends AbstractSelectionList<Minecraft1211ScrollController.EmptyEntry>
        implements NativeScrollController {
    private static final int ITEM_HEIGHT = 64;

    private Optional<ViewSpec.ScrollSurface> surface = Optional.empty();
    private final NativeScrollSync synchronization = new NativeScrollSync();
    private int maximum;

    Minecraft1211ScrollController(Minecraft minecraft) {
        super(Objects.requireNonNull(minecraft, "minecraft"), 1, 1, 0, ITEM_HEIGHT);
    }

    @Override
    public void synchronize(Optional<ViewSpec.ScrollSurface> desired) {
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
        Bounds viewport = next.viewport();
        if (decision.geometryChanged()) {
            updateSizeAndPosition(viewport.width(), viewport.height(), viewport.y());
            setX(viewport.x());
        }
        if (decision.resetOffset()) {
            setScrollAmount(next.offsetPixels());
        }
    }

    @Override
    public void renderFrame(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        if (surface.isPresent()) {
            render(graphics, mouseX, mouseY, partialTick);
        }
    }

    @Override
    public boolean mouseScrolled(
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

    @Override
    public Optional<String> surfaceId() {
        return surface.map(ViewSpec.ScrollSurface::id);
    }

    @Override
    public double offsetPixels() {
        return getScrollAmount();
    }

    @Override
    public void acceptedRuntimeOffset(double offsetPixels) {
        synchronization.acceptedRuntimeOffset(offsetPixels);
    }

    @Override
    public int getMaxScroll() {
        return maximum;
    }

    @Override
    protected boolean scrollbarVisible() {
        return false;
    }

    @Override
    protected void renderListBackground(GuiGraphics graphics) {
    }

    @Override
    protected void renderListSeparators(GuiGraphics graphics) {
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
    }

    private static double dominantAmount(double horizontalAmount, double verticalAmount) {
        return Math.abs(horizontalAmount) > Math.abs(verticalAmount)
                ? horizontalAmount
                : verticalAmount;
    }

    static final class EmptyEntry extends AbstractSelectionList.Entry<EmptyEntry> {
        @Override
        public void render(
                GuiGraphics graphics,
                int index,
                int top,
                int left,
                int width,
                int height,
                int mouseX,
                int mouseY,
                boolean hovered,
                float partialTick) {
        }
    }
}

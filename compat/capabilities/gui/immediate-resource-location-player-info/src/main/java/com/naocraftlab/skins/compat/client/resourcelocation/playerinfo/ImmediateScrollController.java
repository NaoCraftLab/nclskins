package com.naocraftlab.skins.compat.client.resourcelocation.playerinfo;

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


final class ImmediateScrollController
        extends AbstractSelectionList<ImmediateScrollController.EmptyEntry>
        implements NativeScrollController {
    private static final int ITEM_HEIGHT = 64;
    private static final int OFFSCREEN_SCROLLBAR_X = -1_000_000;

    private Optional<ViewSpec.ScrollSurface> surface = Optional.empty();
    private final NativeScrollSync synchronization = new NativeScrollSync();
    private int maximum;

    ImmediateScrollController(Minecraft minecraft) {
        super(Objects.requireNonNull(minecraft, "minecraft"), 1, 1, 0, 1, ITEM_HEIGHT);
        setRenderSelection(false);
        setRenderBackground(false);
        setRenderTopAndBottom(false);
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
            updateSize(viewport.width(), viewport.height(), viewport.y(), viewport.bottom());
            setLeftPos(viewport.x());
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
        return amount != 0.0 && super.mouseScrolled(mouseX, mouseY, amount);
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
    protected int getScrollbarPosition() {
        return OFFSCREEN_SCROLLBAR_X;
    }

    @Override
    public void updateNarration(NarrationElementOutput output) {
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

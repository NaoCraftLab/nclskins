package com.naocraftlab.skins.compat.gui.immediate;

import com.naocraftlab.skins.runtime.Bounds;
import java.util.Comparator;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;


public final class MenuScreenSupport {
    private static final int MIN_MAIN_ROW_WIDTH = 150;
    private static final int MIN_MAIN_ROW_HEIGHT = 18;
    private static final int MAX_MAIN_ROW_HEIGHT = 24;
    private static final int CENTER_TOLERANCE = 8;

    private MenuScreenSupport() {}


    public static Optional<Bounds> topMainAction(Screen screen, AbstractWidget excluded) {
        Objects.requireNonNull(screen, "screen");
        return screen.children().stream()
                .filter(AbstractWidget.class::isInstance)
                .map(AbstractWidget.class::cast)
                .filter(widget -> widget != excluded)
                .filter(widget -> widget.visible)
                .filter(widget -> widget.getWidth() >= MIN_MAIN_ROW_WIDTH)
                .filter(widget -> widget.getHeight() >= MIN_MAIN_ROW_HEIGHT)
                .filter(widget -> widget.getHeight() <= MAX_MAIN_ROW_HEIGHT)
                .filter(widget -> widget.getX() >= 0 && widget.getY() >= 0)
                .filter(widget -> widget.getX() + widget.getWidth() <= screen.width)
                .filter(widget -> widget.getY() + widget.getHeight() <= screen.height)
                .filter(widget -> Math.abs(
                                widget.getX() + widget.getWidth() / 2 - screen.width / 2)
                        <= CENTER_TOLERANCE)
                .min(Comparator.comparingInt(AbstractWidget::getY))
                .map(widget -> new Bounds(
                        widget.getX(), widget.getY(), widget.getWidth(), widget.getHeight()));
    }


    public static final class NoChromeAction extends AbstractButton {
        private final Screen screen;
        private final Runnable onPress;

        public NoChromeAction(Screen screen, Runnable onPress) {
            super(
                    screen.width + 8,
                    screen.height + 8,
                    1,
                    1,
                    Component.translatable("nclskins.menu.preview"));
            this.screen = Objects.requireNonNull(screen, "screen");
            this.onPress = Objects.requireNonNull(onPress, "onPress");
            active = false;
            visible = false;
        }

        public void applyBounds(Optional<Bounds> bounds) {
            Objects.requireNonNull(bounds, "bounds");
            if (bounds.isEmpty()) {
                active = false;
                visible = false;
                setFocused(false);
                return;
            }
            Bounds value = bounds.orElseThrow();
            setX(value.x());
            setY(value.y());
            setWidth(value.width());

            height = value.height();
            active = true;
            visible = true;
        }

        @Override
        public void onPress() {
            onPress.run();
        }

        @Override
        public boolean mouseClicked(double mouseX, double mouseY, int button) {
            if (coveredByAnotherWidget(mouseX, mouseY)) {
                return false;
            }
            return super.mouseClicked(mouseX, mouseY, button);
        }

        @Override
        protected void renderWidget(
                GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {

        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput output) {
            defaultButtonNarrationText(output);
        }

        private boolean coveredByAnotherWidget(double mouseX, double mouseY) {
            for (GuiEventListener child : screen.children()) {
                if (child instanceof AbstractWidget widget
                        && widget != this
                        && widget.visible
                        && widget.isMouseOver(mouseX, mouseY)) {
                    return true;
                }
            }
            return false;
        }
    }
}

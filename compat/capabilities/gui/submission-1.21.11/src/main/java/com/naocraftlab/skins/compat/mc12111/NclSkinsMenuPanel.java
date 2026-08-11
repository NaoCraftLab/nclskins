package com.naocraftlab.skins.compat.mc12111;

import com.naocraftlab.skins.client.PreviewRenderer;
import com.naocraftlab.skins.compat.mc12111.mixin.ScreenRenderablesAccessor;
import com.naocraftlab.skins.runtime.Bounds;
import com.naocraftlab.skins.runtime.MenuPanelPresenter;
import java.util.Comparator;
import java.util.Map;
import java.util.Optional;
import java.util.WeakHashMap;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.input.InputWithModifiers;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;


public final class NclSkinsMenuPanel {
    private static final MenuPanelPresenter PRESENTER = new MenuPanelPresenter();
    private static final int MIN_MAIN_ROW_WIDTH = 150;
    private static final int MIN_MAIN_ROW_HEIGHT = 18;
    private static final int MAX_MAIN_ROW_HEIGHT = 24;
    private static final int CENTER_TOLERANCE = 8;
    private static final Map<Screen, State> STATES = new WeakHashMap<>();

    private NclSkinsMenuPanel() {}

    public static void install(Screen screen, Consumer<AbstractWidget> widgetConsumer) {
        if (!(screen instanceof TitleScreen || screen instanceof PauseScreen)
                || NclSkins12111ClientRuntime.closed()) {
            return;
        }
        NclSkinsScreen.warmSessionSnapshot();
        removed(screen);
        State state = new State(screen);
        STATES.put(screen, state);
        widgetConsumer.accept(state.action);
        var renderables = ((ScreenRenderablesAccessor) screen).nclskins$renderables();
        renderables.remove(state);
        renderables.add(0, state);
    }

    public static void removed(Screen screen) {
        State state = STATES.remove(screen);
        if (state != null) {
            ((ScreenRenderablesAccessor) screen).nclskins$renderables().remove(state);
            state.close();
        }
    }

    public static void clear() {
        STATES.values().forEach(State::close);
        STATES.clear();
    }

    private static Optional<Bounds> anchor(Screen screen, AbstractWidget excluded) {
        return screen.children().stream()
                .filter(AbstractWidget.class::isInstance)
                .map(AbstractWidget.class::cast)
                .filter(widget -> widget != excluded && widget.visible)
                .filter(widget -> widget.getWidth() >= MIN_MAIN_ROW_WIDTH)
                .filter(widget -> widget.getHeight() >= MIN_MAIN_ROW_HEIGHT)
                .filter(widget -> widget.getHeight() <= MAX_MAIN_ROW_HEIGHT)
                .filter(widget -> widget.getX() >= 0 && widget.getY() >= 0)
                .filter(widget -> widget.getRight() <= screen.width)
                .filter(widget -> widget.getBottom() <= screen.height)
                .filter(widget -> Math.abs(
                                widget.getX() + widget.getWidth() / 2 - screen.width / 2)
                        <= CENTER_TOLERANCE)
                .min(Comparator.comparingInt(AbstractWidget::getY))
                .map(widget -> new Bounds(
                        widget.getX(), widget.getY(), widget.getWidth(), widget.getHeight()));
    }

    private static final class State implements Renderable, AutoCloseable {
        private final Screen screen;
        private final Minecraft12111SimplePreviewRenderer renderer =
                new Minecraft12111SimplePreviewRenderer();
        private final Action action = new Action();

        private State(Screen screen) {
            this.screen = screen;
        }

        @Override
        public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            Optional<MenuPanelPresenter.Layout> layout = anchor(screen, action)
                    .flatMap(bounds -> PRESENTER.present(
                            screen.width, screen.height, mouseX, mouseY, bounds));
            action.apply(layout.map(MenuPanelPresenter.Layout::buttonBounds));
            if (layout.isEmpty()) {
                return;
            }
            var appearance = NclSkins12111ClientRuntime.runtime()
                    .currentPlayerAppearance().orElse(null);
            if (appearance == null) {
                return;
            }
            MenuPanelPresenter.Layout value = layout.orElseThrow();
            if (value.panelBounds().contains(mouseX, mouseY) || action.isFocused()) {
                graphics.fill(
                        value.panelBounds().x(),
                        value.panelBounds().y(),
                        value.panelBounds().right(),
                        value.panelBounds().bottom(),
                        0x28FFFFFF);
            }
            Bounds preview = value.previewBounds();
            Optional<com.naocraftlab.skins.client.TextureRegistry.TextureHandle> cape = appearance.cape();
            renderer.render(graphics, new PreviewRenderer.PreviewRequest(
                    new PreviewRenderer.PreviewAppearance(
                            appearance.skin(),
                            appearance.model(),
                            cape,
                            cape.isPresent() ? PreviewRenderer.CapeMode.CAPE : PreviewRenderer.CapeMode.OFF,
                            true),
                    preview.x(), preview.y(), preview.width(), preview.height(),
                    value.yawDegrees(), value.pitchDegrees(), value.scale(),
                    PreviewRenderer.PreviewIntent.CURRENT_APPEARANCE));
        }

        @Override
        public void close() {
            renderer.close();
        }

        private final class Action extends AbstractButton {
            private Action() {
                super(0, 0, 0, 0, Component.translatable("nclskins.open"));
                visible = false;
            }

            private void apply(Optional<Bounds> bounds) {
                visible = bounds.isPresent();
                bounds.ifPresent(value -> {
                    setWidth(value.width());
                    setHeight(value.height());
                    setX(value.x());
                    setY(value.y());
                });
            }

            @Override
            public void onPress(InputWithModifiers input) {
                Minecraft.getInstance().setScreen(new NclSkinsScreen(screen));
            }

            @Override
            public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
                if (coveredByAnotherWidget(event.x(), event.y())) {
                    return false;
                }
                return super.mouseClicked(event, doubleClick);
            }

            @Override
            protected void renderContents(
                    GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {}

            @Override
            public void updateWidgetNarration(NarrationElementOutput output) {
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
}

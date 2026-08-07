package com.naocraftlab.skins.compat.mc262;

import com.naocraftlab.skins.client.PreviewRenderer;
import com.naocraftlab.skins.client.TextureRegistry.TextureHandle;
import com.naocraftlab.skins.compat.mc262.mixin.ScreenRenderablesAccessor;
import com.naocraftlab.skins.runtime.Bounds;
import com.naocraftlab.skins.runtime.MenuPanelPresenter;
import java.util.Comparator;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.WeakHashMap;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
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
    private static final Map<Screen, PlayerPreviewUnderlay> PREVIEWS = new WeakHashMap<>();

    private NclSkinsMenuPanel() {}

    public static boolean supports(Screen screen) {
        return screen instanceof TitleScreen || screen instanceof PauseScreen;
    }

    public static void install(
            Screen screen,
            Consumer<AbstractWidget> widgetConsumer) {
        if (!supports(screen) || NclSkins262ClientRuntime.closed()) {
            return;
        }
        NclSkinsScreen.warmSessionSnapshot();

        PlayerPreviewUnderlay previous = PREVIEWS.remove(screen);
        if (previous != null) {
            previous.close();
            ((ScreenRenderablesAccessor) screen).nclskins$renderables().remove(previous);
        }

        PlayerPreviewUnderlay preview = new PlayerPreviewUnderlay(
                screen,
                () -> Minecraft26Api.setScreen(Minecraft.getInstance(), new NclSkinsScreen(screen)));
        widgetConsumer.accept(preview.action());
        preview.updateLayout(0, 0);
        var renderables = ((ScreenRenderablesAccessor) screen).nclskins$renderables();
        renderables.remove(preview);
        renderables.add(0, preview);
        PREVIEWS.put(screen, preview);
    }

    public static void removed(Screen screen) {
        PlayerPreviewUnderlay preview = PREVIEWS.remove(screen);
        if (preview != null) {
            preview.close();
        }
    }

    public static void clear() {
        PREVIEWS.values().forEach(PlayerPreviewUnderlay::close);
        PREVIEWS.clear();
    }

    private static Optional<Bounds> topMainAction(Screen screen, AbstractWidget excluded) {
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

    private static final class PlayerPreviewUnderlay implements Renderable, AutoCloseable {
        private final Screen screen;
        private final Minecraft262SimplePreviewRenderer renderer =
                new Minecraft262SimplePreviewRenderer();
        private final NoChromeAction action;

        private PlayerPreviewUnderlay(Screen screen, Runnable openGallery) {
            this.screen = Objects.requireNonNull(screen, "screen");
            action = new NoChromeAction(screen, openGallery);
        }

        private NoChromeAction action() {
            return action;
        }

        @Override
        public void extractRenderState(
                GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
            if (NclSkins262ClientRuntime.closed()) {
                action.applyBounds(Optional.empty());
                return;
            }
            MenuPanelPresenter.Layout layout = updateLayout(mouseX, mouseY).orElse(null);
            if (layout == null) {
                return;
            }
            var current = NclSkins262ClientRuntime.runtime().currentPlayerAppearance().orElse(null);
            if (current == null) {
                return;
            }
            Optional<TextureHandle> cape = current.cape();
            PreviewRenderer.PreviewAppearance appearance = new PreviewRenderer.PreviewAppearance(
                    current.skin(),
                    current.model(),
                    cape,
                    cape.isPresent() ? PreviewRenderer.CapeMode.CAPE : PreviewRenderer.CapeMode.OFF,
                    true);
            var preview = layout.previewBounds();
            if (layout.panelBounds().contains(mouseX, mouseY) || action.isFocused()) {
                graphics.fill(
                        layout.panelBounds().x(),
                        layout.panelBounds().y(),
                        layout.panelBounds().right(),
                        layout.panelBounds().bottom(),
                        0x28FFFFFF);
            }
            renderer.render(
                    graphics,
                    new PreviewRenderer.PreviewRequest(
                            appearance,
                            preview.x(),
                            preview.y(),
                            preview.width(),
                            preview.height(),
                            layout.yawDegrees(),
                            layout.pitchDegrees(),
                            layout.scale(),
                            PreviewRenderer.PreviewIntent.CURRENT_APPEARANCE));


            graphics.nextStratum();
        }

        private Optional<MenuPanelPresenter.Layout> updateLayout(int mouseX, int mouseY) {
            Optional<Bounds> anchor = topMainAction(screen, action);
            Optional<MenuPanelPresenter.Layout> layout = anchor.flatMap(value -> PRESENTER.present(
                    screen.width, screen.height, mouseX, mouseY, value));
            action.applyBounds(layout.map(MenuPanelPresenter.Layout::buttonBounds));
            return layout;
        }

        @Override
        public void close() {
            renderer.close();
        }
    }


    private static final class NoChromeAction extends AbstractButton {
        private final Screen screen;
        private final Runnable onPress;

        private NoChromeAction(Screen screen, Runnable onPress) {
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

        private void applyBounds(Optional<Bounds> bounds) {
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
            setHeight(value.height());
            active = true;
            visible = true;
        }

        @Override
        public void onPress(InputWithModifiers input) {
            onPress.run();
        }

        @Override
        public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
            if (coveredByAnotherWidget(event.x(), event.y())) {
                return false;
            }
            return super.mouseClicked(event, doubleClick);
        }

        @Override
        protected void extractContents(
                GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {

        }

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

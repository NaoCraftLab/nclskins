package com.naocraftlab.skins.mc1211;

import com.mojang.blaze3d.systems.RenderSystem;
import com.naocraftlab.skins.client.CurrentPlayerAppearanceSource;
import com.naocraftlab.skins.client.PreviewRenderer;
import com.naocraftlab.skins.client.TextureRegistry;
import com.naocraftlab.skins.compat.gui.immediate.MenuScreenSupport;
import com.naocraftlab.skins.compat.gui.immediate.MenuScreenSupport.NoChromeAction;
import com.naocraftlab.skins.mc1211.mixin.ScreenRenderablesAccessor;
import com.naocraftlab.skins.runtime.Bounds;
import com.naocraftlab.skins.runtime.MenuPanelPresenter;
import java.util.Map;
import java.util.Optional;
import java.util.WeakHashMap;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import org.lwjgl.opengl.GL11;


public final class NclSkinsMenuPreview implements Renderable {
    private static final MenuPanelPresenter PRESENTER = new MenuPanelPresenter();
    private static final Map<Screen, NclSkinsMenuPreview> STATES = new WeakHashMap<>();

    private final Screen screen;
    private final Minecraft1211SimplePreviewRenderer renderer;
    private final CurrentPlayerAppearanceSource appearanceSource;
    private final NoChromeAction action;

    private NclSkinsMenuPreview(Screen screen, Runnable openGallery) {
        this.screen = screen;
        Minecraft minecraft = Minecraft.getInstance();
        renderer = new Minecraft1211SimplePreviewRenderer(minecraft);
        appearanceSource = Minecraft1211Client.instance().currentAppearanceSource();
        action = new NoChromeAction(screen, openGallery);
    }

    public static boolean supports(Screen screen) {
        return screen instanceof TitleScreen || screen instanceof PauseScreen;
    }

    public static void install(
            Screen screen, Runnable openGallery, Consumer<AbstractWidget> widgetConsumer) {
        if (!supports(screen)) {
            return;
        }
        NclSkinsMenuPreview preview = new NclSkinsMenuPreview(screen, openGallery);
        NclSkinsMenuPreview previous = STATES.put(screen, preview);
        var renderables = ((ScreenRenderablesAccessor) screen).nclskins$renderables();
        if (previous != null) {
            renderables.remove(previous);
        }
        widgetConsumer.accept(preview.action);
        preview.updateLayout(0, 0);
        renderables.remove(preview);
        renderables.add(0, preview);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        MenuPanelPresenter.Layout layout = updateLayout(mouseX, mouseY).orElse(null);
        if (layout == null) {
            return;
        }
        var current = appearanceSource.currentPlayerAppearance();
        TextureRegistry.TextureHandle skinTexture = current.skin();
        Optional<TextureRegistry.TextureHandle> capeTexture = current.cape();
        PreviewRenderer.PreviewAppearance appearance = new PreviewRenderer.PreviewAppearance(
                skinTexture,
                current.model(),
                capeTexture,
                capeTexture.isPresent()
                        ? PreviewRenderer.CapeMode.CAPE
                        : PreviewRenderer.CapeMode.OFF,
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
        graphics.enableScissor(preview.x(), preview.y(), preview.right(), preview.bottom());
        try {
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
                            layout.scale()));
        } finally {
            graphics.disableScissor();
        }


        RenderSystem.clear(GL11.GL_DEPTH_BUFFER_BIT, Minecraft.ON_OSX);
    }

    public static void removed(Screen screen) {
        STATES.remove(screen);
    }

    public static void clear() {
        STATES.clear();
    }

    private Optional<MenuPanelPresenter.Layout> updateLayout(int mouseX, int mouseY) {
        Optional<Bounds> anchor = MenuScreenSupport.topMainAction(screen, action);
        Optional<MenuPanelPresenter.Layout> layout = anchor.flatMap(value -> PRESENTER.present(
                screen.width, screen.height, mouseX, mouseY, value));
        action.applyBounds(layout.map(MenuPanelPresenter.Layout::buttonBounds));
        return layout;
    }
}

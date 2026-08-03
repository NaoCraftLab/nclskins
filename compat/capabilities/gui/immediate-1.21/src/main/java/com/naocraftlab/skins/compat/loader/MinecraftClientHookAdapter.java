package com.naocraftlab.skins.compat.loader;

import com.naocraftlab.skins.client.MinecraftClientHooks;
import com.naocraftlab.skins.mc1211.Minecraft1211Client;
import com.naocraftlab.skins.mc1211.NclSkinsMenuPreview;
import java.util.Objects;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.Screen;


public final class MinecraftClientHookAdapter
        implements MinecraftClientHooks<
                Minecraft, Screen, AbstractWidget, MinecraftClientHookAdapter.Frame> {
    private static final MinecraftClientHookAdapter INSTANCE = new MinecraftClientHookAdapter();

    private MinecraftClientHookAdapter() {}

    public static MinecraftClientHookAdapter instance() {
        return INSTANCE;
    }

    @Override
    public void initialize() {
        Minecraft1211Client.instance().initialize();
    }

    @Override
    public void tick(Minecraft client) {
        Minecraft1211Client.instance().tick(client);
    }

    @Override
    public void afterScreenInit(
            Minecraft client,
            Screen screen,
            int width,
            int height,
            Consumer<AbstractWidget> widgets) {
        Objects.requireNonNull(client, "client");
        Objects.requireNonNull(widgets, "widgets");
        if (!NclSkinsMenuPreview.supports(screen)) {
            return;
        }
        Minecraft1211Client.instance().warmSession();
        NclSkinsMenuPreview.install(
                screen,
                () -> Minecraft1211Client.instance().openOrToggle(client, screen),
                widgets);
    }

    @Override
    public void screenRemoved(Screen screen) {
        NclSkinsMenuPreview.removed(screen);
    }

    @Override
    public void close() {
        NclSkinsMenuPreview.clear();
        Minecraft1211Client.instance().close();
    }

    public record Frame(GuiGraphics graphics, int mouseX, int mouseY) {
        public Frame {
            Objects.requireNonNull(graphics, "graphics");
        }
    }
}

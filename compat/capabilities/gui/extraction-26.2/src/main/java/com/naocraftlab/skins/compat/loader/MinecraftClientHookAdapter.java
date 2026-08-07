package com.naocraftlab.skins.compat.loader;

import com.naocraftlab.skins.client.MinecraftClientHooks;
import com.naocraftlab.skins.compat.mc262.NclSkinsMenuPanel;
import com.naocraftlab.skins.compat.mc262.NclSkinsScreen;
import java.util.Objects;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.Screen;


public final class MinecraftClientHookAdapter
        implements MinecraftClientHooks<Minecraft, Screen, AbstractWidget, Void> {
    private static final MinecraftClientHookAdapter INSTANCE = new MinecraftClientHookAdapter();

    private MinecraftClientHookAdapter() {}

    public static MinecraftClientHookAdapter instance() {
        return INSTANCE;
    }

    @Override
    public void initialize() {
        NclSkinsScreen.initializeClientRuntime();
    }

    @Override
    public void tick(Minecraft client) {
        NclSkinsScreen.onClientTick(client);
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
        NclSkinsMenuPanel.install(screen, widgets);
    }

    @Override
    public void screenRemoved(Screen screen) {
        NclSkinsMenuPanel.removed(screen);
    }

    @Override
    public void close() {
        NclSkinsMenuPanel.clear();
        NclSkinsScreen.closeClientRuntime();
    }
}

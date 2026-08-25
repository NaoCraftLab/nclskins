package com.naocraftlab.skins.compat.loader;

import com.naocraftlab.skins.client.MinecraftClientHooks;
import com.naocraftlab.skins.compat.config.MinecraftConfigurationBridge;
import com.naocraftlab.skins.compat.client.identifier.extraction.ExtractionGuiApi;
import com.naocraftlab.skins.compat.client.identifier.extraction.NclSkinsMenuPanel;
import com.naocraftlab.skins.compat.client.identifier.extraction.NclSkinsScreen;
import java.util.Objects;
import java.nio.file.Path;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.util.Util;


public final class MinecraftClientHookAdapter
        implements MinecraftClientHooks<Minecraft, Screen, AbstractWidget, Void> {
    private static final MinecraftClientHookAdapter INSTANCE = new MinecraftClientHookAdapter();

    private MinecraftClientHookAdapter() {}

    public static MinecraftClientHookAdapter instance() {
        return INSTANCE;
    }

    @Override
    public void initialize(Path configurationDirectory) {
        NclSkinsScreen.initializeClientRuntime(
                MinecraftConfigurationBridge.initialize(
                        configurationDirectory,
                        NclSkinsScreen.nativeFileDialog(),
                        screen -> ExtractionGuiApi.setScreen(
                                Minecraft.getInstance(), screen),
                        uri -> Util.getPlatform().openUri(uri)).activeDataRoot());
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
        if (!MinecraftConfigurationBridge.previewEnabled(screen)) {
            NclSkinsMenuPanel.removed(screen);
            return;
        }
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

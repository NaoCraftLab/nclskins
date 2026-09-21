package com.naocraftlab.skins.compat.loader;

import com.naocraftlab.skins.core.config.MenuPreviewPlacement;
import com.naocraftlab.skins.client.MinecraftClientHooks;
import com.naocraftlab.skins.compat.config.MinecraftConfigurationBridge;
import com.naocraftlab.skins.compat.config.ConfigurationLinkApi;
import com.naocraftlab.skins.compat.client.identifier.submission.NclSkinsMenuPanel;
import com.naocraftlab.skins.compat.client.identifier.submission.NclSkinsScreen;
import java.nio.file.Path;
import java.util.Objects;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.Screen;


public final class MinecraftClientHookAdapter
        implements MinecraftClientHooks<Minecraft, Screen, AbstractWidget> {
    private static final MinecraftClientHookAdapter INSTANCE = new MinecraftClientHookAdapter();

    private MinecraftClientHookAdapter() {}

    public static MinecraftClientHookAdapter instance() {
        return INSTANCE;
    }

    public com.naocraftlab.skins.diagnostics.DiagnosticSink diagnostics() {
        return NclSkinsScreen.clientDiagnostics();
    }

    public boolean keybindingContextActive() {
        Minecraft client = Minecraft.getInstance();
        return client.level != null && client.player != null && client.isWindowActive()
                && client.screen == null && client.getOverlay() == null;
    }

    public void openDestination(com.naocraftlab.skins.client.ScreenDestination destination) {
        Minecraft client = Minecraft.getInstance();
        Screen parent = client.screen;
        if (parent instanceof NclSkinsScreen || (parent == null && client.level == null)) return;
        client.setScreen(new NclSkinsScreen(parent, destination));
    }

    @Override
    public void initialize(Path configurationDirectory) {
        NclSkinsScreen.initializeClientRuntime(MinecraftConfigurationBridge.initialize(
                configurationDirectory,
                NclSkinsScreen.nativeFileDialog(),
                screen -> Minecraft.getInstance().setScreen(screen),
                ConfigurationLinkApi::open).activeDataRoot());
        NclSkinsScreen.warmSessionSnapshot();
    }

    @Override
    public void tick(Minecraft client) {
        NclSkinsScreen.onClientTick(client);
        com.naocraftlab.skins.compat.keybindings.ScreenKeybindings.tick(client);
    }

    @Override
    public void resourcesReloaded() {
        NclSkinsScreen.onResourcesReloaded();
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
        MenuPreviewPlacement placement = MinecraftConfigurationBridge.previewPlacement(screen);
        if (placement == MenuPreviewPlacement.OFF) {
            NclSkinsMenuPanel.removed(screen);
            return;
        }
        NclSkinsMenuPanel.install(screen, widgets, placement);
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

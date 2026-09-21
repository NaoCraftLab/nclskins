package com.naocraftlab.skins.compat.loader;

import com.naocraftlab.skins.core.config.MenuPreviewPlacement;
import com.naocraftlab.skins.client.MinecraftClientHooks;
import com.naocraftlab.skins.compat.config.MinecraftConfigurationBridge;
import com.naocraftlab.skins.compat.config.ConfigurationLinkApi;
import com.naocraftlab.skins.compat.client.identifier.extraction.ExtractionGuiApi;
import com.naocraftlab.skins.compat.client.identifier.extraction.NclSkinsMenuPanel;
import com.naocraftlab.skins.compat.client.identifier.extraction.NclSkinsScreen;
import java.util.Objects;
import java.nio.file.Path;
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
                && ExtractionGuiApi.currentScreen(client) == null && ExtractionGuiApi.keybindingOverlayAbsent(client);
    }

    public void openDestination(com.naocraftlab.skins.client.ScreenDestination destination) {
        Minecraft client = Minecraft.getInstance();
        Screen parent = ExtractionGuiApi.currentScreen(client);
        if (parent instanceof NclSkinsScreen || (parent == null && client.level == null)) return;
        ExtractionGuiApi.setScreen(client, new NclSkinsScreen(parent, destination));
    }

    @Override
    public void initialize(Path configurationDirectory) {
        NclSkinsScreen.initializeClientRuntime(
                MinecraftConfigurationBridge.initialize(
                        configurationDirectory,
                        NclSkinsScreen.nativeFileDialog(),
                        screen -> ExtractionGuiApi.setScreen(
                                Minecraft.getInstance(), screen),
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

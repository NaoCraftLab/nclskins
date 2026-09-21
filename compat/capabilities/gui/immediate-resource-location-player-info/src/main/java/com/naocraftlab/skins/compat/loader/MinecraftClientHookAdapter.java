package com.naocraftlab.skins.compat.loader;

import com.naocraftlab.skins.core.config.MenuPreviewPlacement;
import com.naocraftlab.skins.client.MinecraftClientHooks;
import com.naocraftlab.skins.compat.config.MinecraftConfigurationBridge;
import com.naocraftlab.skins.compat.config.ConfigurationLinkApi;
import com.naocraftlab.skins.compat.client.resourcelocation.playerinfo.NclSkinsMenuPreview;
import com.naocraftlab.skins.compat.client.resourcelocation.playerinfo.ImmediateClientRuntime;
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
        return ImmediateClientRuntime.instance().runtime().diagnostics();
    }

    public boolean keybindingContextActive() {
        Minecraft client = Minecraft.getInstance();
        return client.level != null && client.player != null && client.isWindowActive()
                && client.screen == null && client.getOverlay() == null;
    }

    public void openDestination(com.naocraftlab.skins.client.ScreenDestination destination) {
        Minecraft client = Minecraft.getInstance();
        Screen parent = client.screen;
        if (parent instanceof com.naocraftlab.skins.compat.gui.immediate.NclSkinsImmediateScreen || (parent == null && client.level == null)) return;
        client.setScreen(ImmediateClientRuntime.instance().createScreen(parent, destination));
    }

    @Override
    public void initialize(Path configurationDirectory) {
        ImmediateClientRuntime client = ImmediateClientRuntime.instance();
        client.initialize(MinecraftConfigurationBridge.initialize(
                configurationDirectory,
                client.nativeFileDialog(),
                screen -> Minecraft.getInstance().setScreen(screen),
                ConfigurationLinkApi::open).activeDataRoot());
        client.warmSession();
    }

    @Override
    public void tick(Minecraft client) {
        ImmediateClientRuntime.instance().tick(client);
        com.naocraftlab.skins.compat.keybindings.ScreenKeybindings.tick(client);
    }

    @Override
    public void resourcesReloaded() {
        ImmediateClientRuntime.instance().resourcesReloaded();
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
        MenuPreviewPlacement placement = MinecraftConfigurationBridge.previewPlacement(screen);
        if (placement == MenuPreviewPlacement.OFF) {
            NclSkinsMenuPreview.removed(screen);
            return;
        }
        ImmediateClientRuntime.instance().warmSession();
        NclSkinsMenuPreview.install(
                screen,
                () -> ImmediateClientRuntime.instance().openOrToggle(client, screen),
                widgets, placement);
    }

    @Override
    public void screenRemoved(Screen screen) {
        NclSkinsMenuPreview.removed(screen);
    }

    @Override
    public void close() {
        NclSkinsMenuPreview.clear();
        ImmediateClientRuntime.instance().close();
    }
}

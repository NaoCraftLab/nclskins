package com.naocraftlab.skins.compat.loader;

import com.naocraftlab.skins.client.MinecraftClientHooks;
import com.naocraftlab.skins.compat.config.MinecraftConfigurationBridge;
import com.naocraftlab.skins.compat.v1_20_1.client.NclSkinsMenuPreview;
import com.naocraftlab.skins.compat.v1_20_1.client.Minecraft1201Client;
import java.util.Objects;
import java.nio.file.Path;
import java.util.function.Consumer;
import net.minecraft.Util;
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
    public void initialize(Path configurationDirectory) {
        Minecraft1201Client client = Minecraft1201Client.instance();
        client.initialize(MinecraftConfigurationBridge.initialize(
                configurationDirectory,
                client.nativeFileDialog(),
                screen -> Minecraft.getInstance().setScreen(screen),
                uri -> Util.getPlatform().openUri(uri)).activeDataRoot());
    }

    @Override
    public void tick(Minecraft client) {
        Minecraft1201Client.instance().tick(client);
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
        if (!MinecraftConfigurationBridge.previewEnabled(screen)) {
            NclSkinsMenuPreview.removed(screen);
            return;
        }
        Minecraft1201Client.instance().warmSession();
        NclSkinsMenuPreview.install(
                screen,
                () -> Minecraft1201Client.instance().openOrToggle(client, screen),
                widgets);
    }

    @Override
    public void screenRemoved(Screen screen) {
        NclSkinsMenuPreview.removed(screen);
    }

    @Override
    public void close() {
        NclSkinsMenuPreview.clear();
        Minecraft1201Client.instance().close();
    }

    public record Frame(GuiGraphics graphics, int mouseX, int mouseY) {
        public Frame {
            Objects.requireNonNull(graphics, "graphics");
        }
    }
}

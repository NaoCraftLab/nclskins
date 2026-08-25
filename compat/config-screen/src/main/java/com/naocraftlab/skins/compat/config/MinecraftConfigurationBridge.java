package com.naocraftlab.skins.compat.config;

import com.naocraftlab.skins.client.FilePicker;
import com.naocraftlab.skins.runtime.ClientConfigurationService;
import com.naocraftlab.skins.runtime.ServerConfigurationAccess;
import it.unimi.dsi.fastutil.booleans.BooleanConsumer;
import java.net.URI;
import java.nio.file.Path;
import java.util.Objects;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConfirmLinkScreen;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;


public final class MinecraftConfigurationBridge {
    private static final String YACL_URL = "https://modrinth.com/mod/yacl";

    private static ClientConfigurationService service;
    private static FilePicker filePicker;
    private static Consumer<Screen> screenSetter;
    private static Consumer<URI> linkOpener;
    private static ConfigurationScreenFactory screenFactory;

    private MinecraftConfigurationBridge() {}

    public static synchronized ClientConfigurationService initialize(
            Path configurationDirectory,
            FilePicker nativeFileDialog,
            Consumer<Screen> nativeScreenSetter,
            Consumer<URI> nativeLinkOpener) {
        Path requested = Objects.requireNonNull(
                configurationDirectory, "configurationDirectory").toAbsolutePath().normalize();
        FilePicker requestedPicker = Objects.requireNonNull(
                nativeFileDialog, "nativeFileDialog");
        Consumer<Screen> requestedScreenSetter = Objects.requireNonNull(
                nativeScreenSetter, "nativeScreenSetter");
        Consumer<URI> requestedLinkOpener = Objects.requireNonNull(
                nativeLinkOpener, "nativeLinkOpener");
        if (service == null) {
            service = new ClientConfigurationService(requested);
            filePicker = requestedPicker;
            screenSetter = requestedScreenSetter;
            linkOpener = requestedLinkOpener;
        } else if (!service.configurationDirectory().equals(requested)) {
            throw new IllegalStateException("NCL Skins client config directory changed during startup");
        } else if (filePicker != requestedPicker) {
            throw new IllegalStateException("NCL Skins native file picker changed during startup");
        }
        return service;
    }

    public static synchronized void configureScreenFactory(
            ConfigurationScreenFactory nativeFactory) {
        if (service != null) {
            throw new IllegalStateException(
                    "NCL Skins configuration screen factory changed after initialization");
        }
        screenFactory = nativeFactory;
    }

    public static synchronized ClientConfigurationService service() {
        if (service == null) {
            throw new IllegalStateException("NCL Skins client configuration is not initialized");
        }
        return service;
    }

    public static boolean previewEnabled(Screen screen) {
        Objects.requireNonNull(screen, "screen");
        if (screen instanceof TitleScreen) {
            return service().client().menuPreview().titleScreen();
        }
        if (screen instanceof PauseScreen) {
            return service().client().menuPreview().pauseMenu();
        }
        return true;
    }

    public static Screen createScreen(Screen parent) {
        Objects.requireNonNull(parent, "parent");
        ClientConfigurationService current = service();
        ConfigurationScreenFactory factory = screenFactory;
        if (factory == null) {
            return missingYaclScreen(parent);
        }
        try {
            Minecraft minecraft = Minecraft.getInstance();
            ServerConfigurationAccess serverAccess = ServerConfigurationAccess.from(
                    minecraft.getConnection() != null,
                    minecraft.getSingleplayerServer() != null);
            return Objects.requireNonNull(
                    factory.create(parent, current, filePicker, serverAccess),
                    "YACL configuration screen");
        } catch (LinkageError incompatible) {
            return missingYaclScreen(parent);
        }
    }

    private static Screen missingYaclScreen(Screen parent) {
        BooleanConsumer callback = accepted -> {
            if (accepted) {
                openYaclPage();
            }
            restoreScreen(parent);
        };
        return new ConfirmLinkScreen(callback, YACL_URL, true);
    }

    private static void restoreScreen(Screen parent) {
        screenSetter.accept(parent);
    }

    private static void openYaclPage() {
        linkOpener.accept(URI.create(YACL_URL));
    }
}

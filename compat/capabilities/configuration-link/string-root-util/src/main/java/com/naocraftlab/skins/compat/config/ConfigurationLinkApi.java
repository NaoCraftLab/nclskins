package com.naocraftlab.skins.compat.config;

import net.minecraft.Util;
import it.unimi.dsi.fastutil.booleans.BooleanConsumer;
import java.net.URI;
import net.minecraft.client.gui.screens.ConfirmLinkScreen;
import net.minecraft.client.gui.screens.Screen;

public final class ConfigurationLinkApi {
    private ConfigurationLinkApi() {}

    public static Screen createScreen(BooleanConsumer callback, URI uri) {
        return new ConfirmLinkScreen(callback, uri.toString(), true);
    }

    public static void open(URI uri) {
        Util.getPlatform().openUri(uri);
    }
}

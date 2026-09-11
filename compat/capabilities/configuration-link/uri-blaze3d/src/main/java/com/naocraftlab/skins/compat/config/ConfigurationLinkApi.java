package com.naocraftlab.skins.compat.config;

import com.mojang.blaze3d.Blaze3D;
import it.unimi.dsi.fastutil.booleans.BooleanConsumer;
import java.net.URI;
import net.minecraft.client.gui.screens.ConfirmLinkScreen;
import net.minecraft.client.gui.screens.Screen;

public final class ConfigurationLinkApi {
    private ConfigurationLinkApi() {}

    public static Screen createScreen(BooleanConsumer callback, URI uri) {
        return new ConfirmLinkScreen(callback, uri, true);
    }

    public static void open(URI uri) {
        Blaze3D.openUri(uri);
    }
}

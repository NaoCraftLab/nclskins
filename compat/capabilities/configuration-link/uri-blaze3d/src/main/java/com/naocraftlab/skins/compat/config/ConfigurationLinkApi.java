package com.naocraftlab.skins.compat.config;

import com.mojang.blaze3d.Blaze3D;
import it.unimi.dsi.fastutil.booleans.BooleanConsumer;
import java.net.URI;
import java.util.function.BooleanSupplier;
import net.minecraft.client.gui.screens.ConfirmLinkScreen;
import net.minecraft.client.gui.screens.Screen;

public final class ConfigurationLinkApi {
    private ConfigurationLinkApi() {}

    public static Screen createScreen(BooleanConsumer callback, URI uri) {
        return new ConfirmLinkScreen(callback, uri, true);
    }

    public static Screen createScreen(BooleanConsumer callback, URI uri,
            BooleanSupplier valid, Runnable expired) {
        return new ConfirmLinkScreen(callback, uri, true) {
            @Override public void tick() {
                if (!valid.getAsBoolean()) expired.run();
                else super.tick();
            }

            @Override public void copyToClipboard() {
                if (!valid.getAsBoolean()) expired.run();
                else super.copyToClipboard();
            }
        };
    }

    public static void open(URI uri) {
        Blaze3D.openUri(uri);
    }
}

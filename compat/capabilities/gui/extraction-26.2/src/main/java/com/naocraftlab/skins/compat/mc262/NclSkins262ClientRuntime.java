package com.naocraftlab.skins.compat.mc262;

import com.naocraftlab.skins.compat.client.MinecraftClientExecutor;
import com.naocraftlab.skins.compat.client.MinecraftFilePicker;
import com.naocraftlab.skins.compat.client.MinecraftGameSessionTokenSource;
import com.naocraftlab.skins.compat.client.MinecraftServerAppearanceRefreshNotifier;
import com.naocraftlab.skins.runtime.AppearanceReconnectTracker;
import com.naocraftlab.skins.runtime.ClientRuntime;
import com.naocraftlab.skins.runtime.TextResolver;
import java.util.Objects;
import net.minecraft.client.Minecraft;
import net.minecraft.locale.Language;


final class NclSkins262ClientRuntime {
    private static final Minecraft262AppearanceSink APPEARANCE_SINK =
            new Minecraft262AppearanceSink();

    private static ClientRuntime runtime;
    private static volatile boolean terminallyClosed;
    private static final AppearanceReconnectTracker<Object> APPEARANCE_RECONNECTS =
            new AppearanceReconnectTracker<>();

    private NclSkins262ClientRuntime() {}

    static synchronized ClientRuntime runtime() {
        if (terminallyClosed) {
            throw new IllegalStateException("NCL Skins client runtime is terminally closed");
        }
        if (runtime == null) {
            runtime = ClientRuntime.createDefaultWithDeterministicAppearance(
                    new MinecraftGameSessionTokenSource(),
                    new Minecraft262BundledSkinSource(),
                    new Minecraft262CurrentPlayerAppearanceSource(APPEARANCE_SINK::installedSkin),
                    new MinecraftClientExecutor(),
                    new MinecraftFilePicker(),
                    TextResolver.withCatalogTranslations(
                            Minecraft262Components::resolveString,
                            (key, fallback) ->
                                    Language.getInstance().getOrDefault(key, fallback)),
                    new Minecraft262SignedTextureVerifier(),
                    APPEARANCE_SINK,
                    new Minecraft262OuterLayerVisibilityController(),
                    new MinecraftServerAppearanceRefreshNotifier());
        }
        return runtime;
    }

    static boolean closed() {
        return terminallyClosed;
    }

    static synchronized void verifyStorageAccess() {
        runtime().verifyStorageAccess();
    }

    static synchronized void warmup() {
        if (terminallyClosed) {
            return;
        }
        runtime().warmSession();
    }

    static void tick(Minecraft client) {
        Objects.requireNonNull(client, "client");
        final ClientRuntime current;
        synchronized (NclSkins262ClientRuntime.class) {


            if (terminallyClosed) {
                return;
            }
            current = runtime();
        }
        current.tick();
        APPEARANCE_SINK.maintain();

        Object connection = client.getConnection();
        if (connection == null) {
            APPEARANCE_RECONNECTS.disconnected();
            return;
        }
        if (client.player == null) {
            return;
        }
        if (client.getConnection().getPlayerInfo(client.player.getUUID()) == null) {
            return;
        }


        if (!APPEARANCE_RECONNECTS.begin(connection)) {
            return;
        }
        current.afterReconnect();
    }

    static synchronized void close() {
        if (terminallyClosed) {
            return;
        }
        terminallyClosed = true;
        ClientRuntime current = runtime;
        runtime = null;
        if (current != null) {
            current.close();
        }
        APPEARANCE_SINK.close();
        APPEARANCE_RECONNECTS.disconnected();
    }
}

package com.naocraftlab.skins.compat.mc262;

import com.naocraftlab.skins.generated.TargetClientBindings;
import com.naocraftlab.skins.runtime.ClientApplicationHost;
import com.naocraftlab.skins.runtime.ClientCapabilityProvider;
import com.naocraftlab.skins.runtime.ClientRuntime;
import com.naocraftlab.skins.runtime.TextResolver;
import java.util.Objects;
import net.minecraft.client.Minecraft;
import net.minecraft.locale.Language;


final class NclSkins262ClientRuntime {
    private static ClientCapabilityProvider.Provision provision;
    private static ClientApplicationHost<Object> application;
    private static volatile boolean terminallyClosed;

    private NclSkins262ClientRuntime() {}

    static synchronized ClientRuntime runtime() {
        if (terminallyClosed) {
            throw new IllegalStateException("NCL Skins client runtime is terminally closed");
        }
        if (application == null) {
            provision = TargetClientBindings.provision();
            application = new ClientApplicationHost<>(
                    provision.capabilities(),
                    TextResolver.withCatalogTranslations(
                            Minecraft262Components::resolveString,
                            (key, fallback) ->
                                    Language.getInstance().getOrDefault(key, fallback)),
                    provision::closeNative);
        }
        return application.runtime();
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
        runtime();
        application.warmSession();
    }

    static void tick(Minecraft client) {
        Objects.requireNonNull(client, "client");
        final ClientApplicationHost<Object> current;
        final ClientCapabilityProvider.Provision currentProvision;
        synchronized (NclSkins262ClientRuntime.class) {


            if (terminallyClosed) {
                return;
            }
            runtime();
            current = application;
            currentProvision = provision;
        }
        currentProvision.maintain();

        Object connection = client.getConnection();
        boolean playerReady = connection != null
                && client.player != null
                && client.getConnection().getPlayerInfo(client.player.getUUID()) != null;
        current.tick(connection, playerReady);
    }

    static synchronized void close() {
        if (terminallyClosed) {
            return;
        }
        terminallyClosed = true;
        ClientApplicationHost<Object> current = application;
        application = null;
        provision = null;
        if (current != null) {
            current.close();
        }
    }

}

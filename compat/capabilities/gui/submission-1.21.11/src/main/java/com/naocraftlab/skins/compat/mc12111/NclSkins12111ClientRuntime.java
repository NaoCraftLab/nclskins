package com.naocraftlab.skins.compat.mc12111;

import com.naocraftlab.skins.client.FilePicker;
import com.naocraftlab.skins.generated.TargetClientBindings;
import com.naocraftlab.skins.runtime.ClientApplicationHost;
import com.naocraftlab.skins.runtime.ClientCapabilityProvider;
import com.naocraftlab.skins.runtime.ClientRuntime;
import com.naocraftlab.skins.runtime.TextResolver;
import java.nio.file.Path;
import java.util.Objects;
import net.minecraft.client.Minecraft;
import net.minecraft.locale.Language;


final class NclSkins12111ClientRuntime {
    private static ClientCapabilityProvider.Provision provision;
    private static ClientApplicationHost<Object> application;
    private static Path dataRoot;
    private static boolean terminallyClosed;

    private NclSkins12111ClientRuntime() {}

    static synchronized void initialize(Path requestedDataRoot) {
        Path checked = Objects.requireNonNull(requestedDataRoot, "dataRoot");
        if (dataRoot != null && !dataRoot.equals(checked)) {
            throw new IllegalStateException("NCL Skins data root changed during startup");
        }
        dataRoot = checked;
        runtime().verifyStorageAccess();
    }

    static synchronized ClientRuntime runtime() {
        if (terminallyClosed) {
            throw new IllegalStateException("NCL Skins client runtime is terminally closed");
        }
        if (application == null) {
            if (dataRoot == null) {
                throw new IllegalStateException("NCL Skins client is not initialized");
            }
            ensureProvision();
            application = new ClientApplicationHost<>(
                    provision.capabilities(),
                    TextResolver.withCatalogTranslations(
                            Minecraft12111Components::resolveString,
                            (key, fallback) -> Language.getInstance().getOrDefault(key, fallback)),
                    dataRoot,
                    provision::closeNative);
        }
        return application.runtime();
    }

    static synchronized FilePicker nativeFileDialog() {
        ensureProvision();
        return provision.capabilities().nativeFileDialog();
    }

    static synchronized void warmup() {
        if (!terminallyClosed) {
            runtime();
            application.warmSession();
        }
    }

    static void tick(Minecraft minecraft) {
        Objects.requireNonNull(minecraft, "minecraft");
        ClientApplicationHost<Object> current;
        ClientCapabilityProvider.Provision currentProvision;
        synchronized (NclSkins12111ClientRuntime.class) {
            if (terminallyClosed) {
                return;
            }
            runtime();
            current = application;
            currentProvision = provision;
        }
        currentProvision.maintain();
        Object connection = minecraft.getConnection();
        boolean playerReady = connection != null
                && minecraft.player != null
                && minecraft.getConnection().getPlayerInfo(minecraft.player.getUUID()) != null;
        current.tick(connection, playerReady);
    }

    static synchronized boolean closed() {
        return terminallyClosed;
    }

    static synchronized void close() {
        if (terminallyClosed) {
            return;
        }
        terminallyClosed = true;
        ClientApplicationHost<Object> current = application;
        application = null;
        provision = null;
        dataRoot = null;
        if (current != null) {
            current.close();
        }
    }

    private static void ensureProvision() {
        if (terminallyClosed) {
            throw new IllegalStateException("NCL Skins client runtime is terminally closed");
        }
        if (provision == null) {
            provision = TargetClientBindings.provision();
        }
    }
}

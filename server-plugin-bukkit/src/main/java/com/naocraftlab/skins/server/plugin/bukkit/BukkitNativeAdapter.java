package com.naocraftlab.skins.server.plugin.bukkit;

import com.naocraftlab.skins.core.config.ServerConfiguration;
import com.naocraftlab.skins.server.plugin.common.ServerRuntimeIdentity;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.logging.Logger;


public interface BukkitNativeAdapter {
    String id();

    ServerRuntimeIdentity identity();

    AbiVerification verifyAbi(ClassLoader classLoader, String craftServerPackage, Logger logger);

    BukkitRefreshEngine createEngine(
            JavaPlugin plugin,
            ServerConfiguration configuration,
            BukkitRefreshEngine.PublicationListener listener);

    record AbiVerification(boolean compatible, String diagnostic) {
        public static AbiVerification compatible(String diagnostic) {
            return new AbiVerification(true, diagnostic);
        }

        public static AbiVerification incompatible(String diagnostic) {
            return new AbiVerification(false, diagnostic);
        }
    }
}

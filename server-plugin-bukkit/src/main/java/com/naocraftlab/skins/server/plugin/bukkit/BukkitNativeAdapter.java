package com.naocraftlab.skins.server.plugin.bukkit;

import com.naocraftlab.skins.core.config.ServerConfiguration;
import com.naocraftlab.skins.diagnostics.DiagnosticSink;
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
            AbiVerification binding,
            BukkitRefreshEngine.PublicationListener listener,
            DiagnosticSink diagnostics);

    record AbiVerification(
            boolean compatible,
            String diagnostic,
            AuthlibSignatureVerifier signatureVerifier,
            BukkitPublicationBackend publicationBackend,
            BukkitConnectionAssurance connectionAssurance) {
        public static AbiVerification compatible(String diagnostic) {
            return new AbiVerification(true, diagnostic, null, null, null);
        }

        public static AbiVerification compatible(
                String diagnostic, AuthlibSignatureVerifier signatureVerifier) {
            return new AbiVerification(true, diagnostic, signatureVerifier, null, null);
        }

        public static AbiVerification compatible(
                String diagnostic,
                AuthlibSignatureVerifier signatureVerifier,
                BukkitPublicationBackend publicationBackend) {
            return new AbiVerification(
                    true, diagnostic, signatureVerifier, publicationBackend, null);
        }

        public static AbiVerification compatible(
                String diagnostic,
                AuthlibSignatureVerifier signatureVerifier,
                BukkitPublicationBackend publicationBackend,
                BukkitConnectionAssurance connectionAssurance) {
            return new AbiVerification(true, diagnostic, signatureVerifier,
                    publicationBackend, connectionAssurance);
        }

        public static AbiVerification incompatible(String diagnostic) {
            return new AbiVerification(false, diagnostic, null, null, null);
        }
    }
}

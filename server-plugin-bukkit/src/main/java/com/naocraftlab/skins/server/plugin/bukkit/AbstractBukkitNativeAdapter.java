package com.naocraftlab.skins.server.plugin.bukkit;

import com.naocraftlab.skins.core.config.ServerConfiguration;
import com.naocraftlab.skins.diagnostics.DiagnosticSink;
import com.naocraftlab.skins.server.plugin.common.ServerRuntimeIdentity;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Objects;
import java.util.logging.Logger;


public abstract class AbstractBukkitNativeAdapter implements BukkitNativeAdapter {
    private final String id;
    private final ServerRuntimeIdentity identity;
    private final String authlibFamily;

    protected AbstractBukkitNativeAdapter(
            String id,
            ServerRuntimeIdentity identity,
            String authlibFamily) {
        this.id = Objects.requireNonNull(id, "id");
        this.identity = Objects.requireNonNull(identity, "identity");
        this.authlibFamily = Objects.requireNonNull(authlibFamily, "authlibFamily");
    }

    @Override
    public final String id() {
        return id;
    }

    @Override
    public final ServerRuntimeIdentity identity() {
        return identity;
    }

    @Override
    public final AbiVerification verifyAbi(
            ClassLoader classLoader,
            String craftServerPackage,
            Logger logger) {
        Objects.requireNonNull(classLoader, "classLoader");
        Objects.requireNonNull(craftServerPackage, "craftServerPackage");
        Objects.requireNonNull(logger, "logger");
        try {
            boolean paperFamily = identity.family() == ServerRuntimeIdentity.Family.PAPER
                    || identity.family() == ServerRuntimeIdentity.Family.PURPUR
                    || identity.family() == ServerRuntimeIdentity.Family.FOLIA;
            if (paperFamily) {
                AbiVerification exact = verifyExactAbi(
                        classLoader, craftServerPackage, Object.class, logger);
                return exact.compatible() ? bind(
                        exact.diagnostic(), exact.signatureVerifier(),
                        PaperProfilePublicationBackend.resolve(
                                classLoader, craftServerPackage, authlibFamily),
                        PaperConnectionAssuranceBinding.resolve(classLoader)) : exact;
            }
            AbiVerification exact = verifyExactAbi(
                    classLoader, craftServerPackage, Object.class, logger);
            return exact.compatible() ? bind(
                    exact.diagnostic(), exact.signatureVerifier(),
                    exact.publicationBackend(), new LegacyConnectionAssurance()) : exact;
        } catch (ReflectiveOperationException | LinkageError failure) {
            return AbiVerification.incompatible(
                    id + " missing exact ABI leaf: " + failure.getClass().getSimpleName());
        }
    }

    protected abstract AbiVerification verifyExactAbi(
            ClassLoader classLoader,
            String craftServerPackage,
            Class<?> serverPlayerClass,
            Logger logger) throws ReflectiveOperationException;

    @Override
    public final BukkitRefreshEngine createEngine(
            JavaPlugin plugin,
            ServerConfiguration configuration,
            AbiVerification binding,
            BukkitRefreshEngine.PublicationListener listener,
            DiagnosticSink diagnostics) {
        AbiVerification checkedBinding = Objects.requireNonNull(binding, "binding");
        if (!checkedBinding.compatible()) {
            throw new IllegalArgumentException("Cannot create an engine from an incompatible ABI");
        }
        return new BukkitAppearanceRefreshEngine(
                plugin, configuration, identity.threadingModel()
                == ServerRuntimeIdentity.ThreadingModel.REGIONIZED,
                checkedBinding.signatureVerifier(),
                checkedBinding.publicationBackend(),
                checkedBinding.connectionAssurance(),
                listener,
                diagnostics);
    }

    protected boolean usesLegacyRuntimeMappings() {
        return false;
    }

    protected final AbiVerification requireProfilePropertyApi(
            ClassLoader classLoader,
            String craftServerPackage,
            String expectedAuthlibFamily) throws ReflectiveOperationException {
        AuthlibSignatureVerifier verifier = ExactAuthlibSignatureVerifier.resolve(
                classLoader, craftServerPackage,
                expectedAuthlibFamily, usesLegacyRuntimeMappings());
        return AbiVerification.compatible(
                id + " authlib=" + expectedAuthlibFamily, verifier);
    }

    private static AbiVerification bind(
            String diagnostic,
            AuthlibSignatureVerifier signatureVerifier,
            BukkitPublicationBackend publicationBackend,
            BukkitConnectionAssurance connectionAssurance) {
        return AbiVerification.compatible(diagnostic, signatureVerifier,
                publicationBackend, connectionAssurance);
    }
}

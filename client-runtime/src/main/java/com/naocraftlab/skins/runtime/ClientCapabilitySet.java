package com.naocraftlab.skins.runtime;

import com.naocraftlab.skins.client.ClientExecutor;
import com.naocraftlab.skins.client.CurrentPlayerAppearanceSource;
import com.naocraftlab.skins.client.FilePicker;
import com.naocraftlab.skins.client.GameSessionTokenSource;
import com.naocraftlab.skins.client.OuterLayerVisibilityController;
import com.naocraftlab.skins.client.PlayerAppearanceSink;
import com.naocraftlab.skins.client.ServerAppearanceRefreshNotifier;
import com.naocraftlab.skins.client.SignedTextureVerifier;
import com.naocraftlab.skins.client.SkinCatalogSource;
import com.naocraftlab.skins.client.SkinExtensionEnvironmentSource;
import com.naocraftlab.skins.core.config.ClientConfiguration;
import com.naocraftlab.skins.diagnostics.DiagnosticSink;

import java.nio.file.Path;
import java.util.Objects;
import java.util.function.Supplier;


public record ClientCapabilitySet(
        GameSessionTokenSource session,
        SkinCatalogSource resourcePackAccess,
        CurrentPlayerAppearanceSource currentAppearance,
        ClientExecutor clientExecutor,
        FilePicker nativeFileDialog,
        SignedTextureVerifier signedTextureVerification,
        PlayerAppearanceSink<AcknowledgedAppearanceAssets> appearanceInstall,
        OuterLayerVisibilityController modelParts,
        ServerAppearanceRefreshNotifier serverSignal,
        SkinExtensionEnvironmentSource skinExtensionEnvironment) {

    public ClientCapabilitySet {
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(resourcePackAccess, "resourcePackAccess");
        Objects.requireNonNull(currentAppearance, "currentAppearance");
        Objects.requireNonNull(clientExecutor, "clientExecutor");
        Objects.requireNonNull(nativeFileDialog, "nativeFileDialog");
        Objects.requireNonNull(signedTextureVerification, "signedTextureVerification");
        Objects.requireNonNull(appearanceInstall, "appearanceInstall");
        Objects.requireNonNull(modelParts, "modelParts");
        Objects.requireNonNull(serverSignal, "serverSignal");
        Objects.requireNonNull(skinExtensionEnvironment, "skinExtensionEnvironment");
    }

    public ClientCapabilitySet(
            GameSessionTokenSource session,
            SkinCatalogSource resourcePackAccess,
            CurrentPlayerAppearanceSource currentAppearance,
            ClientExecutor clientExecutor,
            FilePicker nativeFileDialog,
            SignedTextureVerifier signedTextureVerification,
            PlayerAppearanceSink<AcknowledgedAppearanceAssets> appearanceInstall,
            OuterLayerVisibilityController modelParts,
            ServerAppearanceRefreshNotifier serverSignal) {
        this(
                session,
                resourcePackAccess,
                currentAppearance,
                clientExecutor,
                nativeFileDialog,
                signedTextureVerification,
                appearanceInstall,
                modelParts,
                serverSignal,
                SkinExtensionEnvironmentSource.unknown());
    }

    public ClientRuntime createRuntime(
            TextResolver textResolver, Path dataRoot, DiagnosticSink diagnostics) {
        return createRuntime(
                textResolver, dataRoot, ClientConfiguration::defaults, diagnostics);
    }

    public ClientRuntime createRuntime(
            TextResolver textResolver,
            Path dataRoot,
            Supplier<ClientConfiguration> configurationSource,
            DiagnosticSink diagnostics) {
        return ClientRuntime.createDefaultWithDeterministicAppearance(
                session,
                resourcePackAccess,
                Objects.requireNonNull(dataRoot, "dataRoot"),
                currentAppearance,
                clientExecutor,
                nativeFileDialog,
                Objects.requireNonNull(textResolver, "textResolver"),
                signedTextureVerification,
                appearanceInstall,
                modelParts,
                serverSignal,
                Objects.requireNonNull(diagnostics, "diagnostics"))
                .useConfigurationSource(configurationSource)
                .useSkinExtensionEnvironmentSource(skinExtensionEnvironment);
    }
}

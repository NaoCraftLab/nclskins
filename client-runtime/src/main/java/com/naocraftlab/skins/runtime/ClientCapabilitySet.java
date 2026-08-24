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
import com.naocraftlab.skins.diagnostics.DiagnosticSink;

import java.nio.file.Path;
import java.util.Objects;


public record ClientCapabilitySet(
        GameSessionTokenSource session,
        SkinCatalogSource resourcePackAccess,
        CurrentPlayerAppearanceSource currentAppearance,
        ClientExecutor clientExecutor,
        FilePicker nativeFileDialog,
        SignedTextureVerifier signedTextureVerification,
        PlayerAppearanceSink<AcknowledgedAppearanceAssets> appearanceInstall,
        OuterLayerVisibilityController modelParts,
        ServerAppearanceRefreshNotifier serverSignal) {

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
    }

    public ClientRuntime createRuntime(
            TextResolver textResolver, Path dataRoot, DiagnosticSink diagnostics) {
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
                Objects.requireNonNull(diagnostics, "diagnostics"));
    }
}

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

}

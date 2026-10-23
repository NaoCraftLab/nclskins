package com.naocraftlab.skins.runtime;

import com.naocraftlab.skins.client.GameSessionTokenSource;
import com.naocraftlab.skins.client.OuterLayerVisibility;
import com.naocraftlab.skins.client.SkinCatalogSource;
import com.naocraftlab.skins.client.SkinModel;
import com.naocraftlab.skins.core.compatibility.SkinFeatureEvidence;
import com.naocraftlab.skins.core.importing.ExternalImportProbe;
import com.naocraftlab.skins.core.importing.ExternalImportSource;
import com.naocraftlab.skins.core.model.AccountState;
import com.naocraftlab.skins.core.model.AccountUiPreferences;
import com.naocraftlab.skins.core.model.AddSourceTab;
import com.naocraftlab.skins.core.model.AppearanceSyncStatus;
import com.naocraftlab.skins.core.model.CatalogOrigin;
import com.naocraftlab.skins.core.model.EditorTab;
import com.naocraftlab.skins.core.model.OwnedCapeInventory;
import com.naocraftlab.skins.core.model.PersonalSkinSource;
import com.naocraftlab.skins.core.model.SkinReference;
import com.naocraftlab.skins.core.model.SkinVariant;
import com.naocraftlab.skins.core.provider.AppearanceProviders;
import com.naocraftlab.skins.core.provider.BuiltinProvider;
import com.naocraftlab.skins.core.provider.ProviderObservation;
import com.naocraftlab.skins.core.service.AppliedAppearance;
import com.naocraftlab.skins.core.service.PresetApplicationOutcome;
import com.naocraftlab.skins.core.service.SessionValidation;

import java.nio.file.Path;
import java.util.function.Consumer;
import com.naocraftlab.skins.core.provider.ProviderCape;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;


interface TestCapeOperations extends ClientOperations {
    @Override
    default Optional<DurableAppearance> durableAppearance() throws Exception {
        return Optional.empty();
    }

    @Override
    default Optional<FrozenCatalogSelection> freezeCatalogSelection(String collectionId, String skinId) throws Exception {
        throw new UnsupportedOperationException("This test fixture has no skin catalog");
    }

    default void startOptiFineCapes() {}

    default long capeObservationGeneration() { return 0; }

    default void refreshCapeProvider(ProviderRefreshUseCases.Scope scope, BuiltinProvider provider,
            Consumer<ProviderObservation<ProviderCape>> completion) {
        completion.accept(null);
    }

    default void selfCapeCandidatesChanged(UUID accountId, String canonicalName, AppearanceProviders providers,
            java.util.Set<BuiltinProvider> allowedSources) {}

    static ProviderRefreshResult refreshSource(ClientOperations operations, AppearanceProviders.Component component) throws Exception {
        return refreshSource(operations, ProviderRefreshRequest.all(component));
    }

    static ProviderRefreshResult refreshSource(ClientOperations operations, ProviderRefreshRequest request) throws Exception {
        var before = operations.loadProviders();
        return operations.refreshProviderSources(new ProviderRefreshUseCases.Scope(request, operations.sessionIdentity(),
                before, request.resolve(before), 0));
    }

    default Optional<Duration> capeProviderCooldown(BuiltinProvider provider) {
        return Optional.empty();
    }

    default void optiFineConfigurationChanged() {}

    default void adoptSharedCapeObservation(UUID accountId, String canonicalName,
            AppearanceProviders providers) {}

    default void onCapeObservation(Consumer<Observation> listener) {}

    default void selfCapeCandidatesChanged(UUID accountId, String canonicalName,
            AppearanceProviders providers) {}

    default void trackedCapePlayer(UUID profileId, String canonicalName) {}

    default void untrackedCapePlayer(UUID profileId) {}

    default void capeWorldChanged() {}

    default void closeOptiFineCapes() {}


    default Optional<AccountState> reloadEditorAccount(UUID accountId) throws Exception {
        return Optional.empty();
    }

    default Optional<AccountState> discardCapeIfUnreferenced(UUID accountId, UUID entryId)
            throws Exception {
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(entryId, "entryId");
        return Optional.empty();
    }

    default AccountState removePersonalSkin(String sha256) throws Exception {
        throw new UnsupportedOperationException("Personal skin catalog is unavailable");
    }

    default AccountState renamePersonalSkin(String sha256, String newName) throws Exception {
        throw new UnsupportedOperationException("Personal skin catalog is unavailable");
    }

    default com.naocraftlab.skins.core.model.PersonalCapeEntry importCape(UUID accountId, java.nio.file.Path path, String fallbackName) throws Exception {
        return importCape(path);
    }

    default AccountState renameCape(UUID accountId, UUID entryId, String name) throws Exception { return renameCape(entryId, name); }

    default CapeDeletion deleteCape(UUID accountId, UUID entryId) throws Exception { return deleteCape(entryId); }

    default com.naocraftlab.skins.core.model.PersonalCapeEntry importCape(java.nio.file.Path path) throws Exception {
        throw new UnsupportedOperationException();
    }

    default AccountState renameCape(UUID entryId, String name) throws Exception { throw new UnsupportedOperationException(); }

    default CapeDeletion deleteCape(UUID entryId) throws Exception { throw new UnsupportedOperationException(); }

    default Optional<AccountUiPreferences> loadUiPreferences() throws Exception {
        return Optional.empty();
    }

    default void setSelectedProvidersTab(UUID accountId, AppearanceProviders.Component tab) throws Exception {
    }

    default void setSelectedAddSourceTab(UUID accountId, AddSourceTab tab) throws Exception {
        setSelectedAddSourceTab(tab);
    }

    default void setSelectedAddSourceTab(AddSourceTab tab) throws Exception {
        Objects.requireNonNull(tab, "tab");
    }

    default void setCollapsedCapeCollections(UUID accountId, Set<String> values) throws Exception {}

    default void setSelectedEditorTab(UUID accountId, EditorTab tab) throws Exception {
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(tab, "tab");
    }

    default void setCollectionCollapsed(String collectionId, boolean collapsed) throws Exception {
        Objects.requireNonNull(collectionId, "collectionId");
    }

    default void replaceCollapsedCollectionIds(Set<String> collectionIds) throws Exception {
        Objects.requireNonNull(collectionIds, "collectionIds");
        for (String collectionId : collectionIds) {
            if (Objects.requireNonNull(collectionId, "collectionIds contains null").isBlank()) {
                throw new IllegalArgumentException("collectionId must not be blank");
            }
        }
    }

    default void setPreferredSkinVariant(SkinVariant variant) throws Exception {
        Objects.requireNonNull(variant, "variant");
    }

    default AppearanceProviders loadProviders() throws Exception {
        return AppearanceProviders.initial();
    }

    default DurableAppearance reloadProviders() throws Exception {
        return durableAppearance().orElseThrow();
    }

    default boolean providerRefreshCurrent(ProviderRefreshUseCases.Scope scope) {
        try { return scope.identity().equals(sessionIdentity()) && scope.configurationMatches(loadProviders()); }
        catch (Exception unavailable) { return false; }
    }

    default ProviderRefreshResult refreshProviderSources(ProviderRefreshUseCases.Scope scope) throws Exception {
        return new ProviderRefreshResult(scope, Optional.of(reloadProviders()), java.util.Map.of(), false);
    }

    default DurableAppearance enableProvider(AppearanceProviders.Component component, BuiltinProvider provider)
            throws Exception {
        throw new UnsupportedOperationException("Provider configuration is unavailable");
    }

    default DurableAppearance disableProvider(AppearanceProviders.Component component, BuiltinProvider provider)
            throws Exception {
        throw new UnsupportedOperationException("Provider configuration is unavailable");
    }

    default DurableAppearance moveProvider(
            AppearanceProviders.Component component, BuiltinProvider provider, int direction) throws Exception {
        throw new UnsupportedOperationException("Provider configuration is unavailable");
    }

    default DurableAppearance enableProvider(UUID accountId, AppearanceProviders.Component component, BuiltinProvider provider) throws Exception {
        return enableProvider(component, provider);
    }

    default DurableAppearance disableProvider(UUID accountId, AppearanceProviders.Component component, BuiltinProvider provider) throws Exception {
        return disableProvider(component, provider);
    }

    default DurableAppearance moveProvider(UUID accountId, AppearanceProviders.Component component, BuiltinProvider provider, int direction) throws Exception {
        return moveProvider(component, provider, direction);
    }

    default Optional<Duration> rateLimitRemaining() {
        return rateLimited()
                ? Optional.of(Duration.ofSeconds(1))
                : Optional.empty();
    }

    default Optional<byte[]> loadProviderTexture(String cacheKey, boolean skin) throws Exception {
        return Optional.empty();
    }

    default CapeEditorData loadCapeEditorData(UUID accountId) throws Exception {
        Optional<AccountState> account = reloadEditorAccount(accountId);
        return new CapeEditorData(
                account.orElseThrow(() -> new IllegalStateException("Cape editor account is unavailable")),
                List.of(),
                Map.of(),
                Long.MIN_VALUE);
    }

    default long capeCatalogGeneration() {
        return Long.MIN_VALUE;
    }

    default void warmResourceCapeCatalog(long generation) throws Exception {
    }

    default void warmCapeCatalog(UUID accountId, long generation) throws Exception {
        Objects.requireNonNull(accountId, "accountId");
    }

    default Optional<CapeEditorData> warmedCapeEditorData(UUID accountId) {
        Objects.requireNonNull(accountId, "accountId");
        return Optional.empty();
    }

    default Map<String, byte[]> warmedCapePreviews(UUID accountId) {
        Objects.requireNonNull(accountId, "accountId");
        return Map.of();
    }

    default Optional<byte[]> loadResourceCapePreview(
            UUID accountId, ResourceCapeSelection selection) throws Exception {
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(selection, "selection");
        return Optional.empty();
    }

    default com.naocraftlab.skins.core.model.PersonalCapeEntry materializeResourceCape(
            UUID accountId, ResourceCapeSelection selection) throws Exception {
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(selection, "selection");
        throw new UnsupportedOperationException("Resource-pack cape materialization is unavailable");
    }

    default List<SkinCatalogSource.CollectionDescriptor> catalogCollections() throws Exception {
        return List.of();
    }

    default Map<CatalogVariant, SkinFeatureEvidence> catalogFeatureEvidence() {
        return Map.of();
    }

    default byte[] loadCatalogSkin(String collectionId, String skinId, SkinModel model)
            throws Exception {
        throw new UnsupportedOperationException("Skin catalog is unavailable");
    }

    default Optional<UUID> reusableCatalogSkinAsset(
            String collectionId, String skinId, SkinModel model) throws Exception {
        Objects.requireNonNull(collectionId, "collectionId");
        Objects.requireNonNull(skinId, "skinId");
        Objects.requireNonNull(model, "model");
        return Optional.empty();
    }

    default ImportDraft loadPlayerSkin(String playerNameOrUuid) throws Exception {
        throw new UnsupportedOperationException("Public player skin lookup is unavailable");
    }

    default ImportDraft loadUrlSkin(String url) throws Exception {
        throw new UnsupportedOperationException("Remote PNG import is unavailable");
    }

    @Override
    default AcquiredInput acquireFile(java.nio.file.Path path,
            java.util.Optional<com.naocraftlab.skins.client.CpmModelFileConversion> converter,
            com.naocraftlab.skins.client.CpmModelFileConversion.Reason unavailable) throws Exception {
        return PublicSkinImportService.admitInput(
                com.naocraftlab.skins.core.api.PublicSkinImageAdapter.acquireLocal(path), converter, unavailable,
                UntrustedDisplayName.fromFileName(path.getFileName().toString(), "Imported skin"),
                com.naocraftlab.skins.core.model.PersonalSkinSource.FILE);
    }

    @Override
    default AcquiredInput acquireUrl(String url,
            java.util.Optional<com.naocraftlab.skins.client.CpmModelFileConversion> converter,
            com.naocraftlab.skins.client.CpmModelFileConversion.Reason unavailable) throws Exception {
        return new AcquiredSkin(loadUrlSkin(url));
    }

    default ExternalImportProbe probeExternalSource(
            ExternalImportSource source, Optional<Path> selectedRoot) throws Exception {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(selectedRoot, "selectedRoot");
        return ExternalImportProbe.UNAVAILABLE;
    }

    default ExternalImportReview prepareExternalAppearances(
            ExternalImportSource source, Optional<Path> selectedRoot) throws Exception {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(selectedRoot, "selectedRoot");
        throw new UnsupportedOperationException("External appearance preparation is unavailable");
    }

    default ExternalImportResult commitExternalAppearances(
            List<ExternalImportCandidate> selected, int skipped, int warnings) throws Exception {
        Objects.requireNonNull(selected, "selected");
        throw new UnsupportedOperationException("External appearance import is unavailable");
    }

    @Override
    default void close() {}
    default void rememberActivePreset(UUID accountId, Optional<UUID> presetId) {
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(presetId, "presetId");
    }
    default void warmOwnedCapeCache() throws Exception {}
    default void warmSession() throws Exception {}
    default void verifyStorageAccess() throws Exception {}
    @Override
    default DurableAppearance enableProvider(UUID accountId, AppearanceProviders.Component component,
            BuiltinProvider provider, long revision) throws Exception {
        return enableProvider(accountId, component, provider);
    }
    @Override
    default DurableAppearance disableProvider(UUID accountId, AppearanceProviders.Component component,
            BuiltinProvider provider, long revision) throws Exception {
        return disableProvider(accountId, component, provider);
    }
    @Override
    default DurableAppearance moveProvider(UUID accountId, AppearanceProviders.Component component,
            BuiltinProvider provider, int direction, long revision) throws Exception {
        return moveProvider(accountId, component, provider, direction);
    }
}

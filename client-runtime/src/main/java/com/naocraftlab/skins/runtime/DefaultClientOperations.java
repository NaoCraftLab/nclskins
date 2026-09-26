package com.naocraftlab.skins.runtime;

import com.naocraftlab.skins.core.reconciliation.ReconciliationPolicy.Trigger;
import static com.naocraftlab.skins.runtime.AccountMutationExecutor.needsMinecraftDelivery;
import com.naocraftlab.skins.runtime.AccountSessionAdapter.*;
import static com.naocraftlab.skins.runtime.AccountDeliveryService.*;
import com.naocraftlab.skins.runtime.AccountReconciliationEffects.ObservedAccount;
import com.naocraftlab.skins.client.BundledSkinSource;
import com.naocraftlab.skins.client.ClientExecutor;
import com.naocraftlab.skins.client.GameSessionTokenSource;
import com.naocraftlab.skins.client.GameSessionIdentityChangedException;
import com.naocraftlab.skins.client.GameSessionTokenUnavailableException;
import com.naocraftlab.skins.client.OuterLayerVisibility;
import com.naocraftlab.skins.client.PlayerAppearanceSink;
import com.naocraftlab.skins.client.SignedTextureVerifier;
import com.naocraftlab.skins.client.SkinCatalogSource;
import com.naocraftlab.skins.client.SkinModel;
import com.naocraftlab.skins.core.api.ApiFailureKind;
import com.naocraftlab.skins.core.api.MinecraftProfileApi;
import com.naocraftlab.skins.core.api.ProfileApi;
import com.naocraftlab.skins.core.compatibility.SkinFeatureEvidence;
import com.naocraftlab.skins.core.importing.ExternalImportContext;
import com.naocraftlab.skins.core.importing.ExternalImportProbe;
import com.naocraftlab.skins.core.importing.ExternalImportSource;
import com.naocraftlab.skins.core.model.AccountAppearanceState;
import com.naocraftlab.skins.core.model.AccountState;
import com.naocraftlab.skins.core.model.AccountUiPreferences;
import com.naocraftlab.skins.core.model.AddSourceTab;
import com.naocraftlab.skins.core.model.AppearancePreset;
import com.naocraftlab.skins.core.model.AppearanceSyncStatus;
import com.naocraftlab.skins.core.model.EditorTab;
import com.naocraftlab.skins.core.model.MutationResult;
import com.naocraftlab.skins.core.model.OwnedCapeEntry;
import com.naocraftlab.skins.core.model.OwnedCapeInventory;
import com.naocraftlab.skins.core.model.RemoteCape;
import com.naocraftlab.skins.core.model.RemoteProfile;
import com.naocraftlab.skins.core.model.RemoteSkin;
import com.naocraftlab.skins.core.model.SkinAsset;
import com.naocraftlab.skins.core.model.SkinReference;
import com.naocraftlab.skins.core.model.SkinSource;
import com.naocraftlab.skins.core.model.SkinVariant;
import com.naocraftlab.skins.core.png.PngValidationException;
import com.naocraftlab.skins.core.png.PngValidator;
import com.naocraftlab.skins.core.provider.AppearanceProviders;
import com.naocraftlab.skins.core.provider.BuiltinProvider;
import com.naocraftlab.skins.core.provider.ProviderCape;
import com.naocraftlab.skins.core.provider.ProviderObservation;
import com.naocraftlab.skins.core.provider.ProviderChannel;
import com.naocraftlab.skins.core.provider.ProviderDelivery;
import com.naocraftlab.skins.core.provider.ProviderSkin;
import com.naocraftlab.skins.core.service.AppearanceMutationService;
import com.naocraftlab.skins.core.service.ApplicationPhase;
import com.naocraftlab.skins.core.service.AppliedAppearance;
import com.naocraftlab.skins.core.service.ImportedSkin;
import com.naocraftlab.skins.core.service.LibraryService;
import com.naocraftlab.skins.core.service.PresetApplicationOutcome;
import com.naocraftlab.skins.core.service.PresetApplicationRequest;
import com.naocraftlab.skins.core.service.RemoteAppearanceImpact;
import com.naocraftlab.skins.core.service.RemoteSessionGate;
import com.naocraftlab.skins.core.service.ResolvedSkinAsset;
import com.naocraftlab.skins.core.service.SavedImportedPreset;
import com.naocraftlab.skins.core.service.SavedPersonalSkinPreset;
import com.naocraftlab.skins.core.service.SessionStatus;
import com.naocraftlab.skins.core.service.SessionValidation;
import com.naocraftlab.skins.core.service.SessionValidationService;
import com.naocraftlab.skins.core.storage.NclSkinsStorage;
import com.naocraftlab.skins.core.storage.TextureCache;

import java.io.IOException;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

public final class DefaultClientOperations implements ClientOperations {
    private static final int MAX_BOOTSTRAP_CAS_ATTEMPTS = 3;
    private final GameSessionTokenSource tokenSource;
    private final com.naocraftlab.skins.core.service.ProfileSessionPort profileApi;
    private final com.naocraftlab.skins.core.service.AccountAppearanceStore storage;
    private final com.naocraftlab.skins.core.service.AccountBootstrapPort bootstrap;
    private final com.naocraftlab.skins.core.service.AssetStorePort assets;
    private final com.naocraftlab.skins.core.service.LibraryStatePort libraryState;
    private final UiPreferencesPort uiPreferences;
    private final LocalCapeImportSource capeImports;
    private final SkinCatalogSource bundledSkins;
    private final Clock clock;
    private final AccountDeliveryService delivery;
    private final AccountMutationExecutor accountMutations;
    private final LibraryService library;
    private final RemoteSessionGate sessionGate;
    private final SessionValidationService sessions;
    private final AppearanceMutationService mutations;
    private final com.naocraftlab.skins.core.service.ProviderTextureStore textures;
    private final java.util.function.Function<Executor, DeterministicAppearanceAssetResolver> resolverFactory;
    private final PublicSkinImportService publicImports;
    private final ExternalAppearanceImportService externalImports;
    private final OfficialSkinTextureSource officialSkinTextures;
    private final OfficialSkinClassifier officialSkinClassifier;
    private volatile ResolvedOfficialSkin resolvedOfficialSkin;
    private volatile CapeProviderCoordinator optifineCapes;
    private volatile ExecutorService optifineWorker;

    private final Map<UUID, LibraryObservation> libraryObservations = new ConcurrentHashMap<>();

    private final PreparedCatalogService preparedCatalog;

    private volatile InitialData preparedInitialData;
    private volatile UUID startupObservedAccount;

    public DefaultClientOperations(
            GameSessionTokenSource tokenSource,
            ProfileApi profileApi,
            NclSkinsStorage storage,
            SkinCatalogSource bundledSkins,
            Clock clock) {
        this(tokenSource, profileApi, storage, bundledSkins, clock, null);
    }

    public DefaultClientOperations(
            GameSessionTokenSource tokenSource,
            ProfileApi profileApi,
            NclSkinsStorage storage,
            BundledSkinSource bundledSkins,
            Clock clock) {
        this(tokenSource, profileApi, storage, (SkinCatalogSource) bundledSkins, clock);
    }

    DefaultClientOperations(
            GameSessionTokenSource tokenSource,
            ProfileApi profileApi,
            NclSkinsStorage storage,
            SkinCatalogSource bundledSkins,
            Clock clock,
            OfficialSkinTextureSource officialSkinTextures) {
        this.tokenSource = Objects.requireNonNull(tokenSource, "tokenSource");
        this.profileApi = new com.naocraftlab.skins.core.api.ProfileSessionAdapter(profileApi);
        this.storage = new com.naocraftlab.skins.core.storage.AccountAppearanceStorageAdapter(storage);
        this.bootstrap = new com.naocraftlab.skins.core.storage.AccountBootstrapAdapter(storage);
        this.assets = new com.naocraftlab.skins.core.storage.LibraryStorageAdapter(storage);
        this.libraryState = new com.naocraftlab.skins.core.storage.LibraryStorageAdapter(storage);
        this.capeImports = new LocalCapeImportAdapter();
        this.uiPreferences = new UiPreferencesStorageAdapter(storage,
                () -> resolveAccountId(pinCurrentSession().identity()));
        this.bundledSkins = Objects.requireNonNull(bundledSkins, "bundledSkins");
        this.officialSkinClassifier = new OfficialSkinClassifier(bundledSkins);
        this.clock = Objects.requireNonNull(clock, "clock");
        this.delivery = new AccountDeliveryService(this.storage, clock);
        this.accountMutations = new AccountMutationExecutor(this.storage, this.assets, clock, delivery);
        var libraryStorage = new com.naocraftlab.skins.core.storage.LibraryStorageAdapter(storage);
        this.library = new LibraryService(libraryStorage, libraryStorage, clock);
        this.sessionGate = new RemoteSessionGate();
        this.sessions = new SessionValidationService(this.profileApi, sessionGate);
        this.mutations = new AppearanceMutationService(this.profileApi, new com.naocraftlab.skins.core.storage.MutationStorageAdapter(storage), new com.naocraftlab.skins.core.storage.MutationStorageAdapter(storage), sessionGate, sessions);
        TextureCache textureCache = new TextureCache(storage);
        this.textures = new com.naocraftlab.skins.core.storage.ProviderTextureStorageAdapter(storage, textureCache);
        this.resolverFactory = worker -> new DeterministicAppearanceAssetResolver(tokenSource, storage, textureCache, worker);
        CatalogAccountAccess catalogAccounts = new LibraryCatalogAdapter(library, libraryStorage, libraryStorage,
                () -> resolveAccountId(pinCurrentSession().identity()));
        this.preparedCatalog = new PreparedCatalogService(bundledSkins, catalogAccounts);
        this.publicImports = new PublicSkinImportService(textureCache, this::loadCatalogSkin);
        this.externalImports = new ExternalAppearanceImportService(
                new ExternalImportSourceAdapter(this.publicImports, this.bundledSkins),
                this.preparedCatalog, new LibraryExternalImportAdapter(this.library, catalogAccounts));
        this.officialSkinTextures = officialSkinTextures != null
                ? officialSkinTextures
                : skin -> this.textures.load(skin.textureUri());
    }

    DefaultClientOperations(
            GameSessionTokenSource tokenSource,
            ProfileApi profileApi,
            NclSkinsStorage storage,
            BundledSkinSource bundledSkins,
            Clock clock,
            OfficialSkinTextureSource officialSkinTextures) {
        this(
                tokenSource,
                profileApi,
                storage,
                (SkinCatalogSource) bundledSkins,
                clock,
                officialSkinTextures);
    }

    public static DefaultClientOperations createDefault(
            GameSessionTokenSource tokenSource,
            SkinCatalogSource bundledSkins,
            Path dataRoot) {
        return new DefaultClientOperations(
                tokenSource,
                new MinecraftProfileApi(),
                new NclSkinsStorage(
                        Objects.requireNonNull(dataRoot, "dataRoot"),
                        new PngValidator(),
                        Clock.systemUTC()),
                bundledSkins,
                Clock.systemUTC());
    }

    DefaultClientOperations enablePublicImports(SignedTextureVerifier verifier) {
        publicImports.enablePlayerLookup(Objects.requireNonNull(verifier, "verifier"));
        return this;
    }

    public DefaultClientOperations attachOptifineCapes(PlayerAppearanceSink<?> sink,
            ClientExecutor clientExecutor) {
        if (optifineCapes != null) {
            throw new IllegalStateException("OptiFine cape coordinator already attached");
        }
        AtomicInteger threadIndex = new AtomicInteger();
        optifineWorker = new java.util.concurrent.ThreadPoolExecutor(4, 4, 0L,
                java.util.concurrent.TimeUnit.MILLISECONDS, new java.util.concurrent.ArrayBlockingQueue<>(32), action -> {
            Thread thread = new Thread(action, "nclskins-public-cape-" + threadIndex.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
        optifineCapes = new CapeProviderCoordinator(tokenSource, storage, assets, textures,
                sink, clientExecutor, optifineWorker, new OptifineCapeReader(), new SkinMcCapeReader(), this::verifiedOfficialCapeUri);
        return this;
    }

    private CapeProviderCoordinator capeCoordinator() {
        return Objects.requireNonNull(optifineCapes, "Cape providers must be attached before runtime use");
    }

    @Override
    public void startOptiFineCapes() {
        capeCoordinator().start();
    }

    @Override
    public void refreshOptiFineCapes() {
        capeCoordinator().refresh();
    }

    @Override
    public void refreshOptiFineCapes(Consumer<ProviderObservation<ProviderCape>> completion) {
        capeCoordinator().refresh(completion);
    }

    @Override
    public void refreshSkinMcCapes(Consumer<ProviderObservation<ProviderCape>> completion) {
        capeCoordinator().refreshSkinMc(completion);
    }

    @Override
    public Optional<java.time.Duration> capeProviderCooldown(BuiltinProvider provider) {
        return capeCoordinator().cooldownRemaining(provider);
    }

    @Override
    public void optiFineConfigurationChanged() {
        capeCoordinator().configurationChanged();
    }

    @Override
    public void adoptSharedCapeObservation(UUID accountId, String canonicalName,
            AppearanceProviders providers) {
        capeCoordinator().adoptSharedSnapshot(accountId, canonicalName, providers);
    }

    @Override
    public void onCapeObservation(Consumer<CapeObservationPort.Observation> listener) {
        capeCoordinator().onCapeObservation(listener);
    }

    @Override
    public void selfCapeCandidatesChanged(UUID accountId, String canonicalName,
            AppearanceProviders providers) {
        capeCoordinator().selfCapeCandidatesChanged(accountId,
                canonicalName, providers);
    }

    @Override
    public void trackedCapePlayer(UUID profileId, String canonicalName) {
        capeCoordinator().trackedPlayer(profileId, canonicalName);
    }

    @Override
    public void untrackedCapePlayer(UUID profileId) {
        capeCoordinator().untrackedPlayer(profileId);
    }

    @Override
    public void capeWorldChanged() {
        capeCoordinator().worldChanged();
    }

    @Override
    public void closeOptiFineCapes() {
        if (optifineCapes != null) optifineCapes.close();
        if (optifineWorker != null) optifineWorker.shutdownNow();
    }

    private Optional<java.net.URI> verifiedOfficialCapeUri(UUID accountId, String capeId) {
        GameSessionTokenSource.SessionIdentity identity = tokenSource.currentSession();
        if (!accountId.equals(identity.profileId())) return Optional.empty();
        SessionValidation validation = sessions.cachedStatus(identity);
        if (!validation.valid() || validation.profile() == null
                || !accountId.equals(validation.profile().id())) return Optional.empty();
        return validation.profile().capes().stream()
                .filter(cape -> cape.id().equals(capeId))
                .map(RemoteCape::textureUri)
                .findFirst();
    }

    public DeterministicAppearanceAssetResolver deterministicAppearanceResolver(Executor worker) {
        return resolverFactory.apply(worker);
    }

    @Override
    public void verifyStorageAccess() throws IOException {
        bootstrap.initialize();
    }

    @Override
    public synchronized void warmSession() throws IOException, PngValidationException {
        if (preparedInitialData == null) {
            preparedInitialData = initializeFresh(
                    pinCurrentSession(), SessionClassification.FRESH_CHECKPOINT);
            startupObservedAccount = preparedInitialData.account().accountId();
            warmOwnedCapeCache();
        }
    }

    @Override
    public synchronized Optional<com.naocraftlab.skins.client.OuterLayerVisibility>
            warmedOuterLayerVisibility() {
        return preparedInitialData == null
                ? Optional.empty()
                : preparedInitialData.outerLayerVisibility();
    }

    @Override
    public synchronized AppearanceSyncStatus warmedAppearanceSyncStatus() {
        return preparedInitialData == null
                ? AppearanceSyncStatus.LOCAL_ONLY
                : preparedInitialData.syncStatus();
    }

    @Override
    public synchronized boolean warmedReconciliationRecommended() {
        return preparedInitialData != null && reconciliationRecommended(preparedInitialData);
    }

    @Override
    public synchronized Optional<DurableAppearance> warmedDurableAppearance() {
        if (preparedInitialData == null) {
            return Optional.empty();
        }
        return Optional.of(new DurableAppearance(
                preparedInitialData.account().accountId(),
                preparedInitialData.intentRevision(),
                preparedInitialData.syncStatus(),
                preparedInitialData.activePresetId(),
                preparedInitialData.localAppearance(),
                preparedInitialData.outerLayerVisibility()));
    }

    @Override
    public Optional<InitialData> warmedInitialData() {
        return Optional.ofNullable(preparedInitialData);
    }

    @Override
    public boolean reconciliationRecommended(InitialData data) {
        Objects.requireNonNull(data, "data");
        return ClientOperations.super.reconciliationRecommended(data);
    }

    @Override
    public synchronized InitialData initialize() throws IOException, PngValidationException {
        return initialize(SessionClassification.CACHED);
    }

    private InitialData initialize(SessionClassification sessionClassification)
            throws IOException, PngValidationException {
        OperationContext context = pinCurrentSession();
        List<String> preparedWarnings = List.of();
        if (preparedInitialData != null) {
            InitialData prepared = preparedInitialData;
            preparedInitialData = null;
            if (prepared.account().accountId().equals(context.identity().profileId())) {
                preparedWarnings = prepared.storageWarnings();
            }
        }

        InitialData current = initializeFresh(
                context, sessionClassification);
        if (preparedWarnings.isEmpty()) {
            return current;
        }
        List<String> warnings = new java.util.ArrayList<>(preparedWarnings);
        current.storageWarnings().stream().filter(warning -> !warnings.contains(warning)).forEach(warnings::add);
        return new InitialData(
                current.account(),
                current.session(),
                current.currentOfficialSkinId(),
                current.activePresetId(),
                current.localAppearance(),
                current.pendingOfficialSync(),
                warnings,
                current.uiPreferences(),
                current.outerLayerVisibility(),
                current.ownedCapes(),
                current.intentRevision(),
                current.syncStatus(), current.providers());
    }

    private InitialData initializeFresh(
            OperationContext context, SessionClassification sessionClassification)
            throws IOException, PngValidationException {
        UUID accountId = context.identity().profileId();
        List<String> initialization = bootstrap.initialize();
        return initializeFreshLocked(context, initialization, sessionClassification);
    }

    private InitialData initializeFreshLocked(
            OperationContext context,
            List<String> initialization,
            SessionClassification sessionClassification)
            throws IOException, PngValidationException {
        UUID accountId = resolveAccountId(context.identity());
        AppearanceProviders observedConfiguration = storage.loadAppearance(accountId).providers();
        boolean profileValidationResolved = false;
        for (int attempt = 0; attempt < MAX_BOOTSTRAP_CAS_ATTEMPTS; attempt++) {
            AccountState state = seedVanillaDefaults(library.load(accountId));

            state = library.load(accountId);
            SessionValidation validation;
            if (!profileValidationResolved) {
                validation = switch (sessionClassification) {
                    case CACHED -> sessions.cachedStatus(context.identity());
                    case FRESH_CHECKPOINT -> observedConfiguration.minecraftEnabled()
                            ? sessions.observeFreshAtCheckpoint(context.tokens())
                            : sessions.cachedStatus(context.identity());
                    case MANUAL_RETRY -> sessions.manualRetry(context.tokens());
                };
                profileValidationResolved = true;
            } else {

                validation = sessions.cachedStatus(context.identity());
            }
            OfficialSkinSync official = syncCurrentOfficial(state, validation, sessionClassification != SessionClassification.CACHED);
            InitialPresetBootstrap bootstrap = createInitialPresetFromOfficialSkin(official, validation);
            if (bootstrap.revisionMatched()) {
                return finishInitialization(
                        accountId,
                        bootstrap.official(),
                        validation,
                        initialization, observedConfiguration);
            }

        }

        AccountState latest = seedVanillaDefaults(library.load(accountId));
        latest = library.load(accountId);
        SessionValidation validation = sessions.cachedStatus(context.identity());
        OfficialSkinSync official = syncCurrentOfficial(latest, validation, false);
        return finishInitialization(accountId, official, validation, initialization, observedConfiguration);
    }

    private InitialData finishInitialization(
            UUID accountId,
            OfficialSkinSync official,
            SessionValidation validation,
            List<String> initialization, AppearanceProviders observedConfiguration) throws IOException, PngValidationException {
        Optional<UUID> activePreset = reconcileActivePreset(accountId, official.state(), validation);
        observeMinecraftProviders(accountId, validation, observedConfiguration, true, true);
        AccountAppearanceState appearance = storage.loadAppearance(accountId);
        Optional<AppliedAppearance> localAppearance = materializeLocalAppearance(
                accountId, validation.sessionIdentity().profileId(), appearance, validation);
        com.naocraftlab.skins.core.service.AccountBootstrapPort.Preferences uiPreferences = bootstrap.loadUiPreferences(accountId);
        List<String> warnings = new ArrayList<>(
                initialization);
        uiPreferences.warnings().stream()
                .filter(warning -> !warnings.contains(warning))
                .forEach(warnings::add);
        OwnedCapeInventory ownedCapes = validation.valid() && validation.profile() != null
                ? publishOwnedCapeInventory(accountId, validation.profile())
                : storage.loadOwnedCapes(accountId);
        preparedCatalog.publishInitializedAccount(accountId, official.state());
        InitialData result = new InitialData(
                official.state(),
                validation,
                Optional.ofNullable(official.currentOfficialSkinId()),
                activePreset,
                localAppearance,
                appearance.pendingOfficialSync(),
                warnings,
                uiPreferences.preferences(),
                appearance.optionalOuterLayerVisibility(),
                ownedCapes,
                appearance.intentRevision(),
                appearance.syncStatus(), appearance.providers());
        observeProfileValidated(result.account());
        return result;
    }

    private OwnedCapeInventory publishOwnedCapeInventory(UUID accountId, RemoteProfile profile)
            throws IOException {
        OwnedCapeInventory previous = storage.loadOwnedCapes(accountId);
        List<OwnedCapeEntry> capes = profile.capes().stream()
                .map(cape -> new OwnedCapeEntry(
                        cape.id(),
                        cape.optionalAlias().map(DefaultClientOperations::normalizeCapeAlias).orElse(null),
                        cape.state(),
                        cachedCapeKey(cape, previous.find(cape.id())),
                        previous.find(cape.id()).filter(entry -> Objects.equals(entry.textureCacheKey(), textures.cacheKey(cape.textureUri())))
                                .map(OwnedCapeEntry::hasElytra).orElse(null)))
                .toList();
        return storage.saveOwnedCapes(new OwnedCapeInventory(
                OwnedCapeInventory.CURRENT_SCHEMA_VERSION,
                accountId,
                capes,
                clock.instant()));
    }

    private String cachedCapeKey(RemoteCape cape, Optional<OwnedCapeEntry> previous) {
        try {
            if (textures.readIfCached(cape.textureUri()).isPresent()) {
                return textures.cacheKey(cape.textureUri());
            }
        } catch (IOException | RuntimeException invalidCacheEntry) {

        }
        return previous.flatMap(OwnedCapeEntry::optionalTextureCacheKey).orElse(null);
    }

    private static String normalizeCapeAlias(String value) {
        String cleaned = value.replaceAll("[\\p{Cc}\\p{Cf}]", " ")
                .replaceAll("\\s+", " ")
                .trim();
        return cleaned.length() <= 128 ? cleaned : cleaned.substring(0, 128);
    }

    @Override
    public synchronized List<SkinCatalogSource.CollectionDescriptor> catalogCollections() throws IOException {
        return preparedCatalog.catalogCollections();
    }

    @Override
    public Map<CatalogRead.CatalogVariant, SkinFeatureEvidence> catalogFeatureEvidence() {
        return preparedCatalog.catalogFeatureEvidence();
    }

    @Override
    public Map<UUID, SkinFeatureEvidence> assetFeatureEvidence()
            throws IOException, PngValidationException {
        UUID accountId = resolveAccountId(pinCurrentSession().identity());
        AccountState account = library.load(accountId);
        Map<UUID, SkinFeatureEvidence> evidence = new HashMap<>();
        PngValidator validator = new PngValidator();
        for (SkinAsset asset : account.skinAssets()) {
            evidence.put(
                    asset.id(),
                    validator.projectStoredRender(assets.readAsset(asset.sha256()))
                            .featureEvidence());
        }
        return Map.copyOf(evidence);
    }

    @Override
    public boolean supportsAssetFeatureEvidence() {
        return true;
    }

    @Override
    public Optional<FrozenCatalogSelection> freezeCatalogSelection(String collectionId, String skinId) throws IOException {
        return preparedCatalog.freezeCatalogSelection(collectionId, skinId);
    }

    @Override
    public byte[] loadCatalogSkin(String collectionId, String skinId, SkinModel model)
            throws IOException, PngValidationException {
        return preparedCatalog.loadCatalogSkin(collectionId, skinId, model);
    }

    @Override
    public Optional<UUID> reusableCatalogSkinAsset(
            String collectionId, String skinId, SkinModel model) throws IOException {
        return preparedCatalog.reusableCatalogSkinAsset(collectionId, skinId, model);
    }

    @Override
    public Optional<AccountUiPreferences> loadUiPreferences() throws Exception {
        return uiPreferences.loadUiPreferences();
    }

    @Override
    public void setSelectedProvidersTab(UUID accountId, AppearanceProviders.Component tab) throws Exception {
        uiPreferences.setSelectedProvidersTab(accountId, tab);
    }

    @Override
    public void setSelectedAddSourceTab(UUID accountId, AddSourceTab tab) throws Exception {
        uiPreferences.setSelectedAddSourceTab(accountId, tab);
    }

    @Override
    public void setSelectedAddSourceTab(AddSourceTab tab) throws Exception {
        uiPreferences.setSelectedAddSourceTab(tab);
    }

    @Override
    public void setCollapsedCapeCollections(UUID accountId, Set<String> values) throws Exception {
        uiPreferences.setCollapsedCapeCollections(accountId, values);
    }

    @Override
    public void setSelectedEditorTab(UUID accountId, EditorTab tab) throws Exception {
        uiPreferences.setSelectedEditorTab(accountId, tab);
    }

    @Override
    public void setCollectionCollapsed(String collectionId, boolean collapsed) throws Exception {
        uiPreferences.setCollectionCollapsed(collectionId, collapsed);
    }

    @Override
    public void replaceCollapsedCollectionIds(Set<String> collectionIds) throws Exception {
        uiPreferences.replaceCollapsedCollectionIds(collectionIds);
    }

    @Override
    public void setPreferredSkinVariant(SkinVariant variant) throws Exception {
        uiPreferences.setPreferredSkinVariant(variant);
    }

    @Override
    public AccountState importSkin(String name, SkinVariant variant, byte[] normalizedPng)
            throws IOException, PngValidationException {
        UUID accountId = resolveAccountId(pinCurrentSession().identity());
        return observeLocal(library.importSkin(
                                accountId,
                                normalizeName(name, "Imported skin"),
                                Objects.requireNonNull(variant, "variant"),
                                SkinSource.IMPORTED,
                                Objects.requireNonNull(normalizedPng, "normalizedPng"))
                        .state());
    }

    @Override
    public ImportDraft loadPlayerSkin(String playerNameOrUuid) throws Exception {
        return publicImports.loadPlayer(playerNameOrUuid);
    }

    @Override
    public ImportDraft loadUrlSkin(String url) throws Exception {
        return publicImports.loadUrl(url);
    }

    @Override
    public ExternalImportProbe probeExternalSource(
            ExternalImportSource source, Optional<Path> selectedRoot) throws Exception {
        OperationContext operation = pinCurrentSession();
        ExternalImportContext context = new ExternalImportContext(
                operation.identity().profileId(),
                operation.identity().profileName(),
                Path.of(System.getProperty("user.dir", ".")));
        return externalImports.probe(
                Objects.requireNonNull(source, "source"),
                Objects.requireNonNull(selectedRoot, "selectedRoot"),
                context);
    }

    @Override
    public ExternalImportReview prepareExternalAppearances(
            ExternalImportSource source, Optional<Path> selectedRoot) throws Exception {
        OperationContext operation = pinCurrentSession();
        UUID accountId = resolveAccountId(operation.identity());
        ExternalImportContext context = new ExternalImportContext(
                operation.identity().profileId(),
                operation.identity().profileName(),
                Path.of(System.getProperty("user.dir", ".")));
        return externalImports.prepareAppearances(
                accountId,
                Objects.requireNonNull(source, "source"),
                Objects.requireNonNull(selectedRoot, "selectedRoot"),
                context,
                storage.loadOwnedCapes(accountId));
    }

    @Override
    public ExternalImportResult commitExternalAppearances(
            List<ExternalImportCandidate> selected, int skipped, int warnings) throws Exception {
        UUID accountId = resolveAccountId(pinCurrentSession().identity());
        ExternalAppearanceImportService.Result result = externalImports.commitAppearances(
                accountId,
                Objects.requireNonNull(selected, "selected"),
                skipped,
                warnings);
        preparedCatalog.invalidatePersonalView();
        AccountState observed = observeLocal(result.state());
        return new ExternalImportResult(
                observed,
                result.imported(),
                result.alreadyPresent(),
                result.skipped(),
                result.warnings());
    }

    @Override
    public AccountState renameSkin(UUID skinId, String newName) throws IOException {
        UUID accountId = resolveAccountId(pinCurrentSession().identity());
        return observeLocal(library.renameSkin(
                accountId, skinId, normalizeName(newName, "Imported skin")));
    }

    @Override
    public AccountState changeSkinVariant(UUID skinId, SkinVariant variant) throws IOException {
        UUID accountId = resolveAccountId(pinCurrentSession().identity());
        return observeLocal(library.changeSkinVariant(accountId, skinId, variant));
    }

    @Override
    public AccountState duplicateSkin(UUID skinId, String newName) throws IOException {
        UUID accountId = resolveAccountId(pinCurrentSession().identity());
        return observeLocal(library.duplicateSkin(
                accountId, skinId, normalizeName(newName, "Skin copy")));
    }

    @Override
    public AccountState deleteSkin(UUID skinId) throws IOException {
        UUID accountId = resolveAccountId(pinCurrentSession().identity());
        return observeLocal(library.deleteSkin(accountId, skinId));
    }

    @Override
    public AccountState removePersonalSkin(String sha256) throws IOException {
        UUID accountId = resolveAccountId(pinCurrentSession().identity());
        return observeLocal(library.hidePersonalSkin(accountId, sha256));
    }

    @Override
    public AccountState renamePersonalSkin(String sha256, String newName) throws IOException {
        UUID accountId = resolveAccountId(pinCurrentSession().identity());
        AccountState renamed = library.renamePersonalSkin(
                accountId,
                sha256,
                normalizeName(newName, "Imported skin"));
        preparedCatalog.invalidatePersonalView();
        return observeLocal(renamed);
    }

    @Override
    public Optional<OwnedCapeInventory> ownedCapeInventory() throws IOException {
        UUID accountId = resolveAccountId(pinCurrentSession().identity());
        return Optional.of(storage.loadOwnedCapes(accountId));
    }

    @Override
    public void warmOwnedCapeCache() throws IOException {
        OperationContext context = pinCurrentSession();
        if (!storage.loadAppearance(resolveAccountId(context.identity())).providers().cape()
                .enabled(BuiltinProvider.MINECRAFT)) {
            return;
        }

        SessionValidation validation = sessions.cachedStatus(context.identity());
        if (!validation.valid() || validation.profile() == null) {
            return;
        }
        UUID accountId = resolveAccountId(context.identity());
        RemoteProfile profile = validation.profile();
        publishOwnedCapeInventory(accountId, profile);
        Map<String, byte[]> previews = new HashMap<>();
        for (RemoteCape cape : profile.capes()) {
            try {
                byte[] capeBytes = textures.load(cape.textureUri());
                Boolean classified = storage.loadOwnedCapes(accountId).find(cape.id()).map(OwnedCapeEntry::hasElytra).orElse(null);
                boolean hasElytra = classified != null ? classified
                        : new PngValidator().projectCanonicalCape(capeBytes).hasElytra();
                String cacheKey = textures.cacheKey(cape.textureUri());
                storage.updateOwnedCapes(accountId, current -> {
                    List<OwnedCapeEntry> updated = current.capes().stream()
                            .map(entry -> entry.id().equals(cape.id())
                                    ? entry.withTextureCacheKey(cacheKey).withElytra(hasElytra)
                                    : entry)
                            .toList();
                    return new OwnedCapeInventory(
                            current.schemaVersion(),
                            current.accountId(),
                            updated,
                            current.verifiedAt());
                });
                storage.updateAppearance(accountId, current -> current.withProviders(
                        current.providers().withCapeTexture(cape.id(), cacheKey, hasElytra)));
                previews.put("cape:" + cape.id(), capeBytes);
            } catch (IOException | PngValidationException | RuntimeException unavailableCape) {

            }
        }
        publishOwnedCapePreviews(accountId, previews);
    }

    private void publishOwnedCapePreviews(UUID accountId, Map<String, byte[]> owned) {
        preparedCatalog.publishOwnedCapePreviews(accountId, owned);
    }

    @Override
    public InitialData resetLibrary() throws IOException, PngValidationException {
        OperationContext context = pinCurrentSession();
        UUID accountId = resolveAccountId(context.identity());
        AccountState before = library.load(accountId);
        SkinAsset previousOfficial = latestOfficialAsset(before).orElse(null);
        byte[] previousOfficialPng = previousOfficial == null
                ? null
                : assets.readAsset(previousOfficial.sha256());
        AccountState state = seedVanillaDefaults(library.resetLibrary(accountId));
        if (previousOfficial != null) {
            state = ensureLibraryAsset(
                            state,
                            "Current official",
                            previousOfficial.variant(),
                            SkinSource.CURRENT_OFFICIAL,
                            Objects.requireNonNull(previousOfficialPng, "previousOfficialPng"))
                    .state();
        }
        AccountAppearanceState appearance = recordAccountDefaultAppearance(
                accountId, AppearanceSyncStatus.PENDING);
        SessionValidation validation = sessions.cachedStatus(context.identity());
        com.naocraftlab.skins.core.service.AccountBootstrapPort.Preferences preferences = bootstrap.loadUiPreferences(accountId);
        OwnedCapeInventory ownedCapes = storage.loadOwnedCapes(accountId);
        AccountState latest = observeLocal(library.load(accountId));
        return new InitialData(
                latest,
                validation,
                latestOfficialAsset(latest).map(SkinAsset::id),
                Optional.empty(),
                materializeLocalAppearance(
                        accountId, context.identity().profileId(), appearance, validation),
                true,
                preferences.warnings(),
                preferences.preferences(),
                appearance.optionalOuterLayerVisibility(),
                ownedCapes,
                appearance.intentRevision(),
                appearance.syncStatus(), appearance.providers());
    }

    @Override
    public com.naocraftlab.skins.core.model.PersonalCapeEntry importCape(java.nio.file.Path path) throws IOException, PngValidationException {
        return importCape(resolveAccountId(pinCurrentSession().identity()), path, "Cape");
    }

    @Override
    public com.naocraftlab.skins.core.model.PersonalCapeEntry importCape(UUID accountId, java.nio.file.Path path, String fallbackName) throws IOException, PngValidationException {
        requireCapeAccount(accountId);
        LocalCapeImportSource.Source source = capeImports.read(path);
        String fileName = source.fileName();
        byte[] bytes = source.bytes();
        requireCapeAccount(accountId);
        String name = UntrustedDisplayName.fromFileName(fileName, fallbackName);
        var entry = library.importCape(accountId, name, bytes);
        return entry;
    }

    @Override
    public Optional<AccountState> reloadEditorAccount(UUID accountId) throws IOException {
        requireCapeAccount(accountId);
        return Optional.of(library.load(accountId));
    }

    @Override
    public CapeEditorData loadCapeEditorData(UUID accountId)
            throws IOException {
        return preparedCatalog.loadCapeEditorData(accountId);
    }

    @Override
    public long capeCatalogGeneration() {
        return preparedCatalog.capeCatalogGeneration();
    }

    @Override
    public void warmResourceCapeCatalog(long generation) throws IOException {
        preparedCatalog.warmResourceCapeCatalog(generation);
    }

    @Override
    public void warmCapeCatalog(UUID accountId, long generation) throws IOException {
        preparedCatalog.warmCapeCatalog(accountId, generation);
    }

    @Override
    public Optional<CapeEditorData> warmedCapeEditorData(UUID accountId) {
        return preparedCatalog.warmedCapeEditorData(accountId);
    }

    @Override
    public Map<String, byte[]> warmedCapePreviews(UUID accountId) {
        return preparedCatalog.warmedCapePreviews(accountId);
    }

    @Override
    public Optional<byte[]> loadResourceCapePreview(
            UUID accountId, ResourceCapeSelection selection) throws IOException {
        return preparedCatalog.loadResourceCapePreview(accountId, selection);
    }

    @Override
    public com.naocraftlab.skins.core.model.PersonalCapeEntry materializeResourceCape(
            UUID accountId, ResourceCapeSelection selection)
            throws IOException, PngValidationException {
        return preparedCatalog.materializeResourceCape(accountId, selection);
    }

    @Override
    public Optional<AccountState> discardCapeIfUnreferenced(UUID accountId, UUID entryId)
            throws IOException {
        requireCapeAccount(accountId);
        return Optional.of(observeLocal(library.discardCapeIfUnreferenced(accountId, entryId)));
    }

    private void requireCapeAccount(UUID accountId) throws IOException {
        if (!accountId.equals(resolveAccountId(pinCurrentSession().identity()))) throw new IOException("Account changed");
    }

    @Override
    public AccountState renameCape(UUID accountId, UUID entryId, String name) throws IOException {
        requireCapeAccount(accountId);
        return observeLocal(library.renameCape(accountId, entryId, name));
    }

    @Override
    public CapeDeletion deleteCape(UUID accountId, UUID entryId) throws IOException, PngValidationException {
        requireCapeAccount(accountId);
        library.deleteCape(accountId, entryId);
        return new CapeDeletion(observeLocal(library.load(accountId)), reloadProviders());
    }

    @Override
    public AccountState renameCape(UUID entryId, String name) throws IOException {
        return observeLocal(library.renameCape(resolveAccountId(pinCurrentSession().identity()), entryId, name));
    }

    @Override
    public CapeDeletion deleteCape(UUID entryId) throws IOException, PngValidationException {
        UUID accountId = resolveAccountId(pinCurrentSession().identity());
        library.deleteCape(accountId, entryId);
        return new CapeDeletion(observeLocal(library.load(accountId)), reloadProviders());
    }

    @Override
    public EditorSave saveEditor(EditorSaveRequest request) throws IOException, PngValidationException {
        Objects.requireNonNull(request, "request");
        OperationContext context = pinCurrentSession();
        UUID accountId = resolveAccountId(context.identity());
        if (request.frozenCatalogSelection().isPresent()) {
            preparedCatalog.validateFrozenSelection(request.frozenCatalogSelection().orElseThrow(), request.variant());
        }
        com.naocraftlab.skins.core.service.AccountWriteGuard catalogGuard = current -> {
            requireCapeAccount(accountId);
            if (request.frozenCatalogSelection().isPresent()) {
                preparedCatalog.validateFrozenSelection(request.frozenCatalogSelection().orElseThrow(), request.variant(), current);
            }
        };
        AccountState state = library.load(accountId);
        SkinReference persistedSkin = request.skin();
        Optional<byte[]> pngBytes = request.pngBytes();
        if (pngBytes.isPresent()) {
            if (request.personalSkinName().isPresent()) {
                SavedPersonalSkinPreset saved = library.savePresetWithPersonalSkin(
                        accountId,
                        request.originalPresetId(),
                        request.name(),
                        request.personalSkinName().orElseThrow(),
                        request.variant(),
                        request.personalSkinSource(),
                        pngBytes.orElseThrow(),
                        request.outerLayerVisibility(),
                        request.capeId().orElse(null), request.offlineCape());
                return finishEditorSave(
                        context,
                        request.originalPresetId(),
                        saved.state(),
                        saved.preset().id());
            }
            if (request.catalogOrigin().isPresent()) {
                preparedCatalog.validateCatalogSave(accountId, request.catalogOrigin().orElseThrow(),
                        request.variant(), pngBytes.orElseThrow());
                SavedImportedPreset saved = library.savePresetWithImportedSkin(
                        accountId,
                        request.originalPresetId(),
                        request.name(),
                        request.name() + " skin",
                        request.variant(),
                        SkinSource.IMPORTED,
                        pngBytes.orElseThrow(),
                        request.catalogOrigin().orElseThrow(),
                        request.outerLayerVisibility(),
                        request.capeId().orElse(null), request.offlineCape(), catalogGuard);
                return finishEditorSave(
                        context,
                        request.originalPresetId(),
                        saved.state(),
                        saved.preset().id());
            }
            ImportedSkin imported = library.importSkin(
                    accountId,
                    request.name() + " skin",
                    request.variant(),
                    SkinSource.IMPORTED,
                    pngBytes.orElseThrow(),
                    request.catalogOrigin());
            state = imported.state();
            persistedSkin = SkinReference.asset(imported.asset().id());
        } else if (persistedSkin.optionalAssetId().isPresent()) {
            UUID assetId = persistedSkin.assetId();
            SkinAsset current = library.findSkin(state, assetId);
            if (current.variant() != request.variant()) {
                Set<UUID> beforeIds = new HashSet<>();
                state.skinAssets().forEach(asset -> beforeIds.add(asset.id()));
                state = library.duplicateSkin(accountId, assetId, request.name() + " skin");
                UUID duplicateId = state.skinAssets().stream()
                        .map(SkinAsset::id)
                        .filter(id -> !beforeIds.contains(id))
                        .findFirst()
                        .orElseThrow(() -> new IllegalStateException("Duplicated skin was not returned"));
                state = library.changeSkinVariant(accountId, duplicateId, request.variant());
                persistedSkin = SkinReference.asset(duplicateId);
            }
        } else if (request.variant() != request.initialVariant()) {
            SkinAsset vanilla = state.skinAssets().stream()
                    .filter(asset -> asset.source() == SkinSource.VANILLA_DEFAULT)
                    .filter(asset -> asset.variant() == request.variant())
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("Vanilla skin for selected arms is unavailable"));
            persistedSkin = SkinReference.asset(vanilla.id());
        }

        String capeId = request.capeId().orElse(null);
        if (request.originalPresetId().isEmpty()) {
            Set<UUID> beforeIds = new HashSet<>();
            state.presets().forEach(preset -> beforeIds.add(preset.id()));
            AccountState saved = library.createPreset(
                    accountId, request.name(), persistedSkin, request.outerLayerVisibility(), capeId, request.offlineCape(), catalogGuard);
            UUID presetId = saved.presets().stream()
                    .map(AppearancePreset::id)
                    .filter(id -> !beforeIds.contains(id))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("Created preset was not returned"));
            return finishEditorSave(
                    context,
                    request.originalPresetId(),
                    saved,
                    presetId);
        }
        UUID presetId = request.originalPresetId().orElseThrow();
        library.updatePreset(
                accountId, presetId, request.name(), persistedSkin, request.outerLayerVisibility(), capeId, request.offlineCape(), catalogGuard);
        return finishEditorSave(context, presetId);
    }

    private EditorSave finishEditorSave(
            OperationContext context,
            Optional<UUID> originalPresetId,
            AccountState saved,
            UUID presetId) throws IOException {
        if (originalPresetId.isEmpty()) {
            return new EditorSave(observeLocal(saved), presetId);
        }
        return finishEditorSave(context, presetId);
    }

    private EditorSave finishEditorSave(OperationContext context, UUID presetId) throws IOException {
        UUID accountId = resolveAccountId(context.identity());
        OwnedCapeInventory inventory = storage.loadOwnedCapes(accountId);
        SessionValidation selectionValidation = sessions.cachedStatus(context.identity());
        com.naocraftlab.skins.core.service.AccountAppearanceStore.ActivePresetAppearanceIntentUpdate updated =
                storage.updateAppearanceIntentIfPresetActive(
                        accountId,
                        presetId,
                        (account, current, revision) -> revisedAppearanceForPreset(
                                accountId, account, presetId, revision, current, inventory, selectionValidation));
        AccountState latest = observeLocal(updated.account());
        if (!updated.updated()) {
            return new EditorSave(latest, presetId);
        }
        SessionValidation validation = sessions.cachedStatus(context.identity());
        return new EditorSave(
                latest,
                presetId,
                Optional.of(durableAppearance(
                        accountId, context.identity(), updated.state(), validation)));
    }

    @Override
    public Optional<byte[]> loadProviderTexture(String cacheKey, boolean skin) throws IOException, PngValidationException {
        Objects.requireNonNull(cacheKey, "cacheKey");
        return skin ? Optional.of(assets.readAsset(cacheKey))
                : textures.readIfCached(cacheKey);
    }

    @Override
    public AppearanceProviders loadProviders() throws IOException {
        OperationContext context = pinCurrentSession();
        return storage.loadAppearance(resolveAccountId(context.identity())).providers();
    }

    @Override
    public DurableAppearance reloadProviders() throws IOException {
        OperationContext context = pinCurrentSession();
        UUID accountId = resolveAccountId(context.identity());
        return durableAppearance(accountId, context.identity(), storage.loadAppearance(accountId),
                sessions.cachedStatus(context.identity()));
    }

    @Override
    @SuppressWarnings("try")
    public DurableAppearance refreshProviders(AppearanceProviders.Component component) throws IOException {
        return refreshProvidersWithObservation(component).appearance();
    }

    @Override
    @SuppressWarnings("try")
    public ProviderRefresh refreshProvidersWithObservation(
            AppearanceProviders.Component component) throws IOException {
        Objects.requireNonNull(component, "component");
        OperationContext context = pinCurrentSession();
        UUID accountId = resolveAccountId(context.identity());
        try (var ignored = storage.acquireRemoteMutationLock(accountId)) {
            AccountAppearanceState before = storage.loadAppearance(accountId);
            boolean skin = component == AppearanceProviders.Component.SKIN;
            boolean enabled = skin ? before.providers().skin().enabled(BuiltinProvider.MINECRAFT)
                    : before.providers().cape().enabled(BuiltinProvider.MINECRAFT);
            SessionValidation validation = sessions.cachedStatus(context.identity());
            ProviderObservation<?> confirmedMinecraft = null;
            if (enabled && profileApi.rateLimitRemaining().isEmpty()) {
                validation = sessions.observeFreshAtCheckpoint(context.tokens());
                if (validation.valid() && validation.profile() != null) {
                    if (skin) {
                        syncCurrentOfficial(library.load(accountId), validation);
                    } else {
                        publishOwnedCapeInventory(accountId, validation.profile());
                    }
                    MinecraftObservation confirmed = observeMinecraftProviders(
                            accountId, validation, before.providers(), skin, !skin);
                    confirmedMinecraft = skin ? confirmed.skin() : confirmed.cape();
                }
            }
            return new ProviderRefresh(durableAppearance(accountId, context.identity(),
                    storage.loadAppearance(accountId), validation), confirmedMinecraft);
        }
    }

    @Override
    public DurableAppearance enableProvider(AppearanceProviders.Component component, BuiltinProvider provider)
            throws IOException {
        return changeProviders(current -> current.enable(component, provider), provider == BuiltinProvider.MINECRAFT);
    }

    @Override
    public DurableAppearance disableProvider(AppearanceProviders.Component component, BuiltinProvider provider)
            throws IOException {
        return changeProviders(current -> current.disable(component, provider), false);
    }

    @Override
    public DurableAppearance moveProvider(
            AppearanceProviders.Component component, BuiltinProvider provider, int direction) throws IOException {
        return changeProviders(current -> current.move(component, provider, direction), false);
    }

    @Override
    public DurableAppearance enableProvider(UUID accountId, AppearanceProviders.Component component, BuiltinProvider provider) throws IOException {
        return changeProviders(accountId, current -> current.enable(component, provider), provider == BuiltinProvider.MINECRAFT);
    }

    @Override
    public DurableAppearance disableProvider(UUID accountId, AppearanceProviders.Component component, BuiltinProvider provider) throws IOException {
        return changeProviders(accountId, current -> current.disable(component, provider), false);
    }

    @Override
    public DurableAppearance moveProvider(UUID accountId, AppearanceProviders.Component component, BuiltinProvider provider, int direction) throws IOException {
        return changeProviders(accountId, current -> current.move(component, provider, direction), false);
    }

    private DurableAppearance changeProviders(
            java.util.function.UnaryOperator<AppearanceProviders> change, boolean enablesMinecraft) throws IOException {
        return changeProviders(null, change, enablesMinecraft);
    }

    private DurableAppearance changeProviders(UUID expectedAccount,
            java.util.function.UnaryOperator<AppearanceProviders> change, boolean enablesMinecraft) throws IOException {
        OperationContext context = pinCurrentSession();
        UUID accountId = resolveAccountId(context.identity());
        if (expectedAccount != null && !expectedAccount.equals(accountId)) throw new IOException("Account changed");
        AccountAppearanceState saved = storage.updateAppearance(accountId, current -> {
            AppearanceProviders providers = change.apply(current.providers());
            if (providers.equals(current.providers())) {
                return current;
            }
            AccountAppearanceState updated = current.withProviders(providers);
            if (current.syncStatus() == AppearanceSyncStatus.ATTEMPTING && !sameActivation(updated, current)) {
                return delivery.copyAppearanceStatus(updated, AppearanceSyncStatus.UNKNOWN, current.settledRevision());
            }
            if (enablesMinecraft && current.hasIntent()
                    && current.syncStatus() != AppearanceSyncStatus.UNKNOWN
                    && current.syncStatus() != AppearanceSyncStatus.PARTIAL) {
                return delivery.copyAppearanceStatus(updated, AppearanceSyncStatus.PENDING, current.settledRevision());
            }
            return updated;
        });
        return durableAppearance(accountId, context.identity(), saved, sessions.cachedStatus(context.identity()));
    }

    private PresetValues presetValues(
            UUID accountId,
            AccountState account,
            UUID presetId,
            OwnedCapeInventory inventory,
            SessionValidation validation) {
        AppearancePreset preset = library.findPreset(account, presetId);
        String capeId = preset.capeId();
        if (capeId != null && validation.valid() && validation.profile() != null
                && accountId.equals(validation.profile().id()) && !validation.profile().ownsCape(capeId)) {
            capeId = null;
        }
        SkinAsset skin = preset.skin().optionalAssetId()
                .map(assetId -> library.findSkin(account, assetId))
                .orElse(null);
        return new PresetValues(
                preset,
                skin == null ? null : new ProviderSkin(skin.sha256(), skin.variant()),
                localProviderCape(preset.offlineCape()),
                providerCape(capeId, inventory),
                capeId);
    }

    private AccountAppearanceState pendingAppearanceForPreset(
            UUID accountId,
            AccountState account,
            UUID presetId,
            long revision,
            AccountAppearanceState current,
            OwnedCapeInventory inventory,
            SessionValidation validation) {
        PresetValues values = presetValues(accountId, account, presetId, inventory, validation);
        AppearanceProviders selected = current.providers().selectMatchingObservations(
                revision, values.skin(), values.offlineCape(), values.minecraftCape());
        AppearanceSyncStatus status = assignedMinecraft(revision, selected)
                ? pendingStatus(selected) : current.syncStatus();
        return new AccountAppearanceState(
                AccountAppearanceState.CURRENT_SCHEMA_VERSION,
                accountId,
                revision,
                values.preset().id(),
                values.skin() == null ? null : values.skin().sha256(),
                values.skin() == null ? null : values.skin().variant(),
                values.capeId(),
                values.preset().outerLayerVisibility(),
                status,
                status == AppearanceSyncStatus.OFFICIAL ? revision : current.settledRevision(),
                clock.instant(),
                selected);
    }

    private AccountAppearanceState revisedAppearanceForPreset(
            UUID accountId,
            AccountState account,
            UUID presetId,
            long revision,
            AccountAppearanceState current,
            OwnedCapeInventory inventory,
            SessionValidation validation) {
        PresetValues values = presetValues(accountId, account, presetId, inventory, validation);
        AppearanceProviders revised = current.providers().revise(
                revision, values.skin(), values.offlineCape(), values.minecraftCape());
        AppearanceSyncStatus status = activeEditStatus(current, revised);
        long settledRevision = status == AppearanceSyncStatus.OFFICIAL
                ? revision : current.settledRevision();
        return new AccountAppearanceState(
                AccountAppearanceState.CURRENT_SCHEMA_VERSION,
                accountId,
                revision,
                values.preset().id(),
                values.skin() == null ? null : values.skin().sha256(),
                values.skin() == null ? null : values.skin().variant(),
                values.capeId(),
                values.preset().outerLayerVisibility(),
                status,
                settledRevision,
                clock.instant(),
                revised);
    }

    private static AppearanceSyncStatus pendingStatus(AppearanceProviders providers) {
        boolean unknown = providers.skin().enabled(BuiltinProvider.MINECRAFT)
                        && providers.skin().minecraftDelivery().status() == ProviderDelivery.Status.UNKNOWN
                || providers.cape().enabled(BuiltinProvider.MINECRAFT)
                        && providers.cape().minecraftDelivery().status() == ProviderDelivery.Status.UNKNOWN;
        return unknown ? AppearanceSyncStatus.UNKNOWN : AppearanceSyncStatus.PENDING;
    }

    private static ProviderCape localProviderCape(com.naocraftlab.skins.core.model.LocalCapeReference cape) {
        return cape == null ? null : new ProviderCape(cape.entryId() == null ? cape.sha256() : cape.entryId().toString(), cape.sha256(), cape.hasElytra());
    }

    private static ProviderCape providerCape(String capeId, OwnedCapeInventory inventory) {
        return capeId == null ? null : new ProviderCape(capeId,
                inventory.find(capeId).flatMap(OwnedCapeEntry::optionalTextureCacheKey).orElse(null),
                inventory.find(capeId).map(OwnedCapeEntry::hasElytra).orElse(null));
    }

    @Override
    public PresetDelete deletePreset(UUID presetId) throws IOException, PngValidationException {
        OperationContext context = pinCurrentSession();
        UUID accountId = resolveAccountId(context.identity());
        LibraryService.PresetDeletion deletion = library.deletePreset(
                accountId,
                Objects.requireNonNull(presetId, "presetId"),
                (ignoredAccount, current, revision) -> accountDefaultAppearance(
                        accountId, revision, AppearanceSyncStatus.PENDING, current.providers()));
        AccountState deleted = observeLocal(deletion.state());
        if (!deletion.resetsAppearance()) {
            return PresetDelete.local(deleted);
        }
        SessionValidation validation = sessions.cachedStatus(context.identity());
        DurableAppearance durable = durableAppearance(
                accountId, context.identity(), deletion.appearance(), validation);
        return PresetDelete.local(deleted, durable);
    }

    @Override
    public RemoteResult applyPreset(UUID presetId) throws IOException, PngValidationException {
        PresetUse selected = usePreset(Objects.requireNonNull(presetId, "presetId"));
        ReconciliationKey key = new ReconciliationKey(
                selected.account().accountId(), selected.intentRevision(),
                selected.providers().skin().minecraftDelivery().activation(),
                selected.providers().cape().minecraftDelivery().activation());
        Trigger trigger = selected.syncStatus() == AppearanceSyncStatus.OFFICIAL
                ? Trigger.LOCAL_INTENT : Trigger.EXPLICIT_RETRY;
        ReconciliationResult reconciled = reconcileAppearance(key, trigger)
                .orElseThrow(() -> new IllegalStateException(
                        "Minecraft session or local appearance changed before reconciliation"));
        return legacyRemoteResult(reconciled, "Preset reconciliation completed without a remote mutation.");
    }

    @Override
    public PresetUse usePreset(UUID presetId) throws IOException, PngValidationException {
        OperationContext context = pinCurrentSession();
        UUID accountId = resolveAccountId(context.identity());
        UUID selectedPresetId = Objects.requireNonNull(presetId, "presetId");
        OwnedCapeInventory inventory = storage.loadOwnedCapes(accountId);
        SessionValidation selectionValidation = sessions.cachedStatus(context.identity());
        com.naocraftlab.skins.core.service.LibraryStatePort.AccountAppearanceMutationResult selected =
                libraryState.mutateAccountAndAppearance(accountId, (account, current, revision) ->
                        com.naocraftlab.skins.core.service.LibraryStatePort.AccountAppearanceMutationPlan.appearanceOnly(
                                account,
                                pendingAppearanceForPreset(
                                        accountId, account, selectedPresetId, revision, current, inventory, selectionValidation)));
        AccountState state = observeLocal(selected.account());
        AccountAppearanceState appearance = selected.appearance();
        SessionValidation validation = sessions.cachedStatus(context.identity());
        Optional<AppliedAppearance> local = materializeLocalAppearance(
                accountId, context.identity().profileId(), appearance, validation);
        return new PresetUse(
                state,
                validation,
                presetId,
                local,
                Optional.empty(),
                true,
                true,
                appearance.optionalOuterLayerVisibility(),
                appearance.intentRevision(),
                appearance.syncStatus(), appearance.providers());
    }

    @Override
    public Optional<ReconciliationResult> reconcileAppearance(Trigger trigger)
            throws IOException, PngValidationException {
        Objects.requireNonNull(trigger, "trigger");
        OperationContext context = pinCurrentSession();
        return reconcileAppearance(context, null, trigger);
    }

    @Override
    public Optional<ReconciliationResult> reconcileAppearance(
            ReconciliationKey expected, Trigger trigger)
            throws IOException, PngValidationException {
        Objects.requireNonNull(expected, "expected");
        Objects.requireNonNull(trigger, "trigger");
        OperationContext context = pinCurrentSession();
        if (!expected.accountId().equals(context.identity().profileId())) {
            return Optional.empty();
        }
        return reconcileAppearance(context, expected, trigger);
    }

    @SuppressWarnings("try")
    private Optional<ReconciliationResult> reconcileAppearance(OperationContext context,
            ReconciliationKey expected, Trigger trigger) throws IOException, PngValidationException {
        UUID accountId = resolveAccountId(context.identity());
        bootstrap.initialize();
        try (var ignored = storage.acquireRemoteMutationLock(accountId)) {
            return new AccountReconciliationExecutor().execute(new ReconciliationEffectsAdapter(context), expected, trigger);
        }
    }

    private final class ReconciliationEffectsAdapter implements AccountReconciliationEffects, AccountMutationEffects {
        private final OperationContext context;
        private final UUID accountId;
        private ReconciliationEffectsAdapter(OperationContext context) {
            this.context = context;
            this.accountId = context.identity().profileId();
        }
        public AccountAppearanceState load() throws IOException { return storage.loadAppearance(accountId); }
        public SessionValidation cached() { return sessions.cachedStatus(context.identity()); }
        public boolean automaticAllowed() { return sessions.automaticCheckpointMayAcquireToken(context.identity()); }
        public boolean cooldown() { return profileApi.rateLimitRemaining().isPresent(); }
        public boolean startupObserved() { return accountId.equals(startupObservedAccount); }
        public SessionValidation validate(com.naocraftlab.skins.core.reconciliation.ReconciliationPolicy.Validation mode) {
            return switch (mode) {
                case MANUAL -> sessions.manualRetry(context.tokens());
                case TOKEN_UNAVAILABLE -> sessions.retryTokenUnavailableAtCheckpoint(context.tokens());
                case FRESH -> sessions.observeFreshAtCheckpoint(context.tokens());
                case CACHED -> cached();
                case TRANSIENT -> sessions.retryTransientAtCheckpoint(context.tokens());
            };
        }
        public Optional<ReconciliationResult> withSession(AccountAppearanceState checkpoint, Scoped operation)
                throws IOException, PngValidationException {
            return withRequestScopedToken(context, accountId, checkpoint,
                    scoped -> operation.execute(new ReconciliationEffectsAdapter(scoped)));
        }
        public ObservedAccount observe(SessionValidation validation, AppearanceProviders expected) throws IOException, PngValidationException {
            return observeCheckpointAccount(accountId, validation, expected);
        }
        public ObservedAccount observed() throws IOException { return observedAccount(accountId); }
        public com.naocraftlab.skins.core.reconciliation.ReconciliationPolicy.Observation compare(
                AccountAppearanceState state, SessionValidation validation) { return comparison(state, validation.profile()); }
        public com.naocraftlab.skins.core.service.AccountAppearanceStore.AppearanceIntentUpdate normalizeCape(AccountAppearanceState appearance) throws IOException {
                return storage.updateAppearanceIntentIfCurrent(
                        accountId,
                        appearance.intentRevision(),
                        appearance.syncStatus(),
                        (current, revision) -> {
                            AppearanceProviders normalized = current.providers().revise(
                                    revision,
                                    current.providers().skin().desired(),
                                    current.providers().cape().offlineDesired(),
                                    null);
                            AppearanceSyncStatus status = activeEditStatus(current, normalized);
                            return new AccountAppearanceState(
                                    current.schemaVersion(),
                                    current.accountId(),
                                    revision,
                                    current.activePresetId(),
                                    current.skinSha256(),
                                    current.skinVariant(),
                                    null,
                                    current.outerLayerVisibility(),
                                    status,
                                    status == AppearanceSyncStatus.OFFICIAL
                                            ? revision : current.settledRevision(),
                                    clock.instant(), normalized);
                        });
        }
        public AccountAppearanceState settle(AccountAppearanceState expected, AppearanceSyncStatus status) throws IOException {
            return delivery.settleAppearance(accountId, expected.intentRevision(), expected.syncStatus(), status);
        }
        public ReconciliationResult full(AccountAppearanceState state, SessionValidation validation) throws IOException, PngValidationException {
            return accountMutations.applyFullIntent(accountId, this, state, state.syncStatus(), validation);
        }
        public ReconciliationResult cape(AccountAppearanceState state, SessionValidation validation) throws IOException {
            return state.syncStatus() == AppearanceSyncStatus.PARTIAL
                    ? accountMutations.applyCapeRecovery(accountId, this, state, validation)
                    : accountMutations.applyPendingCapeDelta(accountId, this, state, validation);
        }
        public PresetApplicationOutcome apply(PresetApplicationRequest request, java.util.function.BooleanSupplier current) {
            return mutations.applyPresetWhileLockedAfterSameTokenValidation(context.tokens(), request, current);
        }
        public PresetApplicationOutcome retryCape(String capeId, java.util.function.BooleanSupplier current) {
            return mutations.retryCapeWhileLockedAfterSameTokenValidation(context.tokens(), capeId, current);
        }
        public ReconciliationResult result(AccountAppearanceState state, SessionValidation validation) throws IOException {
            return result(state, validation, observed());
        }
        public ReconciliationResult afterMutation(AccountAppearanceState state, PresetApplicationOutcome outcome, AppearanceProviders expected) {
            return reconciliationAfterMutation(accountId, context, state, outcome, expected);
        }
        public ReconciliationResult result(AccountAppearanceState state, SessionValidation validation, ObservedAccount observed) throws IOException {
            return reconciliationResult(context, state, validation, observed, Optional.empty());
        }
    }

    private com.naocraftlab.skins.core.reconciliation.ReconciliationPolicy.Observation comparison(
            AccountAppearanceState appearance, RemoteProfile profile) {
        var actual = deliveryAppearance(profile);
        if (actual.isEmpty()) return com.naocraftlab.skins.core.reconciliation.ReconciliationPolicy.Observation.UNRESOLVED;
        if (appearanceMatches(appearance, actual.orElseThrow())) return com.naocraftlab.skins.core.reconciliation.ReconciliationPolicy.Observation.MATCH;
        return skinMatches(appearance, actual.orElseThrow())
                ? com.naocraftlab.skins.core.reconciliation.ReconciliationPolicy.Observation.SKIN_MATCH
                : com.naocraftlab.skins.core.reconciliation.ReconciliationPolicy.Observation.DIFFERENT;
    }

    private Optional<ReconciliationResult> withRequestScopedToken(
            OperationContext context,
            UUID accountId,
            AccountAppearanceState checkpointAppearance,
            ScopedReconciliation reconciliation) throws IOException, PngValidationException {
        if (!(context.tokens() instanceof PinnedTokenSource pinned)) {
            throw new IllegalStateException("Remote reconciliation requires a pinned game session");
        }
        try {
            return pinned.withRequestToken(scopedTokens -> {
                try {
                    return reconciliation.execute(
                            new OperationContext(context.identity(), scopedTokens));
                } catch (IOException | PngValidationException checkedFailure) {
                    throw new ScopedCheckedFailure(checkedFailure);
                }
            });
        } catch (ScopedCheckedFailure checked) {
            if (checked.getCause() instanceof PngValidationException pngFailure) {
                throw pngFailure;
            }
            throw (IOException) checked.getCause();
        } catch (ScopedCallbackRuntimeFailure callbackFailure) {
            throw callbackFailure.original();
        } catch (GameSessionIdentityChangedException identityChanged) {
            SessionValidation validation = sessions.rememberIdentityMismatch(context.identity());
            return sessionFailureResult(context, accountId, checkpointAppearance, validation,
                    com.naocraftlab.skins.core.reconciliation.ReconciliationPolicy.SessionFailure.IDENTITY_CHANGED);
        } catch (GameSessionTokenUnavailableException unavailableToken) {
            return sessionFailureResult(context, accountId, checkpointAppearance,
                    sessions.rememberTokenUnavailable(context.identity()),
                    com.naocraftlab.skins.core.reconciliation.ReconciliationPolicy.SessionFailure.TOKEN_UNAVAILABLE);
        } catch (RuntimeException unavailableToken) {
            return sessionFailureResult(context, accountId, checkpointAppearance,
                    sessions.rememberTokenSourceFailure(context.identity()),
                    com.naocraftlab.skins.core.reconciliation.ReconciliationPolicy.SessionFailure.SOURCE_FAILED);
        }
    }

    private Optional<ReconciliationResult> sessionFailureResult(OperationContext context, UUID accountId,
            AccountAppearanceState checkpoint, SessionValidation validation,
            com.naocraftlab.skins.core.reconciliation.ReconciliationPolicy.SessionFailure failure) throws IOException {
        AccountAppearanceState appearance = delivery.afterSessionFailure(accountId, checkpoint.intentRevision(), failure);
        return Optional.of(reconciliationResult(context, appearance, validation, observedAccount(accountId), Optional.empty()));
    }

    private ReconciliationResult reconciliationAfterMutation(
            UUID accountId,
            OperationContext context,
            AccountAppearanceState settled,
            PresetApplicationOutcome outcome, AppearanceProviders expected) {
        RemoteResult remote = refreshAfterMutation(context, outcome, expected);
        try {
            settled = storage.loadAppearance(accountId);
        } catch (IOException localFailure) {
            throw new RemoteMutationSettlementException(outcome.remoteAppearanceImpact());
        }
        return new ReconciliationResult(
                remote.account(),
                remote.session(),
                remote.currentOfficialSkinId(),
                durableAppearance(accountId, context.identity(), settled, remote.session()),
                Optional.of(outcome));
    }

    @Override
    public Optional<DurableAppearance> durableAppearance() throws IOException {
        GameSessionTokenSource.SessionIdentity identity = Objects.requireNonNull(
                tokenSource.currentSession(), "current session");
        UUID accountId = identity.profileId();
        bootstrap.initialize();
        AccountAppearanceState appearance = storage.loadAppearance(accountId);
        if (!appearance.hasIntent()) {
            return Optional.empty();
        }
        return Optional.of(durableAppearance(
                accountId, identity, appearance, sessions.cachedStatus(identity)));
    }

    private ReconciliationResult reconciliationResult(
            OperationContext context,
            AccountAppearanceState appearance,
            SessionValidation validation,
            ObservedAccount observed,
            Optional<PresetApplicationOutcome> outcome) throws IOException {
        return new ReconciliationResult(
                observed.account(),
                validation,
                observed.currentOfficialSkinId(),
                durableAppearance(
                        observed.account().accountId(), context.identity(), appearance, validation),
                outcome);
    }

    private ObservedAccount observeCheckpointAccount(
            UUID accountId, SessionValidation validation, AppearanceProviders expected) throws IOException, PngValidationException {
        AccountState state = seedVanillaDefaults(library.load(accountId));
        state = library.load(accountId);
        OfficialSkinSync official = syncCurrentOfficial(state, validation);
        InitialPresetBootstrap bootstrap = createInitialPresetFromOfficialSkin(official, validation);
        AccountState observed = bootstrap.revisionMatched()
                ? bootstrap.official().state()
                : library.load(accountId);
        reconcileActivePreset(accountId, observed, validation);
        observed = library.load(accountId);
        if (validation.valid() && validation.profile() != null) {
            publishOwnedCapeInventory(accountId, validation.profile());
            observeMinecraftProviders(accountId, validation, expected, true, true);
            observeProfileValidated(observed);
        } else {
            observeLocal(observed);
        }
        return new ObservedAccount(
                observed, latestOfficialAsset(observed).map(SkinAsset::id));
    }

    private MinecraftObservation observeMinecraftProviders(
            UUID accountId, SessionValidation validation, AppearanceProviders expected,
            boolean readSkin, boolean readCape) throws IOException {
        if (!validation.valid() || validation.profile() == null
                || !accountId.equals(validation.profile().id())) {
            return new MinecraftObservation(null, null);
        }
        Optional<ActiveAppearance> actual = readSkin ? activeAppearance(validation.profile()) : Optional.empty();
        ProviderSkin skin = actual.filter(value -> !value.accountDefault())
                .map(value -> new ProviderSkin(value.skinSha256(), value.variant())).orElse(null);
        RemoteCape activeCape = validation.profile().activeCape().orElse(null);
        ProviderCape cape = !readCape || activeCape == null ? null : new ProviderCape(activeCape.id(),
                cachedLocalCapeKey(accountId, activeCape.id(), validation).orElse(null),
                storage.loadOwnedCapes(accountId).find(activeCape.id()).map(OwnedCapeEntry::hasElytra).orElse(null));
        ProviderObservation<?>[] accepted = new ProviderObservation<?>[2];
        storage.updateAppearance(accountId, current -> {
            boolean acceptSkin = actual.isPresent()
                        && current.providers().skin().intentRevision() == expected.skin().intentRevision()
                        && current.providers().skin().minecraftDelivery().activation()
                                == expected.skin().minecraftDelivery().activation();
            boolean acceptCape = readCape && !sessions.capeStateUnknown(accountId)
                    && current.providers().cape().intentRevision() == expected.cape().intentRevision()
                    && current.providers().cape().minecraftDelivery().activation()
                            == expected.cape().minecraftDelivery().activation();
            if (acceptSkin) accepted[0] = ProviderObservation.observed(skin);
            if (acceptCape) accepted[1] = ProviderObservation.observed(cape);
            return current.withProviders(new AppearanceProviders(
                    acceptSkin ? current.providers().skin().observeMinecraft(skin) : current.providers().skin(),
                    acceptCape ? current.providers().cape().observeMinecraft(cape) : current.providers().cape()));
        });
        return new MinecraftObservation(accepted[0], accepted[1]);
    }

    private record MinecraftObservation(ProviderObservation<?> skin,
            ProviderObservation<?> cape) {}

    private ObservedAccount observedAccount(UUID accountId) throws IOException {
        AccountState account = library.load(accountId);
        return new ObservedAccount(account, latestOfficialAsset(account).map(SkinAsset::id));
    }

    private DurableAppearance durableAppearance(
            UUID accountId,
            GameSessionTokenSource.SessionIdentity identity,
            AccountAppearanceState appearance,
            SessionValidation validation) {
        return new DurableAppearance(
                accountId,
                appearance.intentRevision(),
                appearance.syncStatus(),
                appearance.optionalActivePresetId(),
                materializeLocalAppearance(accountId, identity.profileId(), appearance, validation),
                appearance.optionalOuterLayerVisibility(), appearance.providers());
    }

    private static boolean appearanceMatches(
            AccountAppearanceState expected, ActiveAppearance actual) {
        return (!needsMinecraftDelivery(expected.providers().cape())
                        || Objects.equals(expected.capeId(), actual.capeId()))
                && skinMatches(expected, actual);
    }

    private static boolean skinMatches(
            AccountAppearanceState expected, ActiveAppearance actual) {
        return !needsMinecraftDelivery(expected.providers().skin())
                || (expected.skinSha256() == null
                        ? actual.accountDefault()
                        : !actual.accountDefault()
                                && expected.skinSha256().equals(actual.skinSha256())
                                && expected.skinVariant() == actual.variant());
    }

    @Override
    public RemoteResult retryCape(String capeId) throws IOException, PngValidationException {
        OperationContext context = pinCurrentSession();
        UUID accountId = resolveAccountId(context.identity());
        bootstrap.initialize();
        AccountAppearanceState appearance = storage.loadAppearance(accountId);
        if (!appearance.hasIntent()
                || appearance.syncStatus() != AppearanceSyncStatus.PARTIAL
                || !Objects.equals(appearance.capeId(), capeId)) {
            throw new IllegalStateException(
                    "Cape recovery requires the matching current PARTIAL appearance intent");
        }
        ReconciliationKey key = new ReconciliationKey(accountId, appearance.intentRevision(),
                appearance.providers().skin().minecraftDelivery().activation(),
                appearance.providers().cape().minecraftDelivery().activation());
        ReconciliationResult reconciled = reconcileAppearance(
                        context, key, Trigger.EXPLICIT_RETRY)
                .orElseThrow(() -> new IllegalStateException(
                        "Local appearance changed before cape recovery"));
        return legacyRemoteResult(reconciled, "Cape recovery completed without a remote mutation.");
    }

    @Override
    public RemoteResult restorePreviousAppearance(PresetApplicationOutcome outcome)
            throws IOException, PngValidationException {
        Objects.requireNonNull(outcome, "outcome");
        throw new UnsupportedOperationException(
                "Snapshot restore is unavailable; retry the current durable appearance intent instead");
    }

    @Override
    public byte[] loadSkinPreview(UUID skinId) throws IOException, PngValidationException {
        UUID accountId = resolveAccountId(pinCurrentSession().identity());
        AccountState state = library.load(accountId);
        SkinAsset asset = library.findSkin(state, Objects.requireNonNull(skinId, "skinId"));
        return assets.readAsset(asset.sha256());
    }

    @Override
    public Optional<byte[]> loadCapePreview(String capeId) throws IOException {
        Objects.requireNonNull(capeId, "capeId");
        OperationContext context = pinCurrentSession();
        SessionValidation validation = sessions.cachedStatus(context.identity());
        if (validation.valid() && validation.profile() != null) {
            Optional<RemoteCape> cape = validation.profile().capes().stream()
                    .filter(candidate -> candidate.id().equals(capeId))
                    .findFirst();
            if (cape.isPresent()) {
                return Optional.of(textures.load(cape.orElseThrow().textureUri()));
            }
        }
        UUID accountId = resolveAccountId(context.identity());
        return storage.loadOwnedCapes(accountId)
                .find(capeId)
                .flatMap(OwnedCapeEntry::optionalTextureCacheKey)
                .flatMap(key -> {
                    try {
                        return textures.readIfCached(key);
                    } catch (IOException invalidCacheEntry) {
                        return Optional.empty();
                    }
                });
    }

    @Override
    public InitialData retrySession() throws IOException, PngValidationException {

        return initializeFresh(
                pinCurrentSession(), SessionClassification.MANUAL_RETRY);
    }

    @Override
    public boolean rateLimited() {
        return profileApi.rateLimitRemaining().isPresent();
    }

    @Override
    public Optional<java.time.Duration> rateLimitRemaining() {
        return profileApi.rateLimitRemaining();
    }

    private enum SessionClassification {
        CACHED,
        FRESH_CHECKPOINT,
        MANUAL_RETRY
    }

    @Override
    public GameSessionTokenSource.SessionIdentity sessionIdentity() {
        return tokenSource.currentSession();
    }

    @Override
    public Optional<AppliedAppearance> acknowledgedAppearance() {
        GameSessionTokenSource.SessionIdentity identity = tokenSource.currentSession();
        return sessions.acknowledgedAppearance(identity);
    }

    @Override
    public void rememberActivePreset(UUID accountId, Optional<UUID> presetId) {
        ClientOperations.super.rememberActivePreset(accountId, presetId);
    }

    private RemoteResult legacyRemoteResult(ReconciliationResult reconciled, String noMutationMessage) {
        PresetApplicationOutcome outcome = reconciled.outcome().orElseGet(() -> {
            boolean official = reconciled.appearance().syncStatus() == AppearanceSyncStatus.OFFICIAL;
            RemoteProfile profile = reconciled.session().profile();
            return new PresetApplicationOutcome(
                    official ? MutationResult.APPLIED : MutationResult.FAILED,
                    official ? ApplicationPhase.COMPLETE : ApplicationPhase.VALIDATION,
                    profile,
                    profile,
                    reconciled.appearance().localAppearance().orElse(null),
                    official ? null : reconciled.session().failureKind(),
                    Set.of(),
                    RemoteAppearanceImpact.NONE,
                    noMutationMessage);
        });
        return new RemoteResult(
                outcome,
                reconciled.account(),
                reconciled.session(),
                reconciled.currentOfficialSkinId());
    }

    private RemoteResult refreshAfterMutation(
            OperationContext context, PresetApplicationOutcome outcome, AppearanceProviders expected) {
        try {
            UUID accountId = resolveAccountId(context.identity());
            AccountState state = library.load(accountId);
            SessionValidation validation = sessions.currentStatus(context.tokens());
            OfficialSkinSync official = syncCurrentOfficial(state, validation);
            observeMinecraftProviders(accountId, validation, expected, true, true);
            RemoteResult result = new RemoteResult(
                    outcome,
                    official.state(),
                    validation,
                    Optional.ofNullable(official.currentOfficialSkinId()));
            observeLocal(result.account());
            return result;
        } catch (IOException | RuntimeException localFailure) {
            throw new RemoteMutationSettlementException(outcome.remoteAppearanceImpact());
        }
    }

    private AccountState seedVanillaDefaults(AccountState initial)
            throws IOException, PngValidationException {
        AccountState withClassic = ensureLibraryAsset(
                        initial,
                        "Steve",
                        SkinVariant.CLASSIC,
                        SkinSource.VANILLA_DEFAULT,
                        bundledSkins.classic())
                .state();
        return ensureLibraryAsset(
                        withClassic,
                        "Alex",
                        SkinVariant.SLIM,
                        SkinSource.VANILLA_DEFAULT,
                        bundledSkins.slim())
                .state();
    }

    private OfficialSkinSync syncCurrentOfficial(AccountState state, SessionValidation validation) {
        return syncCurrentOfficial(state, validation, true);
    }

    private OfficialSkinSync syncCurrentOfficial(AccountState state, SessionValidation validation, boolean loadRemote) {
        UUID currentId = latestOfficialAsset(state).map(SkinAsset::id).orElse(null);
        try {
            if (!storage.loadAppearance(state.accountId()).providers().skin().enabled(BuiltinProvider.MINECRAFT)) {
                return new OfficialSkinSync(state, currentId, false);
            }
        } catch (IOException unavailableState) {
            return new OfficialSkinSync(state, currentId, false);
        }
        if (validation.status() != SessionStatus.VALID
                || validation.profile() == null
                || !state.accountId().equals(validation.sessionIdentity().profileId())
                || !state.accountId().equals(validation.profile().id())) {
            return new OfficialSkinSync(state, currentId, false);
        }
        Optional<ResolvedOfficialSkin> observation = resolveOfficialSkin(validation.profile(), loadRemote);
        if (observation.isEmpty() || observation.orElseThrow().classification() == OfficialSkinClassifier.Result.UNKNOWN) {
            return new OfficialSkinSync(state, currentId, false);
        }
        ResolvedOfficialSkin skin = observation.orElseThrow();
        if (skin.classification() == OfficialSkinClassifier.Result.DEFAULT) {
            return new OfficialSkinSync(state, null, true);
        }
        Optional<SkinAsset> existing = skin.source().localSkinSha256().isPresent()
                ? state.skinAssets().stream()
                        .filter(asset -> asset.sha256().equals(skin.sha256()) && asset.variant() == skin.variant())
                        .findFirst()
                : Optional.empty();
        if (existing.isPresent()) {
            return new OfficialSkinSync(state, existing.orElseThrow().id(), true);
        }
        try {
            SeededAsset seeded = ensureLibraryAsset(state, "Current official", skin.variant(),
                    SkinSource.CURRENT_OFFICIAL, skin.pngBytes());
            return new OfficialSkinSync(seeded.state(), seeded.asset().id(), true);
        } catch (IOException | PngValidationException unavailableTexture) {
            return new OfficialSkinSync(state, currentId, false);
        }
    }

    private InitialPresetBootstrap createInitialPresetFromOfficialSkin(
            OfficialSkinSync official, SessionValidation validation) throws IOException {
        AccountState state = official.state();
        AccountAppearanceState durable = storage.loadAppearance(state.accountId());
        if (durable.hasIntent()
                || !state.presets().isEmpty()
                || !official.currentSkinVerified()
                || official.currentOfficialSkinId() == null
                || validation.status() != SessionStatus.VALID
                || validation.profile() == null
                || !state.accountId().equals(validation.sessionIdentity().profileId())
                || !state.accountId().equals(validation.profile().id())) {
            return new InitialPresetBootstrap(official, true);
        }

        RemoteProfile profile = validation.profile();
        final AppliedAppearance current;
        try {
            current = sessions.currentAppliedAppearance(profile);
        } catch (IllegalStateException unknownAcknowledgedAppearance) {
            return new InitialPresetBootstrap(official, true);
        }
        if (current.usesAccountDefaultSkin()) {
            return new InitialPresetBootstrap(official, true);
        }

        String capeId = profile.activeCape().map(RemoteCape::id).orElse(null);
        LibraryService.InitialPresetCreation creation = library.createInitialPresetIfEmpty(
                state.accountId(),
                profile.name(),
                SkinReference.asset(official.currentOfficialSkinId()),
                capeId,
                state.updatedAt());
        return new InitialPresetBootstrap(
                new OfficialSkinSync(
                        creation.state(),
                        official.currentOfficialSkinId(),
                        official.currentSkinVerified()),
                creation.revisionMatched());
    }

    private Optional<UUID> reconcileActivePreset(
            UUID accountId, AccountState state, SessionValidation validation)
            throws IOException, PngValidationException {
        if (!state.accountId().equals(accountId)) {
            throw new IllegalArgumentException("account state does not belong to the pinned session");
        }
        AccountAppearanceState durable = storage.loadAppearance(accountId);
        Optional<UUID> durablePreset = durable.optionalActivePresetId()
                .filter(id -> state.presets().stream().anyMatch(preset -> preset.id().equals(id)));
        if (durable.hasIntent()) {

            return durablePreset;
        }
        if (validation.status() != SessionStatus.VALID
                || validation.profile() == null
                || !state.accountId().equals(validation.sessionIdentity().profileId())
                || !state.accountId().equals(validation.profile().id())) {
            return durablePreset;
        }
        Optional<ActiveAppearance> actual = activeAppearance(validation.profile());
        if (actual.isEmpty()) {
            return durablePreset;
        }
        ActiveAppearance appearance = actual.orElseThrow();
        List<AppearancePreset> matches = state.presets().stream()
                .filter(preset -> presetMatches(state, preset, appearance))
                .sorted(Comparator.comparing(AppearancePreset::updatedAt)
                        .reversed()
                        .thenComparing(AppearancePreset::createdAt, Comparator.reverseOrder())
                        .thenComparing(AppearancePreset::id))
                .toList();
        UUID tracked = durable.activePresetId();
        Optional<UUID> matched = matches.stream()
                .filter(preset -> preset.id().equals(tracked))
                .findFirst()
                .or(() -> matches.stream().findFirst())
                .map(AppearancePreset::id);
        if (matched.isPresent()) {
            UUID matchedId = matched.orElseThrow();
            if (durablePreset.filter(matchedId::equals).isEmpty()) {
                return recordAppearance(accountId, state, matchedId, AppearanceSyncStatus.OFFICIAL)
                        .optionalActivePresetId();
            }
        }
        return matched.isPresent() ? matched : durablePreset;
    }

    private AccountAppearanceState recordAppearance(
            UUID accountId,
            AccountState state,
            UUID presetId,
            AppearanceSyncStatus status) throws IOException, PngValidationException {
        return recordAppearance(accountId, state, library.findPreset(state, presetId), status);
    }

    private AccountAppearanceState recordAppearance(
            UUID accountId,
            AccountState state,
            AppearancePreset preset,
            AppearanceSyncStatus status) throws IOException, PngValidationException {
        ResolvedSkinAsset resolved = preset.skin().optionalAssetId().isPresent()
                ? library.resolveSkin(state, preset.skin().assetId())
                : null;
        OwnedCapeInventory inventory = storage.loadOwnedCapes(accountId);
        return storage.updateAppearance(accountId, current -> {
            if (current.hasIntent()) {
                return current;
            }
            long revision = Math.incrementExact(current.intentRevision());
            return new AccountAppearanceState(
                        AccountAppearanceState.CURRENT_SCHEMA_VERSION,
                        accountId,
                        revision,
                        preset.id(),
                        resolved == null ? null : resolved.sha256(),
                        resolved == null ? null : resolved.variant(),
                        preset.capeId(),
                        preset.outerLayerVisibility(),
                        status,
                        status == AppearanceSyncStatus.OFFICIAL ? revision : 0,
                        clock.instant(), current.providers().bootstrap(revision,
                                resolved == null ? null : new ProviderSkin(resolved.sha256(), resolved.variant()),
                                localProviderCape(preset.offlineCape()), providerCape(preset.capeId(), inventory)));
        });
    }

    private AccountAppearanceState recordAccountDefaultAppearance(
            UUID accountId, AppearanceSyncStatus status) throws IOException {
        return storage.updateAppearanceIntent(accountId, (current, revision) ->
                accountDefaultAppearance(accountId, revision, status, current.providers()));
    }

    private AccountAppearanceState accountDefaultAppearance(
            UUID accountId,
            long revision,
            AppearanceSyncStatus status,
            AppearanceProviders providers) {
        return new AccountAppearanceState(
                AccountAppearanceState.CURRENT_SCHEMA_VERSION,
                accountId,
                revision,
                null,
                null,
                null,
                null,
                OuterLayerVisibility.allVisible(),
                status,
                status == AppearanceSyncStatus.OFFICIAL ? revision : 0,
                clock.instant(), providers.select(revision, null, null));
    }

    private Optional<AppliedAppearance> materializeLocalAppearance(
            UUID accountId,
            UUID runningProfileId,
            AccountAppearanceState appearance,
            SessionValidation validation) {
        AppearanceProviders providers = appearance.providers();
        ProviderSkin skin = providers.skin().resolve().map(value -> value.value()).orElse(null);
        ProviderCape cape = providers.cape().resolve().map(value -> value.value()).orElse(null);
        Optional<String> capeKey = cape == null ? Optional.empty() : cape.optionalTextureCacheKey()
                .or(() -> cachedLocalCapeKey(accountId, cape.id(), validation));
        if (skin != null) {
            return Optional.of(AppliedAppearance.localSkin(
                    runningProfileId, skin.sha256(), skin.variant(), Optional.empty(), capeKey)
                    .withCapeElytra(cape == null || !Boolean.FALSE.equals(cape.hasElytra())));
        }
        return Optional.of(AppliedAppearance.accountDefault(runningProfileId, Optional.empty(), capeKey)
                .withCapeElytra(cape == null || !Boolean.FALSE.equals(cape.hasElytra())));
    }

    private Optional<String> cachedLocalCapeKey(
            UUID accountId, String capeId, SessionValidation validation) {
        try {
            Optional<String> persisted = storage.loadOwnedCapes(accountId)
                    .find(capeId)
                    .flatMap(OwnedCapeEntry::optionalTextureCacheKey);
            if (persisted.isPresent()
                    && textures.readIfCached(persisted.orElseThrow()).isPresent()) {
                return persisted;
            }
            if (!validation.valid()
                    || validation.profile() == null
                    || !accountId.equals(validation.profile().id())) {
                return Optional.empty();
            }
            Optional<java.net.URI> verifiedTexture = validation.profile().capes().stream()
                    .filter(cape -> cape.id().equals(capeId))
                    .map(RemoteCape::textureUri)
                    .findFirst();
            if (verifiedTexture.isPresent()
                    && textures.readIfCached(verifiedTexture.orElseThrow()).isPresent()) {
                return Optional.of(textures.cacheKey(verifiedTexture.orElseThrow()));
            }
        } catch (IOException | RuntimeException unavailableCache) {
            return Optional.empty();
        }
        return Optional.empty();
    }

    private Optional<ActiveAppearance> activeAppearance(RemoteProfile profile) {
        return resolveOfficialSkin(profile, false)
                .filter(skin -> skin.classification() != OfficialSkinClassifier.Result.UNKNOWN)
                .map(skin -> skin.classification() == OfficialSkinClassifier.Result.DEFAULT
                        ? ActiveAppearance.accountDefault(profile.activeCape().map(RemoteCape::id).orElse(null))
                        : skin.rawAppearance());
    }

    private Optional<ActiveAppearance> deliveryAppearance(RemoteProfile profile) {
        return resolveOfficialSkin(profile, false).map(ResolvedOfficialSkin::rawAppearance);
    }

    private Optional<ResolvedOfficialSkin> resolveOfficialSkin(RemoteProfile profile, boolean loadRemote) {
        if (!sessions.hasKnownSkin(profile)) {
            return Optional.empty();
        }
        try {
            AppliedAppearance applied = sessions.currentAppliedAppearance(profile);
            long generation = bundledSkins.generation();
            ResolvedOfficialSkin cached = resolvedOfficialSkin;
            if (cached != null && cached.profile() == profile && cached.source().equals(applied)
                    && cached.generation() == generation
                    && cached.classification() != OfficialSkinClassifier.Result.UNKNOWN) {
                return Optional.of(cached);
            }
            ResolvedOfficialSkin resolved;
            if (applied.usesAccountDefaultSkin()) {
                resolved = new ResolvedOfficialSkin(profile, applied, generation,
                        OfficialSkinClassifier.Result.DEFAULT, null, null, null);
            } else {
                byte[] bytes;
                SkinVariant variant = applied.skinVariant().orElseThrow();
                if (applied.localSkinSha256().isPresent()) {
                    bytes = assets.readAsset(applied.localSkinSha256().orElseThrow());
                } else {
                    RemoteSkin active = profile.activeSkin().orElseThrow();
                    if (applied.skinTexture().filter(active.textureUri()::equals).isEmpty()) {
                        return Optional.empty();
                    }
                    if (loadRemote) {
                        bytes = officialSkinTextures.load(active);
                    } else {
                        Optional<byte[]> local = textures.readIfCached(active.textureUri());
                        if (local.isEmpty()) return Optional.empty();
                        bytes = local.orElseThrow();
                    }
                }
                new PngValidator().validate(bytes);
                OfficialSkinClassifier.Result classification =
                        officialSkinClassifier.classify(profile.id(), variant, bytes);
                resolved = new ResolvedOfficialSkin(profile, applied, generation,
                        classification, sha256(bytes), variant, bytes);
            }
            resolvedOfficialSkin = resolved;
            return Optional.of(resolved);
        } catch (IOException | PngValidationException | RuntimeException unavailable) {
            return Optional.empty();
        }
    }

    private SeededAsset ensureLibraryAsset(
            AccountState state,
            String name,
            SkinVariant variant,
            SkinSource source,
            byte[] png) throws IOException, PngValidationException {
        String hash = sha256(png);
        Optional<SkinAsset> existing = state.skinAssets().stream()
                .filter(asset -> asset.source() == source)
                .filter(asset -> asset.variant() == variant)
                .filter(asset -> asset.sha256().equals(hash))
                .findFirst();
        if (existing.isPresent()) {
            return new SeededAsset(state, existing.orElseThrow());
        }
        ImportedSkin imported = library.importSkin(state.accountId(), name, variant, source, png);
        return new SeededAsset(imported.state(), imported.asset());
    }

    private static boolean presetMatches(
            AccountState state, AppearancePreset preset, ActiveAppearance appearance) {
        if (!Objects.equals(preset.capeId(), appearance.capeId())) {
            return false;
        }
        if (preset.skin().kind() == SkinReference.Kind.ACCOUNT_DEFAULT) {
            return appearance.accountDefault();
        }
        if (appearance.accountDefault()) {
            return false;
        }
        return state.skinAssets().stream()
                .filter(asset -> asset.id().equals(preset.skin().assetId()))
                .anyMatch(asset -> asset.sha256().equals(appearance.skinSha256())
                        && asset.variant() == appearance.variant());
    }

    private static Optional<SkinAsset> latestOfficialAsset(AccountState state) {
        return state.skinAssets().stream()
                .filter(asset -> asset.source() == SkinSource.CURRENT_OFFICIAL)
                .max(Comparator.comparing(SkinAsset::updatedAt));
    }

    private AccountState observeLocal(AccountState state) {
        libraryObservations.put(state.accountId(), LibraryObservation.from(state, false));
        return state;
    }

    private AccountState observeProfileValidated(AccountState state) {
        libraryObservations.put(state.accountId(), LibraryObservation.from(state, true));
        return state;
    }

    private OperationContext pinCurrentSession() {
        GameSessionTokenSource.SessionIdentity identity = Objects.requireNonNull(
                tokenSource.currentSession(), "current session");
        return new OperationContext(identity, new PinnedTokenSource(tokenSource, identity));
    }

    private static UUID resolveAccountId(GameSessionTokenSource.SessionIdentity identity) {
        return Objects.requireNonNull(identity, "identity").profileId();
    }

    static String normalizeName(String value, String fallback) {
        String normalized = value == null ? "" : value.trim();
        return normalized.isEmpty() ? fallback : normalized;
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private record SeededAsset(AccountState state, SkinAsset asset) {}

    private record OfficialSkinSync(
            AccountState state, UUID currentOfficialSkinId, boolean currentSkinVerified) {}

    private record InitialPresetBootstrap(
            OfficialSkinSync official, boolean revisionMatched) {}

    private record LibraryObservation(
            Instant updatedAt, boolean presetsEmpty, boolean emptyProfileValidated) {
        private static LibraryObservation from(
                AccountState state, boolean profileValidated) {
            boolean empty = state.presets().isEmpty();
            return new LibraryObservation(state.updatedAt(), empty, empty && profileValidated);
        }

        private boolean matches(AccountState state) {
            return updatedAt.equals(state.updatedAt())
                    && presetsEmpty == state.presets().isEmpty();
        }
    }

    private record OperationContext(
            GameSessionTokenSource.SessionIdentity identity,
            GameSessionTokenSource tokens) {}

    @FunctionalInterface
    private interface ScopedReconciliation {
        Optional<ReconciliationResult> execute(OperationContext scopedContext)
                throws IOException, PngValidationException;
    }

    private record ActiveAppearance(
            boolean accountDefault,
            String skinSha256,
            SkinVariant variant,
            String capeId) {
        private static ActiveAppearance local(String sha256, SkinVariant variant, String capeId) {
            return new ActiveAppearance(false, sha256, variant, capeId);
        }

        private static ActiveAppearance accountDefault(String capeId) {
            return new ActiveAppearance(true, null, null, capeId);
        }
    }

    private record ResolvedOfficialSkin(
            RemoteProfile profile, AppliedAppearance source, long generation,
            OfficialSkinClassifier.Result classification, String sha256,
            SkinVariant variant, byte[] pngBytes) {
        private ActiveAppearance rawAppearance() {
            String capeId = profile.activeCape().map(RemoteCape::id).orElse(null);
            return sha256 == null ? ActiveAppearance.accountDefault(capeId)
                    : ActiveAppearance.local(sha256, variant, capeId);
        }
    }

    private record PresetValues(
            AppearancePreset preset,
            ProviderSkin skin,
            ProviderCape offlineCape,
            ProviderCape minecraftCape,
            String capeId) {}

    @FunctionalInterface
    interface OfficialSkinTextureSource {
        byte[] load(RemoteSkin skin) throws IOException;
    }

}

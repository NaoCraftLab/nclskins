package com.naocraftlab.skins.runtime;

import com.naocraftlab.skins.client.BundledSkinSource;
import com.naocraftlab.skins.client.CapeCatalogSource;
import com.naocraftlab.skins.client.CatalogCollectionOrder;
import com.naocraftlab.skins.client.CatalogText;
import com.naocraftlab.skins.client.ClientExecutor;
import com.naocraftlab.skins.client.GameSessionTokenSource;
import com.naocraftlab.skins.client.GameSessionIdentityChangedException;
import com.naocraftlab.skins.client.GameSessionTokenUnavailableException;
import com.naocraftlab.skins.client.OuterLayerVisibility;
import com.naocraftlab.skins.client.PersonalSkinCatalog;
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
import com.naocraftlab.skins.core.model.PersonalSkinEntry;
import com.naocraftlab.skins.core.model.PersonalSkinSource;
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
import com.naocraftlab.skins.core.storage.AccountUiPreferencesResult;
import com.naocraftlab.skins.core.storage.CachedTexture;
import com.naocraftlab.skins.core.storage.NclSkinsStorage;
import com.naocraftlab.skins.core.storage.StorageInitialization;
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
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;


public final class DefaultClientOperations implements ClientOperations {
    private static final int MAX_BOOTSTRAP_CAS_ATTEMPTS = 3;
    private final GameSessionTokenSource tokenSource;
    private final ProfileApi profileApi;
    private final NclSkinsStorage storage;
    private final SkinCatalogSource bundledSkins;
    private final Clock clock;
    private final LibraryService library;
    private final RemoteSessionGate sessionGate;
    private final SessionValidationService sessions;
    private final AppearanceMutationService mutations;
    private final TextureCache textures;
    private final PublicSkinImportService publicImports;
    private final ExternalAppearanceImportService externalImports;
    private final OfficialSkinTextureSource officialSkinTextures;
    private final OfficialSkinClassifier officialSkinClassifier;
    private volatile ResolvedOfficialSkin resolvedOfficialSkin;
    private volatile OptifineCapeCoordinator optifineCapes;
    private volatile ExecutorService optifineWorker;

    private final Map<UUID, LibraryObservation> libraryObservations = new ConcurrentHashMap<>();

    private volatile CatalogSnapshot catalogSnapshot = CatalogSnapshot.empty();

    private volatile CatalogDiscoveryCache catalogDiscoveryCache;

    private volatile ResourceCapeDiscovery resourceCapeDiscovery = ResourceCapeDiscovery.empty();

    private volatile ResourceCapeSnapshot resourceCapeSnapshot = ResourceCapeSnapshot.empty();

    private volatile CapeEditorData warmedCapeEditorData;

    private volatile UUID warmedCapePreviewAccountId;

    private volatile Map<String, byte[]> warmedCapePreviews = Map.of();

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
        this.profileApi = Objects.requireNonNull(profileApi, "profileApi");
        this.storage = Objects.requireNonNull(storage, "storage");
        this.bundledSkins = Objects.requireNonNull(bundledSkins, "bundledSkins");
        this.officialSkinClassifier = new OfficialSkinClassifier(bundledSkins);
        this.clock = Objects.requireNonNull(clock, "clock");
        this.library = new LibraryService(storage, clock);
        this.sessionGate = new RemoteSessionGate();
        this.sessions = new SessionValidationService(profileApi, sessionGate);
        this.mutations = new AppearanceMutationService(profileApi, storage, sessionGate, sessions);
        this.textures = new TextureCache(storage);
        this.publicImports = new PublicSkinImportService(this.textures, this::loadCatalogSkin);
        this.externalImports = new ExternalAppearanceImportService(
                this.library, this.publicImports, this.bundledSkins);
        this.officialSkinTextures = officialSkinTextures != null
                ? officialSkinTextures
                : skin -> this.textures.read(this.textures.get(skin));
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
        optifineWorker = Executors.newFixedThreadPool(4, action -> {
            Thread thread = new Thread(action, "nclskins-optifine-cape-" + threadIndex.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
        optifineCapes = new OptifineCapeCoordinator(tokenSource, storage, textures,
                sink, clientExecutor, optifineWorker, this::verifiedOfficialCapeUri);
        CapeProjection.installEvents(new CapeProjection.Events() {
            @Override
            public void trackedPlayer(UUID profileId, String canonicalName) {
                clientExecutor.execute(() -> optifineCapes.trackedPlayer(profileId, canonicalName));
            }

            @Override
            public void playerInfoUpdated(UUID profileId, String canonicalName) {
                clientExecutor.execute(() -> optifineCapes.playerInfoUpdated(profileId, canonicalName));
            }

            @Override
            public void untrackedPlayer(UUID profileId) {
                clientExecutor.execute(() -> optifineCapes.untrackedPlayer(profileId));
            }

            @Override
            public void worldChanged() {
                clientExecutor.execute(() -> optifineCapes.worldChanged());
            }

            @Override
            public void worldEntered() {
                clientExecutor.execute(() -> optifineCapes.worldEntered());
            }
        });
        return this;
    }

    @Override
    public void startOptiFineCapes() {
        if (optifineCapes != null) optifineCapes.start();
    }

    @Override
    public void refreshOptiFineCapes() {
        if (optifineCapes != null) optifineCapes.refresh();
    }

    @Override
    public void refreshOptiFineCapes(Consumer<ProviderObservation<ProviderCape>> completion) {
        if (optifineCapes != null) optifineCapes.refresh(completion);
        else completion.accept(null);
    }

    @Override
    public void optiFineConfigurationChanged() {
        if (optifineCapes != null) optifineCapes.configurationChanged();
    }

    @Override
    public void adoptSharedCapeObservation(UUID accountId, String canonicalName,
            AppearanceProviders providers) {
        if (optifineCapes != null) optifineCapes.adoptSharedSnapshot(accountId, canonicalName, providers);
    }

    @Override
    public void onOptiFineObservation(Consumer<OptiFineObservation> listener) {
        if (optifineCapes != null) optifineCapes.onSelfObservation(listener);
    }

    @Override
    public void selfCapeCandidatesChanged(UUID accountId, String canonicalName,
            AppearanceProviders providers) {
        if (optifineCapes != null) optifineCapes.selfCapeCandidatesChanged(accountId,
                canonicalName, providers);
    }

    @Override
    public void trackedCapePlayer(UUID profileId, String canonicalName) {
        if (optifineCapes != null) optifineCapes.trackedPlayer(profileId, canonicalName);
    }

    @Override
    public void untrackedCapePlayer(UUID profileId) {
        if (optifineCapes != null) optifineCapes.untrackedPlayer(profileId);
    }

    @Override
    public void capeWorldChanged() {
        if (optifineCapes != null) optifineCapes.worldChanged();
    }

    @Override
    public void closeOptiFineCapes() {
        CapeProjection.clearEvents();
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
        return new DeterministicAppearanceAssetResolver(tokenSource, storage, textures, worker);
    }

    @Override
    public void verifyStorageAccess() throws IOException {
        storage.initialize();
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
        StorageInitialization initialization = storage.initialize();
        return initializeFreshLocked(context, initialization, sessionClassification);
    }

    private InitialData initializeFreshLocked(
            OperationContext context,
            StorageInitialization initialization,
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
            StorageInitialization initialization, AppearanceProviders observedConfiguration) throws IOException, PngValidationException {
        Optional<UUID> activePreset = reconcileActivePreset(accountId, official.state(), validation);
        observeMinecraftProviders(accountId, validation, observedConfiguration, true, true);
        AccountAppearanceState appearance = storage.loadAppearance(accountId);
        Optional<AppliedAppearance> localAppearance = materializeLocalAppearance(
                accountId, validation.sessionIdentity().profileId(), appearance, validation);
        AccountUiPreferencesResult uiPreferences = storage.loadUiPreferences(accountId);
        List<String> warnings = new ArrayList<>(
                initialization.warnings().stream().map(warning -> warning.message()).toList());
        uiPreferences.warnings().stream()
                .map(warning -> warning.message())
                .filter(warning -> !warnings.contains(warning))
                .forEach(warnings::add);
        OwnedCapeInventory ownedCapes = validation.valid() && validation.profile() != null
                ? publishOwnedCapeInventory(accountId, validation.profile())
                : storage.loadOwnedCapes(accountId);
        long capeGeneration = capeCatalogGeneration();
        ResourceCapeDiscovery warmedDiscovery = resourceCapeDiscovery;
        if (capeGeneration != Long.MIN_VALUE
                && warmedDiscovery.generation() == capeGeneration) {
            publishCapeEditorData(
                    accountId, official.state(), capeGeneration, warmedDiscovery);
        }
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
                        previous.find(cape.id()).filter(entry -> Objects.equals(entry.textureCacheKey(), TextureCache.cacheKey(cape.textureUri())))
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
                return TextureCache.cacheKey(cape.textureUri());
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
        UUID accountId = resolveAccountId(pinCurrentSession().identity());
        AccountState account = library.load(accountId);
        long generation = bundledSkins.generation();
        CatalogDiscoveryCache cached = catalogDiscoveryCache;
        CatalogDiscovery discovery;
        if (generation != Long.MIN_VALUE
                && cached != null
                && cached.matches(accountId, account.personalSkins(), generation)) {
            discovery = cached.discovery();
        } else {
            discovery = discoverAvailableCatalogCollections(accountId, account);
            catalogDiscoveryCache = generation == Long.MIN_VALUE
                    ? null
                    : new CatalogDiscoveryCache(
                            accountId, account.personalSkins(), generation, discovery);
        }
        catalogSnapshot = new CatalogSnapshot(
                accountId,
                discovery.variantHashes(),
                discovery.personalAssets(),
                discovery.featureEvidence());
        return discovery.collections();
    }

    @Override
    public Map<ClientOperations.CatalogVariant, SkinFeatureEvidence> catalogFeatureEvidence() {
        return catalogSnapshot.featureEvidence();
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
                    validator.projectStoredRender(storage.readAsset(asset.sha256()))
                            .featureEvidence());
        }
        return Map.copyOf(evidence);
    }

    @Override
    public boolean supportsAssetFeatureEvidence() {
        return true;
    }

    private CatalogDiscovery discoverAvailableCatalogCollections(
            UUID accountId, AccountState account) {
        List<SkinCatalogSource.CollectionDescriptor> collections = new ArrayList<>();
        Map<CatalogVariantKey, String> variantHashes = new HashMap<>();
        Map<CatalogVariantKey, UUID> personalAssets = new HashMap<>();
        Map<ClientOperations.CatalogVariant, SkinFeatureEvidence> featureEvidence = new HashMap<>();
        List<PersonalSkinEntry> visiblePersonalSkins = account.personalSkins().stream()
                .filter(PersonalSkinEntry::visible)
                .sorted(Comparator.comparing(PersonalSkinEntry::addedAt)
                        .reversed()
                        .thenComparing(PersonalSkinEntry::sha256))
                .toList();
        List<SkinCatalogSource.SkinDescriptor> personalSkins = personalSkinDescriptors(
                accountId,
                visiblePersonalSkins.stream()
                        .filter(entry -> entry.source() != PersonalSkinSource.PLAYER_NAME)
                        .toList(),
                PersonalSkinCatalog.COLLECTION_ID,
                variantHashes,
                personalAssets,
                featureEvidence);
        if (!personalSkins.isEmpty()) {
            collections.add(new SkinCatalogSource.CollectionDescriptor(
                    PersonalSkinCatalog.COLLECTION_ID,
                    CatalogText.translated("nclskins.your_skins.name", "Your skins"),
                    Optional.empty(),
                    Optional.empty(),
                    personalSkins,
                    CatalogCollectionOrder.personal(PersonalSkinCatalog.SOURCE_ID)));
        }
        List<SkinCatalogSource.SkinDescriptor> otherPlayerSkins = personalSkinDescriptors(
                accountId,
                visiblePersonalSkins.stream()
                        .filter(entry -> entry.source() == PersonalSkinSource.PLAYER_NAME)
                        .toList(),
                PersonalSkinCatalog.OTHER_PLAYERS_COLLECTION_ID,
                variantHashes,
                personalAssets,
                featureEvidence);
        if (!otherPlayerSkins.isEmpty()) {
            collections.add(new SkinCatalogSource.CollectionDescriptor(
                    PersonalSkinCatalog.OTHER_PLAYERS_COLLECTION_ID,
                    CatalogText.translated(
                            "nclskins.other_players.name", "Other players' skins"),
                    Optional.empty(),
                    Optional.empty(),
                    otherPlayerSkins,
                    CatalogCollectionOrder.personal(
                            PersonalSkinCatalog.OTHER_PLAYERS_SOURCE_ID)));
        }
        for (SkinCatalogSource.CollectionDescriptor collection : bundledSkins.collections()) {
            if (PersonalSkinCatalog.isCollection(collection.id())) {
                continue;
            }
            List<SkinCatalogSource.SkinDescriptor> skins = new ArrayList<>();
            for (SkinCatalogSource.SkinDescriptor skin : collection.skins()) {
                List<SkinModel> availableModels = new ArrayList<>();
                for (SkinModel model : skin.models()) {
                    try {
                        byte[] normalized = loadCatalogSkinFromSource(
                                collection.id(), skin.id(), model);
                        variantHashes.put(
                                new CatalogVariantKey(collection.id(), skin.id(), model),
                                sha256(normalized));
                        featureEvidence.put(
                                new ClientOperations.CatalogVariant(
                                        collection.id(),
                                        skin.id(),
                                        model == SkinModel.SLIM
                                                ? SkinVariant.SLIM
                                                : SkinVariant.CLASSIC),
                                new PngValidator()
                                        .projectImport(normalized)
                                        .featureEvidence());
                        availableModels.add(model);
                    } catch (IOException | PngValidationException unavailableVariant) {

                    }
                }
                if (!availableModels.isEmpty()) {
                    skins.add(new SkinCatalogSource.SkinDescriptor(
                            skin.id(),
                            skin.nameText(),
                            skin.descriptionText(),
                            skin.authorsText(),
                            availableModels));
                }
            }
            if (!skins.isEmpty()) {
                collections.add(new SkinCatalogSource.CollectionDescriptor(
                        collection.id(),
                        collection.nameText(),
                        collection.descriptionText(),
                        collection.authorsText(),
                        skins,
                        collection.order()));
            }
        }
        return new CatalogDiscovery(
                collections, variantHashes, personalAssets, featureEvidence);
    }

    private List<SkinCatalogSource.SkinDescriptor> personalSkinDescriptors(
            UUID accountId,
            List<PersonalSkinEntry> entries,
            String collectionId,
            Map<CatalogVariantKey, String> variantHashes,
            Map<CatalogVariantKey, UUID> personalAssets,
            Map<ClientOperations.CatalogVariant, SkinFeatureEvidence> featureEvidence) {
        return entries.stream()
                .map(entry -> personalSkinDescriptor(
                        accountId,
                        entry,
                        collectionId,
                        variantHashes,
                        personalAssets,
                        featureEvidence))
                .toList();
    }

    private SkinCatalogSource.SkinDescriptor personalSkinDescriptor(
            UUID accountId,
            PersonalSkinEntry entry,
            String collectionId,
            Map<CatalogVariantKey, String> variantHashes,
            Map<CatalogVariantKey, UUID> personalAssets,
            Map<ClientOperations.CatalogVariant, SkinFeatureEvidence> featureEvidence) {
        List<SkinModel> models = new ArrayList<>(2);
        addPersonalVariant(
                accountId,
                entry,
                SkinVariant.CLASSIC,
                SkinModel.CLASSIC,
                collectionId,
                models,
                variantHashes,
                personalAssets,
                featureEvidence);
        addPersonalVariant(
                accountId,
                entry,
                SkinVariant.SLIM,
                SkinModel.SLIM,
                collectionId,
                models,
                variantHashes,
                personalAssets,
                featureEvidence);
        if (models.isEmpty()) {
            throw new IllegalStateException("Personal skin has no indexed variants");
        }
        return new SkinCatalogSource.SkinDescriptor(
                entry.sha256(),
                CatalogText.literal(entry.displayName()),
                Optional.empty(),
                Optional.empty(),
                models);
    }

    private void addPersonalVariant(
            UUID accountId,
            PersonalSkinEntry entry,
            SkinVariant variant,
            SkinModel model,
            String collectionId,
            List<SkinModel> models,
            Map<CatalogVariantKey, String> variantHashes,
            Map<CatalogVariantKey, UUID> personalAssets,
            Map<ClientOperations.CatalogVariant, SkinFeatureEvidence> featureEvidence) {
        entry.optionalAssetId(variant).ifPresent(assetId -> {
            CatalogVariantKey key = new CatalogVariantKey(
                    collectionId, entry.sha256(), model);
            models.add(model);
            variantHashes.put(key, entry.sha256());
            personalAssets.put(key, assetId);
            try {
                byte[] normalized = storage.readAsset(entry.sha256());
                featureEvidence.put(
                        new ClientOperations.CatalogVariant(
                                collectionId, entry.sha256(), variant),
                        new PngValidator()
                                .projectStoredRender(normalized)
                                .featureEvidence());
            } catch (IOException | PngValidationException unavailableAsset) {
            }
        });
    }

    @Override
    public byte[] loadCatalogSkin(String collectionId, String skinId, SkinModel model)
            throws IOException, PngValidationException {
        CatalogVariantKey key = new CatalogVariantKey(collectionId, skinId, model);
        CatalogSnapshot snapshot = catalogSnapshot;
        UUID personalAssetId = snapshot.personalAssets().get(key);
        if (PersonalSkinCatalog.isCollection(collectionId)) {
            UUID accountId = resolveAccountId(pinCurrentSession().identity());
            if (!snapshot.accountId().equals(accountId) || personalAssetId == null) {
                throw new IOException("Personal catalog selection is stale; reopen Add");
            }
            byte[] normalized = storage.readAsset(skinId);
            if (!skinId.equals(sha256(normalized))) {
                throw new IOException("Personal catalog asset changed; reopen Add");
            }
            return normalized;
        }
        byte[] normalized = loadCatalogSkinFromSource(collectionId, skinId, model);
        String expectedHash = snapshot.variantHashes().get(key);
        if (expectedHash != null && !expectedHash.equals(sha256(normalized))) {
            throw new IOException("Catalog resources changed; reopen Add to refresh the catalog");
        }
        return normalized;
    }

    @Override
    public Optional<UUID> reusableCatalogSkinAsset(
            String collectionId, String skinId, SkinModel model) throws IOException {
        CatalogVariantKey key = new CatalogVariantKey(collectionId, skinId, model);
        CatalogSnapshot snapshot = catalogSnapshot;
        if (!PersonalSkinCatalog.isCollection(collectionId)) {
            return Optional.empty();
        }
        UUID accountId = resolveAccountId(pinCurrentSession().identity());
        UUID assetId = snapshot.personalAssets().get(key);
        if (!snapshot.accountId().equals(accountId) || assetId == null) {
            throw new IOException("Personal catalog selection is stale; reopen Add");
        }
        return Optional.of(assetId);
    }

    private byte[] loadCatalogSkinFromSource(
            String collectionId, String skinId, SkinModel model)
            throws IOException, PngValidationException {
        if (PersonalSkinCatalog.isCollection(collectionId)) {
            throw new IOException("The personal catalog is not a resource-pack source");
        }
        byte[] loaded = bundledSkins.load(
                Objects.requireNonNull(collectionId, "collectionId"),
                Objects.requireNonNull(skinId, "skinId"),
                Objects.requireNonNull(model, "model"));


        return new PngValidator().normalizeSkin(
                Objects.requireNonNull(loaded, "catalog source returned null").clone());
    }

    @Override
    public Optional<AccountUiPreferences> loadUiPreferences() throws IOException {
        UUID accountId = resolveAccountId(pinCurrentSession().identity());
        return Optional.of(storage.loadUiPreferences(accountId).preferences());
    }

    @Override
    public void setSelectedProvidersTab(UUID accountId, AppearanceProviders.Component tab) throws IOException {
        storage.setSelectedProvidersTab(accountId, tab);
    }

    @Override
    public void setSelectedAddSourceTab(UUID accountId, AddSourceTab tab) throws IOException {
        storage.setSelectedAddSourceTab(accountId, tab);
    }

    @Override
    public void setSelectedAddSourceTab(AddSourceTab tab) throws IOException {
        UUID accountId = resolveAccountId(pinCurrentSession().identity());
        storage.setSelectedAddSourceTab(accountId, Objects.requireNonNull(tab, "tab"));
    }

    @Override
    public void setCollapsedCapeCollections(UUID accountId, Set<String> values) throws IOException {
        storage.setCollapsedCapeCollections(accountId, values);
    }

    @Override
    public void setSelectedEditorTab(UUID accountId, EditorTab tab) throws IOException {
        storage.setSelectedEditorTab(
                Objects.requireNonNull(accountId, "accountId"),
                Objects.requireNonNull(tab, "tab"));
    }

    @Override
    public void setCollectionCollapsed(String collectionId, boolean collapsed) throws IOException {
        UUID accountId = resolveAccountId(pinCurrentSession().identity());
        storage.setCollectionCollapsed(
                accountId, Objects.requireNonNull(collectionId, "collectionId"), collapsed);
    }

    @Override
    public void replaceCollapsedCollectionIds(Set<String> collectionIds) throws IOException {
        UUID accountId = resolveAccountId(pinCurrentSession().identity());
        storage.replaceCollapsedCollectionIds(
                accountId, Objects.requireNonNull(collectionIds, "collectionIds"));
    }

    @Override
    public void setPreferredSkinVariant(SkinVariant variant) throws IOException {
        UUID accountId = resolveAccountId(pinCurrentSession().identity());
        storage.setPreferredSkinVariant(accountId, Objects.requireNonNull(variant, "variant"));
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
        catalogDiscoveryCache = null;
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
        catalogDiscoveryCache = null;
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
                byte[] capeBytes = java.nio.file.Files.readAllBytes(textures.get(cape).path());
                Boolean classified = storage.loadOwnedCapes(accountId).find(cape.id()).map(OwnedCapeEntry::hasElytra).orElse(null);
                boolean hasElytra = classified != null ? classified
                        : new PngValidator().projectCanonicalCape(capeBytes).hasElytra();
                String cacheKey = TextureCache.cacheKey(cape.textureUri());
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

    private synchronized void publishOwnedCapePreviews(
            UUID accountId, Map<String, byte[]> owned) {
        Map<String, byte[]> previews = new HashMap<>();
        if (accountId.equals(warmedCapePreviewAccountId)) {
            warmedCapePreviews.forEach((key, bytes) -> {
                if (key.startsWith("resource:cape:")) {
                    previews.put(key, bytes);
                }
            });
        }
        owned.forEach((key, bytes) -> previews.put(key, bytes.clone()));
        warmedCapePreviewAccountId = accountId;
        warmedCapePreviews = Map.copyOf(previews);
    }

    @Override
    public InitialData resetLibrary() throws IOException, PngValidationException {
        OperationContext context = pinCurrentSession();
        UUID accountId = resolveAccountId(context.identity());
        AccountState before = library.load(accountId);
        SkinAsset previousOfficial = latestOfficialAsset(before).orElse(null);
        byte[] previousOfficialPng = previousOfficial == null
                ? null
                : storage.readAsset(previousOfficial.sha256());
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
        AccountUiPreferencesResult preferences = storage.loadUiPreferences(accountId);
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
                preferences.warnings().stream().map(warning -> warning.message()).toList(),
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
        byte[] bytes;
        try (var input = java.nio.file.Files.newInputStream(path)) {
            bytes = input.readNBytes(com.naocraftlab.skins.core.png.PngValidator.DEFAULT_MAX_BYTES + 1);
        }
        String name = UntrustedDisplayName.fromFileName(path.getFileName().toString(), fallbackName);
        var entry = storage.importCape(accountId, name, bytes);
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
        requireCapeAccount(accountId);
        AccountState account = library.load(accountId);
        long generation = bundledSkins.capeGeneration();
        ResourceCapeDiscovery discovery = frozenResourceCapeDiscovery(generation);
        return publishCapeEditorData(accountId, account, generation, discovery);
    }

    @Override
    public long capeCatalogGeneration() {
        return bundledSkins.capeGeneration();
    }

    @Override
    public void warmResourceCapeCatalog(long generation) {
        if (generation != Long.MIN_VALUE && bundledSkins.capeGeneration() == generation) {
            frozenResourceCapeDiscovery(generation);
        }
    }

    @Override
    public void warmCapeCatalog(UUID accountId, long generation) throws IOException {
        requireCapeAccount(accountId);
        if (bundledSkins.capeGeneration() != generation) {
            return;
        }
        warmResourceCapeCatalog(generation);
        ResourceCapeDiscovery discovery = frozenResourceCapeDiscovery(generation);
        if (bundledSkins.capeGeneration() == generation) {
            publishCapeEditorData(accountId, library.load(accountId), generation, discovery);
        }
    }

    @Override
    public Optional<CapeEditorData> warmedCapeEditorData(UUID accountId) {
        CapeEditorData data = warmedCapeEditorData;
        return data != null && data.account().accountId().equals(accountId)
                && data.resourceGeneration() == bundledSkins.capeGeneration()
                ? Optional.of(data)
                : Optional.empty();
    }

    @Override
    public Map<String, byte[]> warmedCapePreviews(UUID accountId) {
        if (!accountId.equals(warmedCapePreviewAccountId)) {
            return Map.of();
        }
        Map<String, byte[]> copy = new HashMap<>();
        warmedCapePreviews.forEach((key, bytes) -> copy.put(key, bytes.clone()));
        return Map.copyOf(copy);
    }

    private synchronized CapeEditorData publishCapeEditorData(
            UUID accountId, AccountState account, long generation,
            ResourceCapeDiscovery discovery) {
        resourceCapeSnapshot = new ResourceCapeSnapshot(
                accountId, generation, discovery.entries());
        CapeEditorData data = new CapeEditorData(
                account,
                discovery.collections(),
                discovery.sourceHashes(),
                generation);
        warmedCapeEditorData = data;
        Map<String, byte[]> previews = new HashMap<>();
        if (accountId.equals(warmedCapePreviewAccountId)) {
            warmedCapePreviews.forEach((key, bytes) -> {
                if (!key.startsWith("resource:cape:")) {
                    previews.put(key, bytes);
                }
            });
        }
        discovery.entries().forEach((key, entry) -> previews.put(
                resourceCapePreviewKey(generation, key, entry.sourceSha256()),
                entry.previewBytes()));
        warmedCapePreviewAccountId = accountId;
        warmedCapePreviews = Map.copyOf(previews);
        return data;
    }

    private synchronized ResourceCapeDiscovery frozenResourceCapeDiscovery(long generation) {
        ResourceCapeDiscovery cached = resourceCapeDiscovery;
        if (generation != Long.MIN_VALUE && cached.generation() == generation) {
            return cached;
        }
        List<CapeCatalogSource.CollectionDescriptor> collections = new ArrayList<>();
        Map<ResourceCapeKey, ResourceCapeSnapshotEntry> entries = new HashMap<>();
        Map<ResourceCapeKey, String> sourceHashes = new HashMap<>();
        PngValidator validator = new PngValidator();
        for (CapeCatalogSource.CollectionDescriptor collection : bundledSkins.capeCollections()) {
            List<CapeCatalogSource.CapeDescriptor> capes = new ArrayList<>();
            for (CapeCatalogSource.CapeDescriptor cape : collection.capes()) {
                try {
                    byte[] bytes = bundledSkins.loadCape(collection.id(), cape.id());
                    PngValidator.CapePng projection = validator.projectCape(bytes);
                    ResourceCapeKey key = new ResourceCapeKey(collection.id(), cape.id());
                    String sourceHash = sha256(bytes);
                    CapeCatalogSource.RenderSupport support = projection.hasElytra()
                            ? CapeCatalogSource.RenderSupport.CAPE_AND_ELYTRA
                            : CapeCatalogSource.RenderSupport.CAPE_ONLY;
                    capes.add(new CapeCatalogSource.CapeDescriptor(
                            cape.id(),
                            cape.nameText(),
                            cape.descriptionText(),
                            cape.authorsText(),
                            projection.renderSha256(),
                            support));
                    entries.put(key, new ResourceCapeSnapshotEntry(
                            projection.renderSha256(), sourceHash, projection.hasElytra(),
                            bytes, projection.bytes()));
                    sourceHashes.put(key, sourceHash);
                } catch (IOException | PngValidationException | RuntimeException unavailable) {
                }
            }
            if (!capes.isEmpty()) {
                collections.add(new CapeCatalogSource.CollectionDescriptor(
                        collection.id(),
                        collection.nameText(),
                        collection.descriptionText(),
                        collection.authorsText(),
                        capes,
                        collection.order()));
            }
        }
        ResourceCapeDiscovery discovered = new ResourceCapeDiscovery(
                generation, collections, entries, sourceHashes);
        resourceCapeDiscovery = generation == Long.MIN_VALUE
                ? ResourceCapeDiscovery.empty()
                : discovered;
        return discovered;
    }

    private static String resourceCapePreviewKey(
            long generation, ResourceCapeKey key, String sourceSha256) {
        return "resource:cape:" + generation + ":" + key.collectionId() + ":"
                + key.capeId() + ":" + sourceSha256;
    }

    @Override
    public Optional<byte[]> loadResourceCapePreview(
            UUID accountId, ResourceCapeSelection selection) throws IOException {
        requireCapeAccount(accountId);
        ResourceCapeSnapshot snapshot = resourceCapeSnapshot;
        ResourceCapeSnapshotEntry entry = snapshot.entries().get(selection.key());
        if (!snapshot.accountId().equals(accountId)
                || snapshot.generation() != selection.generation()
                || bundledSkins.capeGeneration() != selection.generation()
                || entry == null
                || !entry.contentIdentity().equals(selection.contentIdentity())
                || !entry.sourceSha256().equals(selection.sourceSha256())) {
            return Optional.empty();
        }
        return Optional.of(entry.previewBytes());
    }

    @Override
    public com.naocraftlab.skins.core.model.PersonalCapeEntry materializeResourceCape(
            UUID accountId, ResourceCapeSelection selection)
            throws IOException, PngValidationException {
        requireCapeAccount(accountId);
        ResourceCapeSnapshot snapshot = resourceCapeSnapshot;
        ResourceCapeSnapshotEntry entry = snapshot.entries().get(selection.key());
        if (!snapshot.accountId().equals(accountId)
                || snapshot.generation() != selection.generation()
                || bundledSkins.capeGeneration() != selection.generation()
                || entry == null
                || !entry.contentIdentity().equals(selection.contentIdentity())
                || !entry.sourceSha256().equals(selection.sourceSha256())
                || entry.hasElytra() != selection.hasElytra()) {
            throw new IOException("Resource-pack cape catalog changed; reopen the editor");
        }
        byte[] bytes = bundledSkins.loadCape(selection.collectionId(), selection.capeId());
        if (!sha256(bytes).equals(entry.sourceSha256())) {
            throw new IOException("Resource-pack cape source changed; reopen the editor");
        }
        PngValidator.CapePng projection = new PngValidator().projectCape(bytes);
        if (!projection.renderSha256().equals(selection.contentIdentity())
                || projection.hasElytra() != selection.hasElytra()) {
            throw new IOException("Resource-pack cape identity changed; reopen the editor");
        }
        requireCapeAccount(accountId);
        if (bundledSkins.capeGeneration() != selection.generation()
                || resourceCapeSnapshot != snapshot) {
            throw new IOException("Resource-pack cape catalog changed; reopen the editor");
        }
        com.naocraftlab.skins.core.model.PersonalCapeEntry imported =
                storage.importCape(accountId, selection.displayName(), bytes);
        if (!imported.renderSha256().equals(selection.contentIdentity())) {
            throw new IOException("Resource-pack cape import changed identity");
        }
        return imported;
    }

    @Override
    public Optional<AccountState> discardCapeIfUnreferenced(UUID accountId, UUID entryId)
            throws IOException {
        requireCapeAccount(accountId);
        return Optional.of(observeLocal(storage.discardCapeIfUnreferenced(accountId, entryId)));
    }

    private void requireCapeAccount(UUID accountId) throws IOException {
        if (!accountId.equals(resolveAccountId(pinCurrentSession().identity()))) throw new IOException("Account changed");
    }

    @Override
    public AccountState renameCape(UUID accountId, UUID entryId, String name) throws IOException {
        requireCapeAccount(accountId);
        return observeLocal(storage.renameCape(accountId, entryId, name));
    }

    @Override
    public CapeDeletion deleteCape(UUID accountId, UUID entryId) throws IOException, PngValidationException {
        requireCapeAccount(accountId);
        storage.deleteCape(accountId, entryId);
        return new CapeDeletion(observeLocal(library.load(accountId)), reloadProviders());
    }

    @Override
    public AccountState renameCape(UUID entryId, String name) throws IOException {
        return observeLocal(storage.renameCape(resolveAccountId(pinCurrentSession().identity()), entryId, name));
    }

    @Override
    public CapeDeletion deleteCape(UUID entryId) throws IOException, PngValidationException {
        UUID accountId = resolveAccountId(pinCurrentSession().identity());
        storage.deleteCape(accountId, entryId);
        return new CapeDeletion(observeLocal(library.load(accountId)), reloadProviders());
    }

    @Override
    public EditorSave saveEditor(EditorSaveRequest request) throws IOException, PngValidationException {
        Objects.requireNonNull(request, "request");
        OperationContext context = pinCurrentSession();
        UUID accountId = resolveAccountId(context.identity());
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
                        request.capeId().orElse(null), request.offlineCape());
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
                    accountId, request.name(), persistedSkin, request.outerLayerVisibility(), capeId, request.offlineCape());
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
                accountId, presetId, request.name(), persistedSkin, request.outerLayerVisibility(), capeId, request.offlineCape());
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
        NclSkinsStorage.ActivePresetAppearanceIntentUpdate updated =
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
    public Optional<byte[]> loadProviderTexture(ViewSpec.ProviderTexture texture) throws IOException, PngValidationException {
        Objects.requireNonNull(texture, "texture");
        return texture.skin() ? Optional.of(storage.readAsset(texture.cacheKey()))
                : textures.readIfCached(texture.cacheKey());
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
                return copyAppearanceStatus(updated, AppearanceSyncStatus.UNKNOWN, current.settledRevision());
            }
            if (enablesMinecraft && current.hasIntent()
                    && current.syncStatus() != AppearanceSyncStatus.UNKNOWN
                    && current.syncStatus() != AppearanceSyncStatus.PARTIAL) {
                return copyAppearanceStatus(updated, AppearanceSyncStatus.PENDING, current.settledRevision());
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

    private static AppearanceSyncStatus activeEditStatus(
            AccountAppearanceState current, AppearanceProviders revised) {
        boolean assignedMinecraft = assignedMinecraft(current.intentRevision() + 1, revised);
        if (!assignedMinecraft) {
            return current.syncStatus();
        }
        if (hasMinecraftDeliveryStatus(revised, ProviderDelivery.Status.UNKNOWN)) {
            return AppearanceSyncStatus.UNKNOWN;
        }
        if (hasMinecraftDeliveryStatus(revised, ProviderDelivery.Status.ATTEMPTING)) {
            return AppearanceSyncStatus.ATTEMPTING;
        }
        return AppearanceSyncStatus.PENDING;
    }

    private static boolean assignedMinecraft(long revision, AppearanceProviders providers) {
        return providers.skin().minecraftDelivery().intentRevision() == revision
                || providers.cape().minecraftDelivery().intentRevision() == revision;
    }

    private static boolean hasMinecraftDeliveryStatus(
            AppearanceProviders providers, ProviderDelivery.Status status) {
        return providers.skin().enabled(BuiltinProvider.MINECRAFT)
                        && providers.skin().minecraftDelivery().status() == status
                || providers.cape().enabled(BuiltinProvider.MINECRAFT)
                        && providers.cape().minecraftDelivery().status() == status;
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
        ReconciliationTrigger trigger = selected.syncStatus() == AppearanceSyncStatus.OFFICIAL
                ? ReconciliationTrigger.LOCAL_INTENT : ReconciliationTrigger.EXPLICIT_RETRY;
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
        NclSkinsStorage.AccountAppearanceMutationResult selected =
                storage.mutateAccountAndAppearance(accountId, (account, current, revision) ->
                        NclSkinsStorage.AccountAppearanceMutationPlan.appearanceOnly(
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
    public Optional<ReconciliationResult> reconcileAppearance(ReconciliationTrigger trigger)
            throws IOException, PngValidationException {
        Objects.requireNonNull(trigger, "trigger");
        OperationContext context = pinCurrentSession();
        return reconcileAppearance(context, null, trigger);
    }

    @Override
    public Optional<ReconciliationResult> reconcileAppearance(
            ReconciliationKey expected, ReconciliationTrigger trigger)
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
    private Optional<ReconciliationResult> reconcileAppearance(
            OperationContext context,
            ReconciliationKey expected,
            ReconciliationTrigger trigger) throws IOException, PngValidationException {
        UUID accountId = resolveAccountId(context.identity());
        storage.initialize();
        try (var ignored = storage.acquireRemoteMutationLock(accountId)) {
            AccountAppearanceState checkpointAppearance = storage.loadAppearance(accountId);
            if (expected != null
                    && (!expected.accountId().equals(accountId)
                            || checkpointAppearance.intentRevision() != expected.intentRevision()
                            || checkpointAppearance.providers().skin().minecraftDelivery().activation()
                                    != expected.skinActivation()
                            || checkpointAppearance.providers().cape().minecraftDelivery().activation()
                                    != expected.capeActivation())) {
                return Optional.empty();
            }
            if (!checkpointAppearance.providers().minecraftEnabled()) {
                AccountAppearanceState local = checkpointAppearance.hasIntent()
                        ? settleAppearance(accountId, checkpointAppearance.intentRevision(),
                                checkpointAppearance.syncStatus(), AppearanceSyncStatus.OFFICIAL)
                        : checkpointAppearance;
                return Optional.of(reconciliationResult(context, local,
                        sessions.cachedStatus(context.identity()), observedAccount(accountId), Optional.empty()));
            }
            boolean explicitRecovery = trigger == ReconciliationTrigger.RATE_LIMIT_EXPIRED
                    || trigger == ReconciliationTrigger.EXPLICIT_RETRY
                    || trigger == ReconciliationTrigger.SESSION_REFRESHED;


            if (profileApi.rateLimitRemaining().isPresent()) {
                SessionValidation cached = sessions.cachedStatus(context.identity());
                return Optional.of(reconciliationResult(
                        context,
                        checkpointAppearance,
                        cached,
                        observedAccount(accountId),
                        Optional.empty()));
            }
            boolean reconnectAfterUnavailableToken = trigger == ReconciliationTrigger.RECONNECT
                    && sessions.cachedStatus(context.identity()).failureKind()
                            == ApiFailureKind.TOKEN_UNAVAILABLE;
            if (!explicitRecovery
                    && !reconnectAfterUnavailableToken
                    && !sessions.automaticCheckpointMayAcquireToken(context.identity())) {
                SessionValidation cached = sessions.cachedStatus(context.identity());
                AccountAppearanceState blocked = checkpointAppearance;
                boolean identityMismatch = cached.status()
                        == com.naocraftlab.skins.core.service.SessionStatus.UUID_MISMATCH;
                if (blocked.hasIntent()
                        && (blocked.syncStatus() == AppearanceSyncStatus.ATTEMPTING
                                || identityMismatch
                                        && blocked.syncStatus() == AppearanceSyncStatus.PENDING)) {
                    blocked = settleAppearance(
                            accountId,
                            blocked.intentRevision(),
                            blocked.syncStatus(),
                            AppearanceSyncStatus.UNKNOWN);
                }
                return Optional.of(reconciliationResult(
                        context,
                        blocked,
                        cached,
                        observedAccount(accountId),
                        Optional.empty()));
            }
            if (!explicitRecovery
                    && checkpointAppearance.hasIntent()
                    && (checkpointAppearance.syncStatus() == AppearanceSyncStatus.OFFICIAL
                            || checkpointAppearance.syncStatus() == AppearanceSyncStatus.PARTIAL
                            || checkpointAppearance.syncStatus() == AppearanceSyncStatus.UNKNOWN)) {
                SessionValidation cached = sessions.cachedStatus(context.identity());
                return Optional.of(reconciliationResult(
                        context,
                        checkpointAppearance,
                        cached,
                        observedAccount(accountId),
                        Optional.empty()));
            }
            return withRequestScopedToken(
                    context,
                    accountId,
                    checkpointAppearance,
                    scopedContext -> {


            SessionValidation explicitValidation = explicitRecovery
                    ? checkpointValidation(
                    scopedContext, trigger, checkpointAppearance.syncStatus())
                    : null;
            if (checkpointAppearance.hasIntent()
                    && (checkpointAppearance.syncStatus() == AppearanceSyncStatus.OFFICIAL
                            || !explicitRecovery
                                    && (checkpointAppearance.syncStatus() == AppearanceSyncStatus.PARTIAL
                                            || checkpointAppearance.syncStatus()
                                                    == AppearanceSyncStatus.UNKNOWN))) {
                SessionValidation cached = explicitValidation == null
                        ? sessions.cachedStatus(context.identity())
                        : explicitValidation;
                return Optional.of(reconciliationResult(
                        context,
                        checkpointAppearance,
                        cached,
                        observedAccount(accountId),
                        Optional.empty()));
            }
            SessionValidation validation = explicitValidation == null
                    ? checkpointValidation(
                            scopedContext, trigger, checkpointAppearance.syncStatus())
                    : explicitValidation;
            ObservedAccount observed = observeCheckpointAccount(accountId, validation, checkpointAppearance.providers());
            AccountAppearanceState appearance = storage.loadAppearance(accountId);


            if (!sameDelivery(appearance, checkpointAppearance)) {
                return Optional.of(reconciliationResult(
                        context, appearance, validation, observed, Optional.empty()));
            }
            if (!appearance.hasIntent()) {
                return Optional.of(reconciliationResult(
                        context, appearance, validation, observed, Optional.empty()));
            }

            if (appearance.syncStatus() == AppearanceSyncStatus.ATTEMPTING) {
                AppearanceSyncStatus recovered = validation.valid()
                                && validation.profile() != null
                                && compareAppearance(appearance, validation.profile())
                                        == AppearanceComparison.MATCH
                        ? AppearanceSyncStatus.OFFICIAL
                        : AppearanceSyncStatus.UNKNOWN;
                AccountAppearanceState settled = settleAppearance(
                        accountId, appearance.intentRevision(), AppearanceSyncStatus.ATTEMPTING, recovered);
                return Optional.of(reconciliationResult(
                        context, settled, validation, observed, Optional.empty()));
            }

            if (!validation.valid() || validation.profile() == null) {
                if (appearance.syncStatus() == AppearanceSyncStatus.PENDING
                        && !allowsAutomaticCheckpointRetry(validation.failureKind())) {
                    appearance = settleAppearance(
                            accountId,
                            appearance.intentRevision(),
                            AppearanceSyncStatus.PENDING,
                            AppearanceSyncStatus.UNKNOWN);
                }
                return Optional.of(reconciliationResult(
                        context, appearance, validation, observed, Optional.empty()));
            }

            if (appearance.providers().cape().enabled(BuiltinProvider.MINECRAFT)
                    && appearance.capeId() != null
                    && appearance.syncStatus() != AppearanceSyncStatus.OFFICIAL
                    && !validation.profile().ownsCape(appearance.capeId())) {
                long previousRevision = appearance.intentRevision();
                AppearanceSyncStatus previousStatus = appearance.syncStatus();
                NclSkinsStorage.AppearanceIntentUpdate effective = storage.updateAppearanceIntentIfCurrent(
                        accountId,
                        previousRevision,
                        previousStatus,
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
                appearance = effective.state();
                if (!effective.updated()) {
                    return Optional.of(reconciliationResult(
                            context, appearance, validation, observed, Optional.empty()));
                }
            }

            if (appearance.syncStatus() == AppearanceSyncStatus.UNKNOWN) {
                if (!explicitRecovery) {
                    return Optional.of(reconciliationResult(
                            context, appearance, validation, observed, Optional.empty()));
                }
                AppearanceComparison comparison = compareAppearance(
                        appearance, validation.profile());
                if (comparison == AppearanceComparison.MATCH) {
                    AccountAppearanceState settled = settleAppearance(
                            accountId,
                            appearance.intentRevision(),
                            AppearanceSyncStatus.UNKNOWN,
                            AppearanceSyncStatus.OFFICIAL);
                    return Optional.of(reconciliationResult(
                            context, settled, validation, observed, Optional.empty()));
                }
                if (comparison != AppearanceComparison.DIFFERENT) {
                    return Optional.of(reconciliationResult(
                            context, appearance, validation, observed, Optional.empty()));
                }
                return Optional.of(applyFullIntent(
                        accountId,
                        scopedContext,
                        appearance,
                        AppearanceSyncStatus.UNKNOWN,
                        validation));
            }

            if (appearance.syncStatus() == AppearanceSyncStatus.PARTIAL) {
                if (!explicitRecovery) {
                    return Optional.of(reconciliationResult(
                            context, appearance, validation, observed, Optional.empty()));
                }
                Optional<ActiveAppearance> actual = deliveryAppearance(validation.profile());
                if (actual.isEmpty()) {
                    return Optional.of(reconciliationResult(
                            context, appearance, validation, observed, Optional.empty()));
                }
                if (appearanceMatches(appearance, actual.orElseThrow())) {
                    AccountAppearanceState settled = settleAppearance(
                            accountId,
                            appearance.intentRevision(),
                            AppearanceSyncStatus.PARTIAL,
                            AppearanceSyncStatus.OFFICIAL);
                    return Optional.of(reconciliationResult(
                            context, settled, validation, observed, Optional.empty()));
                }
                if (!skinMatches(appearance, actual.orElseThrow())) {
                    return Optional.of(reconciliationResult(
                            context, appearance, validation, observed, Optional.empty()));
                }
                return Optional.of(applyCapeRecovery(
                        accountId, scopedContext, appearance, validation));
            }

            if (appearance.syncStatus() != AppearanceSyncStatus.PENDING) {
                return Optional.of(reconciliationResult(
                        context, appearance, validation, observed, Optional.empty()));
            }

            Optional<ActiveAppearance> actual = deliveryAppearance(validation.profile());
            if (actual.isPresent()) {
                ActiveAppearance observedAppearance = actual.orElseThrow();
                if (appearanceMatches(appearance, observedAppearance)) {
                    AccountAppearanceState settled = settleAppearance(
                            accountId,
                            appearance.intentRevision(),
                            AppearanceSyncStatus.PENDING,
                            AppearanceSyncStatus.OFFICIAL);
                    return Optional.of(reconciliationResult(
                            context, settled, validation, observed, Optional.empty()));
                }
                if (skinMatches(appearance, observedAppearance)) {
                    return Optional.of(applyPendingCapeDelta(
                            accountId, scopedContext, appearance, validation));
                }
            }

            return Optional.of(applyFullIntent(
                    accountId,
                    scopedContext,
                    appearance,
                    AppearanceSyncStatus.PENDING,
                    validation));
                    });
        }
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
            AccountAppearanceState appearance = storage.loadAppearance(accountId);
            if (appearance.intentRevision() == checkpointAppearance.intentRevision()
                    && (appearance.syncStatus() == AppearanceSyncStatus.PENDING
                            || appearance.syncStatus() == AppearanceSyncStatus.ATTEMPTING)) {
                appearance = settleAppearance(
                        accountId,
                        appearance.intentRevision(),
                        appearance.syncStatus(),
                        AppearanceSyncStatus.UNKNOWN);
            }
            return Optional.of(reconciliationResult(
                    context,
                    appearance,
                    validation,
                    observedAccount(accountId),
                    Optional.empty()));
        } catch (GameSessionTokenUnavailableException unavailableToken) {
            return tokenUnavailableBeforeRequest(context, accountId, checkpointAppearance);
        } catch (RuntimeException unavailableToken) {
            SessionValidation validation = sessions.rememberTokenSourceFailure(context.identity());
            AccountAppearanceState appearance = storage.loadAppearance(accountId);
            if (appearance.intentRevision() == checkpointAppearance.intentRevision()
                    && appearance.syncStatus() == AppearanceSyncStatus.ATTEMPTING) {
                appearance = settleAppearance(
                        accountId,
                        appearance.intentRevision(),
                        appearance.syncStatus(),
                        AppearanceSyncStatus.UNKNOWN);
            }
            return Optional.of(reconciliationResult(
                    context,
                    appearance,
                    validation,
                    observedAccount(accountId),
                    Optional.empty()));
        }
    }

    private Optional<ReconciliationResult> tokenUnavailableBeforeRequest(
            OperationContext context,
            UUID accountId,
            AccountAppearanceState checkpointAppearance) throws IOException {
        SessionValidation validation = sessions.rememberTokenUnavailable(context.identity());
        AccountAppearanceState appearance = storage.loadAppearance(accountId);
        if (appearance.intentRevision() == checkpointAppearance.intentRevision()
                && appearance.syncStatus() == AppearanceSyncStatus.ATTEMPTING) {
            appearance = settleAppearance(
                    accountId,
                    appearance.intentRevision(),
                    AppearanceSyncStatus.ATTEMPTING,
                    AppearanceSyncStatus.UNKNOWN);
        }
        return Optional.of(reconciliationResult(
                context,
                appearance,
                validation,
                observedAccount(accountId),
                Optional.empty()));
    }

    private ReconciliationResult applyFullIntent(
            UUID accountId,
            OperationContext context,
            AccountAppearanceState appearance,
            AppearanceSyncStatus expectedStatus,
            SessionValidation validation) throws IOException, PngValidationException {
        long revision = appearance.intentRevision();


        PresetApplicationRequest request = requestFromAppearance(appearance);
        AccountAppearanceState claimed = claimAppearance(
                accountId, appearance, expectedStatus, request.writeSkin(), request.writeCape());
        if (claimed.intentRevision() != revision
                || claimed.syncStatus() != AppearanceSyncStatus.ATTEMPTING) {
            return reconciliationResult(
                    context,
                    claimed,
                    validation,
                    observedAccount(accountId),
                    Optional.empty());
        }
        PresetApplicationOutcome outcome = mutations.applyPresetWhileLockedAfterSameTokenValidation(
                context.tokens(),
                request,
                () -> appearanceStillCurrent(accountId, appearance));
        AccountAppearanceState settled = settleAfterMutation(
                accountId,
                claimed,
                AppearanceSyncStatus.ATTEMPTING,
                settlementStatus(outcome),
                outcome);
        return reconciliationAfterMutation(accountId, context, settled, outcome, appearance.providers());
    }

    private ReconciliationResult applyCapeRecovery(
            UUID accountId,
            OperationContext context,
            AccountAppearanceState appearance,
            SessionValidation validation) throws IOException {
        long revision = appearance.intentRevision();
        AccountAppearanceState claimed = claimAppearance(
                accountId, appearance, AppearanceSyncStatus.PARTIAL, false, true);
        if (claimed.intentRevision() != revision
                || claimed.syncStatus() != AppearanceSyncStatus.ATTEMPTING) {
            return reconciliationResult(
                    context,
                    claimed,
                    validation,
                    observedAccount(accountId),
                    Optional.empty());
        }
        PresetApplicationOutcome outcome = mutations.retryCapeWhileLockedAfterSameTokenValidation(
                context.tokens(),
                claimed.capeId(),
                () -> appearanceStillCurrent(accountId, appearance));
        AppearanceSyncStatus status = switch (outcome.result()) {
            case APPLIED -> AppearanceSyncStatus.OFFICIAL;
            case UNKNOWN -> AppearanceSyncStatus.UNKNOWN;
            case PARTIAL, FAILED, SESSION_EXPIRED -> AppearanceSyncStatus.PARTIAL;
        };
        AccountAppearanceState settled = settleAfterMutation(
                accountId,
                claimed,
                AppearanceSyncStatus.ATTEMPTING,
                status,
                outcome);
        return reconciliationAfterMutation(accountId, context, settled, outcome, appearance.providers());
    }

    private ReconciliationResult applyPendingCapeDelta(
            UUID accountId,
            OperationContext context,
            AccountAppearanceState appearance,
            SessionValidation validation) throws IOException {
        long revision = appearance.intentRevision();
        AccountAppearanceState claimed = claimAppearance(
                accountId, appearance, AppearanceSyncStatus.PENDING, false, true);
        if (claimed.intentRevision() != revision
                || claimed.syncStatus() != AppearanceSyncStatus.ATTEMPTING) {
            return reconciliationResult(
                    context,
                    claimed,
                    validation,
                    observedAccount(accountId),
                    Optional.empty());
        }
        PresetApplicationOutcome outcome = mutations.retryCapeWhileLockedAfterSameTokenValidation(
                context.tokens(),
                claimed.capeId(),
                () -> appearanceStillCurrent(accountId, appearance));
        AccountAppearanceState settled = settleAfterMutation(
                accountId,
                claimed,
                AppearanceSyncStatus.ATTEMPTING,
                settlementStatus(outcome),
                outcome);
        return reconciliationAfterMutation(accountId, context, settled, outcome, appearance.providers());
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
        storage.initialize();
        AccountAppearanceState appearance = storage.loadAppearance(accountId);
        if (!appearance.hasIntent()) {
            return Optional.empty();
        }
        return Optional.of(durableAppearance(
                accountId, identity, appearance, sessions.cachedStatus(identity)));
    }

    private SessionValidation checkpointValidation(
            OperationContext context,
            ReconciliationTrigger trigger,
            AppearanceSyncStatus status) {
        return switch (trigger) {
            case RATE_LIMIT_EXPIRED, EXPLICIT_RETRY -> sessions.manualRetry(context.tokens());
            case SESSION_REFRESHED -> sessions.retryTokenUnavailableAtCheckpoint(context.tokens());
            case RECONNECT -> {
                if (status == AppearanceSyncStatus.ATTEMPTING) {
                    yield sessions.observeFreshAtCheckpoint(context.tokens());
                }
                if (sessions.cachedStatus(context.identity()).failureKind() == ApiFailureKind.TOKEN_UNAVAILABLE) {
                    yield sessions.retryTokenUnavailableAtCheckpoint(context.tokens());
                }
                yield sessions.retryTransientAtCheckpoint(context.tokens());
            }
            case PROCESS_START -> context.identity().profileId().equals(startupObservedAccount)
                    ? sessions.cachedStatus(context.identity())
                    : sessions.retryTransientAtCheckpoint(context.tokens());
            default -> status == AppearanceSyncStatus.ATTEMPTING
                    ? sessions.observeFreshAtCheckpoint(context.tokens())
                    : sessions.retryTransientAtCheckpoint(context.tokens());
        };
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

    private AccountAppearanceState claimAppearance(
            UUID accountId,
            AccountAppearanceState expected,
            AppearanceSyncStatus expectedStatus,
            boolean claimSkin,
            boolean claimCape) throws IOException {
        return storage.updateAppearance(accountId, current -> {
            if (!sameDelivery(current, expected)
                    || current.syncStatus() != expectedStatus) {
                return current;
            }
            return withAppearanceStatus(
                    current,
                    AppearanceSyncStatus.ATTEMPTING,
                    current.settledRevision(),
                    new AppearanceProviders(
                            claimDelivery(current.providers().skin(), claimSkin),
                            claimDelivery(current.providers().cape(), claimCape)));
        });
    }

    private static <T> ProviderChannel<T> claimDelivery(
            ProviderChannel<T> channel, boolean selected) {
        if (!selected
                || !channel.enabled(BuiltinProvider.MINECRAFT)
                || channel.minecraftDelivery().status() == ProviderDelivery.Status.UNKNOWN) {
            return channel;
        }
        return channel.settle(
                channel.minecraftDelivery(), ProviderDelivery.Status.ATTEMPTING, null);
    }

    private PresetApplicationRequest requestFromAppearance(AccountAppearanceState appearance)
            throws IOException, PngValidationException {
        boolean writeSkin = needsMinecraftDelivery(appearance.providers().skin());
        boolean writeCape = needsMinecraftDelivery(appearance.providers().cape());
        ResolvedSkinAsset resolved = null;
        SkinReference skin = SkinReference.accountDefault();
        if (writeSkin && appearance.skinSha256() != null) {
            UUID assetId = UUID.randomUUID();
            resolved = new ResolvedSkinAsset(
                    assetId,
                    appearance.skinSha256(),
                    appearance.skinVariant(),
                    storage.readAsset(appearance.skinSha256()));
            skin = SkinReference.asset(assetId);
        }
        Instant now = clock.instant();
        UUID presetId = appearance.activePresetId() == null
                ? UUID.randomUUID()
                : appearance.activePresetId();
        AppearancePreset preset = new AppearancePreset(
                presetId,
                "Durable appearance",
                skin,
                appearance.capeId(),
                appearance.outerLayerVisibility(),
                now,
                now);
        return new PresetApplicationRequest(preset, resolved,
                writeSkin,
                writeCape);
    }

    private static AppearanceSyncStatus settlementStatus(PresetApplicationOutcome outcome) {
        return switch (outcome.result()) {
            case APPLIED -> AppearanceSyncStatus.OFFICIAL;
            case PARTIAL -> AppearanceSyncStatus.PARTIAL;
            case UNKNOWN -> AppearanceSyncStatus.UNKNOWN;
            case FAILED -> allowsAutomaticMutationRetry(outcome)
                    ? AppearanceSyncStatus.PENDING
                    : AppearanceSyncStatus.UNKNOWN;
            case SESSION_EXPIRED -> AppearanceSyncStatus.UNKNOWN;
        };
    }

    private static boolean allowsAutomaticCheckpointRetry(ApiFailureKind failureKind) {
        return failureKind == ApiFailureKind.TOKEN_UNAVAILABLE
                || failureKind == ApiFailureKind.NETWORK
                || failureKind == ApiFailureKind.SERVER_ERROR
                || failureKind == ApiFailureKind.RATE_LIMITED;
    }

    private static boolean allowsAutomaticMutationRetry(PresetApplicationOutcome outcome) {
        ApiFailureKind failureKind = outcome.failureKind();
        return allowsAutomaticCheckpointRetry(failureKind);
    }

    private AppearanceComparison compareAppearance(
            AccountAppearanceState expected, RemoteProfile profile) {
        Optional<ActiveAppearance> actual = deliveryAppearance(profile);
        if (actual.isEmpty()) {
            return AppearanceComparison.UNRESOLVED;
        }
        return appearanceMatches(expected, actual.orElseThrow())
                ? AppearanceComparison.MATCH
                : AppearanceComparison.DIFFERENT;
    }

    private static boolean needsMinecraftDelivery(ProviderChannel<?> channel) {
        return channel.enabled(BuiltinProvider.MINECRAFT)
                && channel.minecraftDelivery().status() != ProviderDelivery.Status.CONFIRMED;
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

    private boolean appearanceStillCurrent(UUID accountId, AccountAppearanceState expected) {
        try {
            AccountAppearanceState current = storage.loadAppearance(accountId);
            return sameDelivery(current, expected)
                    && current.syncStatus() == AppearanceSyncStatus.ATTEMPTING;
        } catch (IOException unavailableState) {
            return false;
        }
    }

    private static boolean sameDelivery(AccountAppearanceState current, AccountAppearanceState expected) {
        return current.intentRevision() == expected.intentRevision() && sameActivation(current, expected);
    }

    private static boolean sameActivation(AccountAppearanceState current, AccountAppearanceState expected) {
        return current.providers().skin().minecraftDelivery().activation()
                            == expected.providers().skin().minecraftDelivery().activation()
                    && current.providers().cape().minecraftDelivery().activation()
                            == expected.providers().cape().minecraftDelivery().activation()
                    && current.providers().skin().enabled(BuiltinProvider.MINECRAFT)
                            == expected.providers().skin().enabled(BuiltinProvider.MINECRAFT)
                    && current.providers().cape().enabled(BuiltinProvider.MINECRAFT)
                            == expected.providers().cape().enabled(BuiltinProvider.MINECRAFT);
    }

    @Override
    public RemoteResult retryCape(String capeId) throws IOException, PngValidationException {
        OperationContext context = pinCurrentSession();
        UUID accountId = resolveAccountId(context.identity());
        storage.initialize();
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
                        context, key, ReconciliationTrigger.EXPLICIT_RETRY)
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
        return storage.readAsset(asset.sha256());
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
                CachedTexture cached = textures.get(cape.orElseThrow());
                return Optional.of(textures.read(cached));
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

    private AccountAppearanceState settleAppearance(
            UUID accountId,
            long revision,
            AppearanceSyncStatus expectedStatus,
            AppearanceSyncStatus status) throws IOException {
        return storage.updateAppearance(accountId, current -> {
            if (current.intentRevision() != revision || current.syncStatus() != expectedStatus) {
                return current;
            }
            long settled = status == AppearanceSyncStatus.OFFICIAL
                    ? revision
                    : current.settledRevision();
            return copyAppearanceStatus(current, status, settled);
        });
    }

    private AccountAppearanceState settleAfterMutation(
            UUID accountId,
            AccountAppearanceState expected,
            AppearanceSyncStatus expectedStatus,
            AppearanceSyncStatus status,
            PresetApplicationOutcome outcome) throws IOException {
        try {
            return storage.updateAppearance(accountId, current -> {
                if (!sameDelivery(current, expected) || current.syncStatus() != expectedStatus) {
                    if (current.intentRevision() > expected.intentRevision()
                            && sameActivation(current, expected)
                            && current.syncStatus() == AppearanceSyncStatus.UNKNOWN
                            && (outcome.result() == MutationResult.APPLIED || outcome.result() == MutationResult.PARTIAL)) {
                        AppearanceProviders providers = new AppearanceProviders(
                                resumeSupersedingDelivery(
                                        current.providers().skin(), expected.providers().skin()),
                                resumeSupersedingDelivery(
                                        current.providers().cape(), expected.providers().cape()));
                        if (providers.equals(current.providers())) {
                            return current;
                        }
                        return withAppearanceStatus(
                                current,
                                supersedingRecoveryStatus(current, providers),
                                current.settledRevision(),
                                providers);
                    }
                    return current;
                }
                return copyAppearanceStatus(current, status,
                        status == AppearanceSyncStatus.OFFICIAL
                                ? current.intentRevision() : current.settledRevision());
            });
        } catch (IOException | RuntimeException localFailure) {


            throw new RemoteMutationSettlementException(outcome.remoteAppearanceImpact());
        }
    }

    private static <T> ProviderChannel<T> resumeSupersedingDelivery(
            ProviderChannel<T> current, ProviderChannel<T> previous) {
        ProviderDelivery delivery = current.minecraftDelivery();
        ProviderDelivery previousDelivery = previous.minecraftDelivery();
        if (!current.enabled(BuiltinProvider.MINECRAFT)
                || !previous.enabled(BuiltinProvider.MINECRAFT)
                || delivery.status() != ProviderDelivery.Status.UNKNOWN
                || previousDelivery.status() != ProviderDelivery.Status.ATTEMPTING
                || delivery.activation() != previousDelivery.activation()
                || delivery.intentRevision() <= previousDelivery.intentRevision()) {
            return current;
        }
        return current.settle(delivery, ProviderDelivery.Status.PENDING, null);
    }

    private static AppearanceSyncStatus supersedingRecoveryStatus(
            AccountAppearanceState current, AppearanceProviders providers) {
        if (hasMinecraftDeliveryStatus(providers, ProviderDelivery.Status.UNKNOWN)) {
            return AppearanceSyncStatus.UNKNOWN;
        }
        if (hasMinecraftDeliveryStatus(providers, ProviderDelivery.Status.ATTEMPTING)) {
            return AppearanceSyncStatus.ATTEMPTING;
        }
        if (hasMinecraftDeliveryStatus(providers, ProviderDelivery.Status.PENDING)) {
            return AppearanceSyncStatus.PENDING;
        }
        return current.syncStatus();
    }

    private AccountAppearanceState copyAppearanceStatus(
            AccountAppearanceState current,
            AppearanceSyncStatus status,
            long settledRevision) {
        return withAppearanceStatus(current, status, settledRevision, new AppearanceProviders(
                deliveryStatus(current.providers().skin(), status, true),
                deliveryStatus(current.providers().cape(), status, false)));
    }

    private AccountAppearanceState withAppearanceStatus(
            AccountAppearanceState current,
            AppearanceSyncStatus status,
            long settledRevision,
            AppearanceProviders providers) {
        return new AccountAppearanceState(
                current.schemaVersion(),
                current.accountId(),
                current.intentRevision(),
                current.activePresetId(),
                current.skinSha256(),
                current.skinVariant(),
                current.capeId(),
                current.outerLayerVisibility(),
                status,
                settledRevision,
                clock.instant(),
                providers);
    }

    private static <T> ProviderChannel<T> deliveryStatus(
            ProviderChannel<T> channel, AppearanceSyncStatus status, boolean skin) {
        if (!channel.enabled(BuiltinProvider.MINECRAFT)
                || channel.minecraftDelivery().status() == ProviderDelivery.Status.CONFIRMED) {
            return channel;
        }
        ProviderDelivery.Status delivery = switch (status) {
            case ATTEMPTING -> ProviderDelivery.Status.ATTEMPTING;
            case OFFICIAL -> ProviderDelivery.Status.CONFIRMED;
            case UNKNOWN -> ProviderDelivery.Status.UNKNOWN;
            case PARTIAL -> skin ? ProviderDelivery.Status.CONFIRMED : ProviderDelivery.Status.PENDING;
            case PENDING -> channel.minecraftDelivery().status() == ProviderDelivery.Status.UNKNOWN
                    ? ProviderDelivery.Status.UNKNOWN : ProviderDelivery.Status.PENDING;
            case LOCAL_ONLY -> ProviderDelivery.Status.IDLE;
        };
        return channel.settle(channel.minecraftDelivery(), delivery, channel.desired());
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
                return Optional.of(TextureCache.cacheKey(verifiedTexture.orElseThrow()));
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
                    bytes = storage.readAsset(applied.localSkinSha256().orElseThrow());
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

    private record ObservedAccount(
            AccountState account, Optional<UUID> currentOfficialSkinId) {
        private ObservedAccount {
            Objects.requireNonNull(account, "account");
            currentOfficialSkinId = Objects.requireNonNull(
                    currentOfficialSkinId, "currentOfficialSkinId");
        }
    }

    private enum AppearanceComparison {
        MATCH,
        DIFFERENT,
        UNRESOLVED
    }


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

    @FunctionalInterface
    private interface ScopedTokenRequest<T> {
        T execute(GameSessionTokenSource scopedTokens);
    }

    private static final class ScopedCheckedFailure extends RuntimeException {
        private static final long serialVersionUID = 1L;

        private ScopedCheckedFailure(Exception cause) {
            super(cause);
        }
    }

    private static final class ScopedCallbackRuntimeFailure extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private final RuntimeException original;

        private ScopedCallbackRuntimeFailure(RuntimeException original) {
            super(null, null, false, false);
            this.original = Objects.requireNonNull(original, "original");
        }

        private RuntimeException original() {
            return original;
        }
    }

    private static final class PinnedTokenSource implements GameSessionTokenSource {
        private final GameSessionTokenSource delegate;
        private final SessionIdentity identity;

        private PinnedTokenSource(GameSessionTokenSource delegate, SessionIdentity identity) {
            this.delegate = Objects.requireNonNull(delegate, "delegate");
            this.identity = Objects.requireNonNull(identity, "identity");
        }

        @Override
        public SessionIdentity currentSession() {
            return identity;
        }

        @Override
        public <T, E extends Exception> T withAccessToken(TokenRequest<T, E> request) throws E {
            Objects.requireNonNull(request, "request");
            return delegate.withSession((current, accessToken) -> {
                if (!identity.profileId().equals(current.profileId())) {
                    throw new GameSessionIdentityChangedException();
                }
                return request.execute(accessToken);
            });
        }

        private <T> T withRequestToken(ScopedTokenRequest<T> request) {
            Objects.requireNonNull(request, "request");
            return delegate.withSession((current, accessToken) -> {
                if (!identity.profileId().equals(current.profileId())) {
                    throw new GameSessionIdentityChangedException();
                }
                GameSessionTokenSource scoped = new RequestScopedTokenSource(
                        identity, accessToken);
                try {
                    return request.execute(scoped);
                } catch (ScopedCheckedFailure checkedFailure) {
                    throw checkedFailure;
                } catch (RuntimeException callbackFailure) {
                    throw new ScopedCallbackRuntimeFailure(callbackFailure);
                }
            });
        }
    }


    private static final class RequestScopedTokenSource implements GameSessionTokenSource {
        private final SessionIdentity identity;
        private final String accessToken;

        private RequestScopedTokenSource(SessionIdentity identity, String accessToken) {
            this.identity = Objects.requireNonNull(identity, "identity");
            this.accessToken = Objects.requireNonNull(accessToken, "accessToken");
        }

        @Override
        public SessionIdentity currentSession() {
            return identity;
        }

        @Override
        public <T, E extends Exception> T withAccessToken(TokenRequest<T, E> request) throws E {
            return Objects.requireNonNull(request, "request").execute(accessToken);
        }
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

    private record CatalogVariantKey(String collectionId, String skinId, SkinModel model) {
        private CatalogVariantKey {
            Objects.requireNonNull(collectionId, "collectionId");
            Objects.requireNonNull(skinId, "skinId");
            Objects.requireNonNull(model, "model");
        }
    }

    private record CatalogDiscovery(
            List<SkinCatalogSource.CollectionDescriptor> collections,
            Map<CatalogVariantKey, String> variantHashes,
            Map<CatalogVariantKey, UUID> personalAssets,
            Map<ClientOperations.CatalogVariant, SkinFeatureEvidence> featureEvidence) {
        private CatalogDiscovery {
            collections = List.copyOf(Objects.requireNonNull(collections, "collections"));
            variantHashes = Map.copyOf(Objects.requireNonNull(variantHashes, "variantHashes"));
            personalAssets = Map.copyOf(Objects.requireNonNull(personalAssets, "personalAssets"));
            featureEvidence = Map.copyOf(Objects.requireNonNull(
                    featureEvidence, "featureEvidence"));
        }
    }

    private record CatalogDiscoveryCache(
            UUID accountId,
            List<PersonalSkinEntry> personalSkins,
            long generation,
            CatalogDiscovery discovery) {
        private CatalogDiscoveryCache {
            Objects.requireNonNull(accountId, "accountId");
            personalSkins = List.copyOf(Objects.requireNonNull(personalSkins, "personalSkins"));
            Objects.requireNonNull(discovery, "discovery");
        }

        private boolean matches(
                UUID currentAccountId,
                List<PersonalSkinEntry> currentPersonalSkins,
                long currentGeneration) {
            return generation == currentGeneration
                    && accountId.equals(currentAccountId)
                    && personalSkins.equals(currentPersonalSkins);
        }
    }

    private record CatalogSnapshot(
            UUID accountId,
            Map<CatalogVariantKey, String> variantHashes,
            Map<CatalogVariantKey, UUID> personalAssets,
            Map<ClientOperations.CatalogVariant, SkinFeatureEvidence> featureEvidence) {
        private CatalogSnapshot {
            Objects.requireNonNull(accountId, "accountId");
            variantHashes = Map.copyOf(Objects.requireNonNull(variantHashes, "variantHashes"));
            personalAssets = Map.copyOf(Objects.requireNonNull(personalAssets, "personalAssets"));
            featureEvidence = Map.copyOf(Objects.requireNonNull(
                    featureEvidence, "featureEvidence"));
        }

        private static CatalogSnapshot empty() {
            return new CatalogSnapshot(
                    new UUID(0L, 0L), Map.of(), Map.of(), Map.of());
        }
    }

    private record ResourceCapeDiscovery(
            long generation,
            List<CapeCatalogSource.CollectionDescriptor> collections,
            Map<ResourceCapeKey, ResourceCapeSnapshotEntry> entries,
            Map<ResourceCapeKey, String> sourceHashes) {
        private ResourceCapeDiscovery {
            collections = List.copyOf(Objects.requireNonNull(collections, "collections"));
            entries = Map.copyOf(Objects.requireNonNull(entries, "entries"));
            sourceHashes = Map.copyOf(Objects.requireNonNull(sourceHashes, "sourceHashes"));
        }

        private static ResourceCapeDiscovery empty() {
            return new ResourceCapeDiscovery(Long.MIN_VALUE, List.of(), Map.of(), Map.of());
        }
    }

    private record ResourceCapeSnapshot(
            UUID accountId,
            long generation,
            Map<ResourceCapeKey, ResourceCapeSnapshotEntry> entries) {
        private ResourceCapeSnapshot {
            accountId = Objects.requireNonNull(accountId, "accountId");
            entries = Map.copyOf(Objects.requireNonNull(entries, "entries"));
        }

        private static ResourceCapeSnapshot empty() {
            return new ResourceCapeSnapshot(new UUID(0L, 0L), Long.MIN_VALUE, Map.of());
        }
    }

    private record ResourceCapeSnapshotEntry(
            String contentIdentity, String sourceSha256, boolean hasElytra,
            byte[] bytes, byte[] previewBytes) {
        private ResourceCapeSnapshotEntry {
            Objects.requireNonNull(contentIdentity, "contentIdentity");
            Objects.requireNonNull(sourceSha256, "sourceSha256");
            bytes = Objects.requireNonNull(bytes, "bytes").clone();
            previewBytes = Objects.requireNonNull(previewBytes, "previewBytes").clone();
        }

        @Override
        public byte[] bytes() {
            return bytes.clone();
        }

        @Override
        public byte[] previewBytes() {
            return previewBytes.clone();
        }
    }
}

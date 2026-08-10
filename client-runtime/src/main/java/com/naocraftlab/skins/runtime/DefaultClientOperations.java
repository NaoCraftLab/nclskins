package com.naocraftlab.skins.runtime;

import com.naocraftlab.skins.client.BundledSkinSource;
import com.naocraftlab.skins.client.CatalogCollectionOrder;
import com.naocraftlab.skins.client.CatalogText;
import com.naocraftlab.skins.client.GameSessionTokenSource;
import com.naocraftlab.skins.client.OuterLayerVisibility;
import com.naocraftlab.skins.client.PersonalSkinCatalog;
import com.naocraftlab.skins.client.SignedTextureVerifier;
import com.naocraftlab.skins.client.SkinCatalogSource;
import com.naocraftlab.skins.client.SkinModel;
import com.naocraftlab.skins.core.api.ApiFailureKind;
import com.naocraftlab.skins.core.api.MinecraftProfileApi;
import com.naocraftlab.skins.core.api.ProfileApi;
import com.naocraftlab.skins.core.importing.ExternalImportContext;
import com.naocraftlab.skins.core.importing.ExternalImportProbe;
import com.naocraftlab.skins.core.importing.ExternalImportSource;
import com.naocraftlab.skins.core.model.AccountAppearanceState;
import com.naocraftlab.skins.core.model.AccountState;
import com.naocraftlab.skins.core.model.AccountUiPreferences;
import com.naocraftlab.skins.core.model.AddSourceTab;
import com.naocraftlab.skins.core.model.AppearancePreset;
import com.naocraftlab.skins.core.model.AppearanceSyncStatus;
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

    private final Map<UUID, LibraryObservation> libraryObservations = new ConcurrentHashMap<>();

    private volatile CatalogSnapshot catalogSnapshot = CatalogSnapshot.empty();

    private volatile CatalogDiscoveryCache catalogDiscoveryCache;

    private volatile InitialData preparedInitialData;

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
            preparedInitialData = initializeFresh(pinCurrentSession());
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
        return ClientOperations.super.reconciliationRecommended(data) || !data.session().valid();
    }

    @Override
    public synchronized InitialData initialize() throws IOException, PngValidationException {
        OperationContext context = pinCurrentSession();
        List<String> preparedWarnings = List.of();
        if (preparedInitialData != null) {
            InitialData prepared = preparedInitialData;
            preparedInitialData = null;
            if (prepared.account().accountId().equals(context.identity().profileId())) {
                preparedWarnings = prepared.storageWarnings();
            }
        }


        InitialData current = initializeFresh(context);
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
                current.syncStatus());
    }

    private InitialData initializeFresh(OperationContext context)
            throws IOException, PngValidationException {
        return initializeFresh(context, false);
    }

    private InitialData initializeFresh(
            OperationContext context, boolean requireFreshProfile)
            throws IOException, PngValidationException {
        UUID accountId = context.identity().profileId();
        StorageInitialization initialization = storage.initialize();
        return initializeFreshLocked(context, initialization, requireFreshProfile);
    }

    private InitialData initializeFreshLocked(
            OperationContext context,
            StorageInitialization initialization,
            boolean requireFreshProfile)
            throws IOException, PngValidationException {
        UUID accountId = resolveAccountId(context.identity());
        boolean profileValidationResolved = false;
        for (int attempt = 0; attempt < MAX_BOOTSTRAP_CAS_ATTEMPTS; attempt++) {
            AccountState state = seedVanillaDefaults(library.load(accountId));


            state = library.load(accountId);
            SessionValidation validation;
            if (!profileValidationResolved) {
                validation = requireFreshProfile
                        ? sessions.manualRetry(context.tokens())
                        : sessions.cachedStatus(context.identity());
                profileValidationResolved = true;
            } else {


                validation = sessions.cachedStatus(context.identity());
            }
            OfficialSkinSync official = syncCurrentOfficial(state, validation);
            InitialPresetBootstrap bootstrap = createInitialPresetFromOfficialSkin(official, validation);
            if (bootstrap.revisionMatched()) {
                return finishInitialization(
                        accountId,
                        bootstrap.official(),
                        validation,
                        initialization);
            }


        }


        AccountState latest = seedVanillaDefaults(library.load(accountId));
        latest = library.load(accountId);
        SessionValidation validation = sessions.cachedStatus(context.identity());
        OfficialSkinSync official = syncCurrentOfficial(latest, validation);
        return finishInitialization(accountId, official, validation, initialization);
    }

    private InitialData finishInitialization(
            UUID accountId,
            OfficialSkinSync official,
            SessionValidation validation,
            StorageInitialization initialization) throws IOException, PngValidationException {
        Optional<UUID> activePreset = reconcileActivePreset(accountId, official.state(), validation);
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
                appearance.syncStatus());
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
                        cachedCapeKey(cape, previous.find(cape.id()))))
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
                accountId, discovery.variantHashes(), discovery.personalAssets());
        return discovery.collections();
    }

    private CatalogDiscovery discoverAvailableCatalogCollections(
            UUID accountId, AccountState account) {
        List<SkinCatalogSource.CollectionDescriptor> collections = new ArrayList<>();
        Map<CatalogVariantKey, String> variantHashes = new HashMap<>();
        Map<CatalogVariantKey, UUID> personalAssets = new HashMap<>();
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
                personalAssets);
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
                personalAssets);
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
        return new CatalogDiscovery(collections, variantHashes, personalAssets);
    }

    private List<SkinCatalogSource.SkinDescriptor> personalSkinDescriptors(
            UUID accountId,
            List<PersonalSkinEntry> entries,
            String collectionId,
            Map<CatalogVariantKey, String> variantHashes,
            Map<CatalogVariantKey, UUID> personalAssets) {
        return entries.stream()
                .map(entry -> personalSkinDescriptor(
                        accountId, entry, collectionId, variantHashes, personalAssets))
                .toList();
    }

    private SkinCatalogSource.SkinDescriptor personalSkinDescriptor(
            UUID accountId,
            PersonalSkinEntry entry,
            String collectionId,
            Map<CatalogVariantKey, String> variantHashes,
            Map<CatalogVariantKey, UUID> personalAssets) {
        List<SkinModel> models = new ArrayList<>(2);
        addPersonalVariant(
                accountId,
                entry,
                SkinVariant.CLASSIC,
                SkinModel.CLASSIC,
                collectionId,
                models,
                variantHashes,
                personalAssets);
        addPersonalVariant(
                accountId,
                entry,
                SkinVariant.SLIM,
                SkinModel.SLIM,
                collectionId,
                models,
                variantHashes,
                personalAssets);
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

    private static void addPersonalVariant(
            UUID accountId,
            PersonalSkinEntry entry,
            SkinVariant variant,
            SkinModel model,
            String collectionId,
            List<SkinModel> models,
            Map<CatalogVariantKey, String> variantHashes,
            Map<CatalogVariantKey, UUID> personalAssets) {
        entry.optionalAssetId(variant).ifPresent(assetId -> {
            CatalogVariantKey key = new CatalogVariantKey(
                    collectionId, entry.sha256(), model);
            models.add(model);
            variantHashes.put(key, entry.sha256());
            personalAssets.put(key, assetId);
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
    public void setSelectedAddSourceTab(AddSourceTab tab) throws IOException {
        UUID accountId = resolveAccountId(pinCurrentSession().identity());
        storage.setSelectedAddSourceTab(accountId, Objects.requireNonNull(tab, "tab"));
    }

    @Override
    public void setCollectionCollapsed(String collectionId, boolean collapsed) throws IOException {
        UUID accountId = resolveAccountId(pinCurrentSession().identity());
        storage.setCollectionCollapsed(
                accountId, Objects.requireNonNull(collectionId, "collectionId"), collapsed);
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


        SessionValidation validation = sessions.cachedStatus(context.identity());
        if (!validation.valid() || validation.profile() == null) {
            return;
        }
        UUID accountId = resolveAccountId(context.identity());
        RemoteProfile profile = validation.profile();
        publishOwnedCapeInventory(accountId, profile);
        for (RemoteCape cape : profile.capes()) {
            try {
                textures.get(cape);
                String cacheKey = TextureCache.cacheKey(cape.textureUri());
                storage.updateOwnedCapes(accountId, current -> {
                    List<OwnedCapeEntry> updated = current.capes().stream()
                            .map(entry -> entry.id().equals(cape.id())
                                    ? entry.withTextureCacheKey(cacheKey)
                                    : entry)
                            .toList();
                    return new OwnedCapeInventory(
                            current.schemaVersion(),
                            current.accountId(),
                            updated,
                            current.verifiedAt());
                });
            } catch (IOException | RuntimeException unavailableCape) {

            }
        }
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
                appearance.syncStatus());
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
                        request.capeId().orElse(null));
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
                        request.capeId().orElse(null));
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
                    accountId, request.name(), persistedSkin, request.outerLayerVisibility(), capeId);
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
                accountId, presetId, request.name(), persistedSkin, request.outerLayerVisibility(), capeId);
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
        NclSkinsStorage.ActivePresetAppearanceIntentUpdate updated =
                storage.updateAppearanceIntentIfPresetActive(
                        accountId,
                        presetId,
                        (account, ignored, revision) -> pendingAppearanceForPreset(
                                accountId, account, presetId, revision));
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

    private AccountAppearanceState pendingAppearanceForPreset(
            UUID accountId,
            AccountState account,
            UUID presetId,
            long revision) {
        AppearancePreset preset = library.findPreset(account, presetId);
        SkinAsset skin = preset.skin().optionalAssetId()
                .map(assetId -> library.findSkin(account, assetId))
                .orElse(null);
        return new AccountAppearanceState(
                AccountAppearanceState.CURRENT_SCHEMA_VERSION,
                accountId,
                revision,
                preset.id(),
                skin == null ? null : skin.sha256(),
                skin == null ? null : skin.variant(),
                preset.capeId(),
                preset.outerLayerVisibility(),
                AppearanceSyncStatus.PENDING,
                0,
                clock.instant());
    }

    @Override
    public PresetDelete deletePreset(UUID presetId) throws IOException, PngValidationException {
        OperationContext context = pinCurrentSession();
        UUID accountId = resolveAccountId(context.identity());
        LibraryService.PresetDeletion deletion = library.deletePreset(
                accountId,
                Objects.requireNonNull(presetId, "presetId"),
                (ignoredAccount, ignoredAppearance, revision) -> accountDefaultAppearance(
                        accountId, revision, AppearanceSyncStatus.PENDING));
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
                selected.account().accountId(), selected.intentRevision());
        ReconciliationResult reconciled = reconcileAppearance(key, ReconciliationTrigger.LOCAL_INTENT)
                .orElseThrow(() -> new IllegalStateException(
                        "Minecraft session or local appearance changed before reconciliation"));
        return legacyRemoteResult(reconciled, "Preset reconciliation completed without a remote mutation.");
    }

    @Override
    public PresetUse usePreset(UUID presetId) throws IOException, PngValidationException {
        OperationContext context = pinCurrentSession();
        UUID accountId = resolveAccountId(context.identity());
        UUID selectedPresetId = Objects.requireNonNull(presetId, "presetId");
        NclSkinsStorage.AccountAppearanceMutationResult selected =
                storage.mutateAccountAndAppearance(accountId, (account, ignored, revision) ->
                        NclSkinsStorage.AccountAppearanceMutationPlan.appearanceOnly(
                                account,
                                pendingAppearanceForPreset(
                                        accountId, account, selectedPresetId, revision)));
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
                appearance.syncStatus());
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
                            || checkpointAppearance.intentRevision() != expected.intentRevision())) {
                return Optional.empty();
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
            if (!explicitRecovery
                    && !sessions.automaticCheckpointMayAcquireToken(context.identity())) {
                SessionValidation cached = sessions.cachedStatus(context.identity());
                AccountAppearanceState blocked = checkpointAppearance;
                if (blocked.hasIntent()
                        && (blocked.syncStatus() == AppearanceSyncStatus.PENDING
                                || blocked.syncStatus() == AppearanceSyncStatus.ATTEMPTING)) {
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
            ObservedAccount observed = observeCheckpointAccount(accountId, validation);
            AccountAppearanceState appearance = storage.loadAppearance(accountId);


            if (appearance.intentRevision() != checkpointAppearance.intentRevision()) {
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

            if (appearance.capeId() != null
                    && appearance.syncStatus() != AppearanceSyncStatus.OFFICIAL
                    && !validation.profile().ownsCape(appearance.capeId())) {
                long previousRevision = appearance.intentRevision();
                AppearanceSyncStatus previousStatus = appearance.syncStatus();
                NclSkinsStorage.AppearanceIntentUpdate effective = storage.updateAppearanceIntentIfCurrent(
                        accountId,
                        previousRevision,
                        previousStatus,
                        (current, revision) -> new AccountAppearanceState(
                                current.schemaVersion(),
                                current.accountId(),
                                revision,
                                current.activePresetId(),
                                current.skinSha256(),
                                current.skinVariant(),
                                null,
                                current.outerLayerVisibility(),
                                AppearanceSyncStatus.PENDING,
                                0,
                                clock.instant()));
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
                Optional<ActiveAppearance> actual = activeAppearance(validation.profile());
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

            Optional<ActiveAppearance> actual = activeAppearance(validation.profile());
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
        } catch (RuntimeException unavailableToken) {
            SessionValidation validation = sessions.rememberTokenSourceFailure(context.identity());
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
        }
    }

    private ReconciliationResult applyFullIntent(
            UUID accountId,
            OperationContext context,
            AccountAppearanceState appearance,
            AppearanceSyncStatus expectedStatus,
            SessionValidation validation) throws IOException, PngValidationException {
        long revision = appearance.intentRevision();


        PresetApplicationRequest request = requestFromAppearance(appearance);
        AccountAppearanceState claimed = claimAppearance(accountId, revision, expectedStatus);
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
                () -> appearanceStillCurrent(accountId, revision));
        AccountAppearanceState settled = settleAfterMutation(
                accountId,
                revision,
                AppearanceSyncStatus.ATTEMPTING,
                settlementStatus(outcome),
                outcome);
        return reconciliationAfterMutation(accountId, context, settled, outcome);
    }

    private ReconciliationResult applyCapeRecovery(
            UUID accountId,
            OperationContext context,
            AccountAppearanceState appearance,
            SessionValidation validation) throws IOException {
        long revision = appearance.intentRevision();
        AccountAppearanceState claimed = claimAppearance(
                accountId, revision, AppearanceSyncStatus.PARTIAL);
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
                () -> appearanceStillCurrent(accountId, revision));
        AppearanceSyncStatus status = switch (outcome.result()) {
            case APPLIED -> AppearanceSyncStatus.OFFICIAL;
            case UNKNOWN -> AppearanceSyncStatus.UNKNOWN;
            case PARTIAL, FAILED, SESSION_EXPIRED -> AppearanceSyncStatus.PARTIAL;
        };
        AccountAppearanceState settled = settleAfterMutation(
                accountId,
                revision,
                AppearanceSyncStatus.ATTEMPTING,
                status,
                outcome);
        return reconciliationAfterMutation(accountId, context, settled, outcome);
    }

    private ReconciliationResult applyPendingCapeDelta(
            UUID accountId,
            OperationContext context,
            AccountAppearanceState appearance,
            SessionValidation validation) throws IOException {
        long revision = appearance.intentRevision();
        AccountAppearanceState claimed = claimAppearance(
                accountId, revision, AppearanceSyncStatus.PENDING);
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
                () -> appearanceStillCurrent(accountId, revision));
        AccountAppearanceState settled = settleAfterMutation(
                accountId,
                revision,
                AppearanceSyncStatus.ATTEMPTING,
                settlementStatus(outcome),
                outcome);
        return reconciliationAfterMutation(accountId, context, settled, outcome);
    }

    private ReconciliationResult reconciliationAfterMutation(
            UUID accountId,
            OperationContext context,
            AccountAppearanceState settled,
            PresetApplicationOutcome outcome) {
        RemoteResult remote = refreshAfterMutation(context, outcome);
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
            case SESSION_REFRESHED -> sessions.cachedStatus(context.identity());
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
            UUID accountId, SessionValidation validation) throws IOException, PngValidationException {
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
            observeProfileValidated(observed);
        } else {
            observeLocal(observed);
        }
        return new ObservedAccount(
                observed, latestOfficialAsset(observed).map(SkinAsset::id));
    }

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
                appearance.optionalOuterLayerVisibility());
    }

    private AccountAppearanceState claimAppearance(
            UUID accountId, long revision, AppearanceSyncStatus expectedStatus) throws IOException {
        return storage.updateAppearance(accountId, current -> {
            if (current.intentRevision() != revision
                    || current.syncStatus() != expectedStatus) {
                return current;
            }
            return copyAppearanceStatus(current, AppearanceSyncStatus.ATTEMPTING, current.settledRevision());
        });
    }

    private PresetApplicationRequest requestFromAppearance(AccountAppearanceState appearance)
            throws IOException, PngValidationException {
        ResolvedSkinAsset resolved = null;
        SkinReference skin = SkinReference.accountDefault();
        if (appearance.skinSha256() != null) {
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
        return new PresetApplicationRequest(preset, resolved);
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
        return failureKind == ApiFailureKind.NETWORK
                || failureKind == ApiFailureKind.SERVER_ERROR
                || failureKind == ApiFailureKind.RATE_LIMITED;
    }

    private static boolean allowsAutomaticMutationRetry(PresetApplicationOutcome outcome) {
        ApiFailureKind failureKind = outcome.failureKind();
        return allowsAutomaticCheckpointRetry(failureKind);
    }

    private AppearanceComparison compareAppearance(
            AccountAppearanceState expected, RemoteProfile profile) {
        Optional<ActiveAppearance> actual = activeAppearance(profile);
        if (actual.isEmpty()) {
            return AppearanceComparison.UNRESOLVED;
        }
        return appearanceMatches(expected, actual.orElseThrow())
                ? AppearanceComparison.MATCH
                : AppearanceComparison.DIFFERENT;
    }

    private static boolean appearanceMatches(
            AccountAppearanceState expected, ActiveAppearance actual) {
        return Objects.equals(expected.capeId(), actual.capeId())
                && skinMatches(expected, actual);
    }

    private static boolean skinMatches(
            AccountAppearanceState expected, ActiveAppearance actual) {
        return expected.skinSha256() == null
                ? actual.accountDefault()
                : !actual.accountDefault()
                        && expected.skinSha256().equals(actual.skinSha256())
                        && expected.skinVariant() == actual.variant();
    }

    private boolean appearanceStillCurrent(UUID accountId, long revision) {
        try {
            AccountAppearanceState current = storage.loadAppearance(accountId);
            return current.intentRevision() == revision
                    && current.syncStatus() == AppearanceSyncStatus.ATTEMPTING;
        } catch (IOException unavailableState) {
            return false;
        }
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
        ReconciliationKey key = new ReconciliationKey(accountId, appearance.intentRevision());
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


        return initializeFresh(pinCurrentSession(), true);
    }

    @Override
    public boolean rateLimited() {
        return profileApi.rateLimitRemaining().isPresent();
    }

    @Override
    public Optional<java.time.Duration> rateLimitRemaining() {
        return profileApi.rateLimitRemaining();
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
            OperationContext context, PresetApplicationOutcome outcome) {
        try {
            UUID accountId = resolveAccountId(context.identity());
            AccountState state = library.load(accountId);
            SessionValidation validation = sessions.currentStatus(context.tokens());
            OfficialSkinSync official = syncCurrentOfficial(state, validation);
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
        UUID currentId = latestOfficialAsset(state).map(SkinAsset::id).orElse(null);
        if (validation.status() != SessionStatus.VALID
                || validation.profile() == null
                || !state.accountId().equals(validation.sessionIdentity().profileId())
                || !state.accountId().equals(validation.profile().id())) {
            return new OfficialSkinSync(state, currentId, false);
        }
        RemoteProfile profile = validation.profile();
        final AppliedAppearance acknowledged;
        try {
            acknowledged = sessions.currentAppliedAppearance(profile);
        } catch (IllegalStateException unknownAcknowledgedAppearance) {
            return new OfficialSkinSync(state, currentId, false);
        }
        if (acknowledged.localSkinSha256().isPresent()) {
            String hash = acknowledged.localSkinSha256().orElseThrow();
            SkinVariant variant = acknowledged.skinVariant().orElseThrow();
            Optional<SkinAsset> existing = state.skinAssets().stream()
                    .filter(asset -> asset.sha256().equals(hash) && asset.variant() == variant)
                    .findFirst();
            if (existing.isPresent()) {
                return new OfficialSkinSync(state, existing.orElseThrow().id(), true);
            }
            try {
                SeededAsset seeded = ensureLibraryAsset(
                        state,
                        "Current official",
                        variant,
                        SkinSource.CURRENT_OFFICIAL,
                        storage.readAsset(hash));
                return new OfficialSkinSync(seeded.state(), seeded.asset().id(), true);
            } catch (IOException | PngValidationException unavailableLocalAsset) {
                return new OfficialSkinSync(state, currentId, false);
            }
        }
        if (acknowledged.usesAccountDefaultSkin()) {
            return new OfficialSkinSync(state, null, true);
        }
        RemoteSkin activeSkin = profile.activeSkin().orElse(null);
        if (activeSkin == null) {
            return new OfficialSkinSync(state, currentId, false);
        }
        try {
            SeededAsset seeded = ensureLibraryAsset(
                    state,
                    "Current official",
                    activeSkin.variant(),
                    SkinSource.CURRENT_OFFICIAL,
                    officialSkinTextures.load(activeSkin));
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
                recordAppearance(accountId, state, matchedId, AppearanceSyncStatus.OFFICIAL);
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
        return storage.updateAppearanceIntent(accountId, (ignored, revision) ->
                new AccountAppearanceState(
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
                        clock.instant()));
    }

    private AccountAppearanceState recordAccountDefaultAppearance(
            UUID accountId, AppearanceSyncStatus status) throws IOException {
        return storage.updateAppearanceIntent(accountId, (ignored, revision) ->
                accountDefaultAppearance(accountId, revision, status));
    }

    private AccountAppearanceState accountDefaultAppearance(
            UUID accountId,
            long revision,
            AppearanceSyncStatus status) {
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
                clock.instant());
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
            long revision,
            AppearanceSyncStatus expectedStatus,
            AppearanceSyncStatus status,
            PresetApplicationOutcome outcome) throws IOException {
        try {
            return settleAppearance(accountId, revision, expectedStatus, status);
        } catch (IOException | RuntimeException localFailure) {


            throw new RemoteMutationSettlementException(outcome.remoteAppearanceImpact());
        }
    }

    private AccountAppearanceState copyAppearanceStatus(
            AccountAppearanceState current,
            AppearanceSyncStatus status,
            long settledRevision) {
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
                clock.instant());
    }

    private Optional<AppliedAppearance> materializeLocalAppearance(
            UUID accountId,
            UUID runningProfileId,
            AccountAppearanceState appearance,
            SessionValidation validation) {
        if (!appearance.hasIntent()) {
            return Optional.empty();
        }
        Optional<String> localCapeCacheKey = appearance.optionalCapeId()
                .flatMap(capeId -> cachedLocalCapeKey(accountId, capeId, validation));
        if (appearance.optionalSkinSha256().isPresent()) {
            return Optional.of(AppliedAppearance.localSkin(
                    runningProfileId,
                    appearance.optionalSkinSha256().orElseThrow(),
                    appearance.optionalSkinVariant().orElseThrow(),
                    Optional.empty(),
                    localCapeCacheKey));
        }
        return Optional.of(AppliedAppearance.accountDefault(
                runningProfileId, Optional.empty(), localCapeCacheKey));
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
        String capeId = profile.activeCape().map(RemoteCape::id).orElse(null);
        final AppliedAppearance applied;
        try {
            applied = sessions.currentAppliedAppearance(profile);
        } catch (IllegalStateException unknownAcknowledgedSkin) {
            return Optional.empty();
        }
        if (applied.localSkinSha256().isPresent()) {
            return Optional.of(ActiveAppearance.local(
                    applied.localSkinSha256().orElseThrow(),
                    applied.skinVariant().orElseThrow(),
                    capeId));
        }
        if (applied.usesAccountDefaultSkin()) {
            return Optional.of(ActiveAppearance.accountDefault(capeId));
        }
        RemoteSkin activeSkin = profile.activeSkin().orElse(null);
        if (activeSkin == null
                || applied.skinTexture().filter(activeSkin.textureUri()::equals).isEmpty()) {
            return Optional.empty();
        }
        try {
            Optional<byte[]> cached = textures.readIfCached(activeSkin.textureUri());
            if (cached.isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(ActiveAppearance.local(
                    sha256(cached.orElseThrow()), activeSkin.variant(), capeId));
        } catch (IOException | RuntimeException unavailableTexture) {
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
                    throw new IllegalStateException(
                            "Minecraft session changed while an operation was in progress");
                }
                return request.execute(accessToken);
            });
        }

        private <T> T withRequestToken(ScopedTokenRequest<T> request) {
            Objects.requireNonNull(request, "request");
            return delegate.withSession((current, accessToken) -> {
                if (!identity.profileId().equals(current.profileId())) {
                    throw new IllegalStateException(
                            "Minecraft session changed while an operation was in progress");
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
            Map<CatalogVariantKey, UUID> personalAssets) {
        private CatalogDiscovery {
            collections = List.copyOf(Objects.requireNonNull(collections, "collections"));
            variantHashes = Map.copyOf(Objects.requireNonNull(variantHashes, "variantHashes"));
            personalAssets = Map.copyOf(Objects.requireNonNull(personalAssets, "personalAssets"));
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
            Map<CatalogVariantKey, UUID> personalAssets) {
        private CatalogSnapshot {
            Objects.requireNonNull(accountId, "accountId");
            variantHashes = Map.copyOf(Objects.requireNonNull(variantHashes, "variantHashes"));
            personalAssets = Map.copyOf(Objects.requireNonNull(personalAssets, "personalAssets"));
        }

        private static CatalogSnapshot empty() {
            return new CatalogSnapshot(
                    new UUID(0L, 0L), Map.of(), Map.of());
        }
    }
}

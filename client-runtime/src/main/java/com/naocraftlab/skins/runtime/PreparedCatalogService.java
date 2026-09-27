package com.naocraftlab.skins.runtime;

import com.naocraftlab.skins.client.CapeCatalogSource;
import com.naocraftlab.skins.client.CatalogCollectionOrder;
import com.naocraftlab.skins.client.CatalogText;
import com.naocraftlab.skins.client.PersonalSkinCatalog;
import com.naocraftlab.skins.client.SkinCatalogSource;
import com.naocraftlab.skins.client.SkinModel;
import com.naocraftlab.skins.core.compatibility.SkinFeatureEvidence;
import com.naocraftlab.skins.core.model.AccountState;
import com.naocraftlab.skins.core.model.PersonalSkinEntry;
import com.naocraftlab.skins.core.model.PersonalSkinSource;
import com.naocraftlab.skins.core.model.SkinVariant;
import com.naocraftlab.skins.core.png.PngValidationException;
import com.naocraftlab.skins.core.png.PngValidator;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import static com.naocraftlab.skins.runtime.CatalogRead.*;

final class PreparedCatalogService implements CatalogRead, CatalogMaterialization {
    private final SkinCatalogSource bundledSkins;
    private final CatalogAccountAccess accounts;
    private volatile PreparedResources preparedResources = new PreparedResources(null, ResourceCapeDiscovery.empty());

    private volatile CatalogSnapshot catalogSnapshot = CatalogSnapshot.empty();

    private volatile CatalogDiscoveryCache catalogDiscoveryCache;

    private volatile CapeView capeView = CapeView.empty();

    PreparedCatalogService(SkinCatalogSource sources, CatalogAccountAccess accounts) {
        this.bundledSkins = Objects.requireNonNull(sources, "sources");
        this.accounts = Objects.requireNonNull(accounts, "accounts");
    }

    void invalidatePersonalView() { catalogDiscoveryCache = null; }

    void publishInitializedAccount(UUID accountId, AccountState account) {
        long generation = capeCatalogGeneration();
        ResourceCapeDiscovery discovery = preparedResources.capes();
        if (generation != Long.MIN_VALUE && discovery.generation() == generation) {
            publishCapeEditorData(accountId, account, generation, discovery);
        }
    }

    private void requireCapeAccount(UUID accountId) throws IOException {
        accounts.requireCurrent(accountId);
    }

    @Override
    public synchronized List<SkinCatalogSource.CollectionDescriptor> catalogCollections() throws IOException {
        UUID accountId = accounts.currentAccountId();
        AccountState account = accounts.load(accountId);
        long generation = bundledSkins.generation();
        CatalogDiscoveryCache cached = catalogDiscoveryCache;
        CatalogDiscovery discovery;
        if (generation != Long.MIN_VALUE
                && cached != null
                && cached.matches(accountId, account.personalSkins(), generation)) {
            discovery = cached.discovery();
        } else {
            discovery = discoverAvailableCatalogCollections(accountId, account, generation);
            catalogDiscoveryCache = generation == Long.MIN_VALUE
                    ? null
                    : new CatalogDiscoveryCache(
                            accountId, account.personalSkins(), generation, discovery);
        }
        accounts.requireCurrent(accountId);
        if (bundledSkins.generation() != generation || !accounts.load(accountId).equals(account)) {
            throw new IOException("Catalog changed during preparation");
        }
        catalogSnapshot = new CatalogSnapshot(
                accountId, generation, account.updatedAt(),
                discovery.variantHashes(),
                discovery.personalAssets(),
                discovery.featureEvidence(), discovery.resources());
        return discovery.collections();
    }

    @Override
    public Map<CatalogVariant, SkinFeatureEvidence> catalogFeatureEvidence() {
        return catalogSnapshot.featureEvidence();
    }

    private CatalogDiscovery discoverAvailableCatalogCollections(
            UUID accountId, AccountState account, long generation) throws IOException {
        List<SkinCatalogSource.CollectionDescriptor> collections = new ArrayList<>();
        Map<CatalogVariantKey, String> variantHashes = new HashMap<>();
        Map<CatalogVariantKey, UUID> personalAssets = new HashMap<>();
        Map<CatalogVariant, SkinFeatureEvidence> featureEvidence = new HashMap<>();
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
        ResourceSkinDiscovery resources = prepareResourceSkins(generation);
        collections.addAll(resources.discovery().collections());
        variantHashes.putAll(resources.discovery().variantHashes());
        featureEvidence.putAll(resources.discovery().featureEvidence());
        accounts.requireCurrent(accountId);
        return new CatalogDiscovery(collections, variantHashes, personalAssets, featureEvidence, resources.snapshot());
    }

    private synchronized ResourceSkinDiscovery prepareResourceSkins(long generation) throws IOException {
        ResourceSkinDiscovery cached = preparedResources.skins();
        if (generation != Long.MIN_VALUE && cached != null
                && cached.snapshot().generation() == generation) return cached;
        List<SkinCatalogSource.CollectionDescriptor> collections = new ArrayList<>();
        Map<CatalogVariantKey, String> variantHashes = new HashMap<>();
        Map<CatalogVariant, SkinFeatureEvidence> featureEvidence = new HashMap<>();
        List<PreparedCatalogSnapshot.Entry> prepared = new ArrayList<>();
        for (SkinCatalogSource.CollectionDescriptor collection : bundledSkins.collections()) {
            if (PersonalSkinCatalog.isCollection(collection.id())) {
                continue;
            }
            List<SkinCatalogSource.SkinDescriptor> skins = new ArrayList<>();
            for (SkinCatalogSource.SkinDescriptor skin : collection.skins()) {
                List<SkinModel> availableModels = new ArrayList<>();
                for (SkinModel model : skin.models()) {
                    try {
                        byte[] source = bundledSkins.load(collection.id(), skin.id(), model);
                        PngValidator validator = new PngValidator();
                        var projection = validator.projectImport(source);
                        byte[] normalized = projection.pngBytes();
                        CatalogCollectionOrder provenance = skin.provenance().getOrDefault(model, collection.order());
                        prepared.add(new PreparedCatalogSnapshot.Entry(
                                new PreparedCatalogSnapshot.LogicalKey(provenance.sourceId(), collection.id(), skin.id(),
                                        PreparedCatalogSnapshot.Component.SKIN, Optional.of(model == SkinModel.SLIM
                                                ? SkinVariant.SLIM : SkinVariant.CLASSIC)),
                                new PreparedCatalogSnapshot.SourceIdentity(sha256(source)),
                                new PreparedCatalogSnapshot.VisualIdentity(validator.renderSha256(normalized)),
                                new PreparedCatalogSnapshot.RawPixelIdentity(validator.rawSkinPixelSha256(source)),
                                provenance, prepared.size(), Optional.of(projection.detectedVariant()), projection.featureEvidence()));
                        variantHashes.put(
                                new CatalogVariantKey(collection.id(), skin.id(), model),
                                sha256(normalized));
                        featureEvidence.put(
                                new CatalogVariant(
                                        collection.id(),
                                        skin.id(),
                                        model == SkinModel.SLIM
                                                ? SkinVariant.SLIM
                                                : SkinVariant.CLASSIC),
                                projection.featureEvidence());
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
                            availableModels, skin.provenance()));
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
        if (bundledSkins.generation() != generation) {
            throw new IOException("Catalog resources changed during preparation");
        }
        PreparedCatalogSnapshot snapshot = new PreparedCatalogSnapshot(generation, prepared);
        ResourceSkinDiscovery result = new ResourceSkinDiscovery(
                new CatalogDiscovery(collections, variantHashes, Map.of(), featureEvidence, snapshot), snapshot);
        ResourceCapeDiscovery capes = preparedResources.capes();
        preparedResources = new PreparedResources(generation == Long.MIN_VALUE ? null : result,
                capes.generation() == bundledSkins.capeGeneration() ? capes : ResourceCapeDiscovery.empty());
        return result;
    }

    synchronized PreparedCatalogSnapshot preparedSkins() throws IOException {
        return prepareResourceSkins(bundledSkins.generation()).snapshot();
    }

    private List<SkinCatalogSource.SkinDescriptor> personalSkinDescriptors(
            UUID accountId,
            List<PersonalSkinEntry> entries,
            String collectionId,
            Map<CatalogVariantKey, String> variantHashes,
            Map<CatalogVariantKey, UUID> personalAssets,
            Map<CatalogVariant, SkinFeatureEvidence> featureEvidence) {
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
            Map<CatalogVariant, SkinFeatureEvidence> featureEvidence) {
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
            Map<CatalogVariant, SkinFeatureEvidence> featureEvidence) {
        entry.optionalAssetId(variant).ifPresent(assetId -> {
            CatalogVariantKey key = new CatalogVariantKey(
                    collectionId, entry.sha256(), model);
            models.add(model);
            variantHashes.put(key, entry.sha256());
            personalAssets.put(key, assetId);
            try {
                byte[] normalized = accounts.readAsset(entry.sha256());
                featureEvidence.put(
                        new CatalogVariant(
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
            UUID accountId = accounts.currentAccountId();
            if (!snapshot.accountId().equals(accountId) || personalAssetId == null) {
                throw new IOException("Personal catalog selection is stale; reopen Add");
            }
            byte[] normalized = accounts.readAsset(skinId);
            if (!skinId.equals(sha256(normalized))) {
                throw new IOException("Personal catalog asset changed; reopen Add");
            }
            return normalized;
        }
        if (snapshot.generation() != Long.MIN_VALUE && bundledSkins.generation() != snapshot.generation()) {
            throw new IOException("Catalog resources changed; reopen Add to refresh the catalog");
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
        UUID accountId = accounts.currentAccountId();
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
        Objects.requireNonNull(loaded, "catalog source returned null");
        if (loaded.length > PngValidator.DEFAULT_MAX_BYTES) {
            throw new PngValidationException(PngValidationException.Reason.OVERSIZED,
                    "Catalog skin exceeds the encoded texture limit");
        }
        return new PngValidator().normalizeSkin(
                loaded.clone());
    }

    @Override
    public CapeEditorData loadCapeEditorData(UUID accountId)
            throws IOException {
        requireCapeAccount(accountId);
        AccountState account = accounts.load(accountId);
        long generation = bundledSkins.capeGeneration();
        ResourceCapeDiscovery discovery = frozenResourceCapeDiscovery(generation);
        requireCapeAccount(accountId);
        return publishCapeEditorData(accountId, account, generation, discovery);
    }

    @Override
    public long capeCatalogGeneration() {
        return bundledSkins.capeGeneration();
    }

    @Override
    public void warmResourceCapeCatalog(long generation) throws IOException {
        if (generation != Long.MIN_VALUE && bundledSkins.capeGeneration() == generation) {
            prepareResourceSkins(bundledSkins.generation());
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
            publishCapeEditorData(accountId, accounts.load(accountId), generation, discovery);
        }
    }

    @Override
    public Optional<CapeEditorData> warmedCapeEditorData(UUID accountId) {
        CapeEditorData data = capeView.data();
        return data != null && data.resourceGeneration() != Long.MIN_VALUE
                && data.account().accountId().equals(accountId)
                && data.resourceGeneration() == bundledSkins.capeGeneration()
                ? Optional.of(data)
                : Optional.empty();
    }

    @Override
    public Map<String, byte[]> warmedCapePreviews(UUID accountId) {
        CapeView view = capeView;
        if (!accountId.equals(view.snapshot().accountId())) return Map.of();
        Map<String, byte[]> copy = new HashMap<>();
        view.previews().forEach((key, bytes) -> copy.put(key, bytes.clone()));
        return Map.copyOf(copy);
    }

    private synchronized CapeEditorData publishCapeEditorData(
            UUID accountId, AccountState account, long generation, ResourceCapeDiscovery discovery) {
        if (bundledSkins.capeGeneration() != generation) {
            throw new IllegalStateException("Cape catalog changed during preparation");
        }
        ResourceCapeSnapshot snapshot = new ResourceCapeSnapshot(accountId, generation, discovery.entries());
        CapeEditorData data = new CapeEditorData(account, discovery.collections(), discovery.sourceHashes(), generation);
        Map<String, byte[]> previews = new HashMap<>();
        CapeView previous = capeView;
        if (accountId.equals(previous.snapshot().accountId())) {
            previous.previews().forEach((key, bytes) -> {
                if (!key.startsWith("resource:cape:")) previews.put(key, bytes);
            });
        }
        discovery.entries().forEach((key, entry) -> previews.put(
                resourceCapePreviewKey(generation, key, entry.sourceSha256()), entry.previewBytes()));
        capeView = new CapeView(snapshot, data, previews);
        return data;
    }

    private synchronized ResourceCapeDiscovery frozenResourceCapeDiscovery(long generation) {
        ResourceCapeDiscovery cached = preparedResources.capes();
        if (generation != Long.MIN_VALUE && cached.generation() == generation) {
            return cached;
        }
        List<CapeCatalogSource.CollectionDescriptor> collections = new ArrayList<>();
        Map<ResourceCapeKey, ResourceCapeSnapshotEntry> entries = new HashMap<>();
        Map<ResourceCapeKey, String> sourceHashes = new HashMap<>();
        PngValidator validator = new PngValidator();
        List<PreparedCatalogSnapshot.Entry> prepared = new ArrayList<>();
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
                            support, cape.provenance()));
                    CatalogCollectionOrder provenance = cape.provenance().kind() == CatalogCollectionOrder.Kind.UNSPECIFIED
                            ? collection.order() : cape.provenance();
                    prepared.add(new PreparedCatalogSnapshot.Entry(
                            new PreparedCatalogSnapshot.LogicalKey(provenance.sourceId(), collection.id(), cape.id(),
                                    PreparedCatalogSnapshot.Component.CAPE, Optional.empty()),
                            new PreparedCatalogSnapshot.SourceIdentity(sourceHash),
                            new PreparedCatalogSnapshot.VisualIdentity(projection.renderSha256()),
                            new PreparedCatalogSnapshot.RawPixelIdentity(validator.rawCapePixelSha256(bytes)),
                            provenance, prepared.size(), Optional.empty(), SkinFeatureEvidence.ORDINARY));
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
        if (bundledSkins.capeGeneration() != generation) {
            throw new IllegalStateException("Cape catalog changed during preparation");
        }
        ResourceCapeDiscovery discovered = new ResourceCapeDiscovery(
                generation, collections, entries, sourceHashes, new PreparedCatalogSnapshot(generation, prepared));
        ResourceSkinDiscovery skins = preparedResources.skins();
        preparedResources = new PreparedResources(
                skins != null && skins.snapshot().generation() == bundledSkins.generation() ? skins : null,
                generation == Long.MIN_VALUE ? ResourceCapeDiscovery.empty() : discovered);
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
        ResourceCapeSnapshot snapshot = capeView.snapshot();
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
        ResourceCapeSnapshot snapshot = capeView.snapshot();
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
                || capeView.snapshot() != snapshot) {
            throw new IOException("Resource-pack cape catalog changed; reopen the editor");
        }
        com.naocraftlab.skins.core.model.PersonalCapeEntry imported =
                accounts.importCape(accountId, selection.displayName(), bytes, current -> {
                    requireCapeAccount(accountId);
                    if (bundledSkins.capeGeneration() != selection.generation() || capeView.snapshot() != snapshot) {
                        throw new IOException("Resource-pack cape changed before commit");
                    }
                    byte[] currentBytes = bundledSkins.loadCape(selection.collectionId(), selection.capeId());
                    requireCapeAccount(accountId);
                    if (bundledSkins.capeGeneration() != selection.generation() || capeView.snapshot() != snapshot
                            || !entry.sourceSha256().equals(sha256(currentBytes))) {
                        throw new IOException("Resource-pack cape changed before commit");
                    }
                });
        if (!imported.renderSha256().equals(selection.contentIdentity())) {
            throw new IOException("Resource-pack cape import changed identity");
        }
        return imported;
    }

    synchronized void publishOwnedCapePreviews(UUID accountId, Map<String, byte[]> owned) {
        CapeView previous = capeView;
        Map<String, byte[]> previews = new HashMap<>();
        boolean sameAccount = accountId.equals(previous.snapshot().accountId());
        if (sameAccount) previous.previews().forEach((key, bytes) -> {
            if (key.startsWith("resource:cape:")) previews.put(key, bytes);
        });
        owned.forEach((key, bytes) -> previews.put(key, bytes.clone()));
        capeView = new CapeView(sameAccount ? previous.snapshot()
                : new ResourceCapeSnapshot(accountId, Long.MIN_VALUE, Map.of()),
                sameAccount ? previous.data() : null, previews);
    }

    void requirePersonalView(PersonalCatalogView view) throws IOException {
        accounts.requireCurrent(view.accountId());
        if (!accounts.load(view.accountId()).equals(view.account())) throw new IOException("Personal catalog changed during import");
    }

    void requireImportContext(UUID accountId, Optional<ImportOperations.AccountRevision> context) throws IOException {
        accounts.requireCurrent(accountId);
        if (context.isPresent() && (!context.orElseThrow().accountId().equals(accountId)
                || !accounts.load(accountId).updatedAt().equals(context.orElseThrow().revision()))) {
            throw new IOException("Import review belongs to a stale account revision");
        }
    }

    synchronized PersonalCatalogView personalView(UUID accountId) throws IOException {
        accounts.requireCurrent(accountId);
        AccountState account = accounts.load(accountId);
        List<PersonalCatalogView.Candidate> candidates = new ArrayList<>();
        for (PersonalSkinEntry entry : account.personalSkins()) {
            for (var variant : entry.variantAssetIds().entrySet()) {
                try {
                    byte[] png = accounts.resolveSkin(account, variant.getValue());
                    var identity = new PreparedCatalogSnapshot.VisualIdentity(new PngValidator().renderSha256(png));
                    candidates.add(new PersonalCatalogView.Candidate(
                            variant.getValue(), entry.sha256(), variant.getKey(), entry.visible(), identity));
                } catch (IOException | PngValidationException | RuntimeException invalidExistingAsset) {
                }
            }
        }
        accounts.requireCurrent(accountId);
        if (!accounts.load(accountId).equals(account)) throw new IOException("Personal catalog changed during preparation");
        return new PersonalCatalogView(accountId, account.updatedAt(), account, CatalogEquivalenceIndex.build(candidates, PersonalCatalogView.Candidate::visual));
    }

    byte[] loadPersonalCandidate(PersonalCatalogView view, PersonalCatalogView.Candidate candidate)
            throws IOException, PngValidationException {
        accounts.requireCurrent(view.accountId());
        if (!accounts.load(view.accountId()).equals(view.account())) throw new IOException("Personal catalog changed during import");
        return accounts.resolveSkin(view.account(), candidate.assetId());
    }

    @Override
    public Optional<FrozenCatalogSelection> freezeCatalogSelection(String collectionId, String skinId) throws IOException {
        CatalogSnapshot snapshot = catalogSnapshot;
        accounts.requireCurrent(snapshot.accountId());
        long generation = snapshot.generation();
        Map<SkinModel, String> hashes = new java.util.EnumMap<>(SkinModel.class);
        Map<SkinModel, UUID> assets = new java.util.EnumMap<>(SkinModel.class);
        if (PersonalSkinCatalog.isCollection(collectionId)) {
            snapshot.personalAssets().forEach((key, asset) -> {
                if (key.collectionId().equals(collectionId) && key.skinId().equals(skinId)) assets.put(key.model(), asset);
            });
        } else {
            PreparedCatalogSnapshot resources = snapshot.resources();
            if (bundledSkins.generation() != generation) {
                throw new IOException("Catalog resources changed; reopen Add");
            }
            resources.entries().stream().filter(entry -> entry.key().collection().equals(collectionId)
                    && entry.key().item().equals(skinId)).forEach(entry -> hashes.put(
                            entry.key().model().orElseThrow() == SkinVariant.SLIM ? SkinModel.SLIM : SkinModel.CLASSIC,
                            entry.source().sha256()));
            if (bundledSkins.generation() != generation) {
                throw new IOException("Catalog resources changed; reopen Add");
            }
        }
        accounts.requireCurrent(snapshot.accountId());
        if (catalogSnapshot != snapshot) throw new IOException("Catalog selection changed; reopen Add");
        return Optional.of(new FrozenCatalogSelection(snapshot.accountId(), collectionId, skinId,
                generation, hashes, assets));
    }

    void validateFrozenSelection(FrozenCatalogSelection frozen, SkinVariant variant) throws IOException {
        validateFrozenSelection(frozen, variant, accounts.load(frozen.accountId()));
    }

    void validateFrozenSelection(FrozenCatalogSelection frozen, SkinVariant variant, AccountState current) throws IOException {
        accounts.requireCurrent(frozen.accountId());
        SkinModel model = variant == SkinVariant.SLIM ? SkinModel.SLIM : SkinModel.CLASSIC;
        if (PersonalSkinCatalog.isCollection(frozen.collectionId())) {
            UUID asset = frozen.personalAssets().get(model);
            if (asset == null || current.skinAssets().stream().noneMatch(value -> value.id().equals(asset)
                    && value.sha256().equals(frozen.skinId()))) throw new IOException("Personal catalog selection is stale");
            return;
        }
        if (bundledSkins.generation() != frozen.generation()) throw new IOException("Catalog resources changed; reopen Add");
        String expected = frozen.sourceHashes().get(model);
        if (expected == null || !expected.equals(sha256(bundledSkins.load(frozen.collectionId(), frozen.skinId(), model)))
                || bundledSkins.generation() != frozen.generation()) throw new IOException("Catalog source changed; reopen Add");
        accounts.requireCurrent(frozen.accountId());
    }

    void validateCatalogSave(UUID accountId, com.naocraftlab.skins.core.model.CatalogOrigin origin,
            SkinVariant variant, byte[] expected) throws IOException, PngValidationException {
        accounts.requireCurrent(accountId);
        CatalogSnapshot frozen = catalogSnapshot;
        if (!frozen.variantHashes().isEmpty() && !frozen.accountId().equals(accountId)) {
            throw new IOException("Catalog account changed; reopen Add");
        }
        byte[] current = loadCatalogSkin(origin.collectionId(), origin.skinId(),
                variant == SkinVariant.SLIM ? SkinModel.SLIM : SkinModel.CLASSIC);
        if (!sha256(new PngValidator().normalizeSkin(expected)).equals(sha256(current))) {
            throw new IOException("Catalog source changed; reopen Add");
        }
        accounts.requireCurrent(accountId);
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private record PreparedResources(ResourceSkinDiscovery skins, ResourceCapeDiscovery capes) {}

    private record ResourceSkinDiscovery(CatalogDiscovery discovery, PreparedCatalogSnapshot snapshot) {}

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
            Map<CatalogVariant, SkinFeatureEvidence> featureEvidence,
            PreparedCatalogSnapshot resources) {
        private CatalogDiscovery {
            Objects.requireNonNull(resources, "resources");
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
            UUID accountId, long generation, java.time.Instant revision,
            Map<CatalogVariantKey, String> variantHashes,
            Map<CatalogVariantKey, UUID> personalAssets,
            Map<CatalogVariant, SkinFeatureEvidence> featureEvidence,
            PreparedCatalogSnapshot resources) {
        private CatalogSnapshot {
            Objects.requireNonNull(resources, "resources");
            Objects.requireNonNull(accountId, "accountId");
            variantHashes = Map.copyOf(Objects.requireNonNull(variantHashes, "variantHashes"));
            personalAssets = Map.copyOf(Objects.requireNonNull(personalAssets, "personalAssets"));
            featureEvidence = Map.copyOf(Objects.requireNonNull(
                    featureEvidence, "featureEvidence"));
        }

        private static CatalogSnapshot empty() {
            return new CatalogSnapshot(
                    new UUID(0L, 0L), Long.MIN_VALUE, java.time.Instant.EPOCH, Map.of(), Map.of(), Map.of(),
                    new PreparedCatalogSnapshot(Long.MIN_VALUE, List.of()));
        }
    }

    private record CapeView(ResourceCapeSnapshot snapshot, CapeEditorData data, Map<String, byte[]> previews) {
        private CapeView {
            var copy = new HashMap<String, byte[]>();
            previews.forEach((key, bytes) -> copy.put(key, bytes.clone()));
            previews = Map.copyOf(copy);
        }
        private static CapeView empty() { return new CapeView(ResourceCapeSnapshot.empty(), null, Map.of()); }
    }

    private record ResourceCapeDiscovery(
            long generation,
            List<CapeCatalogSource.CollectionDescriptor> collections,
            Map<ResourceCapeKey, ResourceCapeSnapshotEntry> entries,
            Map<ResourceCapeKey, String> sourceHashes, PreparedCatalogSnapshot prepared) {
        private ResourceCapeDiscovery {
            collections = List.copyOf(Objects.requireNonNull(collections, "collections"));
            entries = Map.copyOf(Objects.requireNonNull(entries, "entries"));
            sourceHashes = Map.copyOf(Objects.requireNonNull(sourceHashes, "sourceHashes"));
        }

        private static ResourceCapeDiscovery empty() {
            return new ResourceCapeDiscovery(Long.MIN_VALUE, List.of(), Map.of(), Map.of(),
                    new PreparedCatalogSnapshot(Long.MIN_VALUE, List.of()));
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

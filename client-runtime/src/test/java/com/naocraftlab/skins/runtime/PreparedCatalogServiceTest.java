package com.naocraftlab.skins.runtime;

import com.naocraftlab.skins.client.CapeCatalogSource;
import com.naocraftlab.skins.client.PersonalSkinCatalog;
import com.naocraftlab.skins.client.ResourcePackCapeCatalog;
import com.naocraftlab.skins.client.ResourcePackSkinCatalog;
import com.naocraftlab.skins.client.SkinCatalogSource;
import com.naocraftlab.skins.client.SkinModel;
import com.naocraftlab.skins.core.model.AccountState;
import com.naocraftlab.skins.core.model.OwnedCapeInventory;
import com.naocraftlab.skins.core.model.PersonalCapeEntry;
import com.naocraftlab.skins.core.model.PersonalSkinSource;
import com.naocraftlab.skins.core.model.SkinVariant;
import com.naocraftlab.skins.core.png.PngValidator;
import com.naocraftlab.skins.core.service.LibraryService;
import com.naocraftlab.skins.core.storage.NclSkinsStorage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PreparedCatalogServiceTest {
    private static PreparedCatalogService prepared(SkinCatalogSource source,
            CatalogAccountAccess accounts) {
        return new PreparedCatalogService(source, accounts, new FileCapeStaging());
    }
    @TempDir
    Path temporaryDirectory;

    @Test
    void clientOperationsRequiresFrozenSelectionImplementation() throws Exception {
        var freeze = ClientOperations.class.getMethod("freezeCatalogSelection", String.class, String.class);
        assertFalse(freeze.isDefault());
        assertTrue(java.lang.reflect.Modifier.isAbstract(freeze.getModifiers()));
    }

    @ParameterizedTest
    @CsvSource({
            "generation, 2", "generation, 3", "generation, 4",
            "account, 2", "account, 3", "account, 4",
            "snapshot, 2", "snapshot, 3", "snapshot, 4"
    })
    void capeSourceReadRechecksEveryFenceBeforeDurablePublication(String mutation, int changedRead) throws Exception {
        byte[] capeBytes = png(64, 32, 0);
        UUID accountId = UUID.randomUUID();
        var currentAccount = new AtomicReference<>(accountId);
        var generation = new AtomicInteger(1);
        var reads = new AtomicInteger();
        var owner = new AtomicReference<PreparedCatalogService>();
        var frozenAccount = new AtomicReference<AccountState>();
        SkinCatalogSource source = new SkinCatalogSource() {
            @Override public byte[] load(String collection, String skin, SkinModel model) {
                throw new AssertionError("Skin reads are outside this cape scenario");
            }
            @Override public long capeGeneration() { return generation.get(); }
            @Override public List<CapeCatalogSource.CollectionDescriptor> capeCollections() {
                return ResourcePackCapeCatalog.build(List.of(new ResourcePackCapeCatalog.Variant(
                        "event", "hero", "pack", 0, "event:textures/entity/cape/hero.png")));
            }
            @Override public byte[] loadCape(String collection, String cape) {
                if (reads.incrementAndGet() == changedRead) {
                    switch (mutation) {
                        case "generation" -> generation.incrementAndGet();
                        case "account" -> currentAccount.set(UUID.randomUUID());
                        case "snapshot" -> owner.get().publishInitializedAccount(accountId, frozenAccount.get());
                        default -> throw new AssertionError(mutation);
                    }
                }
                return capeBytes.clone();
            }
        };
        var storage = new NclSkinsStorage(temporaryDirectory, new PngValidator(), Clock.systemUTC());
        var library = new LibraryService(new com.naocraftlab.skins.core.storage.LibraryStorageAdapter(storage), new com.naocraftlab.skins.core.storage.LibraryStorageAdapter(storage), Clock.systemUTC());
        var service = prepared(source,
                new LibraryCatalogAdapter(library, new com.naocraftlab.skins.core.storage.LibraryStorageAdapter(storage), new com.naocraftlab.skins.core.storage.LibraryStorageAdapter(storage), currentAccount::get));
        owner.set(service);
        var data = service.loadCapeEditorData(accountId);
        frozenAccount.set(library.load(accountId));
        var descriptor = data.resourceCollections().get(0).capes().get(0);
        var key = new CatalogRead.ResourceCapeKey("event", "hero");
        var selection = new CatalogRead.ResourceCapeSelection("event", "hero", "Hero",
                descriptor.contentIdentity(), data.sourceHashes().get(key), data.resourceGeneration(), true);
        assertThrows(IOException.class, () -> service.materializeResourceCape(accountId, selection));
        assertEquals(changedRead, reads.get());
        assertTrue(library.load(accountId).personalCapes().isEmpty());
    }

    @Test
    void preparedVariantsSeparateSourceRawVisualAndProvenance() throws Exception {
        byte[] first = png(64, 64, 0x00112233);
        byte[] second = png(64, 64, 0x00445566);
        MemoryAccounts accounts = new MemoryAccounts();
        SkinCatalogSource source = new SkinCatalogSource() {
            @Override public byte[] load(String collection, String skin, SkinModel model) {
                return model == SkinModel.CLASSIC ? first.clone() : second.clone();
            }
            @Override public List<CollectionDescriptor> collections() {
                return ResourcePackSkinCatalog.build(List.of(
                        new ResourcePackSkinCatalog.Variant("event", "hero", SkinModel.CLASSIC, "top", 0),
                        new ResourcePackSkinCatalog.Variant("event", "hero", SkinModel.SLIM, "lower", 1)));
            }
            @Override public long generation() { return 1; }
        };
        var service = prepared(source, accounts);
        var prepared = service.preparedSkins();
        assertEquals(2, prepared.entries().size());
        var classic = prepared.entries().get(0);
        var slim = prepared.entries().get(1);
        assertEquals(classic.visual(), slim.visual());
        assertNotEquals(classic.rawPixels(), slim.rawPixels());
        assertNotEquals(classic.source(), slim.source());
        assertEquals(Optional.of(SkinVariant.CLASSIC), classic.key().model());
        assertEquals(Optional.of(SkinVariant.SLIM), slim.key().model());
        assertEquals("top", classic.provenance().sourceId());
        assertEquals("lower", slim.provenance().sourceId());
        assertEquals(List.of(classic, slim), prepared.equivalenceIndex().candidates(classic.visual()));
        assertThrows(UnsupportedOperationException.class, () -> prepared.entries().clear());
    }

    @Test
    void resourcePreparationSurvivesPersonalRevisionAndAccountSwitch() throws Exception {
        AtomicInteger loads = new AtomicInteger();
        MemoryAccounts accounts = new MemoryAccounts();
        var source = source(png(64, 64, 0), new AtomicInteger(1), loads);
        var service = prepared(source, accounts);
        service.catalogCollections();
        accounts.state = AccountState.empty(accounts.current, Instant.EPOCH.plusSeconds(1));
        service.catalogCollections();
        accounts.current = UUID.randomUUID();
        accounts.state = AccountState.empty(accounts.current, Instant.EPOCH);
        service.catalogCollections();
        assertEquals(1, loads.get());
        assertEquals(accounts.current, service.freezeCatalogSelection("event", "hero").orElseThrow().accountId());
    }

    @Test void cpmCatalogAndExternalReviewNeverReuseAnotherTransparentCarrier() throws Exception {
        byte[] first = java.nio.file.Files.readAllBytes(Path.of("../core/src/test/resources/cpm/classic-a.png"));
        byte[] second = java.nio.file.Files.readAllBytes(Path.of("../core/src/test/resources/cpm/classic-b.png"));
        UUID accountId = UUID.randomUUID();
        var storage = new NclSkinsStorage(temporaryDirectory, new PngValidator(), Clock.systemUTC());
        var adapter = new com.naocraftlab.skins.core.storage.LibraryStorageAdapter(storage);
        var library = new LibraryService(adapter, adapter, Clock.systemUTC());
        var stored = library.createPresetFromPersonalSkin(accountId, "Stored A", "A", SkinVariant.CLASSIC,
                PersonalSkinSource.FILE, first, null);
        SkinCatalogSource source = new SkinCatalogSource() {
            public byte[] load(String collection, String skin, SkinModel model) { return model == SkinModel.CLASSIC ? first : second; }
            public List<CollectionDescriptor> collections() {
                return ResourcePackSkinCatalog.build(List.of(
                        new ResourcePackSkinCatalog.Variant("event", "hero", SkinModel.CLASSIC, "pack", 0),
                        new ResourcePackSkinCatalog.Variant("event", "hero", SkinModel.SLIM, "pack", 0)));
            }
            public long generation() { return 1; }
        };
        var service = prepared(source, new LibraryCatalogAdapter(library, adapter, adapter, () -> accountId));
        var entries = service.preparedSkins().entries();
        assertNotEquals(entries.get(0).visual(), entries.get(1).visual());
        var personal = service.personalView(accountId);
        assertEquals(stored.asset().id(), personal.first(new PngValidator().renderSha256(first)).orElseThrow().assetId());
        assertTrue(personal.first(new PngValidator().renderSha256(second)).isEmpty());
        service.catalogCollections();
        var frozen = service.freezeCatalogSelection("event", "hero").orElseThrow();
        service.validateFrozenSelection(frozen, SkinVariant.SLIM);
        assertArrayEquals(second, service.loadCatalogSkin("event", "hero", SkinModel.SLIM));
        var records = List.of(new com.naocraftlab.skins.core.importing.ExternalAppearanceRecord("B", "B", Optional.of(SkinVariant.CLASSIC),
                new com.naocraftlab.skins.core.importing.SkinLocator.EmbeddedPng(second), Optional.empty(), 0));
        var access = new ExternalImportSourceAccess() {
            public com.naocraftlab.skins.core.importing.ExternalImportProbe probe(com.naocraftlab.skins.core.importing.ExternalImportSource kind,
                    Optional<Path> root, com.naocraftlab.skins.core.importing.ExternalImportContext context) { throw new AssertionError("unused probe"); }
            public com.naocraftlab.skins.core.importing.ExternalImportBatch discover(com.naocraftlab.skins.core.importing.ExternalImportSource kind,
                    Optional<Path> root, com.naocraftlab.skins.core.importing.ExternalImportContext context) {
                return new com.naocraftlab.skins.core.importing.ExternalImportBatch(kind, records, List.of());
            }
            public Resolution resolve(com.naocraftlab.skins.core.importing.ExternalAppearanceRecord record) {
                return new Resolution(second, SkinVariant.CLASSIC, PersonalSkinSource.FILE);
            }
        };
        var importer = new ExternalAppearanceImportService(access, service, (id, selected) -> {
            var result = library.importPersonalSkinPresets(id, selected.stream().map(candidate ->
                    new LibraryService.PersonalSkinPresetImport(candidate.displayName(), candidate.displayName(), candidate.variant(),
                            candidate.source(), candidate.normalizedPng(), candidate.capeId())).toList());
            return new ExternalImportCommit.BatchResult(result.state(), result.imported(), result.alreadyPresent());
        });
        var review = importer.prepareAppearances(accountId, com.naocraftlab.skins.core.importing.ExternalImportSource.PRISM_LAUNCHER,
                Optional.empty(), new com.naocraftlab.skins.core.importing.ExternalImportContext(accountId, "Fixture", temporaryDirectory),
                OwnedCapeInventory.empty(accountId, Instant.now()));
        var candidate = review.candidates().get(0);
        assertFalse(candidate.duplicate());
        assertArrayEquals(second, candidate.normalizedPng());
        var result = importer.commitAppearances(accountId, review.candidates(), 0, 0);
        assertEquals(2, result.state().skinAssets().size());
        assertArrayEquals(first, library.resolveSkin(result.state(), stored.asset().id()).pngBytes());
        assertTrue(result.state().skinAssets().stream().anyMatch(asset -> asset.sha256().equals(candidate.sha256())));
    }

    @Test
    void reloadDuringPreparationCannotPublishMixedSkinGeneration() throws Exception {
        AtomicInteger generation = new AtomicInteger(1);
        AtomicInteger loads = new AtomicInteger();
        byte[] image = png(64, 64, 0);
        SkinCatalogSource source = new SkinCatalogSource() {
            @Override public byte[] load(String collection, String skin, SkinModel model) {
                if (loads.getAndIncrement() == 0) generation.incrementAndGet();
                return image;
            }
            @Override public List<CollectionDescriptor> collections() { return descriptors(); }
            @Override public long generation() { return generation.get(); }
        };
        var service = prepared(source, new MemoryAccounts());
        assertThrows(IOException.class, service::catalogCollections);
        assertTrue(service.catalogFeatureEvidence().isEmpty());
        assertEquals(2, service.preparedSkins().generation());
        assertEquals(2, loads.get());
    }

    @Test
    void unknownGenerationIsNeverReused() throws Exception {
        AtomicInteger loads = new AtomicInteger();
        byte[] image = png(64, 64, 0);
        SkinCatalogSource source = new SkinCatalogSource() {
            @Override public byte[] load(String collection, String skin, SkinModel model) { loads.incrementAndGet(); return image; }
            @Override public List<CollectionDescriptor> collections() { return descriptors(); }
        };
        var service = prepared(source, new MemoryAccounts());
        service.catalogCollections();
        service.catalogCollections();
        assertEquals(2, loads.get());
    }

    @ParameterizedTest
    @CsvSource({"false, before", "false, during", "true, before", "true, during"})
    void freezeCannotRelabelPreviousAccountSnapshot(boolean personal, String timing) throws Exception {
        UUID original = UUID.randomUUID();
        UUID replacement = UUID.randomUUID();
        var current = new AtomicReference<>(original);
        var armed = new java.util.concurrent.atomic.AtomicBoolean();
        var clock = Clock.fixed(Instant.EPOCH, java.time.ZoneOffset.UTC);
        var storage = new NclSkinsStorage(temporaryDirectory, new PngValidator(), clock);
        var library = new LibraryService(new com.naocraftlab.skins.core.storage.LibraryStorageAdapter(storage), new com.naocraftlab.skins.core.storage.LibraryStorageAdapter(storage), clock);
        byte[] image = png(64, 64, 0);
        if (personal) library.savePresetWithPersonalSkin(original, Optional.empty(), "Existing", "Personal",
                SkinVariant.CLASSIC, PersonalSkinSource.FILE, image, null);
        var service = prepared(source(image, new AtomicInteger(1), new AtomicInteger()),
                new LibraryCatalogAdapter(library, new com.naocraftlab.skins.core.storage.LibraryStorageAdapter(storage), new com.naocraftlab.skins.core.storage.LibraryStorageAdapter(storage), () -> {
                    UUID result = current.get();
                    if (armed.compareAndSet(true, false)) current.set(replacement);
                    return result;
                }));
        var collections = service.catalogCollections();
        String collection = personal ? PersonalSkinCatalog.COLLECTION_ID : "event";
        String item = collections.stream().filter(value -> value.id().equals(collection))
                .findFirst().orElseThrow().skins().get(0).id();
        var before = library.load(original);
        if (timing.equals("before")) current.set(replacement);
        else armed.set(true);
        assertThrows(IOException.class, () -> service.freezeCatalogSelection(collection, item));
        assertEquals(replacement, current.get());
        assertEquals(before, library.load(original));
        assertTrue(library.load(replacement).presets().isEmpty());
    }

    @Test
    void reloadDuringFreezeCannotRelabelPreparedGeneration() throws Exception {
        var generation = new AtomicInteger(1);
        var generationReads = new AtomicInteger();
        var armed = new java.util.concurrent.atomic.AtomicBoolean();
        byte[] image = png(64, 64, 0);
        SkinCatalogSource source = new SkinCatalogSource() {
            @Override public byte[] load(String collection, String skin, SkinModel model) { return image.clone(); }
            @Override public List<CollectionDescriptor> collections() { return descriptors(); }
            @Override public long generation() {
                if (armed.get() && generationReads.incrementAndGet() == 2) generation.incrementAndGet();
                return generation.get();
            }
        };
        var service = prepared(source, new MemoryAccounts());
        service.catalogCollections();
        service.loadCatalogSkin("event", "hero", SkinModel.CLASSIC);
        armed.set(true);
        assertThrows(IOException.class, () -> {
            var frozen = service.freezeCatalogSelection("event", "hero").orElseThrow();
            service.validateFrozenSelection(frozen, SkinVariant.CLASSIC);
        });
        assertEquals(2, generation.get());
    }

    @ParameterizedTest
    @CsvSource({"account", "snapshot"})
    void freezeRechecksLifecycleAfterResourcePreparation(String mutation) throws Exception {
        var armed = new java.util.concurrent.atomic.AtomicBoolean();
        var owner = new AtomicReference<PreparedCatalogService>();
        var accounts = new MemoryAccounts();
        byte[] image = png(64, 64, 0);
        SkinCatalogSource source = new SkinCatalogSource() {
            @Override public byte[] load(String collection, String skin, SkinModel model) { return image.clone(); }
            @Override public List<CollectionDescriptor> collections() { return descriptors(); }
            @Override public long generation() {
                if (armed.compareAndSet(true, false)) {
                    if (mutation.equals("account")) accounts.current = UUID.randomUUID();
                    else try { owner.get().catalogCollections(); }
                    catch (IOException failure) { throw new AssertionError(failure); }
                }
                return 1;
            }
        };
        var service = prepared(source, accounts);
        owner.set(service);
        service.catalogCollections();
        armed.set(true);
        assertThrows(IOException.class, () -> service.freezeCatalogSelection("event", "hero"));
    }

    @ParameterizedTest
    @CsvSource({"1, 2", "1, -9223372036854775808", "-9223372036854775808, 1"})
    void freezeRejectsGenerationTransitions(long original, long replacement) throws Exception {
        var generation = new java.util.concurrent.atomic.AtomicLong(original);
        byte[] image = png(64, 64, 0);
        SkinCatalogSource source = new SkinCatalogSource() {
            @Override public byte[] load(String collection, String skin, SkinModel model) { return image.clone(); }
            @Override public List<CollectionDescriptor> collections() { return descriptors(); }
            @Override public long generation() { return generation.get(); }
        };
        var service = prepared(source, new MemoryAccounts());
        service.catalogCollections();
        generation.set(replacement);
        assertThrows(IOException.class, () -> service.freezeCatalogSelection("event", "hero"));
    }

    @ParameterizedTest
    @CsvSource({"false", "true"})
    void unknownFreezeRetainsOriginalModelsAcrossSourceRefresh(boolean warmAgain) throws Exception {
        byte[] image = png(64, 64, 0);
        var changed = new java.util.concurrent.atomic.AtomicBoolean();
        SkinCatalogSource source = new SkinCatalogSource() {
            @Override public byte[] load(String collection, String skin, SkinModel model) { return image.clone(); }
            @Override public List<CollectionDescriptor> collections() {
                return ResourcePackSkinCatalog.build(changed.get() ? List.of(
                        new ResourcePackSkinCatalog.Variant("event", "hero", SkinModel.CLASSIC, "new-pack", 1),
                        new ResourcePackSkinCatalog.Variant("event", "hero", SkinModel.SLIM, "new-pack", 1)) : List.of(
                        new ResourcePackSkinCatalog.Variant("event", "hero", SkinModel.CLASSIC, "original-pack", 0)));
            }
        };
        var service = prepared(source, new MemoryAccounts());
        var selected = service.catalogCollections().get(0).skins().get(0);
        changed.set(true);
        if (warmAgain) service.preparedSkins();
        var frozen = service.freezeCatalogSelection("event", "hero").orElseThrow();
        assertEquals(Set.copyOf(selected.models()), frozen.sourceHashes().keySet());
        assertThrows(IOException.class, () -> service.validateFrozenSelection(frozen, SkinVariant.SLIM));
        assertEquals(List.of(SkinModel.CLASSIC, SkinModel.SLIM),
                service.catalogCollections().get(0).skins().get(0).models());
        assertEquals(Set.of(SkinModel.CLASSIC, SkinModel.SLIM),
                service.freezeCatalogSelection("event", "hero").orElseThrow().sourceHashes().keySet());
    }

    @Test
    void unknownGenerationFrozenSelectionStillValidatesSource() throws Exception {
        var image = new AtomicReference<>(png(64, 64, 0));
        var loads = new AtomicInteger();
        SkinCatalogSource source = new SkinCatalogSource() {
            @Override public byte[] load(String collection, String skin, SkinModel model) {
                loads.incrementAndGet();
                return image.get();
            }
            @Override public List<CollectionDescriptor> collections() { return descriptors(); }
        };
        var service = prepared(source, new MemoryAccounts());
        service.catalogCollections();
        var frozen = service.freezeCatalogSelection("event", "hero").orElseThrow();
        assertEquals(Long.MIN_VALUE, frozen.generation());
        assertEquals(1, loads.get(), "Freeze retains the published selection without rediscovery");
        service.validateFrozenSelection(frozen, SkinVariant.CLASSIC);
        image.set(png(64, 64, 0x00112233));
        assertThrows(IOException.class, () -> service.validateFrozenSelection(frozen, SkinVariant.CLASSIC));
    }

    @Test
    void frozenSelectionRejectsReloadEvenWhenBytesAndNewCatalogMatch() throws Exception {
        var generation = new AtomicInteger(1);
        var accounts = new MemoryAccounts();
        var service = prepared(source(png(64, 64, 0), generation, new AtomicInteger()), accounts);
        service.catalogCollections();
        var frozen = service.freezeCatalogSelection("event", "hero").orElseThrow();
        service.validateFrozenSelection(frozen, SkinVariant.CLASSIC);
        generation.incrementAndGet();
        service.catalogCollections();
        assertThrows(IOException.class, () -> service.validateFrozenSelection(frozen, SkinVariant.CLASSIC));
        accounts.current = UUID.randomUUID();
        assertThrows(IOException.class, () -> service.validateFrozenSelection(frozen, SkinVariant.CLASSIC));
    }

    @Test
    void sourceCarrierChangeFailsFrozenSaveDespiteIdenticalVisualProjection() throws Exception {
        var bytes = new AtomicReference<>(png(64, 64, 0x00112233));
        SkinCatalogSource source = new SkinCatalogSource() {
            @Override public byte[] load(String collection, String skin, SkinModel model) { return bytes.get(); }
            @Override public List<CollectionDescriptor> collections() { return descriptors(); }
            @Override public long generation() { return 1; }
        };
        var service = prepared(source, new MemoryAccounts());
        service.catalogCollections();
        var frozen = service.freezeCatalogSelection("event", "hero").orElseThrow();
        bytes.set(png(64, 64, 0x00445566));
        assertThrows(IOException.class, () -> service.validateFrozenSelection(frozen, SkinVariant.CLASSIC));
    }

    @Test
    void capePreparationRejectsReloadAndKeepsOriginalCarrierSeparateFromPreview() throws Exception {
        var generation = new AtomicInteger(1);
        var loads = new AtomicInteger();
        byte[] cape = png(64, 32, 0x00112233);
        SkinCatalogSource source = new SkinCatalogSource() {
            @Override public byte[] load(String collection, String skin, SkinModel model) throws IOException { throw new IOException(); }
            @Override public List<CapeCatalogSource.CollectionDescriptor> capeCollections() {
                return ResourcePackCapeCatalog.build(List.of(new ResourcePackCapeCatalog.Variant("event", "hero", "pack", 0, "event:textures/entity/cape/hero.png")));
            }
            @Override public byte[] loadCape(String collection, String id) {
                if (loads.getAndIncrement() == 0) generation.incrementAndGet();
                return cape;
            }
            @Override public long capeGeneration() { return generation.get(); }
        };
        var accounts = new MemoryAccounts();
        var service = prepared(source, accounts);
        assertThrows(IllegalStateException.class, () -> service.warmResourceCapeCatalog(1));
        assertTrue(service.warmedCapeEditorData(accounts.current).isEmpty());
        var data = service.loadCapeEditorData(accounts.current);
        var descriptor = data.resourceCollections().get(0).capes().get(0);
        var selection = new CatalogRead.ResourceCapeSelection("event", "hero", "Hero", descriptor.contentIdentity(),
                data.sourceHashes().get(new CatalogRead.ResourceCapeKey("event", "hero")), 2, true);
        byte[] preview = service.loadResourceCapePreview(accounts.current, selection).orElseThrow();
        preview[0] = 0;
        assertNotEquals(0, service.loadResourceCapePreview(accounts.current, selection).orElseThrow()[0]);
        generation.incrementAndGet();
        assertThrows(IOException.class, () -> service.materializeResourceCape(accounts.current, selection));
        assertEquals(0, accounts.commits);
    }

    @Test
    void coldBudgetContentionDoesNotFreezeEmptyPackCatalogUntilResourceReload() throws Exception {
        byte[] cape = png(64, 32, 0x00112233);
        var generation = new AtomicInteger(1);
        var reads = new AtomicInteger();
        SkinCatalogSource source = new SkinCatalogSource() {
            @Override public byte[] load(String collection, String skin, SkinModel model) {
                throw new AssertionError("No skin source is selected");
            }
            @Override public List<CollectionDescriptor> collections() { return List.of(); }
            @Override public long capeGeneration() { return generation.get(); }
            @Override public List<CapeCatalogSource.CollectionDescriptor> capeCollections() {
                return ResourcePackCapeCatalog.build(List.of(new ResourcePackCapeCatalog.Variant(
                        "event", "hero", "pack", 0, "event:textures/entity/cape/hero.png")));
            }
            @Override public byte[] loadCape(String collection, String id) {
                reads.incrementAndGet();
                return cape;
            }
        };
        MemoryAccounts accounts = new MemoryAccounts();
        try (var service = prepared(source, accounts)) {
            var pressure = CapePreparationQueue.tryReserveScratch(512L * 1024 * 1024);
            assertNotNull(pressure);
            try (pressure) {
                service.warmResourceCapeCatalog(1);
                assertTrue(service.loadCapeEditorData(accounts.current).resourceCollections().isEmpty());
            }
            var ready = service.loadCapeEditorData(accounts.current);
            assertEquals(1, ready.resourceCollections().size(),
                    "account/editor readiness must retry a cape skipped only by transient capacity");
            assertEquals(1, ready.resourceCollections().get(0).capes().size());
            assertEquals(1, reads.get());
            generation.incrementAndGet();
            assertEquals(1, service.loadCapeEditorData(accounts.current).resourceCollections().size(),
                    "explicit F3+T generation changes must still rebuild the catalog");
        }
    }

    @Test
    void permanentlyInvalidPackCapeIsNotRetriedUntilResourceGenerationChanges() throws Exception {
        byte[] truncated = Arrays.copyOf(png(64, 32, 0), 8);
        var generation = new AtomicInteger(1);
        var opens = new AtomicInteger();
        SkinCatalogSource source = new SkinCatalogSource() {
            @Override public byte[] load(String collection, String skin, SkinModel model) {
                throw new AssertionError("No skin source is selected");
            }
            @Override public List<CollectionDescriptor> collections() { return List.of(); }
            @Override public long capeGeneration() { return generation.get(); }
            @Override public List<CapeCatalogSource.CollectionDescriptor> capeCollections() {
                return ResourcePackCapeCatalog.build(List.of(new ResourcePackCapeCatalog.Variant(
                        "event", "broken", "pack", 0, "event:textures/entity/cape/broken.png")));
            }
            @Override public byte[] loadCape(String collection, String id) {
                opens.incrementAndGet();
                return truncated;
            }
        };
        MemoryAccounts accounts = new MemoryAccounts();
        try (var service = prepared(source, accounts)) {
            assertTrue(service.loadCapeEditorData(accounts.current).resourceCollections().isEmpty());
            assertTrue(service.loadCapeEditorData(accounts.current).resourceCollections().isEmpty());
            assertEquals(1, opens.get(), "a corrupt source must not force repeated same-generation work");
            generation.incrementAndGet();
            assertTrue(service.loadCapeEditorData(accounts.current).resourceCollections().isEmpty());
            assertEquals(2, opens.get(), "F3+T may re-evaluate a changed resource generation");
        }
    }

    @Test
    void animatedPackCapeStreamsPreviewsAndSavesEveryFrameAfterPackRemoval() throws Exception {
        byte[] animation = twoFrameGif();
        var generation = new AtomicInteger(1);
        var available = new AtomicReference<>(animation);
        SkinCatalogSource source = new SkinCatalogSource() {
            @Override public byte[] load(String collection, String skin, SkinModel model) throws IOException {
                throw new IOException("Skin reads are outside this cape scenario");
            }
            @Override public List<CapeCatalogSource.CollectionDescriptor> capeCollections() {
                return available.get() == null ? List.of() : ResourcePackCapeCatalog.build(List.of(
                        new ResourcePackCapeCatalog.Variant("event", "hero", "pack", 0,
                                "event:textures/entity/cape/hero.gif")));
            }
            @Override public long capeGeneration() { return generation.get(); }
            @Override public byte[] loadCape(String collection, String cape) {
                throw new AssertionError("Prepared pack capes must stream their source");
            }
            @Override public java.io.InputStream openCape(String collection, String cape) throws IOException {
                byte[] current = available.get();
                if (current == null) throw new IOException("pack removed");
                return new ByteArrayInputStream(current);
            }
        };
        var storage = new NclSkinsStorage(temporaryDirectory, new PngValidator(), Clock.systemUTC());
        var adapter = new com.naocraftlab.skins.core.storage.LibraryStorageAdapter(storage);
        var library = new LibraryService(adapter, adapter, Clock.systemUTC());
        UUID accountId = UUID.randomUUID();
        try (var service = prepared(source,
                new LibraryCatalogAdapter(library, adapter, adapter, () -> accountId))) {
            var data = service.loadCapeEditorData(accountId);
            var descriptor = data.resourceCollections().get(0).capes().get(0);
            var key = new CatalogRead.ResourceCapeKey("event", "hero");
            var selection = new CatalogRead.ResourceCapeSelection("event", "hero", "Hero",
                    descriptor.contentIdentity(), data.sourceHashes().get(key), data.resourceGeneration(), true);
            byte[] firstPreview = service.loadResourceCapePreview(accountId, selection).orElseThrow();
            assertArrayEquals(new byte[] {(byte) 137, 80, 78, 71}, Arrays.copyOf(firstPreview, 4));
            try (var lease = service.acquirePreparedResourceCape(accountId, selection).orElseThrow()) {
                assertEquals(2, lease.asset().frameCount());
                assertEquals(List.of(50, 70), lease.asset().delaysMillis());
                assertEquals(1, lease.asset().repeatCount());
                assertFalse(Arrays.equals(lease.asset().readFrame(0), lease.asset().readFrame(1)));
            }
            var saved = service.materializeResourceCape(accountId, selection);
            assertEquals(descriptor.contentIdentity(), saved.renderSha256());
            assertEquals(1, library.load(accountId).personalCapes().size());
            available.set(null);
            generation.incrementAndGet();
            service.loadCapeEditorData(accountId);
            assertTrue(service.acquirePreparedResourceCape(accountId, selection).isEmpty());
            var reopened = new NclSkinsStorage(temporaryDirectory, new PngValidator(), Clock.systemUTC());
            var retained = reopened.readPreparedCapeAsset(accountId, saved.texture().sha256()).orElseThrow();
            assertEquals(2, retained.frameCount());
            assertEquals(List.of(50, 70), retained.delaysMillis());
            assertEquals(1, retained.repeatCount());
            assertEquals(descriptor.contentIdentity(), retained.visualSha256());
            assertFalse(Arrays.equals(retained.readFrame(0), retained.readFrame(1)));
        }
    }

    @Test
    void retiredPackBackingLivesUntilFinalPreviewLeaseCloses() throws Exception {
        byte[] animation = twoFrameGif();
        var generation = new AtomicInteger(1);
        SkinCatalogSource source = new SkinCatalogSource() {
            @Override public byte[] load(String collection, String skin, SkinModel model) { return animation; }
            @Override public byte[] loadCape(String collection, String cape) { return animation; }
            @Override public long capeGeneration() { return generation.get(); }
            @Override public List<CapeCatalogSource.CollectionDescriptor> capeCollections() {
                return ResourcePackCapeCatalog.build(List.of(new ResourcePackCapeCatalog.Variant(
                        "event", "hero", "pack", 0, "event:textures/entity/cape/hero.gif")));
            }
        };
        var accounts = new MemoryAccounts();
        var service = prepared(source, accounts);
        var data = service.loadCapeEditorData(accounts.current);
        var descriptor = data.resourceCollections().get(0).capes().get(0);
        var selection = new CatalogRead.ResourceCapeSelection("event", "hero", "Hero",
                descriptor.contentIdentity(), data.sourceHashes().get(new CatalogRead.ResourceCapeKey("event", "hero")),
                data.resourceGeneration(), true);
        var lease = service.acquirePreparedResourceCape(accounts.current, selection).orElseThrow();
        Path backing = lease.asset().backing();
        generation.incrementAndGet();
        service.loadCapeEditorData(accounts.current);
        assertTrue(java.nio.file.Files.exists(backing));
        assertEquals(2, lease.asset().frameCount());
        assertTrue(service.acquirePreparedResourceCape(accounts.current, selection).isEmpty());
        lease.close();
        lease.close();
        assertFalse(java.nio.file.Files.exists(backing));
        service.close();
    }

    private static byte[] twoFrameGif() throws IOException {
        var output = new ByteArrayOutputStream();
        output.write("GIF89a".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        gifShort(output, 64); gifShort(output, 32);
        output.write(new byte[] {(byte) 0xf1, 0, 0, 0, 0, 0, (byte) 255, 0, 0,
                0, (byte) 255, 0, 0, 0, (byte) 255});
        output.write(new byte[] {0x21, (byte) 0xff, 11});
        output.write("NETSCAPE2.0".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        output.write(new byte[] {3, 1, 1, 0, 0});
        gifFrame(output, 1, 5);
        gifFrame(output, 2, 7);
        output.write(0x3b);
        return output.toByteArray();
    }

    private static void gifFrame(ByteArrayOutputStream output, int index, int delay) throws IOException {
        output.write(new byte[] {0x21, (byte) 0xf9, 4, 4});
        gifShort(output, delay);
        output.write(new byte[] {0, 0, 0x2c});
        gifShort(output, 0); gifShort(output, 0);
        gifShort(output, 64); gifShort(output, 32);
        output.write(new byte[] {0, 2});
        var codes = new ByteArrayOutputStream();
        int bits = 0, count = 0;
        for (int i = 0; i < 64 * 32 * 2 + 1; i++) {
            int code = i == 64 * 32 * 2 ? 5 : (i & 1) == 0 ? 4 : index;
            bits |= code << count;
            count += 3;
            if (count >= 8) { codes.write(bits & 255); bits >>>= 8; count -= 8; }
        }
        if (count > 0) codes.write(bits & 255);
        byte[] packed = codes.toByteArray();
        for (int offset = 0; offset < packed.length; offset += 255) {
            int length = Math.min(255, packed.length - offset);
            output.write(length);
            output.write(packed, offset, length);
        }
        output.write(0);
    }

    private static void gifShort(ByteArrayOutputStream output, int value) {
        output.write(value & 255);
        output.write(value >>> 8 & 255);
    }

    private static SkinCatalogSource source(byte[] image, AtomicInteger generation, AtomicInteger loads) {
        return new SkinCatalogSource() {
            @Override public byte[] load(String collection, String skin, SkinModel model) { loads.incrementAndGet(); return image; }
            @Override public List<CollectionDescriptor> collections() { return descriptors(); }
            @Override public long generation() { return generation.get(); }
        };
    }
    private static List<SkinCatalogSource.CollectionDescriptor> descriptors() {
        return ResourcePackSkinCatalog.build(List.of(new ResourcePackSkinCatalog.Variant("event", "hero", SkinModel.CLASSIC, "pack", 0)));
    }
    static byte[] png(int width, int height, int transparentCarrier) throws Exception {
        var image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) image.setRGB(x, y, 0xff557799);
        image.setRGB(63, 0, transparentCarrier);
        var output = new ByteArrayOutputStream();
        ImageIO.write(image, "PNG", output);
        return output.toByteArray();
    }
    static byte[] withTextChunk(byte[] png) throws IOException {
        var output = new ByteArrayOutputStream();
        var data = new java.io.DataOutputStream(output);
        data.write(png, 0, png.length - 12);
        byte[] body = "note\0changed".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
        byte[] type = "tEXt".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        data.writeInt(body.length);
        data.write(type);
        data.write(body);
        var crc = new java.util.zip.CRC32();
        crc.update(type);
        crc.update(body);
        data.writeInt((int) crc.getValue());
        data.write(png, png.length - 12, 12);
        return output.toByteArray();
    }

    static final class MemoryAccounts implements CatalogAccountAccess {
        UUID current = UUID.randomUUID();
        AccountState state = AccountState.empty(current, Instant.EPOCH);
        int commits;
        @Override public UUID currentAccountId() { return current; }
        @Override public AccountState load(UUID account) throws IOException { requireCurrent(account); return state; }
        @Override public byte[] readAsset(String hash) throws IOException { throw new IOException("absent"); }
        @Override public byte[] resolveSkin(AccountState account, UUID asset) throws IOException { throw new IOException("absent"); }
        @Override public PersonalCapeEntry importCape(UUID account, String name, byte[] bytes, com.naocraftlab.skins.core.service.AccountWriteGuard guard) { commits++; throw new AssertionError("unexpected commit"); }
        @Override public void requireCurrent(UUID account) throws IOException { if (!current.equals(account)) throw new IOException("stale account"); }
    }
}

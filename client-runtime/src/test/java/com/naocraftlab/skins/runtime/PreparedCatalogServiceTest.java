package com.naocraftlab.skins.runtime;

import com.naocraftlab.skins.client.*;
import com.naocraftlab.skins.core.model.*;
import com.naocraftlab.skins.core.png.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import com.naocraftlab.skins.core.service.LibraryService;
import com.naocraftlab.skins.core.storage.NclSkinsStorage;
import java.nio.file.Path;
import java.time.Clock;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class PreparedCatalogServiceTest {
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
        var service = new PreparedCatalogService(source,
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
        var service = new PreparedCatalogService(source, accounts);
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
        var service = new PreparedCatalogService(source, accounts);
        service.catalogCollections();
        accounts.state = AccountState.empty(accounts.current, Instant.EPOCH.plusSeconds(1));
        service.catalogCollections();
        accounts.current = UUID.randomUUID();
        accounts.state = AccountState.empty(accounts.current, Instant.EPOCH);
        service.catalogCollections();
        assertEquals(1, loads.get());
        assertEquals(accounts.current, service.freezeCatalogSelection("event", "hero").orElseThrow().accountId());
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
        var service = new PreparedCatalogService(source, new MemoryAccounts());
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
        var service = new PreparedCatalogService(source, new MemoryAccounts());
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
        var service = new PreparedCatalogService(source(image, new AtomicInteger(1), new AtomicInteger()),
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
        var service = new PreparedCatalogService(source, new MemoryAccounts());
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
        var service = new PreparedCatalogService(source, accounts);
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
        var service = new PreparedCatalogService(source, new MemoryAccounts());
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
        var service = new PreparedCatalogService(source, new MemoryAccounts());
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
        var service = new PreparedCatalogService(source, new MemoryAccounts());
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
        var service = new PreparedCatalogService(source(png(64, 64, 0), generation, new AtomicInteger()), accounts);
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
        var service = new PreparedCatalogService(source, new MemoryAccounts());
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
        var service = new PreparedCatalogService(source, accounts);
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

package com.naocraftlab.skins.core.storage;

import com.google.gson.JsonParser;
import com.naocraftlab.skins.core.model.*;
import com.naocraftlab.skins.core.png.PngValidator;
import com.naocraftlab.skins.core.provider.AppearanceProviders;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Clock;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

final class CapePreviewModeStorageTest {
    @TempDir Path directory;

    NclSkinsStorage storage() {
        return new NclSkinsStorage(directory.resolve("storage"), new PngValidator(), Clock.systemUTC());
    }

    @Test void compatibilityConstructorsDefaultAndEveryWitherRetainsKnownMode() {
        UUID id = UUID.randomUUID();
        var defaults = AccountUiPreferences.defaults(id);
        var cape = defaults.withPreferredCapeMode(CapePreviewMode.ELYTRA);
        var withers = java.util.List.<java.util.function.UnaryOperator<AccountUiPreferences>>of(
                p -> p.withSelectedAddSourceTab(AddSourceTab.FILE),
                p -> p.withSelectedEditorTab(EditorTab.CAPE),
                p -> p.withSelectedProvidersTab(AppearanceProviders.Component.CAPE),
                p -> p.withPreferredSkinVariant(SkinVariant.SLIM),
                p -> p.withCollectionCollapsed("fixture:one", true),
                p -> p.withCollapsedCollectionIds(Set.of("fixture:two")),
                p -> p.withCollapsedCapeCollections(Set.of("fixture:capes")),
                p -> p.withGallerySections(Map.of(AccountUiPreferences.GALLERY_FAVORITES, true)));
        for (var wither : withers) assertEquals(CapePreviewMode.ELYTRA, wither.apply(cape).preferredCapeMode());
        for (var constructor : AccountUiPreferences.class.getConstructors()) {
            var arguments = new ArrayList<Object>();
            for (var parameter : constructor.getParameterTypes()) {
                if (parameter == int.class) arguments.add(2);
                else if (parameter == UUID.class) arguments.add(id);
                else if (parameter == AddSourceTab.class) arguments.add(AddSourceTab.FILE);
                else if (parameter == EditorTab.class) arguments.add(EditorTab.CAPE);
                else if (parameter == AppearanceProviders.Component.class) arguments.add(AppearanceProviders.Component.CAPE);
                else if (parameter == Optional.class) arguments.add(Optional.empty());
                else if (parameter == Set.class) arguments.add(Set.of());
                else if (parameter == CapePreviewMode.class) arguments.add(CapePreviewMode.ELYTRA);
                else fail("Uncovered UI preference constructor parameter");
            }
            try {
                var copy = (AccountUiPreferences) constructor.newInstance(arguments.toArray());
                assertEquals(arguments.contains(CapePreviewMode.ELYTRA) ? CapePreviewMode.ELYTRA : CapePreviewMode.CAPE,
                        copy.preferredCapeMode());
            } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
        }
        assertThrows(NullPointerException.class, () -> cape.withPreferredCapeMode(null));
        assertEquals(2, cape.schemaVersion());
    }

    @Test void concurrentModeAndOtherWritersMergeUnderTheExistingLock() throws Exception {
        UUID id = UUID.randomUUID();
        var ready = new java.util.concurrent.CountDownLatch(4);
        var start = new java.util.concurrent.CountDownLatch(1);
        var executor = java.util.concurrent.Executors.newFixedThreadPool(4);
        var updates = List.<java.util.concurrent.Callable<Object>>of(
                () -> storage().updatePreferredCapeMode(id, CapePreviewMode.ELYTRA, () -> true),
                () -> storage().setSelectedAddSourceTab(id, AddSourceTab.FILE),
                () -> storage().setPreferredSkinVariant(id, SkinVariant.SLIM),
                () -> storage().updateGallerySections(id, Map.of(AccountUiPreferences.GALLERY_FAVORITES, true), () -> true));
        try {
            var pending = new ArrayList<java.util.concurrent.Future<Object>>();
            for (var update : updates) pending.add(executor.submit(() -> {
                ready.countDown(); start.await(); return update.call();
            }));
            assertTrue(ready.await(5, java.util.concurrent.TimeUnit.SECONDS)); start.countDown();
            for (var future : pending) future.get(5, java.util.concurrent.TimeUnit.SECONDS);
        } finally { start.countDown(); executor.shutdownNow(); }
        var merged = storage().loadUiPreferences(id).preferences();
        assertEquals(CapePreviewMode.ELYTRA, merged.preferredCapeMode());
        assertEquals(AddSourceTab.FILE, merged.selectedAddSourceTab());
        assertEquals(Optional.of(SkinVariant.SLIM), merged.preferredSkinVariant());
        assertEquals(Set.of(AccountUiPreferences.GALLERY_FAVORITES), merged.collapsedGallerySections());
    }

    @SuppressWarnings("try")
    @Test void scopeRetiredAfterLockAdmissionCannotReplaceLatestDocument() throws Exception {
        var storage = storage();
        UUID id = UUID.randomUUID();
        storage.setSelectedAddSourceTab(id, AddSourceTab.FILE);
        var path = storage.layout().accountUiPreferences(id);
        byte[] before = Files.readAllBytes(path);
        var current = new java.util.concurrent.atomic.AtomicBoolean(true);
        var entered = new java.util.concurrent.CountDownLatch(1);
        var executor = java.util.concurrent.Executors.newSingleThreadExecutor();
        java.util.concurrent.Future<?> pending;
        try {
            try (var held = new InterProcessLockManager().acquire(storage.layout().accountLock(id))) {
                pending = executor.submit(() -> assertThrows(java.io.IOException.class,
                        () -> storage.updatePreferredCapeMode(id, CapePreviewMode.ELYTRA, () -> {
                            boolean accepted = current.get();
                            entered.countDown(); return accepted;
                        })));
                assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS));
                assertFalse(pending.isDone());
                current.set(false);
            }
            pending.get(5, java.util.concurrent.TimeUnit.SECONDS);
        } finally { executor.shutdownNow(); }
        assertArrayEquals(before, Files.readAllBytes(path));
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        assertThrows(java.io.IOException.class, () -> storage.updatePreferredCapeMode(id, CapePreviewMode.ELYTRA,
                () -> calls.incrementAndGet() < 3));
        assertEquals(3, calls.get(), "final atomic replacement must recheck currentness");
        assertArrayEquals(before, Files.readAllBytes(path));
    }

    @Test void optionalCapeModeDefaultsWithoutRewritingSupportedDocuments() throws Exception {
        var storage = storage();
        UUID id = UUID.randomUUID();
        storage.setSelectedAddSourceTab(id, AddSourceTab.FILE);
        Path path = storage.layout().accountUiPreferences(id);
        var base = JsonParser.parseString(Files.readString(path)).getAsJsonObject();
        for (String value : new String[] {null, "null", "\"OFF\"", "\"FUTURE\"", "17", "false", "[]", "{}"}) {
            var document = base.deepCopy();
            if (value == null) document.remove("preferredCapeMode");
            else document.add("preferredCapeMode", JsonParser.parseString(value));
            byte[] bytes = document.toString().getBytes(StandardCharsets.UTF_8);
            Files.write(path, bytes);
            var result = storage.loadUiPreferences(id);
            assertEquals(CapePreviewMode.CAPE, result.preferences().preferredCapeMode());
            assertEquals(AddSourceTab.FILE, result.preferences().selectedAddSourceTab());
            assertTrue(result.warnings().isEmpty());
            assertArrayEquals(bytes, Files.readAllBytes(path));
        }
    }

    @Test void capeModePatchSurvivesRestartAndMergesAllKnownFields() throws Exception {
        UUID id = UUID.randomUUID();
        var first = storage();
        first.setSelectedAddSourceTab(id, AddSourceTab.FILE);
        first.setSelectedEditorTab(id, EditorTab.CAPE);
        first.setPreferredSkinVariant(id, SkinVariant.SLIM);
        first.setSelectedProvidersTab(id, AppearanceProviders.Component.CAPE);
        first.setCollectionCollapsed(id, "fixture:collection", true);
        first.setCollapsedCapeCollections(id, Set.of("fixture:capes"));
        first.updateGallerySections(id, Map.of(AccountUiPreferences.GALLERY_FAVORITES, true), () -> true);
        var before = first.loadUiPreferences(id).preferences();
        var expected = before.withPreferredCapeMode(CapePreviewMode.ELYTRA);
        assertEquals(expected, first.updatePreferredCapeMode(id, CapePreviewMode.ELYTRA, () -> true));
        assertEquals(expected, storage().loadUiPreferences(id).preferences());
        assertEquals(CapePreviewMode.CAPE, storage().loadUiPreferences(UUID.randomUUID()).preferences().preferredCapeMode());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"4294967298", "2.5", "4294967297", "1.5",
            "-4294967294", "\"2\"", "true", "null", "[]", "[2]"})
    void invalidSchemaCannotAliasASupportedVersionOrReplaceBytes(String schema) throws Exception {
        var storage = storage();
        UUID id = UUID.randomUUID();
        storage.setSelectedAddSourceTab(id, AddSourceTab.FILE);
        Path path = storage.layout().accountUiPreferences(id);
        var document = JsonParser.parseString(Files.readString(path)).getAsJsonObject();
        document.add("schemaVersion", JsonParser.parseString(schema));
        byte[] bytes = document.toString().getBytes(StandardCharsets.UTF_8);
        Files.write(path, bytes);
        assertThrows(java.io.IOException.class,
                () -> storage.updatePreferredCapeMode(id, CapePreviewMode.ELYTRA, () -> true));
        assertArrayEquals(bytes, Files.readAllBytes(path));
        assertThrows(java.io.IOException.class, () -> storage.updateGallerySections(id,
                Map.of(AccountUiPreferences.GALLERY_FAVORITES, true), () -> true));
        assertArrayEquals(bytes, Files.readAllBytes(path));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints = {1, 2})
    void supportedSchemaStillAllowsModePatchAndPreservesOtherFields(int schema) throws Exception {
        var storage = storage();
        UUID id = UUID.randomUUID();
        storage.setSelectedAddSourceTab(id, AddSourceTab.FILE);
        Path path = storage.layout().accountUiPreferences(id);
        var document = JsonParser.parseString(Files.readString(path)).getAsJsonObject();
        document.addProperty("schemaVersion", schema);
        document.remove("preferredCapeMode");
        byte[] bytes = document.toString().getBytes(StandardCharsets.UTF_8);
        Files.write(path, bytes);
        assertEquals(CapePreviewMode.CAPE, storage.loadUiPreferences(id).preferences().preferredCapeMode());
        assertArrayEquals(bytes, Files.readAllBytes(path));
        storage.updatePreferredCapeMode(id, CapePreviewMode.ELYTRA, () -> true);
        var reopened = storage().loadUiPreferences(id).preferences();
        assertEquals(CapePreviewMode.ELYTRA, reopened.preferredCapeMode());
        assertEquals(AddSourceTab.FILE, reopened.selectedAddSourceTab());
    }

    @Test void capeModePatchRefusesCorruptUnsupportedForeignAndRetiredWritesWithoutReplacingBytes() throws Exception {
        var storage = storage();
        UUID id = UUID.randomUUID();
        storage.setSelectedAddSourceTab(id, AddSourceTab.FILE);
        Path path = storage.layout().accountUiPreferences(id);
        String valid = Files.readString(path);
        for (String document : new String[] {"{broken", valid.replace("\"schemaVersion\": 2", "\"schemaVersion\": 999"),
                valid.replace(id.toString(), UUID.randomUUID().toString())}) {
            byte[] bytes = document.getBytes(StandardCharsets.UTF_8);
            Files.write(path, bytes);
            assertThrows(java.io.IOException.class, () -> storage.updatePreferredCapeMode(id, CapePreviewMode.ELYTRA, () -> true));
            assertArrayEquals(bytes, Files.readAllBytes(path));
        }
        Files.writeString(path, valid);
        assertThrows(java.io.IOException.class, () -> storage.updatePreferredCapeMode(id, CapePreviewMode.ELYTRA, () -> false));
        assertEquals(valid, Files.readString(path));
    }
}

package com.naocraftlab.skins.core.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.naocraftlab.skins.core.model.AccountState;
import com.naocraftlab.skins.core.model.CatalogOrigin;
import com.naocraftlab.skins.core.model.SkinAsset;
import com.naocraftlab.skins.core.model.SkinSource;
import com.naocraftlab.skins.core.model.SkinVariant;
import com.naocraftlab.skins.core.png.PngValidator;
import com.naocraftlab.skins.core.storage.NclSkinsStorage;
import com.naocraftlab.skins.core.test.TestPng;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CatalogOriginPersistenceTest {
    private static final Instant NOW = Instant.parse("2026-07-31T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final CatalogOrigin ORIGIN =
            new CatalogOrigin(
                    "vanilla",
                    "minecraft",
                    "steve",
                    Optional.of("The original standard skin"),
                    Optional.of("Mojang Studios"));

    @TempDir
    Path temporaryDirectory;

    @Test
    void catalogImportPersistsOriginThroughRenameVariantAndDuplicate() throws Exception {
        LibraryService library = library();
        UUID accountId = UUID.randomUUID();
        ImportedSkin imported = library.importSkin(
                accountId,
                "Steve",
                SkinVariant.CLASSIC,
                SkinSource.IMPORTED,
                TestPng.create(64, 64),
                Optional.of(ORIGIN));
        UUID originalId = imported.asset().id();

        AccountState renamed = library.renameSkin(accountId, originalId, "Steve renamed");
        assertEquals(Optional.of(ORIGIN), library.findSkin(renamed, originalId).catalogOrigin());
        AccountState slim = library.changeSkinVariant(accountId, originalId, SkinVariant.SLIM);
        assertEquals(Optional.of(ORIGIN), library.findSkin(slim, originalId).catalogOrigin());
        AccountState duplicated = library.duplicateSkin(accountId, originalId, "Steve copy");
        SkinAsset copy = duplicated.skinAssets().stream()
                .filter(asset -> !asset.id().equals(originalId))
                .findFirst()
                .orElseThrow();

        assertNotEquals(originalId, copy.id());
        assertEquals(SkinSource.DUPLICATED, copy.source());
        assertEquals(Optional.of(ORIGIN), copy.catalogOrigin());

        LibraryService reopened = library();
        AccountState persisted = reopened.load(accountId);
        assertEquals(Optional.of(ORIGIN), reopened.findSkin(persisted, originalId).catalogOrigin());
        assertEquals(Optional.of(ORIGIN), reopened.findSkin(persisted, copy.id()).catalogOrigin());
        String json = Files.readString(
                storage().layout().accountState(accountId), StandardCharsets.UTF_8);
        assertTrue(json.contains("\"catalogOrigin\""));
        assertTrue(json.contains("\"sourceId\": \"vanilla\""));
        assertTrue(json.contains("\"description\": \"The original standard skin\""));
        assertTrue(json.contains("\"authors\": \"Mojang Studios\""));
    }

    @Test
    void oldImportApiAndOldJsonRemainOriginFreeAndReadable() throws Exception {
        LibraryService library = library();
        UUID importedAccountId = UUID.randomUUID();
        ImportedSkin imported = library.importSkin(
                importedAccountId,
                "Local",
                SkinVariant.CLASSIC,
                SkinSource.IMPORTED,
                TestPng.create(64, 64));
        assertTrue(imported.asset().catalogOrigin().isEmpty());

        NclSkinsStorage storage = storage();
        storage.initialize();
        UUID legacyAccountId = UUID.randomUUID();
        UUID assetId = UUID.randomUUID();
        Path statePath = storage.layout().accountState(legacyAccountId);
        Files.createDirectories(statePath.getParent());
        Files.writeString(statePath, """
                {
                  "schemaVersion": 1,
                  "accountId": "%s",
                  "updatedAt": "%s",
                  "skinAssets": [{
                    "id": "%s",
                    "name": "Legacy",
                    "sha256": "%s",
                    "variant": "CLASSIC",
                    "source": "IMPORTED",
                    "createdAt": "%s",
                    "updatedAt": "%s"
                  }],
                  "presets": []
                }
                """.formatted(legacyAccountId, NOW, assetId, "a".repeat(64), NOW, NOW),
                StandardCharsets.UTF_8);

        AccountState legacy = storage.loadOrCreateAccount(legacyAccountId);
        assertEquals(assetId, legacy.skinAssets().get(0).id());
        assertTrue(legacy.skinAssets().get(0).catalogOrigin().isEmpty());
    }

    @Test
    void managedSnapshotDeduplicationKeepsDistinctCatalogOrigin() throws Exception {
        LibraryService library = library();
        UUID accountId = UUID.randomUUID();
        byte[] png = TestPng.create(64, 64);

        ImportedSkin bootstrap = library.importSkin(
                accountId,
                "Steve bootstrap",
                SkinVariant.CLASSIC,
                SkinSource.VANILLA_DEFAULT,
                png);
        ImportedSkin catalog = library.importSkin(
                accountId,
                "Steve catalog",
                SkinVariant.CLASSIC,
                SkinSource.VANILLA_DEFAULT,
                png,
                Optional.of(ORIGIN));
        ImportedSkin repeatedCatalog = library.importSkin(
                accountId,
                "Steve catalog again",
                SkinVariant.CLASSIC,
                SkinSource.VANILLA_DEFAULT,
                png,
                Optional.of(ORIGIN));

        assertNotEquals(bootstrap.asset().id(), catalog.asset().id());
        assertEquals(catalog.asset().id(), repeatedCatalog.asset().id());
        assertEquals(2, library.load(accountId).skinAssets().size());
    }

    @Test
    void catalogOriginRejectsBlankStableIds() {
        assertThrows(IllegalArgumentException.class, () -> new CatalogOrigin(" ", "minecraft", "steve"));
        assertFalse(ORIGIN.sourceId().isBlank());
    }

    private LibraryService library() {
        return new LibraryService(storage(), CLOCK);
    }

    private NclSkinsStorage storage() {
        return new NclSkinsStorage(
                temporaryDirectory.resolve("nclskins"),
                new PngValidator(),
                CLOCK);
    }
}

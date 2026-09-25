package com.naocraftlab.skins.core.storage;

import com.naocraftlab.skins.client.OuterLayerVisibility;
import com.naocraftlab.skins.core.model.AccountAppearanceState;
import com.naocraftlab.skins.core.model.AppearanceSyncStatus;
import com.naocraftlab.skins.core.model.OwnedCapeEntry;
import com.naocraftlab.skins.core.model.OwnedCapeInventory;
import com.naocraftlab.skins.core.model.RemoteAssetState;
import com.naocraftlab.skins.core.model.SkinReference;
import com.naocraftlab.skins.core.provider.AppearanceProviders;
import com.naocraftlab.skins.core.provider.BuiltinProvider;
import com.naocraftlab.skins.core.provider.ProviderCape;
import com.naocraftlab.skins.core.service.LibraryService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PersonalCapeStorageTest {
    @TempDir Path root;
    private final UUID account = new UUID(0, 1);

    @Test void importRenameRestartAndAccountIsolation() throws Exception {
        var storage = new NclSkinsStorage(root, new com.naocraftlab.skins.core.png.PngValidator(), java.time.Clock.systemUTC());
        storage.initialize();
        storage.loadOrCreateAccount(account);
        var entry = storage.importCape(account, "First", png());
        var duplicate = storage.importCape(account, "Other", png());
        assertEquals(entry, duplicate);
        storage.renameCape(account, entry.texture().entryId(), "Renamed");
        var reopened = new NclSkinsStorage(root, new com.naocraftlab.skins.core.png.PngValidator(), java.time.Clock.systemUTC());
        var loaded = reopened.loadOrCreateAccount(account);
        assertEquals(1, loaded.personalCapes().size());
        assertEquals("Renamed", loaded.personalCapes().get(0).name());
        assertFalse(loaded.personalCapes().get(0).texture().hasElytra());
        assertArrayEquals(storage.readCapeAsset(account, entry.texture().sha256()), reopened.readCapeAsset(account, entry.texture().sha256()));
        assertTrue(reopened.loadOrCreateAccount(new UUID(0, 2)).personalCapes().isEmpty());
    }

    @Test void optifineImportDeduplicatesPaddedPixelsAndSurvivesSourceRemovalAndRestart() throws Exception {
        var storage = new NclSkinsStorage(root, new com.naocraftlab.skins.core.png.PngValidator(), java.time.Clock.systemUTC());
        storage.initialize();
        storage.loadOrCreateAccount(account);
        var narrow = new BufferedImage(46, 22, BufferedImage.TYPE_INT_ARGB);
        narrow.setRGB(0, 0, 0x7f123456);
        narrow.setRGB(45, 21, 0xff123456);
        Path source = root.resolve("optifine.png");
        Files.write(source, png(narrow));
        var first = storage.importCape(account, "Original", Files.readAllBytes(source));
        var padded = new BufferedImage(64, 32, BufferedImage.TYPE_INT_ARGB);
        padded.setRGB(0, 0, 46, 22, narrow.getRGB(0, 0, 46, 22, null, 0, 46), 0, 46);
        var duplicate = storage.importCape(account, "Duplicate", png(padded));
        assertEquals(first, duplicate);
        assertEquals("Original", duplicate.name());
        assertTrue(first.texture().hasElytra());
        Files.delete(source);
        var reopened = new NclSkinsStorage(root, new com.naocraftlab.skins.core.png.PngValidator(), java.time.Clock.systemUTC());
        assertEquals(List.of(first), reopened.loadOrCreateAccount(account).personalCapes());
        var saved = ImageIO.read(new java.io.ByteArrayInputStream(reopened.readCapeAsset(account, first.texture().sha256())));
        assertEquals(64, saved.getWidth());
        assertEquals(32, saved.getHeight());
        assertEquals(0x7f123456, saved.getRGB(0, 0));
        assertEquals(0, saved.getRGB(46, 21));
        assertEquals(0, saved.getRGB(45, 22));
        assertTrue(reopened.loadOrCreateAccount(new UUID(0, 2)).personalCapes().isEmpty());
    }

    @Test void optifineElytraClassificationPersistsForBothPixelPatterns() throws Exception {
        var storage = new NclSkinsStorage(root, new com.naocraftlab.skins.core.png.PngValidator(), java.time.Clock.systemUTC());
        storage.initialize();
        storage.loadOrCreateAccount(account);
        var capeOnly = new BufferedImage(46, 22, BufferedImage.TYPE_INT_ARGB);
        capeOnly.setRGB(22, 0, 0xff123456);
        var withElytra = new BufferedImage(46, 22, BufferedImage.TYPE_INT_ARGB);
        withElytra.setRGB(22, 0, 0xff123456);
        withElytra.setRGB(24, 0, 0x01000000);
        var plain = storage.importCape(account, "Cape", png(capeOnly));
        var winged = storage.importCape(account, "Winged", png(withElytra));
        assertFalse(plain.texture().hasElytra());
        assertTrue(winged.texture().hasElytra());
        var reopened = new NclSkinsStorage(root, new com.naocraftlab.skins.core.png.PngValidator(), java.time.Clock.systemUTC());
        assertEquals(List.of(plain, winged), reopened.loadOrCreateAccount(account).personalCapes());
        assertFalse(reopened.loadOrCreateAccount(account).personalCapes().get(0).texture().hasElytra());
        assertTrue(reopened.loadOrCreateAccount(account).personalCapes().get(1).texture().hasElytra());
    }

    @Test void hdCapeKeepsPixelDetailOfflineIdentityAndElytraAfterRestart() throws Exception {
        var storage = new NclSkinsStorage(root, new com.naocraftlab.skins.core.png.PngValidator(), java.time.Clock.systemUTC());
        storage.initialize();
        storage.loadOrCreateAccount(account);
        var source = new BufferedImage(92, 44, BufferedImage.TYPE_INT_ARGB);
        source.setRGB(0, 0, 0x80112233);
        source.setRGB(1, 0, 0xff445566);
        source.setRGB(91, 43, 0x7f123456);
        source.setRGB(49, 1, 0x01000000);
        Path input = root.resolve("hd-optifine.png");
        Files.write(input, png(source));
        var first = storage.importCape(account, "Detailed", Files.readAllBytes(input));
        var padded = new BufferedImage(128, 64, BufferedImage.TYPE_INT_ARGB);
        padded.setRGB(0, 0, 92, 44, source.getRGB(0, 0, 92, 44, null, 0, 92), 0, 92);
        padded.setRGB(127, 63, 0x00123456);
        assertEquals(first, storage.importCape(account, "Duplicate", png(padded)));
        assertTrue(first.texture().hasElytra());
        var preset = new LibraryService(storage, java.time.Clock.systemUTC()).createPreset(
                account, "HD offline", SkinReference.accountDefault(), OuterLayerVisibility.allVisible(), null,
                first.texture()).presets().get(0);
        Files.delete(input);

        var reopened = new NclSkinsStorage(root, new com.naocraftlab.skins.core.png.PngValidator(), java.time.Clock.systemUTC());
        var loaded = reopened.loadOrCreateAccount(account);
        assertEquals(List.of(first), loaded.personalCapes());
        assertEquals(first.texture(), loaded.presets().stream()
                .filter(value -> value.id().equals(preset.id())).findFirst().orElseThrow().offlineCape());
        var saved = ImageIO.read(new java.io.ByteArrayInputStream(
                reopened.readCapeAsset(account, first.texture().sha256())));
        assertEquals(128, saved.getWidth());
        assertEquals(64, saved.getHeight());
        assertEquals(0x80112233, saved.getRGB(0, 0));
        assertEquals(0xff445566, saved.getRGB(1, 0));
        assertEquals(0x7f123456, saved.getRGB(91, 43));
        assertEquals(0x01000000, saved.getRGB(49, 1));
        assertEquals(0, saved.getRGB(92, 43));
        assertTrue(reopened.loadOrCreateAccount(new UUID(0, 2)).personalCapes().isEmpty());
    }

    @Test void deletionClearsDisabledLocalReferencesButPreservesRemotePendingAndPreventsAba() throws Exception {
        var storage = new NclSkinsStorage(root, new com.naocraftlab.skins.core.png.PngValidator(), java.time.Clock.systemUTC());
        storage.initialize();
        storage.loadOrCreateAccount(account);
        var library = new LibraryService(storage, java.time.Clock.systemUTC());
        var entry = storage.importCape(account, "First", png());
        Path capeAsset = storage.capeAssetPath(account, entry.texture().sha256());
        var state = library.createPreset(account, "A", SkinReference.accountDefault(), OuterLayerVisibility.allVisible(), "owned", entry.texture());
        UUID preset = state.presets().get(0).id();
        library.createPreset(account, "B", SkinReference.accountDefault(), OuterLayerVisibility.allVisible(), null, entry.texture());
        var local = new ProviderCape(entry.texture().entryId().toString(), entry.texture().sha256(), false);
        storage.updateAppearance(account, current -> new AccountAppearanceState(AccountAppearanceState.CURRENT_SCHEMA_VERSION,
                account, 1, preset, null, null, "owned", OuterLayerVisibility.allVisible(), AppearanceSyncStatus.PENDING,
                0, java.time.Instant.now(), current.providers().select(1, null, local, new ProviderCape("owned", null))
                .disable(AppearanceProviders.Component.CAPE, BuiltinProvider.OFFLINE)));
        var before = storage.loadAppearance(account).providers();
        storage.deleteCape(account, entry.texture().entryId());
        var after = storage.loadAppearance(account).providers();
        assertEquals(before.skin(), after.skin());
        assertEquals(before.cape().minecraft(), after.cape().minecraft());
        assertEquals(before.cape().desired(), after.cape().desired());
        assertEquals(before.cape().minecraftDelivery(), after.cape().minecraftDelivery());
        assertNull(after.cape().offlineDesired());
        assertNull(after.cape().offline().value());
        assertTrue(storage.loadOrCreateAccount(account).presets().stream().allMatch(value -> value.offlineCape() == null));
        assertTrue(Files.isRegularFile(capeAsset));
        assertTrue(storage.loadOrCreateAccount(account).personalCapes().isEmpty());
        var again = storage.importCape(account, "Again", png());
        assertNotEquals(entry.texture().entryId(), again.texture().entryId());
        library.updatePreset(account, preset, "Stale save", SkinReference.accountDefault(), OuterLayerVisibility.allVisible(), "owned", entry.texture());
        assertNull(library.findPreset(storage.loadOrCreateAccount(account), preset).offlineCape());
        assertEquals("owned", library.findPreset(storage.loadOrCreateAccount(account), preset).capeId());
    }

    @Test void duplicatePresetReferencesReuseOneCapeAcrossStorageReload() throws Exception {
        var storage = new NclSkinsStorage(root, new com.naocraftlab.skins.core.png.PngValidator(), java.time.Clock.systemUTC());
        storage.initialize();
        storage.loadOrCreateAccount(account);
        var library = new LibraryService(storage, java.time.Clock.systemUTC());
        var cape = storage.importCape(account, "Shared", png()).texture();
        var source = library.createPreset(
                account, "Source", SkinReference.accountDefault(), OuterLayerVisibility.allVisible(), null, cape);
        var duplicate = library.createPreset(
                account, "Duplicate", SkinReference.accountDefault(), OuterLayerVisibility.allVisible(), null, cape);

        var reopened = new NclSkinsStorage(root, new com.naocraftlab.skins.core.png.PngValidator(), java.time.Clock.systemUTC());
        var loaded = reopened.loadOrCreateAccount(account);
        assertNotEquals(source.presets().get(0).id(), duplicate.presets().get(1).id());
        assertEquals(cape, loaded.presets().stream()
                .filter(value -> value.name().equals("Source"))
                .findFirst().orElseThrow().offlineCape());
        assertEquals(cape, loaded.presets().stream()
                .filter(value -> value.name().equals("Duplicate"))
                .findFirst().orElseThrow().offlineCape());
        assertEquals(1, loaded.personalCapes().size());
    }

    @Test void concurrentImportsDeduplicateAcrossStorageInstances() throws Exception {
        var storage = new NclSkinsStorage(root, new com.naocraftlab.skins.core.png.PngValidator(), java.time.Clock.systemUTC()); storage.initialize(); storage.loadOrCreateAccount(account);
        var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            byte[] bytes = png();
            var a = executor.submit(() -> storage.importCape(account, "A", bytes));
            var b = executor.submit(() -> new NclSkinsStorage(root, new com.naocraftlab.skins.core.png.PngValidator(), java.time.Clock.systemUTC()).importCape(account, "B", bytes));
            assertEquals(a.get().texture(), b.get().texture());
            assertEquals(1, storage.loadOrCreateAccount(account).personalCapes().size());
        } finally { executor.shutdownNow(); }
    }

    @Test void conditionalDiscardRemovesOnlyUnreferencedCapeIndexEntries() throws Exception {
        var storage = new NclSkinsStorage(root, new com.naocraftlab.skins.core.png.PngValidator(), java.time.Clock.systemUTC());
        storage.initialize();
        storage.loadOrCreateAccount(account);
        var unused = storage.importCape(account, "Unused", png());

        assertTrue(storage.discardCapeIfUnreferenced(
                account, unused.texture().entryId()).personalCapes().isEmpty());
        assertTrue(Files.isRegularFile(storage.capeAssetPath(account, unused.texture().sha256())));

        var used = storage.importCape(account, "Used", png());
        var library = new LibraryService(storage, java.time.Clock.systemUTC());
        library.createPreset(account, "Preset", SkinReference.accountDefault(),
                OuterLayerVisibility.allVisible(), null, used.texture());

        assertEquals(1, storage.discardCapeIfUnreferenced(
                account, used.texture().entryId()).personalCapes().size());
    }

    @Test void deletionReplayAndBackupNeverRestoreAnEntry() throws Exception {
        var storage = new NclSkinsStorage(root, new com.naocraftlab.skins.core.png.PngValidator(), java.time.Clock.systemUTC());
        storage.initialize(); storage.loadOrCreateAccount(account);
        var entry = storage.importCape(account, "First", png());
        byte[] before = Files.readAllBytes(storage.layout().accountState(account));
        storage.deleteCape(account, entry.texture().entryId());
        byte[] after = Files.readAllBytes(storage.layout().accountState(account));
        byte[] appearance = Files.readAllBytes(storage.layout().accountAppearance(account));
        var marker = new com.google.gson.JsonObject();
        marker.add("account", com.google.gson.JsonParser.parseString(new String(after, java.nio.charset.StandardCharsets.UTF_8)));
        marker.add("appearance", com.google.gson.JsonParser.parseString(new String(appearance, java.nio.charset.StandardCharsets.UTF_8)));
        Path journal = storage.layout().accountState(account).getParent().resolve("cape-transaction.json");
        for (int completed = 0; completed < 3; completed++) {
            Files.write(storage.layout().accountState(account), completed >= 2 ? after : before);
            Files.write(storage.layout().accountBackup(account), before);
            if (completed == 0) Files.deleteIfExists(storage.layout().accountAppearance(account));
            Files.writeString(journal, marker.toString());
            assertTrue(storage.loadOrCreateAccount(account).personalCapes().isEmpty());
            assertFalse(Files.exists(journal));
            Files.writeString(storage.layout().accountState(account), "broken");
            assertTrue(storage.loadOrCreateAccount(account).personalCapes().isEmpty());
        }
        Files.writeString(journal, "{\"account\":{}}");
        assertThrows(StorageException.class, () -> storage.loadOrCreateAccount(account));
    }

    @Test void publishedSingleCapeReaderPreservesAvailableLocalCopyWithoutCreatingPersonalEntry() throws Exception {
        var storage = new NclSkinsStorage(root, new com.naocraftlab.skins.core.png.PngValidator(), java.time.Clock.systemUTC());
        storage.initialize(); storage.loadOrCreateAccount(account);
        var library = new LibraryService(storage, java.time.Clock.systemUTC());
        var saved = library.createPreset(account, "Published", SkinReference.accountDefault(), "owned");
        Path statePath = storage.layout().accountState(account);
        var published = com.google.gson.JsonParser.parseString(Files.readString(statePath)).getAsJsonObject();
        published.remove("personalCapes");
        Files.writeString(statePath, published.toString());
        String key = "c".repeat(64);
        Files.write(storage.layout().textureCache().resolve(key + ".png"), png());
        storage.saveOwnedCapes(new OwnedCapeInventory(OwnedCapeInventory.CURRENT_SCHEMA_VERSION, account,
                List.of(new OwnedCapeEntry("owned", "Owned", RemoteAssetState.ACTIVE, key)), java.time.Instant.now()));
        var restored = storage.loadOrCreateAccount(account);
        var preset = library.findPreset(restored, saved.presets().get(0).id());
        assertEquals("owned", preset.capeId());
        assertNotNull(preset.offlineCape());
        assertNull(preset.offlineCape().entryId());
        assertFalse(preset.offlineCape().hasElytra());
        assertTrue(restored.personalCapes().isEmpty());
        Files.delete(storage.layout().accountOwnedCapes(account));
        assertEquals(restored, storage.loadOrCreateAccount(account));
        assertTrue(Files.exists(storage.layout().textureCache().resolve(preset.offlineCape().sha256() + ".png")));
    }

    private static byte[] png() throws Exception {
        var image = new BufferedImage(64, 32, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(3, 5, 0xff123456);
        return png(image);
    }

    private static byte[] png(BufferedImage image) throws Exception {
        var out = new ByteArrayOutputStream(); ImageIO.write(image, "PNG", out); return out.toByteArray();
    }
}

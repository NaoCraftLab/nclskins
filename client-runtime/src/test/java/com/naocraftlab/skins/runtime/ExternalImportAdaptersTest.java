package com.naocraftlab.skins.runtime;

import com.naocraftlab.skins.client.SkinCatalogSource;
import com.naocraftlab.skins.core.importing.ExternalAppearanceRecord;
import com.naocraftlab.skins.core.importing.ExternalImportAdapter;
import com.naocraftlab.skins.core.importing.ExternalImportBatch;
import com.naocraftlab.skins.core.importing.ExternalImportContext;
import com.naocraftlab.skins.core.importing.ExternalImportSource;
import com.naocraftlab.skins.core.importing.SkinLocator;
import com.naocraftlab.skins.core.model.OwnedCapeEntry;
import com.naocraftlab.skins.core.model.OwnedCapeInventory;
import com.naocraftlab.skins.core.model.RemoteAssetState;
import com.naocraftlab.skins.core.model.SkinVariant;
import com.naocraftlab.skins.core.png.PngValidator;
import com.naocraftlab.skins.core.service.LibraryService;
import com.naocraftlab.skins.core.storage.NclSkinsStorage;
import com.naocraftlab.skins.core.storage.TextureCache;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ExternalImportAdaptersTest {
    private static final UUID ACCOUNT_ID = UUID.fromString("00000000-0000-0000-0000-000000000123");
    private static final Instant NOW = Instant.parse("2026-08-06T00:00:00Z");

    @Test
    void launcherPrefersCurrentEmbeddedLibrary(@TempDir Path root) throws Exception {
        byte[] png = skinPng(0xFF336699);
        String encoded = Base64.getEncoder().encodeToString(png);
        Files.writeString(root.resolve(MinecraftLauncherImportAdapter.CURRENT_FILE), """
                {"customSkins":{"one":{"name":"Current look","skinImage":"data:image/png;base64,%s","slim":true,"capeId":"cape-owned"}}}
                """.formatted(encoded));
        Files.writeString(root.resolve(MinecraftLauncherImportAdapter.LEGACY_FILE), "{} ");

        ExternalImportBatch batch = new MinecraftLauncherImportAdapter()
                .discover(root, context(root));
        assertEquals(1, batch.records().size());
        ExternalAppearanceRecord record = batch.records().get(0);
        assertEquals("Current look", record.displayName());
        assertEquals(Optional.of(SkinVariant.SLIM), record.declaredVariant());
        assertEquals(Optional.of("cape-owned"), record.externalCapeId());
        assertInstanceOf(SkinLocator.EmbeddedPng.class, record.skinLocator());
    }

    @Test
    void prismReadsConfiguredIndexAndUnindexedPng(@TempDir Path root) throws Exception {
        Path skins = Files.createDirectories(root.resolve("custom-skins"));
        Files.writeString(root.resolve("prismlauncher.cfg"), "SkinsDir=custom-skins\n");
        Files.write(skins.resolve("Indexed.png"), skinPng(0xFF224466));
        Files.write(skins.resolve("Loose.png"), skinPng(0xFF664422));
        Files.writeString(skins.resolve("index.json"), """
                {"skins":[{"name":"Indexed","model":"SLIM","capeId":"cape-owned","url":"https://example.com/ignored.png"}]}
                """);

        ExternalImportBatch batch = new PrismLauncherImportAdapter().discover(root, context(root));
        assertEquals(List.of("Indexed", "Loose"),
                batch.records().stream().map(ExternalAppearanceRecord::displayName).toList());
        assertEquals(Optional.of(SkinVariant.SLIM), batch.records().get(0).declaredVariant());
        assertEquals(Optional.of(SkinVariant.CLASSIC), batch.records().get(1).declaredVariant());
    }

    @Test
    void skinShuffleSupportsHistoricalAndCurrentSourceShapes(@TempDir Path game) throws Exception {
        Path data = Files.createDirectories(game.resolve("config/skinshuffle"));
        Files.createDirectories(data.resolve("skins/downloaded"));
        Files.write(data.resolve("skins/configured.png"), skinPng(0xFF557799));
        Files.writeString(data.resolve("presets.json"), """
                {"chosenPreset":0,"loadedPresets":[
                  {"name":"Configured","skin":{"type":"skinshuffle:config","skin_name":"configured","model":"slim"},"cape":{"provider":"legacy"}},
                  {"name":"File","skin":{"type":"skinshuffle:file","path":"local.png","model":"classic"}},
                  {"name":"URL","skin":{"type":"skinshuffle:url","url":"https://example.com/skin.png"}},
                  {"name":"User","skin":{"type":"skinshuffle:username","username":"Player"},"keybindId":2},
                  {"name":"UUID","skin":{"type":"skinshuffle:uuid","uuid":"00000000-0000-0000-0000-000000000001"}},
                  {"name":"Resource","skin":{"type":"skinshuffle:resource","texture":"minecraft:textures/entity/player/wide/steve.png","model":"wide"}}
                ]}
                """);

        ExternalImportBatch global = new SkinShuffleImportAdapter().discover(game, context(game));
        assertEquals(6, global.records().size());
        assertTrue(global.warnings().contains("unsupported_cape_provider"));
        assertInstanceOf(SkinLocator.LocalPng.class, global.records().get(0).skinLocator());
        assertInstanceOf(SkinLocator.PublicUrl.class, global.records().get(2).skinLocator());
        assertInstanceOf(SkinLocator.PublicPlayer.class, global.records().get(3).skinLocator());
        assertInstanceOf(SkinLocator.MinecraftResource.class, global.records().get(5).skinLocator());

        Files.writeString(data.resolve("config.json"), "{\"enableMultiAccountSupport\":true}");
        Files.writeString(data.resolve("presets-Player.json"), """
                {"loadedPresets":[{"name":"Account","skin":{"type":"skinshuffle:config","skin_name":"configured","model":"mystery"}}]}
                """);
        ExternalImportBatch account = new SkinShuffleImportAdapter().discover(game, context(game));
        assertEquals(List.of("Account"),
                account.records().stream().map(ExternalAppearanceRecord::displayName).toList());
        assertEquals(Optional.empty(), account.records().get(0).declaredVariant());
        assertTrue(account.warnings().contains("unknown_model"));
    }

    @Test
    void resolverConfirmsOwnedCapeAndBatchImportIsIdempotent(@TempDir Path root) throws Exception {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        NclSkinsStorage storage = new NclSkinsStorage(root.resolve("ncl"), new PngValidator(), clock);
        LibraryService library = new LibraryService(storage, clock);
        byte[] png = skinPng(0xFF315B72);
        Path localUrlCache = Files.write(root.resolve("downloaded.png"), png);
        ExternalImportAdapter adapter = new ExternalImportAdapter() {
            @Override
            public ExternalImportSource source() {
                return ExternalImportSource.MINECRAFT_LAUNCHER;
            }

            @Override
            public boolean probe(Path ignored, ExternalImportContext context) {
                return true;
            }

            @Override
            public ExternalImportBatch discover(Path ignored, ExternalImportContext context) {
                return new ExternalImportBatch(source(), List.of(
                        new ExternalAppearanceRecord(
                                "one", "Imported", Optional.of(SkinVariant.CLASSIC),
                                new SkinLocator.EmbeddedPng(png), Optional.of("cape-owned"), 0),
                        new ExternalAppearanceRecord(
                                "two", "Cached URL", Optional.empty(),
                                new SkinLocator.PublicUrl(
                                        "https://unreachable.invalid/skin.png",
                                        Optional.of(localUrlCache)),
                                Optional.empty(), 1),
                        new ExternalAppearanceRecord(
                                "three", "Same skin, another preset", Optional.of(SkinVariant.CLASSIC),
                                new SkinLocator.EmbeddedPng(png), Optional.empty(), 2)), List.of());
            }
        };
        PublicSkinImportService publicImports = new PublicSkinImportService(
                new TextureCache(storage), (collection, skin, model) -> png);
        SkinCatalogSource resources = (collection, skin, model) -> png;
        ExternalAppearanceImportService service = new ExternalAppearanceImportService(
                library, publicImports, resources, new PngValidator(), List.of(adapter));
        OwnedCapeInventory capes = new OwnedCapeInventory(
                OwnedCapeInventory.CURRENT_SCHEMA_VERSION,
                ACCOUNT_ID,
                List.of(new OwnedCapeEntry("cape-owned", "Owned", RemoteAssetState.ACTIVE, null)),
                NOW);

        ClientOperations.ExternalImportReview firstReview = service.prepareAppearances(
                ACCOUNT_ID, ExternalImportSource.MINECRAFT_LAUNCHER,
                Optional.of(root), context(root), capes);
        assertEquals(3, firstReview.candidates().size());
        assertTrue(firstReview.candidates().stream().noneMatch(
                ClientOperations.ExternalImportCandidate::duplicate));
        assertEquals(
                firstReview.candidates().get(0).sha256(),
                firstReview.candidates().get(2).sha256());
        ExternalAppearanceImportService.Result first = service.commitAppearances(
                ACCOUNT_ID, firstReview.candidates(), firstReview.skipped(), firstReview.warnings());
        assertEquals(3, first.imported());
        assertEquals("cape-owned", first.state().presets().get(0).capeId());
        ClientOperations.ExternalImportReview repeatedReview = service.prepareAppearances(
                ACCOUNT_ID, ExternalImportSource.MINECRAFT_LAUNCHER,
                Optional.of(root), context(root), capes);
        assertTrue(repeatedReview.candidates().stream().allMatch(
                ClientOperations.ExternalImportCandidate::duplicate));
        ExternalAppearanceImportService.Result repeated = service.commitAppearances(
                ACCOUNT_ID,
                repeatedReview.candidates(),
                repeatedReview.skipped(),
                repeatedReview.warnings());
        assertEquals(0, repeated.imported());
        assertEquals(3, repeated.alreadyPresent());
        assertEquals(first.state(), repeated.state());

        library.hidePersonalSkin(
                ACCOUNT_ID, first.state().personalSkins().get(0).sha256());
        ClientOperations.ExternalImportReview afterCatalogRemoval = service.prepareAppearances(
                ACCOUNT_ID, ExternalImportSource.MINECRAFT_LAUNCHER,
                Optional.of(root), context(root), capes);
        assertTrue(afterCatalogRemoval.candidates().stream().noneMatch(
                ClientOperations.ExternalImportCandidate::duplicate));
    }

    @Test
    void expectedRootsKeepSkinShuffleOnCurrentInstance(@TempDir Path home) {
        Path game = home.resolve("instance/.minecraft");
        ExternalImportContext context = new ExternalImportContext("Player", game);
        assertEquals(List.of(game.toAbsolutePath().normalize()),
                ExternalAppearanceImportService.expectedRoots(
                        ExternalImportSource.SKIN_SHUFFLE,
                        context,
                        "mac os x",
                        home,
                        Map.of()));
        assertEquals(
                home.resolve("Library/Application Support/minecraft"),
                ExternalAppearanceImportService.expectedRoots(
                        ExternalImportSource.MINECRAFT_LAUNCHER,
                        context,
                        "mac os x",
                        home,
                        Map.of()).get(0));
    }

    private static ExternalImportContext context(Path game) {
        return new ExternalImportContext("Player", game);
    }

    private static byte[] skinPng(int color) throws Exception {
        BufferedImage image = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setColor(new Color(color, true));
            graphics.fillRect(0, 0, 64, 64);
        } finally {
            graphics.dispose();
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(image, "png", output);
        return output.toByteArray();
    }
}

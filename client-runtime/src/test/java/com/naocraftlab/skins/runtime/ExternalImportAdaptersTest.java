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
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.CRC32;

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
    void modrinthReadsInitialSchemaForOnlyTheCurrentAccount(@TempDir Path root) throws Exception {
        Path database = root.resolve("app.db");
        byte[] classic = skinPng(0xFF102030);
        byte[] unknown = skinPng(0xFF304050);
        try (Connection connection = sqlite(database); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE custom_minecraft_skin_textures (texture_key TEXT PRIMARY KEY, texture PNG BLOB NOT NULL)");
            statement.execute("CREATE TABLE custom_minecraft_skins (minecraft_user_uuid TEXT NOT NULL, texture_key TEXT NOT NULL, variant TEXT NOT NULL, cape_id TEXT)");
            insertTexture(connection, "classic", classic);
            insertTexture(connection, "foreign", classic);
            insertTexture(connection, "unknown", unknown);
            insertModrinthSkin(connection, ACCOUNT_ID.toString(), "classic", "CLASSIC", "cape-owned", null);
            insertModrinthSkin(connection, UUID.randomUUID().toString(), "foreign", "SLIM", null, null);
            insertModrinthSkin(connection, ACCOUNT_ID.toString(), "unknown", "UNKNOWN", null, null);
        }
        byte[] before = sha256(Files.readAllBytes(database));

        ExternalImportBatch batch = new ModrinthAppImportAdapter().discover(database, context(root));

        assertEquals(List.of("Modrinth skin 1", "Modrinth skin 2"),
                batch.records().stream().map(ExternalAppearanceRecord::displayName).toList());
        assertEquals(Optional.of(SkinVariant.CLASSIC), batch.records().get(0).declaredVariant());
        assertEquals(Optional.of("cape-owned"), batch.records().get(0).externalCapeId());
        assertEquals(Optional.empty(), batch.records().get(1).declaredVariant());
        assertTrue(batch.warnings().contains("unknown_model"));
        assertTrue(MessageDigest.isEqual(before, sha256(Files.readAllBytes(database))));
    }

    @Test
    void modrinthUsesDisplayOrderWhenTheColumnExists(@TempDir Path root) throws Exception {
        Path database = root.resolve("app.db");
        try (Connection connection = sqlite(database); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE custom_minecraft_skin_textures (texture_key TEXT PRIMARY KEY, texture BLOB NOT NULL)");
            statement.execute("CREATE TABLE custom_minecraft_skins (minecraft_user_uuid TEXT NOT NULL, texture_key TEXT NOT NULL, variant TEXT NOT NULL, cape_id TEXT, display_order INTEGER NOT NULL DEFAULT 0)");
            insertTexture(connection, "later", skinPng(0xFF111111));
            insertTexture(connection, "first", skinPng(0xFF222222));
            insertModrinthSkin(connection, ACCOUNT_ID.toString(), "later", "CLASSIC", "later", 5);
            insertModrinthSkin(connection, ACCOUNT_ID.toString(), "first", "SLIM", "first", 0);
        }

        ExternalImportBatch batch = new ModrinthAppImportAdapter().discover(root, context(root));
        assertEquals(List.of(Optional.of("first"), Optional.of("later")),
                batch.records().stream().map(ExternalAppearanceRecord::externalCapeId).toList());
        assertEquals(Optional.of(SkinVariant.SLIM), batch.records().get(0).declaredVariant());
    }

    @Test
    void curseForgeSupportsPreVariantBlobAndUrlRows(@TempDir Path root) throws Exception {
        Path database = Files.createDirectories(root.resolve("agent/database")).resolve("app.db");
        try (Connection connection = sqlite(database); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE minecraft_custom_skins (Id TEXT, Uuid TEXT, Name TEXT NOT NULL DEFAULT '', Url TEXT NOT NULL DEFAULT '', Data BLOB, AddedAt INTEGER NOT NULL DEFAULT 0)");
            insertCurseForgeSkin(connection, "blob", compactUuid(ACCOUNT_ID), "Blob skin", "", skinPng(0xFF556677), 1, null);
            insertCurseForgeSkin(connection, "url", compactUuid(ACCOUNT_ID), "", "https://example.com/skin.png", null, 2, null);
            insertCurseForgeSkin(connection, "foreign", compactUuid(UUID.randomUUID()), "Foreign", "https://example.com/foreign.png", null, 3, null);
        }

        ExternalImportBatch batch = new CurseForgeAppImportAdapter().discover(root, context(root));

        assertEquals(List.of("CurseForge skin 1", "Blob skin"),
                batch.records().stream().map(ExternalAppearanceRecord::displayName).toList());
        assertTrue(batch.records().stream().allMatch(record ->
                record.declaredVariant().equals(Optional.of(SkinVariant.CLASSIC))));
        assertInstanceOf(SkinLocator.PublicUrl.class, batch.records().get(0).skinLocator());
        assertInstanceOf(SkinLocator.EmbeddedPng.class, batch.records().get(1).skinLocator());
        assertTrue(batch.records().stream().allMatch(record -> record.externalCapeId().isEmpty()));
    }

    @Test
    void curseForgePreservesCurrentVariantAndRejectsOversizedBlob(@TempDir Path root) throws Exception {
        Path database = root.resolve("app.db");
        try (Connection connection = sqlite(database); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE minecraft_custom_skins (Id TEXT, Uuid TEXT, Name TEXT, Url TEXT, Data BLOB, AddedAt INTEGER, Variant TEXT NOT NULL DEFAULT 'CLASSIC')");
            insertCurseForgeSkin(connection, "slim", compactUuid(ACCOUNT_ID), "Slim", "", skinPng(0xFF998877), 3, "SLIM");
            insertCurseForgeSkin(connection, "unknown", compactUuid(ACCOUNT_ID), "Unknown", "https://example.com/fallback.png", null, 2, "MYSTERY");
            insertCurseForgeSkin(connection, "large", compactUuid(ACCOUNT_ID), "Large", "https://example.com/not-used.png", new byte[PngValidator.DEFAULT_MAX_BYTES + 1], 1, "CLASSIC");
        }

        ExternalImportBatch batch = new CurseForgeAppImportAdapter().discover(database, context(root));

        assertEquals(2, batch.records().size());
        assertEquals(Optional.of(SkinVariant.SLIM), batch.records().get(0).declaredVariant());
        assertEquals(Optional.empty(), batch.records().get(1).declaredVariant());
        assertTrue(batch.warnings().contains("unknown_model"));
        assertTrue(batch.warnings().contains("invalid_skin_image"));
    }

    @Test
    void sqliteAdaptersKeepTheSharedRecordLimit(@TempDir Path root) throws Exception {
        Path database = root.resolve("app.db");
        try (Connection connection = sqlite(database); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE minecraft_custom_skins (Id TEXT, Uuid TEXT, Name TEXT, Url TEXT, Data BLOB, AddedAt INTEGER, Variant TEXT)");
            String uuid = compactUuid(ACCOUNT_ID);
            statement.execute("WITH RECURSIVE entries(value) AS (SELECT 1 UNION ALL SELECT value + 1 FROM entries WHERE value < 2049) "
                    + "INSERT INTO minecraft_custom_skins(Id, Uuid, Name, Url, Data, AddedAt, Variant) "
                    + "SELECT printf('id-%d', value), '" + uuid + "', printf('Skin %d', value), '', X'01', value, 'CLASSIC' FROM entries");
        }

        ExternalImportBatch batch = new CurseForgeAppImportAdapter().discover(database, context(root));
        assertEquals(ExternalImportFiles.MAX_RECORDS, batch.records().size());
        assertTrue(batch.warnings().contains("record_limit"));
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
    void curseForgeTreatsPixelEquivalentCatalogSkinAsDuplicate(@TempDir Path root)
            throws Exception {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        NclSkinsStorage storage = new NclSkinsStorage(
                root.resolve("ncl"), new PngValidator(), clock);
        LibraryService library = new LibraryService(storage, clock);
        byte[] catalogPng = skinPng(0xFF2468AC);
        byte[] curseForgePng = insertBeforeIend(
                catalogPng, "tEXt", "Source\0CurseForge".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1));
        AtomicReference<byte[]> discovered = new AtomicReference<>(catalogPng);
        ExternalImportAdapter adapter = new ExternalImportAdapter() {
            @Override
            public ExternalImportSource source() {
                return ExternalImportSource.CURSEFORGE_APP;
            }

            @Override
            public boolean probe(Path ignored, ExternalImportContext context) {
                return true;
            }

            @Override
            public ExternalImportBatch discover(Path ignored, ExternalImportContext context) {
                return new ExternalImportBatch(source(), List.of(new ExternalAppearanceRecord(
                        "curseforge-0",
                        "Existing pixels",
                        Optional.of(SkinVariant.CLASSIC),
                        new SkinLocator.EmbeddedPng(discovered.get()),
                        Optional.empty(),
                        0)), List.of());
            }
        };
        ExternalAppearanceImportService service = new ExternalAppearanceImportService(
                library,
                new PublicSkinImportService(
                        new TextureCache(storage), (collection, skin, model) -> catalogPng),
                (collection, skin, model) -> catalogPng,
                new PngValidator(),
                List.of(adapter));
        OwnedCapeInventory capes = new OwnedCapeInventory(
                OwnedCapeInventory.CURRENT_SCHEMA_VERSION, ACCOUNT_ID, List.of(), NOW);

        ClientOperations.ExternalImportReview first = service.prepareAppearances(
                ACCOUNT_ID, ExternalImportSource.CURSEFORGE_APP,
                Optional.of(root), context(root), capes);
        service.commitAppearances(ACCOUNT_ID, first.candidates(), 0, 0);
        discovered.set(curseForgePng);

        ClientOperations.ExternalImportCandidate repeated = service.prepareAppearances(
                        ACCOUNT_ID, ExternalImportSource.CURSEFORGE_APP,
                        Optional.of(root), context(root), capes)
                .candidates().get(0);
        assertTrue(repeated.duplicate());
        assertEquals(sha256Hex(catalogPng), repeated.sha256());
        assertTrue(MessageDigest.isEqual(catalogPng, repeated.normalizedPng()));
    }

    @Test
    void expectedRootsKeepSkinShuffleOnCurrentInstance(@TempDir Path home) {
        Path game = home.resolve("instance/.minecraft");
        ExternalImportContext context = new ExternalImportContext(ACCOUNT_ID, "Player", game);
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
        assertEquals(
                home.resolve("Library/Application Support/CurseForge"),
                ExternalAppearanceImportService.expectedRoots(
                        ExternalImportSource.CURSEFORGE_APP,
                        context,
                        "mac os x",
                        home,
                        Map.of()).get(0));
        assertEquals(
                home.resolve("Library/Application Support/ModrinthApp"),
                ExternalAppearanceImportService.expectedRoots(
                        ExternalImportSource.MODRINTH_APP,
                        context,
                        "mac os x",
                        home,
                        Map.of()).get(0));
    }

    private static ExternalImportContext context(Path game) {
        return new ExternalImportContext(ACCOUNT_ID, "Player", game);
    }

    private static Connection sqlite(Path database) throws Exception {
        Class.forName("org.sqlite.JDBC");
        return DriverManager.getConnection("jdbc:sqlite:" + database.toAbsolutePath());
    }

    private static void insertTexture(Connection connection, String key, byte[] png) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO custom_minecraft_skin_textures(texture_key, texture) VALUES (?, ?)")) {
            statement.setString(1, key);
            statement.setBytes(2, png);
            statement.executeUpdate();
        }
    }

    private static void insertModrinthSkin(
            Connection connection,
            String uuid,
            String texture,
            String variant,
            String cape,
            Integer order) throws Exception {
        String sql = order == null
                ? "INSERT INTO custom_minecraft_skins(minecraft_user_uuid, texture_key, variant, cape_id) VALUES (?, ?, ?, ?)"
                : "INSERT INTO custom_minecraft_skins(minecraft_user_uuid, texture_key, variant, cape_id, display_order) VALUES (?, ?, ?, ?, ?)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, uuid);
            statement.setString(2, texture);
            statement.setString(3, variant);
            statement.setString(4, cape);
            if (order != null) {
                statement.setInt(5, order);
            }
            statement.executeUpdate();
        }
    }

    private static void insertCurseForgeSkin(
            Connection connection,
            String id,
            String uuid,
            String name,
            String url,
            byte[] data,
            long addedAt,
            String variant) throws Exception {
        boolean current = variant != null;
        String sql = "INSERT INTO minecraft_custom_skins(Id, Uuid, Name, Url, Data, AddedAt"
                + (current ? ", Variant" : "") + ") VALUES (?, ?, ?, ?, ?, ?"
                + (current ? ", ?" : "") + ")";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, id);
            statement.setString(2, uuid);
            statement.setString(3, name);
            statement.setString(4, url);
            statement.setBytes(5, data);
            statement.setLong(6, addedAt);
            if (current) {
                statement.setString(7, variant);
            }
            statement.executeUpdate();
        }
    }

    private static String compactUuid(UUID uuid) {
        return uuid.toString().replace("-", "");
    }

    private static byte[] sha256(byte[] value) throws Exception {
        return MessageDigest.getInstance("SHA-256").digest(value);
    }

    private static String sha256Hex(byte[] value) throws Exception {
        return java.util.HexFormat.of().formatHex(sha256(value));
    }

    private static byte[] insertBeforeIend(byte[] png, String type, byte[] data) {
        int iendOffset = png.length - 12;
        byte[] typeBytes = type.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        CRC32 crc = new CRC32();
        crc.update(typeBytes);
        crc.update(data);
        ByteBuffer chunk = ByteBuffer.allocate(12 + data.length).order(ByteOrder.BIG_ENDIAN);
        chunk.putInt(data.length);
        chunk.put(typeBytes);
        chunk.put(data);
        chunk.putInt((int) crc.getValue());
        byte[] result = new byte[png.length + chunk.capacity()];
        System.arraycopy(png, 0, result, 0, iendOffset);
        System.arraycopy(chunk.array(), 0, result, iendOffset, chunk.capacity());
        System.arraycopy(png, iendOffset, result, iendOffset + chunk.capacity(), 12);
        return result;
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

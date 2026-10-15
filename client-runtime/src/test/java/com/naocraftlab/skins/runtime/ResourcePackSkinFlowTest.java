package com.naocraftlab.skins.runtime;

import com.google.gson.JsonParser;
import com.naocraftlab.skins.client.ClientExecutor;
import com.naocraftlab.skins.client.GameSessionTokenSource;
import com.naocraftlab.skins.client.PlayerAppearanceSink;
import com.naocraftlab.skins.client.ResourcePackCatalogDiscovery;
import com.naocraftlab.skins.client.ResourcePackSkinCatalog;
import com.naocraftlab.skins.client.SkinCatalogSource;
import com.naocraftlab.skins.client.SkinModel;
import com.naocraftlab.skins.core.api.ApiFailureKind;
import com.naocraftlab.skins.core.api.ProfileApi;
import com.naocraftlab.skins.core.api.ProfileApiException;
import com.naocraftlab.skins.core.model.AccountUiPreferences;
import com.naocraftlab.skins.core.model.AppearancePreset;
import com.naocraftlab.skins.core.model.RemoteProfile;
import com.naocraftlab.skins.core.model.SkinAsset;
import com.naocraftlab.skins.core.model.SkinVariant;
import com.naocraftlab.skins.core.png.PngValidator;
import com.naocraftlab.skins.core.storage.NclSkinsStorage;
import com.naocraftlab.skins.diagnostics.DiagnosticSinks;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.opentest4j.AssertionFailedError;

import javax.imageio.ImageIO;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ResourcePackSkinFlowTest {
    private static final String COLLECTION = "mojang_the_camp_out";
    private static final String SLIM_PATH = COLLECTION + ":textures/entity/player/slim/big_foot.png";
    private static final String CLASSIC_PATH = COLLECTION + ":textures/entity/player/wide/mushroom_forager.png";
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
    private static final Path PACK = Path.of("../compat/resources/mojang-collections/src/main/resources/resourcepacks/mojang_collections");
    private static final Map<String, List<String>> EXPECTED_NAMES = Map.of(
            "en_us", List.of("Big Foot", "Mushroom Forager"),
            "ru_ru", List.of("Снежный человек", "Грибник"),
            "de_de", List.of("Bigfoot", "Pilzsammler"),
            "pt_br", List.of("Pé-grande", "Coletor de cogumelos"),
            "es_mx", List.of("Pie Grande", "Recolector de hongos"),
            "es_es", List.of("Pie Grande", "Recolector de setas"));

    @TempDir
    Path temporaryDirectory;

    @Test
    void directOriginalPathsReachPreparedCatalogLocalizedSearchAndModelFilteredCards() throws Exception {
        var source = new DirectResources();
        assertEquals("72d5dcde3bb9fc775b4ed72911564b760c2865be383c8ded6d38e258b85efb39", sha(source.resources.get(SLIM_PATH)));
        assertEquals("d34d94e146925c8a79e319279df2b31407e853eaf475a4e51d044b093fbf0852", sha(source.resources.get(CLASSIC_PATH)));
        assertEquals("5f9e5b2b02931163585b1d8bff4a9ab98c2a542530a3a821867449c18ac25f3e", pixelHash(source.resources.get(SLIM_PATH)));
        assertEquals("4d1856307cab54563827178bf5c8dad554bdd7034a6d831fa1ab2b211332261b", pixelHash(source.resources.get(CLASSIC_PATH)));
        var operations = TestClientGraph.operations(tokens(new int[1]), new CountingApi(), storage(), source, CLOCK);
        var collections = operations.catalogCollections();
        assertInventory(collections);
        var collection = collections.stream().filter(value -> value.id().equals(COLLECTION)).findFirst().orElseThrow();
        var model = AddSourceModel.open(AccountUiPreferences.defaults(TestFixtures.ACCOUNT_ID), collections, SkinVariant.CLASSIC, text("ru_ru"));
        assertEquals(Set.of("big_foot", "mushroom_forager"), ids(model, collection));
        assertEquals(Set.of("big_foot"), ids(model.withQuery("снежный"), collection));
        assertEquals(Set.of("mushroom_forager"), ids(model.withQuery("гриб"), collection));
        assertTrue(ids(model.withQuery("big_foot"), collection).isEmpty());
        var classic = model.cycleFilter();
        var slim = classic.cycleFilter();
        assertEquals(AddSourceModel.CatalogFilter.CLASSIC, classic.filter());
        assertEquals(AddSourceModel.CatalogFilter.SLIM, slim.filter());
        assertEquals(Set.of("mushroom_forager"), ids(classic, collection));
        assertEquals(Set.of("big_foot"), ids(slim, collection));
        assertCards(classic, Set.of("mushroom_forager"));
        assertCards(slim, Set.of("big_foot"));
    }

    @Test
    void productionLocalesDriveExactNamedSearchForBothOriginals() throws Exception {
        var source = new DirectResources();
        var collections = TestClientGraph.operations(tokens(new int[1]), new CountingApi(), storage(), source, CLOCK).catalogCollections();
        assertInventory(collections);
        var collection = collections.stream().filter(value -> value.id().equals(COLLECTION)).findFirst().orElseThrow();
        var locales = JsonParser.parseString(Files.readString(Path.of("../gradle/targets.json")))
                .getAsJsonObject().getAsJsonObject("mod").getAsJsonObject("localization").getAsJsonArray("sourceLocales");
        for (var locale : locales) {
            String id = locale.getAsString();
            var model = AddSourceModel.open(AccountUiPreferences.defaults(TestFixtures.ACCOUNT_ID), collections, SkinVariant.CLASSIC, text(id));
            assertEquals(Set.of("big_foot"), ids(model.withQuery(EXPECTED_NAMES.get(id).get(0)), collection), id);
            assertEquals(Set.of("mushroom_forager"), ids(model.withQuery(EXPECTED_NAMES.get(id).get(1)), collection), id);
            assertCards(model.cycleFilter(), Set.of("mushroom_forager"));
            assertCards(model.cycleFilter().cycleFilter(), Set.of("big_foot"));
        }
    }

    @Test
    void characterizationOriginalsRemainByteEqualToProductionInputs() throws Exception {
        var production = new DirectResources();
        Path fixture = Path.of(ResourcePackSkinFlowTest.class.getResource("/catalog/camp-out/assets").toURI());
        var baseline = new DirectResources(fixture);
        assertInventory(baseline.collections());
        assertEquals(baseline.resources.keySet(), production.resources.keySet());
        baseline.resources.forEach((path, bytes) -> assertArrayEquals(bytes, production.resources.get(path)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"wrong-model-path", "extra-variant"})
    void exactInventoryOracleDetectsValidButUnapprovedModelPaths(String mutation) throws Exception {
        var source = new DirectResources();
        assertInventory(source.collections());
        String wide = COLLECTION + ":textures/entity/player/wide/big_foot.png";
        source.resources.put(wide, source.resources.get(SLIM_PATH));
        if (mutation.equals("wrong-model-path")) source.resources.remove(SLIM_PATH);
        assertThrows(AssertionFailedError.class, () -> assertInventory(source.collections()));
    }

    @ParameterizedTest
    @CsvSource({"big_foot, SLIM, 5f9e5b2b02931163585b1d8bff4a9ab98c2a542530a3a821867449c18ac25f3e",
            "mushroom_forager, CLASSIC, 4d1856307cab54563827178bf5c8dad554bdd7034a6d831fa1ab2b211332261b"})
    void selectingPreviewAndCancellingDoNotMutateDurableStateOrAccount(
            String skinId, SkinVariant expected, String pixels) throws Exception {
        var source = new DirectResources();
        var stored = storage();
        var api = new CountingApi();
        int[] tokenRequests = new int[1];
        try (var runtime = runtime(stored, source, api, tokenRequests)) {
            runtime.warmSession();
            runtime.initialize();
            assertEquals(1, api.reads);
            assertEquals(1, tokenRequests[0]);
            var beforeAccount = stored.loadOrCreateAccount(TestFixtures.ACCOUNT_ID);
            var beforeAppearance = stored.loadAppearance(TestFixtures.ACCOUNT_ID);
            var beforeFiles = durableFiles(stored);
            var beforeNetwork = api.counters();
            int tokensBefore = tokenRequests[0];

            select(runtime, skinId, expected);
            var preview = runtime.view(1600, 900, 0, 0).previews().stream()
                    .filter(value -> value.id().equals("editor.preview")).findFirst().orElseThrow();
            assertEquals(expected, preview.variant());
            assertEquals(pixels, pixelHash(runtime.loadSkinPreview(preview).join().orElseThrow()));
            runtime.dispatchWidget("editor.cancel");
            assertTrue(runtime.snapshot().editor().isEmpty());
            assertTrue(runtime.snapshot().addSource().isPresent());
            runtime.dispatchWidget("add.cancel");

            assertEquals(beforeAccount, stored.loadOrCreateAccount(TestFixtures.ACCOUNT_ID));
            assertEquals(beforeAppearance, stored.loadAppearance(TestFixtures.ACCOUNT_ID));
            assertEquals(beforeFiles, durableFiles(stored));
            assertEquals(beforeNetwork, api.counters());
            assertEquals(tokensBefore, tokenRequests[0]);
        }
    }

    @ParameterizedTest
    @CsvSource({"big_foot, SLIM, 5f9e5b2b02931163585b1d8bff4a9ab98c2a542530a3a821867449c18ac25f3e",
            "mushroom_forager, CLASSIC, 4d1856307cab54563827178bf5c8dad554bdd7034a6d831fa1ab2b211332261b"})
    void inactiveSaveSurvivesPackRemovalAndNewRuntimeWithOriginalPixelsAndModel(
            String skinId, SkinVariant expected, String pixels) throws Exception {
        var source = new DirectResources();
        var stored = storage();
        var api = new CountingApi();
        int[] tokenRequests = new int[1];
        AppearancePreset savedPreset;
        SkinAsset savedAsset;
        try (var runtime = runtime(stored, source, api, tokenRequests)) {
            runtime.warmSession();
            runtime.initialize();
            var beforeAccount = stored.loadOrCreateAccount(TestFixtures.ACCOUNT_ID);
            var beforeAppearance = stored.loadAppearance(TestFixtures.ACCOUNT_ID);
            var beforeNetwork = api.counters();
            int beforeTokens = tokenRequests[0];
            select(runtime, skinId, expected);
            runtime.dispatchWidget("editor.save");
            assertTrue(runtime.snapshot().editor().isEmpty());
            var saved = stored.loadOrCreateAccount(TestFixtures.ACCOUNT_ID);
            assertEquals(beforeAccount.presets().size() + 1, saved.presets().size());
            assertEquals(beforeAccount.skinAssets().size() + 1, saved.skinAssets().size());
            savedPreset = saved.presets().get(0);
            savedAsset = saved.skinAssets().stream().filter(asset -> asset.id().equals(savedPreset.skin().assetId())).findFirst().orElseThrow();
            assertEquals(expected, savedAsset.variant());
            assertEquals(COLLECTION, savedAsset.catalogOrigin().orElseThrow().collectionId());
            assertEquals(skinId, savedAsset.catalogOrigin().orElseThrow().skinId());
            assertEquals(pixels, pixelHash(stored.readAsset(savedAsset.sha256())));
            assertEquals(beforeAppearance, stored.loadAppearance(TestFixtures.ACCOUNT_ID));
            assertTrue(runtime.snapshot().activePresetId().isEmpty());
            assertEquals(beforeNetwork, api.counters());
            assertEquals(beforeTokens, tokenRequests[0]);
            source.removePack();
            runtime.dispatchWidget("gallery.add");
            assertSourceAbsentWithSentinel(runtime.snapshot().addSource().orElseThrow());
        }

        var reopenedStorage = storage();
        var offlineApi = new CountingApi();
        offlineApi.offline = true;
        int[] offlineTokens = new int[1];
        try (var reopened = runtime(reopenedStorage, source, offlineApi, offlineTokens)) {
            reopened.warmSession();
            reopened.initialize();
            assertEquals(1, offlineApi.reads);
            assertEquals(1, offlineTokens[0]);
            assertEquals(com.naocraftlab.skins.core.service.SessionStatus.OFFLINE_OR_INVALID,
                    reopened.snapshot().session().orElseThrow().status());
            var startupNetwork = offlineApi.counters();
            int startupTokens = offlineTokens[0];
            var account = reopened.snapshot().account().orElseThrow();
            assertEquals(savedPreset, account.presets().get(0));
            assertEquals(savedAsset, account.skinAssets().stream().filter(asset -> asset.id().equals(savedAsset.id())).findFirst().orElseThrow());
            assertEquals(pixels, pixelHash(reopenedStorage.readAsset(savedAsset.sha256())));
            reopened.dispatchWidget("gallery.preset." + savedPreset.id() + ".edit");
            assertEquals(expected, reopened.snapshot().editor().orElseThrow().variant());
            var preview = reopened.view(1600, 900, 0, 0).previews().stream()
                    .filter(value -> value.id().equals("editor.preview")).findFirst().orElseThrow();
            assertEquals(expected, preview.variant());
            assertEquals(pixels, pixelHash(reopened.loadSkinPreview(preview).join().orElseThrow()));
            reopened.dispatchWidget("editor.cancel");
            reopened.dispatchWidget("gallery.add");
            assertSourceAbsentWithSentinel(reopened.snapshot().addSource().orElseThrow());
            assertEquals(startupNetwork, offlineApi.counters());
            assertEquals(startupTokens, offlineTokens[0]);
            assertEquals(0, source.removedSourceReads);
        }
    }

    @ParameterizedTest
    @CsvSource({"big_foot, SLIM, generation", "mushroom_forager, CLASSIC, generation",
            "big_foot, SLIM, source-bytes", "mushroom_forager, CLASSIC, source-bytes",
            "big_foot, SLIM, source-removed", "mushroom_forager, CLASSIC, source-removed"})
    void staleSelectionSaveRejectsWithoutPartialPresetAssetOrRemoteEffect(
            String skinId, SkinVariant expected, String changed) throws Exception {
        var source = new DirectResources();
        var stored = storage();
        var api = new CountingApi();
        int[] tokenRequests = new int[1];
        try (var runtime = runtime(stored, source, api, tokenRequests)) {
            runtime.warmSession();
            runtime.initialize();
            select(runtime, skinId, expected);
            var beforeAccount = stored.loadOrCreateAccount(TestFixtures.ACCOUNT_ID);
            var beforeAppearance = stored.loadAppearance(TestFixtures.ACCOUNT_ID);
            var beforeFiles = durableFiles(stored);
            var beforeNetwork = api.counters();
            int beforeTokens = tokenRequests[0];
            long beforeGeneration = source.generation();
            String path = expected == SkinVariant.SLIM ? SLIM_PATH : CLASSIC_PATH;
            byte[] original = source.resources.get(path);
            switch (changed) {
                case "generation" -> {
                    source.generation++;
                    assertArrayEquals(original, source.resources.get(path));
                }
                case "source-bytes" -> {
                    byte[] replacement = PreparedCatalogServiceTest.withTextChunk(original);
                    assertNotEquals(sha(original), sha(replacement));
                    assertEquals(pixelHash(original), pixelHash(replacement));
                    source.resources.put(path, replacement);
                    assertEquals(beforeGeneration, source.generation());
                }
                case "source-removed" -> {
                    source.resources.remove(path);
                    assertEquals(beforeGeneration, source.generation());
                }
                default -> throw new AssertionError(changed);
            }
            runtime.dispatchWidget("editor.save");
            assertTrue(runtime.snapshot().editor().isPresent());
            assertFalse(runtime.snapshot().editor().orElseThrow().busy());
            assertEquals("nclskins.error.save", runtime.snapshot().editor().orElseThrow().status().orElseThrow().key());
            assertEquals(beforeAccount, stored.loadOrCreateAccount(TestFixtures.ACCOUNT_ID));
            assertEquals(beforeAppearance, stored.loadAppearance(TestFixtures.ACCOUNT_ID));
            assertEquals(beforeFiles, durableFiles(stored));
            assertEquals(beforeNetwork, api.counters());
            assertEquals(beforeTokens, tokenRequests[0]);
        }
    }

    private static void assertSourceAbsentWithSentinel(AddSourceModel model) {
        assertFalse(model.collections().stream().anyMatch(collection -> collection.id().equals(COLLECTION)));
        assertTrue(model.collections().stream().anyMatch(collection -> collection.id().equals("sentinel")));
    }

    private static void select(ClientRuntime runtime, String skinId, SkinVariant expected) {
        runtime.dispatchWidget("gallery.add");
        runtime.dispatchWidget("add.tab.catalog");
        assertInventory(runtime.snapshot().addSource().orElseThrow().collections());
        runtime.dispatchWidget("add.catalog.skin:" + COLLECTION + ":" + skinId);
        var editor = runtime.snapshot().editor().orElseThrow();
        assertEquals(expected, editor.variant());
        assertEquals(Set.of(expected), editor.availableCatalogVariants());
        assertTrue(editor.saveRequest().frozenCatalogSelection().isPresent());
    }

    private static ClientRuntime runtime(NclSkinsStorage storage, DirectResources source, CountingApi api, int[] requests) throws Exception {
        byte[] defaultPng = PreparedCatalogServiceTest.png(64, 64, 0);
        SkinCatalogSource vanilla = new SkinCatalogSource() {
            @Override public byte[] load(String collection, String skin, SkinModel model) { return defaultPng.clone(); }
            @Override public long generation() { return 0; }
        };
        var graph = TestClientGraph.create(tokens(requests), api, storage,
                SkinCatalogSource.resourcePacksBeforeVanilla(source, vanilla), CLOCK);
        ClientExecutor client = new ClientExecutor() {
            @Override public boolean isClientThread() { return true; }
            @Override public void execute(Runnable action) { action.run(); }
        };
        graph.attachProviders(resolved -> PlayerAppearanceSink.ApplyResult.UPDATED, client);
        return new ClientRuntime(new DefaultRuntimeServices(), graph.operations(), client,
                () -> java.util.concurrent.CompletableFuture.completedFuture(Optional.empty()),
                Runnable::run, text("ru_ru"), Optional.empty(), DiagnosticSinks.discarding());
    }

    private static TextResolver text(String locale) throws Exception {
        var language = JsonParser.parseString(Files.readString(PACK.resolve("assets/nclskins/lang/" + locale + ".json"))).getAsJsonObject();
        return message -> language.has(message.key()) ? language.get(message.key()).getAsString() : message.key();
    }

    private static Map<String, String> durableFiles(NclSkinsStorage storage) throws Exception {
        Map<String, String> result = new LinkedHashMap<>();
        Path root = storage.layout().root();
        try (var paths = Files.walk(root)) {
            for (Path path : paths.filter(Files::isRegularFile).toList()) {
                String relative = root.relativize(path).toString().replace('\\', '/');
                if (relative.startsWith("assets/") || relative.endsWith("state-v1.json") || relative.endsWith("appearance-v1.json")) {
                    result.put(relative, sha(Files.readAllBytes(path)));
                }
            }
        }
        return result;
    }

    private NclSkinsStorage storage() {
        return new NclSkinsStorage(temporaryDirectory.resolve("data"), new PngValidator(), CLOCK);
    }

    private static Set<String> ids(AddSourceModel model, SkinCatalogSource.CollectionDescriptor collection) {
        return model.visibleSkins(collection).stream().map(SkinCatalogSource.SkinDescriptor::id).collect(Collectors.toSet());
    }

    private static void assertInventory(List<SkinCatalogSource.CollectionDescriptor> collections) {
        var collection = collections.stream().filter(value -> value.id().equals(COLLECTION)).findFirst().orElseThrow();
        assertEquals(Map.of("big_foot", List.of(SkinModel.SLIM), "mushroom_forager", List.of(SkinModel.CLASSIC)),
                collection.skins().stream().collect(Collectors.toMap(SkinCatalogSource.SkinDescriptor::id, SkinCatalogSource.SkinDescriptor::models)));
    }

    private static void assertCards(AddSourceModel model, Set<String> expected) {
        var view = new AddSourcePresenter().present(model, false, 1600, 900);
        assertEquals(expected, view.widgets().stream().map(ViewSpec.Widget::id)
                .filter(id -> id.startsWith("add.catalog.skin:" + COLLECTION + ":"))
                .map(id -> id.substring(("add.catalog.skin:" + COLLECTION + ":").length())).collect(Collectors.toSet()));
    }

    private static String sha(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static String pixelHash(byte[] bytes) throws Exception {
        var image = ImageIO.read(new ByteArrayInputStream(bytes));
        assertNotNull(image);
        assertEquals(64, image.getWidth());
        assertEquals(64, image.getHeight());
        byte[] rgba = new byte[64 * 64 * 4];
        int offset = 0;
        for (int y = 0; y < 64; y++) {
            for (int x = 0; x < 64; x++) {
                int argb = image.getRGB(x, y);
                rgba[offset++] = (byte) (argb >>> 16);
                rgba[offset++] = (byte) (argb >>> 8);
                rgba[offset++] = (byte) argb;
                rgba[offset++] = (byte) (argb >>> 24);
            }
        }
        return sha(rgba);
    }

    private static GameSessionTokenSource tokens(int[] requests) {
        return new GameSessionTokenSource() {
            @Override public SessionIdentity currentSession() { return new SessionIdentity(TestFixtures.ACCOUNT_ID, "Player"); }
            @Override public <T, E extends Exception> T withAccessToken(TokenRequest<T, E> request) throws E {
                requests[0]++;
                return request.execute("test-only-token");
            }
        };
    }

    private static final class CountingApi implements ProfileApi {
        private boolean offline;
        private int reads;
        private int uploads;
        private int resets;
        private int activations;
        private int deactivations;

        @Override public RemoteProfile getProfile(String accessToken) throws ProfileApiException {
            reads++;
            if (offline) throw new ProfileApiException(ApiFailureKind.NETWORK, "Test offline", null, null, false);
            return new RemoteProfile(TestFixtures.ACCOUNT_ID, "Player", List.of(), List.of(), Set.of());
        }
        @Override public void uploadSkin(String accessToken, SkinVariant variant, byte[] bytes) { uploads++; }
        @Override public void resetSkin(String accessToken) { resets++; }
        @Override public void activateCape(String accessToken, String capeId) { activations++; }
        @Override public void deactivateCape(String accessToken) { deactivations++; }

        private List<Integer> counters() { return List.of(reads, uploads, resets, activations, deactivations); }
    }

    private static final class DirectResources implements SkinCatalogSource {
        private final Map<String, byte[]> resources = new LinkedHashMap<>();
        private long generation = 1;
        private boolean removed;
        private int removedSourceReads;

        private DirectResources() throws Exception {
            this(PACK.resolve("assets"));
        }

        private DirectResources(Path root) throws Exception {
            assertTrue(Files.isRegularFile(root.resolve("mojang_the_camp_out/textures/entity/player/slim/big_foot.png")), "Approved Big Foot production input missing");
            assertTrue(Files.isRegularFile(root.resolve("mojang_the_camp_out/textures/entity/player/wide/mushroom_forager.png")), "Approved Mushroom Forager production input missing");
            try (var files = Files.walk(root)) {
                for (Path file : files.filter(Files::isRegularFile)
                        .filter(path -> path.getFileName().toString().endsWith(".png"))
                        .filter(path -> root.relativize(path).getName(0).toString().equals(COLLECTION)).toList()) {
                    Path relative = root.relativize(file);
                    resources.put(relative.getName(0) + ":" + relative.subpath(1, relative.getNameCount()).toString().replace('\\', '/'), Files.readAllBytes(file));
                }
            }
            resources.put("sentinel:textures/entity/player/wide/other.png", resources.get(CLASSIC_PATH).clone());
        }

        @Override public List<CollectionDescriptor> collections() {
            return ResourcePackSkinCatalog.build(resources.keySet().stream().flatMap(identifier -> {
                int separator = identifier.indexOf(':');
                return ResourcePackCatalogDiscovery.variant(identifier.substring(0, separator), identifier.substring(separator + 1), "test-pack", 0).stream();
            }).toList());
        }

        @Override public byte[] load(String collection, String skin, SkinModel model) throws IOException {
            if (removed && collection.equals(COLLECTION)) {
                removedSourceReads++;
                throw new AssertionError("Removed source must not be accessed");
            }
            byte[] bytes = resources.get(collection + ":" + ResourcePackCatalogDiscovery.texturePath(skin, model));
            if (bytes == null) throw new IOException("Resource absent");
            return bytes.clone();
        }

        @Override public long generation() { return generation; }

        private void removePack() {
            resources.keySet().removeIf(id -> id.startsWith(COLLECTION + ":"));
            generation++;
            removed = true;
        }
    }
}

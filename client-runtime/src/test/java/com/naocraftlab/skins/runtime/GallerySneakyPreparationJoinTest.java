package com.naocraftlab.skins.runtime;

import com.naocraftlab.skins.client.*;
import com.naocraftlab.skins.core.model.*;
import com.naocraftlab.skins.core.png.SneakyCapeDecoder;
import com.naocraftlab.skins.core.provider.*;
import com.naocraftlab.skins.core.service.LibraryService;
import com.naocraftlab.skins.core.storage.LibraryStorageAdapter;
import com.naocraftlab.skins.diagnostics.DiagnosticSinks;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.naocraftlab.skins.runtime.GalleryRuntimeSelectionTest.*;

final class GallerySneakyPreparationJoinTest {
    @TempDir Path directory;
    final byte[] b;
    final byte[] c;

    GallerySneakyPreparationJoinTest() throws Exception {
        b = SneakyPreviewPreparationTest.skin(0x7f123456);
        c = SneakyPreviewPreparationTest.skin(0xff654321);
    }

    GalleryRuntimeSelectionTest fixture() throws Exception {
        return fixture(b);
    }

    GalleryRuntimeSelectionTest fixture(byte[] bytes) throws Exception {
        var f = new GalleryRuntimeSelectionTest();
        f.directory = directory;
        f.layerController = Optional.of(new GalleryInspectLayersTest.Layers());
        f.setup();
        var adapter = new LibraryStorageAdapter(f.storage);
        var library = new LibraryService(adapter, adapter, GalleryApplyTest.CLOCK);
        var bs = library.importSkin(ACCOUNT, "B", SkinVariant.SLIM, SkinSource.IMPORTED, bytes).asset();
        var cs = library.importSkin(ACCOUNT, "C", SkinVariant.CLASSIC, SkinSource.IMPORTED, c).asset();
        f.storage.updateAccount(ACCOUNT, old -> new AccountState(old.schemaVersion(), ACCOUNT, old.skinAssets(),
                List.of(old.presets().get(0), new AppearancePreset(B, "B", SkinReference.asset(bs.id()), null,
                                OuterLayerVisibility.noneVisible(), GalleryApplyTest.NOW, GalleryApplyTest.NOW),
                        new AppearancePreset(new UUID(0,303), "C", SkinReference.asset(cs.id()), null,
                                GalleryApplyTest.NOW, GalleryApplyTest.NOW)), old.updatedAt()));
        f.operations.enableProvider(AppearanceProviders.Component.CAPE, BuiltinProvider.SNEAKY);
        f.runtime.closeScreen(); f.runtime.reopen(); f.drain();
        return f;
    }

    static ViewSpec.Preview candidate(GalleryRuntimeSelectionTest f) {
        return f.runtime.galleryPreviewCandidate().orElseThrow();
    }

    static ViewSpec.Widget sneak(GalleryRuntimeSelectionTest f) {
        return f.view().widget("gallery.inspect.SNEAKY").orElseThrow(() -> new AssertionError("Exact selected draft Sneaky card missing"));
    }

    static void reopen(GalleryRuntimeSelectionTest f) { f.runtime.closeScreen(); f.runtime.reopen(); f.drain(); }

    static void environment(GalleryRuntimeSelectionTest f, SkinExtensionEnvironmentSource.Snapshot value) {
        f.runtime.close();
        ClientExecutor client = new ClientExecutor() {
            public boolean isClientThread() { return !f.workerLane; }
            public void execute(Runnable action) { if (!f.workerLane || f.inline) action.run(); else f.clients.add(action); }
        };
        f.runtime = new ClientRuntime(new DefaultRuntimeServices(), f.operations, client,
                () -> java.util.concurrent.CompletableFuture.completedFuture(Optional.empty()),
                action -> { if (f.inline) action.run(); else f.workers.add(action); }, UiMessage::key,
                f.currentAppearance, Optional.empty(), f.layerController, Optional.empty(), DiagnosticSinks.discarding());
        f.runtime.useSkinExtensionEnvironmentSource(() -> value);
        f.runtime.initialize(); f.drain(); f.view();
    }

    @Test void cpmCapeEditUsesExistingGuardedGalleryRouteAndRepeatedActiveReveal() throws Exception {
        var f = fixture();
        try {
            f.operations.useCpmPresence(() -> true);
            f.operations.enableProvider(AppearanceProviders.Component.CAPE, BuiltinProvider.CPM);
            environment(f, new SkinExtensionEnvironmentSource.Snapshot(1, Map.of(), true));
            f.runtime.dispatchWidget("gallery.providers"); f.drain();
            f.runtime.dispatchWidget("providers.tab.CAPE"); f.drain();
            f.runtime.dispatchWidget("providers.back"); f.drain();
            var before = ScopedEditorSaveTest.inventory(directory);
            for (int attempt = 0; attempt < 2; attempt++) {
                f.runtime.dispatchWidget("gallery.providers", false, InteractionOrigin.POINTER);
                f.runtime.dispatchWidget("providers.tab.CAPE", false, InteractionOrigin.POINTER);
                f.drain();
                var edit = f.view().widget("providers.edit.CPM").orElseThrow(() -> new AssertionError("Visible CPM cape Edit missing"));
                assertTrue(edit.visible() && edit.enabled() && edit.activationEnabled(), edit + " busy=" + f.runtime.snapshot().busy());
                f.runtime.dispatchWidget(edit.id(), false, InteractionOrigin.POINTER);
                assertEquals("gallery", f.view().screenId());
                assertTrue(f.runtime.snapshot().editor().isEmpty());
                assertEquals(Optional.of(A), f.runtime.snapshot().selectedPresetId());
                assertTrue(f.view().widget(f.card(A)).isPresent());
                f.selectB();
            }
            assertEquals(before, ScopedEditorSaveTest.inventory(directory));
            assertEquals(Optional.of(B), f.runtime.snapshot().selectedPresetId());
            f.runtime.dispatchWidget("providers.edit.CPM", false, InteractionOrigin.POINTER);
            assertEquals("gallery", f.view().screenId());
            assertEquals(Optional.of(B), f.runtime.snapshot().selectedPresetId(), "Captured CPM Edit cannot act from a stale Gallery source");
            assertFalse(BuiltinProvider.CPM.canWrite(AppearanceProviders.Component.CAPE));
            f.runtime.dispatchWidget("gallery.providers");
            f.runtime.dispatchWidget("providers.tab.SKIN");
            f.runtime.dispatchWidget("providers.edit.CPM", false, InteractionOrigin.POINTER);
            assertEquals("providers", f.view().screenId());
            f.operations.disableProvider(AppearanceProviders.Component.CAPE, BuiltinProvider.OFFLINE);
            f.operations.disableProvider(AppearanceProviders.Component.CAPE, BuiltinProvider.MINECRAFT);
            reopen(f);
            f.runtime.dispatchWidget("providers.tab.CAPE");
            assertFalse(f.runtime.snapshot().providers().galleryAvailable());
            assertTrue(f.view().widget("providers.edit.CPM").isEmpty());
            f.runtime.dispatchWidget("providers.edit.CPM", false, InteractionOrigin.POINTER);
            assertEquals("providers", f.view().screenId());
        } finally { f.runtime.close(); }
    }

    @Test void admittedFavoriteCompletionPreservesLaterSameDraftMenuFocusAndScrollIntent() throws Exception {
        var f = fixture();
        try {
            String bp = "gallery.preset." + B;
            String cp = "gallery.preset." + new UUID(0,303);
            f.runtime.dispatchWidget(bp + ".more", false, InteractionOrigin.POINTER);
            var star = f.view().widget(bp + ".favorite").orElseThrow();
            assertTrue(star.visible() && star.enabled() && star.activationEnabled());
            var original = candidate(f);
            f.runtime.dispatchWidget(star.id(), false, InteractionOrigin.KEYBOARD);
            f.work();
            assertTrue(f.storage.loadOrCreateAccount(ACCOUNT).presets().stream().filter(p -> p.id().equals(B)).findFirst().orElseThrow().favorite());
            assertFalse(f.runtime.snapshot().account().orElseThrow().presets().stream().filter(p -> p.id().equals(B)).findFirst().orElseThrow().favorite());
            f.runtime.dispatchWidget(cp + ".more", false, InteractionOrigin.POINTER);
            assertTrue(f.view().widget(cp + ".edit").isPresent());
            var focus = f.view().focusRequest();
            var offset = f.view().scrollSurface("gallery.cards").orElseThrow().offsetPixels();
            f.drain();
            assertEquals(original, candidate(f));
            assertTrue(f.view().widget(cp + ".edit").isPresent(), "Old star completion must not close new same-draft menu");
            assertEquals(focus, f.view().focusRequest());
            assertEquals(offset, f.view().scrollSurface("gallery.cards").orElseThrow().offsetPixels());
            assertTrue(f.runtime.snapshot().account().orElseThrow().presets().stream().filter(p -> p.id().equals(B)).findFirst().orElseThrow().favorite());
        } finally { f.runtime.close(); }
    }

    @Test void laterSameDraftFocusAndFractionalScrollSurviveAdmittedFavoriteReadback() throws Exception {
        var f = fixture();
        try {
            f.storage.updateAccount(ACCOUNT, old -> {
                var presets = new ArrayList<>(old.presets());
                for (int i = 0; i < 40; i++) presets.add(new AppearancePreset(new UUID(0,500+i), "Extra " + i,
                        SkinReference.accountDefault(), null, GalleryApplyTest.NOW.minusSeconds(i+1), GalleryApplyTest.NOW));
                return new AccountState(old.schemaVersion(), ACCOUNT, old.skinAssets(), presets, old.updatedAt());
            });
            reopen(f);
            f.runtime.view(320,240,0,0);
            String bp = "gallery.preset." + B;
            f.runtime.dispatchWidget(bp + ".more", false, InteractionOrigin.POINTER);
            var star = f.runtime.view(320,240,0,0).widget(bp + ".favorite").orElseThrow();
            assertTrue(star.visible() && star.enabled() && star.activationEnabled());
            f.runtime.dispatchWidget(star.id(), false, InteractionOrigin.KEYBOARD);
            f.work();
            assertTrue(f.storage.loadOrCreateAccount(ACCOUNT).presets().stream().filter(p -> p.id().equals(B)).findFirst().orElseThrow().favorite());
            String cp = "gallery.preset." + new UUID(0,303);
            f.runtime.dispatchWidget(cp + ".more", false, InteractionOrigin.POINTER);
            assertTrue(f.runtime.dispatchNavigation(ViewSpec.NavigationCommand.TAB_FORWARD, "gallery.add"));
            var view = f.runtime.view(320,240,0,0);
            var focus = view.focusRequest().orElseThrow();
            assertEquals("gallery.search", focus.widgetId());
            assertTrue(view.scrollSurface("gallery.cards").orElseThrow().maximumPixels() > 20);
            f.runtime.nativeScrollPositionChanged("gallery.cards", 10.5);
            assertEquals(10.5, f.runtime.view(320,240,0,0).scrollSurface("gallery.cards").orElseThrow().offsetPixels());
            f.drain();
            var after = f.runtime.view(320,240,0,0);
            assertEquals(focus, after.focusRequest().orElseThrow());
            assertEquals(10.5, after.scrollSurface("gallery.cards").orElseThrow().offsetPixels());
            assertTrue(after.widget(cp + ".edit").isPresent());
            assertEquals(Optional.of(A), f.runtime.snapshot().selectedPresetId());
            assertTrue(f.runtime.snapshot().account().orElseThrow().presets().stream().filter(p -> p.id().equals(B)).findFirst().orElseThrow().favorite());
        } finally { f.runtime.close(); }
    }

    static List<String> choices(GalleryRuntimeSelectionTest f) {
        return f.view().widgets().stream().filter(w -> w.id().startsWith("gallery.inspect.")).map(ViewSpec.Widget::id).toList();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void confirmedSectionsPreserveNewEligibleMenuOrFallbackOnlyWhenTopologyRemovesIt(boolean bulk) throws Exception {
        var f = fixture();
        try {
            f.operations.setPresetFavorite(new LibraryEditorPort.PresetFavoriteUpdate(new LibraryEditorPort.GalleryScope(ACCOUNT,0,()->true), B, true));
            reopen(f);
            var control = f.view().widget(bulk ? "gallery.sections" : "gallery.favorites").orElseThrow();
            assertTrue(control.enabled() && control.activationEnabled());
            f.runtime.dispatchWidget(control.id(), false, InteractionOrigin.KEYBOARD);
            f.work();
            assertTrue(f.storage.loadUiPreferences(ACCOUNT).preferences().collapsedGallerySections().contains("gallery.favorites"));
            String cp = "gallery.preset." + new UUID(0,303);
            f.runtime.dispatchWidget(cp + ".more", false, InteractionOrigin.POINTER);
            assertTrue(f.view().widget(cp + ".edit").isPresent());
            var source = candidate(f);
            f.drain();
            assertEquals(source, candidate(f));
            assertEquals(!bulk, f.view().widget(cp + ".edit").isPresent());
            assertTrue(f.view().widget("gallery.favorites").isPresent());
            assertFalse(f.runtime.snapshot().busy());
        } finally { f.runtime.close(); }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void channelPriorityAndInspectUseOwnBWhileCardsRemainOwnCWithInlineAndQueuedWorkers(boolean inline) throws Exception {
        var f = fixture(); f.inline = inline;
        try {
            f.storage.updateAccount(ACCOUNT, old -> new AccountState(old.schemaVersion(), ACCOUNT, old.skinAssets(), old.presets().stream()
                    .map(p -> p.id().equals(B) ? new AppearancePreset(p.id(), p.name(), p.skin(), "official-b", p.outerLayerVisibility(), p.createdAt(), p.updatedAt()) : p).toList(), old.updatedAt()));
            f.storage.saveOwnedCapes(new OwnedCapeInventory(OwnedCapeInventory.CURRENT_SCHEMA_VERSION, ACCOUNT,
                    List.of(new OwnedCapeEntry("official-b", null, RemoteAssetState.ACTIVE, null, false)), GalleryApplyTest.NOW));
            reopen(f); f.selectB(); f.view(); f.drain();
            assertEquals(List.of("gallery.inspect.MINECRAFT", "gallery.inspect.SNEAKY"), choices(f));
            assertFalse(candidate(f).capeHasElytra(), "Selected B must use its actual owned cape descriptor");
            assertEquals(Optional.of("official-b"), candidate(f).capeId());
            var cardC = f.view().previews().stream().filter(p -> p.id().equals("gallery.preset." + new UUID(0,303) + ".preview")).findFirst().orElseThrow();
            assertTrue(cardC.capeId().isEmpty());
            var before = ScopedEditorSaveTest.inventory(directory);
            var providers = f.runtime.snapshot().providers();
            GalleryInspectLayersTest.ordinary(f);
            f.runtime.dispatchWidget("gallery.inspect.SNEAKY", false, InteractionOrigin.POINTER);
            assertArrayEquals(new SneakyCapeDecoder().decode(b).orElseThrow().bytes(), f.runtime.loadCapePreview(candidate(f).capeId().orElseThrow()).join().orElseThrow());
            GalleryInspectLayersTest.ordinary(f);
            f.runtime.dispatchWidget("gallery.inspect.MINECRAFT", false, InteractionOrigin.POINTER);
            assertEquals(Optional.of("official-b"), candidate(f).capeId());
            assertTrue(candidate(f).bodySource().orElseThrow().inspectionIdentity().isEmpty());
            assertEquals(cardC, f.view().previews().stream().filter(p -> p.id().equals(cardC.id())).findFirst().orElseThrow());
            assertEquals(providers, f.runtime.snapshot().providers());
            assertEquals(before, ScopedEditorSaveTest.inventory(directory));
        } finally { f.runtime.close(); }
    }

    @Test void higherPriorityNativeAccessoryStillWinsWithoutErasingSneakyInspection() throws Exception {
        var f = fixture();
        try {
            f.operations.useCpmPresence(() -> true);
            environment(f, new SkinExtensionEnvironmentSource.Snapshot(1, Map.of(), true));
            f.selectB(); f.view(); f.drain();
            assertTrue(choices(f).contains("gallery.inspect.SNEAKY"));
            var value = new PreviewAccessories(new UUID(0,51), 1, PreviewAccessories.Availability.CONFIRMED,
                    true, true, PreviewRenderer.LiveReason.AVAILABLE);
            var callback = f.runtime.observePreviewAccessories(candidate(f), () -> value);
            callback.accept(value);
            assertTrue(candidate(f).nativeAccessories(), "Higher CPM source must retain its native cape/elytra winner");
            GalleryInspectLayersTest.ordinary(f);
            f.runtime.dispatchWidget("gallery.inspect.SNEAKY", false, InteractionOrigin.POINTER);
            assertFalse(candidate(f).nativeAccessories());
            assertArrayEquals(new SneakyCapeDecoder().decode(b).orElseThrow().bytes(), f.runtime.loadCapePreview(candidate(f).capeId().orElseThrow()).join().orElseThrow());
        } finally { f.runtime.close(); }
    }

    static void change(GalleryRuntimeSelectionTest f, ClientOperations.DurableAppearance value) throws Exception {
        var method = ClientRuntime.class.getDeclaredMethod("acceptProviderChange", ClientOperations.DurableAppearance.class);
        method.setAccessible(true); method.invoke(f.runtime, value);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"absence", "transparent", "failure"})
    void aggregateAbsenceRequiresEveryApplicableSourceAndTransparentRemainsValue(String mode) throws Exception {
        var f = fixture(mode.equals("absence") ? GalleryPreviewSourceJoinTest.png(0xff234567)
                : mode.equals("transparent") ? SneakyPreviewPreparationTest.skin(0) : b);
        try {
            f.selectB(); f.view();
            assertTrue(choices(f).isEmpty(), "Pending Sneaky cannot mean No Cape");
            if (mode.equals("failure")) f.beforeOperation = name -> {
                if (name.equals("loadSkinPreview")) throw new IllegalStateException("Synthetic local read failure");
            };
            f.drain();
            assertEquals(mode.equals("absence") ? List.of("gallery.inspect.none")
                    : mode.equals("transparent") ? List.of("gallery.inspect.SNEAKY") : List.of(), choices(f));
            if (mode.equals("absence")) {
                var none = f.view().widget("gallery.inspect.none").orElseThrow();
                assertEquals("nclskins.editor.no_cape", none.label().key());
                assertTrue(f.view().iconDecorations().stream().anyMatch(i -> i.icon() == GuiIcon.APPEARANCE_CAPE_NONE));
            }
            var before = ScopedEditorSaveTest.inventory(directory);
            int queued = f.workers.size();
            for (int i = 0; i < 3; i++) { f.view(); f.runtime.tick(); }
            assertEquals(queued, f.workers.size(), "No timer retries for failed source");
            assertEquals(before, ScopedEditorSaveTest.inventory(directory));
            f.beforeOperation = ignored -> {};
            change(f, f.operations.disableProvider(AppearanceProviders.Component.CAPE, BuiltinProvider.SNEAKY));
            assertEquals(List.of("gallery.inspect.none"), choices(f));
            change(f, f.operations.disableProvider(AppearanceProviders.Component.CAPE, BuiltinProvider.MINECRAFT));
            assertEquals(List.of("gallery.inspect.none"), choices(f));
            change(f, f.operations.disableProvider(AppearanceProviders.Component.CAPE, BuiltinProvider.OFFLINE));
            assertTrue(choices(f).isEmpty(), "Empty applicable set never proves absence");
        } finally { f.runtime.close(); }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"FRESH_MOVES", "JUST_EXPRESSIONS"})
    void exactCandidateSurvivesEditorPreviewOnlyOverlapWithoutHidingWarning(String consumer) throws Exception {
        var f = fixture();
        try {
            environment(f, new SkinExtensionEnvironmentSource.Snapshot(1,
                    Map.of(SkinExtensionEnvironmentSource.Consumer.valueOf(consumer), SkinExtensionEnvironmentSource.State.ACTIVE)));
            var before = ScopedEditorSaveTest.inventory(directory);
            f.selectB();
            var loaded = f.runtime.loadSkinPreview(candidate(f)); f.drain();
            assertArrayEquals(b, loaded.join().orElseThrow());
            var preset = f.runtime.snapshot().account().orElseThrow().presets().stream().filter(p -> p.id().equals(B)).findFirst().orElseThrow();
            assertTrue(f.runtime.snapshot().compatibilityFor(preset).activeConflicts().contains(
                    com.naocraftlab.skins.core.compatibility.SkinConflictReason.valueOf("SNEAKY_" + consumer + "_OVERLAP")));
            assertTrue(f.view().widget("gallery.preview.compatibility").isPresent());
            assertTrue(sneak(f).activationEnabled());
            GalleryInspectLayersTest.ordinary(f);
            f.runtime.dispatchWidget(sneak(f).id(), false, InteractionOrigin.POINTER);
            assertEquals(Optional.of("SNEAKY"), candidate(f).bodySource().orElseThrow().inspectionIdentity());
            assertEquals(before, ScopedEditorSaveTest.inventory(directory));
        } finally { f.runtime.close(); }
    }

    @Test void retainedDisplayedAtlasSurvivesPendingCThenRetiresOnlyAfterOwnReplacementDisplay() throws Exception {
        var f = fixture();
        try {
            f.selectB(); f.view(); f.drain();
            GalleryInspectLayersTest.ordinary(f);
            var shown = GalleryInspectLayersTest.main(f);
            String oldKey = shown.capeId().orElseThrow();
            f.runtime.dispatchWidget(f.card(new UUID(0,303)), false, InteractionOrigin.POINTER);
            f.view();
            assertEquals(shown, GalleryInspectLayersTest.main(f));
            assertFalse(sneak(f).activationEnabled());
            var oldLoad = f.runtime.loadCapePreview(oldKey); f.drain();
            assertArrayEquals(new SneakyCapeDecoder().decode(b).orElseThrow().bytes(), oldLoad.join().orElseThrow());
            var desired = candidate(f);
            assertNotEquals(shown.bodySource(), desired.bodySource());
            var rejected = desired;
            f.runtime.dispatchWidget("gallery.inspect.SNEAKY", false, InteractionOrigin.POINTER);
            assertEquals(rejected, candidate(f));
            GalleryInspectLayersTest.ordinary(f);
            assertEquals(desired, GalleryInspectLayersTest.main(f));
            assertTrue(f.runtime.loadCapePreview(oldKey).join().isEmpty());
            assertArrayEquals(new SneakyCapeDecoder().decode(c).orElseThrow().bytes(),
                    f.runtime.loadCapePreview(desired.capeId().orElseThrow()).join().orElseThrow());
        } finally { f.runtime.close(); }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"resource", "world", "close", "account", "disable", "B-C-B"})
    void lateWorkerAndClientCompletionsCannotPublishAcrossSourceLeaseScope(String invalidation) throws Exception {
        var f = fixture();
        try {
            f.selectB(); f.view();
            f.work();
            assertFalse(f.clients.isEmpty());
            while (!f.clients.isEmpty()) f.clients.remove().run();
            assertFalse(f.workers.isEmpty(), "Real decoder worker is held separately from source publication");
            var decode = f.workers.remove();
            switch (invalidation) {
                case "resource" -> f.runtime.resourcesReloaded();
                case "world" -> f.runtime.importWorldChanged();
                case "close" -> { f.runtime.closeScreen(); f.runtime.reopen(); }
                case "account" -> {
                    f.identity.set(new GameSessionTokenSource.SessionIdentity(new UUID(0,999), "Other")); f.runtime.tick();
                    f.identity.set(new GameSessionTokenSource.SessionIdentity(ACCOUNT, "Fixture")); f.runtime.tick();
                }
                case "disable" -> change(f, f.operations.disableProvider(AppearanceProviders.Component.CAPE, BuiltinProvider.SNEAKY));
                case "B-C-B" -> { f.runtime.dispatchWidget(f.card(new UUID(0,303))); f.view(); f.selectB(); f.view(); }
                default -> throw new AssertionError(invalidation);
            }
            var before = ScopedEditorSaveTest.inventory(directory);
            f.workerLane = true;
            try { decode.run(); } finally { f.workerLane = false; }
            assertTrue(choices(f).stream().noneMatch(id -> id.equals("gallery.inspect.SNEAKY")), "Stale worker has no publication authority");
            f.drain();
            if (invalidation.equals("close")) f.selectB();
            f.view(); f.drain();
            if (!invalidation.equals("disable")) {
                assertTrue(sneak(f).enabled());
                assertArrayEquals(new SneakyCapeDecoder().decode(b).orElseThrow().bytes(),
                        f.runtime.loadCapePreview(candidate(f).capeId().orElseThrow()).join().orElseThrow());
            } else assertFalse(choices(f).contains("gallery.inspect.SNEAKY"));
            assertEquals(before, ScopedEditorSaveTest.inventory(directory));
        } finally { f.runtime.close(); }
    }

    @Test void selectedBFlowsThroughLocalDecodeCacheRelookupAndActualRequestWithoutWorldAOrWrites() throws Exception {
        var f = fixture();
        var aSkin = f.runtime.snapshot().account().orElseThrow().presets().stream().filter(p -> p.id().equals(new UUID(0,303))).findFirst().orElseThrow().skin();
        f.storage.updateAccount(ACCOUNT, old -> new AccountState(old.schemaVersion(), ACCOUNT, old.skinAssets(),
                old.presets().stream().map(p -> p.id().equals(A) ? new AppearancePreset(p.id(), p.name(), aSkin, null,
                        p.outerLayerVisibility(), p.createdAt(), p.updatedAt()) : p).toList(), old.updatedAt()));
        var worldCape = new SneakyCapeDecoder().decode(c).orElseThrow();
        f.currentAppearance = Optional.of(() -> new CurrentPlayerAppearanceSource.PlayerAppearance(
                new TextureRegistry.TextureHandle("world-A",64,64), SkinModel.CLASSIC,
                Optional.of(new TextureRegistry.TextureHandle("world-A-cape",64,32))));
        environment(f, new SkinExtensionEnvironmentSource.Snapshot(1, Map.of()));
        var aPreview = candidate(f);
        var aBytes = f.runtime.loadSkinPreview(aPreview); f.drain();
        assertArrayEquals(c, aBytes.join().orElseThrow());
        assertNotEquals(java.util.Arrays.hashCode(b), java.util.Arrays.hashCode(aBytes.join().orElseThrow()));
        assertFalse(java.util.Arrays.equals(worldCape.bytes(), new SneakyCapeDecoder().decode(b).orElseThrow().bytes()));
        var registry = new GalleryPreviewSourceJoinTest.Registry();
        try (var skins = new PreviewAssetCache<String>(registry, TextureRegistry.TextureKind.PLAYER_SKIN, DiagnosticSinks.discarding());
             var capes = new PreviewAssetCache<String>(registry, TextureRegistry.TextureKind.IMAGE, DiagnosticSinks.discarding())) {
            var before = ScopedEditorSaveTest.inventory(directory);
            var providers = f.runtime.snapshot().providers();
            long generation = f.runtime.snapshot().generation();
            int optionsWrites = ((GalleryInspectLayersTest.Layers) f.layerController.orElseThrow()).writes;
            f.selectB();
            f.drain();
            var card = sneak(f);
            assertTrue(card.visible() && card.enabled() && card.activationEnabled());
            assertEquals(card.bounds().width(), card.bounds().height());
            var expected = new SneakyCapeDecoder().decode(b).orElseThrow();
            var preview = candidate(f);
            assertEquals(SkinVariant.SLIM, preview.variant());
            assertEquals(Optional.of(B), preview.presetId());
            assertTrue(preview.capeId().orElseThrow().startsWith("sneaky:preview:gallery:"));
            assertTrue(f.view().widgets().stream().filter(w -> w.id().startsWith("gallery.inspect.")).allMatch(w -> w.id().equals(card.id())));
            String key = preview.capeId().orElseThrow();
            skins.request("B", () -> f.runtime.loadSkinPreview(preview), () -> fail("skin registration"));
            capes.request(key, () -> f.runtime.loadCapePreview(preview), () -> fail("cape registration"));
            f.drain();
            skins.request("B", () -> f.runtime.loadSkinPreview(preview), () -> fail("skin relookup"));
            capes.request(key, () -> f.runtime.loadCapePreview(preview), () -> fail("cape relookup"));
            var skin = skins.handle("B").orElseThrow();
            var cape = capes.handle(key).orElseThrow();
            assertArrayEquals(b, registry.bytes.get(skin.location()));
            assertArrayEquals(expected.bytes(), registry.bytes.get(cape.location()));
            var request = ViewHostPolicy.previewRequest(preview, Optional.of(new PreviewRenderer.PreviewAppearance(
                    skin, SkinModel.SLIM, Optional.of(cape), preview.capeMode(), preview.outerLayerVisibility(),
                    expected.hasElytra(), false, false, TextureUseValidity.INVALID)), Optional.empty()).orElseThrow();
            assertEquals(cape, request.appearance().cape().orElseThrow());
            assertEquals(OuterLayerVisibility.noneVisible(), request.appearance().outerLayerVisibility());
            GalleryInspectLayersTest.ordinary(f);
            f.runtime.dispatchWidget(card.id(), false, InteractionOrigin.POINTER);
            assertEquals(Optional.of("SNEAKY"), candidate(f).bodySource().orElseThrow().inspectionIdentity());
            assertEquals(Optional.of(key), candidate(f).capeId());
            assertEquals(Optional.of(A), f.runtime.snapshot().activePresetId());
            assertEquals(providers, f.runtime.snapshot().providers());
            assertEquals(generation, f.runtime.snapshot().generation());
            assertEquals(before, ScopedEditorSaveTest.inventory(directory));
            assertEquals(0, f.reconciliations.get());
            assertEquals(optionsWrites, ((GalleryInspectLayersTest.Layers) f.layerController.orElseThrow()).writes);
        } finally { f.runtime.close(); }
    }
}

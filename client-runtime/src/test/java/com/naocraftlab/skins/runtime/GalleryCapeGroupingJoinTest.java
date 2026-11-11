package com.naocraftlab.skins.runtime;

import com.naocraftlab.skins.client.PreviewRenderer;
import com.naocraftlab.skins.core.model.AccountState;
import com.naocraftlab.skins.core.png.SneakyCapeDecoder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

final class GalleryCapeGroupingJoinTest {
    @TempDir Path directory;

    private GalleryRuntimeSelectionTest fixture() throws Exception {
        return fixture(true);
    }

    private GalleryRuntimeSelectionTest fixture(boolean completeSneaky) throws Exception {
        return fixture(completeSneaky, false);
    }

    private GalleryRuntimeSelectionTest fixture(boolean completeSneaky, boolean prepared) throws Exception {
        return fixture(completeSneaky, prepared, true);
    }

    private GalleryRuntimeSelectionTest fixture(boolean completeSneaky, boolean prepared, boolean terminalComparisonFailure) throws Exception {
        var support = new GallerySneakyPreparationJoinTest();
        support.directory = directory;
        var f = support.fixture();
        var path = directory.resolve("fixture-cape.png");
        byte[] stitched = new SneakyCapeDecoder().decode(support.b).orElseThrow().bytes();
        var synthetic = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(stitched));
        synthetic.setRGB(60, 31, 0xffabcdef);
        javax.imageio.ImageIO.write(synthetic, "png", path.toFile());
        assertNotEquals(new com.naocraftlab.skins.core.png.PngValidator().projectCanonicalCape(stitched).renderSha256(),
                new com.naocraftlab.skins.core.png.PngValidator().projectCanonicalCape(Files.readAllBytes(path)).renderSha256());
        var adapter = new com.naocraftlab.skins.core.storage.LibraryStorageAdapter(f.storage);
        var library = new com.naocraftlab.skins.core.service.LibraryService(adapter, adapter, GalleryApplyTest.CLOCK);
        var cape = (prepared ? library.importPreparedCape(GalleryRuntimeSelectionTest.ACCOUNT, "Fixture",
                new com.naocraftlab.skins.core.png.CapeAssetDecoder().prepare(path, directory.resolve("fixture.frames")))
                : f.operations.importCape(GalleryRuntimeSelectionTest.ACCOUNT, path, "Fixture")).texture();
        f.storage.updateAccount(GalleryRuntimeSelectionTest.ACCOUNT, old -> new AccountState(old.schemaVersion(),
                old.accountId(), old.skinAssets(), old.personalSkins(), old.presets().stream().map(p ->
                p.id().equals(GalleryRuntimeSelectionTest.B) ? p.withOfflineCape(cape) : p).toList(),
                old.updatedAt(), old.personalCapes()));
        if (terminalComparisonFailure) f.beforeOperation = name -> {
            if (name.equals("loadCapePreviewResult")) throw new IllegalStateException("Synthetic comparison unavailable");
        };
        f.runtime.closeScreen();
        f.runtime.reopen();
        f.drain();
        f.selectB();
        if (completeSneaky) f.drain();
        f.beforeOperation = ignored -> {};
        return f;
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void retainedGuiSourceReconcilesIdentityAfterPresetRoundTrip(boolean prepared) throws Exception {
        var f = fixture(true, prepared);
        var cape = f.storage.loadOrCreateAccount(GalleryRuntimeSelectionTest.ACCOUNT).presets().stream()
                .filter(p -> p.id().equals(GalleryRuntimeSelectionTest.B)).findFirst().orElseThrow().offlineCape();
        f.storage.updateAccount(GalleryRuntimeSelectionTest.ACCOUNT, old -> new AccountState(old.schemaVersion(),
                old.accountId(), old.skinAssets(), old.personalSkins(), old.presets().stream().map(p ->
                p.id().equals(GalleryRuntimeSelectionTest.A) ? p.withOfflineCape(cape) : p).toList(),
                old.updatedAt(), old.personalCapes()));
        f.runtime.closeScreen();
        f.runtime.reopen();
        f.drain();
        f.selectB();
        f.drain();
        var registry = new com.naocraftlab.skins.client.TextureRegistry() {
            public TextureHandle register(TextureKind kind, String hash, Path path) { throw new UnsupportedOperationException(); }
            public TextureHandle register(TextureKind kind, String hash, byte[] bytes) { return new TextureHandle(hash, 64, 32); }
            public TextureHandle registerRgba(TextureKind kind, String hash, int width, int height,
                    byte[] bytes, CpuLease lease) { lease.close(); return new TextureHandle(hash, width, height); }
            public void release(TextureHandle handle) {}
            public void close() {}
        };
        try (var cache = new PreviewAssetCache<String>(registry, com.naocraftlab.skins.client.TextureRegistry.TextureKind.IMAGE,
                com.naocraftlab.skins.diagnostics.DiagnosticSinks.discarding())) {
            GalleryInspectLayersTest.ordinary(f);
            f.runtime.dispatchWidget("gallery.inspect.SNEAKY", false, InteractionOrigin.POINTER);
            GalleryInspectLayersTest.ordinary(f);
            var initial = f.view().backEquipmentPreviews().stream()
                    .filter(p -> p.id().equals("gallery.inspect.OFFLINE.preview")).findFirst().orElseThrow();
            String key = ViewHostPolicy.cardCapeKey(initial);
            var opens = new java.util.concurrent.atomic.AtomicInteger();
            var loads = new java.util.concurrent.atomic.AtomicInteger();
            f.beforeOperation = name -> {
                if (name.equals("openPreparedCapePreview")) opens.incrementAndGet();
                if (name.equals("loadCapePreviewResult")) loads.incrementAndGet();
            };
            syncGuiCapes(f, cache);
            f.drain();
            assertTrue(cache.handle(key).isPresent());
            assertTrue(f.view().widget("gallery.inspect.SNEAKY").isEmpty(), "Cold current-scope lookup must group equal sources");
            assertEquals(1, loads.get(), "Full metadata is prepared by its cache owner, never by the material opener");
            var retained = cache.handle(key).orElseThrow();
            f.runtime.dispatchWidget(f.card(GalleryRuntimeSelectionTest.A), false, InteractionOrigin.POINTER);
            f.drain();
            GalleryInspectLayersTest.ordinary(f);
            assertEquals(Optional.of(GalleryRuntimeSelectionTest.A), f.runtime.snapshot().selectedPresetId());
            assertTrue(syncGuiCapes(f, cache).contains(key), "A must genuinely retain the same Offline card asset");
            f.drain();
            assertEquals(retained, cache.handle(key).orElseThrow());
            f.selectB();
            f.drain();
            GalleryInspectLayersTest.ordinary(f);
            assertTrue(f.view().widget("gallery.inspect.SNEAKY").isEmpty(), "Warm B first projection must already be grouped");
            f.runtime.dispatchWidget("gallery.inspect.OFFLINE", false, InteractionOrigin.POINTER);
            GalleryInspectLayersTest.ordinary(f);
            var before = candidate(f);
            int opened = opens.get();
            int loaded = loads.get();
            var inventory = ScopedEditorSaveTest.inventory(directory);
            assertTrue(syncGuiCapes(f, cache).contains(key));
            f.drain();
            assertEquals(retained, cache.handle(key).orElseThrow());
            assertEquals(opened, opens.get(), "Retained prepared source must not reopen");
            assertEquals(loaded, loads.get(), "Retained static full metadata must not reload");
            assertTrue(f.view().widget("gallery.inspect.SNEAKY").isEmpty(), "Retained full source must reconcile into the current B scope");
            assertEquals(before, candidate(f));
            assertEquals(inventory, ScopedEditorSaveTest.inventory(directory));
            var publications = new java.util.concurrent.atomic.AtomicInteger();
            var subscription = f.runtime.subscribe(snapshot -> publications.incrementAndGet());
            int published = publications.get();
            for (int i = 0; i < 5; i++) syncGuiCapes(f, cache);
            assertEquals(published, publications.get(), "Idempotent current metadata cannot publish a repaint loop");
            subscription.close();
            assertTrue(f.workers.isEmpty(), "Metadata replay must not queue per-frame work");
            assertEquals(opened, opens.get());
            assertEquals(loaded, loads.get());
        } finally { f.runtime.close(); }
    }

    @Test
    void cachedPreparedIdentityCannotPublishForAnUnobservedForeignSession() throws Exception {
        var f = fixture(true, true);
        try {
            GalleryInspectLayersTest.ordinary(f);
            String offline = f.view().backEquipmentPreviews().stream()
                    .filter(p -> p.id().equals("gallery.inspect.OFFLINE.preview")).findFirst().orElseThrow().capeId();
            var opened = f.runtime.openPreparedCapePreview(offline, true);
            f.drain();
            try (var source = opened.join().orElseThrow()) {
                var publications = new java.util.concurrent.atomic.AtomicInteger();
                var subscription = f.runtime.subscribe(snapshot -> publications.incrementAndGet());
                int before = publications.get();
                f.identity.set(new com.naocraftlab.skins.client.GameSessionTokenSource.SessionIdentity(
                        new java.util.UUID(0, 909), "Other"));
                f.runtime.reconcileCapePreviewIdentity(offline, source.visualSha256());
                assertEquals(before, publications.get(), "Cheap current metadata lookup must reject a foreign session before a tick");
                f.identity.set(new com.naocraftlab.skins.client.GameSessionTokenSource.SessionIdentity(
                        GalleryRuntimeSelectionTest.ACCOUNT, "Fixture"));
                f.runtime.reconcileCapePreviewIdentity("unrelated-cape", source.visualSha256());
                assertEquals(before, publications.get(), "An unrelated asset is not a current Gallery candidate");
                subscription.close();
            }
        } finally { f.runtime.close(); }
    }

    private static java.util.Set<String> syncGuiCapes(GalleryRuntimeSelectionTest f, PreviewAssetCache<String> cache) {
        var view = f.view();
        var desired = new java.util.HashSet<String>();
        var previews = new ArrayList<>(view.previews());
        f.runtime.galleryPreviewCandidate().filter(p -> !previews.contains(p)).ifPresent(previews::add);
        for (var preview : previews) preview.capeId().ifPresent(capeId -> {
            String key = f.runtime.previewCapeSurfaceKey(preview);
            desired.add(key);
            cache.requestCape(key, capeId, () -> f.runtime.openPreparedCapePreview(capeId),
                    () -> f.runtime.loadCapePreview(preview), true, preview.capeHasElytra(),
                    f.runtime.previewCapeGlint(preview), () -> fail("main upload"));
        });
        for (var card : view.backEquipmentPreviews()) {
            String key = ViewHostPolicy.cardCapeKey(card);
            desired.add(key);
            cache.requestCape(key, card.capeId(), () -> f.runtime.openPreparedCapePreview(card.capeId(), true),
                    () -> f.runtime.loadCapePreview(card.capeId()),
                    ViewHostPolicy.capeCardHovered(view, card, 0, 0), () -> fail("card upload"));
            f.runtime.reconcileCapePreviewIdentity(card.capeId(), cache.capeVisualIdentity(key, card.capeId()));
        }
        assertTrue(view.iconDecorations().stream().noneMatch(icon -> icon.providerTexture().isPresent()));
        cache.retain(desired);
        return desired;
    }

    @Test void pendingComparisonDoesNotAccumulateRepaintCallbacks() throws Exception {
        var f = fixture(false, false, false);
        try {
            f.view();
            var assetsField = ClientRuntime.class.getDeclaredField("previewAssets");
            assetsField.setAccessible(true);
            var loader = assetsField.get(f.runtime);
            var requestsField = PreviewAssetLoader.class.getDeclaredField("capeInFlight");
            requestsField.setAccessible(true);
            var requests = (java.util.Map<?, ?>) requestsField.get(loader);
            var request = (java.util.concurrent.CompletableFuture<?>) requests.values().iterator().next();
            int callbacks = request.getNumberOfDependents();
            assertTrue(callbacks > 0);
            for (int i = 0; i < 10; i++) f.view();
            assertEquals(callbacks, request.getNumberOfDependents(), "One scope must attach one comparison continuation, not one per repaint");
        } finally { f.runtime.close(); }
    }

    @Test void coldComparisonStartsWithoutVisibleTextureWidgets() throws Exception {
        var f = fixture(false, false, false);
        try {
            assertTrue(f.view().widget("gallery.inspect.OFFLINE").isEmpty(), "Unverified cold card must not be visible");
            assertFalse(f.workers.isEmpty(), "Hidden card must still have an admitted producer");
            var before = candidate(f);
            assertTrue(before.capeId().isPresent(), "Hidden card must not remove the body source's own cape");
            Runnable comparison = f.workers.removeLast();
            f.drain();
            assertTrue(f.view().widget("gallery.inspect.OFFLINE").isEmpty());
            assertTrue(f.view().widget("gallery.inspect.SNEAKY").isPresent(), "Ready sibling must not wait for the held comparison");
            f.workerLane = true;
            try { comparison.run(); } finally { f.workerLane = false; }
            f.drain();
            assertTrue(f.view().widget("gallery.inspect.OFFLINE").isPresent());
            assertTrue(f.view().widget("gallery.inspect.SNEAKY").isEmpty(), "Full metadata groups without GUI callbacks");
            assertEquals(before, candidate(f), "Comparison preparation must not replace the body source");
        } finally { f.runtime.close(); }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"false,selection", "true,selection", "false,reopen", "true,reopen"})
    void firstSubscriberProjectionAfterWarmSelectionNeverContainsDuplicates(boolean prepared, String boundary) throws Exception {
        var f = fixture(true, prepared, false);
        try {
            String cape = f.storage.loadOrCreateAccount(GalleryRuntimeSelectionTest.ACCOUNT).presets().stream()
                    .filter(p -> p.id().equals(GalleryRuntimeSelectionTest.B)).findFirst().orElseThrow().offlineCape().sha256();
            var loaded = f.runtime.loadCapePreview("provider:cape:" + cape);
            f.drain();
            assertTrue(loaded.join().isPresent());
            f.runtime.dispatchWidget(f.card(GalleryRuntimeSelectionTest.A), false, InteractionOrigin.POINTER);
            f.drain();
            if (boundary.equals("reopen")) {
                var use = f.operations.usePreset(GalleryRuntimeSelectionTest.B);
                var accept = ClientRuntime.class.getDeclaredMethod("presetUsed", LibraryEditorPort.PresetUse.class, boolean.class);
                accept.setAccessible(true);
                accept.invoke(f.runtime, use, false);
                f.runtime.closeScreen();
            }
            var loads = new java.util.concurrent.atomic.AtomicInteger();
            f.beforeOperation = name -> { if (name.equals("loadCapePreviewResult")) loads.incrementAndGet(); };
            var projections = new ArrayList<ViewSpec>();
            var subscription = f.runtime.subscribe(snapshot -> {
                if (snapshot.lifecycle() == ClientSnapshot.Lifecycle.READY
                        && snapshot.selectedPresetId().filter(GalleryRuntimeSelectionTest.B::equals).isPresent()) projections.add(f.view());
            });
            if (boundary.equals("reopen")) f.runtime.reopen();
            else f.selectB();
            f.drain();
            subscription.close();
            assertFalse(projections.isEmpty());
            assertTrue(projections.stream().allMatch(view -> view.widget("gallery.inspect.OFFLINE").isEmpty()
                    || view.widget("gallery.inspect.SNEAKY").isEmpty()), "No subscriber may first receive visible duplicates");
            assertTrue(f.view().widget("gallery.inspect.OFFLINE").isPresent());
            assertTrue(f.view().widget("gallery.inspect.SNEAKY").isEmpty());
            assertEquals(0, loads.get(), "Warm projection must not schedule another comparison decode");
        } finally { f.runtime.close(); }
    }

    @Test void terminalComparisonFailureShowsSingletonWithoutRetryLoop() throws Exception {
        var f = fixture();
        try {
            assertTrue(f.view().widget("gallery.inspect.OFFLINE").isPresent());
            assertTrue(f.view().widget("gallery.inspect.SNEAKY").isPresent());
            assertTrue(f.view().widget("gallery.inspect.none").isEmpty());
            for (int i = 0; i < 5; i++) f.view();
            assertTrue(f.workers.isEmpty());
        } finally { f.runtime.close(); }
    }

    private static ViewSpec.Preview candidate(GalleryRuntimeSelectionTest f) {
        return f.runtime.galleryPreviewCandidate().orElseThrow();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void guiCacheGroupsStaticSourceAfterRawProviderLoad(boolean warm) throws Exception {
        var f = fixture();
        var registry = new com.naocraftlab.skins.client.TextureRegistry() {
            public TextureHandle register(TextureKind kind, String hash, Path path) { throw new UnsupportedOperationException(); }
            public TextureHandle register(TextureKind kind, String hash, byte[] bytes) { return new TextureHandle("fixture", 64, 32); }
            public void release(TextureHandle handle) {}
            public void close() {}
        };
        try (var cache = new PreviewAssetCache<String>(registry, com.naocraftlab.skins.client.TextureRegistry.TextureKind.IMAGE,
                com.naocraftlab.skins.diagnostics.DiagnosticSinks.discarding())) {
            GalleryInspectLayersTest.ordinary(f);
            f.runtime.dispatchWidget("gallery.inspect.SNEAKY", false, InteractionOrigin.POINTER);
            GalleryInspectLayersTest.ordinary(f);
            var before = candidate(f);
            assertEquals(Optional.of("SNEAKY"), before.bodySource().orElseThrow().inspectionIdentity());
            String cape = f.view().backEquipmentPreviews().stream()
                    .filter(p -> p.id().equals("gallery.inspect.OFFLINE.preview")).findFirst().orElseThrow().capeId();
            assertTrue(cape.startsWith("provider:cape:"));
            var texture = new ViewSpec.ProviderTexture(cape.substring("provider:cape:".length()), false, false);
            assertEquals(cape, texture.requestKey());
            if (warm) {
                var raw = f.runtime.loadProviderTexture(texture);
                f.drain();
                assertTrue(raw.join().isPresent());
            }
            assertTrue(f.view().widget("gallery.inspect.SNEAKY").isPresent());
            var inventory = ScopedEditorSaveTest.inventory(directory);
            cache.requestCape("card", cape, () -> f.runtime.openPreparedCapePreview(cape, true),
                    () -> f.runtime.loadCapePreview(cape), false, () -> fail("upload"));
            f.drain();
            assertTrue(cache.handle("card").isPresent());
            assertTrue(f.view().widget("gallery.inspect.SNEAKY").isEmpty(), "Equal full sources must group through the GUI cache");
            assertEquals(before, candidate(f));
            assertEquals(inventory, ScopedEditorSaveTest.inventory(directory));
        } finally { f.runtime.close(); }
    }

    @Test void earlierOrdinaryIdentityAndInspectionSurviveLaterSneakyCandidate() throws Exception {
        var f = fixture(false);
        try {
            assertTrue(f.view().widget("gallery.inspect.SNEAKY").isEmpty());
            String offline = candidate(f).capeId().orElseThrow();
            Runnable sneaky = f.workers.removeFirst();
            var loaded = f.runtime.loadCapePreview(offline);
            f.drain();
            assertTrue(loaded.join().isPresent());
            assertTrue(f.view().widget("gallery.inspect.OFFLINE").isPresent());
            GalleryInspectLayersTest.ordinary(f);
            f.runtime.dispatchWidget("gallery.inspect.OFFLINE", false, InteractionOrigin.POINTER);
            GalleryInspectLayersTest.ordinary(f);
            var before = candidate(f);
            assertEquals(Optional.of("OFFLINE"), before.bodySource().orElseThrow().inspectionIdentity());
            f.workers.addFirst(sneaky);
            f.drain();
            assertEquals(before, candidate(f), "A newly known unrelated candidate must not reset exact inspected member");
            assertTrue(f.view().widget("gallery.inspect.SNEAKY").isEmpty(), "Earlier canonical hash remains valid when the later member arrives");
            assertEquals(Optional.of("active"), f.view().widget("gallery.inspect.OFFLINE").orElseThrow().value());
        } finally { f.runtime.close(); }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void mergedKeyboardTargetRevealsRepresentativeWithoutActivation(boolean acknowledged) throws Exception {
        var f = fixture();
        try {
            GalleryInspectLayersTest.ordinary(f);
            var view = f.runtime.view(320, 240, 0, 0);
            var ordered = view.navigationNodes().stream().filter(ViewSpec.NavigationNode::enabled)
                    .filter(n -> n.tabOrder() >= 0).sorted(java.util.Comparator.comparingInt(ViewSpec.NavigationNode::tabOrder)).toList();
            int index = java.util.stream.IntStream.range(0, ordered.size())
                    .filter(i -> ordered.get(i).id().equals("gallery.inspect.SNEAKY")).findFirst().orElseThrow();
            assertTrue(f.runtime.dispatchNavigation(ViewSpec.NavigationCommand.TAB_FORWARD, ordered.get(index - 1).id()));
            var old = f.runtime.view(320, 240, 0, 0).focusRequest().orElseThrow();
            assertEquals("gallery.inspect.SNEAKY", old.widgetId());
            if (acknowledged) f.runtime.acknowledgeFocusApplied("gallery", old);
            var before = candidate(f);
            var inventory = ScopedEditorSaveTest.inventory(directory);
            String offline = view.backEquipmentPreviews().stream().filter(p -> p.id().equals("gallery.inspect.OFFLINE.preview"))
                    .findFirst().orElseThrow().capeId();
            var loaded = f.runtime.loadCapePreview(offline);
            f.drain();
            assertTrue(loaded.join().isPresent());
            var after = f.runtime.view(320, 240, 0, 0);
            var request = after.focusRequest().orElseThrow();
            assertEquals("gallery.inspect.OFFLINE", request.widgetId());
            assertNotEquals(old.token(), request.token());
            f.runtime.acknowledgeFocusApplied("gallery", old);
            assertEquals(request, f.runtime.view(320, 240, 0, 0).focusRequest().orElseThrow());
            var target = after.navigationNode(request.widgetId()).orElseThrow();
            var viewport = after.scrollSurface("gallery.stage").orElseThrow().viewport();
            assertTrue(target.bounds().y() >= viewport.y());
            assertTrue(target.bounds().bottom() <= viewport.bottom());
            f.runtime.acknowledgeFocusApplied("gallery", request);
            assertTrue(f.runtime.view(320, 240, 0, 0).focusRequest().isEmpty());
            assertEquals(before, candidate(f));
            assertEquals(inventory, ScopedEditorSaveTest.inventory(directory));
        } finally { f.runtime.close(); }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void pointerFocusRetiresCapeFocusBeforeMerge(boolean acknowledged) throws Exception {
        var f = fixture();
        try {
            GalleryInspectLayersTest.ordinary(f);
            var view = f.runtime.view(320, 240, 0, 0);
            var ordered = view.navigationNodes().stream().filter(ViewSpec.NavigationNode::enabled)
                    .filter(n -> n.tabOrder() >= 0).sorted(java.util.Comparator.comparingInt(ViewSpec.NavigationNode::tabOrder)).toList();
            int index = java.util.stream.IntStream.range(0, ordered.size())
                    .filter(i -> ordered.get(i).id().equals("gallery.inspect.SNEAKY")).findFirst().orElseThrow();
            assertTrue(f.runtime.dispatchNavigation(ViewSpec.NavigationCommand.TAB_FORWARD, ordered.get(index - 1).id()));
            var old = f.runtime.view(320, 240, 0, 0).focusRequest().orElseThrow();
            assertEquals("gallery.inspect.SNEAKY", old.widgetId());
            if (acknowledged) f.runtime.acknowledgeFocusApplied("gallery", old);
            String offline = view.backEquipmentPreviews().stream().filter(p -> p.id().equals("gallery.inspect.OFFLINE.preview"))
                    .findFirst().orElseThrow().capeId();
            var loaded = f.runtime.loadCapePreview(offline);
            assertFalse(loaded.isDone());
            f.runtime.pointerFocusChanged();
            f.runtime.acknowledgeFocusApplied("gallery", old);
            f.drain();
            assertTrue(loaded.join().isPresent());
            var after = f.runtime.view(320, 240, 0, 0);
            assertTrue(after.widget("gallery.inspect.SNEAKY").isEmpty());
            assertTrue(after.focusRequest().isEmpty());
        } finally { f.runtime.close(); }
    }

    @Test void unrelatedKeyboardFocusSurvivesCapeMerge() throws Exception {
        var f = fixture();
        try {
            GalleryInspectLayersTest.ordinary(f);
            var view = f.runtime.view(320, 240, 0, 0);
            var ordered = view.navigationNodes().stream().filter(ViewSpec.NavigationNode::enabled)
                    .filter(n -> n.tabOrder() >= 0).sorted(java.util.Comparator.comparingInt(ViewSpec.NavigationNode::tabOrder)).toList();
            int index = java.util.stream.IntStream.range(0, ordered.size())
                    .filter(i -> ordered.get(i).id().equals("gallery.inspect.SNEAKY")).findFirst().orElseThrow();
            assertTrue(f.runtime.dispatchNavigation(ViewSpec.NavigationCommand.TAB_FORWARD, ordered.get(index - 1).id()));
            assertTrue(f.runtime.dispatchNavigation(ViewSpec.NavigationCommand.TAB_FORWARD, "gallery.inspect.SNEAKY"));
            var unrelated = f.runtime.view(320, 240, 0, 0).focusRequest().orElseThrow();
            assertFalse(unrelated.widgetId().startsWith("gallery.inspect."));
            String offline = view.backEquipmentPreviews().stream().filter(p -> p.id().equals("gallery.inspect.OFFLINE.preview"))
                    .findFirst().orElseThrow().capeId();
            var loaded = f.runtime.loadCapePreview(offline);
            f.drain();
            assertTrue(loaded.join().isPresent());
            var after = f.runtime.view(320, 240, 0, 0);
            assertTrue(after.widget("gallery.inspect.SNEAKY").isEmpty());
            assertEquals(unrelated, after.focusRequest().orElseThrow());
        } finally { f.runtime.close(); }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"selection", "reopen", "resources", "world", "account"})
    void staleIdentityCompletionCannotRegroupNewScope(String boundary) throws Exception {
        var f = fixture();
        try {
            String offline = candidate(f).capeId().orElseThrow();
            var loaded = f.runtime.loadCapePreview(offline);
            while (f.clients.isEmpty()) f.work();
            assertFalse(f.clients.isEmpty());
            assertFalse(loaded.isDone());
            switch (boundary) {
                case "selection" -> f.runtime.dispatchWidget(f.card(new java.util.UUID(0, 303)), false, InteractionOrigin.POINTER);
                case "reopen" -> { f.runtime.closeScreen(); f.runtime.reopen(); }
                case "resources" -> f.runtime.resourcesReloaded();
                case "world" -> f.runtime.importWorldChanged();
                case "account" -> f.identity.set(new com.naocraftlab.skins.client.GameSessionTokenSource.SessionIdentity(
                        new java.util.UUID(0, 909), "Other"));
            }
            while (!f.clients.isEmpty()) f.clients.remove().run();
            assertTrue(loaded.join().isPresent(), "Image delivery and Gallery publication have distinct lifetimes");
            if (boundary.equals("account")) f.identity.set(new com.naocraftlab.skins.client.GameSessionTokenSource.SessionIdentity(
                    GalleryRuntimeSelectionTest.ACCOUNT, "Fixture"));
            f.drain();
            if (boundary.equals("selection") || boundary.equals("reopen")) { f.selectB(); f.drain(); }
            assertTrue(f.view().widget("gallery.inspect.OFFLINE").isPresent());
            assertTrue(f.view().widget("gallery.inspect.SNEAKY").isEmpty(), "Current projection reuses completed metadata without stale scope publication");
            var current = f.runtime.loadCapePreview(offline);
            f.drain();
            assertTrue(current.join().isPresent());
            assertTrue(f.view().widget("gallery.inspect.SNEAKY").isEmpty(), "Explicit current lookup reuses the cached full identity safely");
        } finally { f.runtime.close(); }
    }

    @Test void regroupPreservesInspectedMemberAndPersistedModeAcrossFilteredBulkSections() throws Exception {
        var f = fixture();
        try {
            GalleryInspectLayersTest.ordinary(f);
            assertTrue(f.view().widget("gallery.preview_mode").orElseThrow().enabled());
            f.runtime.dispatchWidget("gallery.preview_mode");
            f.drain();
            assertEquals(com.naocraftlab.skins.core.model.CapePreviewMode.ELYTRA,
                    f.storage.loadUiPreferences(GalleryRuntimeSelectionTest.ACCOUNT).preferences().preferredCapeMode());
            GalleryInspectLayersTest.ordinary(f);
            assertTrue(f.view().widget("gallery.inspect.SNEAKY").orElseThrow().activationEnabled());
            f.runtime.dispatchWidget("gallery.inspect.SNEAKY", false, InteractionOrigin.POINTER);
            GalleryInspectLayersTest.ordinary(f);
            var before = candidate(f);
            assertEquals(Optional.of("SNEAKY"), before.bodySource().orElseThrow().inspectionIdentity());
            assertEquals(PreviewRenderer.CapeMode.ELYTRA, before.capeMode());
            f.runtime.dispatchText("gallery.search", "B");
            assertEquals(Optional.of("B"), f.view().widget("gallery.search").orElseThrow().value());
            assertTrue(f.view().widget("gallery.sections").orElseThrow().enabled());
            f.runtime.dispatchWidget("gallery.sections");
            f.drain();
            assertTrue(f.storage.loadUiPreferences(GalleryRuntimeSelectionTest.ACCOUNT).preferences()
                    .collapsedGallerySections().contains("gallery.your_appearances"));
            var offline = f.view().backEquipmentPreviews().stream()
                    .filter(p -> p.id().equals("gallery.inspect.OFFLINE.preview")).findFirst().orElseThrow().capeId();
            var pending = f.runtime.loadCapePreview(offline);
            f.drain();
            assertTrue(pending.join().isPresent());
            assertTrue(f.view().widget("gallery.inspect.SNEAKY").isEmpty());
            assertEquals(before, candidate(f));
            assertEquals(Optional.of("active"), f.view().widget("gallery.inspect.OFFLINE").orElseThrow().value());
            assertEquals(com.naocraftlab.skins.core.model.CapePreviewMode.ELYTRA,
                    f.storage.loadUiPreferences(GalleryRuntimeSelectionTest.ACCOUNT).preferences().preferredCapeMode());
            f.runtime.dispatchWidget("gallery.sections");
            f.drain();
            assertFalse(f.storage.loadUiPreferences(GalleryRuntimeSelectionTest.ACCOUNT).preferences()
                    .collapsedGallerySections().contains("gallery.your_appearances"));
            assertEquals(before, candidate(f));
            assertEquals(com.naocraftlab.skins.core.model.CapePreviewMode.ELYTRA,
                    f.storage.loadUiPreferences(GalleryRuntimeSelectionTest.ACCOUNT).preferences().preferredCapeMode());
        } finally { f.runtime.close(); }
    }

    @Test void preparedSourceRemainsUsableAcrossMetadataPublicationAndPriorityGrouping() throws Exception {
        var f = fixture(true, true);
        try {
            var component = com.naocraftlab.skins.core.provider.AppearanceProviders.Component.CAPE;
            var provider = com.naocraftlab.skins.core.provider.BuiltinProvider.SNEAKY;
            while (f.runtime.snapshot().providers().cape().order().indexOf(provider) > 0)
                GallerySneakyPreparationJoinTest.change(f, f.operations.moveProvider(component, provider, -1));
            f.drain();
            GalleryInspectLayersTest.ordinary(f);
            f.runtime.dispatchWidget("gallery.inspect.OFFLINE", false, InteractionOrigin.POINTER);
            GalleryInspectLayersTest.ordinary(f);
            var before = candidate(f);
            assertEquals(Optional.of("OFFLINE"), before.bodySource().orElseThrow().inspectionIdentity(), "Prepared-source control requires admitted Offline inspection");
            var opened = f.runtime.openPreparedCapePreview(before.capeId().orElseThrow());
            f.drain();
            try (var source = opened.join().orElseThrow()) {
                assertTrue(f.view().widget("gallery.inspect.OFFLINE").isEmpty());
                assertEquals(Optional.of("active"), f.view().widget("gallery.inspect.SNEAKY").orElseThrow().value());
                assertEquals(before, candidate(f));
                var frame = source.nextFrame(-1);
                f.drain();
                try (var pixels = frame.join().orElseThrow()) { assertEquals(64, pixels.width()); }
            }
        } finally { f.runtime.close(); }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void lateFullIdentityMergesSneakyWithoutResettingItsInspectionOrBodyAuthority(boolean keyboard) throws Exception {
        var f = fixture();
        try {
            assertTrue(f.view().widget("gallery.inspect.OFFLINE").isPresent());
            assertTrue(f.view().widget("gallery.inspect.SNEAKY").isPresent());
            GalleryInspectLayersTest.ordinary(f);
            f.runtime.dispatchWidget("gallery.inspect.SNEAKY", false, InteractionOrigin.POINTER);
            GalleryInspectLayersTest.ordinary(f);
            var before = candidate(f);
            assertEquals(Optional.of("SNEAKY"), before.bodySource().orElseThrow().inspectionIdentity());
            var providers = f.runtime.snapshot().providers();
            var inventory = ScopedEditorSaveTest.inventory(directory);
            var operations = new ArrayList<String>();
            f.beforeOperation = name -> {
                if (name.startsWith("load") || name.startsWith("openPrepared")) operations.add(name);
            };
            var offline = f.view().backEquipmentPreviews().stream()
                    .filter(p -> p.id().equals("gallery.inspect.OFFLINE.preview")).findFirst().orElseThrow().capeId();
            var pending = f.runtime.loadCapePreview(offline);
            assertFalse(pending.isDone());
            assertTrue(f.view().widget("gallery.inspect.SNEAKY").isPresent());
            f.drain();
            assertTrue(pending.join().isPresent());
            assertTrue(f.view().widget("gallery.inspect.SNEAKY").isEmpty(), "Confirmed equal full assets must form one cell");
            assertEquals(Optional.of("active"), f.view().widget("gallery.inspect.OFFLINE").orElseThrow().value());
            assertEquals(before, candidate(f), "Metadata must not change exact inspected source, transform, generation or material request");
            assertEquals(before, GalleryInspectLayersTest.main(f));
            assertTrue(f.view().widget("gallery.outer_layer.head").orElseThrow().activationEnabled());
            assertTrue(f.view().navigationNode("gallery.inspect.SNEAKY").isEmpty());
            assertTrue(f.view().backEquipmentPreviews().stream().noneMatch(p -> p.id().equals("gallery.inspect.SNEAKY.preview")));
            int loads = operations.size();
            for (int i = 0; i < 5; i++) f.view();
            assertEquals(loads, operations.size(), "Regroup/repaint must not restart loading");
            f.runtime.dispatchWidget("gallery.inspect.SNEAKY", false, InteractionOrigin.POINTER);
            assertEquals(before, candidate(f), "Hidden member action is not an alias");
            assertFalse(f.runtime.dispatchNavigation(ViewSpec.NavigationCommand.ACTIVATE, "gallery.inspect.SNEAKY"));
            if (keyboard) assertTrue(f.runtime.dispatchNavigation(ViewSpec.NavigationCommand.ACTIVATE, "gallery.inspect.OFFLINE"));
            else f.runtime.dispatchWidget("gallery.inspect.OFFLINE", false, InteractionOrigin.POINTER);
            var selected = candidate(f);
            assertEquals(Optional.of(offline), selected.capeId());
            assertNotEquals(before.bodySource(), selected.bodySource());
            assertEquals(before.bodySource().orElseThrow().draftGeneration() + 1,
                    selected.bodySource().orElseThrow().draftGeneration());
            assertEquals(providers, f.runtime.snapshot().providers());
            assertEquals(inventory, ScopedEditorSaveTest.inventory(directory));
            assertEquals(0, f.reconciliations.get());
        } finally { f.runtime.close(); }
    }
}

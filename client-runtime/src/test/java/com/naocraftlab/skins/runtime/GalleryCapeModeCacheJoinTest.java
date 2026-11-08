package com.naocraftlab.skins.runtime;

import com.naocraftlab.skins.client.*;
import com.naocraftlab.skins.core.compatibility.SkinExtensionEnvironment;
import com.naocraftlab.skins.core.model.*;
import com.naocraftlab.skins.core.provider.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.*;

import static org.junit.jupiter.api.Assertions.*;

final class GalleryCapeModeCacheJoinTest {
    @TempDir Path directory;

    @ParameterizedTest
    @ValueSource(ints = {1, 2})
    void admittedModePreservesSecondOrThirdInspectionAndEveryPublishedCapeSet(int index) throws Exception {
        var f = fixture();
        try {
            var live = new AtomicReference<>(confirmed());
            f.runtime.observePreviewAccessories(candidate(f), live::get);
            GalleryInspectLayersTest.ordinary(f);
            var initialRows = rows(f.view());
            assertEquals(List.of("gallery.inspect.OFFLINE", "gallery.inspect.MINECRAFT", "gallery.inspect.CPM"), initialRows);
            String inspected = initialRows.get(index);
            assertTrue(f.view().widget(inspected).orElseThrow().activationEnabled());
            f.runtime.dispatchWidget(inspected, false, InteractionOrigin.POINTER);
            var selected = candidate(f);
            assertEquals(Optional.of(inspected.substring("gallery.inspect.".length())), selected.bodySource().orElseThrow().inspectionIdentity());
            f.runtime.observePreviewAccessories(selected, live::get);
            GalleryInspectLayersTest.ordinary(f);
            assertEquals(initialRows, rows(f.view()));
            var before = ScopedEditorSaveTest.inventory(directory);
            var providers = f.runtime.snapshot().providers();
            var expectedLeft = leftGeometry(f.view());
            var observedLeft = new ArrayList<List<Object>>();
            var observedRows = new ArrayList<List<String>>();
            var subscription = f.runtime.subscribe(ignored -> {
                assertTrue(observedRows.size() < 30, "Subscriber view must reach a fixed point");
                observedRows.add(rows(f.view()));
                observedLeft.add(leftGeometry(f.view()));
            });
            try {
                for (int i = 0; i < 4; i++) {
                    var old = candidate(f);
                    var reads = new AtomicInteger();
                    var body = f.runtime.observePreviewBody(old, () -> { reads.incrementAndGet(); return PreviewRenderer.DisplayKind.ORDINARY; });
                    var accessory = f.runtime.observePreviewAccessories(old, () -> { reads.incrementAndGet(); return live.get(); });
                    var admission = f.runtime.galleryPreviewAdmission(old);
                    var inputAdmission = f.runtime.galleryAccessoryAdmission(old);
                    assertTrue(admission.getAsBoolean());
                    assertTrue(f.view().widget("gallery.preview_mode").orElseThrow().enabled());
                    var oldMode = old.capeMode();
                    var oldSource = f.runtime.previewBodySource(old);
                    f.runtime.dispatchWidget("gallery.preview_mode", false, InteractionOrigin.POINTER);
                    var next = candidate(f);
                    assertNotEquals(oldMode, next.capeMode(), "Enabled mode action must actually change presentation");
                    assertNotEquals(oldSource, f.runtime.previewBodySource(next));
                    assertFalse(admission.getAsBoolean());
                    assertFalse(inputAdmission.getAsBoolean(), "Preserved knowledge cannot authorize an old native query");
                    assertEquals(old.bodySource().orElseThrow().inspectionIdentity(), next.bodySource().orElseThrow().inspectionIdentity());
                    assertEquals(old.capeId(), next.capeId());
                    assertEquals(old.nativeAccessories(), next.nativeAccessories());
                    int staleReads = reads.get();
                    body.accept(PreviewRenderer.BodyStatus.READY);
                    accessory.accept(confirmed());
                    assertEquals(staleReads, reads.get(), "Old generation suppliers stay unread");
                    assertEquals(PreviewRenderer.DisplayKind.UNCONFIRMED, draft(f).displayedBodyKind());
                    assertEquals(initialRows, rows(f.view()));
                    live.set(PreviewAccessories.unavailable(PreviewRenderer.LiveReason.LOADING));
                    f.runtime.observePreviewAccessories(next, live::get);
                    assertEquals(initialRows, rows(f.view()), "Cold successor keeps input knowledge, not live authority");
                    assertFalse(f.view().widget("gallery.inspect.CPM").orElseThrow().activationEnabled());
                    live.set(confirmed());
                    f.runtime.observePreviewAccessories(candidate(f), live::get);
                    GalleryInspectLayersTest.ordinary(f);
                    assertEquals(Optional.of("active"), f.view().widget(inspected).orElseThrow().value());
                    assertTrue(f.view().previews().stream().filter(p -> !p.id().equals("gallery.preview") && p.capeId().isPresent())
                            .allMatch(p -> p.capeMode() == next.capeMode()));
                }
                assertTrue(observedRows.stream().allMatch(initialRows::equals), observedRows.toString());
                assertTrue(observedLeft.stream().allMatch(expectedLeft::equals), observedLeft.toString());
                assertEquals(providers, f.runtime.snapshot().providers());
                assertEquals(before, ScopedEditorSaveTest.inventory(directory));
                assertTrue(f.view().widget("gallery.done").isPresent());
                assertEquals(0, f.reconciliations.get());
                f.selectB();
                assertTrue(candidate(f).bodySource().orElseThrow().inspectionIdentity().isEmpty());
                assertFalse(candidate(f).nativeAccessories());
            } finally { subscription.close(); }
        } finally { f.runtime.close(); }
    }

    private static List<Object> leftGeometry(ViewSpec view) {
        var result = new ArrayList<Object>();
        view.texts().stream().filter(t -> t.id().startsWith("gallery.layers") || t.id().startsWith("gallery.capes"))
                .forEach(t -> { result.add(t.id()); result.add(t.bounds()); });
        view.widgets().stream().filter(w -> w.id().startsWith("gallery.outer_layer.") || w.id().startsWith("gallery.inspect."))
                .forEach(w -> { result.add(w.id()); result.add(w.bounds()); result.add(w.visible()); result.add(w.enabled()); });
        result.add(view.scrollSurface("gallery.stage").orElseThrow());
        return result;
    }

    private GalleryRuntimeSelectionTest fixture() throws Exception {
        var f = new GalleryRuntimeSelectionTest();
        f.directory = directory;
        f.layerController = Optional.of(new GalleryInspectLayersTest.Layers());
        f.setup();
        f.operations.useCpmPresence(() -> true);
        GallerySneakyPreparationJoinTest.change(f, f.operations.enableProvider(AppearanceProviders.Component.CAPE, BuiltinProvider.OFFLINE));
        GallerySneakyPreparationJoinTest.change(f, f.operations.enableProvider(AppearanceProviders.Component.CAPE, BuiltinProvider.CPM));
        var image = new java.awt.image.BufferedImage(64, 32, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 32; y++) for (int x = 0; x < 64; x++) image.setRGB(x, y, 0xff225588);
        var encoded = directory.resolve("cape.png");
        javax.imageio.ImageIO.write(image, "png", encoded.toFile());
        var prepared = new com.naocraftlab.skins.core.png.CapeAssetDecoder().prepare(encoded, directory.resolve("cape.frames"));
        var entry = f.storage.importPreparedCape(GalleryRuntimeSelectionTest.ACCOUNT, "Cape", prepared, ignored -> {});
        f.storage.updateAccount(GalleryRuntimeSelectionTest.ACCOUNT, old -> new AccountState(old.schemaVersion(), old.accountId(), old.skinAssets(), old.personalSkins(),
                old.presets().stream().map(p -> p.id().equals(GalleryRuntimeSelectionTest.A)
                        ? new AppearancePreset(p.id(), p.name(), p.skin(), "official-a", p.outerLayerVisibility(), p.createdAt(), p.updatedAt(),
                                entry.texture(), p.favorite()) : p).toList(), old.updatedAt(), old.personalCapes()));
        f.runtime.dispatchWidget("gallery.reset");
        f.drain();
        var env = ClientRuntime.class.getDeclaredField("skinExtensionEnvironment");
        env.setAccessible(true);
        env.set(f.runtime, new SkinExtensionEnvironment(1, Map.of(), true));
        GalleryInspectLayersTest.providers(f, f.runtime.snapshot().providers());
        return f;
    }

    @org.junit.jupiter.api.Test
    void projectedCapeKeyRetainsActualPreparedSourceAndFrameAcrossModesAndHover() throws Exception {
        var f = fixture();
        var opens = new AtomicInteger();
        var frames = new AtomicInteger();
        var closes = new AtomicInteger();
        var registry = new Registry();
        try (var cache = new PreviewAssetCache<String>(registry, TextureRegistry.TextureKind.IMAGE,
                com.naocraftlab.skins.diagnostics.DiagnosticSinks.discarding())) {
            f.runtime.observePreviewAccessories(candidate(f), GalleryCapeModeCacheJoinTest::confirmed);
            GalleryInspectLayersTest.ordinary(f);
            f.runtime.dispatchWidget("gallery.inspect.MINECRAFT", false, InteractionOrigin.POINTER);
            f.runtime.observePreviewAccessories(candidate(f), GalleryCapeModeCacheJoinTest::confirmed);
            GalleryInspectLayersTest.ordinary(f);
            var original = f.view().backEquipmentPreviews().stream()
                    .filter(p -> p.id().equals("gallery.inspect.OFFLINE.preview")).findFirst().orElseThrow();
            String key = ViewHostPolicy.cardCapeKey(original);
            var retained = new HashSet<String>();
            for (int i = 0; i < 8; i++) {
                var view = f.view();
                var projection = view.backEquipmentPreviews().stream().filter(p -> p.id().equals(original.id())).findFirst().orElseThrow();
                assertEquals(original.capeId(), projection.capeId());
                assertEquals(com.naocraftlab.skins.client.BackEquipmentPreviewRenderer.Mode.CAPE, projection.mode());
                assertEquals(key, ViewHostPolicy.cardCapeKey(projection));
                retained.clear();
                view.backEquipmentPreviews().forEach(p -> retained.add(ViewHostPolicy.cardCapeKey(p)));
                cache.retain(retained);
                boolean hover = ViewHostPolicy.capeCardHovered(view, projection,
                        i % 2 == 0 ? projection.bounds().x() + 1 : -1,
                        i % 2 == 0 ? projection.bounds().y() + 1 : -1);
                cache.requestCape(key, projection.capeId(), () -> {
                    opens.incrementAndGet();
                    return f.runtime.openPreparedCapePreview(projection.capeId(), true).thenApply(source -> source.map(delegate -> new CapePreviewSource() {
                        public java.util.concurrent.CompletableFuture<Optional<Frame>> nextFrame(int installed) {
                            return delegate.nextFrame(installed).thenApply(frame -> { if (frame.isPresent()) frames.incrementAndGet(); return frame; });
                        }
                        public void installed(int index) { delegate.installed(index); }
                        public void playing(boolean value) { delegate.playing(value); }
                        public boolean thumbnail() { return delegate.thumbnail(); }
                        public void close() throws java.io.IOException { closes.incrementAndGet(); delegate.close(); }
                    }));
                }, () -> { fail("Prepared cape must not use byte fallback"); return null; }, hover, () -> fail("Texture registration"));
                f.drain();
                assertTrue(cache.handle(key).isPresent());
                assertEquals(1, opens.get());
                assertEquals(1, frames.get());
                assertEquals(1, registry.registrations);
                assertEquals(0, closes.get());
                assertTrue(f.view().widget("gallery.preview_mode").orElseThrow().enabled());
                var old = candidate(f).capeMode();
                f.runtime.dispatchWidget("gallery.preview_mode", false, InteractionOrigin.POINTER);
                assertNotEquals(old, candidate(f).capeMode());
                f.runtime.observePreviewAccessories(candidate(f), GalleryCapeModeCacheJoinTest::confirmed);
                GalleryInspectLayersTest.ordinary(f);
            }
            cache.retain(Set.of());
            assertTrue(cache.handle(key).isEmpty());
            assertEquals(1, closes.get());
            assertEquals(1, registry.releases);
        } finally { f.runtime.close(); }
    }

    @ParameterizedTest
    @ValueSource(strings = {"source", "resource", "world", "screen", "account", "disable", "reorder", "extension"})
    void modePreservedInspectionStillRetiresWithRealInputOrPolicy(String change) throws Exception {
        var f = fixture();
        try {
            var live = confirmed();
            f.runtime.observePreviewAccessories(candidate(f), () -> live);
            GalleryInspectLayersTest.ordinary(f);
            f.runtime.dispatchWidget("gallery.inspect.CPM", false, InteractionOrigin.POINTER);
            f.runtime.observePreviewAccessories(candidate(f), () -> live);
            GalleryInspectLayersTest.ordinary(f);
            f.runtime.dispatchWidget("gallery.preview_mode", false, InteractionOrigin.POINTER);
            assertEquals(Optional.of("CPM"), candidate(f).bodySource().orElseThrow().inspectionIdentity());
            var reads = new AtomicInteger();
            var callback = f.runtime.observePreviewAccessories(candidate(f), () -> { reads.incrementAndGet(); return live; });
            switch (change) {
                case "source" -> f.selectB();
                case "resource" -> f.runtime.resourcesReloaded();
                case "world" -> f.runtime.importWorldChanged();
                case "screen" -> { f.runtime.closeScreen(); f.runtime.reopen(); f.drain(); }
                case "account" -> f.identity.set(new GameSessionTokenSource.SessionIdentity(new UUID(0, 999), "Other"));
                case "disable" -> GallerySneakyPreparationJoinTest.change(f, f.operations.disableProvider(AppearanceProviders.Component.CAPE, BuiltinProvider.CPM));
                case "reorder" -> GallerySneakyPreparationJoinTest.change(f, f.operations.moveProvider(AppearanceProviders.Component.CAPE, BuiltinProvider.CPM, -1));
                case "extension" -> {
                    var env = ClientRuntime.class.getDeclaredField("skinExtensionEnvironment");
                    env.setAccessible(true);
                    env.set(f.runtime, new SkinExtensionEnvironment(2, Map.of(), false));
                    GalleryInspectLayersTest.providers(f, f.runtime.snapshot().providers());
                }
                default -> fail(change);
            }
            int before = reads.get();
            callback.accept(live);
            assertEquals(before, reads.get());
            if (!change.equals("account")) assertTrue(candidate(f).bodySource().orElseThrow().inspectionIdentity().isEmpty());
        } finally { f.runtime.close(); }
    }

    private static final class Registry implements TextureRegistry {
        int registrations;
        int releases;
        final Map<String, CpuLease> leases = new HashMap<>();
        public TextureHandle register(TextureKind kind, String hash, Path path) { throw new AssertionError(); }
        public TextureHandle register(TextureKind kind, String hash, byte[] png) { throw new AssertionError(); }
        public TextureHandle registerRgba(TextureKind kind, String hash, int width, int height, byte[] rgba, CpuLease lease) {
            var id = "registered/" + ++registrations;
            leases.put(id, lease);
            return new TextureHandle(id, width, height);
        }
        public void release(TextureHandle handle) { releases++; leases.remove(handle.location()).close(); }
        public void close() { assertTrue(leases.isEmpty()); }
    }

    private static GalleryPresenter.DraftInput draft(GalleryRuntimeSelectionTest f) throws Exception {
        var field = ClientRuntime.class.getDeclaredField("galleryFlow");
        field.setAccessible(true);
        var method = GalleryFlow.class.getDeclaredMethod("draftInput");
        method.setAccessible(true);
        return (GalleryPresenter.DraftInput) method.invoke(field.get(f.runtime));
    }

    private static PreviewAccessories confirmed() {
        return new PreviewAccessories(new UUID(0, 931), 1, PreviewAccessories.Availability.CONFIRMED,
                true, true, PreviewRenderer.LiveReason.AVAILABLE);
    }

    private static ViewSpec.Preview candidate(GalleryRuntimeSelectionTest f) {
        return f.runtime.galleryPreviewCandidate().orElseThrow();
    }

    private static List<String> rows(ViewSpec view) {
        return view.widgets().stream().filter(w -> w.id().startsWith("gallery.inspect.")).map(ViewSpec.Widget::id).toList();
    }
}

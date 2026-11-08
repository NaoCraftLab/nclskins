package com.naocraftlab.skins.runtime;

import com.naocraftlab.skins.client.*;
import com.naocraftlab.skins.core.model.*;
import com.naocraftlab.skins.core.service.LibraryService;
import com.naocraftlab.skins.core.storage.LibraryStorageAdapter;
import com.naocraftlab.skins.diagnostics.DiagnosticSinks;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import java.util.*;
import javax.imageio.ImageIO;

import static org.junit.jupiter.api.Assertions.*;

final class GalleryPreviewSourceJoinTest {
    @TempDir Path directory;

    @Test void realSelectionLoadsOwnBytesThroughCacheAndProjectsRequestInsteadOfBorrowingA() throws Exception {
        var f = new GalleryRuntimeSelectionTest();
        f.directory = directory;
        f.setup();
        var registry = new Registry();
        try (var skins = new PreviewAssetCache<String>(registry, TextureRegistry.TextureKind.PLAYER_SKIN, DiagnosticSinks.discarding())) {
            var adapter = new LibraryStorageAdapter(f.storage);
            var library = new LibraryService(adapter, adapter, GalleryApplyTest.CLOCK);
            var ids = new ArrayList<UUID>();
            var expected = new HashMap<UUID, byte[]>();
            for (int i = 0; i < 3; i++) {
                byte[] png = png(0xFF225588 + i * 0x00221122);
                var asset = library.importSkin(GalleryRuntimeSelectionTest.ACCOUNT, "Source " + i,
                        i == 1 ? SkinVariant.SLIM : SkinVariant.CLASSIC, SkinSource.IMPORTED, png).asset();
                UUID id = i == 0 ? GalleryRuntimeSelectionTest.A : i == 1 ? GalleryRuntimeSelectionTest.B : new UUID(0, 303);
                ids.add(id);
                expected.put(id, png);
                var preset = new AppearancePreset(id, "Source " + i, SkinReference.asset(asset.id()),
                        "cape-" + i, i == 1 ? OuterLayerVisibility.noneVisible() : i == 2
                                ? OuterLayerVisibility.of(Set.of(OuterLayerPart.values()[0])) : OuterLayerVisibility.allVisible(),
                        GalleryApplyTest.NOW.plusSeconds(i), GalleryApplyTest.NOW.plusSeconds(i));
                f.storage.updateAccount(GalleryRuntimeSelectionTest.ACCOUNT, old -> {
                    var presets = new ArrayList<>(old.presets().stream().filter(p -> !p.id().equals(id)).toList());
                    presets.add(preset);
                    return new AccountState(old.schemaVersion(), old.accountId(), old.skinAssets(), presets, old.updatedAt());
                });
            }
            f.runtime.dispatchWidget("gallery.reset");
            f.drain();
            var borrowed = new CurrentPlayerAppearanceSource.PlayerAppearance(
                    new TextureRegistry.TextureHandle("borrowed-A", 64, 64), SkinModel.CLASSIC, Optional.empty());
            for (UUID id : ids) {
                f.runtime.dispatchWidget(f.card(id));
                var preview = main(f);
                assertEquals(id, preview.presetId().orElseThrow());
                assertTrue(preview.requiresLoadedSkin());
                String key = preview.id() + ":" + preview.imageRevision();
                skins.request(key, () -> f.runtime.loadSkinPreview(preview), () -> fail("Registration failure"));
                assertTrue(skins.handle(key).isEmpty());
                assertTrue(project(preview, skins, key, borrowed).isEmpty(), "Pending B/C must not borrow A");
                f.drain();
                skins.request(key, () -> f.runtime.loadSkinPreview(preview), () -> fail("Registration failure"));
                var request = project(main(f), skins, key, borrowed).orElseThrow();
                if (id.equals(ids.get(0))) borrowed = new CurrentPlayerAppearanceSource.PlayerAppearance(
                        request.appearance().skin(), request.appearance().model(), request.appearance().cape());
                assertArrayEquals(expected.get(id), registry.bytes.get(request.appearance().skin().location()));
                assertEquals(preview.variant() == SkinVariant.SLIM ? SkinModel.SLIM : SkinModel.CLASSIC, request.appearance().model());
                assertEquals(preview.outerLayerVisibility(), request.appearance().outerLayerVisibility());
                assertEquals(preview.capeMode(), request.appearance().capeMode());
                assertEquals("resolved:" + preview.capeId().orElseThrow(), request.appearance().cape().orElseThrow().location());
                assertEquals(preview.anchorBounds().x(), request.left());
                assertEquals(preview.bounds().width(), request.stageWidth());
                if (!id.equals(ids.get(0))) assertNotEquals(borrowed.skin(), request.appearance().skin());
                var wrong = request(preview, Optional.of(borrowed.skin()), Optional.of(borrowed),
                        request.appearance().cape(), true, false, TextureUseValidity.INVALID).orElseThrow();
                if (!id.equals(ids.get(0))) assertThrows(AssertionError.class, () -> assertArrayEquals(expected.get(id), registry.bytes.get(wrong.appearance().skin().location())),
                        "Borrowed-A substitution must fail the byte oracle");
            }
            assertEquals(GalleryRuntimeSelectionTest.A, f.storage.loadAppearance(GalleryRuntimeSelectionTest.ACCOUNT).activePresetId());
            assertEquals(3, registry.bytes.size());
            f.selectB();
            {
                String key = main(f).id() + ":" + main(f).imageRevision();
                    var joined = f.view();
                    var cardC = joined.previews().stream().filter(p -> p.id().equals("gallery.preset." + ids.get(2) + ".preview")).findFirst().orElseThrow();
                    assertEquals(GalleryRuntimeSelectionTest.B, main(f).presetId().orElseThrow());
                    assertEquals(PreviewRenderer.PreviewIntent.ASSET_THUMBNAIL, cardC.intent());
                    assertTrue(cardC.bodySource().isEmpty());
                    assertEquals(ViewSpec.BodyRole.GALLERY_MAIN, main(f).bodySource().orElseThrow().role());
                    String cardKey = cardC.id() + ":" + cardC.imageRevision();
                    skins.request(cardKey, () -> f.runtime.loadSkinPreview(cardC), () -> fail("C card registration"));

                    f.drain();
                    var currentC = f.view().previews().stream().filter(p -> p.id().equals(cardC.id())).findFirst().orElseThrow();
                    var cRequest = project(currentC, skins, cardKey, borrowed).orElseThrow();
                    var bRequest = project(main(f), skins, key, borrowed).orElseThrow();
                    assertArrayEquals(expected.get(ids.get(1)), registry.bytes.get(bRequest.appearance().skin().location()));
                    assertArrayEquals(expected.get(ids.get(2)), registry.bytes.get(cRequest.appearance().skin().location()));
                    assertNotEquals(bRequest.appearance().skin(), cRequest.appearance().skin());
                    assertEquals(SkinModel.SLIM, bRequest.appearance().model());
                    assertEquals(SkinModel.CLASSIC, cRequest.appearance().model());
                    assertEquals(OuterLayerVisibility.noneVisible(), bRequest.appearance().outerLayerVisibility());
                    assertEquals(OuterLayerVisibility.of(Set.of(OuterLayerPart.values()[0])), cRequest.appearance().outerLayerVisibility());
                    assertEquals("resolved:cape-1", bRequest.appearance().cape().orElseThrow().location());
                    assertEquals("resolved:cape-2", cRequest.appearance().cape().orElseThrow().location());
                    assertEquals(GalleryRuntimeSelectionTest.A, f.runtime.snapshot().activePresetId().orElseThrow());
                }
            assertEquals(4, registry.bytes.size());
        } finally { f.runtime.close(); }
    }

    @Test void defaultProviderRefreshLateAndFailureControlsUseRealRuntimeAndCache() throws Exception {
        var f = new GalleryRuntimeSelectionTest();
        f.directory = directory;
        var active = new CurrentPlayerAppearanceSource.PlayerAppearance(new TextureRegistry.TextureHandle("active-A", 64, 64),
                SkinModel.SLIM, Optional.empty());
        var fallback = new CurrentPlayerAppearanceSource.PlayerAppearance(new TextureRegistry.TextureHandle("account-default", 64, 64),
                SkinModel.CLASSIC, Optional.empty());
        f.currentAppearance = Optional.of(new CurrentPlayerAppearanceSource() {
            public PlayerAppearance currentPlayerAppearance() { return active; }
            public PlayerAppearance defaultPlayerAppearance() { return fallback; }
        });
        f.setup();
        var registry = new Registry();
        try (var cache = new PreviewAssetCache<String>(registry, TextureRegistry.TextureKind.PLAYER_SKIN, DiagnosticSinks.discarding())) {
            var defaultPreview = main(f);
            assertEquals("provider:default", defaultPreview.imageRevision());
            assertFalse(defaultPreview.requiresLoadedSkin());
            var defaultRequest = request(defaultPreview, Optional.of(active.skin()),
                    f.runtime.previewPlayerAppearance(defaultPreview), Optional.empty(), false, false, TextureUseValidity.INVALID).orElseThrow();
            assertEquals(fallback.skin(), defaultRequest.appearance().skin());
            assertEquals(fallback.model(), defaultRequest.appearance().model());
            assertNotEquals(active.skin(), defaultRequest.appearance().skin());
            var adapter = new LibraryStorageAdapter(f.storage);
            var library = new LibraryService(adapter, adapter, GalleryApplyTest.CLOCK);
            var b = library.importSkin(GalleryRuntimeSelectionTest.ACCOUNT, "B bytes", SkinVariant.SLIM, SkinSource.IMPORTED, png(0xFF113355)).asset();
            var c = library.importSkin(GalleryRuntimeSelectionTest.ACCOUNT, "C bytes", SkinVariant.CLASSIC, SkinSource.IMPORTED, png(0xFF779922)).asset();
            UUID cId = new UUID(0, 333);
            f.storage.updateAccount(GalleryRuntimeSelectionTest.ACCOUNT, old -> new AccountState(old.schemaVersion(), old.accountId(), old.skinAssets(),
                    List.of(old.presets().get(0), new AppearancePreset(GalleryRuntimeSelectionTest.B, "B", SkinReference.asset(b.id()), "cape-b",
                            OuterLayerVisibility.noneVisible(), GalleryApplyTest.NOW, GalleryApplyTest.NOW),
                            new AppearancePreset(cId, "C", SkinReference.asset(c.id()), "cape-c", GalleryApplyTest.NOW, GalleryApplyTest.NOW)), old.updatedAt()));
            f.runtime.dispatchWidget("gallery.reset"); f.drain();
            f.selectB(); var bp = main(f);
            cache.request("B", () -> f.runtime.loadSkinPreview(bp), () -> fail("B registration"));
            Runnable lateB = f.workers.remove();
            f.runtime.dispatchWidget(f.card(cId)); var cp = main(f);
            cache.request("C", () -> f.runtime.loadSkinPreview(cp), () -> fail("C registration"));
            f.drain();
            assertTrue(cache.handle("C").isPresent());
            cache.retain(Set.of("C"));
            f.workers.add(lateB); f.drain();
            assertTrue(cache.handle("B").isEmpty(), "Late B cannot reinstall retired cache entry");
            assertEquals(cId, main(f).presetId().orElseThrow());
            assertNotEquals(bp.imageRevision(), cp.imageRevision());
            assertArrayEquals(f.operations.loadSkinPreview(c.id()), registry.bytes.get(project(cp, cache, "C", active).orElseThrow().appearance().skin().location()));
            f.selectB();
            cache.request("B-relookup", () -> f.runtime.loadSkinPreview(main(f)), () -> fail("B relookup"));
            f.drain();
            assertArrayEquals(f.operations.loadSkinPreview(b.id()), registry.bytes.get(project(main(f), cache, "B-relookup", active).orElseThrow().appearance().skin().location()));
            var provider = new ViewSpec.Preview("provider.preview", bp.bounds(), SkinReference.accountDefault(), "provider:skin:" + b.sha256(),
                    SkinVariant.SLIM, Optional.empty(), PreviewRenderer.CapeMode.OFF, bp.outerLayerVisibility(), 0, 0, 1, Optional.empty());
            cache.request("provider", () -> f.runtime.loadSkinPreview(provider), () -> fail("provider registration")); f.drain();
            assertTrue(provider.requiresLoadedSkin());
            assertArrayEquals(f.operations.loadSkinPreview(b.id()), registry.bytes.get(project(provider, cache, "provider", active).orElseThrow().appearance().skin().location()));
            f.runtime.closeScreen(); f.runtime.reopen(); f.drain();
            f.selectB();
            registry.failRegistration = true;
            cache.request("failed", () -> f.runtime.loadSkinPreview(main(f)), () -> {}); f.drain();
            assertTrue(project(main(f), cache, "failed", active).isEmpty(), "Failure cannot borrow A");
            registry.failRegistration = false;
            f.identity.set(new GameSessionTokenSource.SessionIdentity(new UUID(0, 999), "Other"));
            f.runtime.closeScreen(); f.runtime.reopen(); f.drain();
            cache.retain(Set.of());
            assertTrue(cache.handle("B-relookup").isEmpty());
            assertTrue(f.runtime.snapshot().selectedPresetId().isEmpty());
        } finally { f.runtime.close(); }
    }

    private static ViewSpec.Preview main(GalleryRuntimeSelectionTest f) {
        return f.view().previews().stream().filter(p -> p.id().equals("gallery.preview")).findFirst().orElseThrow();
    }

    private static Optional<PreviewRenderer.PreviewRequest> project(ViewSpec.Preview preview, PreviewAssetCache<String> skins,
            String key, CurrentPlayerAppearanceSource.PlayerAppearance borrowed) {
        return request(preview, skins.handle(key), Optional.of(borrowed),
                preview.capeId().map(id -> new TextureRegistry.TextureHandle("resolved:" + id, 64, 32)),
                true, false, TextureUseValidity.INVALID);
    }

    private static Optional<PreviewRenderer.PreviewRequest> request(ViewSpec.Preview preview,
            Optional<TextureRegistry.TextureHandle> loaded, Optional<CurrentPlayerAppearanceSource.PlayerAppearance> borrowed,
            Optional<TextureRegistry.TextureHandle> cape, boolean hasElytra, boolean glint, TextureUseValidity validity) {
        java.util.function.BiFunction<TextureRegistry.TextureHandle, SkinModel, PreviewRenderer.PreviewAppearance> appearance =
                (skin, model) -> new PreviewRenderer.PreviewAppearance(skin, model, cape,
                        cape.isPresent() || preview.nativeAccessories() ? preview.capeMode() : PreviewRenderer.CapeMode.OFF,
                        preview.outerLayerVisibility(), hasElytra, glint, preview.nativeAccessories(), validity);
        return ViewHostPolicy.previewRequest(preview,
                loaded.map(skin -> appearance.apply(skin, preview.variant() == SkinVariant.SLIM ? SkinModel.SLIM : SkinModel.CLASSIC)),
                borrowed.map(source -> appearance.apply(source.skin(), source.model())));
    }

    static byte[] png(int color) throws Exception {
        var image = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 64; y++) for (int x = 0; x < 64; x++) image.setRGB(x, y, color);
        var out = new ByteArrayOutputStream();
        ImageIO.write(image, "PNG", out);
        return out.toByteArray();
    }

    static final class Registry implements TextureRegistry {
        final Map<String, byte[]> bytes = new LinkedHashMap<>();
        boolean failRegistration;
        public TextureHandle register(TextureKind kind, String hash, Path file) { throw new AssertionError(); }
        public TextureHandle register(TextureKind kind, String hash, byte[] png) throws java.io.IOException {
            if (failRegistration) throw new java.io.IOException("Synthetic registration failure");
            String location = "registered/" + bytes.size();
            bytes.put(location, png.clone());
            return new TextureHandle(location, 64, 64);
        }
        public void release(TextureHandle handle) {}
        public void close() {}
    }
}

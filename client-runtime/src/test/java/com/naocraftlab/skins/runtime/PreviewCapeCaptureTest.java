package com.naocraftlab.skins.runtime;

import com.naocraftlab.skins.client.*;
import com.naocraftlab.skins.diagnostics.DiagnosticSink;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PreviewCapeCaptureTest {
    @Test
    @SuppressWarnings("try")
    void cacheSuppliesExactPreviewIncarnationWithoutBorrowingWorldOrRetainingResource() {
        Registry registry = new Registry();
        PreviewAssetCache<String> cache = new PreviewAssetCache<>(registry,
                TextureRegistry.TextureKind.IMAGE, com.naocraftlab.skins.diagnostics.DiagnosticSinks.discarding());
        cache.request("inspection", () -> CompletableFuture.completedFuture(Optional.of(new byte[]{1})), () -> {});
        var handle = cache.handle("inspection").orElseThrow();
        var token = cache.capture("inspection");
        assertTrue(token.isValid());
        assertFalse(cache.capture("other").isValid());
        Object actor = new Object();
        var cape = new PreviewRenderer.PreviewAppearance(handle, SkinModel.CLASSIC, Optional.of(handle),
                PreviewRenderer.CapeMode.CAPE, OuterLayerVisibility.allVisible(), true, true, false, token);
        try (var scope = new ExactLocalPlayerScope(actor, cape)) {
            assertSame(token, ExactLocalPlayerScope.capeValidity(actor, handle.location()));
            assertNull(ExactLocalPlayerScope.capeValidity(new Object(), handle.location()));
            assertFalse(ExactLocalPlayerScope.capeValidity(actor, "nclskins:other").isValid());
        }
        assertNull(ExactLocalPlayerScope.capeValidity(actor, handle.location()));
        var elytra = new PreviewRenderer.PreviewAppearance(handle, SkinModel.CLASSIC, Optional.of(handle),
                PreviewRenderer.CapeMode.ELYTRA, OuterLayerVisibility.allVisible(), true, true, false, token);
        try (var scope = new ExactLocalPlayerScope(actor, elytra)) {
            assertFalse(ExactLocalPlayerScope.capeValidity(actor, handle.location()).isValid());
        }
        cache.retain(Set.of());
        assertFalse(token.isValid());
        assertEquals(1, registry.unloads);
        cache.request("inspection", () -> CompletableFuture.completedFuture(Optional.of(new byte[]{1})), () -> {});
        var next = cache.capture("inspection");
        assertTrue(next.isValid());
        assertNotSame(token, next);
        assertFalse(registry.capture(handle).isValid());
        cache.close();
        assertFalse(next.isValid());
        assertFalse(cache.capture("inspection").isValid());
        registry.close();
    }

    private static final class Registry extends AbstractTextureRegistry<String> {
        private int unloads;
        @Override protected LoadedTexture<String> load(TextureKind kind, String hash, byte[] png) {
            return new LoadedTexture<>(new TextureHandle("nclskins:" + hash, 64, 32), hash);
        }
        @Override protected void unload(String resource) { unloads++; }
        @Override protected void checkClientThread() {}
    }
}

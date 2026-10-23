package com.naocraftlab.skins.client;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNull;

class ExactLocalPlayerScopeTest {
    @Test
    void noCpmFactoryResourceOnlyLeaseAndUnextendedRendererStayOrdinary() {
        PreviewModelFrames.Lease resources = () -> {};
        org.junit.jupiter.api.Assertions.assertEquals(PreviewRenderer.BodyStatus.ORDINARY, resources.body().status());
        PreviewRenderer<Object> renderer = (graphics, request) -> {};
        org.junit.jupiter.api.Assertions.assertEquals(PreviewRenderer.BodyStatus.ORDINARY, renderer.body().status());
        try (var scope = new ExactLocalPlayerScope(new Object())) {
            org.junit.jupiter.api.Assertions.assertEquals(PreviewRenderer.BodyStatus.ORDINARY, scope.body().status());
        }
    }


    @Test
    void actualReadbackIsOnlyAvailableOnTheOpenOwnerScope() throws InterruptedException {
        var appearance = new PreviewRenderer.PreviewAppearance(
                new TextureRegistry.TextureHandle("nclskins:skin", 64, 64), SkinModel.CLASSIC,
                Optional.empty(), PreviewRenderer.CapeMode.OFF, true);
        AtomicBoolean active = new AtomicBoolean(true);
        AtomicBoolean closed = new AtomicBoolean();
        PreviewModelFrames.install(value -> new PreviewModelFrames.Lease() {
            public PreviewModelFrames.Body body() {
                return new PreviewModelFrames.Body(PreviewRenderer.BodyStatus.READY, active::get);
            }
            public void close() { closed.set(true); }
        });
        try {
            var scope = new ExactLocalPlayerScope(new Object(), appearance);
            var captured = scope.body();
            org.junit.jupiter.api.Assertions.assertEquals(PreviewRenderer.BodyStatus.READY, captured.status());
            var foreign = new java.util.concurrent.atomic.AtomicReference<PreviewRenderer.BodyStatus>();
            Thread thread = new Thread(() -> foreign.set(scope.body().status()));
            thread.start();
            thread.join();
            org.junit.jupiter.api.Assertions.assertEquals(PreviewRenderer.BodyStatus.ORDINARY, foreign.get());
            scope.close();
            assertTrue(closed.get());
            org.junit.jupiter.api.Assertions.assertEquals(PreviewRenderer.BodyStatus.ORDINARY, scope.body().status());
            assertTrue(captured.current().getAsBoolean());
            active.set(false);
            assertFalse(captured.current().getAsBoolean());
        } finally {
            PreviewModelFrames.install(value -> () -> {});
        }
    }


    @Test
    @SuppressWarnings("try")
    void elytraScopeAuthoritativelyRejectsProviderCapeGlint() {
        Object player = new Object();
        var appearance = new PreviewRenderer.PreviewAppearance(
                new TextureRegistry.TextureHandle("nclskins:skin", 64, 64), SkinModel.CLASSIC,
                Optional.of(new TextureRegistry.TextureHandle("nclskins:cape", 64, 32)),
                PreviewRenderer.CapeMode.ELYTRA, OuterLayerVisibility.allVisible(), true, true);
        try (var scope = new ExactLocalPlayerScope(player, appearance)) {
            assertFalse(ExactLocalPlayerScope.capeGlint(player, "nclskins:cape"));
        }
    }

    @Test
    void appliesOnlyToTheExactObjectOnTheOwnerThread() throws InterruptedException {
        Object localPlayer = new Object();
        ExactLocalPlayerScope scope = new ExactLocalPlayerScope(localPlayer);
        AtomicBoolean foreignThreadMatch = new AtomicBoolean(true);

        Thread foreignThread = new Thread(
                () -> foreignThreadMatch.set(scope.appliesTo(localPlayer)),
                "preview-scope-test");
        foreignThread.start();
        foreignThread.join();

        assertTrue(scope.appliesTo(localPlayer));
        assertFalse(scope.appliesTo(new Object()));
        assertFalse(foreignThreadMatch.get());
    }

    @Test
    void tryWithResourcesClosesTheScopeAfterAnException() {
        Object localPlayer = new Object();
        ExactLocalPlayerScope scope = new ExactLocalPlayerScope(localPlayer);

        assertThrows(IllegalStateException.class, () -> {
            try (scope) {
                assertTrue(scope.appliesTo(localPlayer));
                throw new IllegalStateException("render failure");
            }
        });

        assertFalse(scope.appliesTo(localPlayer));
    }

    @Test
    @SuppressWarnings("try")
    void previewCapeMaterialUsesExactPlayerAndTextureWithoutLeakingAfterClose() {
        Object player = new Object();
        var appearance = new PreviewRenderer.PreviewAppearance(
                new TextureRegistry.TextureHandle("nclskins:skin", 64, 64),
                SkinModel.CLASSIC,
                Optional.of(new TextureRegistry.TextureHandle("nclskins:cape", 64, 32)),
                PreviewRenderer.CapeMode.CAPE, OuterLayerVisibility.allVisible(), true, true);
        try (var scope = new ExactLocalPlayerScope(player, appearance)) {
            assertTrue(ExactLocalPlayerScope.capeGlint(player, "nclskins:cape"));
            assertFalse(ExactLocalPlayerScope.capeGlint(player, "nclskins:other"));
            assertNull(ExactLocalPlayerScope.capeGlint(new Object(), "nclskins:cape"));
        }
        assertNull(ExactLocalPlayerScope.capeGlint(player, "nclskins:cape"));
    }
    @Test
    @SuppressWarnings("try")
    void sequentialInspectionScopesDoNotLeakCapeMaterial() {
        Object player = new Object();
        var cape = new TextureRegistry.TextureHandle("nclskins:cape", 64, 32);
        var skin = new TextureRegistry.TextureHandle("nclskins:skin", 64, 64);
        var glint = new PreviewRenderer.PreviewAppearance(skin, SkinModel.CLASSIC,
                Optional.of(cape), PreviewRenderer.CapeMode.CAPE,
                OuterLayerVisibility.allVisible(), true, true);
        var plain = new PreviewRenderer.PreviewAppearance(skin, SkinModel.CLASSIC,
                Optional.of(cape), PreviewRenderer.CapeMode.CAPE,
                OuterLayerVisibility.allVisible(), true, false);
        try (var outer = new ExactLocalPlayerScope(player, glint)) {
            assertTrue(ExactLocalPlayerScope.capeGlint(player, cape.location()));
            assertThrows(IllegalStateException.class, () -> new ExactLocalPlayerScope(player, plain));
        }
        assertNull(ExactLocalPlayerScope.capeGlint(player, cape.location()));
        try (var next = new ExactLocalPlayerScope(player, plain)) {
            assertFalse(ExactLocalPlayerScope.capeGlint(player, cape.location()));
        }
        assertNull(ExactLocalPlayerScope.capeGlint(player, cape.location()));
    }

}

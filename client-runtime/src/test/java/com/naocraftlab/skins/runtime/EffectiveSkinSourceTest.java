package com.naocraftlab.skins.runtime;

import com.naocraftlab.skins.client.SkinModel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EffectiveSkinSourceTest {
    @AfterEach void reset() {
        CapeProjection.skinModels(change -> {});
        CapeProjection.skinLookup(id -> {});
    }

    @Test void replacementAndWorldChangeFenceLateWorkAndPreservePrivateRawCopies() {
        var changes = new ArrayList<CapeProjection.SkinChange>();
        CapeProjection.skinModels(changes::add);
        UUID world = UUID.randomUUID();
        UUID preview = UUID.randomUUID();
        int[] raw = new int[4096];
        raw[0] = 0x00123453;
        Object nativeTexture = new Object();
        CapeProjection.skinTextureReady("nclskins:test/raw", raw, nativeTexture);
        try (var registration = CapeProjection.installEvents(TestCapeProjection.events())) {
            assertTrue(registration.active());
            var a = CapeProjection.effectiveSkin(world, world, "nclskins:test/raw", SkinModel.CLASSIC, false);
            raw[0] = 0;
            int[] copy = a.pixels();
            assertEquals(0x00123453, copy[0]);
            copy[0] = 0;
            assertEquals(0x00123453, a.pixels()[0]);
            assertSame(a, CapeProjection.effectiveSkin(world, world, a.location(), a.model(), false));
            AtomicInteger retired = new AtomicInteger();
            a.onRetirement(retired::incrementAndGet);
            var draft = CapeProjection.effectiveSkin(preview, world, a.location(), SkinModel.SLIM, true);
            assertTrue(draft.preview());
            assertEquals(world, draft.subject());
            var b = CapeProjection.effectiveSkin(world, world, "minecraft:textures/default.png", SkinModel.CLASSIC, false);
            assertFalse(a.active());
            assertTrue(b.active());
            assertTrue(draft.active());
            assertEquals(1, retired.get());
            CapeProjection.retireSkin(preview);
            assertFalse(draft.active());
            assertTrue(b.active());
            CapeProjection.worldChanged();
            assertFalse(b.active());
            assertNull(CapeProjection.skinSource(world));
            assertFalse(changes.get(0).active());
            assertTrue(changes.stream().anyMatch(CapeProjection.SkinChange::active));
        }
        assertTrue(changes.stream().noneMatch(CapeProjection.SkinChange::active));
    }

    @Test void closedConnectionCannotRefreshNewConnectionAndExpiredConsumersUnsubscribe() throws Exception {
        UUID id = UUID.randomUUID();
        var old = CapeProjection.installEvents(TestCapeProjection.events());
        var source = CapeProjection.effectiveSkin(id, id, "nclskins:a", SkinModel.CLASSIC, false);
        AtomicInteger calls = new AtomicInteger();
        source.onRetirement(calls::incrementAndGet).close();
        var delayed = new CapeProjection.SkinChange(id, old);
        try (var next = CapeProjection.installEvents(TestCapeProjection.events())) {
            assertTrue(next.active());
            assertFalse(delayed.active());
            assertFalse(source.active());
            assertEquals(0, calls.get());
            var replacement = CapeProjection.effectiveSkin(id, id, "nclskins:b", SkinModel.SLIM, false);
            old.close();
            assertTrue(replacement.active());
            assertSame(replacement, CapeProjection.skinSource(id));
        }
    }

    @Test void textureRevisionRefreshesOnlyItsPlayerAndLookupSkipsSyntheticIdentity() {
        UUID id = UUID.randomUUID();
        UUID preview = UUID.randomUUID();
        AtomicInteger lookups = new AtomicInteger();
        CapeProjection.skinLookup(key -> lookups.incrementAndGet());
        try (var registration = CapeProjection.installEvents(TestCapeProjection.events())) {
            assertTrue(registration.active());
            var original = CapeProjection.effectiveSkin(id, id, "nclskins:raw", SkinModel.CLASSIC, false);
            var draft = CapeProjection.effectiveSkin(preview, id, "nclskins:draft", SkinModel.CLASSIC, true);
            Object texture = new Object();
            CapeProjection.skinTextureReady(original.location(), new int[4096], texture);
            var ready = CapeProjection.effectiveSkin(id, id, original.location(), original.model(), false);
            assertFalse(original.active());
            assertNotNull(ready.pixels());
            assertTrue(draft.active());
            assertSame(draft, CapeProjection.findSkinSource(preview));
            assertEquals(0, lookups.get());
            CapeProjection.findSkinSource(id);
            assertEquals(1, lookups.get());
        }
    }

    @Test void consumerAndActorBudgetsStayBoundedAndReclaimRetiredSlots() throws Exception {
        try (var registration = CapeProjection.installEvents(TestCapeProjection.events())) {
            assertTrue(registration.active());
            UUID id = UUID.randomUUID();
            var source = CapeProjection.effectiveSkin(id, id, "nclskins:budget", SkinModel.CLASSIC, false);
            var subscriptions = new ArrayList<AutoCloseable>();
            for (int i = 0; i < 64; i++) subscriptions.add(source.onRetirement(() -> {}));
            assertThrows(IllegalStateException.class, () -> source.onRetirement(() -> {}));
            subscriptions.remove(0).close();
            assertDoesNotThrow(() -> source.onRetirement(() -> {}));
            for (int i = 0; i < 512; i++) {
                UUID actor = UUID.randomUUID();
                assertNotNull(CapeProjection.effectiveSkin(actor, actor, "nclskins:budget", SkinModel.CLASSIC, false));
            }
            UUID excess = UUID.randomUUID();
            assertNull(CapeProjection.effectiveSkin(excess, excess, "nclskins:budget", SkinModel.CLASSIC, false));
            CapeProjection.retireSkin(id);
            assertNotNull(CapeProjection.effectiveSkin(excess, excess, "nclskins:budget", SkinModel.CLASSIC, false));
        }
    }

    @Test void retirementFailureStillClosesEveryConsumerAndActor() {
        var registration = CapeProjection.installEvents(TestCapeProjection.events());
        UUID first = UUID.randomUUID(), second = UUID.randomUUID();
        var a = CapeProjection.effectiveSkin(first, first, "nclskins:a", SkinModel.CLASSIC, false);
        var b = CapeProjection.effectiveSkin(second, second, "nclskins:b", SkinModel.CLASSIC, false);
        AtomicInteger closed = new AtomicInteger();
        a.onRetirement(() -> { throw new AssertionError("injected cleanup failure"); });
        a.onRetirement(closed::incrementAndGet);
        b.onRetirement(closed::incrementAndGet);
        assertThrows(AssertionError.class, registration::close);
        assertEquals(2, closed.get());
        assertFalse(a.active());
        assertFalse(b.active());
        assertFalse(registration.active());
        assertNull(CapeProjection.skinSource(first));
    }

    @Test void queuedRefreshFromAnEarlierSkinCannotInvalidateItsReplacementInTheSameConnection() {
        var changes = new ArrayList<CapeProjection.SkinChange>();
        CapeProjection.skinModels(changes::add);
        UUID id = UUID.randomUUID();
        try (var registration = CapeProjection.installEvents(TestCapeProjection.events())) {
            assertTrue(registration.active());
            CapeProjection.effectiveSkin(id, id, "nclskins:a", SkinModel.CLASSIC, false);
            CapeProjection.retireSkin(id);
            var b = CapeProjection.effectiveSkin(id, id, "nclskins:b", SkinModel.SLIM, false);
            assertFalse(changes.get(0).active());
            assertFalse(changes.get(1).active());
            assertTrue(changes.get(2).active());
            assertSame(b, changes.get(2).source());
        }
    }
}

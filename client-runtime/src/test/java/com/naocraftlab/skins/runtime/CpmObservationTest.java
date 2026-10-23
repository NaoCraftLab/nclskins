package com.naocraftlab.skins.runtime;

import com.naocraftlab.skins.client.SkinModel;
import com.naocraftlab.skins.core.provider.BuiltinProvider;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CpmObservationTest {
    private static final UUID PLAYER = new UUID(0, 71);

    @Test void auxiliaryMaterialLookupPreservesAuthoritativeInputsAndEmptyReplacesThem() {
        var demands = new java.util.concurrent.atomic.AtomicInteger();
        try (var registration = CapeProjection.installEvents(new CapeProjection.Events() {
            public void trackedPlayer(UUID id, String name) {}
            public void playerInfoUpdated(UUID id, String name) {}
            public void untrackedPlayer(UUID id) {}
            public void worldChanged() {}
            public void worldEntered() {}
            public void capeFrameDemand(UUID id, String name, BuiltinProvider provider) { demands.incrementAndGet(); }
        })) {
            var source = CapeProjection.effectiveSkin(PLAYER, PLAYER, "fixture:material", SkinModel.CLASSIC, false);
            CapeProjection.cpmObservation(source, "fixture", new CpmAccessoryObservation(1,
                    CpmAccessoryObservation.Availability.CONFIRMED, true, true));
            var identity = new CapeProjection.Identity(PLAYER, "fixture");
            registration.publish(new CapeProjection.Snapshot(List.of(BuiltinProvider.MINECRAFT, BuiltinProvider.CPM),
                    Map.of(), Map.of(), Map.of(), Map.of(), Map.of(identity, CapeProjection.cpmCandidate(source)),
                    null, null, null));
            var ordinary = new CapeProjection.Candidate("fixture:official", null, false, true);
            assertEquals(BuiltinProvider.MINECRAFT,
                    CapeProjection.resolve(PLAYER, "fixture", null, ordinary, false).provider());
            assertFalse(CapeProjection.cpmWins(source));
            var material = CapeProjection.resolveSelf(PLAYER, "fixture")
                    .orElseGet(() -> CapeProjection.material(PLAYER, "fixture", false));
            assertFalse(CapeProjection.cpmWins(source), "auxiliary glint query must not replace native inputs");
            assertEquals(BuiltinProvider.MINECRAFT, material.provider());
            assertEquals("fixture:official", material.capeLocation());
            assertTrue(material.capeGlint());
            assertEquals(1, demands.get(), "material query is read-only");
            assertEquals(BuiltinProvider.CPM,
                    CapeProjection.resolve(PLAYER, "fixture", null, null, false).provider());
            assertTrue(CapeProjection.cpmWins(source), "authoritative empty must discard the old official cape");
            assertEquals(BuiltinProvider.CPM, CapeProjection.material(PLAYER, "fixture", false).provider());
            assertNull(CapeProjection.material(PLAYER, "fixture", false).capeLocation());
            assertEquals(2, demands.get());
        }
    }

    @Test void previewCapabilityCannotBorrowWorldOrPreviousDraftEvidence() {
        try (var registration = CapeProjection.installEvents(TestCapeProjection.events())) {
            assertTrue(registration.active());
            var world = CapeProjection.effectiveSkin(PLAYER, PLAYER, "fixture:a", SkinModel.CLASSIC, false);
            CapeProjection.cpmObservation(world, "fixture", new CpmAccessoryObservation(1,
                    CpmAccessoryObservation.Availability.CONFIRMED, true, true));
            var live = com.naocraftlab.skins.client.PreviewRenderer.LiveReason.AVAILABLE;
            assertFalse(CapeProjection.previewAccessories(PLAYER, "fixture:a", SkinModel.CLASSIC, live).present());
            var id = new UUID(0, 73);
            var draft = CapeProjection.effectiveSkin(id, PLAYER, "fixture:a", SkinModel.CLASSIC, true);
            assertFalse(CapeProjection.previewAccessories(id, "fixture:a", SkinModel.CLASSIC, live).canInspect());
            CapeProjection.cpmObservation(draft, "fixture", new CpmAccessoryObservation(1,
                    CpmAccessoryObservation.Availability.CONFIRMED, false, true));
            var current = CapeProjection.previewAccessories(id, "fixture:a", SkinModel.CLASSIC, live);
            assertTrue(current.canInspect());
            assertTrue(current.elytra());
            assertFalse(current.cape());
            assertFalse(CapeProjection.previewAccessories(id, "fixture:b", SkinModel.CLASSIC, live).present());
            var baked = CapeProjection.previewAccessories(id, "fixture:a", SkinModel.CLASSIC,
                    com.naocraftlab.skins.client.PreviewRenderer.LiveReason.LIVE_UNAVAILABLE);
            assertTrue(baked.present());
            assertFalse(baked.canInspect());
            CapeProjection.retireSkin(id);
            assertFalse(CapeProjection.previewAccessories(id, "fixture:a", SkinModel.CLASSIC, live).present());
        }
    }

    @Test void sourceAndModelGenerationFencePublishedCandidates() {
        try (var registration = CapeProjection.installEvents(TestCapeProjection.events())) {
            assertTrue(registration.active());
            var source = CapeProjection.effectiveSkin(PLAYER, PLAYER, "fixture:a", SkinModel.CLASSIC, false);
            assertEquals(CpmAccessoryObservation.Availability.UNKNOWN, source.accessories().availability());
            var confirmed = new CpmAccessoryObservation(1, CpmAccessoryObservation.Availability.CONFIRMED, false, true);
            assertTrue(CapeProjection.cpmObservation(source, "fixture", confirmed));
            var candidate = CapeProjection.cpmCandidate(source);
            var id = new CapeProjection.Identity(PLAYER, "fixture");
            var snapshot = new CapeProjection.Snapshot(List.of(BuiltinProvider.CPM, BuiltinProvider.MINECRAFT),
                    Map.of(), Map.of(), Map.of(), Map.of(), Map.of(id, candidate), null, null, null);
            var ordinary = new CapeProjection.Candidate("fixture:cape", null, false);
            assertEquals(BuiltinProvider.CPM, EffectiveCapeResolver.resolve(snapshot, PLAYER, "fixture", null, ordinary, false).provider());
            assertTrue(CapeProjection.cpmObservation(source, "fixture", new CpmAccessoryObservation(2, CpmAccessoryObservation.Availability.PENDING, false, false)));
            assertFalse(CapeProjection.cpmObservation(source, "fixture", confirmed));
            assertEquals(BuiltinProvider.MINECRAFT, EffectiveCapeResolver.resolve(snapshot, PLAYER, "fixture", null, ordinary, false).provider());
            CapeProjection.effectiveSkin(PLAYER, PLAYER, "fixture:b", SkinModel.CLASSIC, false);
            assertFalse(CapeProjection.cpmObservation(source, "fixture", new CpmAccessoryObservation(3, CpmAccessoryObservation.Availability.CONFIRMED, true, true)));
            assertNull(CapeProjection.cpmCandidate(source));
        }
    }

    @Test void previewAndWorldDoNotShareObservationsAndCloseCannotReviveEither() {
        var registration = CapeProjection.installEvents(TestCapeProjection.events());
        var world = CapeProjection.effectiveSkin(PLAYER, PLAYER, "fixture:a", SkinModel.CLASSIC, false);
        var preview = CapeProjection.effectiveSkin(new UUID(0, 72), PLAYER, "fixture:a", SkinModel.CLASSIC, true);
        var confirmed = new CpmAccessoryObservation(1, CpmAccessoryObservation.Availability.CONFIRMED, true, false);
        CapeProjection.cpmObservation(world, "fixture", confirmed);
        assertNull(CapeProjection.cpmCandidate(preview));
        CapeProjection.cpmObservation(preview, "preview", new CpmAccessoryObservation(1, CpmAccessoryObservation.Availability.CONFIRMED, false, true));
        assertTrue(CapeProjection.cpmCandidate(world).nativeCape());
        assertTrue(CapeProjection.cpmCandidate(preview).nativeElytra());
        CapeProjection.retirePreviewSkins();
        assertNull(CapeProjection.cpmCandidate(preview));
        assertNotNull(CapeProjection.cpmCandidate(world));
        registration.close();
        assertFalse(CapeProjection.cpmObservation(world, "fixture", confirmed));
        assertNull(CapeProjection.cpmCandidate(world));
    }

    @Test void replacedObserverAndEarlierModelCannotRepublishAndPolicyInvalidationIsImmediate() {
        try (var registration = CapeProjection.installEvents(TestCapeProjection.events())) {
            assertTrue(registration.active());
            var source = CapeProjection.effectiveSkin(PLAYER, PLAYER, "fixture:a", SkinModel.CLASSIC, false);
            var first = CapeProjection.cpmObserver(source, "fixture");
            first.publish(2, CpmAccessoryObservation.Availability.CONFIRMED, true, false);
            var candidate = CapeProjection.cpmCandidate(source);
            assertNotNull(candidate);
            assertFalse(first.publish(1, CpmAccessoryObservation.Availability.CONFIRMED, false, true));
            var second = CapeProjection.cpmObserver(source, "fixture");
            assertFalse(candidate.present());
            assertFalse(first.publish(3, CpmAccessoryObservation.Availability.CONFIRMED, true, true));
            assertTrue(second.publish(1, CpmAccessoryObservation.Availability.CONFIRMED, false, true));
            candidate = CapeProjection.cpmCandidate(source);
            CapeProjection.invalidateCpmObservations();
            assertFalse(candidate.present());
            assertTrue(second.publish(1, CpmAccessoryObservation.Availability.BLOCKED, false, false));
            assertNull(CapeProjection.cpmCandidate(source));
            assertTrue(second.publish(1, CpmAccessoryObservation.Availability.CONFIRMED, false, true));
        }
    }

    @Test void unavailableIsNotEmptyAndCannotCarryComponents() {
        for (var availability : CpmAccessoryObservation.Availability.values()) {
            var observation = new CpmAccessoryObservation(1, availability, false, false);
            assertEquals(availability == CpmAccessoryObservation.Availability.CONFIRMED, observation.empty());
            if (availability != CpmAccessoryObservation.Availability.CONFIRMED)
                assertThrows(IllegalArgumentException.class, () -> new CpmAccessoryObservation(1, availability, true, false));
        }
    }
}

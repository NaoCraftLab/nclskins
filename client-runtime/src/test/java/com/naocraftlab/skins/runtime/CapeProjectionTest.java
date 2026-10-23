package com.naocraftlab.skins.runtime;

import com.naocraftlab.skins.core.provider.BuiltinProvider;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CapeProjectionTest {
    @Test
    void minecraftCapesMaterialFollowsExactSelfObserverAndWinningFrame() {
        UUID id = UUID.fromString("12345678-1234-1234-1234-123456789abc");
        var identity = new CapeProjection.Identity(id, "Player");
        var glint = new CapeProjection.Candidate("nclskins:frame0", "nclskins:frame0", true, true);
        var plain = new CapeProjection.Candidate("nclskins:offline", null, false);
        try {
            TestCapeProjection.publish(new CapeProjection.Snapshot(
                    List.of(BuiltinProvider.MINECRAFTCAPES, BuiltinProvider.OFFLINE),
                    Map.of(), Map.of(), Map.of(), Map.of(identity, glint), identity, plain, null));
            for (boolean self : new boolean[] {true, false}) {
                var selected = CapeProjection.resolve(id, "Player", plain, null, self);
                assertEquals("nclskins:frame0", selected.capeLocation());
                assertTrue(selected.capeGlint());
                assertEquals(BuiltinProvider.MINECRAFTCAPES, selected.provider());
            }
            assertFalse(CapeProjection.resolve(id, "Other", plain, null, false).capeGlint());
            TestCapeProjection.publish(new CapeProjection.Snapshot(
                    List.of(BuiltinProvider.OFFLINE, BuiltinProvider.MINECRAFTCAPES),
                    Map.of(), Map.of(), Map.of(), Map.of(identity, glint), identity, plain, null));
            var selected = CapeProjection.resolve(id, "Player", plain, null, true);
            assertEquals("nclskins:offline", selected.capeLocation());
            assertFalse(selected.capeGlint());
        } finally {
            TestCapeProjection.clear();
        }
    }

    @Test
    void oldOwnerCannotPublishClearOrDetachNewOwner() {
        var identity = new CapeProjection.Identity(UUID.randomUUID(), "Player");
        var candidate = new CapeProjection.Candidate("cape:new", null, false);
        var old = CapeProjection.installEvents(TestCapeProjection.events());
        var current = CapeProjection.installEvents(TestCapeProjection.events());
        current.publish(new CapeProjection.Snapshot(List.of(BuiltinProvider.SNEAKY),
                Map.of(), Map.of(), Map.of(identity, candidate), null, null, null));
        old.publish(CapeProjection.Snapshot.empty());
        old.close();
        old.close();
        assertTrue(current.active());
        assertEquals("cape:new", CapeProjection.resolve(identity.profileId(), identity.canonicalName(),
                null, null, false).capeLocation());
        current.close();
        assertNull(CapeProjection.resolve(identity.profileId(), identity.canonicalName(),
                null, null, false).capeLocation());
    }

    @Test
    void sneakyCandidateObeysCapeOrderAndDoesNotReplaceOfficialCape() {
        UUID id = UUID.randomUUID();
        var identity = new CapeProjection.Identity(id, "Player");
        var sneaky = new CapeProjection.Candidate("nclskins:sneaky", null, false);
        var official = new CapeProjection.Candidate("minecraft:official", null, false);
        try {
            TestCapeProjection.publish(new CapeProjection.Snapshot(
                    List.of(BuiltinProvider.MINECRAFT, BuiltinProvider.SNEAKY), Map.of(), Map.of(),
                    Map.of(identity, sneaky), null, null, null));
            assertEquals(BuiltinProvider.MINECRAFT,
                    CapeProjection.resolve(id, "Player", null, official, false).provider());
            assertEquals("minecraft:official",
                    CapeProjection.resolve(id, "Player", null, official, false).capeLocation());
            TestCapeProjection.publish(new CapeProjection.Snapshot(
                    List.of(BuiltinProvider.SNEAKY, BuiltinProvider.MINECRAFT), Map.of(), Map.of(),
                    Map.of(identity, sneaky), null, null, null));
            assertEquals(BuiltinProvider.SNEAKY,
                    CapeProjection.resolve(id, "Player", null, official, false).provider());
            assertEquals(BuiltinProvider.MINECRAFT,
                    CapeProjection.resolve(id, "Other", null, official, false).provider());
            TestCapeProjection.publish(new CapeProjection.Snapshot(
                    List.of(BuiltinProvider.MINECRAFT), Map.of(), Map.of(), Map.of(identity, sneaky),
                    null, null, null));
            assertEquals(BuiltinProvider.MINECRAFT,
                    CapeProjection.resolve(id, "Player", null, official, false).provider());
            assertEquals("minecraft:official",
                    CapeProjection.resolve(id, "Player", null, official, false).capeLocation());
        } finally {
            TestCapeProjection.clear();
        }
    }

    @Test
    void skinMcCandidateUsesSharedPriorityAndExactIdentity() {
        UUID id = UUID.fromString("12345678-1234-1234-1234-123456789abc");
        var identity = new CapeProjection.Identity(id, "Player");
        var optifine = new CapeProjection.Candidate("optifine", null, false);
        var skinmc = new CapeProjection.Candidate("skinmc", "skinmc", true);
        try {
            TestCapeProjection.publish(new CapeProjection.Snapshot(
                    List.of(BuiltinProvider.SKINMC, BuiltinProvider.OPTIFINE, BuiltinProvider.MINECRAFT),
                    Map.of(identity, optifine), Map.of(identity, skinmc), null, null, null));
            var winner = CapeProjection.resolve(id, "Player", null, null, false);
            assertEquals(BuiltinProvider.SKINMC, winner.provider());
            assertEquals("skinmc", winner.capeLocation());
            assertEquals("skinmc", winner.elytraLocation());
            assertNull(CapeProjection.resolve(id, "Renamed", null, null, false).capeLocation());

            TestCapeProjection.publish(new CapeProjection.Snapshot(
                    List.of(BuiltinProvider.OPTIFINE, BuiltinProvider.SKINMC, BuiltinProvider.MINECRAFT),
                    Map.of(identity, optifine), Map.of(identity, skinmc), null, null, null));
            assertEquals(BuiltinProvider.OPTIFINE,
                    CapeProjection.resolve(id, "Player", null, null, false).provider());
        } finally {
            TestCapeProjection.clear();
        }
    }
}

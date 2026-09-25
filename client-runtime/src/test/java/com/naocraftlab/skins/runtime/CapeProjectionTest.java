package com.naocraftlab.skins.runtime;

import com.naocraftlab.skins.core.provider.BuiltinProvider;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class CapeProjectionTest {
    @Test
    void sneakyCandidateObeysCapeOrderAndDoesNotReplaceOfficialCape() {
        UUID id = UUID.randomUUID();
        var identity = new CapeProjection.Identity(id, "Player");
        var sneaky = new CapeProjection.Candidate("nclskins:sneaky", null, false);
        var official = new CapeProjection.Candidate("minecraft:official", null, false);
        try {
            CapeProjection.publish(new CapeProjection.Snapshot(
                    List.of(BuiltinProvider.MINECRAFT, BuiltinProvider.SNEAKY), Map.of(), Map.of(),
                    Map.of(identity, sneaky), null, null, null));
            assertEquals(BuiltinProvider.MINECRAFT,
                    CapeProjection.resolve(id, "Player", null, official, false).provider());
            assertEquals("minecraft:official",
                    CapeProjection.resolve(id, "Player", null, official, false).capeLocation());
            CapeProjection.publish(new CapeProjection.Snapshot(
                    List.of(BuiltinProvider.SNEAKY, BuiltinProvider.MINECRAFT), Map.of(), Map.of(),
                    Map.of(identity, sneaky), null, null, null));
            assertEquals(BuiltinProvider.SNEAKY,
                    CapeProjection.resolve(id, "Player", null, official, false).provider());
            assertEquals(BuiltinProvider.MINECRAFT,
                    CapeProjection.resolve(id, "Other", null, official, false).provider());
            CapeProjection.publish(new CapeProjection.Snapshot(
                    List.of(BuiltinProvider.MINECRAFT), Map.of(), Map.of(), Map.of(identity, sneaky),
                    null, null, null));
            assertEquals(BuiltinProvider.MINECRAFT,
                    CapeProjection.resolve(id, "Player", null, official, false).provider());
            assertEquals("minecraft:official",
                    CapeProjection.resolve(id, "Player", null, official, false).capeLocation());
        } finally {
            CapeProjection.clear();
        }
    }

    @Test
    void skinMcCandidateUsesSharedPriorityAndExactIdentity() {
        UUID id = UUID.fromString("12345678-1234-1234-1234-123456789abc");
        var identity = new CapeProjection.Identity(id, "Player");
        var optifine = new CapeProjection.Candidate("optifine", null, false);
        var skinmc = new CapeProjection.Candidate("skinmc", "skinmc", true);
        try {
            CapeProjection.publish(new CapeProjection.Snapshot(
                    List.of(BuiltinProvider.SKINMC, BuiltinProvider.OPTIFINE, BuiltinProvider.MINECRAFT),
                    Map.of(identity, optifine), Map.of(identity, skinmc), null, null, null));
            var winner = CapeProjection.resolve(id, "Player", null, null, false);
            assertEquals(BuiltinProvider.SKINMC, winner.provider());
            assertEquals("skinmc", winner.capeLocation());
            assertEquals("skinmc", winner.elytraLocation());
            assertNull(CapeProjection.resolve(id, "Renamed", null, null, false).capeLocation());

            CapeProjection.publish(new CapeProjection.Snapshot(
                    List.of(BuiltinProvider.OPTIFINE, BuiltinProvider.SKINMC, BuiltinProvider.MINECRAFT),
                    Map.of(identity, optifine), Map.of(identity, skinmc), null, null, null));
            assertEquals(BuiltinProvider.OPTIFINE,
                    CapeProjection.resolve(id, "Player", null, null, false).provider());
        } finally {
            CapeProjection.clear();
        }
    }
}

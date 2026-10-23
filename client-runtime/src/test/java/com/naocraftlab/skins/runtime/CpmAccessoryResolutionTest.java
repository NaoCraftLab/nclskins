package com.naocraftlab.skins.runtime;

import com.naocraftlab.skins.core.provider.BuiltinProvider;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CpmAccessoryResolutionTest {
    @Test
    void offlinePrecedenceRequiresSelfEvenWithTheSamePublicCape() {
        var self = new CapeProjection.Identity(new UUID(0, 3), "self");
        var foreign = new CapeProjection.Identity(new UUID(0, 4), "foreign");
        var offline = new CapeProjection.Candidate("fixture:shared-cape", null, false);
        var publicCape = new CapeProjection.Candidate("fixture:shared-cape", null, false, true, false, false);
        var snapshot = new CapeProjection.Snapshot(List.of(BuiltinProvider.OFFLINE, BuiltinProvider.MINECRAFTCAPES),
                Map.of(), Map.of(), Map.of(), Map.of(self, publicCape, foreign, publicCape), self, offline, null);
        var local = EffectiveCapeResolver.resolve(snapshot, self.profileId(), self.canonicalName(), null, null, true);
        assertEquals(BuiltinProvider.OFFLINE, local.provider());
        assertEquals(offline.capeLocation(), local.capeLocation());
        for (var subject : List.of(self, foreign)) {
            var remote = EffectiveCapeResolver.resolve(snapshot, subject.profileId(), subject.canonicalName(), offline, null, false);
            assertEquals(BuiltinProvider.MINECRAFTCAPES, remote.provider());
            assertEquals(local.capeLocation(), remote.capeLocation());
            assertTrue(remote.capeGlint());
        }
        assertNull(EffectiveCapeResolver.resolve(snapshot, new UUID(0, 5), "untracked", offline, null, false).provider());
        assertEquals(BuiltinProvider.OFFLINE, EffectiveCapeResolver.resolve(snapshot,
                self.profileId(), self.canonicalName(), null, null, true).provider());
    }

    @Test
    void nativeElytraWinsAtomicallyWithoutBorrowingLowerCape() {
        var id = new CapeProjection.Identity(new UUID(0, 1), "fixture");
        var cpm = new CapeProjection.Candidate(null, null, true, false, false, true);
        var normal = new CapeProjection.Candidate("fixture:cape", "fixture:elytra", true);
        var snapshot = new CapeProjection.Snapshot(
                List.of(BuiltinProvider.CPM, BuiltinProvider.MINECRAFT),
                Map.of(), Map.of(), Map.of(), Map.of(), Map.of(id, cpm), null, null, null);
        var result = EffectiveCapeResolver.resolve(snapshot, id.profileId(), id.canonicalName(),
                null, normal, false);
        assertEquals(BuiltinProvider.CPM, result.provider());
        assertNull(result.capeLocation());
        assertNull(result.elytraLocation());
        assertTrue(result.nativeElytra());
        assertEquals(BuiltinProvider.MINECRAFT, EffectiveCapeResolver.resolve(snapshot,
                new UUID(0, 2), "other", null, normal, false).provider());
    }
}

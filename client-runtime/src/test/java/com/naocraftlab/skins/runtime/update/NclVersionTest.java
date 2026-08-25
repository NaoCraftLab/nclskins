package com.naocraftlab.skins.runtime.update;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class NclVersionTest {
    @Test
    void stableBetaAndAlphaUseSemverPrecedence() {
        List<NclVersion> versions = NclVersion.parseUnique(List.of(
                "2.0.0",
                "1.0.0",
                "1.0.0-beta.10",
                "1.0.0-beta.2",
                "1.0.0-alpha.20",
                "1.0.0-alpha.3"));

        assertEquals(List.of(
                "1.0.0-alpha.3",
                "1.0.0-alpha.20",
                "1.0.0-beta.2",
                "1.0.0-beta.10",
                "1.0.0",
                "2.0.0"), versions.stream().sorted().map(NclVersion::toString).toList());
    }

    @Test
    void numericComponentsDoNotOverflow() {
        NclVersion huge = NclVersion.parse(
                "999999999999999999999999.0.0-beta.999999999999999999999999");

        assertTrue(huge.isNewerThan(NclVersion.parse("2.0.0")));
    }

    @Test
    void onlyTheThreeExactVersionShapesAreAccepted() {
        List.of(
                "v1.0.0",
                "01.0.0",
                "1.00.0",
                "1.0.00",
                "1.0",
                "1.0.0-alpha",
                "1.0.0-alpha.0",
                "1.0.0-beta.01",
                "1.0.0-rc.1",
                "1.0.0+build",
                " 1.0.0",
                "1.0.0 ").forEach(value ->
                assertThrows(IllegalArgumentException.class, () -> NclVersion.parse(value)));
    }

    @Test
    void duplicateVersionsAreRejected() {
        assertThrows(IllegalArgumentException.class, () ->
                NclVersion.parseUnique(List.of("1.0.0-beta.2", "1.0.0-beta.2")));
    }

    @Test
    void equalAndAheadCurrentVersionsAreNotUpdates() {
        NclVersion current = NclVersion.parse("1.0.0-beta.3");

        assertFalse(NclVersion.parse("1.0.0-beta.3").isNewerThan(current));
        assertFalse(NclVersion.parse("1.0.0-beta.2").isNewerThan(current));
        assertTrue(NclVersion.parse("1.0.0").isNewerThan(current));
    }

    @Test
    void channelPreferenceAllowsOnlyItsDeclaredStabilityRange() {
        assertTrue(UpdateChannel.RELEASE.allows(UpdateChannel.RELEASE));
        assertFalse(UpdateChannel.RELEASE.allows(UpdateChannel.BETA));
        assertTrue(UpdateChannel.BETA.allows(UpdateChannel.RELEASE));
        assertTrue(UpdateChannel.BETA.allows(UpdateChannel.BETA));
        assertFalse(UpdateChannel.BETA.allows(UpdateChannel.ALPHA));
        assertTrue(UpdateChannel.ALPHA.allows(UpdateChannel.RELEASE));
        assertTrue(UpdateChannel.ALPHA.allows(UpdateChannel.BETA));
        assertTrue(UpdateChannel.ALPHA.allows(UpdateChannel.ALPHA));
    }
}

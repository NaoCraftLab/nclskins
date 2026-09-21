package com.naocraftlab.skins.core.provider;

import org.junit.jupiter.api.Test;

import java.util.List;

import static com.naocraftlab.skins.core.provider.BuiltinProvider.MINECRAFT;
import static com.naocraftlab.skins.core.provider.BuiltinProvider.OFFLINE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProviderChannelTest {
    @Test
    void bootstrapPreservesKnownOfflineAndCannotOverwriteAnIntent() {
        var channel = new ProviderChannel<String>(java.util.List.of(BuiltinProvider.OFFLINE, BuiltinProvider.MINECRAFT),
                ProviderObservation.observed("offline"), ProviderObservation.unknown(), 0, 0, null,
                new ProviderDelivery(0, 0, ProviderDelivery.Status.IDLE));
        var bootstrapped = channel.bootstrap(1, "minecraft");
        assertEquals("offline", bootstrapped.offline().value());
        assertEquals("minecraft", bootstrapped.minecraft().value());
        assertEquals(ProviderDelivery.Status.CONFIRMED, bootstrapped.minecraftDelivery().status());
        assertEquals(bootstrapped, bootstrapped.bootstrap(2, "late"));
        var selected = channel.select(1, "selected");
        assertEquals(selected, selected.bootstrap(2, "late"));
    }

    @Test
    void everyOrderAndObservationCombinationResolvesFirstPresent() {
        List<List<BuiltinProvider>> orders = List.of(List.of(), List.of(OFFLINE), List.of(MINECRAFT),
                List.of(OFFLINE, MINECRAFT), List.of(MINECRAFT, OFFLINE));
        List<ProviderObservation<String>> observations = List.of(ProviderObservation.unknown(),
                ProviderObservation.observed(null), ProviderObservation.observed("value"));
        for (List<BuiltinProvider> order : orders) {
            for (ProviderObservation<String> offline : observations) {
                for (ProviderObservation<String> minecraft : observations) {
                    ProviderChannel<String> channel = new ProviderChannel<>(order, offline, minecraft,
                            0, 0, null, new ProviderDelivery(0, 0, ProviderDelivery.Status.IDLE));
                    BuiltinProvider expected = order.stream()
                            .filter(provider -> (provider == OFFLINE ? offline : minecraft).value() != null)
                            .findFirst().orElse(null);
                    assertEquals(expected, channel.resolve().map(ProviderChannel.Resolved::provider).orElse(null));
                }
            }
        }
    }

    @Test
    void pendingSelectionAndObservedMinecraftAreIndependent() {
        ProviderChannel<String> channel = ProviderChannel.<String>initial().observeMinecraft("A")
                .disable(OFFLINE).select(1, "B");
        assertEquals("B", channel.desired());
        assertEquals("A", channel.resolve().orElseThrow().value());
        channel = channel.settle(channel.minecraftDelivery(), ProviderDelivery.Status.CONFIRMED, "B");
        assertEquals("B", channel.resolve().orElseThrow().value());
    }

    @Test
    void revisePreservesUnchangedDeliveryAndUpdatesLatestLocalIntent() {
        ProviderDelivery delivery = new ProviderDelivery(3, 7, ProviderDelivery.Status.CONFIRMED);
        ProviderChannel<String> channel = new ProviderChannel<>(
                List.of(OFFLINE, MINECRAFT),
                ProviderObservation.observed("offline"),
                ProviderObservation.observed("remote"),
                7,
                3,
                "remote",
                delivery,
                "offline");

        ProviderChannel<String> revised = channel.revise(4, "offline-new", "remote", false);

        assertEquals(delivery, revised.minecraftDelivery());
        assertEquals("offline-new", revised.offline().value());
        assertEquals("offline-new", revised.offlineDesired());
        assertEquals("remote", revised.desired());
        assertEquals("remote", revised.minecraft().value());
        assertEquals(4, revised.intentRevision());
    }

    @Test
    void activatingAfterOfflineOnlySelectionDeliversLatestEvenWithoutNewIntent() {
        ProviderChannel<String> channel = ProviderChannel.<String>initial().disable(MINECRAFT)
                .select(1, "A").select(2, "B").enable(MINECRAFT);
        assertEquals("B", channel.offline().value());
        assertEquals("B", channel.desired());
        assertEquals(2, channel.minecraftDelivery().intentRevision());
        assertEquals(ProviderDelivery.Status.PENDING, channel.minecraftDelivery().status());
        assertEquals(2, channel.intentRevision());
    }

    @Test
    void reactivationFencesEarlierCompletionAndPreservesUnknownOutcome() {
        ProviderChannel<String> channel = ProviderChannel.<String>initial().observeMinecraft("A")
                .select(1, "B");
        channel = channel.settle(channel.minecraftDelivery(), ProviderDelivery.Status.ATTEMPTING, null);
        ProviderDelivery inFlight = channel.minecraftDelivery();
        channel = channel.disable(MINECRAFT).enable(MINECRAFT);
        assertEquals(ProviderDelivery.Status.UNKNOWN, channel.minecraftDelivery().status());
        assertSame(channel, channel.settle(inFlight, ProviderDelivery.Status.CONFIRMED, "B"));
        assertEquals("A", channel.minecraft().value());
    }

    @Test
    void resetFallsThroughWhileOfficialResetWaits() {
        ProviderChannel<String> channel = ProviderChannel.<String>initial().observeMinecraft("A")
                .select(1, "B").select(2, null);
        assertTrue(channel.offline().known());
        assertNull(channel.offline().value());
        assertEquals(MINECRAFT, channel.resolve().orElseThrow().provider());
        assertEquals("A", channel.resolve().orElseThrow().value());
        assertTrue(channel.settle(channel.minecraftDelivery(), ProviderDelivery.Status.CONFIRMED, null)
                .resolve().isEmpty());
    }

    @Test
    void disabledValuesSurviveButDoNotParticipate() {
        ProviderChannel<String> channel = ProviderChannel.<String>initial().observeMinecraft("A")
                .select(1, "B").disable(OFFLINE).disable(MINECRAFT);
        assertTrue(channel.resolve().isEmpty());
        assertEquals("B", channel.offline().value());
        assertEquals("A", channel.minecraft().value());
        assertSame(channel, channel.observeMinecraft("unrequested"));
    }

    @Test
    void reorderChangesWinnerWithoutAssigningDelivery() {
        ProviderChannel<String> channel = ProviderChannel.<String>initial().observeMinecraft("A")
                .select(1, "B");
        ProviderDelivery delivery = channel.minecraftDelivery();
        ProviderChannel<String> reordered = channel.move(MINECRAFT, -1);
        assertEquals("A", reordered.resolve().orElseThrow().value());
        assertEquals(delivery, reordered.minecraftDelivery());
        assertEquals(channel.offline(), reordered.offline());
        assertEquals(channel.minecraft(), reordered.minecraft());
    }

    @Test
    void freshObservationDoesNotMirrorOverExistingOfflineSelection() {
        ProviderChannel<String> channel = ProviderChannel.<String>initial().observeMinecraft("A")
                .select(1, "B").observeMinecraft(null);
        assertEquals("B", channel.offline().value());
        assertTrue(channel.minecraft().known());
        assertNull(channel.minecraft().value());
        assertEquals("B", channel.resolve().orElseThrow().value());
    }

    @Test
    void canonicalOrderIsImmutableAndActivationIsIdempotent() {
        ProviderChannel<String> channel = ProviderChannel.initial();
        assertEquals(List.of(OFFLINE, MINECRAFT), channel.order());
        assertThrows(UnsupportedOperationException.class, () -> channel.order().clear());
        assertSame(channel, channel.enable(OFFLINE));
        assertSame(channel, channel.move(OFFLINE, -1));
    }

    @Test
    void staleIntentAndDuplicateProvidersAreRejected() {
        ProviderChannel<String> channel = ProviderChannel.<String>initial().select(1, "A");
        assertThrows(IllegalArgumentException.class, () -> channel.select(1, "B"));
        assertThrows(IllegalArgumentException.class, () -> new ProviderChannel<>(
                List.of(OFFLINE, OFFLINE), channel.offline(), channel.minecraft(), 0, 1, "A",
                channel.minecraftDelivery()));
    }
}

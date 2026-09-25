package com.naocraftlab.skins.core.provider;

import org.junit.jupiter.api.Test;

import java.util.List;

import static com.naocraftlab.skins.core.provider.BuiltinProvider.MINECRAFT;
import static com.naocraftlab.skins.core.provider.BuiltinProvider.OFFLINE;
import static com.naocraftlab.skins.core.provider.BuiltinProvider.OPTIFINE;
import static com.naocraftlab.skins.core.provider.BuiltinProvider.SKINMC;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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
    void applySelectionComparesConfirmedObservationInsteadOfPreviousDesired() {
        ProviderChannel<String> confirmed = ProviderChannel.<String>initial().select(1, "old");
        confirmed = confirmed.settle(confirmed.minecraftDelivery(), ProviderDelivery.Status.CONFIRMED, "observed");
        ProviderDelivery delivery = confirmed.minecraftDelivery();

        ProviderChannel<String> matching = confirmed.selectMatchingObservation(2, "local", "observed", String::equals);
        assertEquals(delivery, matching.minecraftDelivery());
        assertEquals("observed", matching.desired());
        assertEquals("local", matching.offline().value());
        assertEquals(2, matching.intentRevision());

        ProviderChannel<String> previousDesired = confirmed.selectMatchingObservation(2, "local", "old", String::equals);
        assertEquals(ProviderDelivery.Status.PENDING, previousDesired.minecraftDelivery().status());
        assertEquals(2, previousDesired.minecraftDelivery().intentRevision());
    }

    @Test
    void applySelectionRequiresCompleteActiveConfirmation() {
        ProviderChannel<String> confirmed = ProviderChannel.<String>initial().select(1, "value");
        confirmed = confirmed.settle(confirmed.minecraftDelivery(), ProviderDelivery.Status.CONFIRMED, "value");
        ProviderChannel<String> unknownObservation = new ProviderChannel<>(confirmed.order(), confirmed.offline(),
                ProviderObservation.unknown(), confirmed.configurationRevision(), confirmed.intentRevision(),
                confirmed.desired(), confirmed.minecraftDelivery(), confirmed.offlineDesired());
        ProviderChannel<String> absentObservation = confirmed.observeMinecraft(null);
        ProviderChannel<String> unknownDelivery = confirmed.settle(
                confirmed.minecraftDelivery(), ProviderDelivery.Status.UNKNOWN, "value");
        ProviderChannel<String> staleActivation = confirmed.move(MINECRAFT, -1);

        for (ProviderChannel<String> channel : List.of(unknownObservation, absentObservation,
                unknownDelivery, staleActivation)) {
            ProviderChannel<String> selected = channel.selectMatchingObservation(2, "value", "value", String::equals);
            assertNotEquals(ProviderDelivery.Status.CONFIRMED, selected.minecraftDelivery().status());
            assertEquals(2, selected.minecraftDelivery().intentRevision());
        }
        assertEquals(ProviderDelivery.Status.PENDING,
                confirmed.selectMatchingObservation(2, null, null, String::equals).minecraftDelivery().status());
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

    @Test
    void optifineIsReadOnlyCapeFallbackWithIndependentObservation() {
        var initial = AppearanceProviders.initial();
        assertEquals(List.of(OFFLINE, MINECRAFT), initial.cape().order());
        assertThrows(IllegalArgumentException.class,
                () -> initial.enable(AppearanceProviders.Component.SKIN, OPTIFINE));
        var enabled = initial.enable(AppearanceProviders.Component.CAPE, OPTIFINE);
        var cape = enabled.cape().observeOptifine(new ProviderCape("optifine", "a".repeat(64), false));
        assertTrue(cape.optifine().known());
        assertEquals(OPTIFINE, cape.resolve().orElseThrow().provider());
        assertNull(cape.desired());
        assertEquals(0, cape.minecraftDelivery().intentRevision());
        var disabled = cape.disable(OPTIFINE);
        assertTrue(disabled.resolve().isEmpty());
        assertEquals(cape.optifine(), disabled.optifine());
        assertEquals(cape.optifine(), disabled.enable(OPTIFINE).optifine());
        assertEquals(MINECRAFT, cape.disable(OFFLINE).observeMinecraft(new ProviderCape("official", null))
                .resolve().orElseThrow().provider());
    }

    @Test
    void optifineEnableDisableReorderAndAbsenceKeepCapeFallbackAndSkinIndependent() {
        var offline = new ProviderCape("offline", null, false);
        var minecraft = new ProviderCape("official", null, true);
        var optifine = new ProviderCape("optifine", "a".repeat(64), false);
        var initial = AppearanceProviders.initial();
        var base = initial.cape();
        var configured = new AppearanceProviders(initial.skin(), new ProviderChannel<>(base.order(),
                ProviderObservation.observed(offline), ProviderObservation.observed(minecraft),
                base.configurationRevision(), base.intentRevision(), base.desired(),
                base.minecraftDelivery(), base.offlineDesired(), ProviderObservation.observed(optifine)));

        assertEquals(OFFLINE, configured.cape().resolve().orElseThrow().provider());
        assertEquals(List.of(OFFLINE, MINECRAFT), configured.skin().order());

        var enabled = configured.enable(AppearanceProviders.Component.CAPE, OPTIFINE);
        assertEquals(OFFLINE, enabled.cape().resolve().orElseThrow().provider());
        var first = enabled.move(AppearanceProviders.Component.CAPE, OPTIFINE, -1)
                .move(AppearanceProviders.Component.CAPE, OPTIFINE, -1);
        assertEquals(List.of(OPTIFINE, OFFLINE, MINECRAFT), first.cape().order());
        assertEquals(OPTIFINE, first.cape().resolve().orElseThrow().provider());
        assertEquals(optifine, first.cape().optifine().value());
        assertEquals(configured.skin(), first.skin());

        var absent = new AppearanceProviders(first.skin(), first.cape().observeOptifine(null));
        assertTrue(absent.cape().optifine().known());
        assertEquals(OFFLINE, absent.cape().resolve().orElseThrow().provider());
        var withoutOffline = absent.disable(AppearanceProviders.Component.CAPE, OFFLINE);
        assertEquals(MINECRAFT, withoutOffline.cape().resolve().orElseThrow().provider());

        var disabled = first.disable(AppearanceProviders.Component.CAPE, OPTIFINE);
        assertEquals(OFFLINE, disabled.cape().resolve().orElseThrow().provider());
        assertEquals(optifine, disabled.cape().optifine().value());
        var reenabled = disabled.enable(AppearanceProviders.Component.CAPE, OPTIFINE);
        assertEquals(List.of(OFFLINE, MINECRAFT, OPTIFINE), reenabled.cape().order());
        assertEquals(OFFLINE, reenabled.cape().resolve().orElseThrow().provider());
        assertEquals(optifine, reenabled.cape().optifine().value());
    }

    @Test
    void skinmcCapeObservationIsIndependentAndKnownAbsenceFallsThrough() {
        var initial = AppearanceProviders.initial();
        assertThrows(IllegalArgumentException.class,
                () -> initial.enable(AppearanceProviders.Component.SKIN, SKINMC));
        var enabled = initial.enable(AppearanceProviders.Component.CAPE, SKINMC);
        assertTrue(!enabled.cape().skinmc().known());
        var cape = new ProviderCape("skinmc", "c".repeat(64), true);
        var observed = new AppearanceProviders(enabled.skin(), enabled.cape().observeSkinmc(cape));
        assertEquals(SKINMC, observed.cape().resolve().orElseThrow().provider());
        assertEquals(initial.skin(), observed.skin());
        var absent = new AppearanceProviders(observed.skin(), observed.cape().observeSkinmc(null)
                .observeMinecraft(new ProviderCape("official", null, false)));
        assertTrue(absent.cape().skinmc().known());
        assertEquals(OFFLINE, absent.cape().resolve().orElseThrow().provider());
        var disabled = observed.disable(AppearanceProviders.Component.CAPE, SKINMC);
        assertEquals(cape, disabled.cape().skinmc().value());
        assertEquals(cape, disabled.enable(AppearanceProviders.Component.CAPE, SKINMC)
                .cape().skinmc().value());
    }

    @Test
    void onlyReadOnlyCapeDoesNotOpenGallery() {
        var initial = AppearanceProviders.initial()
                .disable(AppearanceProviders.Component.CAPE, OFFLINE)
                .disable(AppearanceProviders.Component.CAPE, MINECRAFT)
                .enable(AppearanceProviders.Component.CAPE, OPTIFINE);
        assertTrue(!initial.galleryAvailable());
    }
}

package com.naocraftlab.skins.runtime;

import com.naocraftlab.skins.client.ClientExecutor;
import com.naocraftlab.skins.client.GameSessionTokenSource;
import com.naocraftlab.skins.client.PlayerAppearanceSink;
import com.naocraftlab.skins.client.SignedProfileResolver;
import com.naocraftlab.skins.core.png.PngValidator;
import com.naocraftlab.skins.core.provider.AppearanceProviders;
import com.naocraftlab.skins.core.provider.BuiltinProvider;
import com.naocraftlab.skins.core.storage.NclSkinsStorage;
import com.naocraftlab.skins.core.storage.TextureCache;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.nio.file.Files;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OptifineCapeCoordinatorTest {
    @TempDir Path directory;

    @Test
    void refreshSweepsEntireRosterAndDoesNotPollOnProjection() throws Exception {
        Fixture fixture = fixture();
        for (int index = 0; index < 300; index++) {
            fixture.sink.players.add(new PlayerAppearanceSink.TrackedCapePlayer(
                    UUID.nameUUIDFromBytes(("player-" + index).getBytes()), "Player_" + index));
        }
        fixture.coordinator.start();
        assertEquals(301, fixture.calls.get());
        for (int index = 0; index < 100; index++) {
            var player = fixture.sink.players.get(index);
            CapeProjection.resolve(player.profileId(), player.canonicalName(), null, null, false);
        }
        assertEquals(301, fixture.calls.get());
        fixture.coordinator.refresh();
        assertEquals(602, fixture.calls.get());
    }

    @Test
    void repeatedRefreshCoalescesAndFailureNeedsExplicitRetry() throws Exception {
        Fixture fixture = fixture();
        fixture.status = 503;
        ManualExecutor held = new ManualExecutor();
        fixture.coordinator.close();
        fixture.coordinator = new OptifineCapeCoordinator(fixture.session, fixture.storage,
                new TextureCache(fixture.storage), fixture.sink, new ImmediateClient(), held,
                fixture.reader());
        fixture.coordinator.start();
        fixture.coordinator.refresh();
        fixture.coordinator.refresh();
        while (!held.jobs.isEmpty()) held.runNext();
        assertEquals(2, fixture.calls.get());
        fixture.coordinator.trackedPlayer(fixture.session.currentSession().profileId(),
                fixture.session.currentSession().profileName());
        assertEquals(2, fixture.calls.get());
        fixture.coordinator.refresh();
        while (!held.jobs.isEmpty()) held.runNext();
        assertEquals(3, fixture.calls.get());
    }

    @Test
    void worldAndAccountChangeCancelOldJobsAndCloseClearsProjection() throws Exception {
        Fixture fixture = fixture();
        fixture.status = 200;
        ManualExecutor held = new ManualExecutor();
        fixture.coordinator.close();
        fixture.coordinator = new OptifineCapeCoordinator(fixture.session, fixture.storage,
                new TextureCache(fixture.storage), fixture.sink, new ImmediateClient(), held,
                fixture.reader());
        fixture.coordinator.start();
        fixture.coordinator.worldChanged();
        while (!held.jobs.isEmpty()) held.runNext();
        assertEquals(0, fixture.calls.get());

        fixture.coordinator.refresh();
        UUID prior = fixture.session.currentSession().profileId();
        UUID next = UUID.randomUUID();
        fixture.storage.loadOrCreateAccount(next);
        fixture.session.identity = new GameSessionTokenSource.SessionIdentity(next, "Self");
        fixture.coordinator.configurationChanged();
        while (!held.jobs.isEmpty()) held.runNext();
        assertEquals(0, fixture.calls.get());
        assertNull(CapeProjection.resolve(prior, "Self", null, null, true).provider());
        fixture.coordinator.close();
        assertNull(CapeProjection.resolve(next, "Self", null, null, true).provider());
    }

    @Test
    void sameConnectionWorldEntryResweepsRetainedRosterWithoutAddPacket() throws Exception {
        Fixture fixture = fixture();
        fixture.status = 200;
        UUID remote = UUID.randomUUID();
        fixture.sink.players.add(new PlayerAppearanceSink.TrackedCapePlayer(remote, "Remote"));
        fixture.coordinator.start();
        assertEquals(2, fixture.calls.get());
        assertEquals(BuiltinProvider.OPTIFINE,
                CapeProjection.resolve(remote, "Remote", null, null, false).provider());
        fixture.coordinator.worldEntered();
        assertEquals(3, fixture.calls.get());
        assertEquals(BuiltinProvider.OPTIFINE,
                CapeProjection.resolve(remote, "Remote", null, null, false).provider());
    }

    @Test
    void disablePreservesConfirmedSelfAndReenableRestoresTextureBeforeNetwork() throws Exception {
        Fixture fixture = fixture();
        fixture.status = 200;
        fixture.coordinator.start();
        UUID self = fixture.session.currentSession().profileId();
        int firstCalls = fixture.calls.get();
        fixture.storage.updateAppearance(self, state -> state.withProviders(state.providers()
                .disable(AppearanceProviders.Component.CAPE, BuiltinProvider.OPTIFINE)));
        fixture.coordinator.configurationChanged();
        assertNull(CapeProjection.resolve(self, "Self", null, null, true).provider());
        assertTrue(fixture.storage.loadAppearance(self).providers().cape().optifine().known());
        ManualExecutor held = new ManualExecutor();
        fixture.coordinator.close();
        fixture.coordinator = new OptifineCapeCoordinator(fixture.session, fixture.storage,
                new TextureCache(fixture.storage), fixture.sink, new ImmediateClient(), held,
                fixture.reader());
        fixture.storage.updateAppearance(self, state -> state.withProviders(state.providers()
                .enable(AppearanceProviders.Component.CAPE, BuiltinProvider.OPTIFINE)));
        fixture.coordinator.start();
        assertEquals(BuiltinProvider.OPTIFINE,
                CapeProjection.resolve(self, "Self", null, null, true).provider());
        assertEquals(firstCalls, fixture.calls.get());
    }

    @Test
    void confirmedSelfSurvivesFailureAnd404ClearsIt() throws Exception {
        Fixture fixture = fixture();
        fixture.status = 200;
        fixture.coordinator.start();
        var self = fixture.session.currentSession();
        assertEquals(BuiltinProvider.OPTIFINE, CapeProjection.resolve(self.profileId(),
                self.profileName(), null, null, true).provider());
        assertTrue(fixture.storage.loadAppearance(self.profileId()).providers().cape().optifine().known());
        fixture.status = 503;
        fixture.coordinator.refresh();
        assertEquals(BuiltinProvider.OPTIFINE, CapeProjection.resolve(self.profileId(),
                self.profileName(), null, null, true).provider());
        fixture.status = 404;
        fixture.coordinator.refresh();
        assertNull(CapeProjection.resolve(self.profileId(), self.profileName(), null, null, true).provider());
        assertTrue(fixture.storage.loadAppearance(self.profileId()).providers().cape().optifine().known());
        assertNull(fixture.storage.loadAppearance(self.profileId()).providers().cape().optifine().value());
    }

    @Test
    void highResolutionReaderRegistersSourceScopedSelfAndRemoteAndRestoresSelfAtStartup()
            throws Exception {
        for (int width : new int[] {92, 128}) {
            Fixture fixture = fixture();
            fixture.image = detailedPng(width);
            fixture.status = 200;
            UUID remote = UUID.randomUUID();
            fixture.sink.players.add(new PlayerAppearanceSink.TrackedCapePlayer(remote, "Remote"));
            fixture.coordinator.start();
            UUID self = fixture.session.currentSession().profileId();
            for (UUID profile : new UUID[] {self, remote}) {
                var resolved = CapeProjection.resolve(profile, profile.equals(self) ? "Self" : "Remote",
                        null, null, profile.equals(self));
                assertEquals(BuiltinProvider.OPTIFINE, resolved.provider());
                byte[] registered = fixture.sink.registered.get(resolved.capeLocation());
                assertTrue(registered != null);
                BufferedImage canonical = ImageIO.read(new java.io.ByteArrayInputStream(registered));
                assertEquals(128, canonical.getWidth());
                assertEquals(64, canonical.getHeight());
                assertEquals(0x80112233, canonical.getRGB(3, 7));
                assertEquals(0x44224466, canonical.getRGB(4, 7));
                assertEquals(0x01010203, canonical.getRGB(48, 0));
                assertEquals(0x7f556677, canonical.getRGB(width - 1,
                        width == 92 ? 43 : 63));
                assertTrue(resolved.hasElytra());
            }
            assertEquals(2, fixture.sink.registered.size());
            fixture.coordinator.refresh();
            assertEquals(4, fixture.calls.get());
            assertEquals(2, fixture.sink.registered.size());
            assertEquals(BuiltinProvider.OPTIFINE,
                    CapeProjection.resolve(remote, "Remote", null, null, false).provider());
            fixture.coordinator.close();

            fixture.status = 503;
            ManualExecutor held = new ManualExecutor();
            fixture.coordinator = new OptifineCapeCoordinator(fixture.session, fixture.storage,
                    new TextureCache(fixture.storage), fixture.sink, new ImmediateClient(), held,
                    fixture.reader());
            fixture.coordinator.start();
            assertEquals(BuiltinProvider.OPTIFINE,
                    CapeProjection.resolve(self, "Self", null, null, true).provider());
            assertEquals(1, fixture.sink.registered.size());
            assertEquals(128, ImageIO.read(new java.io.ByteArrayInputStream(
                    fixture.sink.registered.values().iterator().next())).getWidth());
            fixture.coordinator.close();
            while (!held.jobs.isEmpty()) held.runNext();
            assertTrue(fixture.sink.registered.isEmpty());
        }
    }

    @Test
    void savedSelfReplayRejectsValidCanonicalPixelsUnderWrongVisualHash() throws Exception {
        for (int width : new int[] {64, 128}) {
            Fixture fixture = fixture();
            fixture.image = width == 64 ? png() : detailedPng(128);
            fixture.status = 200;
            fixture.coordinator.start();
            UUID self = fixture.session.currentSession().profileId();
            var saved = fixture.storage.loadAppearance(self).providers().cape().optifine().value();
            assertTrue(saved != null);
            String key = saved.textureCacheKey();
            assertEquals(new PngValidator().projectCanonicalCape(fixture.image).renderSha256(), key);
            fixture.coordinator.close();

            ManualExecutor heldValid = new ManualExecutor();
            fixture.coordinator = new OptifineCapeCoordinator(fixture.session, fixture.storage,
                    new TextureCache(fixture.storage), fixture.sink, new ImmediateClient(),
                    heldValid, fixture.reader());
            fixture.coordinator.start();
            assertEquals(BuiltinProvider.OPTIFINE,
                    CapeProjection.resolve(self, "Self", null, null, true).provider());
            assertEquals(saved.hasElytra(),
                    CapeProjection.resolve(self, "Self", null, null, true).hasElytra());
            assertEquals(1, fixture.sink.registered.size());
            fixture.coordinator.close();

            byte[] substituted = substitutedPng(width);
            assertFalse(new PngValidator().projectCanonicalCape(substituted).renderSha256().equals(key));
            Files.write(new TextureCache(fixture.storage).cachePath(key), substituted);
            ManualExecutor heldTampered = new ManualExecutor();
            fixture.coordinator = new OptifineCapeCoordinator(fixture.session, fixture.storage,
                    new TextureCache(fixture.storage), fixture.sink, new ImmediateClient(),
                    heldTampered, fixture.reader());
            fixture.coordinator.start();
            assertTrue(fixture.sink.registered.isEmpty());
            assertNull(CapeProjection.resolve(self, "Self", null, null, true).provider());
            assertFalse(CapeProjection.resolve(self, "Self", null, null, true).hasElytra());
            assertEquals(saved.hasElytra(), fixture.storage.loadAppearance(self)
                    .providers().cape().optifine().value().hasElytra());
            fixture.coordinator.close();

            Files.write(new TextureCache(fixture.storage).cachePath(key), new byte[] {1, 2, 3});
            ManualExecutor heldMalformed = new ManualExecutor();
            fixture.status = 503;
            fixture.coordinator = new OptifineCapeCoordinator(fixture.session, fixture.storage,
                    new TextureCache(fixture.storage), fixture.sink, new ImmediateClient(),
                    heldMalformed, fixture.reader());
            int previousCalls = fixture.calls.get();
            fixture.coordinator.start();
            assertTrue(fixture.sink.registered.isEmpty());
            assertNull(CapeProjection.resolve(self, "Self", null, null, true).provider());
            assertFalse(heldMalformed.jobs.isEmpty(), "cache failure must still schedule startup fetch");
            while (!heldMalformed.jobs.isEmpty()) heldMalformed.runNext();
            assertEquals(previousCalls + 1, fixture.calls.get());
            assertTrue(fixture.storage.loadAppearance(self).providers().cape()
                    .enabled(BuiltinProvider.OPTIFINE));
            fixture.coordinator.close();
        }
    }

    @Test
    void disableAndExactNameFenceExcludeLateCape() throws Exception {
        Fixture fixture = fixture();
        fixture.image = detailedPng(92);
        ManualExecutor held = new ManualExecutor();
        fixture.coordinator.close();
        fixture.coordinator = new OptifineCapeCoordinator(fixture.session, fixture.storage,
                new TextureCache(fixture.storage), fixture.sink, new ImmediateClient(), held,
                fixture.reader());
        fixture.coordinator.start();
        assertEquals(2, held.jobs.size());
        fixture.storage.updateAppearance(fixture.session.currentSession().profileId(), state ->
                state.withProviders(state.providers().disable(AppearanceProviders.Component.CAPE,
                        BuiltinProvider.OPTIFINE)));
        fixture.coordinator.configurationChanged();
        held.runNext();
        held.runNext();
        assertEquals(0, fixture.calls.get());
        assertNull(CapeProjection.resolve(fixture.session.currentSession().profileId(),
                fixture.session.currentSession().profileName(), null, null, true).provider());
        assertFalse(fixture.storage.loadAppearance(fixture.session.currentSession().profileId())
                .providers().cape().optifine().known());
        assertTrue(fixture.sink.registered.isEmpty());
    }

    @Test
    void untrackCancelsRequestAndRetrackUsesNewRequestIdentity() throws Exception {
        Fixture fixture = fixture();
        ManualExecutor held = new ManualExecutor();
        fixture.coordinator.close();
        fixture.coordinator = new OptifineCapeCoordinator(fixture.session, fixture.storage,
                new TextureCache(fixture.storage), fixture.sink, new ImmediateClient(), held,
                fixture.reader());
        UUID remote = UUID.randomUUID();
        fixture.coordinator.start();
        fixture.coordinator.trackedPlayer(remote, "Remote");
        assertEquals(3, held.jobs.size());
        fixture.coordinator.untrackedPlayer(remote);
        fixture.coordinator.trackedPlayer(remote, "Remote");
        assertEquals(4, held.jobs.size());
        held.runNext();
        held.runNext();
        held.runNext();
        held.runNext();
        assertEquals(2, fixture.calls.get());
        assertTrue(fixture.storage.loadAppearance(fixture.session.currentSession().profileId())
                .providers().cape().optifine().known());
    }

    @Test
    void completedPresentAbsentAndFailureAllFetchAgainAfterDeparture() throws Exception {
        for (int status : new int[] {200, 404, 503}) {
            Fixture fixture = fixture();
            fixture.status = status;
            fixture.coordinator.start();
            UUID remote = UUID.randomUUID();
            fixture.coordinator.trackedPlayer(remote, "Remote");
            assertEquals(2, fixture.calls.get(), "first lookup for status " + status);
            fixture.coordinator.trackedPlayer(remote, "Remote");
            assertEquals(2, fixture.calls.get(), "same roster identity does not poll");
            fixture.coordinator.untrackedPlayer(remote);
            fixture.coordinator.trackedPlayer(remote, "Remote");
            assertEquals(3, fixture.calls.get(), "new roster entry for status " + status);
        }
    }

    @Test
    void repeatedPlayerInfoAddTargetsOnlyTrackedActorAndPreservesUnchangedHandle() throws Exception {
        Fixture fixture = fixture();
        fixture.status = 200;
        UUID remote = UUID.randomUUID();
        fixture.sink.players.add(new PlayerAppearanceSink.TrackedCapePlayer(remote, "Remote"));
        fixture.coordinator.start();
        assertEquals(2, fixture.calls.get());
        fixture.coordinator.playerInfoUpdated(remote, "Remote");
        assertEquals(3, fixture.calls.get());
        int registrations = fixture.sink.registrations;
        int releases = fixture.sink.releases;
        fixture.coordinator.playerInfoUpdated(remote, "Remote");
        assertEquals(4, fixture.calls.get());
        assertEquals(registrations, fixture.sink.registrations);
        assertEquals(releases, fixture.sink.releases);
        fixture.coordinator.untrackedPlayer(remote);
        assertEquals(releases + 1, fixture.sink.releases);
    }

    @Test
    void worldEntryRepeatedAddRefreshesObservedRemoteAndKnownAbsence() throws Exception {
        for (int initialStatus : new int[] {200, 404}) {
            Fixture fixture = fixture();
            fixture.status = initialStatus;
            UUID remote = UUID.randomUUID();
            fixture.sink.players.add(new PlayerAppearanceSink.TrackedCapePlayer(remote, "Remote"));
            fixture.coordinator.start();
            assertEquals(2, fixture.calls.get());
            fixture.coordinator.worldEntered();
            int afterWorldEntry = fixture.calls.get();
            assertEquals(3, afterWorldEntry);
            fixture.status = 200;
            fixture.image = substitutedPng(64);
            fixture.coordinator.playerInfoUpdated(remote, "Remote");
            assertEquals(afterWorldEntry + 1, fixture.calls.get(),
                    "ADD must recheck after unknown-only world entry");
            assertEquals(BuiltinProvider.OPTIFINE,
                    CapeProjection.resolve(remote, "Remote", null, null, false).provider());
            var expected = new PngValidator().projectCanonicalCape(fixture.image);
            assertEquals(expected.renderSha256(), new PngValidator().projectCanonicalCape(
                    fixture.sink.registered.get("nclskins:optifine/" + remote)).renderSha256());
        }
    }

    @Test
    void worldEntryDuplicateAddHintsCoalesceToOneFollowupRead() throws Exception {
        Fixture fixture = fixture();
        fixture.status = 404;
        UUID remote = UUID.randomUUID();
        fixture.sink.players.add(new PlayerAppearanceSink.TrackedCapePlayer(remote, "Remote"));
        ManualExecutor held = new ManualExecutor();
        fixture.coordinator.close();
        fixture.coordinator = new OptifineCapeCoordinator(fixture.session, fixture.storage,
                new TextureCache(fixture.storage), fixture.sink, new ImmediateClient(), held,
                fixture.reader());
        fixture.coordinator.start();
        while (!held.jobs.isEmpty()) held.runNext();
        int beforeWorldEntry = fixture.calls.get();
        fixture.status = 200;
        fixture.image = substitutedPng(64);
        fixture.coordinator.worldEntered();
        held.runNext();
        assertEquals(beforeWorldEntry, fixture.calls.get(),
                "known self is skipped by unknown-only discovery");
        assertEquals(1, held.jobs.size(), "remote discovery remains pending");
        fixture.coordinator.playerInfoUpdated(remote, "Remote");
        for (int hint = 0; hint < 10_000; hint++) fixture.coordinator.playerInfoUpdated(remote, "Remote");
        assertEquals(1, held.jobs.size());
        held.runNext();
        assertEquals(1, held.jobs.size(), "burst creates one followup request");
        fixture.image = png();
        held.runNext();
        assertTrue(held.jobs.isEmpty());
        assertEquals(beforeWorldEntry + 2, fixture.calls.get(),
                "world discovery plus one latest coalesced read");
        assertEquals(BuiltinProvider.OPTIFINE,
                CapeProjection.resolve(remote, "Remote", null, null, false).provider());
        assertEquals(new PngValidator().projectCanonicalCape(fixture.image).renderSha256(),
                new PngValidator().projectCanonicalCape(
                        fixture.sink.registered.get("nclskins:optifine/" + remote)).renderSha256());
    }

    @Test
    void capacityBlockedObservedActorHintEventuallyReadsLatestCape() throws Exception {
        Fixture fixture = heldObservedRemote();
        UUID remote = fixture.sink.players.get(0).profileId();
        ManualExecutor held = fixture.worker;
        for (int index = 0; index < 300; index++) {
            UUID newcomer = UUID.nameUUIDFromBytes(("capacity-" + index).getBytes());
            fixture.sink.players.add(new PlayerAppearanceSink.TrackedCapePlayer(newcomer,
                    "Capacity_" + index));
            fixture.coordinator.trackedPlayer(newcomer, "Capacity_" + index);
        }
        assertEquals(4, held.jobs.size());
        fixture.image = substitutedPng(64);
        for (int hint = 0; hint < 10_000; hint++) fixture.coordinator.playerInfoUpdated(remote, "Remote");
        drainHeld(held);
        assertEquals(2, fixture.reads("Remote"), "10,000 hints require one latest read");
        assertRemotePixels(fixture, remote, fixture.image);
    }

    @Test
    void pendingActorHintSurvivesFullQueueAsOneLatestRead() throws Exception {
        Fixture fixture = heldObservedRemote();
        UUID remote = fixture.sink.players.get(0).profileId();
        ManualExecutor held = fixture.worker;
        fixture.coordinator.playerInfoUpdated(remote, "Remote");
        for (int index = 0; index < 300; index++) {
            UUID newcomer = UUID.nameUUIDFromBytes(("pending-capacity-" + index).getBytes());
            fixture.sink.players.add(new PlayerAppearanceSink.TrackedCapePlayer(newcomer,
                    "Pending_" + index));
            fixture.coordinator.trackedPlayer(newcomer, "Pending_" + index);
        }
        for (int hint = 0; hint < 10_000; hint++) fixture.coordinator.playerInfoUpdated(remote, "Remote");
        held.runNext();
        fixture.image = substitutedPng(64);
        drainHeld(held);
        assertEquals(3, fixture.reads("Remote"), "pending burst requires one followup read");
        assertRemotePixels(fixture, remote, fixture.image);
    }

    @Test
    void deferredActorMarkersClearOnAccountAndTrackingIdentityChange() throws Exception {
        Fixture fixture = heldObservedRemote();
        UUID remote = fixture.sink.players.get(0).profileId();
        fixture.coordinator.playerInfoUpdated(remote, "Remote");
        fixture.coordinator.playerInfoUpdated(remote, "Remote");
        assertEquals(1, deferredActors(fixture.coordinator).size());
        fixture.coordinator.untrackedPlayer(remote);
        assertTrue(deferredActors(fixture.coordinator).isEmpty());
        fixture.coordinator.trackedPlayer(remote, "Remote");
        fixture.coordinator.playerInfoUpdated(remote, "Remote");
        assertEquals(1, deferredActors(fixture.coordinator).size());
        fixture.coordinator.trackedPlayer(remote, "Renamed");
        assertTrue(deferredActors(fixture.coordinator).isEmpty());
        fixture.coordinator.playerInfoUpdated(remote, "Renamed");
        assertEquals(1, deferredActors(fixture.coordinator).size());
        UUID next = UUID.randomUUID();
        fixture.storage.loadOrCreateAccount(next);
        fixture.session.identity = new GameSessionTokenSource.SessionIdentity(next, "Self");
        fixture.coordinator.refresh();
        assertTrue(deferredActors(fixture.coordinator).isEmpty());
    }

    @Test
    void sharedOpenAdoptsValidatedCapeWithoutLookupAndRejectsOldSelfCompletion() throws Exception {
        Fixture fixture = fixture();
        fixture.status = 200;
        CountingExecutor preparations = new CountingExecutor();
        fixture.coordinator.close();
        fixture.coordinator = new OptifineCapeCoordinator(fixture.session, fixture.storage,
                new TextureCache(fixture.storage), fixture.sink, new ImmediateClient(),
                preparations, fixture.reader());
        fixture.coordinator.start();
        UUID self = fixture.session.currentSession().profileId();
        int lookups = fixture.calls.get();
        int registrations = fixture.sink.registrations;
        byte[] replacement = substitutedPng(64);
        TextureCache cache = new TextureCache(fixture.storage);
        var normalized = new PngValidator().projectCanonicalCape(replacement);
        String key = cache.storeObservedCape(normalized);
        var replacementCape = new com.naocraftlab.skins.core.provider.ProviderCape(
                "optifine:" + self.toString().replace("-", "") + ":Self", key,
                normalized.hasElytra());
        var shared = fixture.storage.updateAppearance(self, state -> state.withProviders(
                new AppearanceProviders(state.providers().skin(),
                        state.providers().cape().observeOptifine(replacementCape)))).providers();
        fixture.coordinator.adoptSharedSnapshot(self, "Self", shared);
        assertEquals(lookups, fixture.calls.get());
        assertEquals(registrations + 1, fixture.sink.registrations);
        assertEquals(key, fixture.storage.loadAppearance(self).providers().cape().optifine()
                .value().textureCacheKey());
        int prepared = preparations.calls;
        fixture.coordinator.adoptSharedSnapshot(self, "Self", shared);
        assertEquals(registrations + 1, fixture.sink.registrations);
        assertEquals(prepared, preparations.calls);
        var absent = fixture.storage.updateAppearance(self, state -> state.withProviders(
                new AppearanceProviders(state.providers().skin(),
                        state.providers().cape().observeOptifine(null)))).providers();
        fixture.coordinator.adoptSharedSnapshot(self, "Self", absent);
        assertNull(CapeProjection.resolve(self, "Self", null, null, true).provider());
        assertEquals(lookups, fixture.calls.get());
    }

    @Test
    void selfLookupCannotOverwriteSharedObservationChangedAfterDispatch() throws Exception {
        Fixture fixture = fixture();
        fixture.status = 200;
        ManualExecutor held = new ManualExecutor();
        fixture.coordinator.close();
        TextureCache cache = new TextureCache(fixture.storage);
        fixture.coordinator = new OptifineCapeCoordinator(fixture.session, fixture.storage,
                cache, fixture.sink, new ImmediateClient(), held, fixture.reader());
        fixture.coordinator.start();
        UUID self = fixture.session.currentSession().profileId();
        var normalized = new PngValidator().projectCanonicalCape(substitutedPng(64));
        String key = cache.storeObservedCape(normalized);
        var sharedCape = new com.naocraftlab.skins.core.provider.ProviderCape(
                "optifine:" + self.toString().replace("-", "") + ":Self", key,
                normalized.hasElytra());
        var shared = fixture.storage.updateAppearance(self, state -> state.withProviders(
                new AppearanceProviders(state.providers().skin(),
                        state.providers().cape().observeOptifine(sharedCape)))).providers();
        while (!held.jobs.isEmpty()) held.runNext();
        assertEquals(key, fixture.storage.loadAppearance(self).providers().cape().optifine()
                .value().textureCacheKey());
        fixture.coordinator.adoptSharedSnapshot(self, "Self", shared);
        while (!held.jobs.isEmpty()) held.runNext();
        assertEquals(BuiltinProvider.OPTIFINE,
                CapeProjection.resolve(self, "Self", null, null, true).provider());
        assertEquals(key, fixture.storage.loadAppearance(self).providers().cape().optifine()
                .value().textureCacheKey());
    }

    @Test
    void sharedCachePreparationCannotRegisterAfterWorldChange() throws Exception {
        Fixture fixture = fixture();
        ManualExecutor held = new ManualExecutor();
        fixture.coordinator.close();
        TextureCache cache = new TextureCache(fixture.storage);
        fixture.coordinator = new OptifineCapeCoordinator(fixture.session, fixture.storage,
                cache, fixture.sink, new ImmediateClient(), held, fixture.reader());
        fixture.coordinator.start();
        while (!held.jobs.isEmpty()) held.runNext();
        UUID self = fixture.session.currentSession().profileId();
        var normalized = new PngValidator().projectCanonicalCape(png());
        String key = cache.storeObservedCape(normalized);
        var sharedCape = new com.naocraftlab.skins.core.provider.ProviderCape(
                "optifine:" + self.toString().replace("-", "") + ":Self", key,
                normalized.hasElytra());
        var shared = fixture.storage.updateAppearance(self, state -> state.withProviders(
                new AppearanceProviders(state.providers().skin(),
                        state.providers().cape().observeOptifine(sharedCape)))).providers();
        fixture.coordinator.adoptSharedSnapshot(self, "Self", shared);
        int registrations = fixture.sink.registrations;
        fixture.coordinator.worldChanged();
        while (!held.jobs.isEmpty()) held.runNext();
        assertEquals(registrations, fixture.sink.registrations);
        assertNull(CapeProjection.resolve(self, "Self", null, null, true).provider());
    }

    @Test
    void unchangedRefreshKeepsAllRegisteredSourceHandles() throws Exception {
        Fixture fixture = fixture();
        fixture.status = 200;
        UUID self = fixture.session.currentSession().profileId();
        TextureCache cache = new TextureCache(fixture.storage);
        var normalized = new PngValidator().projectCanonicalCape(png());
        String key = cache.storeObservedCape(normalized);
        fixture.storage.updateAppearance(self, state -> state.withProviders(
                new AppearanceProviders(state.providers().skin(),
                        state.providers().cape().observeMinecraft(
                                new com.naocraftlab.skins.core.provider.ProviderCape(
                                        "official", key, normalized.hasElytra())))));
        fixture.coordinator.start();
        int registrations = fixture.sink.registrations;
        int releases = fixture.sink.releases;
        fixture.coordinator.refresh();
        assertEquals(registrations, fixture.sink.registrations);
        assertEquals(releases, fixture.sink.releases);
    }

    @Test
    void departedRosterChurnDoesNotRetainAttemptState() throws Exception {
        Fixture fixture = fixture();
        fixture.status = 503;
        fixture.coordinator.start();
        for (int index = 0; index < 320; index++) {
            UUID remote = UUID.nameUUIDFromBytes(("churn-" + index).getBytes());
            fixture.coordinator.trackedPlayer(remote, "Remote_" + index);
            fixture.coordinator.untrackedPlayer(remote);
        }
        assertEquals(321, fixture.calls.get());
        var attempted = OptifineCapeCoordinator.class.getDeclaredField("attempted");
        attempted.setAccessible(true);
        assertEquals(1, ((java.util.Set<?>) attempted.get(fixture.coordinator)).size());
        var tracked = OptifineCapeCoordinator.class.getDeclaredField("tracked");
        tracked.setAccessible(true);
        assertTrue(((java.util.Map<?, ?>) tracked.get(fixture.coordinator)).isEmpty());
    }

    @Test
    void reorderingWhileLookupIsPendingKeepsThatRequestAndStartsNoReplacement() throws Exception {
        Fixture fixture = fixture();
        fixture.status = 200;
        ManualExecutor held = new ManualExecutor();
        fixture.coordinator.close();
        fixture.coordinator = new OptifineCapeCoordinator(fixture.session, fixture.storage,
                new TextureCache(fixture.storage), fixture.sink, new ImmediateClient(), held,
                fixture.reader());
        fixture.coordinator.start();
        UUID self = fixture.session.currentSession().profileId();
        fixture.storage.updateAppearance(self, state -> state.withProviders(state.providers()
                .move(AppearanceProviders.Component.CAPE, BuiltinProvider.OPTIFINE, -1)));
        fixture.coordinator.configurationChanged();
        while (!held.jobs.isEmpty()) held.runNext();
        assertEquals(1, fixture.calls.get());
        assertTrue(fixture.storage.loadAppearance(self).providers().cape().optifine().known());
        assertEquals(BuiltinProvider.OPTIFINE,
                CapeProjection.resolve(self, "Self", null, null, true).provider());
    }

    @Test
    void resolverUsesOrderAndSkipsOfflineForRemote() {
        UUID self = UUID.randomUUID();
        UUID remote = UUID.randomUUID();
        var offline = new CapeProjection.Candidate("offline", null, false);
        var minecraft = new CapeProjection.Candidate("minecraft", "minecraft", true);
        var optifine = new CapeProjection.Candidate("optifine", null, false);
        var map = java.util.Map.of(new CapeProjection.Identity(self, "Self"), optifine,
                new CapeProjection.Identity(remote, "Remote"), optifine);
        CapeProjection.publish(new CapeProjection.Snapshot(
                List.of(BuiltinProvider.OPTIFINE, BuiltinProvider.OFFLINE, BuiltinProvider.MINECRAFT),
                map, new CapeProjection.Identity(self, "Self"), offline, minecraft));
        assertEquals("optifine", CapeProjection.resolve(self, "Self", null, null, true).capeLocation());
        assertEquals("optifine", CapeProjection.resolve(remote, "Remote", offline, minecraft, false).capeLocation());
        CapeProjection.publish(new CapeProjection.Snapshot(
                List.of(BuiltinProvider.OFFLINE, BuiltinProvider.MINECRAFT, BuiltinProvider.OPTIFINE),
                map, new CapeProjection.Identity(self, "Self"), offline, minecraft));
        assertEquals("offline", CapeProjection.resolve(self, "Self", null, null, true).capeLocation());
        assertEquals("minecraft", CapeProjection.resolve(remote, "Remote", offline, minecraft, false).capeLocation());
        assertNull(CapeProjection.resolve(remote, "ChangedName", offline, null, false).capeLocation());
        CapeProjection.clear();
    }

    @Test
    void selfMinecraftCandidateLoadsFromConfirmedUriWhenObservationHasNoCacheKey() throws Exception {
        Fixture fixture = fixture();
        UUID self = fixture.session.currentSession().profileId();
        fixture.storage.updateAppearance(self, state -> state.withProviders(new AppearanceProviders(
                state.providers().skin(), state.providers().cape()
                        .disable(BuiltinProvider.OFFLINE)
                        .observeMinecraft(new com.naocraftlab.skins.core.provider.ProviderCape(
                                "official", null, true)))));
        URI official = URI.create("https://textures.minecraft.net/texture/official-cape");
        TextureCache cache = new TextureCache(fixture.storage);
        Files.write(cache.cachePath(TextureCache.cacheKey(official)), png());
        fixture.coordinator.close();
        fixture.status = 200;
        fixture.coordinator = new OptifineCapeCoordinator(fixture.session, fixture.storage,
                cache, fixture.sink, new ImmediateClient(), Runnable::run, fixture.reader(),
                (accountId, capeId) -> accountId.equals(self) && capeId.equals("official")
                        ? Optional.of(official) : Optional.empty());
        fixture.coordinator.start();
        assertEquals(BuiltinProvider.MINECRAFT, CapeProjection.resolve(self, "Self",
                null, null, true).provider());
    }

    private Fixture fixture() throws Exception {
        UUID profile = UUID.randomUUID();
        FakeSession session = new FakeSession(profile, "Self");
        NclSkinsStorage storage = new NclSkinsStorage(directory, new PngValidator(), Clock.systemUTC());
        storage.loadOrCreateAccount(profile);
        storage.updateAppearance(profile, state -> state.withProviders(state.providers()
                .enable(AppearanceProviders.Component.CAPE, BuiltinProvider.OPTIFINE)));
        FakeSink sink = new FakeSink();
        Fixture fixture = new Fixture(session, storage, sink);
        fixture.image = png();
        fixture.coordinator = new OptifineCapeCoordinator(session, storage, new TextureCache(storage),
                sink, new ImmediateClient(), Runnable::run, fixture.reader());
        return fixture;
    }

    private Fixture heldObservedRemote() throws Exception {
        Fixture fixture = fixture();
        fixture.coordinator.close();
        fixture.status = 200;
        ManualExecutor held = new ManualExecutor();
        fixture.worker = held;
        UUID remote = UUID.randomUUID();
        fixture.sink.players.add(new PlayerAppearanceSink.TrackedCapePlayer(remote, "Remote"));
        fixture.coordinator = new OptifineCapeCoordinator(fixture.session, fixture.storage,
                new TextureCache(fixture.storage), fixture.sink, new ImmediateClient(), held,
                fixture.reader());
        fixture.coordinator.start();
        drainHeld(held);
        assertEquals(BuiltinProvider.OPTIFINE,
                CapeProjection.resolve(remote, "Remote", null, null, false).provider());
        return fixture;
    }

    private static void drainHeld(ManualExecutor held) {
        int jobs = 0;
        while (!held.jobs.isEmpty() && jobs < 2_000) {
            held.runNext();
            jobs++;
        }
        assertTrue(held.jobs.isEmpty(), "bounded work must finish without polling");
    }

    private static void assertRemotePixels(Fixture fixture, UUID remote, byte[] expected) throws Exception {
        byte[] registered = fixture.sink.registered.get("nclskins:optifine/" + remote);
        assertTrue(registered != null, "tracked actor retains a cape texture");
        PngValidator validator = new PngValidator();
        assertEquals(validator.projectCanonicalCape(expected).renderSha256(),
                validator.projectCanonicalCape(registered).renderSha256());
    }

    private static Set<?> deferredActors(OptifineCapeCoordinator coordinator) throws Exception {
        var field = OptifineCapeCoordinator.class.getDeclaredField("repeatPending");
        field.setAccessible(true);
        return (Set<?>) field.get(coordinator);
    }

    private static byte[] png() throws IOException {
        BufferedImage image = new BufferedImage(64, 32, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(1, 1, 0xff123456);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(image, "png", output);
        return output.toByteArray();
    }

    private static byte[] detailedPng(int width) throws IOException {
        BufferedImage image = new BufferedImage(width, width == 92 ? 44 : 64,
                BufferedImage.TYPE_INT_ARGB);
        image.setRGB(3, 7, 0x80112233);
        image.setRGB(4, 7, 0x44224466);
        image.setRGB(48, 0, 0x01010203);
        image.setRGB(width - 1, width == 92 ? 43 : 63, 0x7f556677);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(image, "png", output);
        return output.toByteArray();
    }

    private static byte[] substitutedPng(int width) throws IOException {
        BufferedImage image = new BufferedImage(width, width / 2, BufferedImage.TYPE_INT_ARGB);
        if (width == 64) image.setRGB(24, 0, 0x01010203);
        else image.setRGB(1, 1, 0xff112233);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(image, "png", output);
        return output.toByteArray();
    }

    private static final class Fixture {
        private final FakeSession session;
        private final NclSkinsStorage storage;
        private final FakeSink sink;
        private final AtomicInteger calls = new AtomicInteger();
        private final Map<String, AtomicInteger> perPlayerReads = new HashMap<>();
        private volatile int status = 404;
        private volatile byte[] image;
        private OptifineCapeCoordinator coordinator;
        private ManualExecutor worker;

        private Fixture(FakeSession session, NclSkinsStorage storage, FakeSink sink) {
            this.session = session;
            this.storage = storage;
            this.sink = sink;
        }

        private OptifineCapeReader reader() throws IOException {
            return new OptifineCapeReader((URI uri, java.time.Duration timeout, int maxBytes) -> {
                calls.incrementAndGet();
                String path = uri.getPath();
                String name = path.substring(path.lastIndexOf('/') + 1, path.length() - 4);
                perPlayerReads.computeIfAbsent(name, ignored -> new AtomicInteger()).incrementAndGet();
                return new OptifineCapeReader.Response(status, status == 200 ? image : new byte[0]);
            }, new PngValidator());
        }

        private int reads(String canonicalName) {
            return perPlayerReads.getOrDefault(canonicalName, new AtomicInteger()).get();
        }
    }

    private static final class FakeSession implements GameSessionTokenSource {
        private SessionIdentity identity;

        private FakeSession(UUID profile, String name) {
            identity = new SessionIdentity(profile, name);
        }

        @Override
        public SessionIdentity currentSession() {
            return identity;
        }

        @Override
        public <T, E extends Exception> T withAccessToken(TokenRequest<T, E> request) {
            throw new AssertionError("Public cape lookup must not use credentials");
        }
    }

    private static final class ImmediateClient implements ClientExecutor {
        @Override public boolean isClientThread() { return true; }
        @Override public void execute(Runnable action) { action.run(); }
    }

    private static final class CountingExecutor implements java.util.concurrent.Executor {
        private int calls;

        @Override
        public void execute(Runnable action) {
            calls++;
            action.run();
        }
    }

    private static final class FakeSink implements PlayerAppearanceSink<Object> {
        private final List<TrackedCapePlayer> players = new ArrayList<>();
        private final Map<String, byte[]> registered = new HashMap<>();
        private int registrations;
        private int releases;

        @Override
        public ApplyResult apply(SignedProfileResolver.ResolvedProfile<Object> resolvedProfile) {
            return ApplyResult.UPDATED;
        }

        @Override
        public List<TrackedCapePlayer> trackedCapePlayers() {
            return List.copyOf(players);
        }

        @Override
        public Optional<String> registerCapeTexture(UUID profileId, CapeSource source,
                String sha256, byte[] normalizedPng) {
            registrations++;
            String location = "nclskins:" + source.name().toLowerCase() + "/" + profileId;
            registered.put(location, normalizedPng.clone());
            return Optional.of(location);
        }

        @Override
        public void releaseCapeTexture(UUID profileId, CapeSource source) {
            releases++;
            registered.remove("nclskins:" + source.name().toLowerCase() + "/" + profileId);
        }
    }

    private static final class ManualExecutor extends AbstractExecutorService {
        private final List<Runnable> jobs = new ArrayList<>();
        private boolean shutdown;

        @Override public void shutdown() { shutdown = true; }
        @Override public List<Runnable> shutdownNow() {
            shutdown = true;
            List<Runnable> remaining = List.copyOf(jobs);
            jobs.clear();
            return remaining;
        }
        @Override public boolean isShutdown() { return shutdown; }
        @Override public boolean isTerminated() { return shutdown && jobs.isEmpty(); }
        @Override public boolean awaitTermination(long timeout, TimeUnit unit) { return isTerminated(); }
        @Override public void execute(Runnable command) { jobs.add(command); }

        private void runNext() {
            jobs.remove(0).run();
        }
    }
}

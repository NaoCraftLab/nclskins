package com.naocraftlab.skins.runtime;

import com.naocraftlab.skins.client.ClientExecutor;
import com.naocraftlab.skins.client.GameSessionTokenSource;
import com.naocraftlab.skins.client.PlayerAppearanceSink;
import com.naocraftlab.skins.client.AbstractTextureRegistry;
import com.naocraftlab.skins.client.TextureRegistry;
import com.naocraftlab.skins.client.SignedProfileResolver;
import com.naocraftlab.skins.core.png.PngValidator;
import com.naocraftlab.skins.core.png.CapeAssetDecoder;
import com.naocraftlab.skins.core.provider.AppearanceProviders;
import com.naocraftlab.skins.core.provider.BuiltinProvider;
import com.naocraftlab.skins.core.provider.ProviderCape;
import com.naocraftlab.skins.core.model.AccountAppearanceState;
import com.naocraftlab.skins.core.model.AppearanceSyncStatus;
import com.naocraftlab.skins.client.OuterLayerVisibility;
import com.naocraftlab.skins.core.storage.NclSkinsStorage;
import com.naocraftlab.skins.core.storage.ProviderTextureStorageAdapter;
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
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.security.SecureRandom;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CapeProviderCoordinatorTest {
    @TempDir Path directory;
    private final List<Fixture> fixtures = new ArrayList<>();

    @org.junit.jupiter.api.AfterEach
    void closeProcessOwners() {
        fixtures.forEach(fixture -> fixture.coordinator.close());
    }

    @Test
    void observerConfigurationImmediatelyControlsRemoteCpmWithoutChangingSubjectSource() throws Exception {
        Fixture fixture = fixture();
        UUID self = fixture.session.identity.profileId(), remote = new UUID(0, 203);
        fixture.storage.updateAppearance(self, state -> state.withProviders(state.providers()
                .disable(AppearanceProviders.Component.CAPE, BuiltinProvider.OPTIFINE)
                .enable(AppearanceProviders.Component.CAPE, BuiltinProvider.CPM)
                .move(AppearanceProviders.Component.CAPE, BuiltinProvider.CPM, -1)));
        fixture.coordinator.start();
        var source = CapeProjection.effectiveSkin(remote, remote, "fixture:remote", com.naocraftlab.skins.client.SkinModel.CLASSIC, false);
        var observation = CapeProjection.cpmObserver(source, "Remote");
        observation.publish(1, CpmAccessoryObservation.Availability.CONFIRMED, true, true);
        var ordinary = new CapeProjection.Candidate("fixture:cape", "fixture:elytra", true);
        assertEquals(BuiltinProvider.CPM, CapeProjection.resolve(remote, "Remote", null, ordinary, false).provider());
        assertTrue(CapeProjection.cpmWins(source));
        int reads = fixture.calls.get();
        long revision = fixture.storage.loadAppearance(self).providers().cape().configurationRevision();
        fixture.storage.updateAppearance(self, state -> state.withProviders(state.providers()
                .move(AppearanceProviders.Component.CAPE, BuiltinProvider.CPM, 1)));
        fixture.coordinator.configurationChanged();
        assertTrue(fixture.storage.loadAppearance(self).providers().cape().configurationRevision() > revision);
        assertFalse(CapeProjection.cpmWins(source));
        assertEquals(BuiltinProvider.MINECRAFT, CapeProjection.material(remote, "Remote", false).provider());
        assertEquals(BuiltinProvider.CPM, CapeProjection.resolve(remote, "Remote", null, null, false).provider());
        assertTrue(CapeProjection.cpmWins(source));
        fixture.storage.updateAppearance(self, state -> state.withProviders(state.providers()
                .disable(AppearanceProviders.Component.CAPE, BuiltinProvider.CPM)));
        fixture.coordinator.configurationChanged();
        var disabled = fixture.storage.loadAppearance(self);
        assertFalse(CapeProjection.cpmWins(source));
        observation.publish(2, CpmAccessoryObservation.Availability.CONFIRMED, false, true);
        assertFalse(CapeProjection.cpmWins(source));
        assertNull(CapeProjection.resolve(remote, "Remote", null, null, false).provider());
        assertEquals(disabled, fixture.storage.loadAppearance(self));
        fixture.storage.updateAppearance(self, state -> state.withProviders(state.providers()
                .enable(AppearanceProviders.Component.CAPE, BuiltinProvider.CPM)));
        fixture.coordinator.configurationChanged();
        assertTrue(CapeProjection.cpmWins(source));
        assertEquals(source, CapeProjection.skinSource(remote));
        assertTrue(source.active());
        assertEquals(reads, fixture.calls.get());
    }

    @Test
    void cpmObservationPublishesWithoutDurableNetworkOrSkinEffects() throws Exception {
        Fixture fixture = fixture();
        UUID id = fixture.session.identity.profileId();
        fixture.storage.updateAppearance(id, state -> state.withProviders(state.providers()
                .enable(AppearanceProviders.Component.CAPE, BuiltinProvider.CPM)));
        fixture.coordinator.start();
        var durable = fixture.storage.loadAppearance(id);
        int reads = fixture.calls.get();
        var source = CapeProjection.effectiveSkin(id, id, "fixture:cpm", com.naocraftlab.skins.client.SkinModel.CLASSIC, false);
        var observer = CapeProjection.cpmObserver(source, "Self");
        assertTrue(observer.publish(1, CpmAccessoryObservation.Availability.CONFIRMED, false, true));
        var result = CapeProjection.resolveSelf(id, "Self").orElseThrow();
        assertEquals(BuiltinProvider.CPM, result.provider());
        assertTrue(result.nativeElytra());
        assertNull(result.capeLocation());
        assertEquals(durable, fixture.storage.loadAppearance(id));
        assertEquals(reads, fixture.calls.get());
        assertTrue(observer.publish(2, CpmAccessoryObservation.Availability.BLOCKED, false, false));
        assertNotEquals(BuiltinProvider.CPM, CapeProjection.resolveSelf(id, "Self").orElseThrow().provider());
        assertEquals(durable, fixture.storage.loadAppearance(id));
        assertEquals(reads, fixture.calls.get());
        assertTrue(source.active());
        assertEquals(source, CapeProjection.skinSource(id));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(value = BuiltinProvider.class,
            names = {"OPTIFINE", "SKINMC", "MINECRAFTCAPES"})
    void partialSweepRefreshRereadsCompletedSelfAndCoalescesUnsentActors(BuiltinProvider provider)
            throws Exception {
        Fixture fixture = fixture();
        fixture.coordinator.close();
        UUID self = fixture.session.identity.profileId();
        UUID remote = UUID.randomUUID();
        fixture.storage.updateAppearance(self, state -> state.withProviders(state.providers()
                .disable(AppearanceProviders.Component.CAPE, BuiltinProvider.OPTIFINE)
                .enable(AppearanceProviders.Component.CAPE, provider)));
        ManualExecutor worker = new ManualExecutor();
        AtomicInteger selfReads = new AtomicInteger();
        AtomicInteger remoteReads = new AtomicInteger();
        CapeTestComposition.ResponseFixture read = (uri, timeout, maximum) -> {
            String path = uri.getPath();
            if (path.contains("Self") || path.contains(self.toString())) selfReads.incrementAndGet();
            else remoteReads.incrementAndGet();
            return new OptifineCapeReader.Response(404, new byte[0]);
        };
        MinecraftCapesCapeReader.Transport mcTransport = new MinecraftCapesCapeReader.Transport() {
            public MinecraftCapesCapeReader.ProfileResponse profile(URI uri, Duration timeout,
                    long maximum) throws IOException {
                if (uri.getPath().contains(self.toString().replace("-", ""))) selfReads.incrementAndGet();
                else remoteReads.incrementAndGet();
                return new MinecraftCapesCapeReader.ProfileResponse(404,
                        MinecraftCapesCapeReader.parse("{}".getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                        Map.of());
            }
            public MinecraftCapesCapeReader.Response image(URI uri, Duration timeout,
                    long maximum, Path destination) {
                throw new AssertionError("confirmed absence must not fetch an image");
            }
        };
        var minecraftCapes = new MinecraftCapesCapeReader(mcTransport, new CapeAssetDecoder(),
                Clock.systemUTC(), System::nanoTime, new SecureRandom(), directory.resolve("partial-mc"));
        fixture.sink.players.add(new PlayerAppearanceSink.TrackedCapePlayer(remote, "Remote"));
        fixture.coordinator = CapeTestComposition.create(fixture.session, fixture.storage,
                new TextureCache(fixture.storage), fixture.sink, new ImmediateClient(), worker,
                CapeTestComposition.optifine(fixture.storage, read, Clock.systemUTC()),
                CapeTestComposition.skinMc(fixture.storage, read, Clock.systemUTC()), minecraftCapes,
                (id, capeId) -> Optional.empty());
        AtomicInteger completed = new AtomicInteger();
        java.util.function.Consumer<com.naocraftlab.skins.core.provider.ProviderObservation<ProviderCape>> completion =
                observation -> {
                    assertNotNull(observation, "current refresh must confirm its self observation");
                    completed.incrementAndGet();
                };
        fixture.coordinator.start();
        while (selfReads.get() == 0) worker.runNext();
        try (var busy = CapePreparationQueue.tryReserveScratch(CapePreparationQueue.CAPE_PREPARATION_PEAK)) {
            assertNotNull(busy);
            drainHeld(worker);
            assertEquals(1, selfReads.get());
            assertEquals(0, remoteReads.get());
            for (int refresh = 0; refresh < 2; refresh++) {
                switch (provider) {
                    case OPTIFINE -> refreshScoped(fixture, BuiltinProvider.OPTIFINE, completion);
                    case SKINMC -> refreshScoped(fixture, BuiltinProvider.SKINMC, completion);
                    case MINECRAFTCAPES -> refreshScoped(fixture, BuiltinProvider.MINECRAFTCAPES, completion);
                    default -> throw new AssertionError(provider);
                }
            }
            drainHeld(worker);
            assertEquals(0, completed.get());
        }
        drainHeld(worker);
        assertEquals(2, selfReads.get(), "explicit Refresh must reread a self completed before capacity wait");
        assertEquals(1, remoteReads.get(), "the still-unsent actor must remain one current request");
        assertEquals(2, completed.get());
    }

    @Test
    void capacityWaitingRefreshRetainsAllEnabledProvidersAndCurrentObserverDemand() throws Exception {
        Fixture fixture = fixture();
        fixture.coordinator.close();
        UUID self = fixture.session.identity.profileId();
        fixture.storage.updateAppearance(self, state -> state.withProviders(state.providers()
                .enable(AppearanceProviders.Component.CAPE, BuiltinProvider.SKINMC)
                .enable(AppearanceProviders.Component.CAPE, BuiltinProvider.MINECRAFTCAPES)));
        ManualExecutor worker = new ManualExecutor();
        AtomicInteger skinMcReads = new AtomicInteger();
        AtomicInteger mcReads = new AtomicInteger();
        SkinMcCapeReader skinMc = CapeTestComposition.skinMc(fixture.storage, (uri, timeout, maximum) -> {
            skinMcReads.incrementAndGet();
            return new OptifineCapeReader.Response(404, new byte[0]);
        }, Clock.systemUTC());
        fixture.coordinator = CapeTestComposition.create(fixture.session, fixture.storage,
                new TextureCache(fixture.storage), fixture.sink, new ImmediateClient(), worker,
                fixture.reader(), skinMc, minecraftCapes(fixture, mcReads, false),
                (id, capeId) -> Optional.empty());
        UUID remote = UUID.randomUUID();
        fixture.sink.players.add(new PlayerAppearanceSink.TrackedCapePlayer(remote, "Remote"));
        AtomicInteger completed = new AtomicInteger();
        try {
            try (var busy = CapePreparationQueue.tryReserveScratch(CapePreparationQueue.CAPE_PREPARATION_PEAK)) {
                assertNotNull(busy);
                fixture.coordinator.start();
                drainHeld(worker);
                refreshScoped(fixture, BuiltinProvider.OPTIFINE, ignored -> completed.incrementAndGet());
                refreshScoped(fixture, BuiltinProvider.SKINMC, ignored -> completed.incrementAndGet());
                refreshScoped(fixture, BuiltinProvider.MINECRAFTCAPES, ignored -> completed.incrementAndGet());
                fixture.coordinator.playerInfoUpdated(remote, "Remote");
                fixture.coordinator.playerInfoUpdated(remote, "Remote");
                drainHeld(worker);
                assertEquals(0, fixture.calls.get());
                assertEquals(0, skinMcReads.get());
                assertEquals(0, mcReads.get());
                assertEquals(0, completed.get(), "capacity wait is not a completed refresh failure");
            }
            drainHeld(worker);
            assertEquals(2, fixture.calls.get());
            assertEquals(2, skinMcReads.get());
            assertEquals(2, mcReads.get());
            assertEquals(3, completed.get());
        } finally {
            fixture.coordinator.close();
        }
    }

    @Test
    void disabledProviderAndDepartedActorCannotResumeCapacityWaitingDemand() throws Exception {
        Fixture fixture = fixture();
        fixture.coordinator.close();
        ManualExecutor worker = new ManualExecutor();
        fixture.coordinator = CapeTestComposition.create(fixture.session, fixture.storage,
                new TextureCache(fixture.storage), fixture.sink, new ImmediateClient(), worker,
                fixture.reader());
        UUID self = fixture.session.identity.profileId();
        UUID remote = UUID.randomUUID();
        fixture.sink.players.add(new PlayerAppearanceSink.TrackedCapePlayer(remote, "Remote"));
        try {
            try (var busy = CapePreparationQueue.tryReserveScratch(CapePreparationQueue.CAPE_PREPARATION_PEAK)) {
                assertNotNull(busy);
                fixture.coordinator.start();
                drainHeld(worker);
                fixture.coordinator.untrackedPlayer(remote);
                fixture.sink.players.clear();
                fixture.storage.updateAppearance(self, state -> state.withProviders(state.providers()
                        .disable(AppearanceProviders.Component.CAPE, BuiltinProvider.OPTIFINE)));
                fixture.coordinator.configurationChanged();
            }
            drainHeld(worker);
            assertEquals(0, fixture.calls.get());
        } finally {
            fixture.coordinator.close();
        }
    }

    @Test
    void sharedSkinMcChangesPreserveWarmMinecraftCapesAndMissingMaterializationRecoversLocally() throws Exception {
        Fixture fixture = fixture();
        fixture.coordinator.close();
        UUID self = fixture.session.identity.profileId();
        fixture.storage.updateAppearance(self, state -> state.withProviders(state.providers()
                .enable(AppearanceProviders.Component.CAPE, BuiltinProvider.SKINMC)
                .enable(AppearanceProviders.Component.CAPE, BuiltinProvider.MINECRAFTCAPES)
                .move(AppearanceProviders.Component.CAPE, BuiltinProvider.MINECRAFTCAPES, -1)));
        AtomicInteger mcReads = new AtomicInteger();
        SkinMcCapeReader skinMc = CapeTestComposition.skinMc(fixture.storage,
                (uri, timeout, maximum) -> new OptifineCapeReader.Response(500, new byte[0]), Clock.systemUTC());
        fixture.coordinator = CapeTestComposition.create(fixture.session, fixture.storage,
                new TextureCache(fixture.storage), fixture.sink, new ImmediateClient(), Runnable::run,
                fixture.reader(), skinMc, minecraftCapes(fixture, mcReads, true), (id, capeId) -> Optional.empty());
        try {
            fixture.coordinator.start();
            var before = CapeProjection.resolveSelf(self, "Self").orElseThrow();
            int registrations = fixture.sink.registrations;
            NclSkinsStorage sharedWriter = new NclSkinsStorage(directory, new PngValidator(), Clock.systemUTC());
            var snapshot = sharedWriter.updateAppearance(self, state -> state.withProviders(
                    new AppearanceProviders(state.providers().skin(), state.providers().cape().observeSkinmc(null))));
            fixture.coordinator.adoptSharedSnapshot(self, "Self", snapshot.providers());
            var after = CapeProjection.resolveSelf(self, "Self").orElseThrow();
            assertEquals(BuiltinProvider.MINECRAFTCAPES, after.provider());
            assertEquals(before.capeLocation(), after.capeLocation());
            assertTrue(after.capeGlint());
            assertEquals(registrations, fixture.sink.registrations);
            var remove = CapeProviderCoordinator.class.getDeclaredMethod("removeMinecraftCapes", CapeProjection.Identity.class);
            remove.setAccessible(true);
            remove.invoke(fixture.coordinator, new CapeProjection.Identity(self, "Self"));
            fixture.coordinator.adoptSharedSnapshot(self, "Self", snapshot.providers());
            assertEquals(BuiltinProvider.MINECRAFTCAPES,
                    CapeProjection.resolveSelf(self, "Self").orElseThrow().provider());
            assertEquals(1, mcReads.get(), "recovery must use cached bytes without HTTP");
        } finally {
            fixture.coordinator.close();
        }
    }

    @Test
    void sneakyConfigurationChangeSchedulesAfterFinalRemoteGenerationInvalidation() throws Exception {
        Fixture fixture = fixture();
        fixture.coordinator.close();
        UUID self = fixture.session.identity.profileId();
        ManualExecutor worker = new ManualExecutor();
        fixture.coordinator = CapeTestComposition.create(fixture.session, fixture.storage,
                new TextureCache(fixture.storage), fixture.sink, new ImmediateClient(), worker, fixture.reader());
        try {
            fixture.coordinator.start();
            drainHeld(worker);
            String skin = fixture.storage.storeAsset(sneakySkin()).sha256();
            fixture.storage.updateAppearance(self, state -> state.withProviders(new AppearanceProviders(
                    state.providers().skin().disable(BuiltinProvider.OFFLINE).observeMinecraft(
                            new com.naocraftlab.skins.core.provider.ProviderSkin(skin,
                                    com.naocraftlab.skins.core.model.SkinVariant.CLASSIC)),
                    state.providers().cape().disable(BuiltinProvider.OPTIFINE).enable(BuiltinProvider.SNEAKY))));
            fixture.coordinator.configurationChanged();
            drainHeld(worker);
            assertEquals(BuiltinProvider.SNEAKY,
                    CapeProjection.resolveSelf(self, "Self").orElseThrow().provider());
            assertNotNull(fixture.sink.registered.get("nclskins:sneaky/" + self));
        } finally {
            fixture.coordinator.close();
        }
    }

    private MinecraftCapesCapeReader minecraftCapes(Fixture fixture, AtomicInteger reads,
            boolean present) {
        MinecraftCapesCapeReader.Transport transport = new MinecraftCapesCapeReader.Transport() {
            public MinecraftCapesCapeReader.ProfileResponse profile(URI uri, Duration timeout,
                    long maximum) throws IOException {
                reads.incrementAndGet();
                String json = present ? "{\"cape_url\":\"https://textures.minecraftcapes.net/capes/"
                        + "a".repeat(64) + "\",\"capeGlint\":true}" : "{}";
                return new MinecraftCapesCapeReader.ProfileResponse(present ? 200 : 404,
                        MinecraftCapesCapeReader.parse(json.getBytes(java.nio.charset.StandardCharsets.UTF_8)), Map.of());
            }
            public MinecraftCapesCapeReader.Response image(URI uri, Duration timeout,
                    long maximum, Path destination) throws IOException {
                Files.write(destination, fixture.image);
                return new MinecraftCapesCapeReader.Response(200, new byte[0], Map.of());
            }
        };
        return new MinecraftCapesCapeReader(transport, new CapeAssetDecoder(), Clock.systemUTC(),
                System::nanoTime, new SecureRandom(), directory.resolve("mc-regression-scratch"));
    }

    @Test
    void transientWorldFramesRetainInstalledMaterialAndRetryLatestWithoutRemoteWork() throws Exception {
        FrameFixture f = frameFixture();
        String trusted = CapeProjection.resolveSelf(f.self(), "Self").orElseThrow().capeLocation();
        long revision = f.fixture().storage.loadAppearance(f.self()).intentRevision();
        f.faults().failures.set(1);
        f.now().set(TimeUnit.MILLISECONDS.toNanos(100));
        CapeProjection.resolveSelf(f.self(), "Self");
        drainHeld(f.worker());
        assertEquals(trusted, CapeProjection.resolveSelf(f.self(), "Self").orElseThrow().capeLocation());
        assertTrue(f.sink().live(trusted));
        assertTrue(CapeProjection.resolveSelf(f.self(), "Self").orElseThrow().capeGlint());
        int reads = f.faults().reads.get();
        f.now().set(TimeUnit.MILLISECONDS.toNanos(349));
        for (int i = 0; i < 50; i++) CapeProjection.resolveSelf(f.self(), "Self");
        drainHeld(f.worker());
        assertEquals(reads, f.faults().reads.get());
        f.now().set(TimeUnit.MILLISECONDS.toNanos(350));
        CapeProjection.resolveSelf(f.self(), "Self");
        drainHeld(f.worker());
        assertEquals(reads + 1, f.faults().reads.get());
        assertNotEquals(trusted, CapeProjection.resolveSelf(f.self(), "Self").orElseThrow().capeLocation());
        assertEquals(1, f.remoteReads().get());
        assertEquals(revision, f.fixture().storage.loadAppearance(f.self()).intentRevision());
    }

    @Test
    void exhaustedWorldFrameRetriesKeepTrustedFrameUntilMeaningfulReset() throws Exception {
        FrameFixture f = frameFixture();
        String trusted = CapeProjection.resolveSelf(f.self(), "Self").orElseThrow().capeLocation();
        f.faults().failures.set(99);
        for (long ms : new long[]{100, 350, 1350, 6350, 16350, 126350}) {
            f.now().set(TimeUnit.MILLISECONDS.toNanos(ms));
            for (int i = 0; i < 20; i++) CapeProjection.resolveSelf(f.self(), "Self");
            drainHeld(f.worker());
        }
        assertEquals(5, f.faults().reads.get());
        assertEquals(trusted, CapeProjection.resolveSelf(f.self(), "Self").orElseThrow().capeLocation());
        assertTrue(f.sink().live(trusted));
        assertEquals(1, f.remoteReads().get());
        f.faults().failures.set(0);
        int opens = f.faults().opens.get();
        refreshScoped(f.fixture(), BuiltinProvider.MINECRAFTCAPES, ignored -> {});
        drainHeld(f.worker());
        assertEquals(2, f.remoteReads().get());
        assertTrue(f.faults().opens.get() > opens);
        assertNotEquals(trusted, CapeProjection.resolveSelf(f.self(), "Self").orElseThrow().capeLocation());
    }

    @Test
    void closedWorldBackingReopensOnWorkerAndCorruptionRetiresOnlyVisual() throws Exception {
        FrameFixture f = frameFixture();
        String trusted = CapeProjection.resolveSelf(f.self(), "Self").orElseThrow().capeLocation();
        int opens = f.faults().opens.get();
        f.faults().closeNextRead.set(true);
        f.now().set(TimeUnit.MILLISECONDS.toNanos(100));
        CapeProjection.resolveSelf(f.self(), "Self");
        drainHeld(f.worker());
        assertEquals(trusted, CapeProjection.resolveSelf(f.self(), "Self").orElseThrow().capeLocation());
        f.now().set(TimeUnit.MILLISECONDS.toNanos(350));
        CapeProjection.resolveSelf(f.self(), "Self");
        assertEquals(opens, f.faults().opens.get());
        drainHeld(f.worker());
        assertEquals(opens + 1, f.faults().opens.get());
        String replacement = CapeProjection.resolveSelf(f.self(), "Self").orElseThrow().capeLocation();
        assertNotEquals(trusted, replacement);
        AtomicInteger observations = new AtomicInteger();
        f.fixture().coordinator.onCapeObservation(ignored -> observations.incrementAndGet());
        var confirmed = f.fixture().storage.loadAppearance(f.self()).providers().cape().minecraftcapes();
        try (var file = new java.io.RandomAccessFile(f.faults().asset.backing().toFile(), "rw")) {
            file.seek(4);
            file.write(1);
        }
        f.now().set(TimeUnit.MILLISECONDS.toNanos(400));
        CapeProjection.resolveSelf(f.self(), "Self");
        drainHeld(f.worker());
        assertNull(CapeProjection.resolveSelf(f.self(), "Self").orElseThrow().capeLocation());
        assertFalse(f.sink().live(replacement));
        assertEquals(confirmed, f.fixture().storage.loadAppearance(f.self()).providers().cape().minecraftcapes());
        assertEquals(0, observations.get());
        assertEquals(1, f.remoteReads().get());
    }

    @Test
    void initialBackingOpenRetriesTransientFailureAndExplicitRefreshAfterBoundedExhaustion() throws Exception {
        FrameFixture f = frameFixture(5, 0, false);
        for (long millis : new long[]{0, 249, 250, 1_249, 1_250, 6_249, 6_250, 16_250}) {
            f.now().set(TimeUnit.MILLISECONDS.toNanos(millis));
            CapeProjection.resolveSelf(f.self(), "Self");
            drainHeld(f.worker());
        }

        assertEquals(4, f.faults().opens.get(), "initial open receives one attempt plus three retries");
        assertTrue(f.sink().rawLocations.isEmpty());
        assertEquals(1, f.remoteReads().get(), "frame retry must not repeat provider IO");

        f.faults().transientOpenFailures.set(0);
        refreshScoped(f.fixture(), BuiltinProvider.MINECRAFTCAPES, ignored -> {});
        drainHeld(f.worker());
        assertEquals(5, f.faults().opens.get(), "explicit refresh resets the existing frame retry state");
        assertFalse(f.sink().rawLocations.isEmpty());
        assertEquals(2, f.remoteReads().get());
    }

    @Test
    void initialBackingIntegrityFailureRetiresVisualWithoutRetryingOrRemoteEmpty() throws Exception {
        FrameFixture f = frameFixture(0, 1, true);
        pumpFrameFixture(f);
        assertEquals(1, f.faults().opens.get());
        assertTrue(f.faults().lastOpenWasWorkerOwned,
                "integrity classification must happen on the frame worker");
        f.client().runAll();
        for (long millis : new long[]{250, 1_250, 6_250, 20_000}) {
            f.now().set(TimeUnit.MILLISECONDS.toNanos(millis));
            CapeProjection.resolveSelf(f.self(), "Self");
            pumpFrameFixture(f);
            f.client().runAll();
        }
        assertEquals(1, f.faults().opens.get(), "confirmed integrity failure must retire, not retry");
        assertEquals(1, f.remoteReads().get(), "local corruption must not be reported as provider absence");
        assertTrue(f.sink().rawLocations.isEmpty());
    }

    @Test
    void initialBackingOpenRunsOnFrameWorkerAndLateCompletionCannotPublishAfterClose() throws Exception {
        FrameFixture f = frameFixture(0, 0, true);
        pumpFrameFixture(f);

        assertEquals(1, f.faults().opens.get());
        assertTrue(f.faults().lastOpenWasWorkerOwned,
                "backing lease acquisition belongs to the frame worker, not client/render callback");
        assertTrue(f.sink().rawLocations.isEmpty());
        f.client().runInline(f.fixture().coordinator::close);
        f.client().runAll();
        assertTrue(f.sink().rawLocations.isEmpty(), "late frame completion must not publish after close");
    }

    private static void pumpFrameFixture(FrameFixture fixture) {
        int guard = 0;
        while (fixture.faults().opens.get() == 0 && guard++ < 100) {
            if (!fixture.worker().jobs.isEmpty()) fixture.worker().runNext();
            while (fixture.faults().opens.get() == 0 && fixture.client().hasJobs()) {
                fixture.client().runNext();
            }
        }
    }

    @Test
    void rejectedWorldUploadRetainsFrameAndLateClosedReadCannotRepublish() throws Exception {
        FrameFixture f = frameFixture();
        String trusted = CapeProjection.resolveSelf(f.self(), "Self").orElseThrow().capeLocation();
        f.sink().rejectFrame = true;
        f.now().set(TimeUnit.MILLISECONDS.toNanos(100));
        CapeProjection.resolveSelf(f.self(), "Self");
        drainHeld(f.worker());
        assertEquals(trusted, CapeProjection.resolveSelf(f.self(), "Self").orElseThrow().capeLocation());
        assertTrue(f.sink().live(trusted));
        f.sink().rejectFrame = false;
        f.now().set(TimeUnit.MILLISECONDS.toNanos(350));
        CapeProjection.resolveSelf(f.self(), "Self");
        drainHeld(f.worker());
        assertEquals(2, f.sink().rawLocations.size());
        f.now().set(TimeUnit.MILLISECONDS.toNanos(400));
        CapeProjection.resolveSelf(f.self(), "Self");
        assertEquals(1, f.worker().jobs.size());
        f.fixture().coordinator.close();
        drainHeld(f.worker());
        assertEquals(2, f.sink().rawLocations.size());
        try (var all = CapePreparationQueue.tryReserveCpu(64L * 1024 * 1024)) { assertNotNull(all); }
    }

    private FrameFixture frameFixture() throws Exception {
        return frameFixture(0, 0, false);
    }

    private FrameFixture frameFixture(int transientOpenFailures, int integrityOpenFailures,
            boolean queuedClient) throws Exception {
        Fixture fixture = fixture();
        fixture.coordinator.close();
        UUID self = fixture.session.identity.profileId();
        fixture.storage.updateAppearance(self, state -> state.withProviders(state.providers()
                .enable(AppearanceProviders.Component.CAPE, BuiltinProvider.MINECRAFTCAPES)
                .move(AppearanceProviders.Component.CAPE, BuiltinProvider.MINECRAFTCAPES, -1)));
        BufferedImage strip = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
        strip.setRGB(1, 1, 0xff112233);
        strip.setRGB(1, 33, 0x80445566);
        Path source = directory.resolve("retry.png");
        assertTrue(ImageIO.write(strip, "png", source.toFile()));
        var asset = new CapeAssetDecoder().prepare(source, directory.resolve("retry.frames"));
        var faults = new com.naocraftlab.skins.core.png.FrameReadFixture(asset);
        faults.transientOpenFailures.set(transientOpenFailures);
        faults.integrityOpenFailures.set(integrityOpenFailures);
        AtomicInteger remoteReads = new AtomicInteger();
        CapeObservationReader reader = new CapeObservationReader() {
            public Optional<Duration> cooldownRemaining() { return Optional.empty(); }
            public long accountEpoch() { return 0; }
            public void accountChanged() {}
            public Result observe(UUID account, String name, long epoch) {
                remoteReads.incrementAndGet();
                return new Result(Kind.PRESENT, null, asset, true, false);
            }
        };
        var delegate = new ProviderTextureStorageAdapter(fixture.storage, new TextureCache(fixture.storage));
        com.naocraftlab.skins.core.service.ProviderTextureStore textures = new com.naocraftlab.skins.core.service.ProviderTextureStore() {
            public byte[] load(URI uri) { throw new AssertionError("HTTP"); }
            public Optional<byte[]> readIfCached(URI uri) throws IOException { return delegate.readIfCached(uri); }
            public Optional<byte[]> readIfCached(String key) throws IOException { return delegate.readIfCached(key); }
            public String cacheKey(URI uri) { return delegate.cacheKey(uri); }
            public String storeObservedCape(PngValidator.CapePng cape) { throw new AssertionError("unexpected static"); }
            public Optional<byte[]> readLocalCape(UUID account, String key) throws IOException, com.naocraftlab.skins.core.png.PngValidationException {
                return delegate.readLocalCape(account, key);
            }
            public Optional<com.naocraftlab.skins.core.png.PreparedCapeAsset> readObservedPreparedCape(String key) {
                return asset.visualSha256().equals(key) ? Optional.of(faults.asset) : Optional.empty();
            }
        };
        RegistryBackedSink sink = new RegistryBackedSink();
        ManualExecutor worker = new ManualExecutor();
        faults.workerContext = () -> worker.running;
        QueuedClient queued = queuedClient ? new QueuedClient() : null;
        AtomicLong now = new AtomicLong();
        fixture.coordinator = new CapeProviderCoordinator(fixture.session,
                new com.naocraftlab.skins.core.storage.AccountAppearanceStorageAdapter(fixture.storage),
                new com.naocraftlab.skins.core.storage.LibraryStorageAdapter(fixture.storage), textures,
                sink, queued == null ? new ImmediateClient() : queued, worker, fixture.reader(),
                CapeTestComposition.skinMc(fixture.storage, (uri, timeout, maximum) -> new OptifineCapeReader.Response(404, new byte[0]), Clock.systemUTC()),
                reader, (id, cape) -> Optional.empty(), now::get);
        fixture.coordinator.start();
        if (queued == null) drainHeld(worker);
        return new FrameFixture(fixture, self, sink, worker, now, faults, remoteReads, queued);
    }

    private record FrameFixture(Fixture fixture, UUID self, RegistryBackedSink sink,
            ManualExecutor worker, AtomicLong now, com.naocraftlab.skins.core.png.FrameReadFixture faults,
            AtomicInteger remoteReads, QueuedClient client) {}

    @Test
    void animatedProviderFrameLocationRemainsLiveAcrossWorldAndMenuTimelineWrap() throws Exception {
        Fixture fixture = fixture();
        fixture.coordinator.close();
        UUID self = fixture.session.identity.profileId();
        fixture.storage.updateAppearance(self, state -> state.withProviders(state.providers()
                .enable(AppearanceProviders.Component.CAPE, BuiltinProvider.MINECRAFTCAPES)
                .move(AppearanceProviders.Component.CAPE, BuiltinProvider.MINECRAFTCAPES, -1)));
        BufferedImage strip = new BufferedImage(64, 96, BufferedImage.TYPE_INT_ARGB);
        for (int index = 0; index < 3; index++) {
            strip.setRGB(1, index * 32 + 1, 0xff102030 + index * 0x001100);
        }
        ByteArrayOutputStream encoded = new ByteArrayOutputStream();
        ImageIO.write(strip, "png", encoded);
        String imageUri = "https://textures.minecraftcapes.net/capes/" + "b".repeat(64);
        MinecraftCapesCapeReader reader = new MinecraftCapesCapeReader(
                new MinecraftCapesCapeReader.Transport() {
                    @Override public MinecraftCapesCapeReader.ProfileResponse profile(
                            URI uri, Duration timeout, long maxBytes) throws IOException {
                        byte[] body = ("{\"cape_url\":\"" + imageUri + "\",\"capeGlint\":true}")
                                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
                        return new MinecraftCapesCapeReader.ProfileResponse(200,
                                MinecraftCapesCapeReader.parse(body), Map.of());
                    }
                    @Override public MinecraftCapesCapeReader.Response image(
                            URI uri, Duration timeout, long maxBytes, Path destination) throws IOException {
                        Files.write(destination, encoded.toByteArray());
                        return new MinecraftCapesCapeReader.Response(200, new byte[0], Map.of());
                    }
                }, new CapeAssetDecoder(), Clock.systemUTC(), System::nanoTime,
                new SecureRandom(), directory.resolve("timeline-staging"));
        RegistryBackedSink sink = new RegistryBackedSink();
        ManualExecutor worker = new ManualExecutor();
        fixture.coordinator = CapeTestComposition.create(fixture.session, fixture.storage,
                new TextureCache(fixture.storage), sink, new ImmediateClient(), worker,
                fixture.reader(), new SkinMcCapeReader(fixture.storage.layout().root()
                        .resolve("timeline-skinmc-staging")), reader, (id, capeId) -> Optional.empty());
        fixture.coordinator.start();
        drainHeld(worker);
        String first = CapeProjection.resolveSelf(self, "Self").orElseThrow().capeLocation();
        assertTrue(sink.live(first));
        assertTrue(CapeProjection.resolveSelf(self, "Self").orElseThrow().capeGlint());

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (sink.rawLocations.size() < 3 && System.nanoTime() < deadline) {
            CapeProjection.resolveSelf(self, "Self");
            drainHeld(worker);
            Thread.sleep(15L);
        }
        assertEquals(3, sink.rawLocations.size(), "three distinct native frames must be installed");
        assertFalse(sink.live(first), "the native registry retires frame zero after frame two");
        String third = sink.rawLocations.get(2);
        assertNotEquals(first, third);
        assertEquals(third, CapeProjection.resolveSelf(self, "Self").orElseThrow().capeLocation());
        assertEquals(third, CapeProjection.resolve(self, "Self", null, null, true).capeLocation());
        assertTrue(CapeProjection.resolveSelf(self, "Self").orElseThrow().capeGlint());
        assertTrue(CapeProjection.resolve(self, "Self", null, null, true).capeGlint());
        assertTrue(sink.live(third), "both world and menu must see an installed frame");

        while (sink.rawLocations.size() < 5 && System.nanoTime() < deadline) {
            CapeProjection.resolveSelf(self, "Self");
            drainHeld(worker);
            Thread.sleep(15L);
        }
        assertEquals(5, sink.rawLocations.size(), "animation must wrap and continue");
        String fifth = sink.rawLocations.get(4);
        assertEquals(fifth, CapeProjection.resolveSelf(self, "Self").orElseThrow().capeLocation());
        assertEquals(fifth, CapeProjection.resolve(self, "Self", null, null, true).capeLocation());
        assertTrue(CapeProjection.resolveSelf(self, "Self").orElseThrow().capeGlint());
        assertTrue(CapeProjection.resolve(self, "Self", null, null, true).capeGlint());
        assertTrue(sink.live(fifth));
        fixture.coordinator.close();
        sink.registry.close();
    }

    @Test
    void otherObservedAnimatedProvidersPublishEachInstalledFrame() throws Exception {
        assertObservedAnimatedProviderPublishesFrames(BuiltinProvider.OPTIFINE);
        assertObservedAnimatedProviderPublishesFrames(BuiltinProvider.SKINMC);
    }

    private void assertObservedAnimatedProviderPublishesFrames(BuiltinProvider provider) throws Exception {
        Fixture fixture = fixture();
        fixture.coordinator.close();
        UUID self = fixture.session.identity.profileId();
        fixture.storage.updateAppearance(self, state -> state.withProviders(state.providers()
                .enable(AppearanceProviders.Component.CAPE, provider)
                .move(AppearanceProviders.Component.CAPE, provider, -1)
                .disable(AppearanceProviders.Component.CAPE,
                        provider == BuiltinProvider.SKINMC
                                ? BuiltinProvider.OPTIFINE : BuiltinProvider.SKINMC)));
        BufferedImage strip = new BufferedImage(64, 96, BufferedImage.TYPE_INT_ARGB);
        for (int index = 0; index < 3; index++) {
            strip.setRGB(1, index * 32 + 1, 0xff102030 + index * 0x001100);
        }
        ByteArrayOutputStream encoded = new ByteArrayOutputStream();
        ImageIO.write(strip, "png", encoded);
        fixture.image = encoded.toByteArray();
        fixture.status = 200;
        RegistryBackedSink sink = new RegistryBackedSink();
        ManualExecutor worker = new ManualExecutor();
        SkinMcCapeReader skinMc = CapeTestComposition.skinMc(fixture.storage,
                (uri, timeout, maxBytes) -> new OptifineCapeReader.Response(200, fixture.image,
                        Map.of("Content-Type", List.of("image/png"))), Clock.systemUTC());
        fixture.coordinator = CapeTestComposition.create(fixture.session, fixture.storage,
                new TextureCache(fixture.storage), sink, new ImmediateClient(), worker,
                fixture.reader(), skinMc, (id, capeId) -> Optional.empty());
        fixture.coordinator.start();
        drainHeld(worker);
        String first = CapeProjection.resolveSelf(self, "Self").orElseThrow().capeLocation();
        assertTrue(sink.live(first), provider.name());
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (sink.rawLocations.size() < 3 && System.nanoTime() < deadline) {
            CapeProjection.resolveSelf(self, "Self");
            drainHeld(worker);
            Thread.sleep(15L);
        }
        assertEquals(3, sink.rawLocations.size(), provider.name());
        String third = sink.rawLocations.get(2);
        assertFalse(sink.live(first), provider.name());
        assertEquals(third, CapeProjection.resolveSelf(self, "Self").orElseThrow().capeLocation(),
                provider.name());
        assertEquals(third, CapeProjection.resolve(self, "Self", null, null, true).capeLocation(),
                provider.name());
        assertTrue(sink.live(third), provider.name());
        fixture.coordinator.close();
        sink.registry.close();
    }

    @Test
    void selfCapeReplacementKeepsPublishedNativeLocationWhilePreparationIsPending()
            throws Exception {
        Fixture fixture = fixture();
        fixture.coordinator.close();
        UUID self = fixture.session.identity.profileId();
        var entry = fixture.storage.importCape(self, "First", png());
        ProviderCape first = new ProviderCape("local:first", entry.texture().sha256(),
                entry.texture().hasElytra());
        fixture.storage.updateAppearance(self, state -> new AccountAppearanceState(
                AccountAppearanceState.CURRENT_SCHEMA_VERSION, self, 1L, null, null, null,
                first.id(), OuterLayerVisibility.allVisible(), AppearanceSyncStatus.LOCAL_ONLY,
                0L, Instant.now(), state.providers().select(1L, null, first, null)));
        ManualExecutor held = new ManualExecutor();
        fixture.coordinator = CapeTestComposition.create(fixture.session, fixture.storage,
                new TextureCache(fixture.storage), fixture.sink, new ImmediateClient(), held,
                fixture.reader());
        fixture.coordinator.start();
        drainHeld(held);
        String oldLocation = CapeProjection.resolveSelf(self, "Self").orElseThrow().capeLocation();
        assertNotNull(fixture.sink.registered.get(oldLocation));

        ProviderCape second = new ProviderCape("local:second", entry.texture().sha256(),
                entry.texture().hasElytra());
        AppearanceProviders next = fixture.storage.updateAppearance(self, state ->
                new AccountAppearanceState(AccountAppearanceState.CURRENT_SCHEMA_VERSION, self,
                        2L, null, null, null, second.id(), OuterLayerVisibility.allVisible(),
                        AppearanceSyncStatus.LOCAL_ONLY, 0L, Instant.now(),
                        state.providers().select(2L, null, second, null))).providers();
        fixture.coordinator.selfCapeCandidatesChanged(self, "Self", next);
        assertEquals(oldLocation, CapeProjection.resolveSelf(self, "Self").orElseThrow().capeLocation());
        assertNotNull(fixture.sink.registered.get(oldLocation),
                "a published menu cape must remain registered while replacement is pending");
        drainHeld(held);
        String ready = CapeProjection.resolveSelf(self, "Self").orElseThrow().capeLocation();
        assertNotNull(fixture.sink.registered.get(ready));
        fixture.coordinator.close();
    }

    @Test
    void minecraftCapesGlintOnlyRefreshKeepsHandleAndReplacementInstallsBeforeRelease() throws Exception {
        Fixture fixture = fixture();
        fixture.coordinator.close();
        UUID self = fixture.session.identity.profileId();
        fixture.storage.updateAppearance(self, state -> state.withProviders(state.providers()
                .enable(AppearanceProviders.Component.CAPE, BuiltinProvider.MINECRAFTCAPES)
                .move(AppearanceProviders.Component.CAPE, BuiltinProvider.MINECRAFTCAPES, -1)));
        AtomicBoolean glint = new AtomicBoolean(false);
        BufferedImage strip = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
        strip.setRGB(1, 1, 0xffff0000);
        strip.setRGB(1, 33, 0xff0000ff);
        ByteArrayOutputStream animated = new ByteArrayOutputStream();
        ImageIO.write(strip, "png", animated);
        byte[][] current = {animated.toByteArray()};
        AtomicInteger reads = new AtomicInteger();
        AtomicBoolean malformedProfile = new AtomicBoolean(false);
        String imageUri = "https://textures.minecraftcapes.net/capes/" + "a".repeat(64);
        MinecraftCapesCapeReader.Transport transport = new MinecraftCapesCapeReader.Transport() {
            @Override public MinecraftCapesCapeReader.ProfileResponse profile(URI uri,
                    Duration timeout, long maxBytes) throws IOException {
                reads.incrementAndGet();
                if (malformedProfile.get()) {
                    return new MinecraftCapesCapeReader.ProfileResponse(200,
                            MinecraftCapesCapeReader.parse(new byte[]{'{', '"', 'x', '"', ':', '"',
                                    (byte) 0xff, '"', '}'}), Map.of());
                }
                byte[] body = ("{\"cape_url\":\"" + imageUri + "\",\"capeGlint\":" + glint.get() + "}")
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8);
                return new MinecraftCapesCapeReader.ProfileResponse(200,
                        MinecraftCapesCapeReader.parse(body), Map.of());
            }
            @Override public MinecraftCapesCapeReader.Response image(URI uri, Duration timeout,
                    long maxBytes, Path destination) throws IOException {
                Files.write(destination, current[0]);
                return new MinecraftCapesCapeReader.Response(200, new byte[0], Map.of());
            }
        };
        MinecraftCapesCapeReader reader = new MinecraftCapesCapeReader(transport,
                new CapeAssetDecoder(), Clock.systemUTC(), System::nanoTime,
                new SecureRandom(), directory.resolve("mc-scratch"));
        fixture.coordinator = CapeTestComposition.create(fixture.session, fixture.storage,
                new TextureCache(fixture.storage), fixture.sink, new ImmediateClient(), Runnable::run,
                fixture.reader(), new SkinMcCapeReader(fixture.storage.layout().root().resolve("test-skinmc-staging")), reader, (id, capeId) -> Optional.empty());
        fixture.coordinator.start();
        int registrations = fixture.sink.registrations;
        String location = "nclskins:minecraftcapes/" + self;
        assertNotNull(fixture.sink.registered.get(location));
        assertFalse(CapeProjection.resolveSelf(self, "Self").orElseThrow().capeGlint());

        glint.set(true);
        refreshScoped(fixture, BuiltinProvider.MINECRAFTCAPES, ignored -> {});
        assertEquals(registrations, fixture.sink.registrations,
                "material-only refresh must reuse the current native handle");
        assertTrue(CapeProjection.resolveSelf(self, "Self").orElseThrow().capeGlint());
        int beforeNextFrame = fixture.sink.rawRegistrations;
        Thread.sleep(140);
        assertTrue(CapeProjection.resolveSelf(self, "Self").orElseThrow().capeGlint());
        assertEquals(beforeNextFrame + 1, fixture.sink.rawRegistrations,
                "material-only refresh must keep the animated frame source active");

        malformedProfile.set(true);
        refreshScoped(fixture, BuiltinProvider.MINECRAFTCAPES, ignored -> {});
        assertTrue(CapeProjection.resolveSelf(self, "Self").orElseThrow().capeGlint(),
                "malformed profile must retain the previous installed material");
        assertNotNull(fixture.sink.registered.get(location));
        malformedProfile.set(false);

        current[0] = pngWithPixel(0xffaabbcc);
        int beforeReplacement = fixture.sink.events.size();
        refreshScoped(fixture, BuiltinProvider.MINECRAFTCAPES, ignored -> {});
        assertEquals("register", fixture.sink.events.get(beforeReplacement),
                "successor is registered before the previous native texture is released");
        assertTrue(reads.get() >= 3);
        fixture.coordinator.close();
        assertFalse(fixture.sink.registered.containsKey(location));
    }

    @Test
    void offlineAnimatedDescriptorPlaysAfterSourceRemovalAndRestart() throws Exception {
        Fixture fixture = fixture();
        fixture.coordinator.close();
        UUID self = fixture.session.identity.profileId();
        BufferedImage sheet = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
        sheet.setRGB(1, 1, 0xff123456);
        sheet.setRGB(1, 33, 0xff654321);
        Path source = directory.resolve("animated.png");
        ImageIO.write(sheet, "png", source.toFile());
        var entry = fixture.storage.importCape(self, "Animated", source);
        Files.delete(source);
        fixture.storage.updateAppearance(self, state -> {
            ProviderCape local = new ProviderCape("local:animated", entry.texture().sha256(),
                    entry.texture().hasElytra());
            return new AccountAppearanceState(AccountAppearanceState.CURRENT_SCHEMA_VERSION,
                    self, 1L, null, null, null, local.id(), OuterLayerVisibility.allVisible(),
                    AppearanceSyncStatus.LOCAL_ONLY, 0L, Instant.now(),
                    state.providers().select(1L, null, local, null));
        });

        fixture.coordinator = CapeTestComposition.create(fixture.session, fixture.storage,
                new TextureCache(fixture.storage), fixture.sink, new ImmediateClient(), Runnable::run,
                fixture.reader());
        fixture.coordinator.start();
        String location = "nclskins:offline/" + self;
        byte[] first = fixture.sink.registered.get(location);
        assertNotNull(first);
        assertEquals(BuiltinProvider.OFFLINE,
                CapeProjection.resolveSelf(self, "Self").orElseThrow().provider());
        Thread.sleep(120L);
        CapeProjection.resolveSelf(self, "Self");
        byte[] second = fixture.sink.registered.get(location);
        assertNotNull(second);
        assertFalse(java.util.Arrays.equals(first, second), "frame demand uploads the next frame");

        fixture.coordinator.close();
        assertFalse(fixture.sink.registered.containsKey(location));
        fixture.coordinator = CapeTestComposition.create(fixture.session, fixture.storage,
                new TextureCache(fixture.storage), fixture.sink, new ImmediateClient(), Runnable::run,
                fixture.reader());
        fixture.coordinator.start();
        assertArrayEquals(first, fixture.sink.registered.get(location),
                "durable descriptor restores frame zero without the source file");
    }

    @Test
    void skinMcUsesSharedPriorityAbsenceAndSourceScopedHandles() throws Exception {
        Fixture fixture = fixture();
        fixture.coordinator.close();
        fixture.storage.updateAppearance(fixture.session.identity.profileId(), state ->
                state.withProviders(state.providers().enable(AppearanceProviders.Component.CAPE,
                        BuiltinProvider.SKINMC).move(AppearanceProviders.Component.CAPE,
                                BuiltinProvider.SKINMC, -1)));
        fixture.status = 200;
        AtomicInteger skinMcCalls = new AtomicInteger();
        AtomicInteger skinMcStatus = new AtomicInteger(200);
        SkinMcCapeReader skinMc = CapeTestComposition.skinMc(fixture.storage, (uri, timeout, maxBytes) -> {
            skinMcCalls.incrementAndGet();
            return new OptifineCapeReader.Response(skinMcStatus.get(),
                    skinMcStatus.get() == 200 ? fixture.image : new byte[0],
                    Map.of("Content-Type", List.of("image/png")));
        }, Clock.systemUTC());
        fixture.coordinator = CapeTestComposition.create(fixture.session, fixture.storage,
                new TextureCache(fixture.storage), fixture.sink, new ImmediateClient(),
                Runnable::run, fixture.reader(), skinMc, (id, capeId) -> Optional.empty());
        fixture.coordinator.start();
        UUID self = fixture.session.identity.profileId();
        assertEquals(BuiltinProvider.SKINMC,
                CapeProjection.resolve(self, "Self", null, null, true).provider());
        assertTrue(fixture.sink.registered.containsKey("nclskins:skinmc/" + self));
        UUID remote = UUID.randomUUID();
        fixture.sink.players.add(new PlayerAppearanceSink.TrackedCapePlayer(remote, "Remote"));
        fixture.coordinator.trackedPlayer(remote, "Remote");
        assertEquals(BuiltinProvider.SKINMC,
                CapeProjection.resolve(remote, "Remote", null, null, false).provider());
        assertNull(CapeProjection.resolve(remote, "Renamed", null, null, false).provider());
        assertTrue(fixture.sink.registered.containsKey("nclskins:skinmc/" + remote));
        int beforeReorder = skinMcCalls.get();
        fixture.storage.updateAppearance(self, state -> state.withProviders(
                state.providers().move(AppearanceProviders.Component.CAPE, BuiltinProvider.SKINMC, 1)));
        fixture.coordinator.configurationChanged();
        assertEquals(beforeReorder, skinMcCalls.get());
        skinMcStatus.set(404);
        refreshScoped(fixture, BuiltinProvider.SKINMC, ignored -> {});
        assertEquals(BuiltinProvider.OPTIFINE,
                CapeProjection.resolve(self, "Self", null, null, true).provider());
        fixture.storage.updateAppearance(self, state -> state.withProviders(
                state.providers().disable(AppearanceProviders.Component.CAPE, BuiltinProvider.SKINMC)));
        fixture.coordinator.configurationChanged();
        refreshScoped(fixture, BuiltinProvider.SKINMC, ignored -> {});
        assertEquals(beforeReorder + 2, skinMcCalls.get());
        assertFalse(fixture.sink.registered.containsKey("nclskins:skinmc/" + self));
        assertFalse(fixture.sink.registered.containsKey("nclskins:skinmc/" + remote));
    }

    @Test
    void skinMcCooldownRetainsLatestCurrentActorMarkersAndLeavesOptifineAvailable() throws Exception {
        Fixture fixture = fixture();
        fixture.coordinator.close();
        UUID self = fixture.session.identity.profileId();
        fixture.storage.updateAppearance(self, state -> state.withProviders(
                state.providers().enable(AppearanceProviders.Component.CAPE, BuiltinProvider.SKINMC)));
        MutableClock clock = new MutableClock();
        AtomicInteger skinMcCalls = new AtomicInteger();
        SkinMcCapeReader skinMc = CapeTestComposition.skinMc(fixture.storage, (uri, timeout, maxBytes) -> {
            skinMcCalls.incrementAndGet();
            return new OptifineCapeReader.Response(429, new byte[0],
                    Map.of("Retry-After", List.of("120")));
        }, clock);
        fixture.coordinator = CapeTestComposition.create(fixture.session, fixture.storage,
                new TextureCache(fixture.storage), fixture.sink, new ImmediateClient(),
                Runnable::run, fixture.reader(), skinMc, (id, capeId) -> Optional.empty());
        fixture.coordinator.start();
        assertEquals(1, skinMcCalls.get());
        assertTrue(fixture.coordinator.cooldownRemaining(BuiltinProvider.SKINMC).isPresent());
        assertTrue(fixture.coordinator.cooldownRemaining(BuiltinProvider.OPTIFINE).isEmpty());
        UUID remote = UUID.randomUUID();
        fixture.sink.players.add(new PlayerAppearanceSink.TrackedCapePlayer(remote, "Remote"));
        fixture.coordinator.trackedPlayer(remote, "Remote");
        for (int index = 0; index < 10; index++) fixture.coordinator.playerInfoUpdated(remote, "Remote");
        fixture.coordinator.refresh();
        assertEquals(1, skinMcCalls.get());
        assertTrue(fixture.calls.get() >= 1);
        fixture.coordinator.untrackedPlayer(remote);
        clock.advanceSeconds(121);
        refreshScoped(fixture, BuiltinProvider.SKINMC, ignored -> {});
        assertEquals(2, skinMcCalls.get());
    }

    @Test
    void accountSwitchKeepsBothServiceCooldownsAndDropsOldActorWork() throws Exception {
        Fixture fixture = fixture();
        fixture.coordinator.close();
        UUID oldAccount = fixture.session.identity.profileId();
        UUID nextAccount = UUID.randomUUID();
        for (UUID account : List.of(oldAccount, nextAccount)) {
            fixture.storage.loadOrCreateAccount(account);
            fixture.storage.updateAppearance(account, state -> state.withProviders(state.providers()
                    .enable(AppearanceProviders.Component.CAPE, BuiltinProvider.OPTIFINE)
                    .enable(AppearanceProviders.Component.CAPE, BuiltinProvider.SKINMC)));
        }
        MutableClock clock = new MutableClock();
        List<String> optifineNames = new ArrayList<>();
        List<UUID> skinMcProfiles = new ArrayList<>();
        OptifineCapeReader optifine = CapeTestComposition.optifine(fixture.storage, (uri, timeout, maxBytes) -> {
            optifineNames.add(uri.getPath().substring("/capes/".length()).replace(".png", ""));
            return new OptifineCapeReader.Response(optifineNames.size() == 1 ? 429 : 404,
                    new byte[0], optifineNames.size() == 1
                            ? Map.of("Retry-After", List.of("120")) : Map.of());
        }, clock);
        SkinMcCapeReader skinMc = CapeTestComposition.skinMc(fixture.storage, (uri, timeout, maxBytes) -> {
            skinMcProfiles.add(UUID.fromString(uri.getPath().substring(uri.getPath().lastIndexOf('/') + 1)));
            return new OptifineCapeReader.Response(skinMcProfiles.size() == 1 ? 429 : 404,
                    new byte[0], skinMcProfiles.size() == 1
                            ? Map.of("Retry-After", List.of("120")) : Map.of());
        }, clock);
        fixture.coordinator = CapeTestComposition.create(fixture.session, fixture.storage,
                new TextureCache(fixture.storage), fixture.sink, new ImmediateClient(),
                Runnable::run, optifine, skinMc, (id, capeId) -> Optional.empty());
        fixture.coordinator.start();
        assertEquals(List.of("Self"), optifineNames);
        assertEquals(List.of(oldAccount), skinMcProfiles);

        UUID oldTracked = UUID.randomUUID();
        fixture.sink.players.add(new PlayerAppearanceSink.TrackedCapePlayer(oldTracked, "Remote"));
        fixture.coordinator.trackedPlayer(oldTracked, "Remote");
        fixture.sink.players.clear();
        fixture.session.identity = new GameSessionTokenSource.SessionIdentity(nextAccount, "Next");
        fixture.coordinator.configurationChanged();
        for (int attempt = 0; attempt < 5; attempt++) {
            fixture.coordinator.refresh();
            refreshScoped(fixture, BuiltinProvider.SKINMC, ignored -> {});
        }
        assertEquals(List.of("Self"), optifineNames);
        assertEquals(List.of(oldAccount), skinMcProfiles);
        assertEquals(java.time.Duration.ofSeconds(120),
                fixture.coordinator.cooldownRemaining(BuiltinProvider.OPTIFINE).orElseThrow());
        assertEquals(java.time.Duration.ofSeconds(120),
                fixture.coordinator.cooldownRemaining(BuiltinProvider.SKINMC).orElseThrow());

        clock.advanceSeconds(120);
        fixture.coordinator.refresh();
        refreshScoped(fixture, BuiltinProvider.SKINMC, ignored -> {});
        assertEquals(List.of("Self", "Next"), optifineNames);
        assertEquals(List.of(oldAccount, nextAccount), skinMcProfiles);
    }

    @Test
    void combinedRefreshAfterCooldownConsumesOneLatestSkinMcMarker() throws Exception {
        Fixture fixture = fixture();
        fixture.coordinator.close();
        UUID self = fixture.session.identity.profileId();
        fixture.storage.updateAppearance(self, state -> state.withProviders(state.providers()
                .enable(AppearanceProviders.Component.CAPE, BuiltinProvider.SKINMC)));
        MutableClock clock = new MutableClock();
        AtomicInteger skinMcReads = new AtomicInteger();
        SkinMcCapeReader skinMc = CapeTestComposition.skinMc(fixture.storage, (uri, timeout, maxBytes) -> {
            int read = skinMcReads.incrementAndGet();
            return new OptifineCapeReader.Response(read == 1 ? 429 : 404, new byte[0],
                    read == 1 ? Map.of("Retry-After", List.of("120")) : Map.of());
        }, clock);
        fixture.coordinator = CapeTestComposition.create(fixture.session, fixture.storage,
                new TextureCache(fixture.storage), fixture.sink, new ImmediateClient(),
                Runnable::run, fixture.reader(), skinMc, (id, capeId) -> Optional.empty());
        fixture.coordinator.start();
        for (int attempt = 0; attempt < 5; attempt++) {
            fixture.coordinator.refresh();
            refreshScoped(fixture, BuiltinProvider.SKINMC, ignored -> {});
        }
        assertEquals(1, skinMcReads.get());

        clock.advanceSeconds(120);
        fixture.coordinator.refresh();
        refreshScoped(fixture, BuiltinProvider.SKINMC, ignored -> {});
        assertEquals(2, skinMcReads.get(), "one combined Refresh admits one SkinMC GET");
    }

    @Test
    void skinMcCooldownCoalescesSelfAndTrackedActorWithoutReplayBurst() throws Exception {
        Fixture fixture = fixture();
        fixture.coordinator.close();
        UUID self = fixture.session.identity.profileId();
        UUID remote = UUID.randomUUID();
        fixture.sink.players.add(new PlayerAppearanceSink.TrackedCapePlayer(remote, "Remote"));
        fixture.storage.updateAppearance(self, state -> state.withProviders(
                state.providers().enable(AppearanceProviders.Component.CAPE, BuiltinProvider.SKINMC)));
        MutableClock clock = new MutableClock();
        AtomicInteger calls = new AtomicInteger();
        SkinMcCapeReader skinMc = CapeTestComposition.skinMc(fixture.storage, (uri, timeout, maxBytes) -> {
            int call = calls.incrementAndGet();
            return new OptifineCapeReader.Response(call == 1 ? 429 : 404, new byte[0],
                    call == 1 ? Map.of("Retry-After", List.of("120")) : Map.of());
        }, clock);
        fixture.coordinator = CapeTestComposition.create(fixture.session, fixture.storage,
                new TextureCache(fixture.storage), fixture.sink, new ImmediateClient(),
                Runnable::run, fixture.reader(), skinMc, (id, capeId) -> Optional.empty());
        fixture.coordinator.start();
        for (int index = 0; index < 10; index++) {
            fixture.coordinator.playerInfoUpdated(remote, "Remote");
            refreshScoped(fixture, BuiltinProvider.SKINMC, ignored -> {});
        }
        assertEquals(1, calls.get());
        clock.advanceSeconds(121);
        refreshScoped(fixture, BuiltinProvider.SKINMC, ignored -> {});
        assertEquals(3, calls.get());
        assertEquals(0, fixture.sink.registered.size());
    }

    @Test
    void optifineCooldownDoesNotBlockSkinMcLookup() throws Exception {
        Fixture fixture = fixture();
        fixture.coordinator.close();
        UUID self = fixture.session.identity.profileId();
        fixture.storage.updateAppearance(self, state -> state.withProviders(
                state.providers().enable(AppearanceProviders.Component.CAPE, BuiltinProvider.SKINMC)));
        fixture.status = 429;
        AtomicInteger skinMcCalls = new AtomicInteger();
        SkinMcCapeReader skinMc = CapeTestComposition.skinMc(fixture.storage, (uri, timeout, maxBytes) -> {
            skinMcCalls.incrementAndGet();
            return new OptifineCapeReader.Response(200, fixture.image,
                    Map.of("Content-Type", List.of("image/png")));
        }, Clock.systemUTC());
        fixture.coordinator = CapeTestComposition.create(fixture.session, fixture.storage,
                new TextureCache(fixture.storage), fixture.sink, new ImmediateClient(),
                Runnable::run, fixture.reader(), skinMc, (id, capeId) -> Optional.empty());
        fixture.coordinator.start();
        assertEquals(1, skinMcCalls.get());
        assertTrue(fixture.coordinator.cooldownRemaining(BuiltinProvider.OPTIFINE).isPresent());
        assertTrue(fixture.coordinator.cooldownRemaining(BuiltinProvider.SKINMC).isEmpty());
        assertEquals(BuiltinProvider.SKINMC,
                CapeProjection.resolve(self, "Self", null, null, true).provider());
    }

    @Test
    void sharedSkinMcAbsenceReleasesOnlyItsConfirmedSourceWithoutLookup() throws Exception {
        Fixture fixture = fixture();
        fixture.coordinator.close();
        UUID self = fixture.session.identity.profileId();
        fixture.storage.updateAppearance(self, state -> state.withProviders(
                state.providers().enable(AppearanceProviders.Component.CAPE, BuiltinProvider.SKINMC)
                        .move(AppearanceProviders.Component.CAPE, BuiltinProvider.SKINMC, -1)));
        fixture.status = 200;
        AtomicInteger skinMcCalls = new AtomicInteger();
        SkinMcCapeReader skinMc = CapeTestComposition.skinMc(fixture.storage, (uri, timeout, maxBytes) -> {
            skinMcCalls.incrementAndGet();
            return new OptifineCapeReader.Response(200, fixture.image,
                    Map.of("Content-Type", List.of("image/png")));
        }, Clock.systemUTC());
        fixture.coordinator = CapeTestComposition.create(fixture.session, fixture.storage,
                new TextureCache(fixture.storage), fixture.sink, new ImmediateClient(),
                Runnable::run, fixture.reader(), skinMc, (id, capeId) -> Optional.empty());
        fixture.coordinator.start();
        assertEquals(BuiltinProvider.SKINMC,
                CapeProjection.resolve(self, "Self", null, null, true).provider());
        int before = skinMcCalls.get();
        var absent = fixture.storage.updateAppearance(self, state -> state.withProviders(
                new AppearanceProviders(state.providers().skin(),
                        state.providers().cape().observeSkinmc(null))));
        fixture.coordinator.adoptSharedSnapshot(self, "Self", absent.providers());
        assertEquals(before, skinMcCalls.get());
        assertFalse(fixture.sink.registered.containsKey("nclskins:skinmc/" + self));
        assertEquals(BuiltinProvider.OPTIFINE,
                CapeProjection.resolve(self, "Self", null, null, true).provider());
    }

    @Test
    void trackedRosterHasFiniteCapAcrossPublicCapeProviders() throws Exception {
        Fixture fixture = fixture();
        for (int index = 0; index < 600; index++) {
            fixture.sink.players.add(new PlayerAppearanceSink.TrackedCapePlayer(
                    UUID.nameUUIDFromBytes(("bounded-" + index).getBytes()), "Player_" + index));
        }
        fixture.coordinator.start();
        assertEquals(513, fixture.calls.get());
    }

    @Test
    void lateSkinMcResponseCannotRestoreDepartedPlayerOrOldAccount() throws Exception {
        Fixture fixture = fixture();
        fixture.coordinator.close();
        UUID self = fixture.session.identity.profileId();
        fixture.storage.updateAppearance(self, state -> state.withProviders(
                state.providers().enable(AppearanceProviders.Component.CAPE, BuiltinProvider.SKINMC)));
        HoldExecutor held = new HoldExecutor();
        SkinMcCapeReader skinMc = CapeTestComposition.skinMc(fixture.storage, (uri, timeout, maxBytes) ->
                new OptifineCapeReader.Response(200, fixture.image,
                        Map.of("Content-Type", List.of("image/png"))),
                Clock.systemUTC());
        fixture.coordinator = CapeTestComposition.create(fixture.session, fixture.storage,
                new TextureCache(fixture.storage), fixture.sink, new ImmediateClient(), held,
                fixture.reader(), skinMc, (id, capeId) -> Optional.empty());
        fixture.coordinator.start();
        UUID remote = UUID.randomUUID();
        fixture.coordinator.trackedPlayer(remote, "Remote");
        fixture.coordinator.untrackedPlayer(remote);
        fixture.session.identity = new GameSessionTokenSource.SessionIdentity(UUID.randomUUID(), "Next");
        fixture.coordinator.configurationChanged();
        held.runAll();
        assertFalse(fixture.sink.registered.containsKey("nclskins:skinmc/" + self));
        assertFalse(fixture.sink.registered.containsKey("nclskins:skinmc/" + remote));
    }

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
        fixture.coordinator = CapeTestComposition.create(fixture.session, fixture.storage,
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
        fixture.coordinator = CapeTestComposition.create(fixture.session, fixture.storage,
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
        fixture.coordinator = CapeTestComposition.create(fixture.session, fixture.storage,
                new TextureCache(fixture.storage), fixture.sink, new ImmediateClient(), held,
                fixture.reader());
        fixture.storage.updateAppearance(self, state -> state.withProviders(state.providers()
                .enable(AppearanceProviders.Component.CAPE, BuiltinProvider.OPTIFINE)));
        fixture.coordinator.start();
        held.runNext();
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
            fixture.coordinator = CapeTestComposition.create(fixture.session, fixture.storage,
                    new TextureCache(fixture.storage), fixture.sink, new ImmediateClient(), held,
                    fixture.reader());
            fixture.coordinator.start();
            held.runNext();
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
    void savedSelfReplayRejectsPreparedBackingUnderWrongVisualHash() throws Exception {
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
            fixture.coordinator = CapeTestComposition.create(fixture.session, fixture.storage,
                    new TextureCache(fixture.storage), fixture.sink, new ImmediateClient(),
                    heldValid, fixture.reader());
            fixture.coordinator.start();
            heldValid.runNext();
            assertEquals(BuiltinProvider.OPTIFINE,
                    CapeProjection.resolve(self, "Self", null, null, true).provider());
            assertEquals(saved.hasElytra(),
                    CapeProjection.resolve(self, "Self", null, null, true).hasElytra());
            assertEquals(1, fixture.sink.registered.size());
            fixture.coordinator.close();

            var backing = new ProviderTextureStorageAdapter(fixture.storage,
                    new TextureCache(fixture.storage)).readObservedPreparedCape(key).orElseThrow().backing();
            byte[] substituted = Files.readAllBytes(backing);
            substituted[0] ^= 1;
            Files.write(backing, substituted);
            ManualExecutor heldTampered = new ManualExecutor();
            fixture.coordinator = CapeTestComposition.create(fixture.session, fixture.storage,
                    new TextureCache(fixture.storage), fixture.sink, new ImmediateClient(),
                    heldTampered, fixture.reader());
            fixture.coordinator.start();
            assertTrue(fixture.sink.registered.isEmpty());
            assertNull(CapeProjection.resolve(self, "Self", null, null, true).provider());
            assertFalse(CapeProjection.resolve(self, "Self", null, null, true).hasElytra());
            assertEquals(saved.hasElytra(), fixture.storage.loadAppearance(self)
                    .providers().cape().optifine().value().hasElytra());
            fixture.coordinator.close();

            Files.delete(backing);
            ManualExecutor heldMalformed = new ManualExecutor();
            fixture.status = 503;
            fixture.coordinator = CapeTestComposition.create(fixture.session, fixture.storage,
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
        fixture.coordinator = CapeTestComposition.create(fixture.session, fixture.storage,
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
        fixture.coordinator = CapeTestComposition.create(fixture.session, fixture.storage,
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
    void observerHintRechecksOnlyTargetedTrackedPlayerAfterKnownAbsence() throws Exception {
        Fixture fixture = fixture();
        UUID target = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        fixture.sink.players.add(new PlayerAppearanceSink.TrackedCapePlayer(target, "Target"));
        fixture.sink.players.add(new PlayerAppearanceSink.TrackedCapePlayer(other, "Other"));
        fixture.coordinator.start();
        assertEquals(1, fixture.reads("Self"));
        assertEquals(1, fixture.reads("Target"));
        assertEquals(1, fixture.reads("Other"));

        fixture.status = 200;
        fixture.image = substitutedPng(64);
        fixture.coordinator.playerInfoUpdated(target, "Target");

        assertEquals(1, fixture.reads("Self"));
        assertEquals(2, fixture.reads("Target"));
        assertEquals(1, fixture.reads("Other"));
        assertEquals(BuiltinProvider.OPTIFINE,
                CapeProjection.resolve(target, "Target", null, null, false).provider());
        assertNull(CapeProjection.resolve(other, "Other", null, null, false).provider());
    }

    @Test
    void observerHintRechecksTargetAcrossLocallyEnabledPublicProvidersWithoutBurst() throws Exception {
        Fixture fixture = fixture();
        fixture.coordinator.close();
        UUID self = fixture.session.identity.profileId();
        UUID target = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        fixture.sink.players.add(new PlayerAppearanceSink.TrackedCapePlayer(target, "Target"));
        fixture.sink.players.add(new PlayerAppearanceSink.TrackedCapePlayer(other, "Other"));
        fixture.storage.updateAppearance(self, state -> state.withProviders(
                state.providers().enable(AppearanceProviders.Component.CAPE, BuiltinProvider.SKINMC)));
        Map<UUID, AtomicInteger> skinMcReads = new HashMap<>();
        SkinMcCapeReader skinMc = CapeTestComposition.skinMc(fixture.storage, (uri, timeout, maxBytes) -> {
            UUID profile = UUID.fromString(uri.getPath().substring(uri.getPath().lastIndexOf('/') + 1));
            skinMcReads.computeIfAbsent(profile, ignored -> new AtomicInteger()).incrementAndGet();
            return new OptifineCapeReader.Response(404, new byte[0]);
        }, Clock.systemUTC());
        ManualExecutor held = new ManualExecutor();
        fixture.coordinator = CapeTestComposition.create(fixture.session, fixture.storage,
                new TextureCache(fixture.storage), fixture.sink, new ImmediateClient(), held,
                fixture.reader(), skinMc, (id, capeId) -> Optional.empty());
        fixture.coordinator.start();
        drainHeld(held);
        assertEquals(1, fixture.reads("Self"));
        assertEquals(1, fixture.reads("Target"));
        assertEquals(1, fixture.reads("Other"));
        assertEquals(1, skinMcReads.get(self).get());
        assertEquals(1, skinMcReads.get(target).get());
        assertEquals(1, skinMcReads.get(other).get());

        for (int hint = 0; hint < 10_000; hint++) fixture.coordinator.playerInfoUpdated(target, "Target");
        drainHeld(held);
        assertEquals(1, fixture.reads("Self"));
        assertEquals(3, fixture.reads("Target"), "one in-flight and one latest followup read");
        assertEquals(1, fixture.reads("Other"));
        assertEquals(1, skinMcReads.get(self).get());
        assertEquals(3, skinMcReads.get(target).get(), "one in-flight and one latest followup read");
        assertEquals(1, skinMcReads.get(other).get());

        fixture.storage.updateAppearance(self, state -> state.withProviders(
                state.providers().disable(AppearanceProviders.Component.CAPE, BuiltinProvider.SKINMC)));
        fixture.coordinator.configurationChanged();
        drainHeld(held);
        fixture.coordinator.playerInfoUpdated(target, "Target");
        drainHeld(held);
        assertEquals(4, fixture.reads("Target"));
        assertEquals(3, skinMcReads.get(target).get(), "disabled local source is never queried");

        fixture.storage.updateAppearance(self, state -> state.withProviders(
                state.providers().enable(AppearanceProviders.Component.CAPE, BuiltinProvider.SKINMC)
                        .disable(AppearanceProviders.Component.CAPE, BuiltinProvider.OPTIFINE)));
        fixture.coordinator.configurationChanged();
        drainHeld(held);
        int optifineBefore = fixture.reads("Target");
        int skinMcBefore = skinMcReads.get(target).get();
        fixture.coordinator.playerInfoUpdated(target, "Target");
        drainHeld(held);
        assertEquals(optifineBefore, fixture.reads("Target"), "disabled OptiFine is never queried");
        assertEquals(skinMcBefore + 1, skinMcReads.get(target).get(),
                "SkinMC-only observer configuration rechecks SkinMC");
    }

    @Test
    void worldEntryDuplicateAddHintsCoalesceToOneFollowupRead() throws Exception {
        Fixture fixture = fixture();
        fixture.status = 404;
        UUID remote = UUID.randomUUID();
        fixture.sink.players.add(new PlayerAppearanceSink.TrackedCapePlayer(remote, "Remote"));
        ManualExecutor held = new ManualExecutor();
        fixture.coordinator.close();
        fixture.coordinator = CapeTestComposition.create(fixture.session, fixture.storage,
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
        assertEquals(2, held.jobs.size(), "one frame upload and one followup request");
        held.runNext();
        assertEquals(1, held.jobs.size(), "burst creates one followup request");
        fixture.image = png();
        held.runNext();
        assertEquals(1, held.jobs.size(), "replacement frame upload follows the coalesced read");
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
        fixture.coordinator = CapeTestComposition.create(fixture.session, fixture.storage,
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
        fixture.coordinator = CapeTestComposition.create(fixture.session, fixture.storage,
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
        fixture.coordinator = CapeTestComposition.create(fixture.session, fixture.storage,
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
        Object scheduler = schedule(fixture.coordinator);
        var attempted = scheduler.getClass().getDeclaredField("attempted");
        attempted.setAccessible(true);
        assertEquals(1, ((java.util.Set<?>) attempted.get(scheduler)).size());
        var tracked = CapeProviderCoordinator.class.getDeclaredField("tracked");
        tracked.setAccessible(true);
        assertTrue(((java.util.Map<?, ?>) tracked.get(fixture.coordinator)).isEmpty());
    }

    @Test
    void reorderingWhileLookupIsPendingKeepsThatRequestAndStartsNoReplacement() throws Exception {
        Fixture fixture = fixture();
        fixture.status = 200;
        ManualExecutor held = new ManualExecutor();
        fixture.coordinator.close();
        fixture.coordinator = CapeTestComposition.create(fixture.session, fixture.storage,
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
        TestCapeProjection.publish(new CapeProjection.Snapshot(
                List.of(BuiltinProvider.OPTIFINE, BuiltinProvider.OFFLINE, BuiltinProvider.MINECRAFT),
                map, new CapeProjection.Identity(self, "Self"), offline, minecraft));
        assertEquals("optifine", CapeProjection.resolve(self, "Self", null, null, true).capeLocation());
        assertEquals("optifine", CapeProjection.resolve(remote, "Remote", offline, minecraft, false).capeLocation());
        TestCapeProjection.publish(new CapeProjection.Snapshot(
                List.of(BuiltinProvider.OFFLINE, BuiltinProvider.MINECRAFT, BuiltinProvider.OPTIFINE),
                map, new CapeProjection.Identity(self, "Self"), offline, minecraft));
        assertEquals("offline", CapeProjection.resolve(self, "Self", null, null, true).capeLocation());
        assertEquals("minecraft", CapeProjection.resolve(remote, "Remote", offline, minecraft, false).capeLocation());
        assertNull(CapeProjection.resolve(remote, "ChangedName", offline, null, false).capeLocation());
        TestCapeProjection.clear();
    }

    @Test
    void oldProcessCloseCannotReleaseNewProcessTexturesOrSnapshot() throws Exception {
        Fixture fixture = fixture();
        fixture.status = 200;
        fixture.coordinator.start();
        CapeProviderCoordinator old = fixture.coordinator;
        CapeProviderCoordinator next = CapeTestComposition.create(fixture.session, fixture.storage,
                new TextureCache(fixture.storage), fixture.sink, new ImmediateClient(), Runnable::run,
                fixture.reader());
        next.start();
        UUID self = fixture.session.currentSession().profileId();
        String location = CapeProjection.resolve(self, "Self", null, null, true).capeLocation();
        assertNotNull(location);
        old.close();
        old.close();
        assertEquals(location, CapeProjection.resolve(self, "Self", null, null, true).capeLocation());
        assertTrue(fixture.sink.registered.containsKey(location));
        next.close();
        assertFalse(fixture.sink.registered.containsKey(location));
    }

    @Test
    void sneakyOldWorldCompletionCannotPopulateNewWorldAndWarmPixelsCanRetry() throws Exception {
        Fixture fixture = fixture();
        UUID self = fixture.session.currentSession().profileId();
        fixture.storage.updateAppearance(self, state -> state.withProviders(state.providers()
                .disable(AppearanceProviders.Component.CAPE, BuiltinProvider.OPTIFINE)
                .enable(AppearanceProviders.Component.CAPE, BuiltinProvider.SNEAKY)));
        HoldExecutor worker = new HoldExecutor();
        fixture.coordinator = CapeTestComposition.create(fixture.session, fixture.storage,
                new TextureCache(fixture.storage), fixture.sink, new ImmediateClient(), worker,
                fixture.reader());
        fixture.coordinator.start();
        worker.runAll();
        UUID remote = UUID.randomUUID();
        Object nativeTexture = new Object();
        CapeProjection.trackedPlayer(remote, "Remote");
        CapeProjection.visibleSkin(remote, "Remote", "minecraft:world-skin");
        CapeProjection.skinTextureReady("minecraft:world-skin", sneakyPixels(), nativeTexture);
        fixture.coordinator.worldChanged();
        worker.runAll();
        assertFalse(fixture.coordinator.hasSkinTexture("minecraft:world-skin"));
        assertNull(CapeProjection.resolve(remote, "Remote", null, null, false).provider());
        CapeProjection.worldEntered();
        CapeProjection.trackedPlayer(remote, "Remote");
        CapeProjection.visibleSkin(remote, "Remote", "minecraft:world-skin");
        worker.runAll();
        assertEquals(BuiltinProvider.SNEAKY,
                CapeProjection.resolve(remote, "Remote", null, null, false).provider());
        fixture.coordinator.close();
    }

    @Test
    void nativeTextureReplacementUnderSameLocationInvalidatesPreparedCape() throws Exception {
        Fixture fixture = fixture();
        UUID self = fixture.session.currentSession().profileId();
        fixture.storage.updateAppearance(self, state -> state.withProviders(state.providers()
                .disable(AppearanceProviders.Component.CAPE, BuiltinProvider.OPTIFINE)
                .enable(AppearanceProviders.Component.CAPE, BuiltinProvider.SNEAKY)));
        fixture.coordinator.start();
        UUID remote = UUID.randomUUID();
        CapeProjection.trackedPlayer(remote, "Remote");
        CapeProjection.visibleSkin(remote, "Remote", "minecraft:reloaded");
        Object oldTexture = new Object();
        CapeProjection.skinTextureReady("minecraft:reloaded", sneakyPixels(), oldTexture);
        assertEquals(BuiltinProvider.SNEAKY,
                CapeProjection.resolve(remote, "Remote", null, null, false).provider());
        Object newTexture = new Object();
        CapeProjection.skinTextureReady("minecraft:reloaded", new int[4096], newTexture);
        assertNull(CapeProjection.resolve(remote, "Remote", null, null, false).provider());
        fixture.coordinator.close();
    }

    @Test
    void sneakyPreparationWaitsForWorkerAndCoalescesSharedTexture() throws Exception {
        Fixture fixture = fixture();
        UUID self = fixture.session.currentSession().profileId();
        fixture.storage.updateAppearance(self, state -> state.withProviders(state.providers()
                .disable(AppearanceProviders.Component.CAPE, BuiltinProvider.OPTIFINE)
                .enable(AppearanceProviders.Component.CAPE, BuiltinProvider.SNEAKY)));
        HoldExecutor worker = new HoldExecutor();
        fixture.coordinator = CapeTestComposition.create(fixture.session, fixture.storage,
                new TextureCache(fixture.storage), fixture.sink, new ImmediateClient(), worker,
                fixture.reader());
        fixture.coordinator.start();
        worker.runAll();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        fixture.coordinator.trackedPlayer(first, "First");
        fixture.coordinator.trackedPlayer(second, "Second");
        fixture.coordinator.visibleSkin(first, "First", "minecraft:shared");
        fixture.coordinator.visibleSkin(second, "Second", "minecraft:shared");
        fixture.coordinator.skinTextureReady("minecraft:shared", sneakyPixels());
        fixture.coordinator.skinTextureReady("minecraft:shared", sneakyPixels());
        assertNull(CapeProjection.resolve(first, "First", null, null, false).provider());
        assertEquals(1, worker.jobs.size());
        worker.runAll();
        assertEquals(BuiltinProvider.SNEAKY,
                CapeProjection.resolve(first, "First", null, null, false).provider());
        assertEquals(BuiltinProvider.SNEAKY,
                CapeProjection.resolve(second, "Second", null, null, false).provider());
        fixture.coordinator.skinTextureReady("minecraft:shared", sneakyPixels());
        assertTrue(worker.jobs.isEmpty());
        fixture.coordinator.close();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"false,false", "true,false", "false,true", "true,true"})
    void sneakyRegistrationRecoversVisibilityWithoutChangedFrames(boolean warm, boolean trackingFirst) throws Exception {
        Fixture fixture = sneakyOnlyFixture();
        UUID self = fixture.session.currentSession().profileId();
        UUID remote = UUID.randomUUID();
        UUID unrelated = UUID.randomUUID();
        String key = "minecraft:skins/registration-" + remote;
        int[] pixels = sneakyPixels();
        int[] original = pixels.clone();
        Object nativeTexture = new Object();
        var before = fixture.storage.loadAppearance(self);
        try {
            if (warm) {
                CapeProjection.skinTextureReady(key, pixels, nativeTexture);
                assertTrue(fixture.coordinator.hasSkinTexture(key));
                CapeProjection.worldEntered();
                assertTrue(fixture.coordinator.hasSkinTexture(key));
            }
            var body = CapeProjection.effectiveSkin(remote, remote, key,
                    com.naocraftlab.skins.client.SkinModel.CLASSIC, false);
            CapeProjection.cpmObserver(body, "Remote").publish(1,
                    CpmAccessoryObservation.Availability.CONFIRMED, true, true);
            var accessories = body.accessories();
            if (trackingFirst) CapeProjection.trackedPlayer(remote, "Remote");
            CapeProjection.visibleSkin(remote, "Remote", key);
            if (!trackingFirst) {
                assertEquals(BuiltinProvider.MINECRAFT, remoteWinner(remote, "Remote"));
                CapeProjection.trackedPlayer(remote, "Remote");
            }
            if (!warm) CapeProjection.skinTextureReady(key, pixels, nativeTexture);
            assertEquals(BuiltinProvider.SNEAKY, remoteWinner(remote, "Remote"));
            int registrations = fixture.sink.registrations;
            for (int index = 0; index < 10; index++) CapeProjection.visibleSkin(remote, "Remote", key);
            assertEquals(BuiltinProvider.SNEAKY, remoteWinner(remote, "Remote"));
            assertEquals(registrations, fixture.sink.registrations);
            assertArrayEquals(new com.naocraftlab.skins.core.png.SneakyCapeDecoder()
                    .decode(sneakySkin()).orElseThrow().bytes(),
                    fixture.sink.registered.get("nclskins:sneaky/" + remote));
            assertArrayEquals(original, pixels);
            assertEquals(0, fixture.calls.get());
            assertEquals(before, fixture.storage.loadAppearance(self));
            assertNull(CapeProjection.resolve(self, "Self", null, null, true).provider());
            assertEquals(BuiltinProvider.MINECRAFT, remoteWinner(unrelated, "Unrelated"));
            assertTrue(body.active());
            assertTrue(body == CapeProjection.skinSource(remote));
            assertEquals(accessories, body.accessories());
        } finally {
            java.lang.ref.Reference.reachabilityFence(nativeTexture);
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void sneakyOnlyPlayerInfoRegistersRemoteWithoutHttp(boolean visibleFirst) throws Exception {
        Fixture fixture = sneakyOnlyFixture();
        UUID self = fixture.session.currentSession().profileId();
        var before = fixture.storage.loadAppearance(self);
        UUID remote = UUID.randomUUID();
        String key = "minecraft:skins/player-info-" + remote;
        Object nativeTexture = new Object();
        try {
            CapeProjection.skinTextureReady(key, sneakyPixels(), nativeTexture);
            if (visibleFirst) CapeProjection.visibleSkin(remote, "Remote", key);
            CapeProjection.playerInfoUpdated(remote, "Remote");
            if (!visibleFirst) CapeProjection.visibleSkin(remote, "Remote", key);
            assertEquals(BuiltinProvider.SNEAKY, remoteWinner(remote, "Remote"));
            int registrations = fixture.sink.registrations;
            CapeProjection.playerInfoUpdated(remote, "Remote");
            for (int index = 0; index < 10; index++) CapeProjection.visibleSkin(remote, "Remote", key);
            assertEquals(BuiltinProvider.SNEAKY, remoteWinner(remote, "Remote"));
            assertEquals(registrations, fixture.sink.registrations);
            assertArrayEquals(new com.naocraftlab.skins.core.png.SneakyCapeDecoder()
                    .decode(sneakySkin()).orElseThrow().bytes(),
                    fixture.sink.registered.get("nclskins:sneaky/" + remote));
            assertEquals(0, fixture.calls.get());
            assertEquals(before, fixture.storage.loadAppearance(self));
        } finally {
            java.lang.ref.Reference.reachabilityFence(nativeTexture);
        }
    }

    @Test
    void sneakySweepAcceptsOnlineIdentityBeforeVisibility() throws Exception {
        Fixture fixture = sneakyOnlyFixture();
        UUID self = fixture.session.currentSession().profileId();
        UUID remote = UUID.randomUUID();
        String key = "minecraft:skins/sweep-" + remote;
        Object nativeTexture = new Object();
        try {
            fixture.sink.players.add(new PlayerAppearanceSink.TrackedCapePlayer(remote, "Remote"));
            fixture.storage.updateAppearance(self, state -> state.withProviders(state.providers()
                    .enable(AppearanceProviders.Component.CAPE, BuiltinProvider.OPTIFINE)));
            fixture.coordinator.configurationChanged();
            assertEquals(BuiltinProvider.MINECRAFT, remoteWinner(remote, "Remote"));
            int reads = fixture.calls.get();
            assertTrue(reads > 0);
            CapeProjection.skinTextureReady(key, sneakyPixels(), nativeTexture);
            CapeProjection.visibleSkin(remote, "Remote", key);
            assertEquals(BuiltinProvider.SNEAKY, remoteWinner(remote, "Remote"));
            assertEquals(reads, fixture.calls.get());
        } finally {
            java.lang.ref.Reference.reachabilityFence(nativeTexture);
        }
    }

    @Test
    void sneakySweepRecoversPreviouslyVisibleOnlineIdentity() throws Exception {
        Fixture fixture = sneakyOnlyFixture();
        UUID self = fixture.session.currentSession().profileId();
        UUID remote = UUID.randomUUID();
        UUID untracked = UUID.randomUUID();
        String key = "minecraft:skins/sweep-recovery-" + remote;
        Object nativeTexture = new Object();
        try {
            CapeProjection.skinTextureReady(key, sneakyPixels(), nativeTexture);
            CapeProjection.visibleSkin(remote, "Remote", key);
            CapeProjection.visibleSkin(untracked, "Untracked", key);
            assertEquals(BuiltinProvider.MINECRAFT, remoteWinner(remote, "Remote"));
            fixture.sink.players.add(new PlayerAppearanceSink.TrackedCapePlayer(remote, "Remote"));
            fixture.storage.updateAppearance(self, state -> state.withProviders(state.providers()
                    .enable(AppearanceProviders.Component.CAPE, BuiltinProvider.OPTIFINE)));
            fixture.coordinator.configurationChanged();
            assertEquals(BuiltinProvider.SNEAKY, remoteWinner(remote, "Remote"));
            assertEquals(BuiltinProvider.MINECRAFT, remoteWinner(untracked, "Untracked"));
            int reads = fixture.calls.get();
            int registrations = fixture.sink.registrations;
            for (int index = 0; index < 10; index++) CapeProjection.visibleSkin(remote, "Remote", key);
            assertEquals(BuiltinProvider.SNEAKY, remoteWinner(remote, "Remote"));
            assertEquals(registrations, fixture.sink.registrations);
            assertEquals(reads, fixture.calls.get());
            assertArrayEquals(new com.naocraftlab.skins.core.png.SneakyCapeDecoder()
                    .decode(sneakySkin()).orElseThrow().bytes(),
                    fixture.sink.registered.get("nclskins:sneaky/" + remote));
        } finally {
            java.lang.ref.Reference.reachabilityFence(nativeTexture);
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"remove", "rename"})
    void sneakyRetiredIdentityCannotRecoverOldVisibility(String boundary) throws Exception {
        Fixture fixture = sneakyOnlyFixture();
        UUID remote = UUID.randomUUID();
        String key = "minecraft:skins/retired-" + remote;
        Object nativeTexture = new Object();
        try {
            CapeProjection.skinTextureReady(key, sneakyPixels(), nativeTexture);
            CapeProjection.visibleSkin(remote, "Remote", key);
            CapeProjection.trackedPlayer(remote, "Remote");
            assertEquals(BuiltinProvider.SNEAKY, remoteWinner(remote, "Remote"));
            if (boundary.equals("remove")) fixture.coordinator.untrackedPlayer(remote);
            else {
                CapeProjection.playerInfoUpdated(remote, "Renamed");
                assertEquals(BuiltinProvider.MINECRAFT, remoteWinner(remote, "Renamed"));
            }
            assertEquals(BuiltinProvider.MINECRAFT, remoteWinner(remote, "Remote"));
            assertFalse(fixture.sink.registered.containsKey("nclskins:sneaky/" + remote));
            CapeProjection.trackedPlayer(remote, "Remote");
            assertEquals(BuiltinProvider.MINECRAFT, remoteWinner(remote, "Remote"));
            CapeProjection.visibleSkin(remote, "Remote", key);
            assertEquals(BuiltinProvider.SNEAKY, remoteWinner(remote, "Remote"));
            assertEquals(0, fixture.calls.get());
        } finally {
            java.lang.ref.Reference.reachabilityFence(nativeTexture);
        }
    }

    @Test
    void sneakyDirectWorldInvalidationCannotRecoverPendingVisibility() throws Exception {
        Fixture fixture = sneakyOnlyFixture();
        UUID remote = UUID.randomUUID();
        String key = "minecraft:skins/world-pending-" + remote;
        Object nativeTexture = new Object();
        try {
            CapeProjection.skinTextureReady(key, sneakyPixels(), nativeTexture);
            CapeProjection.visibleSkin(remote, "Remote", key);
            fixture.coordinator.worldChanged();
            CapeProjection.trackedPlayer(remote, "Remote");
            assertEquals(BuiltinProvider.MINECRAFT, remoteWinner(remote, "Remote"));
            CapeProjection.visibleSkin(remote, "Remote", key);
            assertEquals(BuiltinProvider.SNEAKY, remoteWinner(remote, "Remote"));
            assertEquals(0, fixture.calls.get());
        } finally {
            java.lang.ref.Reference.reachabilityFence(nativeTexture);
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"remove", "null", "rename", "world", "account", "disable"})
    void sneakyPendingVisibilityRequiresFreshObservationAfterInvalidation(String boundary) throws Exception {
        Fixture fixture = sneakyOnlyFixture();
        UUID self = fixture.session.currentSession().profileId();
        UUID remote = UUID.randomUUID();
        String key = "minecraft:skins/pending-" + remote;
        Object nativeTexture = new Object();
        try {
            CapeProjection.skinTextureReady(key, sneakyPixels(), nativeTexture);
            CapeProjection.visibleSkin(remote, "Remote", key);
            String acceptedName = "Remote";
            switch (boundary) {
                case "remove" -> CapeProjection.untrackedPlayer(remote);
                case "null" -> CapeProjection.visibleSkin(remote, "Remote", null);
                case "rename" -> acceptedName = "Renamed";
                case "world" -> CapeProjection.worldEntered();
                case "account" -> {
                    UUID next = UUID.randomUUID();
                    fixture.storage.loadOrCreateAccount(next);
                    var providers = fixture.storage.loadAppearance(self).providers();
                    fixture.storage.updateAppearance(next, state -> state.withProviders(providers));
                    fixture.session.identity = new GameSessionTokenSource.SessionIdentity(next, "Next");
                    CapeProjection.trackedPlayer(remote, "Remote");
                    assertEquals(BuiltinProvider.MINECRAFT, remoteWinner(remote, "Remote"));
                    fixture.coordinator.configurationChanged();
                }
                case "disable" -> {
                    fixture.storage.updateAppearance(self, state -> state.withProviders(state.providers()
                            .disable(AppearanceProviders.Component.CAPE, BuiltinProvider.SNEAKY)));
                    fixture.coordinator.configurationChanged();
                    CapeProjection.playerInfoUpdated(remote, "Remote");
                    assertEquals(BuiltinProvider.MINECRAFT, remoteWinner(remote, "Remote"));
                    fixture.storage.updateAppearance(self, state -> state.withProviders(state.providers()
                            .enable(AppearanceProviders.Component.CAPE, BuiltinProvider.SNEAKY)
                            .move(AppearanceProviders.Component.CAPE, BuiltinProvider.SNEAKY, -1)));
                    fixture.coordinator.configurationChanged();
                }
                default -> throw new AssertionError(boundary);
            }
            CapeProjection.trackedPlayer(remote, acceptedName);
            assertEquals(BuiltinProvider.MINECRAFT, remoteWinner(remote, acceptedName));
            assertFalse(fixture.sink.registered.containsKey("nclskins:sneaky/" + remote));
            CapeProjection.visibleSkin(remote, acceptedName, key);
            assertEquals(BuiltinProvider.SNEAKY, remoteWinner(remote, acceptedName));
            assertEquals(0, fixture.calls.get());
        } finally {
            java.lang.ref.Reference.reachabilityFence(nativeTexture);
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"tracking", "visibility"})
    void sneakyRecoveryPreservesAdmissionBounds(String bound) throws Exception {
        Fixture fixture = sneakyOnlyFixture();
        UUID remote = UUID.randomUUID();
        UUID first = UUID.randomUUID();
        String key = "minecraft:skins/bounded-" + remote;
        Object nativeTexture = new Object();
        try {
            CapeProjection.skinTextureReady(key, sneakyPixels(), nativeTexture);
            int limit = bound.equals("tracking") ? 512 : 513;
            for (int index = 0; index < limit; index++) {
                UUID id = index == 0 ? first : UUID.randomUUID();
                if (bound.equals("tracking")) CapeProjection.trackedPlayer(id, "Other");
                else CapeProjection.visibleSkin(id, "Other", key);
            }
            CapeProjection.visibleSkin(remote, "Remote", key);
            CapeProjection.trackedPlayer(remote, "Remote");
            assertEquals(BuiltinProvider.MINECRAFT, remoteWinner(remote, "Remote"));
            assertFalse(fixture.sink.registered.containsKey("nclskins:sneaky/" + remote));
            if (bound.equals("tracking")) {
                CapeProjection.untrackedPlayer(first);
                CapeProjection.trackedPlayer(remote, "Remote");
            } else {
                CapeProjection.visibleSkin(first, "Other", null);
                CapeProjection.visibleSkin(remote, "Remote", key);
            }
            assertEquals(BuiltinProvider.SNEAKY, remoteWinner(remote, "Remote"));
            assertEquals(0, fixture.calls.get());
        } finally {
            java.lang.ref.Reference.reachabilityFence(nativeTexture);
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"remove", "rename", "world", "account", "disable", "close"})
    void sneakyRecoveredVisibilityRejectsLateDecode(String boundary) throws Exception {
        Fixture fixture = sneakyOnlyFixture();
        UUID self = fixture.session.currentSession().profileId();
        fixture.coordinator.close();
        HoldExecutor worker = new HoldExecutor();
        fixture.coordinator = CapeTestComposition.create(fixture.session, fixture.storage,
                new TextureCache(fixture.storage), fixture.sink, new ImmediateClient(), worker,
                fixture.reader());
        fixture.coordinator.start();
        worker.runAll();
        UUID remote = UUID.randomUUID();
        String key = "minecraft:skins/late-registration-" + remote;
        Object nativeTexture = new Object();
        try {
            CapeProjection.visibleSkin(remote, "Remote", key);
            CapeProjection.playerInfoUpdated(remote, "Remote");
            CapeProjection.skinTextureReady(key, sneakyPixels(), nativeTexture);
            assertFalse(worker.jobs.isEmpty());
            assertEquals(BuiltinProvider.MINECRAFT, remoteWinner(remote, "Remote"));
            switch (boundary) {
                case "remove" -> CapeProjection.untrackedPlayer(remote);
                case "rename" -> CapeProjection.playerInfoUpdated(remote, "Renamed");
                case "world" -> CapeProjection.worldEntered();
                case "account" -> {
                    UUID next = UUID.randomUUID();
                    fixture.storage.loadOrCreateAccount(next);
                    var providers = fixture.storage.loadAppearance(self).providers();
                    fixture.storage.updateAppearance(next, state -> state.withProviders(providers));
                    fixture.session.identity = new GameSessionTokenSource.SessionIdentity(next, "Next");
                    fixture.coordinator.configurationChanged();
                }
                case "disable" -> {
                    fixture.storage.updateAppearance(self, state -> state.withProviders(state.providers()
                            .disable(AppearanceProviders.Component.CAPE, BuiltinProvider.SNEAKY)));
                    fixture.coordinator.configurationChanged();
                }
                case "close" -> fixture.coordinator.close();
                default -> throw new AssertionError(boundary);
            }
            worker.runAll();
            assertNull(CapeProjection.resolve(remote, "Remote", null, null, false).provider());
            assertFalse(fixture.sink.registered.containsKey("nclskins:sneaky/" + remote));
            assertEquals(0, fixture.calls.get());
            if (boundary.equals("close")) {
                CapeProjection.trackedPlayer(remote, "Remote");
                CapeProjection.visibleSkin(remote, "Remote", key);
                worker.runAll();
                assertNull(CapeProjection.resolve(remote, "Remote", null, null, false).provider());
                return;
            }
            if (boundary.equals("disable")) {
                fixture.storage.updateAppearance(self, state -> state.withProviders(state.providers()
                        .enable(AppearanceProviders.Component.CAPE, BuiltinProvider.SNEAKY)
                        .move(AppearanceProviders.Component.CAPE, BuiltinProvider.SNEAKY, -1)));
                fixture.coordinator.configurationChanged();
            }
            String name = boundary.equals("rename") ? "Renamed" : "Remote";
            CapeProjection.trackedPlayer(remote, name);
            assertEquals(BuiltinProvider.MINECRAFT, remoteWinner(remote, name));
            CapeProjection.visibleSkin(remote, name, key);
            worker.runAll();
            assertEquals(BuiltinProvider.SNEAKY, remoteWinner(remote, name));
            assertArrayEquals(new com.naocraftlab.skins.core.png.SneakyCapeDecoder()
                    .decode(sneakySkin()).orElseThrow().bytes(),
                    fixture.sink.registered.get("nclskins:sneaky/" + remote));
            assertEquals(0, fixture.calls.get());
        } finally {
            java.lang.ref.Reference.reachabilityFence(nativeTexture);
        }
    }

    @Test
    void sneakyRecoveryCannotBorrowRetiredRegistrationVisibility() throws Exception {
        Fixture fixture = sneakyOnlyFixture();
        fixture.coordinator.close();
        QueuedClient client = new QueuedClient();
        fixture.coordinator = CapeTestComposition.create(fixture.session, fixture.storage,
                new TextureCache(fixture.storage), fixture.sink, client, Runnable::run,
                fixture.reader());
        client.runInline(fixture.coordinator::start);
        client.runAll();
        UUID remote = UUID.randomUUID();
        String key = "minecraft:skins/retired-registration-" + remote;
        Object nativeTexture = new Object();
        CapeProviderCoordinator old = fixture.coordinator;
        try {
            CapeProjection.skinTextureReady(key, sneakyPixels(), nativeTexture);
            CapeProjection.visibleSkin(remote, "Remote", key);
            CapeProjection.playerInfoUpdated(remote, "Remote");
            assertTrue(client.hasJobs());
            fixture.coordinator = CapeTestComposition.create(fixture.session, fixture.storage,
                    new TextureCache(fixture.storage), fixture.sink, client, Runnable::run,
                    fixture.reader());
            client.runInline(fixture.coordinator::start);
            old.close();
            client.runAll();
            CapeProjection.playerInfoUpdated(remote, "Remote");
            client.runAll();
            assertEquals(BuiltinProvider.MINECRAFT, remoteWinner(remote, "Remote"));
            assertFalse(fixture.sink.registered.containsKey("nclskins:sneaky/" + remote));
            CapeProjection.visibleSkin(remote, "Remote", key);
            client.runAll();
            assertEquals(BuiltinProvider.SNEAKY, remoteWinner(remote, "Remote"));
            old.trackedPlayer(remote, "Remote");
            old.worldChanged();
            old.close();
            client.runAll();
            assertEquals(BuiltinProvider.SNEAKY, remoteWinner(remote, "Remote"));
            assertTrue(fixture.sink.registered.containsKey("nclskins:sneaky/" + remote));
            assertEquals(0, fixture.calls.get());
        } finally {
            old.close();
            java.lang.ref.Reference.reachabilityFence(nativeTexture);
        }
    }

    @Test
    void sneakyRecoveryUsesLatestExactSkinWithoutSelfOrDefaultBorrowing() throws Exception {
        Fixture fixture = sneakyOnlyFixture();
        UUID self = fixture.session.currentSession().profileId();
        UUID remote = UUID.randomUUID();
        UUID unrelated = UUID.randomUUID();
        String oldKey = "minecraft:skins/old-" + remote;
        String key = "minecraft:skins/latest-" + remote;
        Object oldTexture = new Object();
        Object emptyTexture = new Object();
        Object latestTexture = new Object();
        int[] pixels = sneakyPixels();
        int[] original = pixels.clone();
        try {
            CapeProjection.skinTextureReady(oldKey, pixels, oldTexture);
            CapeProjection.visibleSkin(remote, "Remote", oldKey);
            CapeProjection.visibleSkin(remote, "Remote", key);
            CapeProjection.playerInfoUpdated(remote, "Remote");
            assertEquals(BuiltinProvider.MINECRAFT, remoteWinner(remote, "Remote"));
            CapeProjection.skinTextureReady(key, new int[64 * 64], emptyTexture);
            assertEquals(BuiltinProvider.MINECRAFT, remoteWinner(remote, "Remote"));
            CapeProjection.skinTextureReady(key, pixels, latestTexture);
            assertEquals(BuiltinProvider.SNEAKY, remoteWinner(remote, "Remote"));
            assertEquals(BuiltinProvider.MINECRAFT, remoteWinner(remote, "OtherName"));
            CapeProjection.visibleSkin(self, "Self", key);
            CapeProjection.trackedPlayer(self, "Self");
            assertNull(CapeProjection.resolve(self, "Self", null, null, true).provider());
            CapeProjection.playerInfoUpdated(unrelated, "Unrelated");
            CapeProjection.visibleSkin(unrelated, "Unrelated", "minecraft:default-synthetic");
            assertEquals(BuiltinProvider.MINECRAFT, remoteWinner(unrelated, "Unrelated"));
            assertArrayEquals(original, pixels);
            assertEquals(0, fixture.calls.get());
        } finally {
            java.lang.ref.Reference.reachabilityFence(oldTexture);
            java.lang.ref.Reference.reachabilityFence(emptyTexture);
            java.lang.ref.Reference.reachabilityFence(latestTexture);
        }
    }

    @Test
    void sneakySweepRetirementPublishesBeforeExternalWorkCompletes() throws Exception {
        Fixture fixture = sneakyOnlyFixture();
        UUID self = fixture.session.currentSession().profileId();
        fixture.coordinator.close();
        HoldExecutor worker = new HoldExecutor();
        fixture.coordinator = CapeTestComposition.create(fixture.session, fixture.storage,
                new TextureCache(fixture.storage), fixture.sink, new ImmediateClient(), worker,
                fixture.reader());
        fixture.coordinator.start();
        worker.runAll();
        UUID remote = UUID.randomUUID();
        String key = "minecraft:skins/sweep-retired-" + remote;
        Object nativeTexture = new Object();
        try {
            CapeProjection.skinTextureReady(key, sneakyPixels(), nativeTexture);
            worker.runAll();
            CapeProjection.visibleSkin(remote, "Remote", key);
            fixture.sink.players.add(new PlayerAppearanceSink.TrackedCapePlayer(remote, "Remote"));
            fixture.storage.updateAppearance(self, state -> state.withProviders(state.providers()
                    .enable(AppearanceProviders.Component.CAPE, BuiltinProvider.OPTIFINE)));
            fixture.coordinator.configurationChanged();
            worker.runAll();
            assertEquals(BuiltinProvider.SNEAKY, remoteWinner(remote, "Remote"));
            fixture.sink.players.clear();
            fixture.coordinator.refresh();
            assertFalse(worker.jobs.isEmpty());
            assertEquals(BuiltinProvider.MINECRAFT, remoteWinner(remote, "Remote"));
            assertFalse(fixture.sink.registered.containsKey("nclskins:sneaky/" + remote));
            CapeProjection.trackedPlayer(remote, "Remote");
            assertEquals(BuiltinProvider.MINECRAFT, remoteWinner(remote, "Remote"));
            CapeProjection.visibleSkin(remote, "Remote", key);
            assertEquals(BuiltinProvider.SNEAKY, remoteWinner(remote, "Remote"));
            worker.runAll();
        } finally {
            java.lang.ref.Reference.reachabilityFence(nativeTexture);
        }
    }

    private Fixture sneakyOnlyFixture() throws Exception {
        Fixture fixture = fixture();
        UUID self = fixture.session.currentSession().profileId();
        fixture.storage.updateAppearance(self, state -> {
            AppearanceProviders providers = state.providers();
            for (BuiltinProvider source : BuiltinProvider.values()) {
                if (source != BuiltinProvider.SNEAKY && source != BuiltinProvider.MINECRAFT)
                    providers = providers.disable(AppearanceProviders.Component.CAPE, source);
            }
            providers = providers.enable(AppearanceProviders.Component.CAPE, BuiltinProvider.SNEAKY)
                    .enable(AppearanceProviders.Component.CAPE, BuiltinProvider.MINECRAFT);
            for (int index = 0; index < BuiltinProvider.values().length; index++)
                providers = providers.move(AppearanceProviders.Component.CAPE, BuiltinProvider.SNEAKY, -1);
            return state.withProviders(providers);
        });
        fixture.coordinator.start();
        return fixture;
    }

    private static BuiltinProvider remoteWinner(UUID remote, String name) {
        return CapeProjection.resolve(remote, name, null,
                new CapeProjection.Candidate("minecraft:synthetic-official", null, false), false).provider();
    }

    @Test
    void sneakyRemoteUsesVisibleSkinPixelsAndClearsOnWorldChange() throws Exception {
        Fixture fixture = fixture();
        UUID self = fixture.session.currentSession().profileId();
        fixture.storage.updateAppearance(self, state -> state.withProviders(state.providers()
                .disable(AppearanceProviders.Component.CAPE, BuiltinProvider.OPTIFINE)
                .enable(AppearanceProviders.Component.CAPE, BuiltinProvider.SNEAKY)));
        fixture.coordinator.start();
        UUID remote = UUID.randomUUID();
        fixture.coordinator.trackedPlayer(remote, "Remote");
        fixture.coordinator.visibleSkin(remote, "Remote", "minecraft:skin/a");
        assertNull(CapeProjection.resolve(remote, "Remote", null, null, false).capeLocation());
        fixture.coordinator.skinTextureReady("minecraft:skin/a", sneakyPixels());
        assertEquals(BuiltinProvider.SNEAKY,
                CapeProjection.resolve(remote, "Remote", null, null, false).provider());
        assertTrue(fixture.sink.registered.containsKey("nclskins:sneaky/" + remote));
        fixture.coordinator.visibleSkin(remote, "Remote", null);
        assertNull(CapeProjection.resolve(remote, "Remote", null, null, false).capeLocation());
        fixture.coordinator.visibleSkin(remote, "Remote", "minecraft:skin/a");
        assertEquals(BuiltinProvider.SNEAKY,
                CapeProjection.resolve(remote, "Remote", null, null, false).provider());
        fixture.coordinator.visibleSkin(remote, "Remote", "minecraft:skin/b");
        assertNull(CapeProjection.resolve(remote, "Remote", null, null, false).capeLocation());
        fixture.coordinator.skinTextureReady("minecraft:skin/a", sneakyPixels());
        assertNull(CapeProjection.resolve(remote, "Remote", null, null, false).capeLocation());
        fixture.coordinator.skinTextureReady("minecraft:skin/b", sneakyPixels());
        assertEquals(BuiltinProvider.SNEAKY,
                CapeProjection.resolve(remote, "Remote", null, null, false).provider());
        fixture.storage.updateAppearance(self, state -> state.withProviders(state.providers()
                .disable(AppearanceProviders.Component.CAPE, BuiltinProvider.SNEAKY)));
        fixture.coordinator.configurationChanged();
        var official = new CapeProjection.Candidate("minecraft:official", null, false);
        assertEquals(BuiltinProvider.MINECRAFT,
                CapeProjection.resolve(remote, "Remote", null, official, false).provider());
        fixture.coordinator.worldChanged();
        assertNull(CapeProjection.resolve(remote, "Remote", null, null, false).capeLocation());
    }

    @Test
    void sneakyRemoteUsesRegisteredKeysWhenSkinUploadsShareOneDefaultFallback() throws Exception {
        Fixture fixture = fixture();
        UUID self = fixture.session.currentSession().profileId();
        fixture.storage.updateAppearance(self, state -> state.withProviders(state.providers()
                .disable(AppearanceProviders.Component.CAPE, BuiltinProvider.OPTIFINE)
                .enable(AppearanceProviders.Component.CAPE, BuiltinProvider.SNEAKY)));
        fixture.coordinator.start();

        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        UUID pending = UUID.randomUUID();
        String sharedDefaultFallback = "minecraft:textures/entity/player/wide/steve.png";
        String firstRegisteredSkin = "minecraft:skins/first-hash";
        String secondRegisteredSkin = "minecraft:skins/second-hash";
        fixture.coordinator.trackedPlayer(first, "First");
        fixture.coordinator.trackedPlayer(second, "Second");
        fixture.coordinator.trackedPlayer(pending, "Pending");
        fixture.coordinator.visibleSkin(first, "First", firstRegisteredSkin);
        fixture.coordinator.visibleSkin(second, "Second", secondRegisteredSkin);
        fixture.coordinator.visibleSkin(pending, "Pending", sharedDefaultFallback);

        fixture.coordinator.skinTextureReady(firstRegisteredSkin, sneakyPixels());
        assertEquals(BuiltinProvider.SNEAKY,
                CapeProjection.resolve(first, "First", null, null, false).provider());
        assertNull(CapeProjection.resolve(second, "Second", null, null, false).capeLocation());
        assertNull(CapeProjection.resolve(pending, "Pending", null, null, false).capeLocation());

        fixture.coordinator.skinTextureReady(secondRegisteredSkin, sneakyPixels());
        assertEquals(BuiltinProvider.SNEAKY,
                CapeProjection.resolve(second, "Second", null, null, false).provider());
        assertNull(CapeProjection.resolve(pending, "Pending", null, null, false).capeLocation());
    }

    @Test
    void sneakyRemoteRecoversWarmSkinAcrossWorldRetrackAndAccountWithoutReupload() throws Exception {
        Fixture fixture = fixture();
        UUID self = fixture.session.currentSession().profileId();
        fixture.storage.updateAppearance(self, state -> state.withProviders(state.providers()
                .disable(AppearanceProviders.Component.CAPE, BuiltinProvider.OPTIFINE)
                .enable(AppearanceProviders.Component.CAPE, BuiltinProvider.SNEAKY)));
        fixture.coordinator.start();
        UUID remote = UUID.randomUUID();
        String key = "minecraft:skins/warm-hash";
        fixture.coordinator.trackedPlayer(remote, "Remote");
        fixture.coordinator.visibleSkin(remote, "Remote", key);
        fixture.coordinator.skinTextureReady(key, sneakyPixels());
        assertEquals(BuiltinProvider.SNEAKY,
                CapeProjection.resolve(remote, "Remote", null, null, false).provider());

        fixture.coordinator.worldEntered();
        assertNull(CapeProjection.resolve(remote, "Remote", null, null, false).capeLocation());
        fixture.coordinator.trackedPlayer(remote, "Remote");
        fixture.coordinator.visibleSkin(remote, "Remote", key);
        assertEquals(BuiltinProvider.SNEAKY,
                CapeProjection.resolve(remote, "Remote", null, null, false).provider());
        fixture.coordinator.untrackedPlayer(remote);
        assertNull(CapeProjection.resolve(remote, "Remote", null, null, false).capeLocation());
        fixture.coordinator.trackedPlayer(remote, "Remote");
        fixture.coordinator.visibleSkin(remote, "Remote", key);
        assertEquals(BuiltinProvider.SNEAKY,
                CapeProjection.resolve(remote, "Remote", null, null, false).provider());

        UUID nextAccount = UUID.randomUUID();
        fixture.storage.loadOrCreateAccount(nextAccount);
        fixture.storage.updateAppearance(nextAccount, state -> state.withProviders(state.providers()
                .enable(AppearanceProviders.Component.CAPE, BuiltinProvider.SNEAKY)));
        fixture.session.identity = new GameSessionTokenSource.SessionIdentity(nextAccount, "Next");
        fixture.coordinator.configurationChanged();
        assertNull(CapeProjection.resolve(remote, "Remote", null, null, false).capeLocation());
        fixture.coordinator.trackedPlayer(remote, "Remote");
        fixture.coordinator.visibleSkin(remote, "Remote", key);
        assertEquals(BuiltinProvider.SNEAKY,
                CapeProjection.resolve(remote, "Remote", null, null, false).provider());
        UUID pending = UUID.randomUUID();
        fixture.coordinator.trackedPlayer(pending, "Pending");
        fixture.coordinator.visibleSkin(pending, "Pending", "minecraft:textures/entity/player/default.png");
        assertNull(CapeProjection.resolve(pending, "Pending", null, null, false).capeLocation());
    }

    @Test
    void sneakyNativeOwnerReplaysAfterDecodedCacheEvictionWithoutDefaultSharing() throws Exception {
        Fixture fixture = fixture();
        UUID self = fixture.session.currentSession().profileId();
        fixture.storage.updateAppearance(self, state -> state.withProviders(state.providers()
                .disable(AppearanceProviders.Component.CAPE, BuiltinProvider.OPTIFINE)
                .enable(AppearanceProviders.Component.CAPE, BuiltinProvider.SNEAKY)));
        fixture.coordinator.start();
        Object nativeTexture = new Object();
        List<Object> otherNativeTextures = new ArrayList<>();
        try {
            UUID remote = UUID.randomUUID();
            UUID pending = UUID.randomUUID();
            String key = "minecraft:skins/immutable-hash";
            CapeProjection.trackedPlayer(remote, "Remote");
            CapeProjection.trackedPlayer(pending, "Pending");
            CapeProjection.skinTextureReady(key, sneakyPixels(), nativeTexture);
            CapeProjection.visibleSkin(remote, "Remote", key);
            CapeProjection.visibleSkin(pending, "Pending", "minecraft:textures/entity/player/default.png");
            assertEquals(BuiltinProvider.SNEAKY,
                    CapeProjection.resolve(remote, "Remote", null, null, false).provider());
            assertNull(CapeProjection.resolve(pending, "Pending", null, null, false).capeLocation());

            for (int index = 0; index < 512; index++) {
                Object otherTexture = new Object();
                otherNativeTextures.add(otherTexture);
                CapeProjection.skinTextureReady("minecraft:skins/other-" + index, new int[64 * 64], otherTexture);
            }
            assertFalse(fixture.coordinator.hasSkinTexture(key));
            CapeProjection.worldEntered();
            CapeProjection.trackedPlayer(remote, "Remote");
            CapeProjection.visibleSkin(remote, "Remote", key);
            assertEquals(BuiltinProvider.SNEAKY,
                    CapeProjection.resolve(remote, "Remote", null, null, false).provider());
            assertNull(CapeProjection.resolve(pending, "Pending", null, null, false).capeLocation());

            CapeProjection.untrackedPlayer(remote);
            CapeProjection.trackedPlayer(remote, "Remote");
            CapeProjection.visibleSkin(remote, "Remote", key);
            assertEquals(BuiltinProvider.SNEAKY,
                    CapeProjection.resolve(remote, "Remote", null, null, false).provider());
            CapeProjection.worldEntered();
            CapeProjection.trackedPlayer(remote, "Remote");
            CapeProjection.visibleSkin(remote, "Remote", key);
            assertEquals(BuiltinProvider.SNEAKY,
                    CapeProjection.resolve(remote, "Remote", null, null, false).provider());

            UUID nextAccount = UUID.randomUUID();
            fixture.storage.loadOrCreateAccount(nextAccount);
            fixture.storage.updateAppearance(nextAccount, state -> state.withProviders(state.providers()
                    .enable(AppearanceProviders.Component.CAPE, BuiltinProvider.SNEAKY)));
            fixture.session.identity = new GameSessionTokenSource.SessionIdentity(nextAccount, "Next");
            fixture.coordinator.configurationChanged();
            CapeProjection.trackedPlayer(remote, "Remote");
            CapeProjection.visibleSkin(remote, "Remote", key);
            assertEquals(BuiltinProvider.SNEAKY,
                    CapeProjection.resolve(remote, "Remote", null, null, false).provider());
            assertNull(CapeProjection.resolve(pending, "Pending", null, null, false).capeLocation());
        } finally {
            java.lang.ref.Reference.reachabilityFence(nativeTexture);
            java.lang.ref.Reference.reachabilityFence(otherNativeTextures);
            fixture.coordinator.close();
        }
    }

    @Test
    void sneakySelfReadsOnlySelectedAssetAndRespectsCapeOrder() throws Exception {
        Fixture fixture = fixture();
        UUID self = fixture.session.currentSession().profileId();
        byte[] skin = sneakySkin();
        String sha = fixture.storage.storeAsset(skin).sha256();
        fixture.storage.updateAppearance(self, state -> state.withProviders(new AppearanceProviders(
                state.providers().skin().disable(BuiltinProvider.OFFLINE).observeMinecraft(
                        new com.naocraftlab.skins.core.provider.ProviderSkin(sha,
                                com.naocraftlab.skins.core.model.SkinVariant.CLASSIC)),
                state.providers().cape().disable(BuiltinProvider.OPTIFINE)
                        .enable(BuiltinProvider.SNEAKY))));
        fixture.coordinator.start();
        assertEquals(BuiltinProvider.SNEAKY,
                CapeProjection.resolve(self, "Self", null, null, true).provider());
        String ordinarySha = fixture.storage.storeAsset(png()).sha256();
        fixture.storage.updateAppearance(self, state -> state.withProviders(new AppearanceProviders(
                state.providers().skin().observeMinecraft(
                        new com.naocraftlab.skins.core.provider.ProviderSkin(ordinarySha,
                                com.naocraftlab.skins.core.model.SkinVariant.CLASSIC)),
                state.providers().cape())));
        fixture.coordinator.configurationChanged();
        assertNull(CapeProjection.resolve(self, "Self", null, null, true).provider());
        fixture.storage.updateAppearance(self, state -> state.withProviders(new AppearanceProviders(
                state.providers().skin().observeMinecraft(
                        new com.naocraftlab.skins.core.provider.ProviderSkin(sha,
                                com.naocraftlab.skins.core.model.SkinVariant.CLASSIC)),
                state.providers().cape())));
        fixture.coordinator.configurationChanged();
        assertEquals(BuiltinProvider.SNEAKY,
                CapeProjection.resolve(self, "Self", null, null, true).provider());
        fixture.storage.updateAppearance(self, state -> state.withProviders(new AppearanceProviders(
                state.providers().skin(), state.providers().cape()
                        .observeMinecraft(new com.naocraftlab.skins.core.provider.ProviderCape("official", null, false))
                        .move(BuiltinProvider.SNEAKY, -1))));
        fixture.coordinator.configurationChanged();
        assertEquals(BuiltinProvider.SNEAKY,
                CapeProjection.resolve(self, "Self", null, null, true).provider());
    }

    @Test
    void sneakySelfPublishesExactPreviewBytesBeforeObservation() throws Exception {
        Fixture fixture = fixture();
        UUID self = fixture.session.currentSession().profileId();
        byte[] skin = sneakySkin();
        String sha = fixture.storage.storeAsset(skin).sha256();
        fixture.storage.updateAppearance(self, state -> state.withProviders(new AppearanceProviders(
                state.providers().skin().observeMinecraft(
                        new com.naocraftlab.skins.core.provider.ProviderSkin(sha,
                                com.naocraftlab.skins.core.model.SkinVariant.CLASSIC)),
                state.providers().cape().disable(BuiltinProvider.OPTIFINE)
                        .enable(BuiltinProvider.SNEAKY))));
        List<CapeObservationPort.Observation> notifications = new ArrayList<>();
        fixture.coordinator.onCapeObservation(notifications::add);
        fixture.coordinator.start();

        assertEquals(BuiltinProvider.SNEAKY,
                CapeProjection.resolve(self, "Self", null, null, true).provider());
        var observed = notifications.get(notifications.size() - 1).observation();
        assertTrue(observed.known());
        var sneaky = observed.optionalValue().orElseThrow();
        byte[] expected = new com.naocraftlab.skins.core.png.SneakyCapeDecoder()
                .decode(skin).orElseThrow().bytes();
        byte[] preview = new TextureCache(fixture.storage)
                .readIfCached(sneaky.textureCacheKey()).orElseThrow();
        assertArrayEquals(expected, preview);
        assertArrayEquals(preview, fixture.sink.registered.get("nclskins:sneaky/" + self));
    }

    @Test
    void sneakyPayloadInLosingMinecraftSkinDoesNotBecomeOwnCape() throws Exception {
        Fixture fixture = fixture();
        UUID self = fixture.session.currentSession().profileId();
        String sneakySha = fixture.storage.storeAsset(sneakySkin()).sha256();
        String ordinarySha = fixture.storage.storeAsset(png()).sha256();
        fixture.storage.updateAppearance(self, state -> {
            var observed = state.providers().skin().observeMinecraft(
                    new com.naocraftlab.skins.core.provider.ProviderSkin(sneakySha,
                            com.naocraftlab.skins.core.model.SkinVariant.CLASSIC));
            var skin = new com.naocraftlab.skins.core.provider.ProviderChannel<>(observed.order(),
                    com.naocraftlab.skins.core.provider.ProviderObservation.observed(
                            new com.naocraftlab.skins.core.provider.ProviderSkin(ordinarySha,
                                    com.naocraftlab.skins.core.model.SkinVariant.CLASSIC)),
                    observed.minecraft(), observed.configurationRevision(), observed.intentRevision(),
                    observed.desired(), observed.minecraftDelivery(), observed.offlineDesired(),
                    observed.optifine(), observed.skinmc());
            return state.withProviders(new AppearanceProviders(skin,
                    state.providers().cape().disable(BuiltinProvider.OPTIFINE)
                            .enable(BuiltinProvider.SNEAKY)));
        });
        fixture.coordinator.start();
        assertNull(CapeProjection.resolve(self, "Self", null, null, true).capeLocation());
    }

    private static int[] sneakyPixels() {
        int[] pixels = new int[64 * 64];
        int[] markers = {0xfffff42f, 0xffffffff, 0xff9c59d1, 0xff292929};
        for (int index = 0; index < markers.length; index++) pixels[(48 + index) * 64 + 60] = markers[index];
        pixels[16 * 64 + 56] = 0xff123456;
        return pixels;
    }

    private static byte[] sneakySkin() throws IOException {
        BufferedImage image = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(0, 0, 64, 64, sneakyPixels(), 0, 64);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(image, "png", output);
        return output.toByteArray();
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
        fixture.coordinator = CapeTestComposition.create(fixture.session, fixture.storage,
                cache, fixture.sink, new ImmediateClient(), Runnable::run, fixture.reader(),
                (accountId, capeId) -> accountId.equals(self) && capeId.equals("official")
                        ? Optional.of(official) : Optional.empty());
        fixture.coordinator.start();
        assertEquals(BuiltinProvider.MINECRAFT, CapeProjection.resolve(self, "Self",
                null, null, true).provider());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(value = BuiltinProvider.class,
            names = {"OPTIFINE", "SKINMC", "MINECRAFTCAPES"})
    void scopedRefreshUsesOnlyItsScheduleAndRetainsTheBoundedRoster(BuiltinProvider provider) throws Exception {
        AtomicInteger[] reads = {new AtomicInteger(), new AtomicInteger(), new AtomicInteger()};
        AtomicInteger textures = new AtomicInteger();
        Fixture fixture = scopedFixture(Runnable::run, new ImmediateClient(), reads, textures);
        fixture.sink.players.add(new PlayerAppearanceSink.TrackedCapePlayer(UUID.randomUUID(), "Tracked"));
        fixture.coordinator.start();
        for (var count : reads) count.set(0);
        AtomicInteger completed = new AtomicInteger();
        fixture.coordinator.refreshScoped(refreshScope(fixture, provider), provider, observation -> {
            assertNotNull(observation);
            assertNull(observation.value());
            completed.incrementAndGet();
        });
        int selected = provider == BuiltinProvider.OPTIFINE ? 0 : provider == BuiltinProvider.SKINMC ? 1 : 2;
        for (int index = 0; index < reads.length; index++) assertEquals(index == selected ? 2 : 0, reads[index].get());
        assertEquals(1, completed.get());
        assertEquals(0, textures.get());
    }

    @Test
    void scopedAdmissionIgnoresCandidateObservationChangesButRejectsConfigurationIdentityAndGeneration() throws Exception {
        AtomicInteger[] reads = {new AtomicInteger(), new AtomicInteger(), new AtomicInteger()};
        AtomicInteger textures = new AtomicInteger();
        Fixture fixture = scopedFixture(Runnable::run, new ImmediateClient(), reads, textures);
        fixture.coordinator.start();
        var scope = refreshScope(fixture, BuiltinProvider.OPTIFINE);
        fixture.storage.updateAppearance(fixture.session.identity.profileId(), state -> state.withProviders(
                new AppearanceProviders(state.providers().skin(), state.providers().cape()
                        .observeMinecraft(new ProviderCape("uncached", null)))));
        int before = reads[0].get();
        AtomicInteger completed = new AtomicInteger();
        fixture.coordinator.refreshScoped(scope, BuiltinProvider.OPTIFINE, observation -> {
            assertNotNull(observation); completed.incrementAndGet();
        });
        assertEquals(before + 1, reads[0].get());
        assertEquals(0, textures.get());
        var currentScope = refreshScope(fixture, BuiltinProvider.OPTIFINE);
        fixture.storage.updateAppearance(fixture.session.identity.profileId(), state -> state.withProviders(
                state.providers().disable(AppearanceProviders.Component.CAPE, BuiltinProvider.OFFLINE)));
        fixture.coordinator.refreshScoped(currentScope, BuiltinProvider.OPTIFINE, observation -> {
            assertNull(observation); completed.incrementAndGet();
        });
        fixture.session.identity = new GameSessionTokenSource.SessionIdentity(UUID.randomUUID(), "Changed");
        fixture.coordinator.refreshScoped(scope, BuiltinProvider.OPTIFINE, observation -> {
            assertNull(observation); completed.incrementAndGet();
        });
        assertEquals(before + 1, reads[0].get());
        assertEquals(3, completed.get());
        assertEquals(scope.capeGeneration(), fixture.coordinator.observationGeneration());
        assertEquals(0, textures.get());
    }

    private static java.util.stream.Stream<org.junit.jupiter.params.provider.Arguments> externalConfigurationCandidateCases() {
        return java.util.Arrays.stream(BuiltinProvider.values())
                .filter(source -> source.supportsCape() && ProviderRefreshBehavior.of(source, AppearanceProviders.Component.CAPE)
                        .observation() == ProviderRefreshBehavior.Observation.EXTERNAL)
                .flatMap(source -> java.util.stream.Stream.of(false, true).flatMap(enable ->
                        java.util.stream.Stream.of(false, true).flatMap(beforeIssue ->
                                java.util.stream.Stream.of("minecraft", "offline", "mixed").map(candidates ->
                                        org.junit.jupiter.params.provider.Arguments.of(source, enable, beforeIssue, candidates)))));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.MethodSource("externalConfigurationCandidateCases")
    void externalConfigurationPreservesPersistedIndependentCandidates(BuiltinProvider external,
            boolean enable, boolean beforeIssue, String candidates) throws Exception {
        HoldExecutor worker = new HoldExecutor();
        HoldExecutor publications = new HoldExecutor();
        ClientExecutor client = new ClientExecutor() {
            public boolean isClientThread() { return true; }
            public void execute(Runnable action) { publications.execute(action); }
        };
        AtomicInteger loads = new AtomicInteger();
        Fixture fixture = candidateFixture(worker, client, loads);
        UUID account = fixture.session.identity.profileId();
        fixture.storage.updateAppearance(account, state -> {
            var providers = enable ? state.providers().disable(AppearanceProviders.Component.CAPE, external)
                    : state.providers().enable(AppearanceProviders.Component.CAPE, external);
            var cape = providers.cape();
            if (!candidates.equals("minecraft")) cape = cape.select(1, new ProviderCape("local", "a".repeat(64)));
            else cape = cape.disable(BuiltinProvider.OFFLINE);
            if (!candidates.equals("offline")) cape = cape.observeMinecraft(new ProviderCape("official", null));
            else cape = cape.disable(BuiltinProvider.MINECRAFT);
            return new AccountAppearanceState(state.schemaVersion(), account, 1, null, null, null,
                    candidates.equals("minecraft") ? null : "local", OuterLayerVisibility.allVisible(),
                    AppearanceSyncStatus.LOCAL_ONLY, 0, state.updatedAt(), new AppearanceProviders(providers.skin(), cape));
        });
        fixture.coordinator.start();
        long previousGeneration = fixture.coordinator.observationGeneration();
        if (!beforeIssue) worker.runAll();
        fixture.storage.updateAppearance(account, state -> state.withProviders(enable
                ? state.providers().enable(AppearanceProviders.Component.CAPE, external)
                : state.providers().disable(AppearanceProviders.Component.CAPE, external)));
        fixture.coordinator.configurationChanged();
        assertTrue(fixture.coordinator.observationGeneration() > previousGeneration, "external generation remains guarded");
        for (int repeat = 0; repeat < 5; repeat++) fixture.coordinator.configurationChanged();
        worker.runAll(); publications.runAll();
        worker.runAll(); publications.runAll();
        assertEquals(candidates.equals("offline") ? 0 : 1, loads.get(), "only the original candidate load is admitted");
        assertEquals(!candidates.equals("offline"), fixture.sink.registered.containsKey("nclskins:minecraft/" + account));
        assertEquals(!candidates.equals("minecraft"), fixture.sink.registered.containsKey("nclskins:offline/" + account));
        if (!enable) assertFalse(fixture.sink.registered.containsKey("nclskins:" + external.name().toLowerCase() + "/" + account));
        int nativeWrites = fixture.sink.events.size();
        int reads = fixture.calls.get();
        fixture.coordinator.configurationChanged(); worker.runAll(); publications.runAll();
        assertEquals(nativeWrites, fixture.sink.events.size());
        assertEquals(reads, fixture.calls.get(), "repeated callbacks create no external demand");
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"world,true", "world,false", "worldEntered,true", "worldEntered,false",
            "account,true", "account,false", "accountRoundTrip,true", "accountRoundTrip,false",
            "close,true", "close,false", "registration,true", "registration,false"})
    void lifecycleRejectsOriginalCandidateWithoutClearingNewAdmission(String boundary, boolean beforeIssue) throws Exception {
        HoldExecutor worker = new HoldExecutor();
        HoldExecutor publications = new HoldExecutor();
        ClientExecutor client = new ClientExecutor() {
            public boolean isClientThread() { return true; }
            public void execute(Runnable action) { publications.execute(action); }
        };
        AtomicInteger loads = new AtomicInteger();
        Fixture fixture = candidateFixture(worker, client, loads);
        UUID original = fixture.session.identity.profileId();
        fixture.storage.updateAppearance(original, state -> state.withProviders(new AppearanceProviders(
                state.providers().skin(), state.providers().cape().observeMinecraft(new ProviderCape("official", null)))));
        fixture.coordinator.start();
        if (!beforeIssue) worker.runAll();
        CapeProjection.Registration replacement = null;
        switch (boundary) {
            case "world" -> {
                CapeProjection.worldChanged();
                publications.jobs.remove(publications.jobs.size() - 1).run();
            }
            case "worldEntered" -> {
                CapeProjection.worldEntered();
                publications.jobs.remove(publications.jobs.size() - 1).run();
            }
            case "account", "accountRoundTrip" -> {
                UUID next = UUID.randomUUID();
                fixture.storage.loadOrCreateAccount(next);
                fixture.storage.updateAppearance(next, state -> state.withProviders(new AppearanceProviders(
                        state.providers().skin(), state.providers().cape().observeMinecraft(new ProviderCape("official", null)))));
                fixture.session.identity = new GameSessionTokenSource.SessionIdentity(next, "Other");
                fixture.coordinator.configurationChanged();
                if (boundary.equals("accountRoundTrip")) {
                    fixture.session.identity = new GameSessionTokenSource.SessionIdentity(original, "Self");
                    fixture.coordinator.configurationChanged();
                }
            }
            case "close" -> fixture.coordinator.close();
            case "registration" -> replacement = CapeProjection.installEvents(new CapeProjection.Events() {
                public void trackedPlayer(UUID id, String name) {}
                public void playerInfoUpdated(UUID id, String name) {}
                public void untrackedPlayer(UUID id) {}
                public void worldChanged() {}
                public void worldEntered() {}
            });
            default -> throw new AssertionError(boundary);
        }
        try {
            publications.runAll();
            assertFalse(fixture.sink.registered.containsKey("nclskins:minecraft/" + original));
            worker.runAll(); publications.runAll();
            boolean terminal = boundary.equals("close") || boundary.equals("registration");
            assertEquals((beforeIssue ? 0 : 1) + (terminal ? 0 : 1), loads.get());
            assertEquals(!terminal, fixture.sink.registered.containsKey("nclskins:minecraft/" + fixture.session.identity.profileId()));
        } finally {
            fixture.coordinator.close();
            if (replacement != null) replacement.close();
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void offlineRefreshPreservesAdmittedMinecraftPreparationAndLatestOffline(boolean publishMinecraftFirst) throws Exception {
        HoldExecutor worker = new HoldExecutor();
        HoldExecutor clientJobs = new HoldExecutor();
        ClientExecutor client = new ClientExecutor() {
            public boolean isClientThread() { return true; }
            public void execute(Runnable action) { clientJobs.execute(action); }
        };
        AtomicInteger loads = new AtomicInteger();
        Fixture fixture = candidateFixture(worker, client, loads);
        fixture.coordinator.start();
        var initial = fixture.storage.loadAppearance(fixture.session.identity.profileId()).providers();
        var minecraft = new AppearanceProviders(initial.skin(), initial.cape().select(1, new ProviderCape("old-local", "a".repeat(64))).observeMinecraft(new ProviderCape("official", null)));
        fixture.coordinator.selfCapeCandidatesChanged(fixture.session.identity.profileId(), "Self", minecraft);
        worker.runAll();
        assertEquals(1, loads.get());
        if (publishMinecraftFirst) clientJobs.runAll();
        var offline = new AppearanceProviders(minecraft.skin(), minecraft.cape().select(2, new ProviderCape("local", "b".repeat(64))));
        fixture.coordinator.selfCapeCandidatesChanged(fixture.session.identity.profileId(), "Self", offline, Set.of(BuiltinProvider.OFFLINE));
        worker.runAll();
        if (!publishMinecraftFirst && clientJobs.jobs.size() > 1) {
            clientJobs.jobs.remove(clientJobs.jobs.size() - 1).run();
        }
        clientJobs.runAll();
        assertEquals(1, loads.get());
        assertNotNull(fixture.sink.registered.get("nclskins:minecraft/" + fixture.session.identity.profileId()));
        assertArrayEquals(new PngValidator().projectCanonicalCape(substitutedPng(64)).bytes(),
                fixture.sink.registered.get("nclskins:offline/" + fixture.session.identity.profileId()),
                "stale Offline completion cannot replace the latest local texture");
    }

    @Test
    void corruptInitialOfflineReplacementKeepsPreviouslyInstalledCandidateLive() throws Exception {
        AtomicInteger loads = new AtomicInteger();
        Fixture fixture = candidateFixture(Runnable::run, new ImmediateClient(), loads);
        fixture.coordinator.start();
        UUID account = fixture.session.identity.profileId();

        Path oldPng = directory.resolve("offline-good.png");
        Files.write(oldPng, png());
        var oldAsset = new CapeAssetDecoder().prepare(oldPng, directory.resolve("offline-good.frames"));
        fixture.localPreparedCape.set(oldAsset);
        var initial = fixture.storage.loadAppearance(account).providers();
        var oldState = new AppearanceProviders(initial.skin(), initial.cape().select(2,
                new ProviderCape("old-local", oldAsset.visualSha256())));
        fixture.coordinator.selfCapeCandidatesChanged(account, "Self", oldState, Set.of(BuiltinProvider.OFFLINE));
        String location = "nclskins:offline/" + account;
        byte[] confirmed = fixture.sink.registered.get(location);
        assertNotNull(confirmed, "the old offline candidate must be installed before replacement");

        Path replacementPng = directory.resolve("offline-replacement.png");
        Files.write(replacementPng, substitutedPng(64));
        var replacementSource = new CapeAssetDecoder().prepare(
                replacementPng, directory.resolve("offline-replacement.frames"));
        var corrupt = new com.naocraftlab.skins.core.png.FrameReadFixture(replacementSource);
        corrupt.integrityOpenFailures.set(1);
        fixture.localPreparedCape.set(corrupt.asset);
        var replacement = new AppearanceProviders(oldState.skin(), oldState.cape().select(3,
                new ProviderCape("replacement-local", corrupt.asset.visualSha256())));

        fixture.coordinator.selfCapeCandidatesChanged(account, "Self", replacement, Set.of(BuiltinProvider.OFFLINE));

        assertEquals(1, corrupt.opens.get());
        assertArrayEquals(confirmed, fixture.sink.registered.get(location),
                "corruption in the not-yet-installed replacement must not release the previous candidate");
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"beforeEffect", "changed", "roundTrip", "disable", "account", "generation", "close"})
    void candidateTicketsRejectOnlyTheirStaleSourceAndNeverTransferReadPermission(String change) throws Exception {
        HoldExecutor worker = new HoldExecutor();
        HoldExecutor publications = new HoldExecutor();
        ClientExecutor client = new ClientExecutor() {
            public boolean isClientThread() { return true; }
            public void execute(Runnable action) { publications.execute(action); }
        };
        AtomicInteger loads = new AtomicInteger();
        Fixture fixture = candidateFixture(worker, client, loads);
        UUID accountId = fixture.session.identity.profileId();
        fixture.coordinator.start();
        worker.runAll(); publications.runAll();
        var initial = fixture.storage.loadAppearance(fixture.session.identity.profileId()).providers();
        var first = new AppearanceProviders(initial.skin(), initial.cape().observeMinecraft(new ProviderCape("first", null)));
        fixture.coordinator.selfCapeCandidatesChanged(fixture.session.identity.profileId(), "Self", first);
        if (!change.equals("beforeEffect")) worker.runAll();
        int admittedReads = change.equals("beforeEffect") ? 0 : 1;
        assertEquals(admittedReads, loads.get());
        var next = new AppearanceProviders(first.skin(), first.cape().select(1, new ProviderCape("local", "a".repeat(64))));
        if (change.equals("beforeEffect") || change.equals("changed") || change.equals("roundTrip"))
            next = new AppearanceProviders(next.skin(), next.cape().observeMinecraft(new ProviderCape("second", null)));
        if (change.equals("disable")) next = next.disable(AppearanceProviders.Component.CAPE, BuiltinProvider.MINECRAFT);
        fixture.coordinator.selfCapeCandidatesChanged(fixture.session.identity.profileId(), "Self", next, Set.of(BuiltinProvider.OFFLINE));
        if (change.equals("roundTrip")) fixture.coordinator.selfCapeCandidatesChanged(fixture.session.identity.profileId(), "Self",
                new AppearanceProviders(next.skin(), next.cape().observeMinecraft(new ProviderCape("first", null))), Set.of(BuiltinProvider.OFFLINE));
        if (change.equals("account")) fixture.session.identity = new GameSessionTokenSource.SessionIdentity(UUID.randomUUID(), "Other");
        if (change.equals("generation")) fixture.coordinator.worldChanged();
        if (change.equals("close")) fixture.coordinator.close();
        publications.runAll();
        assertFalse(fixture.sink.registered.containsKey("nclskins:minecraft/" + accountId), change);
        if (!change.equals("generation")) {
            worker.runAll(); publications.runAll();
            assertEquals(admittedReads, loads.get(), "no read permission transferred to new observation");
        }
        if (List.of("beforeEffect", "changed", "roundTrip", "disable").contains(change)) {
            assertNotNull(fixture.sink.registered.get("nclskins:offline/" + fixture.session.identity.profileId()));
            var finalProviders = next.enable(AppearanceProviders.Component.CAPE, BuiltinProvider.MINECRAFT);
            fixture.coordinator.selfCapeCandidatesChanged(fixture.session.identity.profileId(), "Self", finalProviders, Set.of(BuiltinProvider.MINECRAFT));
            worker.runAll(); publications.runAll();
            assertEquals(admittedReads + 1, loads.get());
            assertNotNull(fixture.sink.registered.get("nclskins:minecraft/" + fixture.session.identity.profileId()));
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"initial", "latest", "client"})
    void candidateRejectionClearsTicketsAndNextCheckpointWorksWithoutAutomaticRetry(String rejection) throws Exception {
        HoldExecutor workers = new HoldExecutor();
        HoldExecutor publications = new HoldExecutor();
        AtomicBoolean rejectWorker = new AtomicBoolean();
        AtomicBoolean rejectClient = new AtomicBoolean();
        ClientExecutor client = new ClientExecutor() {
            public boolean isClientThread() { return true; }
            public void execute(Runnable action) {
                if (rejectClient.get()) throw new java.util.concurrent.RejectedExecutionException();
                publications.execute(action);
            }
        };
        AtomicInteger loads = new AtomicInteger();
        Fixture fixture = candidateFixture(action -> {
            if (rejectWorker.get()) throw new java.util.concurrent.RejectedExecutionException();
            workers.execute(action);
        }, client, loads);
        fixture.coordinator.start(); workers.runAll(); publications.runAll();
        var initial = fixture.storage.loadAppearance(fixture.session.identity.profileId()).providers();
        var next = new AppearanceProviders(initial.skin(), initial.cape().observeMinecraft(new ProviderCape("official", null)));
        if (rejection.equals("initial")) rejectWorker.set(true);
        fixture.coordinator.selfCapeCandidatesChanged(fixture.session.identity.profileId(), "Self", next);
        if (rejection.equals("latest")) {
            next = new AppearanceProviders(next.skin(), next.cape().select(1, new ProviderCape("local", "a".repeat(64))));
            fixture.coordinator.selfCapeCandidatesChanged(fixture.session.identity.profileId(), "Self", next, Set.of(BuiltinProvider.OFFLINE));
            rejectWorker.set(true);
        }
        if (rejection.equals("client")) rejectClient.set(true);
        workers.runAll();
        assertTrue(fixture.sink.registered.isEmpty(), "worker rejection never writes native state");
        rejectWorker.set(false); rejectClient.set(false);
        publications.runAll();
        int beforeRetry = loads.get();
        workers.runAll(); publications.runAll();
        assertEquals(beforeRetry, loads.get(), "terminal failure does not retry");
        fixture.coordinator.selfCapeCandidatesChanged(fixture.session.identity.profileId(), "Self", next);
        workers.runAll(); publications.runAll();
        assertNotNull(fixture.sink.registered.get("nclskins:minecraft/" + fixture.session.identity.profileId()));
        if (rejection.equals("latest")) assertNotNull(fixture.sink.registered.get("nclskins:offline/" + fixture.session.identity.profileId()));
        assertEquals(rejection.equals("client") ? 2 : 1, loads.get());
    }

    @Test
    void failedCandidatePreparationKeepsItsLastConfirmedNativeTextureAndDoesNotRetryFromAnotherScope() throws Exception {
        AtomicInteger loads = new AtomicInteger();
        Fixture fixture = candidateFixture(Runnable::run, new ImmediateClient(), loads);
        fixture.coordinator.start();
        var initial = fixture.storage.loadAppearance(fixture.session.identity.profileId()).providers();
        var first = new AppearanceProviders(initial.skin(), initial.cape().observeMinecraft(new ProviderCape("first", null)));
        fixture.coordinator.selfCapeCandidatesChanged(fixture.session.identity.profileId(), "Self", first);
        String location = "nclskins:minecraft/" + fixture.session.identity.profileId();
        byte[] confirmed = fixture.sink.registered.get(location);
        assertNotNull(confirmed);
        fixture.failCandidateLoad.set(true);
        var next = new AppearanceProviders(first.skin(), first.cape().observeMinecraft(new ProviderCape("second", null)));
        fixture.coordinator.selfCapeCandidatesChanged(fixture.session.identity.profileId(), "Self", next);
        assertEquals(2, loads.get());
        assertArrayEquals(confirmed, fixture.sink.registered.get(location));
        fixture.failCandidateLoad.set(false);
        fixture.coordinator.selfCapeCandidatesChanged(fixture.session.identity.profileId(), "Self", next, Set.of(BuiltinProvider.OFFLINE));
        assertEquals(2, loads.get());
        assertArrayEquals(confirmed, fixture.sink.registered.get(location));
        fixture.coordinator.selfCapeCandidatesChanged(fixture.session.identity.profileId(), "Self", next, Set.of(BuiltinProvider.MINECRAFT));
        assertEquals(3, loads.get());
        assertNotNull(fixture.sink.registered.get(location));
    }

    private Fixture candidateFixture(java.util.concurrent.Executor worker, ClientExecutor client,
            AtomicInteger loads) throws Exception {
        Fixture fixture = fixture();
        fixture.coordinator.close();
        var delegate = new ProviderTextureStorageAdapter(fixture.storage, new TextureCache(fixture.storage));
        var textures = (com.naocraftlab.skins.core.service.ProviderTextureStore) java.lang.reflect.Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{com.naocraftlab.skins.core.service.ProviderTextureStore.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("load")) {
                        loads.incrementAndGet();
                        if (fixture.failCandidateLoad.get()) throw new IOException("fixture preparation failure");
                        return png();
                    }
                    if (method.getName().equals("readLocalPreparedCape")) {
                        var prepared = fixture.localPreparedCape.get();
                        if (prepared != null && prepared.visualSha256().equals(args[1])) return Optional.of(prepared);
                    }
                    if (method.getName().equals("readIfCached") && args[0].equals("a".repeat(64))) return Optional.of(png());
                    if (method.getName().equals("readIfCached") && args[0].equals("b".repeat(64))) return Optional.of(substitutedPng(64));
                    try { return method.invoke(delegate, args); }
                    catch (java.lang.reflect.InvocationTargetException failure) { throw failure.getCause(); }
                });
        var minecraftCapes = new MinecraftCapesCapeReader(new MinecraftCapesCapeReader.Transport() {
            public MinecraftCapesCapeReader.ProfileResponse profile(URI uri, Duration timeout, long max) throws IOException {
                return new MinecraftCapesCapeReader.ProfileResponse(404,
                        MinecraftCapesCapeReader.parse("{}".getBytes(java.nio.charset.StandardCharsets.UTF_8)), Map.of());
            }
            public MinecraftCapesCapeReader.Response image(URI uri, Duration timeout, long max, Path destination) {
                throw new AssertionError("absence has no image");
            }
        }, new CapeAssetDecoder(), Clock.systemUTC(), System::nanoTime, new SecureRandom(), directory.resolve("candidate-mc"));
        fixture.coordinator = new CapeProviderCoordinator(fixture.session,
                new com.naocraftlab.skins.core.storage.AccountAppearanceStorageAdapter(fixture.storage),
                new com.naocraftlab.skins.core.storage.LibraryStorageAdapter(fixture.storage), textures,
                fixture.sink, client, worker, fixture.reader(),
                CapeTestComposition.skinMc(fixture.storage, (uri, timeout, max) -> new OptifineCapeReader.Response(404, new byte[0]), Clock.systemUTC()), minecraftCapes,
                (id, cape) -> Optional.of(URI.create("https://textures.minecraft.net/texture/fixture-cape")));
        return fixture;
    }

    @Test
    void scopedCandidatePublicationCannotDownloadForeignMinecraftButLifecycleStillCan() throws Exception {
        AtomicInteger[] reads = {new AtomicInteger(), new AtomicInteger(), new AtomicInteger()};
        AtomicInteger textures = new AtomicInteger();
        Fixture fixture = scopedFixture(Runnable::run, new ImmediateClient(), reads, textures);
        fixture.coordinator.start();
        var providers = fixture.storage.loadAppearance(fixture.session.identity.profileId()).providers();
        var next = new AppearanceProviders(providers.skin(), providers.cape().observeMinecraft(new ProviderCape("uncached", null)));
        fixture.coordinator.selfCapeCandidatesChanged(fixture.session.identity.profileId(), fixture.session.identity.profileName(),
                next, Set.of(BuiltinProvider.OFFLINE));
        assertEquals(0, textures.get());
        var changed = new AppearanceProviders(next.skin(), next.cape().observeMinecraft(new ProviderCape("other", null)));
        fixture.coordinator.selfCapeCandidatesChanged(fixture.session.identity.profileId(), fixture.session.identity.profileName(), changed);
        assertEquals(1, textures.get());
    }

    @Test
    void scopedWorkerAndClientRejectionsAndCloseCompleteWithoutConfirmedAbsence() throws Exception {
        for (String terminal : List.of("worker", "client", "close")) {
            AtomicInteger[] reads = {new AtomicInteger(), new AtomicInteger(), new AtomicInteger()};
            HoldExecutor worker = new HoldExecutor();
            AtomicBoolean rejectWorker = new AtomicBoolean();
            AtomicBoolean rejectClient = new AtomicBoolean();
            ClientExecutor client = new ClientExecutor() {
                public boolean isClientThread() { return true; }
                public void execute(Runnable action) {
                    if (rejectClient.get()) throw new java.util.concurrent.RejectedExecutionException();
                    action.run();
                }
            };
            Fixture fixture = scopedFixture(action -> {
                if (rejectWorker.get()) throw new java.util.concurrent.RejectedExecutionException();
                worker.execute(action);
            }, client, reads, new AtomicInteger());
            fixture.coordinator.start();
            worker.runAll();
            AtomicInteger completed = new AtomicInteger();
            if (terminal.equals("worker")) rejectWorker.set(true);
            fixture.coordinator.refreshScoped(refreshScope(fixture, BuiltinProvider.OPTIFINE), BuiltinProvider.OPTIFINE,
                    observation -> { assertNull(observation); completed.incrementAndGet(); });
            if (terminal.equals("client")) rejectClient.set(true);
            if (terminal.equals("close")) fixture.coordinator.close();
            else worker.runAll();
            assertEquals(1, completed.get(), terminal);
            rejectClient.set(false);
        }
    }

    @Test
    void clientRejectionWhenCapacityReturnsTerminatesScopedCallback() throws Exception {
        AtomicInteger[] reads = {new AtomicInteger(), new AtomicInteger(), new AtomicInteger()};
        HoldExecutor worker = new HoldExecutor();
        AtomicBoolean rejectClient = new AtomicBoolean();
        ClientExecutor client = new ClientExecutor() {
            public boolean isClientThread() { return true; }
            public void execute(Runnable action) {
                if (rejectClient.get()) throw new java.util.concurrent.RejectedExecutionException();
                action.run();
            }
        };
        Fixture fixture = scopedFixture(worker, client, reads, new AtomicInteger());
        fixture.coordinator.start();
        worker.runAll();
        AtomicInteger completed = new AtomicInteger();
        try (var busy = CapePreparationQueue.tryReserveScratch(CapePreparationQueue.CAPE_PREPARATION_PEAK)) {
            assertNotNull(busy);
            refreshScoped(fixture, BuiltinProvider.OPTIFINE,
                    observation -> { assertNull(observation); completed.incrementAndGet(); });
            worker.runAll();
            assertEquals(0, completed.get());
            rejectClient.set(true);
        }
        assertEquals(1, completed.get());
        rejectClient.set(false);
        fixture.coordinator.close();
    }

    @Test
    void configurationChangeBeforeQueuedScopedHttpDoesNotReadOrResetOtherSchedules() throws Exception {
        AtomicInteger[] reads = {new AtomicInteger(), new AtomicInteger(), new AtomicInteger()};
        HoldExecutor worker = new HoldExecutor();
        Fixture fixture = scopedFixture(worker, new ImmediateClient(), reads, new AtomicInteger());
        fixture.coordinator.start();
        worker.runAll();
        for (var count : reads) count.set(0);
        AtomicInteger completed = new AtomicInteger();
        fixture.coordinator.refreshScoped(refreshScope(fixture, BuiltinProvider.OPTIFINE), BuiltinProvider.OPTIFINE,
                observation -> { assertNull(observation); completed.incrementAndGet(); });
        fixture.storage.updateAppearance(fixture.session.identity.profileId(), state -> state.withProviders(state.providers()
                .disable(AppearanceProviders.Component.CAPE, BuiltinProvider.OFFLINE)));
        worker.runAll();
        assertEquals(1, completed.get());
        for (var count : reads) assertEquals(0, count.get());
    }

    @Test
    void scopedRefreshPreservesPreviouslyAdmittedIndependentBackgroundDemand() throws Exception {
        AtomicInteger[] reads = {new AtomicInteger(), new AtomicInteger(), new AtomicInteger()};
        HoldExecutor worker = new HoldExecutor();
        Fixture fixture = scopedFixture(worker, new ImmediateClient(), reads, new AtomicInteger());
        fixture.coordinator.start();
        worker.runAll();
        for (var count : reads) count.set(0);
        UUID tracked = UUID.randomUUID();
        fixture.sink.players.add(new PlayerAppearanceSink.TrackedCapePlayer(tracked, "Tracked"));
        fixture.coordinator.trackedPlayer(tracked, "Tracked");
        fixture.coordinator.refreshScoped(refreshScope(fixture, BuiltinProvider.OPTIFINE), BuiltinProvider.OPTIFINE, ignored -> {});
        worker.runAll();
        assertEquals(1, reads[1].get());
        assertEquals(1, reads[2].get());
        assertTrue(reads[0].get() >= 1);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(value = BuiltinProvider.class,
            names = {"OPTIFINE", "SKINMC", "MINECRAFTCAPES"})
    void completedScopedRefreshDoesNotFenceLaterLifecycleDemand(BuiltinProvider provider) throws Exception {
        AtomicInteger[] reads = {new AtomicInteger(), new AtomicInteger(), new AtomicInteger()};
        Fixture fixture = scopedFixture(Runnable::run, new ImmediateClient(), reads, new AtomicInteger());
        fixture.coordinator.start();
        refreshScoped(fixture, provider, observation -> assertNotNull(observation));
        fixture.storage.updateAppearance(fixture.session.identity.profileId(), state -> state.withProviders(state.providers()
                .disable(AppearanceProviders.Component.CAPE, BuiltinProvider.OFFLINE)));
        fixture.coordinator.configurationChanged();
        for (var count : reads) count.set(0);
        UUID tracked = UUID.randomUUID();
        fixture.sink.players.add(new PlayerAppearanceSink.TrackedCapePlayer(tracked, "Later"));
        fixture.coordinator.trackedPlayer(tracked, "Later");
        for (var count : reads) assertEquals(1, count.get(), "Later lifecycle demand has its own permissions");
    }

    private void refreshScoped(Fixture fixture, BuiltinProvider provider,
            java.util.function.Consumer<com.naocraftlab.skins.core.provider.ProviderObservation<ProviderCape>> completion) throws Exception {
        fixture.coordinator.refreshScoped(refreshScope(fixture, provider), provider, completion);
    }

    private ProviderRefreshUseCases.Scope refreshScope(Fixture fixture, BuiltinProvider provider) throws Exception {
        var before = fixture.storage.loadAppearance(fixture.session.identity.profileId()).providers();
        var request = ProviderRefreshRequest.one(AppearanceProviders.Component.CAPE, provider);
        return new ProviderRefreshUseCases.Scope(request, fixture.session.identity, before, request.resolve(before),
                fixture.coordinator.observationGeneration());
    }

    private Fixture scopedFixture(java.util.concurrent.Executor worker, ClientExecutor client,
            AtomicInteger[] reads, AtomicInteger textureLoads) throws Exception {
        Fixture fixture = fixture();
        fixture.coordinator.close();
        fixture.storage.updateAppearance(fixture.session.identity.profileId(), state -> state.withProviders(state.providers()
                .enable(AppearanceProviders.Component.CAPE, BuiltinProvider.SKINMC)
                .enable(AppearanceProviders.Component.CAPE, BuiltinProvider.MINECRAFTCAPES)));
        var cache = new TextureCache(fixture.storage);
        var delegate = new ProviderTextureStorageAdapter(fixture.storage, cache);
        var textures = (com.naocraftlab.skins.core.service.ProviderTextureStore) java.lang.reflect.Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{com.naocraftlab.skins.core.service.ProviderTextureStore.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("load")) {
                        textureLoads.incrementAndGet();
                        throw new IOException("fixture download");
                    }
                    try { return method.invoke(delegate, args); }
                    catch (java.lang.reflect.InvocationTargetException failure) { throw failure.getCause(); }
                });
        var minecraftCapes = new MinecraftCapesCapeReader(new MinecraftCapesCapeReader.Transport() {
            public MinecraftCapesCapeReader.ProfileResponse profile(URI uri, Duration timeout, long max) throws IOException {
                reads[2].incrementAndGet();
                return new MinecraftCapesCapeReader.ProfileResponse(404,
                        MinecraftCapesCapeReader.parse("{}".getBytes(java.nio.charset.StandardCharsets.UTF_8)), Map.of());
            }
            public MinecraftCapesCapeReader.Response image(URI uri, Duration timeout, long max, Path destination) {
                throw new AssertionError("absence has no image");
            }
        }, new CapeAssetDecoder(), Clock.systemUTC(), System::nanoTime, new SecureRandom(), directory.resolve("scoped-mc"));
        fixture.coordinator = new CapeProviderCoordinator(fixture.session,
                new com.naocraftlab.skins.core.storage.AccountAppearanceStorageAdapter(fixture.storage),
                new com.naocraftlab.skins.core.storage.LibraryStorageAdapter(fixture.storage), textures,
                fixture.sink, client, worker,
                CapeTestComposition.optifine(fixture.storage, (uri, timeout, max) -> {
                    reads[0].incrementAndGet(); return new OptifineCapeReader.Response(404, new byte[0]);
                }, Clock.systemUTC()),
                CapeTestComposition.skinMc(fixture.storage, (uri, timeout, max) -> {
                    reads[1].incrementAndGet(); return new OptifineCapeReader.Response(404, new byte[0]);
                }, Clock.systemUTC()), minecraftCapes,
                (id, cape) -> Optional.of(URI.create("https://textures.minecraft.net/texture/scoped-official")));
        return fixture;
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
        fixtures.add(fixture);
        fixture.image = png();
        fixture.coordinator = CapeTestComposition.create(session, storage, new TextureCache(storage),
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
        fixture.coordinator = CapeTestComposition.create(fixture.session, fixture.storage,
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

    private static Object schedule(CapeProviderCoordinator coordinator) throws Exception {
        var field = CapeProviderCoordinator.class.getDeclaredField("optifineSchedule");
        field.setAccessible(true);
        return field.get(coordinator);
    }

    private static Set<?> deferredActors(CapeProviderCoordinator coordinator) throws Exception {
        Object scheduler = schedule(coordinator);
        var field = scheduler.getClass().getDeclaredField("repeatPending");
        field.setAccessible(true);
        return (Set<?>) field.get(scheduler);
    }

    private static byte[] png() throws IOException {
        return pngWithPixel(0xff123456);
    }

    private static byte[] pngWithPixel(int color) throws IOException {
        BufferedImage image = new BufferedImage(64, 32, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(1, 1, color);
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
        private final AtomicBoolean failCandidateLoad = new AtomicBoolean();
        private final AtomicReference<com.naocraftlab.skins.core.png.PreparedCapeAsset> localPreparedCape = new AtomicReference<>();
        private final Map<String, AtomicInteger> perPlayerReads = new HashMap<>();
        private volatile int status = 404;
        private volatile byte[] image;
        private CapeProviderCoordinator coordinator;
        private ManualExecutor worker;

        private Fixture(FakeSession session, NclSkinsStorage storage, FakeSink sink) {
            this.session = session;
            this.storage = storage;
            this.sink = sink;
        }

        private OptifineCapeReader reader() throws IOException {
            return CapeTestComposition.optifine(storage, (URI uri, java.time.Duration timeout, long maxBytes) -> {
                calls.incrementAndGet();
                String path = uri.getPath();
                String name = path.substring(path.lastIndexOf('/') + 1, path.length() - 4);
                perPlayerReads.computeIfAbsent(name, ignored -> new AtomicInteger()).incrementAndGet();
                return new OptifineCapeReader.Response(status, status == 200 ? image : new byte[0]);
            }, Clock.systemUTC());
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

    private static final class MutableClock extends Clock {
        private final AtomicLong seconds = new AtomicLong(Instant.parse("2026-09-25T10:00:00Z").getEpochSecond());

        void advanceSeconds(long count) { seconds.addAndGet(count); }

        @Override public ZoneId getZone() { return ZoneOffset.UTC; }

        @Override public Clock withZone(ZoneId zone) { return this; }

        @Override public Instant instant() { return Instant.ofEpochSecond(seconds.get()); }
    }

    private static final class ImmediateClient implements ClientExecutor {
        @Override public boolean isClientThread() { return true; }
        @Override public void execute(Runnable action) { action.run(); }
    }

    private static final class QueuedClient implements ClientExecutor {
        private final List<Runnable> jobs = new ArrayList<>();
        private boolean running;

        @Override public boolean isClientThread() { return running; }
        @Override public void execute(Runnable action) { jobs.add(action); }
        private boolean hasJobs() { return !jobs.isEmpty(); }
        private void runNext() {
            Runnable job = jobs.remove(0);
            runInline(job);
        }
        private void runInline(Runnable job) {
            running = true;
            try { job.run(); }
            finally { running = false; }
        }
        private void runAll() {
            int count = 0;
            while (!jobs.isEmpty() && count++ < 2_000) runNext();
            assertTrue(jobs.isEmpty());
        }
    }

    private static final class CountingExecutor implements java.util.concurrent.Executor {
        private int calls;

        @Override
        public void execute(Runnable action) {
            calls++;
            action.run();
        }
    }

    private static final class HoldExecutor implements java.util.concurrent.Executor {
        private final List<Runnable> jobs = new ArrayList<>();

        @Override public void execute(Runnable action) { jobs.add(action); }

        void runAll() {
            int count = 0;
            while (!jobs.isEmpty() && count++ < 2_000) jobs.remove(0).run();
            assertTrue(jobs.isEmpty());
        }
    }

    private static final class RegistryBackedSink implements PlayerAppearanceSink<Object> {
        private boolean rejectFrame;
        private final Registry registry = new Registry();
        private final Map<InstalledKey, TextureRegistry.TextureHandle> current = new HashMap<>();
        private final Map<InstalledKey, TextureRegistry.TextureHandle> previous = new HashMap<>();
        private final List<String> rawLocations = new ArrayList<>();

        @Override public ApplyResult apply(SignedProfileResolver.ResolvedProfile<Object> profile) {
            return ApplyResult.UPDATED;
        }

        @Override public Optional<String> registerCapeTexture(UUID profileId, CapeSource source,
                String sha256, byte[] png) {
            try {
                return install(new InstalledKey(profileId, source),
                        registry.register(TextureRegistry.TextureKind.IMAGE, sha256, png));
            } catch (IOException unavailable) {
                return Optional.empty();
            }
        }

        @Override public Optional<String> registerCapeFrame(UUID profileId, CapeSource source,
                String sha256, int width, int height, byte[] rgba8, TextureRegistry.CpuLease cpuLease) {
            if (rejectFrame) return Optional.empty();
            try {
                TextureRegistry.TextureHandle next = registry.registerRgba(
                        TextureRegistry.TextureKind.IMAGE, sha256, width, height, rgba8, cpuLease);
                rawLocations.add(next.location());
                return install(new InstalledKey(profileId, source), next);
            } catch (IOException unavailable) {
                return Optional.empty();
            }
        }

        private Optional<String> install(InstalledKey key, TextureRegistry.TextureHandle next) {
            TextureRegistry.TextureHandle old = current.put(key, next);
            release(previous.remove(key));
            if (old != null) {
                if (key.source == CapeSource.MINECRAFTCAPES || key.source == CapeSource.OFFLINE) {
                    previous.put(key, old);
                } else {
                    release(old);
                }
            }
            return Optional.of(next.location());
        }

        @Override public void releaseCapeTexture(UUID profileId, CapeSource source) {
            InstalledKey key = new InstalledKey(profileId, source);
            release(current.remove(key));
            release(previous.remove(key));
        }

        private boolean live(String location) { return registry.live(location); }

        private void release(TextureRegistry.TextureHandle handle) {
            if (handle != null) registry.release(handle);
        }

        private record InstalledKey(UUID profileId, CapeSource source) { }
    }

    private static final class Registry extends AbstractTextureRegistry<String> {
        private final Map<String, byte[]> physical = new HashMap<>();

        @Override protected LoadedTexture<String> load(TextureKind kind, String sha256,
                byte[] pngBytes) {
            String location = "nclskins:live/appearance/image/" + sha256;
            physical.put(location, pngBytes.clone());
            return new LoadedTexture<>(new TextureHandle(location, 64, 32), location);
        }

        @Override protected LoadedTexture<String> loadRgba(String sha256, int width, int height,
                byte[] rgba8) {
            String location = "nclskins:live/appearance/image/" + sha256;
            physical.put(location, rgba8.clone());
            return new LoadedTexture<>(new TextureHandle(location, width, height), location);
        }

        @Override protected void unload(String location) { physical.remove(location); }
        @Override protected void checkClientThread() { }
        private boolean live(String location) { return physical.containsKey(location); }
    }

    private static final class FakeSink implements PlayerAppearanceSink<Object> {
        private final List<TrackedCapePlayer> players = new ArrayList<>();
        private final Map<String, byte[]> registered = new HashMap<>();
        private final List<String> events = new ArrayList<>();
        private int registrations;
        private int rawRegistrations;
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
            events.add("register");
            String location = "nclskins:" + source.name().toLowerCase() + "/" + profileId;
            registered.put(location, normalizedPng.clone());
            return Optional.of(location);
        }

        @Override
        public Optional<String> registerCapeFrame(UUID profileId, CapeSource source,
                String frameSha256, int width, int height, byte[] rgba8,
                com.naocraftlab.skins.client.TextureRegistry.CpuLease cpuLease) {
            rawRegistrations++;
            java.awt.image.BufferedImage frame = new java.awt.image.BufferedImage(
                    width, height, java.awt.image.BufferedImage.TYPE_INT_ARGB);
            int[] row = new int[width];
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    int offset = (y * width + x) * 4;
                    row[x] = ((rgba8[offset + 3] & 255) << 24)
                            | ((rgba8[offset] & 255) << 16)
                            | ((rgba8[offset + 1] & 255) << 8) | (rgba8[offset + 2] & 255);
                }
                frame.setRGB(0, y, width, 1, row, 0, width);
            }
            try {
                java.io.ByteArrayOutputStream encoded = new java.io.ByteArrayOutputStream();
                javax.imageio.ImageIO.write(frame, "png", encoded);
                return registerCapeTexture(profileId, source, frameSha256, encoded.toByteArray());
            } catch (IOException failure) {
                throw new AssertionError(failure);
            } finally {
                cpuLease.close();
            }
        }

        @Override
        public void releaseCapeTexture(UUID profileId, CapeSource source) {
            releases++;
            events.add("release");
            registered.remove("nclskins:" + source.name().toLowerCase() + "/" + profileId);
        }
    }

    private static final class ManualExecutor extends AbstractExecutorService {
        private final List<Runnable> jobs = new ArrayList<>();
        private boolean shutdown;
        private boolean running;

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
            Runnable job = jobs.remove(0);
            running = true;
            try { job.run(); }
            finally { running = false; }
        }
    }
}

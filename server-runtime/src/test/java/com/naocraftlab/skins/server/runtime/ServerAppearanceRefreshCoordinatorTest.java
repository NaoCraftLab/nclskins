package com.naocraftlab.skins.server.runtime;

import com.naocraftlab.skins.server.Admission;
import com.naocraftlab.skins.server.BatchAppearancePublisher;
import com.naocraftlab.skins.server.BatchPublicationResult;
import com.naocraftlab.skins.server.ConnectionKey;
import com.naocraftlab.skins.server.ConnectionSnapshot;
import com.naocraftlab.skins.server.IdentityAssurance;
import com.naocraftlab.skins.server.OfficialProfileResolver;
import com.naocraftlab.skins.server.PublicationMetrics;
import com.naocraftlab.skins.server.PublicationOutcome;
import com.naocraftlab.skins.server.PublicationRequest;
import com.naocraftlab.skins.server.RefreshResult;
import com.naocraftlab.skins.server.RefreshSubmission;
import com.naocraftlab.skins.server.SignedTexturesProperty;
import com.naocraftlab.skins.server.TextureAppearance;
import com.naocraftlab.skins.server.VerifiedOfficialProfile;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.PriorityQueue;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ServerAppearanceRefreshCoordinatorTest {
    @Test
    void retriesRespectDeterministicOffsetsAndPerProfileCooldown() {
        ManualTime time = new ManualTime();
        SequenceResolver resolver = new SequenceResolver(time);
        resolver.add(OfficialProfileResolver.Resolution.transientFailure());
        resolver.add(OfficialProfileResolver.Resolution.transientFailure());
        resolver.add(OfficialProfileResolver.Resolution.transientFailure());
        resolver.add(connection -> resolved(connection, 'a'));
        RecordingPublisher publisher = new RecordingPublisher();
        ServerAppearanceRefreshCoordinator coordinator = coordinator(
                time, resolver, publisher, defaultPolicy());

        RefreshSubmission submission = coordinator.request(connection(1, 1L));
        time.advance(Duration.ofSeconds(16));

        assertEquals(Admission.ACCEPTED, submission.admission());
        assertEquals(RefreshResult.UPDATED, result(submission));
        assertEquals(
                List.of(
                        Duration.ofMillis(500).toNanos(),
                        Duration.ofMillis(5_500).toNanos(),
                        Duration.ofMillis(10_500).toNanos(),
                        Duration.ofMillis(15_500).toNanos()),
                resolver.callTimes);
        assertEquals(List.of(1), publisher.batchSizes);
        assertEquals(4L, coordinator.health().lookups());
        assertEquals(1L, coordinator.health().lookupResolved());
        assertEquals(3L, coordinator.health().lookupTransientFailures());
        coordinator.close();
    }

    @Test
    void retryCooldownStartsAtDelayedFirstDispatchInsteadOfOriginalQueueTime() {
        ManualTime time = new ManualTime();
        ConnectionSnapshot blocker = connection(2, 1L);
        ConnectionSnapshot target = connection(3, 1L);
        CompletableFuture<OfficialProfileResolver.Resolution> held = new CompletableFuture<>();
        AtomicInteger blockerCalls = new AtomicInteger();
        AtomicInteger targetCalls = new AtomicInteger();
        List<Long> targetTimes = new ArrayList<>();
        OfficialProfileResolver resolver = connection -> {
            if (connection.key().equals(blocker.key())) {
                return blockerCalls.incrementAndGet() == 1
                        ? held
                        : OfficialProfileResolver.completed(
                                OfficialProfileResolver.Resolution.transientFailure());
            }
            targetCalls.incrementAndGet();
            targetTimes.add(time.now());
            return targetCalls.get() == 1
                    ? OfficialProfileResolver.completed(
                            OfficialProfileResolver.Resolution.transientFailure())
                    : OfficialProfileResolver.completed(resolved(connection, 'a'));
        };
        ServerRefreshPolicy policy = policy(
                16,
                1,
                10_000.0d,
                20,
                List.of(Duration.ofMillis(500), Duration.ofSeconds(2)),
                Duration.ofMinutes(5),
                Duration.ofSeconds(30),
                Duration.ofMillis(50),
                64,
                0);
        ServerAppearanceRefreshCoordinator coordinator = coordinator(
                time, resolver, new RecordingPublisher(), policy);

        coordinator.request(blocker);
        RefreshSubmission targetSubmission = coordinator.request(target);
        time.advance(Duration.ofMillis(500));
        assertEquals(0, targetCalls.get());
        time.advance(Duration.ofSeconds(10));
        held.complete(OfficialProfileResolver.Resolution.transientFailure());
        assertEquals(1, targetCalls.get());

        time.advance(Duration.ofMillis(4_999));
        assertEquals(1, targetCalls.get());
        time.advance(Duration.ofMillis(1));
        time.advance(Duration.ofMillis(50));

        assertEquals(2, targetCalls.get());
        assertEquals(
                Duration.ofSeconds(5).toNanos(),
                targetTimes.get(1) - targetTimes.get(0));
        assertEquals(RefreshResult.UPDATED, result(targetSubmission));
        coordinator.close();
    }

    @Test
    void onlyTwoLookupsAreInFlightAndReadyPlayersRemainFair() {
        ManualTime time = new ManualTime();
        List<CompletableFuture<OfficialProfileResolver.Resolution>> held = new ArrayList<>();
        AtomicInteger calls = new AtomicInteger();
        OfficialProfileResolver resolver = connection -> {
            calls.incrementAndGet();
            CompletableFuture<OfficialProfileResolver.Resolution> future =
                    new CompletableFuture<>();
            held.add(future);
            return future;
        };
        ServerAppearanceRefreshCoordinator coordinator = coordinator(
                time, resolver, new RecordingPublisher(), defaultPolicy());
        for (int index = 0; index < 5; index++) {
            coordinator.request(connection(index, 1L));
        }

        time.advance(Duration.ofMillis(500));
        assertEquals(2, calls.get());
        assertEquals(2, coordinator.health().lookupsInFlight());

        held.get(0).complete(OfficialProfileResolver.Resolution.transientFailure());
        assertEquals(3, calls.get());
        assertEquals(2, coordinator.health().lookupsInFlight());
        coordinator.close();
    }

    @Test
    void retryAfterCreatesOneGlobalBarrierForFirstAttemptsAndRetries() {
        ManualTime time = new ManualTime();
        SequenceResolver resolver = new SequenceResolver(time);
        resolver.add(OfficialProfileResolver.Resolution.throttled(Duration.ofSeconds(10)));
        resolver.add(connection -> resolved(connection, 'b'));
        RecordingPublisher publisher = new RecordingPublisher();
        ServerRefreshPolicy policy = policy(
                16, 1, 10.0d, 20, List.of(Duration.ofMillis(500), Duration.ofSeconds(2)),
                Duration.ofMinutes(5), Duration.ofSeconds(30), Duration.ofMillis(50), 64, 2);
        ServerAppearanceRefreshCoordinator coordinator = coordinator(
                time, resolver, publisher, policy);
        RefreshSubmission first = coordinator.request(connection(10, 1L));
        RefreshSubmission second = coordinator.request(connection(11, 1L));

        time.advance(Duration.ofMillis(500));
        assertEquals(1, resolver.calls.get());
        time.advance(Duration.ofMillis(9_999));
        assertEquals(1, resolver.calls.get());
        time.advance(Duration.ofMillis(1));
        assertEquals(3, resolver.calls.get());
        time.advance(Duration.ofMillis(50));

        assertTrue(
                result(first) == RefreshResult.EXHAUSTED
                        || result(first) == RefreshResult.EXPIRED);
        assertEquals(RefreshResult.UPDATED, result(second));
        assertEquals(1L, coordinator.health().throttled());
        coordinator.close();
    }

    @Test
    void retryAfterFromASupersededLookupStillInstallsTheGlobalBarrier() {
        ManualTime time = new ManualTime();
        ConnectionSnapshot connection = connection(12, 1L);
        CompletableFuture<OfficialProfileResolver.Resolution> firstLookup =
                new UncancellableFuture<>();
        AtomicInteger calls = new AtomicInteger();
        OfficialProfileResolver resolver = ignored -> calls.incrementAndGet() == 1
                ? firstLookup
                : OfficialProfileResolver.completed(resolved(connection, 'a'));
        RecordingPublisher publisher = new RecordingPublisher();
        ServerAppearanceRefreshCoordinator coordinator = coordinator(
                time, resolver, publisher, defaultPolicy());

        RefreshSubmission first = coordinator.request(connection);
        time.advance(Duration.ofMillis(500));
        RefreshSubmission latest = coordinator.request(connection);
        assertEquals(RefreshResult.SUPERSEDED, result(first));
        firstLookup.complete(OfficialProfileResolver.Resolution.throttled(
                Duration.ofSeconds(10)));

        time.advance(Duration.ofMillis(9_999));
        assertEquals(1, calls.get());
        time.advance(Duration.ofMillis(1));
        time.advance(Duration.ofMillis(50));

        assertEquals(2, calls.get());
        assertEquals(RefreshResult.UPDATED, result(latest));
        assertEquals(1L, coordinator.health().throttled());
        coordinator.close();
    }

    @Test
    void retryCooldownBeginsAtFirstDispatchAfterADeepGlobalThrottleQueue() {
        ManualTime time = new ManualTime();
        ConnectionSnapshot throttler = connection(13, 1L);
        ConnectionSnapshot queued = connection(14, 1L);
        Map<UUID, Integer> attempts = new HashMap<>();
        List<Long> queuedCallTimes = new ArrayList<>();
        OfficialProfileResolver resolver = connection -> {
            int attempt = attempts.merge(connection.key().profileId(), 1, Integer::sum);
            if (connection.key().equals(throttler.key()) && attempt == 1) {
                return OfficialProfileResolver.completed(
                        OfficialProfileResolver.Resolution.throttled(Duration.ofSeconds(100)));
            }
            if (connection.key().equals(queued.key())) {
                queuedCallTimes.add(time.now());
                return OfficialProfileResolver.completed(attempt == 1
                        ? OfficialProfileResolver.Resolution.transientFailure()
                        : resolved(connection, 'c'));
            }
            return OfficialProfileResolver.completed(resolved(connection, 'b'));
        };
        ServerRefreshPolicy policy = policy(
                16, 1, 10_000.0d, 20,
                List.of(Duration.ofMillis(500), Duration.ofSeconds(2)),
                Duration.ofMinutes(5), Duration.ofSeconds(30),
                Duration.ofMillis(50), 64, 0);
        ServerAppearanceRefreshCoordinator coordinator = coordinator(
                time, resolver, new RecordingPublisher(), policy);

        coordinator.request(throttler);
        RefreshSubmission submission = coordinator.request(queued);
        time.advance(Duration.ofMillis(100_500));
        assertEquals(List.of(Duration.ofMillis(100_500).toNanos()), queuedCallTimes);

        time.advance(Duration.ofMillis(4_999));
        assertEquals(1, queuedCallTimes.size());
        time.advance(Duration.ofMillis(1));
        assertEquals(
                List.of(
                        Duration.ofMillis(100_500).toNanos(),
                        Duration.ofMillis(105_500).toNanos()),
                queuedCallTimes);
        time.advance(Duration.ofMillis(50));

        assertEquals(RefreshResult.UPDATED, result(submission));
        coordinator.close();
    }

    @Test
    void oneThousandDistinctSignalsAreAdmittedAndDrainWithoutLocalDrops() {
        ManualTime time = new ManualTime();
        SequenceResolver resolver = new SequenceResolver(time);
        resolver.fallback = connection -> resolved(connection, 'c');
        RecordingPublisher publisher = new RecordingPublisher();
        ServerAppearanceRefreshCoordinator coordinator = coordinator(
                time, resolver, publisher, ServerRefreshPolicy.defaults(false, 1_000));
        List<RefreshSubmission> submissions = new ArrayList<>();

        for (int index = 0; index < 1_000; index++) {
            RefreshSubmission submission = coordinator.request(connection(index, 1L));
            assertEquals(Admission.ACCEPTED, submission.admission());
            submissions.add(submission);
        }
        time.advance(Duration.ofMinutes(2));

        assertTrue(submissions.stream().allMatch(
                submission -> result(submission) == RefreshResult.UPDATED));
        assertEquals(1_000, resolver.calls.get());
        assertEquals(1_000, publisher.actorCount.get());
        assertEquals(0, coordinator.health().pending());
        assertEquals(0L, coordinator.health().overloaded());
        coordinator.close();
    }

    @Test
    void oneFiveTenAndFiftyChangesPerSecondAllConvergeAfterTheBurst() {
        for (int changesPerSecond : new int[] {1, 5, 10, 50}) {
            ManualTime time = new ManualTime();
            SequenceResolver resolver = new SequenceResolver(time);
            resolver.fallback = connection -> resolved(connection, 'e');
            RecordingPublisher publisher = new RecordingPublisher();
            ServerAppearanceRefreshCoordinator coordinator = coordinator(
                    time,
                    resolver,
                    publisher,
                    ServerRefreshPolicy.defaults(false, 1_000));
            List<RefreshSubmission> submissions = new ArrayList<>();
            Duration interval = Duration.ofNanos(
                    Duration.ofSeconds(1).toNanos() / changesPerSecond);

            for (int index = 0; index < changesPerSecond; index++) {
                RefreshSubmission submission = coordinator.request(connection(
                        changesPerSecond * 10_000 + index, 1L));
                assertTrue(Set.of(Admission.ACCEPTED, Admission.COALESCED)
                        .contains(submission.admission()));
                submissions.add(submission);
                time.advance(interval);
            }
            time.advance(Duration.ofMinutes(1));

            assertTrue(submissions.stream().allMatch(
                    submission -> result(submission) == RefreshResult.UPDATED));
            assertEquals(changesPerSecond, resolver.calls.get());
            assertEquals(changesPerSecond, publisher.actorCount.get());
            assertEquals(0, coordinator.health().pending());
            assertEquals(0L, coordinator.health().overloaded());
            coordinator.close();
        }
    }

    @Test
    void publicationBatchesAreChunkedAtSixtyFourActors() {
        ManualTime time = new ManualTime();
        SequenceResolver resolver = new SequenceResolver(time);
        resolver.fallback = connection -> resolved(connection, 'd');
        RecordingPublisher publisher = new RecordingPublisher();
        ServerRefreshPolicy policy = policy(
                256, 256, 10_000.0d, 256, List.of(Duration.ofMillis(500)),
                Duration.ofMinutes(5), Duration.ofSeconds(30), Duration.ofMillis(50), 64, 0);
        ServerAppearanceRefreshCoordinator coordinator = coordinator(
                time, resolver, publisher, policy);
        List<RefreshSubmission> submissions = new ArrayList<>();
        for (int index = 0; index < 130; index++) {
            submissions.add(coordinator.request(connection(index, 2L)));
        }

        time.advance(Duration.ofSeconds(1));

        assertEquals(List.of(64, 64, 2), publisher.batchSizes);
        assertTrue(submissions.stream().allMatch(
                submission -> result(submission) == RefreshResult.UPDATED));
        coordinator.close();
    }

    @Test
    void healthPublishesOnlyAggregateLookupQueueAndNativeBatchMeasurements() {
        ManualTime time = new ManualTime();
        SequenceResolver resolver = new SequenceResolver(time);
        resolver.add(connection -> resolved(connection, 'd'));
        RecordingPublisher publisher = new RecordingPublisher();
        publisher.metrics =
                new PublicationMetrics(
                        100L,
                        99L,
                        2L,
                        7L,
                        202_752L,
                        4_000_000L,
                        1_500_000L,
                        2L,
                        3L);
        ServerAppearanceRefreshCoordinator coordinator = coordinator(
                time, resolver, publisher, defaultPolicy());

        RefreshSubmission submission = coordinator.request(connection(19, 2L));
        time.advance(Duration.ofMillis(250));
        assertEquals(Duration.ofMillis(250).toNanos(), coordinator.health().oldestQueueAgeNanos());
        time.advance(Duration.ofSeconds(1));

        assertEquals(RefreshResult.UPDATED, result(submission));
        assertEquals(100L, coordinator.health().publicationRecipients());
        assertEquals(99L, coordinator.health().publicationProfileDeliveries());
        assertEquals(2L, coordinator.health().publicationPacketChunks());
        assertEquals(7L, coordinator.health().publicationWatcherPairs());
        assertEquals(202_752L, coordinator.health().estimatedEgressBytes());
        assertEquals(4_000_000L, coordinator.health().platformThreadTotalNanos());
        assertEquals(1_500_000L, coordinator.health().platformThreadMaxNanos());
        assertEquals(2L, coordinator.health().reconciliationAttempts());
        assertEquals(3L, coordinator.health().reconciliationDeliveries());
        assertFalse(coordinator.health().toString().contains("Player19"));
        coordinator.close();
    }

    @Test
    void coalescingSupersedesTheOldFutureAndDisconnectPurgesTheLatestIntent() {
        ManualTime time = new ManualTime();
        CompletableFuture<OfficialProfileResolver.Resolution> held = new CompletableFuture<>();
        AtomicInteger calls = new AtomicInteger();
        OfficialProfileResolver resolver = ignored -> {
            calls.incrementAndGet();
            return held;
        };
        RecordingPublisher publisher = new RecordingPublisher();
        ServerAppearanceRefreshCoordinator coordinator = coordinator(
                time, resolver, publisher, defaultPolicy());
        ConnectionSnapshot connection = connection(20, 7L);

        RefreshSubmission first = coordinator.request(connection);
        RefreshSubmission latest = coordinator.request(connection);
        assertEquals(Admission.COALESCED, latest.admission());
        assertEquals(RefreshResult.SUPERSEDED, result(first));
        time.advance(Duration.ofMillis(500));
        assertEquals(1, calls.get());
        coordinator.disconnected(connection.key());
        assertEquals(RefreshResult.DISCONNECTED, result(latest));
        assertTrue(held.isCancelled());
        assertEquals(0, coordinator.health().lookupsInFlight());

        held.complete(resolved(connection, 'e'));
        time.advance(Duration.ofSeconds(1));
        assertEquals(0, publisher.actorCount.get());
        assertEquals(0, coordinator.health().pending());
        coordinator.close();
    }

    @Test
    void coalescingDuringTheFinalLookupWaitsForPerProfileCooldownAndFetchesNewestRevision() {
        ManualTime time = new ManualTime();
        List<CompletableFuture<OfficialProfileResolver.Resolution>> lookups = new ArrayList<>();
        AtomicInteger calls = new AtomicInteger();
        OfficialProfileResolver resolver = ignored -> {
            calls.incrementAndGet();
            CompletableFuture<OfficialProfileResolver.Resolution> lookup =
                    new CompletableFuture<>();
            lookups.add(lookup);
            return lookup;
        };
        RecordingPublisher publisher = new RecordingPublisher();
        ServerRefreshPolicy oneAttempt = policy(
                16, 1, 10.0d, 20, List.of(Duration.ofMillis(500)),
                Duration.ofMinutes(5), Duration.ofSeconds(30), Duration.ofMillis(50), 64, 0);
        ServerAppearanceRefreshCoordinator coordinator = coordinator(
                time, resolver, publisher, oneAttempt);
        ConnectionSnapshot connection = connection(21, 8L);

        RefreshSubmission first = coordinator.request(connection);
        time.advance(Duration.ofMillis(500));
        assertEquals(1, calls.get());

        RefreshSubmission latest = coordinator.request(connection);
        assertEquals(RefreshResult.SUPERSEDED, result(first));
        assertFalse(lookups.get(0).isCancelled());
        assertEquals(1, coordinator.health().lookupsInFlight());
        lookups.get(0).complete(OfficialProfileResolver.Resolution.transientFailure());
        time.advance(Duration.ZERO);

        assertEquals(1, calls.get());
        time.advance(Duration.ofMillis(4_999));
        assertEquals(1, calls.get());
        time.advance(Duration.ofMillis(1));
        assertEquals(2, calls.get());
        lookups.get(1).complete(resolved(connection, 'a'));
        time.advance(Duration.ofMillis(50));

        assertEquals(RefreshResult.UPDATED, result(latest));
        assertEquals(1, publisher.actorCount.get());
        coordinator.close();
    }

    @Test
    void completedCycleKeepsCooldownFromItsLastLookup() {
        ManualTime time = new ManualTime();
        List<Long> callTimes = new ArrayList<>();
        ConnectionSnapshot connection = connection(58, 1L);
        ServerAppearanceRefreshCoordinator coordinator = coordinator(
                time,
                ignored -> {
                    callTimes.add(time.now());
                    return OfficialProfileResolver.completed(resolved(connection, 'd'));
                },
                new RecordingPublisher(),
                defaultPolicy());

        RefreshSubmission first = coordinator.request(connection);
        time.advance(Duration.ofMillis(550));
        assertEquals(RefreshResult.UPDATED, result(first));

        RefreshSubmission second = coordinator.request(connection);
        time.advance(Duration.ofMillis(5_449));
        assertEquals(List.of(Duration.ofMillis(500).toNanos()), callTimes);
        time.advance(Duration.ofMillis(1));
        time.advance(Duration.ofMillis(50));

        assertEquals(
                List.of(
                        Duration.ofMillis(500).toNanos(),
                        Duration.ofSeconds(6).toNanos()),
                callTimes);
        assertEquals(RefreshResult.UPDATED, result(second));
        coordinator.close();
    }

    @Test
    void signalFloodKeepsOneTimerAndOneLatestLookup() {
        ManualTime time = new ManualTime();
        AtomicInteger calls = new AtomicInteger();
        ServerAppearanceRefreshCoordinator coordinator = coordinator(
                time,
                connection -> {
                    calls.incrementAndGet();
                    return OfficialProfileResolver.completed(resolved(connection, 'f'));
                },
                new RecordingPublisher(),
                defaultPolicy());
        ConnectionSnapshot connection = connection(22, 9L);

        RefreshSubmission latest = coordinator.request(connection);
        for (int index = 0; index < 10_000; index++) {
            latest = coordinator.request(connection);
        }

        assertEquals(1, time.pendingTasks());
        time.advance(Duration.ofMillis(500));
        time.advance(Duration.ofMillis(50));
        assertEquals(1, calls.get());
        assertEquals(RefreshResult.UPDATED, result(latest));
        coordinator.close();
    }

    @Test
    void signalFloodDuringActiveLookupCreatesOneCooldownBoundFollowUp() {
        ManualTime time = new ManualTime();
        List<CompletableFuture<OfficialProfileResolver.Resolution>> lookups = new ArrayList<>();
        List<Long> callTimes = new ArrayList<>();
        OfficialProfileResolver resolver = ignored -> {
            callTimes.add(time.now());
            CompletableFuture<OfficialProfileResolver.Resolution> lookup =
                    new CompletableFuture<>();
            lookups.add(lookup);
            return lookup;
        };
        RecordingPublisher publisher = new RecordingPublisher();
        ServerAppearanceRefreshCoordinator coordinator = coordinator(
                time, resolver, publisher, defaultPolicy());
        ConnectionSnapshot connection = connection(52, 1L);

        RefreshSubmission latest = coordinator.request(connection);
        time.advance(Duration.ofMillis(500));
        for (int index = 0; index < 10_000; index++) {
            latest = coordinator.request(connection);
        }
        lookups.get(0).complete(OfficialProfileResolver.Resolution.transientFailure());

        assertEquals(1, time.pendingTasks());
        time.advance(Duration.ofMillis(4_999));
        assertEquals(1, lookups.size());
        time.advance(Duration.ofMillis(1));
        assertEquals(
                List.of(Duration.ofMillis(500).toNanos(), Duration.ofMillis(5_500).toNanos()),
                callTimes);

        lookups.get(1).complete(resolved(connection, 'a'));
        time.advance(Duration.ofMillis(50));

        assertEquals(RefreshResult.UPDATED, result(latest));
        assertEquals(2L, coordinator.health().lookups());
        assertEquals(10_000L, coordinator.health().coalesced());
        assertEquals(1, publisher.actorCount.get());
        coordinator.close();
    }

    @Test
    void continuousSignalFloodCannotExceedThePerProfileCooldownOrCycleDeadline() {
        ManualTime time = new ManualTime();
        List<CompletableFuture<OfficialProfileResolver.Resolution>> lookups = new ArrayList<>();
        List<Long> callTimes = new ArrayList<>();
        OfficialProfileResolver resolver = ignored -> {
            callTimes.add(time.now());
            CompletableFuture<OfficialProfileResolver.Resolution> lookup =
                    new CompletableFuture<>();
            lookups.add(lookup);
            return lookup;
        };
        ServerAppearanceRefreshCoordinator coordinator = coordinator(
                time, resolver, new RecordingPublisher(), defaultPolicy());
        ConnectionSnapshot connection = connection(53, 1L);

        RefreshSubmission latest = coordinator.request(connection);
        time.advance(Duration.ofMillis(500));
        for (int lookup = 0; lookup < 6; lookup++) {
            for (int signal = 0; signal < 100; signal++) {
                latest = coordinator.request(connection);
            }
            lookups.get(lookup).complete(
                    OfficialProfileResolver.Resolution.transientFailure());
            time.advance(Duration.ofSeconds(5));
        }

        assertEquals(
                List.of(
                        Duration.ofMillis(500).toNanos(),
                        Duration.ofMillis(5_500).toNanos(),
                        Duration.ofMillis(10_500).toNanos(),
                        Duration.ofMillis(15_500).toNanos(),
                        Duration.ofMillis(20_500).toNanos(),
                        Duration.ofMillis(25_500).toNanos()),
                callTimes);
        assertEquals(6L, coordinator.health().lookups());
        assertEquals(RefreshResult.EXPIRED, result(latest));
        assertEquals(0, coordinator.health().pending());
        assertEquals(0, time.pendingTasks());
        coordinator.close();
    }

    @Test
    void readyLatestIntentRunsBeforeAnAttackersCooldownFollowUp() {
        ManualTime time = new ManualTime();
        ConnectionSnapshot attacker = connection(54, 1L);
        ConnectionSnapshot legitimate = connection(55, 1L);
        CompletableFuture<OfficialProfileResolver.Resolution> attackerLookup =
                new CompletableFuture<>();
        List<ConnectionKey> callOrder = new ArrayList<>();
        OfficialProfileResolver resolver = connection -> {
            callOrder.add(connection.key());
            if (connection.key().equals(attacker.key()) && callOrder.size() == 1) {
                return attackerLookup;
            }
            return connection.key().equals(legitimate.key())
                    ? OfficialProfileResolver.completed(resolved(connection, 'b'))
                    : OfficialProfileResolver.completed(
                            OfficialProfileResolver.Resolution.transientFailure());
        };
        ServerRefreshPolicy policy = policy(
                16, 1, 10_000.0d, 20, List.of(Duration.ofMillis(500)),
                Duration.ofMinutes(5), Duration.ofSeconds(30), Duration.ofMillis(50), 64, 0);
        ServerAppearanceRefreshCoordinator coordinator = coordinator(
                time, resolver, new RecordingPublisher(), policy);

        coordinator.request(attacker);
        RefreshSubmission originalLegitimate = coordinator.request(legitimate);
        time.advance(Duration.ofMillis(500));
        RefreshSubmission latestAttacker = coordinator.request(attacker);
        RefreshSubmission latestLegitimate = coordinator.request(legitimate);
        assertEquals(RefreshResult.SUPERSEDED, result(originalLegitimate));

        attackerLookup.complete(OfficialProfileResolver.Resolution.transientFailure());
        assertEquals(List.of(attacker.key(), legitimate.key()), callOrder);
        time.advance(Duration.ofMillis(50));

        assertEquals(RefreshResult.UPDATED, result(latestLegitimate));
        time.advance(Duration.ofMillis(4_949));
        assertEquals(2, callOrder.size());
        time.advance(Duration.ofMillis(1));
        assertEquals(List.of(attacker.key(), legitimate.key(), attacker.key()), callOrder);
        assertEquals(RefreshResult.EXHAUSTED, result(latestAttacker));
        coordinator.close();
    }

    @Test
    void coalescingABatchQueuedPublicationWaitsForTheProfileCooldown() {
        ManualTime time = new ManualTime();
        AtomicInteger calls = new AtomicInteger();
        ConnectionSnapshot connection = connection(56, 1L);
        RecordingPublisher publisher = new RecordingPublisher();
        ServerAppearanceRefreshCoordinator coordinator = coordinator(
                time,
                ignored -> {
                    calls.incrementAndGet();
                    return OfficialProfileResolver.completed(resolved(connection, 'c'));
                },
                publisher,
                defaultPolicy());

        RefreshSubmission first = coordinator.request(connection);
        time.advance(Duration.ofMillis(500));
        RefreshSubmission latest = coordinator.request(connection);
        assertEquals(RefreshResult.SUPERSEDED, result(first));
        assertEquals(List.of(connection.key()), publisher.superseded);

        time.advance(Duration.ofMillis(50));
        assertEquals(0, publisher.batchSizes.size());
        time.advance(Duration.ofMillis(4_949));
        assertEquals(1, calls.get());
        time.advance(Duration.ofMillis(1));
        assertEquals(2, calls.get());
        time.advance(Duration.ofMillis(50));

        assertEquals(RefreshResult.UPDATED, result(latest));
        assertEquals(List.of(1), publisher.batchSizes);
        coordinator.close();
    }

    @Test
    void disconnectCancelsACooldownBoundFollowUp() {
        ManualTime time = new ManualTime();
        List<CompletableFuture<OfficialProfileResolver.Resolution>> lookups = new ArrayList<>();
        ConnectionSnapshot connection = connection(57, 1L);
        ServerAppearanceRefreshCoordinator coordinator = coordinator(
                time,
                ignored -> {
                    CompletableFuture<OfficialProfileResolver.Resolution> lookup =
                            new CompletableFuture<>();
                    lookups.add(lookup);
                    return lookup;
                },
                new RecordingPublisher(),
                defaultPolicy());

        RefreshSubmission first = coordinator.request(connection);
        time.advance(Duration.ofMillis(500));
        RefreshSubmission latest = coordinator.request(connection);
        assertEquals(RefreshResult.SUPERSEDED, result(first));
        lookups.get(0).complete(OfficialProfileResolver.Resolution.transientFailure());
        assertEquals(1, time.pendingTasks());

        coordinator.disconnected(connection.key());
        assertEquals(RefreshResult.DISCONNECTED, result(latest));
        time.advance(Duration.ofSeconds(10));

        assertEquals(1, lookups.size());
        assertEquals(0, coordinator.health().pending());
        assertEquals(0, time.pendingTasks());
        coordinator.close();
    }

    @Test
    void closeCancelsACooldownBoundFollowUp() {
        ManualTime time = new ManualTime();
        List<CompletableFuture<OfficialProfileResolver.Resolution>> lookups = new ArrayList<>();
        ConnectionSnapshot connection = connection(59, 1L);
        ServerAppearanceRefreshCoordinator coordinator = coordinator(
                time,
                ignored -> {
                    CompletableFuture<OfficialProfileResolver.Resolution> lookup =
                            new CompletableFuture<>();
                    lookups.add(lookup);
                    return lookup;
                },
                new RecordingPublisher(),
                defaultPolicy());

        RefreshSubmission first = coordinator.request(connection);
        time.advance(Duration.ofMillis(500));
        RefreshSubmission latest = coordinator.request(connection);
        assertEquals(RefreshResult.SUPERSEDED, result(first));
        lookups.get(0).complete(OfficialProfileResolver.Resolution.transientFailure());
        assertEquals(1, time.pendingTasks());

        coordinator.close();
        assertEquals(RefreshResult.CLOSED, result(latest));
        time.advance(Duration.ofSeconds(10));

        assertEquals(1, lookups.size());
        assertEquals(0, coordinator.health().pending());
        assertEquals(0, time.pendingTasks());
    }

    @Test
    void coalescingAnActivePublicationFencesTheOldActorBeforeTheNextBatch() {
        ManualTime time = new ManualTime();
        ConnectionSnapshot connection = connection(27, 1L);
        CompletableFuture<BatchPublicationResult> firstBatch = new CompletableFuture<>();
        List<PublicationRequest> firstRequests = new ArrayList<>();
        List<ConnectionKey> superseded = new ArrayList<>();
        AtomicInteger batches = new AtomicInteger();
        BatchAppearancePublisher publisher = new BatchAppearancePublisher() {
            @Override
            public CompletionStage<BatchPublicationResult> publishBatch(
                    List<PublicationRequest> requests) {
                if (batches.incrementAndGet() == 1) {
                    firstRequests.addAll(requests);
                    return firstBatch;
                }
                return CompletableFuture.completedFuture(
                        BatchPublicationResult.all(requests, PublicationOutcome.UPDATED));
            }

            @Override
            public void supersede(ConnectionKey key) {
                superseded.add(key);
            }
        };
        ServerAppearanceRefreshCoordinator coordinator = coordinator(
                time,
                ignored -> OfficialProfileResolver.completed(resolved(connection, 'a')),
                publisher,
                defaultPolicy());

        RefreshSubmission old = coordinator.request(connection);
        time.advance(Duration.ofMillis(550));
        assertEquals(1, batches.get());

        RefreshSubmission latest = coordinator.request(connection);
        assertEquals(RefreshResult.SUPERSEDED, result(old));
        assertEquals(List.of(connection.key()), superseded);
        time.advance(Duration.ofMillis(500));
        assertEquals(1, batches.get());

        firstBatch.complete(BatchPublicationResult.all(
                firstRequests, PublicationOutcome.UPDATED));
        time.advance(Duration.ofMillis(4_449));
        assertEquals(1, batches.get());
        time.advance(Duration.ofMillis(1));
        time.advance(Duration.ofMillis(50));

        assertEquals(2, batches.get());
        assertEquals(RefreshResult.UPDATED, result(latest));
        coordinator.close();
    }

    @Test
    void coalescedSignalKeepsTheOriginalAttemptOriginAndCycleDeadline() {
        ManualTime time = new ManualTime();
        List<CompletableFuture<OfficialProfileResolver.Resolution>> lookups = new ArrayList<>();
        List<Long> callTimes = new ArrayList<>();
        OfficialProfileResolver resolver = ignored -> {
            callTimes.add(time.now());
            CompletableFuture<OfficialProfileResolver.Resolution> lookup =
                    new CompletableFuture<>();
            lookups.add(lookup);
            return lookup;
        };
        RecordingPublisher publisher = new RecordingPublisher();
        ServerAppearanceRefreshCoordinator coordinator = coordinator(
                time, resolver, publisher, defaultPolicy());
        ConnectionSnapshot connection = connection(22, 9L);

        RefreshSubmission first = coordinator.request(connection);
        time.advance(Duration.ofMillis(500));
        time.advance(Duration.ofMillis(10_500));
        RefreshSubmission latest = coordinator.request(connection);
        assertEquals(RefreshResult.SUPERSEDED, result(first));
        lookups.get(0).complete(OfficialProfileResolver.Resolution.transientFailure());

        time.advance(Duration.ZERO);
        assertEquals(
                List.of(Duration.ofMillis(500).toNanos(), Duration.ofSeconds(11).toNanos()),
                callTimes);
        lookups.get(1).complete(resolved(connection, 'b'));
        time.advance(Duration.ofMillis(50));

        assertEquals(RefreshResult.UPDATED, result(latest));
        coordinator.close();
    }

    @Test
    void coalescedFollowUpDoesNotRestoreUsedRetryBudget() {
        ManualTime time = new ManualTime();
        CompletableFuture<OfficialProfileResolver.Resolution> held = new CompletableFuture<>();
        AtomicInteger calls = new AtomicInteger();
        OfficialProfileResolver resolver = ignored -> calls.incrementAndGet() == 1
                ? held
                : OfficialProfileResolver.completed(
                        OfficialProfileResolver.Resolution.transientFailure());
        ServerRefreshPolicy policy = policy(
                16, 1, 10_000.0d, 20,
                List.of(Duration.ofMillis(500), Duration.ofSeconds(2)),
                Duration.ofMinutes(5), Duration.ofSeconds(30),
                Duration.ofMillis(50), 64, 0);
        ServerAppearanceRefreshCoordinator coordinator = coordinator(
                time, resolver, new RecordingPublisher(), policy);
        ConnectionSnapshot connection = connection(60, 1L);

        RefreshSubmission first = coordinator.request(connection);
        time.advance(Duration.ofMillis(500));
        RefreshSubmission latest = coordinator.request(connection);
        assertEquals(RefreshResult.SUPERSEDED, result(first));
        held.complete(OfficialProfileResolver.Resolution.transientFailure());

        time.advance(Duration.ofSeconds(5));
        assertEquals(2, calls.get());
        assertEquals(RefreshResult.EXHAUSTED, result(latest));
        time.advance(Duration.ofSeconds(10));

        assertEquals(2, calls.get());
        coordinator.close();
    }

    @Test
    void revisionChangeClearsOnlyResolvedUnchangedState() {
        ManualTime time = new ManualTime();
        SequenceResolver resolver = new SequenceResolver(time);
        ConnectionSnapshot connection = connection(61, 1L);
        resolver.add(resolved(connection, 'e'));
        resolver.add(OfficialProfileResolver.Resolution.transientFailure());
        RecordingPublisher publisher = new RecordingPublisher();
        publisher.outcomes.add(PublicationOutcome.UNCHANGED);
        ServerRefreshPolicy policy = policy(
                16, 1, 10_000.0d, 20,
                List.of(Duration.ofMillis(500), Duration.ofSeconds(2)),
                Duration.ofMinutes(5), Duration.ofSeconds(30),
                Duration.ofMillis(50), 64, 0);
        ServerAppearanceRefreshCoordinator coordinator = coordinator(
                time, resolver, publisher, policy);

        RefreshSubmission first = coordinator.request(connection);
        time.advance(Duration.ofMillis(550));
        RefreshSubmission latest = coordinator.request(connection);
        assertEquals(RefreshResult.SUPERSEDED, result(first));
        assertEquals(1, time.pendingTasks());

        time.advance(Duration.ofMillis(4_950));

        assertEquals(2, resolver.calls.get());
        assertEquals(RefreshResult.EXHAUSTED, result(latest));
        assertEquals(1, publisher.actorCount.get());
        coordinator.close();
    }

    @Test
    void lookupDeadlineReleasesTheSlotAndIgnoresALateCompletion() {
        ManualTime time = new ManualTime();
        CompletableFuture<OfficialProfileResolver.Resolution> held = new CompletableFuture<>();
        RecordingPublisher publisher = new RecordingPublisher();
        ServerRefreshPolicy policy = policy(
                16, 1, 10.0d, 20, List.of(Duration.ofMillis(500)),
                Duration.ofMinutes(5), Duration.ofSeconds(2), Duration.ofMillis(50), 64, 0);
        ConnectionSnapshot connection = connection(23, 1L);
        ServerAppearanceRefreshCoordinator coordinator = coordinator(
                time, ignored -> held, publisher, policy);

        RefreshSubmission submission = coordinator.request(connection);
        time.advance(Duration.ofMillis(500));
        assertEquals(1, coordinator.health().lookupsInFlight());
        time.advance(Duration.ofSeconds(2));

        assertEquals(RefreshResult.EXPIRED, result(submission));
        assertEquals(0, coordinator.health().lookupsInFlight());
        assertTrue(held.isCancelled());
        held.complete(resolved(connection, 'c'));
        time.advance(Duration.ofSeconds(1));
        assertEquals(0, publisher.actorCount.get());
        coordinator.close();
    }

    @Test
    void closeCancelsAnActiveLookupAndReleasesItsSlot() {
        ManualTime time = new ManualTime();
        CompletableFuture<OfficialProfileResolver.Resolution> held = new CompletableFuture<>();
        ServerAppearanceRefreshCoordinator coordinator = coordinator(
                time, ignored -> held, new RecordingPublisher(), defaultPolicy());
        RefreshSubmission submission = coordinator.request(connection(28, 1L));
        time.advance(Duration.ofMillis(500));
        assertEquals(1, coordinator.health().lookupsInFlight());

        coordinator.close();

        assertTrue(held.isCancelled());
        assertEquals(0, coordinator.health().lookupsInFlight());
        assertEquals(RefreshResult.CLOSED, result(submission));
    }

    @Test
    void lateResolverCallbackCannotBeatADelayedDeadlineScheduler() {
        ManualTime time = new ManualTime();
        CompletableFuture<OfficialProfileResolver.Resolution> held = new CompletableFuture<>();
        RecordingPublisher publisher = new RecordingPublisher();
        ConnectionSnapshot connection = connection(26, 1L);
        ServerAppearanceRefreshCoordinator coordinator = coordinator(
                time, ignored -> held, publisher, defaultPolicy());

        RefreshSubmission submission = coordinator.request(connection);
        time.advance(Duration.ofMillis(500));
        time.elapseWithoutRunning(Duration.ofSeconds(31));
        held.complete(resolved(connection, 'f'));

        assertEquals(RefreshResult.EXPIRED, result(submission));
        assertEquals(0, publisher.actorCount.get());
        assertEquals(0, coordinator.health().lookupsInFlight());
        time.advance(Duration.ZERO);
        assertEquals(0, publisher.actorCount.get());
        coordinator.close();
    }

    @Test
    void publicationDeadlineReleasesTheSingleSlotAndIgnoresALateCompletion() {
        ManualTime time = new ManualTime();
        ConnectionSnapshot connection = connection(24, 1L);
        CompletableFuture<BatchPublicationResult> held = new CompletableFuture<>();
        BatchAppearancePublisher publisher = new BatchAppearancePublisher() {
            @Override
            public CompletionStage<BatchPublicationResult> publishBatch(
                    List<PublicationRequest> ignored) {
                return held;
            }

            @Override
            public void supersede(ConnectionKey ignored) {

            }
        };
        ServerRefreshPolicy policy = policy(
                16, 1, 10.0d, 20, List.of(Duration.ofMillis(500)),
                Duration.ofMinutes(5), Duration.ofSeconds(1), Duration.ofMillis(50), 64, 0);
        ServerAppearanceRefreshCoordinator coordinator = coordinator(
                time,
                ignored -> OfficialProfileResolver.completed(resolved(connection, 'd')),
                publisher,
                policy);

        RefreshSubmission submission = coordinator.request(connection);
        time.advance(Duration.ofSeconds(2));

        assertEquals(RefreshResult.EXPIRED, result(submission));
        assertEquals(0, coordinator.health().publicationsInFlight());
        assertTrue(held.isCancelled());
        held.complete(BatchPublicationResult.all(
                List.of(new PublicationRequest(
                        connection.key(),
                        resolved(connection, 'd').profile().orElseThrow())),
                PublicationOutcome.UPDATED));
        time.advance(Duration.ofSeconds(1));
        assertEquals(RefreshResult.EXPIRED, result(submission));
        coordinator.close();
    }

    @Test
    void closeCancelsAnActivePublicationAndCompletesItsCycle() {
        ManualTime time = new ManualTime();
        ConnectionSnapshot connection = connection(29, 1L);
        CompletableFuture<BatchPublicationResult> held = new CompletableFuture<>();
        BatchAppearancePublisher publisher = new BatchAppearancePublisher() {
            @Override
            public CompletionStage<BatchPublicationResult> publishBatch(
                    List<PublicationRequest> ignored) {
                return held;
            }

            @Override
            public void supersede(ConnectionKey ignored) {

            }
        };
        ServerAppearanceRefreshCoordinator coordinator = coordinator(
                time,
                ignored -> OfficialProfileResolver.completed(resolved(connection, 'e')),
                publisher,
                defaultPolicy());
        RefreshSubmission submission = coordinator.request(connection);
        time.advance(Duration.ofMillis(550));
        assertEquals(1, coordinator.health().publicationsInFlight());

        coordinator.close();

        assertTrue(held.isCancelled());
        assertEquals(0, coordinator.health().publicationsInFlight());
        assertEquals(RefreshResult.CLOSED, result(submission));
    }

    @Test
    void retryWaitingGaugeExcludesInitialDelayAndCooldown() {
        ManualTime time = new ManualTime();
        SequenceResolver resolver = new SequenceResolver(time);
        resolver.add(OfficialProfileResolver.Resolution.transientFailure());
        resolver.add(connection -> resolved(connection, 'e'));
        ServerAppearanceRefreshCoordinator coordinator = coordinator(
                time, resolver, new RecordingPublisher(), defaultPolicy());

        coordinator.request(connection(25, 1L));
        assertEquals(0, coordinator.health().retryWaiting());
        time.advance(Duration.ofMillis(500));
        assertEquals(1, coordinator.health().retryWaiting());
        coordinator.close();
    }

    @Test
    void publisherOwnsPartialReconciliationAndReturnsOneTerminalOutcome() {
        ManualTime time = new ManualTime();
        SequenceResolver resolver = new SequenceResolver(time);
        resolver.add(connection -> resolved(connection, 'f'));
        RecordingPublisher publisher = new RecordingPublisher();
        publisher.outcomes.add(PublicationOutcome.FAILED);
        ServerAppearanceRefreshCoordinator coordinator = coordinator(
                time, resolver, publisher, defaultPolicy());

        RefreshSubmission submission = coordinator.request(connection(30, 1L));
        time.advance(Duration.ofSeconds(1));

        assertEquals(RefreshResult.FAILED, result(submission));
        assertEquals(1, resolver.calls.get());
        assertEquals(0L, coordinator.health().reconciliationAttempts());
        coordinator.close();
    }

    @Test
    void boundedCapacityIsTruthfulAndCloseCompletesAdmittedWork() {
        ManualTime time = new ManualTime();
        OfficialProfileResolver resolver = ignored -> new CompletableFuture<>();
        ServerRefreshPolicy policy = policy(
                2, 2, 10.0d, 20, List.of(Duration.ofMillis(500)),
                Duration.ofMinutes(5), Duration.ofSeconds(30), Duration.ofMillis(50), 64, 0);
        ServerAppearanceRefreshCoordinator coordinator = coordinator(
                time, resolver, new RecordingPublisher(), policy);
        RefreshSubmission first = coordinator.request(connection(40, 1L));
        RefreshSubmission second = coordinator.request(connection(41, 1L));
        RefreshSubmission rejected = coordinator.request(connection(42, 1L));

        assertEquals(Admission.OVERLOADED, rejected.admission());
        assertEquals(RefreshResult.OVERLOADED, result(rejected));
        coordinator.close();
        assertEquals(RefreshResult.CLOSED, result(first));
        assertEquals(RefreshResult.CLOSED, result(second));
        assertEquals(Admission.CLOSED, coordinator.request(connection(43, 1L)).admission());
    }

    @Test
    void mismatchedOfficialIdentityIsRejectedBeforePublication() {
        ManualTime time = new ManualTime();
        ConnectionSnapshot expected = connection(50, 1L);
        ConnectionSnapshot other = connection(51, 1L);
        OfficialProfileResolver resolver = ignored -> OfficialProfileResolver.completed(
                resolved(other, 'a'));
        RecordingPublisher publisher = new RecordingPublisher();
        ServerAppearanceRefreshCoordinator coordinator = coordinator(
                time, resolver, publisher, defaultPolicy());

        RefreshSubmission submission = coordinator.request(expected);
        time.advance(Duration.ofSeconds(1));

        assertEquals(RefreshResult.REJECTED, result(submission));
        assertEquals(0, publisher.actorCount.get());
        coordinator.close();
    }

    private static ServerAppearanceRefreshCoordinator coordinator(
            ManualTime time,
            OfficialProfileResolver resolver,
            BatchAppearancePublisher publisher,
            ServerRefreshPolicy policy) {
        return new ServerAppearanceRefreshCoordinator(
                resolver,
                publisher,
                policy,
                time::now,
                time,
                ServerAppearanceRefreshCoordinator.Jitter.none());
    }

    private static ServerRefreshPolicy defaultPolicy() {
        return ServerRefreshPolicy.defaults(false, 100);
    }

    private static ServerRefreshPolicy policy(
            int capacity,
            int concurrency,
            double rate,
            int burst,
            List<Duration> attempts,
            Duration queueAge,
            Duration deadline,
            Duration batchWindow,
            int batchActors,
            int reconciliationAttempts) {
        return new ServerRefreshPolicy(
                false,
                capacity,
                concurrency,
                rate,
                burst,
                attempts,
                queueAge,
                deadline,
                Duration.ofSeconds(5),
                batchWindow,
                batchActors,
                reconciliationAttempts);
    }

    private static ConnectionSnapshot connection(int seed, long generation) {
        UUID profileId = UUID.nameUUIDFromBytes(
                ("server-player-" + seed).getBytes(StandardCharsets.UTF_8));
        return new ConnectionSnapshot(
                new ConnectionKey(profileId, generation),
                "Player" + seed,
                IdentityAssurance.ONLINE);
    }

    private static OfficialProfileResolver.Resolution resolved(
            ConnectionSnapshot connection,
            char textureCharacter) {
        TextureAppearance appearance = TextureAppearance.verified(
                Optional.of(String.valueOf(textureCharacter).repeat(64)),
                Optional.of(TextureAppearance.SkinModel.CLASSIC),
                Optional.empty(),
                Optional.empty());
        return OfficialProfileResolver.Resolution.resolved(new VerifiedOfficialProfile(
                connection.identity(),
                appearance,
                Optional.of(new SignedTexturesProperty("value-secret", "signature-secret"))));
    }

    private static RefreshResult result(RefreshSubmission submission) {
        return submission.completion().toCompletableFuture().join();
    }

    private static final class SequenceResolver implements OfficialProfileResolver {
        private final ManualTime time;
        private final Queue<Function<ConnectionSnapshot, OfficialProfileResolver.Resolution>>
                results = new ArrayDeque<>();
        private final AtomicInteger calls = new AtomicInteger();
        private final List<Long> callTimes = new ArrayList<>();
        private Function<ConnectionSnapshot, OfficialProfileResolver.Resolution> fallback;

        private SequenceResolver(ManualTime time) {
            this.time = time;
        }

        private void add(OfficialProfileResolver.Resolution result) {
            add(ignored -> result);
        }

        private void add(Function<ConnectionSnapshot, OfficialProfileResolver.Resolution> result) {
            results.add(result);
        }

        @Override
        public CompletionStage<OfficialProfileResolver.Resolution> resolve(
                ConnectionSnapshot connection) {
            calls.incrementAndGet();
            callTimes.add(time.now());
            Function<ConnectionSnapshot, OfficialProfileResolver.Resolution> result =
                    results.poll();
            if (result == null) {
                result = fallback;
            }
            if (result == null) {
                return OfficialProfileResolver.completed(
                        OfficialProfileResolver.Resolution.transientFailure());
            }
            return OfficialProfileResolver.completed(result.apply(connection));
        }
    }

    private static final class RecordingPublisher implements BatchAppearancePublisher {
        private final List<Integer> batchSizes = new ArrayList<>();
        private final List<ConnectionKey> superseded = new ArrayList<>();
        private final Queue<PublicationOutcome> outcomes = new ArrayDeque<>();
        private final AtomicInteger actorCount = new AtomicInteger();
        private PublicationMetrics metrics = PublicationMetrics.ZERO;

        @Override
        public CompletionStage<BatchPublicationResult> publishBatch(
                List<PublicationRequest> requests) {
            batchSizes.add(requests.size());
            actorCount.addAndGet(requests.size());
            Map<ConnectionKey, PublicationOutcome> result = new HashMap<>();
            for (PublicationRequest request : requests) {
                PublicationOutcome outcome = outcomes.poll();
                result.put(
                        request.connection(),
                        outcome == null ? PublicationOutcome.UPDATED : outcome);
            }
            return CompletableFuture.completedFuture(BatchPublicationResult.of(result, metrics));
        }

        @Override
        public void supersede(ConnectionKey connection) {
            superseded.add(connection);
        }
    }

    private static final class ManualTime
            implements ServerAppearanceRefreshCoordinator.Scheduler {
        private final PriorityQueue<ScheduledAction> actions = new PriorityQueue<>(
                Comparator.comparingLong(ScheduledAction::at)
                        .thenComparingLong(ScheduledAction::sequence));
        private long now;
        private long nextSequence;

        private long now() {
            return now;
        }

        private long pendingTasks() {
            return actions.stream().filter(action -> !action.cancelled).count();
        }

        @Override
        public ServerAppearanceRefreshCoordinator.Cancellable schedule(
                Duration delay,
                Runnable action) {
            ScheduledAction scheduled = new ScheduledAction(
                    saturatedAdd(now, delay.toNanos()), nextSequence++, action);
            actions.add(scheduled);
            return () -> scheduled.cancelled = true;
        }

        private void advance(Duration duration) {
            long target = saturatedAdd(now, duration.toNanos());
            while (!actions.isEmpty() && actions.peek().at <= target) {
                ScheduledAction next = actions.remove();
                now = next.at;
                if (!next.cancelled) {
                    next.action.run();
                }
            }
            now = target;
        }

        private void elapseWithoutRunning(Duration duration) {
            now = saturatedAdd(now, duration.toNanos());
        }

        private static long saturatedAdd(long left, long right) {
            return left > Long.MAX_VALUE - right ? Long.MAX_VALUE : left + right;
        }
    }

    private static final class ScheduledAction {
        private final long at;
        private final long sequence;
        private final Runnable action;
        private boolean cancelled;

        private ScheduledAction(long at, long sequence, Runnable action) {
            this.at = at;
            this.sequence = sequence;
            this.action = action;
        }

        private long at() {
            return at;
        }

        private long sequence() {
            return sequence;
        }
    }


    private static final class UncancellableFuture<T> extends CompletableFuture<T> {
        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            return false;
        }
    }
}

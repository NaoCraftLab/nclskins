package com.naocraftlab.skins.server.vanilla;

import com.naocraftlab.skins.server.BatchPublicationResult;
import com.naocraftlab.skins.server.ConnectionKey;
import com.naocraftlab.skins.server.OfficialTextureSignatureVerifier;
import com.naocraftlab.skins.server.PublicationOutcome;
import com.naocraftlab.skins.server.PublicationRequest;
import com.naocraftlab.skins.server.ServerPlayerIdentity;
import com.naocraftlab.skins.server.SignedTexturesProperty;
import com.naocraftlab.skins.server.TextureAppearance;
import com.naocraftlab.skins.server.VerifiedOfficialProfile;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class VanillaBatchAppearancePublisherTest {
    @Test
    void cancellationDuringSemanticHandoffWakesTheCancelledHeadAndStartsNextJob()
            throws Exception {
        FakePlatform platform = new FakePlatform();
        ConnectionKey cancelledActor = platform.connect("semantic-cancelled");
        ConnectionKey nextActor = platform.connect("semantic-next");
        platform.current.put(
                cancelledActor,
                LiveProfileTextures.signed(
                        new SignedTexturesProperty("live-value", "live-signature")));
        platform.verifiedCurrent = appearance('a');
        AtomicBoolean cancelled = new AtomicBoolean();
        VanillaBatchAppearancePublisher publisher = publisher(
                platform,
                64,
                5_000_000L,
                2,
                completion -> {
                    if (cancelled.compareAndSet(false, true)) {
                        completion.cancel(true);
                    }
                });

        CompletionStage<BatchPublicationResult> cancelledStage = publisher.publishBatch(List.of(
                request(
                        cancelledActor,
                        "semantic-cancelled",
                        appearance('b'),
                        Optional.of(new SignedTexturesProperty(
                                "target-value", "target-signature")))));
        platform.runImmediate();
        assertTrue(cancelledStage.toCompletableFuture().isCancelled());

        BatchPublicationResult next = platform.await(publisher.publishBatch(List.of(
                defaultRequest(nextActor, "semantic-next"))));

        assertEquals(PublicationOutcome.UPDATED, next.outcome(nextActor).orElseThrow());
        publisher.close();
    }

    @Test
    void nextTickRejectionUsesBoundedRecoveryAndAllowsLaterPublication() throws Exception {
        FakePlatform platform = new FakePlatform();
        ConnectionKey failedActor = platform.connect("recovered-actor");
        ConnectionKey observer = platform.connect("recovered-observer");
        ConnectionKey nextActor = platform.connect("recovered-next");
        platform.observers.put(failedActor, List.of(observer));
        platform.retrackFailuresRemaining = 1;
        platform.onUntrack = () -> platform.nextTickRejectionsRemaining = 1;
        VanillaBatchAppearancePublisher publisher = publisher(platform, 64, 1L, 2);

        BatchPublicationResult failed = platform.await(publisher.publishBatch(List.of(
                defaultRequest(failedActor, "recovered-actor"))));

        assertEquals(PublicationOutcome.FAILED, failed.outcome(failedActor).orElseThrow());
        assertEquals(1, platform.events.stream().filter("retrack-failed"::equals).count());
        assertEquals(1, platform.events.stream().filter("retrack"::equals).count());
        platform.onUntrack = () -> {};
        BatchPublicationResult next = platform.await(publisher.publishBatch(List.of(
                defaultRequest(nextActor, "recovered-next"))));
        assertEquals(PublicationOutcome.UPDATED, next.outcome(nextActor).orElseThrow());
        publisher.close();
    }

    @Test
    void persistentRetrackFailureStillSettlesAndReleasesQueueOwnership() throws Exception {
        FakePlatform platform = new FakePlatform();
        ConnectionKey failedActor = platform.connect("persistent-actor");
        ConnectionKey observer = platform.connect("persistent-observer");
        ConnectionKey nextActor = platform.connect("persistent-next");
        platform.observers.put(failedActor, List.of(observer));
        platform.retrackFailuresRemaining = 100;
        platform.onUntrack = () -> platform.nextTickRejectionsRemaining = 1;
        VanillaBatchAppearancePublisher publisher = publisher(platform, 64, 1L, 2);

        BatchPublicationResult failed = platform.await(publisher.publishBatch(List.of(
                defaultRequest(failedActor, "persistent-actor"))));

        assertEquals(PublicationOutcome.FAILED, failed.outcome(failedActor).orElseThrow());
        assertEquals(4, platform.events.stream().filter("retrack-failed"::equals).count());
        platform.onUntrack = () -> {};
        BatchPublicationResult next = platform.await(publisher.publishBatch(List.of(
                defaultRequest(nextActor, "persistent-next"))));
        assertEquals(PublicationOutcome.UPDATED, next.outcome(nextActor).orElseThrow());
        publisher.close();
    }
    @Test
    void continuesAcrossTicksAndNeverExceedsDeliveryBudget() throws Exception {
        FakePlatform platform = new FakePlatform();
        ConnectionKey actor = platform.connect("actor");
        for (int index = 0; index < 5; index++) {
            platform.connect("observer-" + index);
        }
        platform.operationCostNanos = 2L;
        VanillaBatchAppearancePublisher publisher = publisher(platform, 2, 5L, 2);

        CompletionStage<BatchPublicationResult> stage = publisher.publishBatch(List.of(
                defaultRequest(actor, "actor")));
        platform.runImmediate();

        assertFalse(stage.toCompletableFuture().isDone());
        BatchPublicationResult result = platform.await(stage);
        assertEquals(PublicationOutcome.UPDATED, result.outcome(actor).orElseThrow());
        assertTrue(platform.tick > 0);
        assertTrue(platform.deliveriesByTick.values().stream().allMatch(count -> count <= 2));
        assertEquals(5L, result.metrics().recipients());
        assertEquals(5L, result.metrics().profileDeliveries());
        publisher.close();
    }

    @Test
    void reportsTotalAndMaximumPlatformThreadTimeSeparatelyAcrossTicks() throws Exception {
        FakePlatform platform = new FakePlatform();
        ConnectionKey actor = platform.connect("actor");
        platform.connect("observer");
        platform.operationCostNanos = 500_000L;
        VanillaBatchAppearancePublisher publisher = publisher(platform, 64, 5_000_000L, 2);

        BatchPublicationResult result = platform.await(publisher.publishBatch(List.of(
                defaultRequest(actor, "actor"))));

        assertTrue(result.metrics().platformThreadNanos() > 5_000_000L);
        assertTrue(result.metrics().platformThreadMaxTickNanos() <= 5_000_000L);
        assertTrue(result.metrics().platformThreadMaxTickNanos()
                < result.metrics().platformThreadNanos());
        publisher.close();
    }

    @Test
    void retriesFailedRetrackBeforeCompletingAndRestoresExactPair() throws Exception {
        FakePlatform platform = new FakePlatform();
        ConnectionKey actor = platform.connect("actor");
        ConnectionKey observer = platform.connect("observer");
        platform.observers.put(actor, List.of(observer));
        platform.retrackFailuresRemaining = 1;
        VanillaBatchAppearancePublisher publisher = publisher(platform, 64, 5_000_000L, 2);

        CompletionStage<BatchPublicationResult> stage = publisher.publishBatch(List.of(
                defaultRequest(actor, "actor")));
        platform.runImmediate();

        assertFalse(stage.toCompletableFuture().isDone());
        assertEquals(1, platform.events.stream().filter("untrack"::equals).count());
        assertEquals(1, platform.events.stream().filter("retrack-failed"::equals).count());
        platform.runNextTick();
        BatchPublicationResult result = platform.await(stage);
        assertEquals(PublicationOutcome.UPDATED, result.outcome(actor).orElseThrow());
        assertEquals(1, platform.events.stream().filter("retrack"::equals).count());
        assertTrue(platform.events.indexOf("install") > platform.events.indexOf("untrack"));
        assertTrue(platform.events.indexOf("retrack") > platform.events.indexOf("install"));
        publisher.close();
    }

    @Test
    void cancellationAndSchedulerRejectionStillRetrackAlreadyRemovedPairs() {
        FakePlatform platform = new FakePlatform();
        ConnectionKey actor = platform.connect("actor");
        ConnectionKey observer = platform.connect("observer");
        platform.observers.put(actor, List.of(observer));
        VanillaBatchAppearancePublisher publisher = publisher(platform, 64, 5_000_000L, 2);
        CompletionStage<BatchPublicationResult> stage = publisher.publishBatch(List.of(
                defaultRequest(actor, "actor")));
        platform.onUntrack = () -> {
            platform.rejectExecute = true;
            stage.toCompletableFuture().cancel(true);
        };

        platform.runImmediate();

        assertTrue(stage.toCompletableFuture().isCancelled());
        assertTrue(platform.events.contains("untrack"));
        assertTrue(platform.events.contains("retrack"));
        assertFalse(platform.events.contains("install"));
        publisher.close();
    }

    @Test
    void cancelledHeadRetainsRetrackBarrierUntilRecoveryBeforeNextInstall() throws Exception {
        FakePlatform platform = new FakePlatform();
        ConnectionKey cancelledActor = platform.connect("cancelled-actor");
        ConnectionKey observer = platform.connect("observer");
        ConnectionKey nextActor = platform.connect("next-actor");
        platform.observers.put(cancelledActor, List.of(observer));
        platform.retrackFailuresRemaining = 5;
        VanillaBatchAppearancePublisher publisher = publisher(platform, 64, 5_000_000L, 2);
        CompletionStage<BatchPublicationResult> cancelledStage = publisher.publishBatch(List.of(
                defaultRequest(cancelledActor, "cancelled-actor")));
        AtomicReference<CompletionStage<BatchPublicationResult>> nextStage =
                new AtomicReference<>();
        platform.onUntrack = () -> {
            platform.onUntrack = () -> {};
            cancelledStage.toCompletableFuture().cancel(true);
            nextStage.set(publisher.publishBatch(List.of(
                    defaultRequest(nextActor, "next-actor"))));
        };

        platform.runImmediate();
        assertTrue(cancelledStage.toCompletableFuture().isCancelled());
        assertEquals(1, platform.events.stream().filter("retrack-failed"::equals).count());
        assertFalse(platform.events.contains("install"));

        for (int tick = 0; tick < 4; tick++) {
            platform.runNextTick();
            assertFalse(platform.events.contains("install"));
            assertFalse(nextStage.get().toCompletableFuture().isDone());
        }
        platform.runNextTick();

        BatchPublicationResult result = platform.await(nextStage.get());
        assertEquals(PublicationOutcome.UPDATED, result.outcome(nextActor).orElseThrow());
        assertEquals(5, platform.events.stream().filter("retrack-failed"::equals).count());
        assertEquals(1, platform.events.stream().filter("retrack"::equals).count());
        assertEquals(1, platform.events.stream().filter("install"::equals).count());
        assertTrue(platform.events.indexOf("install") > platform.events.indexOf("retrack"));
        publisher.close();
    }

    @Test
    void closeOnPlatformThreadRetracksAnInterruptedPublication() {
        FakePlatform platform = new FakePlatform();
        ConnectionKey actor = platform.connect("actor");
        ConnectionKey observer = platform.connect("observer");
        platform.observers.put(actor, List.of(observer));
        platform.operationCostNanos = 1L;
        VanillaBatchAppearancePublisher publisher = publisher(platform, 64, 1L, 2);
        CompletionStage<BatchPublicationResult> stage = publisher.publishBatch(List.of(
                defaultRequest(actor, "actor")));

        platform.runImmediate();
        while (!platform.events.contains("untrack")) {
            platform.runNextTick();
        }
        assertFalse(stage.toCompletableFuture().isDone());

        publisher.close();

        assertTrue(platform.events.contains("retrack"));
        assertEquals(
                PublicationOutcome.FAILED,
                stage.toCompletableFuture().join().outcome(actor).orElseThrow());
    }

    @Test
    void comparesRotatedSignedPropertySemanticallyOnWorker() throws Exception {
        FakePlatform platform = new FakePlatform();
        ConnectionKey actor = platform.connect("actor");
        TextureAppearance appearance = appearance('a');
        SignedTexturesProperty live = new SignedTexturesProperty("live-value", "live-signature");
        SignedTexturesProperty fetched =
                new SignedTexturesProperty("fetched-value", "fetched-signature");
        platform.current.put(actor, LiveProfileTextures.signed(live));
        platform.verifiedCurrent = appearance;
        PublicationRequest request = request(actor, "actor", appearance, Optional.of(fetched));
        VanillaBatchAppearancePublisher publisher = publisher(platform, 64, 5_000_000L, 2);

        BatchPublicationResult result = platform.await(publisher.publishBatch(List.of(request)));

        assertEquals(PublicationOutcome.UNCHANGED, result.outcome(actor).orElseThrow());
        assertFalse(platform.events.contains("install"));
        publisher.close();
    }

    @Test
    void semanticCompletionResumesOnFollowingLogicalTickWithoutFreshSameTickBudget()
            throws Exception {
        FakePlatform platform = new FakePlatform();
        ConnectionKey actor = platform.connect("actor");
        platform.current.put(
                actor,
                LiveProfileTextures.signed(
                        new SignedTexturesProperty("live-value", "live-signature")));
        platform.verifiedCurrent = appearance('a');
        PublicationRequest target = request(
                actor,
                "actor",
                appearance('b'),
                Optional.of(new SignedTexturesProperty("target-value", "target-signature")));
        VanillaBatchAppearancePublisher publisher = publisher(platform, 64, 5_000_000L, 2);

        BatchPublicationResult result = platform.await(publisher.publishBatch(List.of(target)));

        assertEquals(PublicationOutcome.UPDATED, result.outcome(actor).orElseThrow());
        assertEquals(List.of(1), platform.snapshotTicks);
        assertEquals(List.of(1), platform.installTicks);
        publisher.close();
    }

    @Test
    void retriesOnlyFailedRecipientChunkAndReportsAggregateMetrics() throws Exception {
        FakePlatform platform = new FakePlatform();
        ConnectionKey actor = platform.connect("actor");
        ConnectionKey observer = platform.connect("observer");
        platform.transportFailures.put(observer, 1);
        VanillaBatchAppearancePublisher publisher = publisher(platform, 64, 5_000_000L, 2);

        BatchPublicationResult result = platform.await(publisher.publishBatch(List.of(
                defaultRequest(actor, "actor"))));

        assertEquals(PublicationOutcome.UPDATED, result.outcome(actor).orElseThrow());
        assertEquals(1L, result.metrics().reconciliationAttempts());
        assertEquals(1L, result.metrics().reconciliationDeliveries());
        assertEquals(2, platform.transportAttempts.getOrDefault(observer, 0));
        assertEquals(Set.of(0, 1), platform.deliveriesByTick.keySet());
        publisher.close();
    }

    @Test
    void reconciliationAttemptsAreBoundedToOnePerFollowingTick() throws Exception {
        FakePlatform platform = new FakePlatform();
        ConnectionKey actor = platform.connect("actor");
        ConnectionKey observer = platform.connect("observer");
        platform.transportFailures.put(observer, 2);
        VanillaBatchAppearancePublisher publisher = publisher(platform, 64, 5_000_000L, 2);

        BatchPublicationResult result = platform.await(publisher.publishBatch(List.of(
                defaultRequest(actor, "actor"))));

        assertEquals(PublicationOutcome.UPDATED, result.outcome(actor).orElseThrow());
        assertEquals(2L, result.metrics().reconciliationAttempts());
        assertEquals(3, platform.transportAttempts.getOrDefault(observer, 0));
        assertEquals(Set.of(0, 1, 2), platform.deliveriesByTick.keySet());
        publisher.close();
    }

    @Test
    void successfulWatcherRetryRefreshesWorldPairAfterInitializeFailure() throws Exception {
        FakePlatform platform = new FakePlatform();
        ConnectionKey actor = platform.connect("actor");
        ConnectionKey watcher = platform.connect("watcher");
        platform.observers.put(actor, List.of(watcher));
        platform.initializeFailures.put(watcher, 1);
        VanillaBatchAppearancePublisher publisher = publisher(platform, 64, 5_000_000L, 2);

        BatchPublicationResult result = platform.await(publisher.publishBatch(List.of(
                defaultRequest(actor, "actor"))));

        assertEquals(PublicationOutcome.UPDATED, result.outcome(actor).orElseThrow());
        assertEquals(1L, result.metrics().reconciliationAttempts());
        assertEquals(2, platform.transportAttempts.getOrDefault(watcher, 0));
        assertEquals(2, platform.initializeAttempts.getOrDefault(watcher, 0));
        assertEquals(2, platform.events.stream().filter("untrack"::equals).count());
        assertEquals(2, platform.events.stream().filter("retrack"::equals).count());
        int successfulInitialize = platform.events.lastIndexOf("initialize:watcher");
        int refreshUntrack = platform.events.lastIndexOf("untrack");
        int refreshRetrack = platform.events.lastIndexOf("retrack");
        assertTrue(successfulInitialize >= 0);
        assertTrue(refreshUntrack > successfulInitialize);
        assertTrue(refreshRetrack > refreshUntrack);
        publisher.close();
    }

    @Test
    void sixtyFourActorBatchKeepsOneRecipientFanoutAcrossOneThousandPlayers()
            throws Exception {
        FakePlatform platform = new FakePlatform();
        List<ConnectionKey> actors = new ArrayList<>();
        List<PublicationRequest> requests = new ArrayList<>();
        for (int index = 0; index < 64; index++) {
            String name = "actor-" + index;
            ConnectionKey actor = platform.connect(name);
            actors.add(actor);
            requests.add(defaultRequest(actor, name));
        }
        List<ConnectionKey> observers = new ArrayList<>();
        for (int index = 0; index < 1_000; index++) {
            observers.add(platform.connect("observer-" + index));
        }
        VanillaBatchAppearancePublisher publisher = publisher(platform, 4_096, 5_000_000L, 2);

        BatchPublicationResult result = platform.await(publisher.publishBatch(requests));

        assertEquals(64, result.size());
        assertTrue(actors.stream().allMatch(actor ->
                result.outcome(actor).orElseThrow() == PublicationOutcome.UPDATED));
        assertEquals(1_064, platform.transportAttempts.size());
        assertTrue(platform.transportAttempts.values().stream().allMatch(count -> count == 1));
        assertEquals(1_064, platform.initializeAttempts.size());
        assertTrue(platform.initializeAttempts.values().stream().allMatch(count -> count == 1));
        for (ConnectionKey actor : actors) {
            List<ConnectionKey> delivered = platform.removePayloads.get(actor).get(0);
            assertEquals(63, delivered.size());
            assertFalse(delivered.contains(actor));
        }
        assertEquals(64, platform.removePayloads.get(observers.get(0)).get(0).size());
        assertEquals(1_064L, result.metrics().recipients());
        assertEquals(68_032L, result.metrics().profileDeliveries());
        publisher.close();
    }

    @Test
    void watcherChannelRetracksBeforeLargeTabOnlyTail() throws Exception {
        FakePlatform platform = new FakePlatform();
        ConnectionKey actor = platform.connect("actor");
        ConnectionKey watcher = platform.connect("watcher");
        platform.connect("distant");
        platform.observers.put(actor, List.of(watcher));
        VanillaBatchAppearancePublisher publisher = publisher(platform, 64, 5_000_000L, 2);

        BatchPublicationResult result = platform.await(publisher.publishBatch(List.of(
                defaultRequest(actor, "actor"))));

        assertEquals(PublicationOutcome.UPDATED, result.outcome(actor).orElseThrow());
        assertOrdered(
                platform.events,
                "untrack",
                "install",
                "remove:watcher",
                "initialize:watcher",
                "retrack",
                "remove:distant",
                "initialize:distant");
        publisher.close();
    }

    @Test
    void newerQueuedIntentSupersedesOlderActorBeforePlatformMutation() throws Exception {
        FakePlatform platform = new FakePlatform();
        ConnectionKey actor = platform.connect("actor");
        VanillaBatchAppearancePublisher publisher = publisher(platform, 64, 5_000_000L, 2);
        PublicationRequest older = request(
                actor,
                "actor",
                appearance('b'),
                Optional.of(new SignedTexturesProperty("older-value", "older-signature")));
        PublicationRequest latest = defaultRequest(actor, "actor");

        CompletionStage<BatchPublicationResult> oldStage = publisher.publishBatch(List.of(older));
        CompletionStage<BatchPublicationResult> latestStage = publisher.publishBatch(List.of(latest));
        BatchPublicationResult oldResult = platform.await(oldStage);
        BatchPublicationResult latestResult = platform.await(latestStage);

        assertEquals(PublicationOutcome.STALE, oldResult.outcome(actor).orElseThrow());
        assertEquals(PublicationOutcome.UPDATED, latestResult.outcome(actor).orElseThrow());
        assertEquals(1, platform.events.stream().filter("install"::equals).count());
        assertEquals(
                LiveProfileTextures.Status.ACCOUNT_DEFAULT,
                platform.current.get(actor).status());
        publisher.close();
    }

    @Test
    void explicitSupersedeFencesAdmittedIntentAndDoesNotPoisonFutureIntent() throws Exception {
        FakePlatform platform = new FakePlatform();
        ConnectionKey actor = platform.connect("actor");
        VanillaBatchAppearancePublisher publisher = publisher(platform, 64, 5_000_000L, 2);
        PublicationRequest older = request(
                actor,
                "actor",
                appearance('b'),
                Optional.of(new SignedTexturesProperty("older-value", "older-signature")));

        CompletionStage<BatchPublicationResult> oldStage = publisher.publishBatch(List.of(older));
        publisher.supersede(actor);
        BatchPublicationResult oldResult = platform.await(oldStage);
        BatchPublicationResult futureResult = platform.await(publisher.publishBatch(List.of(
                defaultRequest(actor, "actor"))));

        assertEquals(PublicationOutcome.STALE, oldResult.outcome(actor).orElseThrow());
        assertEquals(PublicationOutcome.UPDATED, futureResult.outcome(actor).orElseThrow());
        assertEquals(1, platform.events.stream().filter("install"::equals).count());
        publisher.close();
    }

    @Test
    void disconnectDuringUntrackDoesNotPoisonReconnectOrLaterRefreshes() throws Exception {
        FakePlatform platform = new FakePlatform();
        UUID profileId = UUID.randomUUID();
        ConnectionKey oldActor = platform.connect(profileId, "actor");
        ConnectionKey observer = platform.connect("observer");
        platform.observers.put(oldActor, List.of(observer));
        platform.operationCostNanos = 1L;
        VanillaBatchAppearancePublisher publisher = publisher(platform, 64, 1L, 2);
        CompletionStage<BatchPublicationResult> oldStage = publisher.publishBatch(List.of(
                defaultRequest(oldActor, "actor")));

        platform.runImmediate();
        while (!platform.events.contains("untrack")) {
            platform.runNextTick();
        }
        platform.disconnect(oldActor);
        publisher.supersede(oldActor);
        ConnectionKey reconnected = platform.connect(profileId, "actor");
        platform.observers.put(reconnected, List.of(observer));
        CompletionStage<BatchPublicationResult> reconnectedStage = publisher.publishBatch(List.of(
                request(
                        reconnected,
                        "actor",
                        appearance('b'),
                        Optional.of(new SignedTexturesProperty(
                                "reconnected-value", "reconnected-signature")))));

        assertEquals(
                PublicationOutcome.STALE,
                platform.await(oldStage).outcome(oldActor).orElseThrow());
        assertEquals(
                PublicationOutcome.UPDATED,
                platform.await(reconnectedStage).outcome(reconnected).orElseThrow());
        assertEquals(
                PublicationOutcome.UPDATED,
                platform.await(publisher.publishBatch(List.of(
                        defaultRequest(reconnected, "actor"))))
                        .outcome(reconnected)
                        .orElseThrow());
        assertEquals(2, platform.events.stream().filter("install"::equals).count());
        publisher.close();
    }

    @Test
    void staleOfficialProfileAfterReconnectCannotRollBackNewerLiveAppearance()
            throws Exception {
        FakePlatform platform = new FakePlatform();
        UUID profileId = UUID.randomUUID();
        ConnectionKey oldActor = platform.connect(profileId, "actor");
        platform.disconnect(oldActor);
        ConnectionKey reconnected = platform.connect(profileId, "actor");
        platform.current.put(
                reconnected,
                LiveProfileTextures.signed(
                        new SignedTexturesProperty("live-newer", "live-newer-signature")));
        platform.verifiedCurrent = appearance('b').withVerifiedSourceTimestamp(200L);
        VanillaBatchAppearancePublisher publisher = publisher(platform, 64, 5_000_000L, 2);

        BatchPublicationResult stale = platform.await(publisher.publishBatch(List.of(
                request(
                        reconnected,
                        "actor",
                        appearance('a').withVerifiedSourceTimestamp(100L),
                        Optional.of(new SignedTexturesProperty(
                                "fetched-older", "fetched-older-signature"))))));

        assertEquals(
                PublicationOutcome.UNCHANGED,
                stale.outcome(reconnected).orElseThrow());
        assertEquals(0, platform.events.stream().filter("untrack"::equals).count());
        assertEquals(0, platform.events.stream().filter("install"::equals).count());

        BatchPublicationResult fresh = platform.await(publisher.publishBatch(List.of(
                request(
                        reconnected,
                        "actor",
                        appearance('c').withVerifiedSourceTimestamp(300L),
                        Optional.of(new SignedTexturesProperty(
                                "fetched-newer", "fetched-newer-signature"))))));

        assertEquals(
                PublicationOutcome.UPDATED,
                fresh.outcome(reconnected).orElseThrow());
        assertEquals(1, platform.events.stream().filter("install"::equals).count());
        publisher.close();
    }

    @Test
    void concurrentIntentCannotEnterBetweenLatestCheckAndProfileInstall() throws Exception {
        FakePlatform platform = new FakePlatform();
        ConnectionKey actor = platform.connect("actor");
        VanillaBatchAppearancePublisher publisher = publisher(platform, 64, 5_000_000L, 2);
        PublicationRequest older = request(
                actor,
                "actor",
                appearance('b'),
                Optional.of(new SignedTexturesProperty("older-value", "older-signature")));
        PublicationRequest latest = defaultRequest(actor, "actor");
        AtomicReference<CompletionStage<BatchPublicationResult>> latestStage =
                new AtomicReference<>();
        CountDownLatch submissionStarted = new CountDownLatch(1);
        CountDownLatch submissionReturned = new CountDownLatch(1);
        AtomicBoolean returnedInsideInstall = new AtomicBoolean();
        AtomicBoolean firstInstall = new AtomicBoolean(true);
        platform.beforeInstall = () -> {
            if (!firstInstall.getAndSet(false)) {
                return;
            }
            Thread submitter = new Thread(() -> {
                submissionStarted.countDown();
                latestStage.set(publisher.publishBatch(List.of(latest)));
                submissionReturned.countDown();
            }, "nclskins-test-concurrent-submit");
            submitter.start();
            awaitLatch(submissionStarted, 1L, TimeUnit.SECONDS);
            returnedInsideInstall.set(awaitLatch(
                    submissionReturned, 200L, TimeUnit.MILLISECONDS));
        };

        BatchPublicationResult oldResult = platform.await(publisher.publishBatch(List.of(older)));

        assertFalse(returnedInsideInstall.get());
        assertTrue(submissionReturned.await(1L, TimeUnit.SECONDS));
        BatchPublicationResult latestResult = platform.await(latestStage.get());
        assertTrue(Set.of(PublicationOutcome.UPDATED, PublicationOutcome.STALE)
                .contains(oldResult.outcome(actor).orElseThrow()));
        assertEquals(PublicationOutcome.UPDATED, latestResult.outcome(actor).orElseThrow());
        assertEquals(
                LiveProfileTextures.Status.ACCOUNT_DEFAULT,
                platform.current.get(actor).status());
        publisher.close();
    }

    @Test
    void visibilityPortPreventsProfileDisclosureToHiddenRecipient() throws Exception {
        FakePlatform platform = new FakePlatform();
        ConnectionKey actor = platform.connect("actor");
        ConnectionKey observer = platform.connect("observer");
        platform.hidden.add(new Visibility(actor, observer));
        VanillaBatchAppearancePublisher publisher = publisher(platform, 64, 5_000_000L, 2);

        BatchPublicationResult result = platform.await(publisher.publishBatch(List.of(
                defaultRequest(actor, "actor"))));

        assertEquals(PublicationOutcome.UPDATED, result.outcome(actor).orElseThrow());
        assertEquals(0, platform.transportAttempts.getOrDefault(observer, 0));
        assertEquals(0L, result.metrics().profileDeliveries());
        publisher.close();
    }

    @Test
    void throwingVisibilityCheckSkipsOnlyThatRecipient() throws Exception {
        FakePlatform platform = new FakePlatform();
        ConnectionKey actor = platform.connect("actor");
        ConnectionKey broken = platform.connect("broken");
        ConnectionKey healthy = platform.connect("healthy");
        platform.throwingVisibility.add(broken);
        VanillaBatchAppearancePublisher publisher = publisher(platform, 64, 5_000_000L, 2);

        BatchPublicationResult result = platform.await(publisher.publishBatch(List.of(
                defaultRequest(actor, "actor"))));

        assertEquals(PublicationOutcome.UPDATED, result.outcome(actor).orElseThrow());
        assertEquals(0, platform.transportAttempts.getOrDefault(broken, 0));
        assertEquals(1, platform.transportAttempts.getOrDefault(healthy, 0));
        publisher.close();
    }

    private static VanillaBatchAppearancePublisher publisher(
            FakePlatform platform,
            int deliveries,
            long nanos,
            int reconciliationAttempts) {
        return publisher(
                platform,
                deliveries,
                nanos,
                reconciliationAttempts,
                VanillaBatchAppearancePublisher.AdvanceProbe.NOOP);
    }

    private static VanillaBatchAppearancePublisher publisher(
            FakePlatform platform,
            int deliveries,
            long nanos,
            int reconciliationAttempts,
            VanillaBatchAppearancePublisher.AdvanceProbe advanceProbe) {
        return new VanillaBatchAppearancePublisher(
                platform,
                platform,
                platform,
                platform,
                platform,
                platform,
                new VanillaPublicationPolicy(
                        64,
                        64,
                        deliveries,
                        Duration.ofNanos(nanos),
                        reconciliationAttempts,
                        128),
                advanceProbe);
    }

    private static PublicationRequest defaultRequest(ConnectionKey key, String name) {
        return request(
                key,
                name,
                TextureAppearance.accountDefault(),
                Optional.empty());
    }

    private static PublicationRequest request(
            ConnectionKey key,
            String name,
            TextureAppearance appearance,
            Optional<SignedTexturesProperty> textures) {
        return new PublicationRequest(
                key,
                new VerifiedOfficialProfile(
                        new ServerPlayerIdentity(key.profileId(), name),
                        appearance,
                        textures));
    }

    private static TextureAppearance appearance(char digit) {
        return TextureAppearance.verified(
                Optional.of(String.valueOf(digit).repeat(64)),
                Optional.of(TextureAppearance.SkinModel.CLASSIC),
                Optional.empty(),
                Optional.empty());
    }

    private static final class FakePlatform implements
            ConnectionRegistry,
            ProfileAccess,
            TrackingAccess,
            PlayerInfoTransport,
            PlatformScheduler,
            OfficialTextureSignatureVerifier {
        private final Set<ConnectionKey> connected = new LinkedHashSet<>();
        private final Map<ConnectionKey, String> names = new LinkedHashMap<>();
        private final Map<ConnectionKey, LiveProfileTextures> current = new LinkedHashMap<>();
        private final Map<ConnectionKey, List<ConnectionKey>> observers = new LinkedHashMap<>();
        private final Map<ConnectionKey, Integer> transportFailures = new LinkedHashMap<>();
        private final Map<ConnectionKey, Integer> initializeFailures = new LinkedHashMap<>();
        private final Map<ConnectionKey, Integer> transportAttempts = new LinkedHashMap<>();
        private final Map<ConnectionKey, Integer> initializeAttempts = new LinkedHashMap<>();
        private final Map<ConnectionKey, List<List<ConnectionKey>>> removePayloads =
                new LinkedHashMap<>();
        private final Set<Visibility> hidden = new LinkedHashSet<>();
        private final Set<ConnectionKey> throwingVisibility = new LinkedHashSet<>();
        private final Map<Integer, Integer> deliveriesByTick = new LinkedHashMap<>();
        private final List<String> events = new ArrayList<>();
        private final List<Integer> snapshotTicks = new ArrayList<>();
        private final List<Integer> installTicks = new ArrayList<>();
        private final ConcurrentLinkedQueue<Runnable> immediate = new ConcurrentLinkedQueue<>();
        private final ConcurrentLinkedQueue<Runnable> nextTick = new ConcurrentLinkedQueue<>();
        private TextureAppearance verifiedCurrent;
        private Runnable onUntrack = () -> {};
        private Runnable beforeInstall = () -> {};
        private int retrackFailuresRemaining;
        private int tick;
        private long nanos;
        private long operationCostNanos;
        private long generation;
        private boolean rejectExecute;
        private int nextTickRejectionsRemaining;

        private ConnectionKey connect(String name) {
            return connect(UUID.randomUUID(), name);
        }

        private ConnectionKey connect(UUID profileId, String name) {
            ConnectionKey key = new ConnectionKey(profileId, ++generation);
            connected.add(key);
            names.put(key, name);
            current.put(key, LiveProfileTextures.invalid());
            return key;
        }

        private void disconnect(ConnectionKey key) {
            connected.remove(key);
            names.remove(key);
            current.remove(key);
            observers.remove(key);
        }

        @Override
        public boolean isCurrent(PublicationRequest actor) {
            cost();
            return connected.contains(actor.connection())
                    && names.get(actor.connection()).equals(actor.profile().identity().profileName());
        }

        @Override
        public boolean isCurrent(ConnectionKey connection) {
            cost();
            return connected.contains(connection);
        }

        @Override
        public List<ConnectionKey> recipients() {
            cost();
            return List.copyOf(connected);
        }

        @Override
        public boolean isProfileVisible(
                ConnectionKey recipient,
                PublicationRequest actor) {
            cost();
            if (throwingVisibility.contains(recipient)) {
                throw new IllegalStateException("synthetic visibility failure");
            }
            return !hidden.contains(new Visibility(actor.connection(), recipient));
        }

        @Override
        public LiveProfileTextures captureCurrent(PublicationRequest actor) {
            cost();
            return current.get(actor.connection());
        }

        @Override
        public void install(PublicationRequest actor) {
            cost();
            beforeInstall.run();
            installTicks.add(tick);
            events.add("install");
            current.put(
                    actor.connection(),
                    actor.profile().textures()
                            .map(LiveProfileTextures::signed)
                            .orElseGet(LiveProfileTextures::accountDefault));
        }

        @Override
        public List<ConnectionKey> snapshotObservers(PublicationRequest actor) {
            cost();
            snapshotTicks.add(tick);
            return observers.getOrDefault(actor.connection(), List.of());
        }

        @Override
        public boolean untrack(PublicationRequest actor, ConnectionKey observer) {
            cost();
            events.add("untrack");
            onUntrack.run();
            return true;
        }

        @Override
        public void retrack(PublicationRequest actor, ConnectionKey observer) {
            cost();
            if (retrackFailuresRemaining > 0) {
                retrackFailuresRemaining--;
                events.add("retrack-failed");
                throw new IllegalStateException("synthetic retrack failure");
            }
            events.add("retrack");
        }

        @Override
        public void removeProfiles(
                ConnectionKey recipient,
                List<PublicationRequest> actors) {
            cost();
            transportAttempts.merge(recipient, 1, Integer::sum);
            events.add("remove:" + names.get(recipient));
            removePayloads.computeIfAbsent(recipient, ignored -> new ArrayList<>()).add(
                    actors.stream().map(PublicationRequest::connection).toList());
            deliveriesByTick.merge(tick, actors.size(), Integer::sum);
            int failures = transportFailures.getOrDefault(recipient, 0);
            if (failures > 0) {
                transportFailures.put(recipient, failures - 1);
                throw new IllegalStateException("synthetic packet failure");
            }
        }

        @Override
        public void initializeProfiles(
                ConnectionKey recipient,
                List<PublicationRequest> actors) {
            cost();
            initializeAttempts.merge(recipient, 1, Integer::sum);
            events.add("initialize:" + names.get(recipient));
            int failures = initializeFailures.getOrDefault(recipient, 0);
            if (failures > 0) {
                initializeFailures.put(recipient, failures - 1);
                throw new IllegalStateException("synthetic initialize failure");
            }
        }

        @Override
        public Optional<TextureAppearance> verify(
                SignedTexturesProperty textures,
                ServerPlayerIdentity expectedIdentity) {
            return Optional.ofNullable(verifiedCurrent);
        }

        @Override
        public boolean isPlatformThread() {
            return true;
        }

        @Override
        public void execute(Runnable action) {
            if (rejectExecute) {
                throw new IllegalStateException("synthetic scheduler rejection");
            }
            immediate.add(action);
        }

        @Override
        public void nextTick(Runnable action) {
            if (nextTickRejectionsRemaining > 0) {
                nextTickRejectionsRemaining--;
                throw new IllegalStateException("synthetic next-tick rejection");
            }
            nextTick.add(action);
        }

        @Override
        public long nanoTime() {
            return nanos;
        }

        @Override
        public long tickId() {
            return tick;
        }

        private void runImmediate() {
            Runnable action;
            while ((action = immediate.poll()) != null) {
                action.run();
            }
        }

        private void runNextTick() {
            tick++;
            Runnable action;
            while ((action = nextTick.poll()) != null) {
                immediate.add(action);
            }
            runImmediate();
        }

        private BatchPublicationResult await(
                CompletionStage<BatchPublicationResult> stage) throws Exception {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!stage.toCompletableFuture().isDone() && System.nanoTime() < deadline) {
                runImmediate();
                if (!nextTick.isEmpty()) {
                    runNextTick();
                } else {
                    Thread.sleep(1L);
                }
            }
            return stage.toCompletableFuture().get(1L, TimeUnit.SECONDS);
        }

        private void cost() {
            nanos += operationCostNanos;
        }
    }

    private static boolean awaitLatch(
            CountDownLatch latch,
            long timeout,
            TimeUnit unit) {
        try {
            return latch.await(timeout, unit);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static void assertOrdered(List<String> events, String... expected) {
        int previous = -1;
        for (String marker : expected) {
            int current = events.indexOf(marker);
            assertTrue(current > previous, () -> marker + " missing or out of order: " + events);
            previous = current;
        }
    }

    private record Visibility(ConnectionKey actor, ConnectionKey recipient) {}
}

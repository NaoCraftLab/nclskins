package com.naocraftlab.skins.server.runtime;

import com.naocraftlab.skins.server.Admission;
import com.naocraftlab.skins.server.BatchAppearancePublisher;
import com.naocraftlab.skins.server.BatchPublicationResult;
import com.naocraftlab.skins.server.ConnectionKey;
import com.naocraftlab.skins.server.ConnectionSnapshot;
import com.naocraftlab.skins.server.OfficialProfileResolver;
import com.naocraftlab.skins.server.PublicationMetrics;
import com.naocraftlab.skins.server.PublicationOutcome;
import com.naocraftlab.skins.server.PublicationRequest;
import com.naocraftlab.skins.server.RefreshResult;
import com.naocraftlab.skins.server.RefreshSubmission;
import com.naocraftlab.skins.server.ServerRefreshHealthSnapshot;
import com.naocraftlab.skins.server.VerifiedOfficialProfile;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;


public final class ServerAppearanceRefreshCoordinator implements AutoCloseable {
    private static final Duration FALLBACK_RETRY_AFTER = Duration.ofSeconds(60);
    private static final Cancellable NO_SCHEDULE = () -> false;

    private final Object lock = new Object();
    private final OfficialProfileResolver resolver;
    private final BatchAppearancePublisher publisher;
    private final ServerRefreshPolicy policy;
    private final LongSupplier nanoTime;
    private final Scheduler scheduler;
    private final Jitter jitter;
    private final AutoCloseable ownedInfrastructure;
    private final Map<UUID, Cycle> cycles = new LinkedHashMap<>();
    private final Map<UUID, Long> lastCycleStarts = new LinkedHashMap<>();
    private final ArrayDeque<Cycle> ready = new ArrayDeque<>();
    private final LinkedHashMap<UUID, PendingPublication> pendingPublications =
            new LinkedHashMap<>();

    private int lookupsInFlight;
    private int publicationsInFlight;
    private double lookupTokens;
    private long tokensUpdatedAt;
    private long globalNotBefore;
    private Cancellable pumpSchedule = NO_SCHEDULE;
    private long pumpScheduledAt = Long.MAX_VALUE;
    private Cancellable batchSchedule = NO_SCHEDULE;
    private PublicationDispatch activePublication;
    private boolean closed;

    private long acceptedCount;
    private long coalescedCount;
    private long overloadedCount;
    private long expiredCount;
    private long throttledCount;
    private long lookupCount;
    private long lookupResolvedCount;
    private long lookupTransientFailureCount;
    private long lookupRejectedCount;
    private long lookupLatencyTotalNanos;
    private long lookupLatencyMaxNanos;
    private long publicationBatchCount;
    private long publicationActorCount;
    private long publicationRecipientCount;
    private long publicationProfileDeliveryCount;
    private long publicationPacketChunkCount;
    private long publicationWatcherPairCount;
    private long estimatedEgressBytes;
    private long platformThreadTotalNanos;
    private long platformThreadMaxNanos;
    private long reconciliationCount;
    private long reconciliationDeliveryCount;


    public ServerAppearanceRefreshCoordinator(
            OfficialProfileResolver resolver,
            BatchAppearancePublisher publisher,
            ServerRefreshPolicy policy) {
        this(resolver, publisher, policy, new OwnedInfrastructure());
    }


    public ServerAppearanceRefreshCoordinator(
            OfficialProfileResolver resolver,
            BatchAppearancePublisher publisher,
            ServerRefreshPolicy policy,
            LongSupplier nanoTime,
            Scheduler scheduler,
            Jitter jitter) {
        this(resolver, publisher, policy, nanoTime, scheduler, jitter, null);
    }

    private ServerAppearanceRefreshCoordinator(
            OfficialProfileResolver resolver,
            BatchAppearancePublisher publisher,
            ServerRefreshPolicy policy,
            OwnedInfrastructure infrastructure) {
        this(
                resolver,
                publisher,
                policy,
                System::nanoTime,
                infrastructure,
                Jitter.randomTwentyPercent(),
                infrastructure);
    }

    private ServerAppearanceRefreshCoordinator(
            OfficialProfileResolver resolver,
            BatchAppearancePublisher publisher,
            ServerRefreshPolicy policy,
            LongSupplier nanoTime,
            Scheduler scheduler,
            Jitter jitter,
            AutoCloseable ownedInfrastructure) {
        this.resolver = Objects.requireNonNull(resolver, "resolver");
        this.publisher = Objects.requireNonNull(publisher, "publisher");
        this.policy = Objects.requireNonNull(policy, "policy");
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.jitter = Objects.requireNonNull(jitter, "jitter");
        this.ownedInfrastructure = ownedInfrastructure;
        long now = nanoTime.getAsLong();
        this.tokensUpdatedAt = now;
        this.lookupTokens = policy.lookupBurst();
    }


    public RefreshSubmission request(ConnectionSnapshot connection) {
        Objects.requireNonNull(connection, "connection");
        CompletableFuture<RefreshResult> previousCompletion = null;
        CompletableFuture<RefreshResult> completion;
        Admission admission;
        Cycle scheduleInitial = null;
        Cycle disconnected = null;
        ConnectionKey publisherFence = null;
        synchronized (lock) {
            if (closed) {
                return immediate(Admission.CLOSED, RefreshResult.CLOSED);
            }
            long now = nanoTime.getAsLong();
            purgeCooldownsLocked(now);
            UUID profileId = connection.key().profileId();
            Cycle existing = cycles.get(profileId);
            if (existing != null && existing.connection.key().equals(connection.key())) {
                previousCompletion = existing.completion;
                existing.completion = new CompletableFuture<>();
                existing.connection = connection;
                existing.revision++;
                completion = existing.completion;
                if (existing.state == State.BATCH_QUEUED
                        || existing.state == State.PUBLISHING) {
                    publisherFence = existing.connection.key();
                }
                admission = Admission.COALESCED;
                coalescedCount++;
            } else {
                if (existing != null) {
                    publisherFence = existing.connection.key();
                    disconnected = removeCycleLocked(existing);
                }
                if (cycles.size() >= policy.maxPendingConnections()) {
                    overloadedCount++;
                    return immediate(Admission.OVERLOADED, RefreshResult.OVERLOADED);
                }
                long eligibleAt = now;
                Long lastStart = lastCycleStarts.get(profileId);
                if (lastStart != null) {
                    eligibleAt = Math.max(
                            eligibleAt,
                            saturatedAdd(lastStart, nanos(policy.independentCycleCooldown())));
                }
                Cycle cycle = new Cycle(connection, now, eligibleAt);
                cycles.put(profileId, cycle);
                completion = cycle.completion;
                admission = Admission.ACCEPTED;
                acceptedCount++;
                scheduleInitial = cycle;
            }
        }
        if (publisherFence != null) {
            publisher.supersede(publisherFence);
        }
        if (previousCompletion != null) {
            previousCompletion.complete(RefreshResult.SUPERSEDED);
        }
        if (disconnected != null) {
            disconnected.completion.complete(RefreshResult.DISCONNECTED);
        }
        if (scheduleInitial != null) {
            scheduleAttempt(scheduleInitial, 0);
        }
        pump();
        return new RefreshSubmission(admission, completion);
    }


    public void disconnected(ConnectionKey connection) {
        Objects.requireNonNull(connection, "connection");
        Cycle removed = null;
        synchronized (lock) {
            Cycle candidate = cycles.get(connection.profileId());
            if (candidate != null && candidate.connection.key().equals(connection)) {
                removed = removeCycleLocked(candidate);
            }
        }
        if (removed != null) {
            publisher.supersede(connection);
            removed.completion.complete(RefreshResult.DISCONNECTED);
        }
        pump();
    }

    public ServerRefreshHealthSnapshot health() {
        synchronized (lock) {
            long now = nanoTime.getAsLong();
            int delayed = 0;
            long oldestQueueAgeNanos = 0L;
            for (Cycle cycle : cycles.values()) {
                if (cycle.state == State.DELAYED && cycle.waitingForRetry) {
                    delayed++;
                }
                oldestQueueAgeNanos = Math.max(
                        oldestQueueAgeNanos,
                        nonNegativeDifference(now, cycle.enqueuedAt));
            }
            return new ServerRefreshHealthSnapshot(
                    cycles.size(),
                    ready.size(),
                    delayed,
                    lookupsInFlight,
                    publicationsInFlight,
                    acceptedCount,
                    coalescedCount,
                    overloadedCount,
                    expiredCount,
                    throttledCount,
                    lookupCount,
                    lookupResolvedCount,
                    lookupTransientFailureCount,
                    lookupRejectedCount,
                    lookupLatencyTotalNanos,
                    lookupLatencyMaxNanos,
                    oldestQueueAgeNanos,
                    nonNegativeDifference(globalNotBefore, now),
                    publicationBatchCount,
                    publicationActorCount,
                    publicationRecipientCount,
                    publicationProfileDeliveryCount,
                    publicationPacketChunkCount,
                    publicationWatcherPairCount,
                    estimatedEgressBytes,
                    platformThreadTotalNanos,
                    platformThreadMaxNanos,
                    reconciliationCount,
                    reconciliationDeliveryCount);
        }
    }

    @Override
    public void close() {
        List<Cycle> pending;
        PublicationDispatch publication;
        synchronized (lock) {
            if (closed) {
                return;
            }
            closed = true;
            pending = new ArrayList<>(cycles.values());
            for (Cycle cycle : pending) {
                cycle.schedule.cancel();
                cancelLookupLocked(cycle);
            }
            cycles.clear();
            ready.clear();
            pendingPublications.clear();
            lastCycleStarts.clear();
            pumpSchedule.cancel();
            pumpSchedule = NO_SCHEDULE;
            batchSchedule.cancel();
            batchSchedule = NO_SCHEDULE;
            publication = activePublication;
            activePublication = null;
            if (publication != null && publication.settled.compareAndSet(false, true)) {
                publicationsInFlight = 0;
            } else {
                publication = null;
            }
        }
        if (publication != null) {
            publication.timeout.cancel();
            publication.cancelStage();
        }
        for (Cycle cycle : pending) {
            cycle.completion.complete(RefreshResult.CLOSED);
        }
        if (ownedInfrastructure != null) {
            try {
                ownedInfrastructure.close();
            } catch (Exception ignored) {

            }
        }
    }

    private void scheduleAttempt(Cycle cycle, int attemptIndex) {
        Duration nominal = policy.attemptOffsets().get(attemptIndex);
        Duration offset = Objects.requireNonNull(
                jitter.apply(nominal, attemptIndex), "jittered attempt offset");
        if (offset.isNegative()) {
            throw new IllegalArgumentException("Jitter must not produce a negative delay");
        }
        long dueAt;
        synchronized (lock) {
            if (!isCurrentLocked(cycle)) {
                return;
            }
            cycle.waitingForRetry = attemptIndex > 0;
            dueAt = saturatedAdd(cycle.attemptOriginAt, nanos(offset));
        }
        scheduleCycleAt(cycle, dueAt);
    }

    private void scheduleCycleAt(Cycle cycle, long dueAt) {
        PendingSchedule token = new PendingSchedule();
        Cancellable previous;
        long delay;
        synchronized (lock) {
            if (!isCurrentLocked(cycle)) {
                return;
            }
            long now = nanoTime.getAsLong();
            if (isExpiredLocked(cycle, now)) {
                finishLater(cycle, RefreshResult.EXPIRED);
                return;
            }
            previous = cycle.schedule;
            cycle.schedule = token;
            cycle.state = State.DELAYED;
            delay = nonNegativeDifference(dueAt, now);
        }
        previous.cancel();
        Cancellable scheduled;
        try {
            scheduled = Objects.requireNonNull(
                    scheduler.schedule(Duration.ofNanos(delay), () -> makeReady(cycle, token)),
                    "scheduled attempt");
        } catch (RuntimeException rejected) {
            finish(cycle, RefreshResult.FAILED);
            return;
        }
        token.attach(scheduled);
        synchronized (lock) {
            if (!isCurrentLocked(cycle) || cycle.schedule != token) {
                token.cancel();
            }
        }
    }

    private void makeReady(Cycle cycle, PendingSchedule token) {
        boolean expired = false;
        synchronized (lock) {
            if (!isCurrentLocked(cycle) || cycle.schedule != token) {
                return;
            }
            cycle.schedule = NO_SCHEDULE;
            long now = nanoTime.getAsLong();
            if (isExpiredLocked(cycle, now)) {
                expired = true;
            } else {
                cycle.state = State.READY;
                ready.addLast(cycle);
            }
        }
        if (expired) {
            finish(cycle, RefreshResult.EXPIRED);
        } else {
            pump();
        }
    }

    private void pump() {
        List<LookupDispatch> dispatches = new ArrayList<>();
        List<Cycle> expired = new ArrayList<>();
        long wakeAt = Long.MAX_VALUE;
        synchronized (lock) {
            if (closed) {
                return;
            }
            long now = nanoTime.getAsLong();
            refillTokensLocked(now);
            purgeStaleReadyLocked();
            while (lookupsInFlight < policy.maxConcurrentLookups() && !ready.isEmpty()) {
                Cycle cycle = ready.peekFirst();
                if (cycle == null) {
                    break;
                }
                if (!isCurrentLocked(cycle) || cycle.state != State.READY) {
                    ready.removeFirst();
                    continue;
                }
                if (isExpiredLocked(cycle, now)) {
                    ready.removeFirst();
                    expired.add(cycle);
                    continue;
                }
                if (now < globalNotBefore) {
                    wakeAt = Math.min(globalNotBefore, expirationAtLocked(cycle));
                    break;
                }
                if (lookupTokens < 1.0d) {
                    double missing = 1.0d - lookupTokens;
                    long wait = (long) Math.ceil(
                            missing * 1_000_000_000.0d / policy.lookupRatePerSecond());
                    wakeAt = Math.min(
                            saturatedAdd(now, Math.max(1L, wait)),
                            expirationAtLocked(cycle));
                    break;
                }
                ready.removeFirst();
                lookupTokens -= 1.0d;
                lookupsInFlight++;
                lookupCount++;
                if (cycle.activeStartedAt == Long.MIN_VALUE) {
                    cycle.activeStartedAt = now;
                    cycle.attemptOriginAt = now;
                    cycle.deadlineAt = saturatedAdd(now, nanos(policy.lookupCycleDeadline()));
                    lastCycleStarts.put(cycle.connection.key().profileId(), now);
                    while (lastCycleStarts.size() > policy.maxPendingConnections()) {
                        Iterator<UUID> oldest = lastCycleStarts.keySet().iterator();
                        oldest.next();
                        oldest.remove();
                    }
                }
                int attempt = cycle.attemptsDispatched++;
                cycle.state = State.LOOKUP;
                LookupDispatch dispatch = new LookupDispatch(
                        cycle,
                        cycle.revision,
                        attempt,
                        cycle.connection,
                        now,
                        expirationAtLocked(cycle));
                cycle.lookupDispatch = dispatch;
                dispatches.add(dispatch);
            }
        }
        for (Cycle cycle : expired) {
            finish(cycle, RefreshResult.EXPIRED);
        }
        if (wakeAt != Long.MAX_VALUE) {
            schedulePumpAt(wakeAt);
        }
        for (LookupDispatch dispatch : dispatches) {
            dispatchLookup(dispatch);
        }
    }

    private void dispatchLookup(LookupDispatch dispatch) {
        try {
            dispatch.timeout.attach(Objects.requireNonNull(
                    scheduler.schedule(
                            Duration.ofNanos(nonNegativeDifference(
                                    dispatch.expiresAtNanos,
                                    nanoTime.getAsLong())),
                            () -> lookupCompleted(dispatch, null, true)),
                    "lookup deadline"));
        } catch (RuntimeException unavailableScheduler) {
            lookupCompleted(dispatch, null, true);
            return;
        }
        if (dispatch.settled.get()) {
            return;
        }
        final CompletionStage<OfficialProfileResolver.Resolution> stage;
        try {
            stage = Objects.requireNonNull(
                    resolver.resolve(dispatch.connection), "profile resolution stage");
        } catch (RuntimeException failure) {
            lookupCompleted(dispatch, null, false);
            return;
        }
        dispatch.attachStage(stage);
        stage.whenComplete((resolution, failure) -> lookupCompleted(
                dispatch,
                failure == null ? resolution : null,
                false));
    }

    private void lookupCompleted(
            LookupDispatch dispatch,
            OfficialProfileResolver.Resolution resolution,
            boolean timedOut) {
        OfficialProfileResolver.Resolution observed = resolution != null
                ? resolution
                : OfficialProfileResolver.Resolution.transientFailure();
        if (observed.status() == OfficialProfileResolver.Resolution.Status.THROTTLED) {


            installGlobalThrottle(observed);
        }
        long completedAt = nanoTime.getAsLong();
        timedOut = timedOut || completedAt >= dispatch.expiresAtNanos;
        if (!dispatch.settled.compareAndSet(false, true)) {
            return;
        }
        if (timedOut) {
            dispatch.cancelStage();
        }
        dispatch.timeout.cancel();
        OfficialProfileResolver.Resolution safe = !timedOut
                ? observed
                : OfficialProfileResolver.Resolution.transientFailure();
        boolean currentRevision;
        synchronized (lock) {
            if (dispatch.cycle.lookupDispatch == dispatch) {
                dispatch.cycle.lookupDispatch = null;
            }
            lookupsInFlight--;
            long latency = nonNegativeDifference(completedAt, dispatch.startedAtNanos);
            lookupLatencyTotalNanos = saturatedAdd(lookupLatencyTotalNanos, latency);
            lookupLatencyMaxNanos = Math.max(lookupLatencyMaxNanos, latency);
            switch (safe.status()) {
                case RESOLVED -> lookupResolvedCount++;
                case TRANSIENT_FAILURE -> lookupTransientFailureCount++;
                case REJECTED -> lookupRejectedCount++;
                case THROTTLED -> {

                }
            }
            currentRevision = isCurrentLocked(dispatch.cycle)
                    && dispatch.cycle.revision == dispatch.revision;
        }
        if (!currentRevision) {
            retrySuperseded(dispatch.cycle);
            pump();
            return;
        }
        switch (safe.status()) {
            case REJECTED -> finish(dispatch.cycle, RefreshResult.REJECTED);
            case TRANSIENT_FAILURE -> retryOrFinish(dispatch.cycle, RefreshResult.EXHAUSTED);
            case THROTTLED -> retryOrFinish(dispatch.cycle, RefreshResult.EXHAUSTED);
            case RESOLVED -> handleResolved(dispatch, safe.profile().orElseThrow());
        }
        pump();
    }

    private void installGlobalThrottle(
            OfficialProfileResolver.Resolution resolution) {
        Duration retryAfter = resolution.retryAfter().orElse(FALLBACK_RETRY_AFTER);
        if (retryAfter.isNegative()) {
            retryAfter = FALLBACK_RETRY_AFTER;
        }
        long now = nanoTime.getAsLong();
        synchronized (lock) {
            globalNotBefore = Math.max(
                    globalNotBefore,
                    saturatedAdd(now, nanos(retryAfter)));
            throttledCount++;
        }
    }

    private void handleResolved(LookupDispatch dispatch, VerifiedOfficialProfile profile) {
        if (!dispatch.connection.identity().equals(profile.identity())) {
            finish(dispatch.cycle, RefreshResult.REJECTED);
            return;
        }
        enqueuePublication(new PendingPublication(
                dispatch.cycle,
                dispatch.revision,
                profile));
    }

    private void enqueuePublication(PendingPublication pending) {
        boolean schedule;
        synchronized (lock) {
            if (!isCurrentLocked(pending.cycle)
                    || pending.cycle.revision != pending.revision) {
                return;
            }
            pendingPublications.put(pending.cycle.connection.key().profileId(), pending);
            pending.cycle.state = State.BATCH_QUEUED;
            schedule = publicationsInFlight == 0 && batchSchedule == NO_SCHEDULE;
        }
        if (schedule) {
            scheduleBatch();
        }
    }

    private void scheduleBatch() {
        PendingSchedule token = new PendingSchedule();
        synchronized (lock) {
            if (closed || publicationsInFlight != 0 || pendingPublications.isEmpty()
                    || batchSchedule != NO_SCHEDULE) {
                return;
            }
            batchSchedule = token;
        }
        Cancellable scheduled;
        try {
            scheduled = Objects.requireNonNull(
                    scheduler.schedule(policy.batchWindow(), () -> flushBatch(token)),
                    "scheduled publication batch");
        } catch (RuntimeException failure) {
            failPendingPublications();
            return;
        }
        token.attach(scheduled);
        synchronized (lock) {
            if (batchSchedule != token) {
                token.cancel();
            }
        }
    }

    private void flushBatch(PendingSchedule token) {
        List<PendingPublication> batch = new ArrayList<>();
        List<Cycle> superseded = new ArrayList<>();
        List<Cycle> expired = new ArrayList<>();
        long publicationExpiresAt = Long.MAX_VALUE;
        PublicationDispatch selectedDispatch = null;
        synchronized (lock) {
            if (batchSchedule != token || closed) {
                return;
            }
            batchSchedule = NO_SCHEDULE;
            if (publicationsInFlight != 0) {
                return;
            }
            long now = nanoTime.getAsLong();
            Iterator<PendingPublication> iterator = pendingPublications.values().iterator();
            while (iterator.hasNext() && batch.size() < policy.maxBatchActors()) {
                PendingPublication pending = iterator.next();
                iterator.remove();
                if (!isCurrentLocked(pending.cycle)
                        || pending.cycle.revision != pending.revision) {
                    if (isCurrentLocked(pending.cycle)) {
                        superseded.add(pending.cycle);
                    }
                    continue;
                }
                if (isExpiredLocked(pending.cycle, now)) {
                    expired.add(pending.cycle);
                    continue;
                }
                pending.cycle.state = State.PUBLISHING;
                batch.add(pending);
                publicationExpiresAt = Math.min(
                        publicationExpiresAt,
                        expirationAtLocked(pending.cycle));
            }
            if (!batch.isEmpty()) {
                publicationsInFlight = 1;
                publicationBatchCount++;
                publicationActorCount += batch.size();
                selectedDispatch = new PublicationDispatch(batch, publicationExpiresAt);
                activePublication = selectedDispatch;
            }
        }
        for (Cycle cycle : superseded) {
            retrySuperseded(cycle);
        }
        for (Cycle cycle : expired) {
            finish(cycle, RefreshResult.EXPIRED);
        }
        if (batch.isEmpty()) {
            scheduleBatch();
            return;
        }
        PublicationDispatch dispatch = Objects.requireNonNull(
                selectedDispatch, "active publication dispatch");
        List<PublicationRequest> requests = batch.stream()
                .map(PendingPublication::request)
                .toList();
        long batchDeadline = publicationExpiresAt;
        try {
            dispatch.timeout.attach(Objects.requireNonNull(
                    scheduler.schedule(
                            Duration.ofNanos(nonNegativeDifference(
                                    batchDeadline,
                                    nanoTime.getAsLong())),
                            () -> publicationCompleted(dispatch, null, true)),
                    "publication deadline"));
        } catch (RuntimeException unavailableScheduler) {
            publicationCompleted(dispatch, null, true);
            return;
        }
        if (dispatch.settled.get()) {
            return;
        }
        if (nanoTime.getAsLong() >= batchDeadline) {
            publicationCompleted(dispatch, null, true);
            return;
        }
        final CompletionStage<BatchPublicationResult> stage;
        try {
            stage = Objects.requireNonNull(
                    publisher.publishBatch(List.copyOf(requests)),
                    "publication stage");
        } catch (RuntimeException failure) {
            publicationCompleted(dispatch, null, true);
            return;
        }
        dispatch.attachStage(stage);
        stage.whenComplete((result, failure) -> publicationCompleted(
                dispatch,
                failure == null ? result : null,
                failure != null));
    }

    private void publicationCompleted(
            PublicationDispatch dispatch,
            BatchPublicationResult result,
            boolean failure) {
        failure = failure || nanoTime.getAsLong() >= dispatch.expiresAtNanos;
        if (!dispatch.settled.compareAndSet(false, true)) {
            return;
        }
        if (failure) {
            dispatch.cancelStage();
        }
        dispatch.timeout.cancel();
        List<PendingPublication> batch = dispatch.batch;
        synchronized (lock) {
            if (activePublication == dispatch) {
                activePublication = null;
            }
            publicationsInFlight = 0;
            if (!failure && result != null) {
                PublicationMetrics metrics = result.metrics();
                publicationRecipientCount = saturatedAdd(
                        publicationRecipientCount, metrics.recipients());
                publicationProfileDeliveryCount = saturatedAdd(
                        publicationProfileDeliveryCount, metrics.profileDeliveries());
                publicationPacketChunkCount = saturatedAdd(
                        publicationPacketChunkCount, metrics.packetChunks());
                publicationWatcherPairCount = saturatedAdd(
                        publicationWatcherPairCount, metrics.watcherPairs());
                estimatedEgressBytes = saturatedAdd(
                        estimatedEgressBytes, metrics.estimatedEgressBytes());
                platformThreadTotalNanos = saturatedAdd(
                        platformThreadTotalNanos, metrics.platformThreadNanos());
                platformThreadMaxNanos = Math.max(
                        platformThreadMaxNanos, metrics.platformThreadMaxTickNanos());
                reconciliationDeliveryCount = saturatedAdd(
                        reconciliationDeliveryCount, metrics.reconciliationDeliveries());
                reconciliationCount = saturatedAdd(
                        reconciliationCount, metrics.reconciliationAttempts());
            }
        }
        for (PendingPublication pending : batch) {
            boolean expired;
            synchronized (lock) {
                expired = isCurrentLocked(pending.cycle)
                        && isExpiredLocked(pending.cycle, nanoTime.getAsLong());
            }
            if (expired) {
                finish(pending.cycle, RefreshResult.EXPIRED);
                continue;
            }
            PublicationOutcome outcome = !failure && result != null
                    ? result.outcome(pending.cycle.connection.key())
                            .orElse(PublicationOutcome.FAILED)
                    : PublicationOutcome.FAILED;
            handlePublication(pending, outcome);
        }
        scheduleBatch();
        pump();
    }

    private void handlePublication(
            PendingPublication pending,
            PublicationOutcome outcome) {
        synchronized (lock) {
            if (!isCurrentLocked(pending.cycle)) {
                return;
            }
            if (pending.cycle.revision != pending.revision) {
                retrySuperseded(pending.cycle);
                return;
            }
        }
        switch (outcome) {
            case UPDATED -> finish(pending.cycle, RefreshResult.UPDATED);
            case UNCHANGED -> {
                synchronized (lock) {
                    pending.cycle.sawResolvedUnchanged = true;
                }
                retryOrFinish(pending.cycle, RefreshResult.UNCHANGED);
            }
            case STALE -> finish(pending.cycle, RefreshResult.STALE_CONNECTION);
            case FAILED -> finish(pending.cycle, RefreshResult.FAILED);
        }
    }

    private void retrySuperseded(Cycle cycle) {
        synchronized (lock) {
            if (!isCurrentLocked(cycle)) {
                return;
            }
            cycle.attemptsDispatched = 0;
            cycle.sawResolvedUnchanged = false;
            cycle.waitingForRetry = false;
            cycle.attemptOriginAt = nanoTime.getAsLong();
        }
        scheduleAttempt(cycle, 0);
    }

    private void retryOrFinish(Cycle cycle, RefreshResult terminal) {
        int nextAttempt;
        long now = nanoTime.getAsLong();
        synchronized (lock) {
            if (!isCurrentLocked(cycle)) {
                return;
            }
            if (isExpiredLocked(cycle, now)) {
                terminal = RefreshResult.EXPIRED;
                nextAttempt = -1;
            } else {
                nextAttempt = cycle.attemptsDispatched;
                if (nextAttempt >= policy.attemptOffsets().size()) {
                    if (cycle.sawResolvedUnchanged && terminal == RefreshResult.EXHAUSTED) {
                        terminal = RefreshResult.UNCHANGED;
                    }
                    nextAttempt = -1;
                }
            }
        }
        if (nextAttempt < 0) {
            finish(cycle, terminal);
        } else {
            scheduleAttempt(cycle, nextAttempt);
        }
    }

    private void finish(Cycle cycle, RefreshResult result) {
        CompletableFuture<RefreshResult> completion;
        synchronized (lock) {
            if (!isCurrentLocked(cycle)) {
                return;
            }
            removeCycleLocked(cycle);
            if (result == RefreshResult.EXPIRED) {
                expiredCount++;
            }
            completion = cycle.completion;
        }
        completion.complete(result);
    }

    private void finishLater(Cycle cycle, RefreshResult result) {
        try {
            scheduler.schedule(Duration.ZERO, () -> finish(cycle, result));
        } catch (RuntimeException failure) {
            finish(cycle, result);
        }
    }

    private Cycle removeCycleLocked(Cycle cycle) {
        UUID profileId = cycle.connection.key().profileId();
        if (cycles.get(profileId) == cycle) {
            cycles.remove(profileId);
        }
        cancelLookupLocked(cycle);
        pendingPublications.remove(profileId);
        ready.removeIf(candidate -> candidate == cycle);
        cycle.schedule.cancel();
        cycle.schedule = NO_SCHEDULE;
        cycle.state = State.TERMINAL;
        return cycle;
    }


    private void cancelLookupLocked(Cycle cycle) {
        LookupDispatch dispatch = cycle.lookupDispatch;
        cycle.lookupDispatch = null;
        if (dispatch == null || !dispatch.settled.compareAndSet(false, true)) {
            return;
        }
        dispatch.timeout.cancel();
        dispatch.cancelStage();
        if (lookupsInFlight <= 0) {
            throw new IllegalStateException("Lookup slot accounting underflow");
        }
        lookupsInFlight--;
    }

    private void failPendingPublications() {
        List<PendingPublication> pending;
        synchronized (lock) {
            batchSchedule = NO_SCHEDULE;
            pending = new ArrayList<>(pendingPublications.values());
            pendingPublications.clear();
        }
        for (PendingPublication item : pending) {
            finish(item.cycle, RefreshResult.FAILED);
        }
    }

    private void schedulePumpAt(long wakeAt) {
        PendingSchedule token = new PendingSchedule();
        long delay;
        Cancellable previous;
        synchronized (lock) {
            if (closed || pumpScheduledAt <= wakeAt) {
                return;
            }
            previous = pumpSchedule;
            pumpSchedule = token;
            pumpScheduledAt = wakeAt;
            delay = nonNegativeDifference(wakeAt, nanoTime.getAsLong());
        }
        previous.cancel();
        Cancellable scheduled;
        try {
            scheduled = Objects.requireNonNull(
                    scheduler.schedule(Duration.ofNanos(delay), () -> {
                        synchronized (lock) {
                            if (pumpSchedule != token) {
                                return;
                            }
                            pumpSchedule = NO_SCHEDULE;
                            pumpScheduledAt = Long.MAX_VALUE;
                        }
                        pump();
                    }),
                    "scheduled lookup pump");
        } catch (RuntimeException failure) {
            synchronized (lock) {
                if (pumpSchedule == token) {
                    pumpSchedule = NO_SCHEDULE;
                    pumpScheduledAt = Long.MAX_VALUE;
                }
            }
            return;
        }
        token.attach(scheduled);
    }

    private void refillTokensLocked(long now) {
        long elapsed = nonNegativeDifference(now, tokensUpdatedAt);
        if (elapsed > 0L) {
            lookupTokens = Math.min(
                    policy.lookupBurst(),
                    lookupTokens + elapsed * policy.lookupRatePerSecond() / 1_000_000_000.0d);
            tokensUpdatedAt = now;
        }
    }

    private void purgeStaleReadyLocked() {
        ready.removeIf(cycle -> !isCurrentLocked(cycle) || cycle.state != State.READY);
    }

    private void purgeCooldownsLocked(long now) {
        long retention = nanos(policy.independentCycleCooldown());
        lastCycleStarts.entrySet().removeIf(
                entry -> saturatedAdd(entry.getValue(), retention) <= now);
    }

    private long expirationAtLocked(Cycle cycle) {
        long queueExpiry = saturatedAdd(cycle.enqueuedAt, nanos(policy.maxQueueAge()));
        return cycle.activeStartedAt == Long.MIN_VALUE
                ? queueExpiry
                : Math.min(queueExpiry, cycle.deadlineAt);
    }

    private boolean isExpiredLocked(Cycle cycle, long now) {
        if (saturatedAdd(cycle.enqueuedAt, nanos(policy.maxQueueAge())) <= now) {
            return true;
        }
        return cycle.activeStartedAt != Long.MIN_VALUE && cycle.deadlineAt <= now;
    }

    private boolean isCurrentLocked(Cycle cycle) {
        return !closed
                && cycles.get(cycle.connection.key().profileId()) == cycle
                && cycle.state != State.TERMINAL;
    }

    private static RefreshSubmission immediate(Admission admission, RefreshResult result) {
        return new RefreshSubmission(
                admission,
                CompletableFuture.completedFuture(result));
    }

    private static long nanos(Duration duration) {
        try {
            return duration.toNanos();
        } catch (ArithmeticException overflow) {
            return Long.MAX_VALUE;
        }
    }

    private static long nonNegativeDifference(long later, long earlier) {
        if (later <= earlier) {
            return 0L;
        }
        long difference = later - earlier;
        return difference < 0L ? Long.MAX_VALUE : difference;
    }

    private static long saturatedAdd(long left, long right) {
        if (right > 0L && left > Long.MAX_VALUE - right) {
            return Long.MAX_VALUE;
        }
        if (right < 0L && left < Long.MIN_VALUE - right) {
            return Long.MIN_VALUE;
        }
        return left + right;
    }

    @FunctionalInterface
    public interface Scheduler {
        Cancellable schedule(Duration delay, Runnable action);
    }

    @FunctionalInterface
    public interface Cancellable {
        boolean cancel();
    }

    @FunctionalInterface
    public interface Jitter {
        Duration apply(Duration nominalOffset, int attemptIndex);

        static Jitter none() {
            return (offset, ignored) -> offset;
        }

        static Jitter randomTwentyPercent() {
            return (offset, ignored) -> {
                double factor = ThreadLocalRandom.current().nextDouble(0.8d, 1.2d);
                long adjusted = Math.max(0L, Math.round(nanos(offset) * factor));
                return Duration.ofNanos(adjusted);
            };
        }
    }

    private static final class Cycle {
        private ConnectionSnapshot connection;
        private final long enqueuedAt;
        private final long eligibleAt;
        private long revision = 1L;
        private long activeStartedAt = Long.MIN_VALUE;
        private long attemptOriginAt;
        private long deadlineAt = Long.MAX_VALUE;
        private int attemptsDispatched;
        private boolean sawResolvedUnchanged;
        private boolean waitingForRetry;
        private State state = State.DELAYED;
        private Cancellable schedule = NO_SCHEDULE;
        private LookupDispatch lookupDispatch;
        private CompletableFuture<RefreshResult> completion = new CompletableFuture<>();

        private Cycle(ConnectionSnapshot connection, long enqueuedAt, long eligibleAt) {
            this.connection = connection;
            this.enqueuedAt = enqueuedAt;
            this.eligibleAt = eligibleAt;
            this.attemptOriginAt = eligibleAt;
        }
    }

    private enum State {
        DELAYED,
        READY,
        LOOKUP,
        BATCH_QUEUED,
        PUBLISHING,
        TERMINAL
    }

    private static final class LookupDispatch {
        private final Cycle cycle;
        private final long revision;
        private final int attempt;
        private final ConnectionSnapshot connection;
        private final long startedAtNanos;
        private final long expiresAtNanos;
        private final AtomicBoolean settled = new AtomicBoolean();
        private final AtomicBoolean cancellationRequested = new AtomicBoolean();
        private final PendingSchedule timeout = new PendingSchedule();
        private volatile CompletableFuture<?> stage;

        private LookupDispatch(
                Cycle cycle,
                long revision,
                int attempt,
                ConnectionSnapshot connection,
                long startedAtNanos,
                long expiresAtNanos) {
            this.cycle = cycle;
            this.revision = revision;
            this.attempt = attempt;
            this.connection = connection;
            this.startedAtNanos = startedAtNanos;
            this.expiresAtNanos = expiresAtNanos;
        }

        private void attachStage(CompletionStage<?> attached) {
            stage = attached.toCompletableFuture();
            if (cancellationRequested.get()) {
                stage.cancel(true);
            }
        }

        private void cancelStage() {
            cancellationRequested.set(true);
            CompletableFuture<?> attached = stage;
            if (attached != null) {
                attached.cancel(true);
            }
        }
    }

    private static final class PublicationDispatch {
        private final List<PendingPublication> batch;
        private final long expiresAtNanos;
        private final AtomicBoolean settled = new AtomicBoolean();
        private final AtomicBoolean cancellationRequested = new AtomicBoolean();
        private final PendingSchedule timeout = new PendingSchedule();
        private volatile CompletableFuture<?> stage;

        private PublicationDispatch(
                List<PendingPublication> batch,
                long expiresAtNanos) {
            this.batch = List.copyOf(batch);
            this.expiresAtNanos = expiresAtNanos;
        }

        private void attachStage(CompletionStage<?> attached) {
            stage = attached.toCompletableFuture();
            if (cancellationRequested.get()) {
                stage.cancel(true);
            }
        }

        private void cancelStage() {
            cancellationRequested.set(true);
            CompletableFuture<?> attached = stage;
            if (attached != null) {
                attached.cancel(true);
            }
        }
    }

    private record PendingPublication(
            Cycle cycle,
            long revision,
            VerifiedOfficialProfile profile) {
        private PublicationRequest request() {
            return new PublicationRequest(
                    cycle.connection.key(),
                    profile);
        }
    }

    private static final class PendingSchedule implements Cancellable {
        private Cancellable delegate;
        private boolean cancelled;

        private synchronized void attach(Cancellable scheduled) {
            if (cancelled) {
                scheduled.cancel();
            } else {
                delegate = scheduled;
            }
        }

        @Override
        public synchronized boolean cancel() {
            if (cancelled) {
                return false;
            }
            cancelled = true;
            return delegate == null || delegate.cancel();
        }
    }

    private static final class OwnedInfrastructure implements Scheduler, AutoCloseable {
        private final ScheduledThreadPoolExecutor scheduler;

        private OwnedInfrastructure() {
            ThreadFactory threads = runnable -> {
                Thread thread = new Thread(runnable, "nclskins-server-runtime");
                thread.setDaemon(true);
                return thread;
            };
            scheduler = new ScheduledThreadPoolExecutor(1, threads);
            scheduler.setRemoveOnCancelPolicy(true);
            scheduler.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
            scheduler.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);
        }

        @Override
        public Cancellable schedule(Duration delay, Runnable action) {
            Objects.requireNonNull(delay, "delay");
            Objects.requireNonNull(action, "action");
            if (delay.isNegative()) {
                throw new IllegalArgumentException("Delay must not be negative");
            }
            var future = scheduler.schedule(action, nanos(delay), TimeUnit.NANOSECONDS);
            return () -> future.cancel(false);
        }

        @Override
        public void close() {
            scheduler.shutdownNow();
        }
    }
}

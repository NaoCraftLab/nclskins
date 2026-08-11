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
    private final CommitProbe commitProbe;
    private final AutoCloseable ownedInfrastructure;
    private final Map<UUID, Cycle> cycles = new LinkedHashMap<>();
    private final Map<UUID, Long> lastLookupStarts = new LinkedHashMap<>();
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
        this(
                resolver,
                publisher,
                policy,
                nanoTime,
                scheduler,
                jitter,
                CommitProbe.NOOP,
                null);
    }

    ServerAppearanceRefreshCoordinator(
            OfficialProfileResolver resolver,
            BatchAppearancePublisher publisher,
            ServerRefreshPolicy policy,
            LongSupplier nanoTime,
            Scheduler scheduler,
            Jitter jitter,
            CommitProbe commitProbe) {
        this(resolver, publisher, policy, nanoTime, scheduler, jitter, commitProbe, null);
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
                CommitProbe.NOOP,
                infrastructure);
    }

    private ServerAppearanceRefreshCoordinator(
            OfficialProfileResolver resolver,
            BatchAppearancePublisher publisher,
            ServerRefreshPolicy policy,
            LongSupplier nanoTime,
            Scheduler scheduler,
            Jitter jitter,
            CommitProbe commitProbe,
            AutoCloseable ownedInfrastructure) {
        this.resolver = Objects.requireNonNull(resolver, "resolver");
        this.publisher = Objects.requireNonNull(publisher, "publisher");
        this.policy = Objects.requireNonNull(policy, "policy");
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.jitter = Objects.requireNonNull(jitter, "jitter");
        this.commitProbe = Objects.requireNonNull(commitProbe, "commitProbe");
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
        CycleRemoval disconnected = null;
        ConnectionKey publisherFence = null;
        synchronized (lock) {
            if (closed) {
                return immediate(Admission.CLOSED, RefreshResult.CLOSED);
            }
            long now = nanoTime.getAsLong();
            purgeLookupCooldownsLocked(now);
            UUID profileId = connection.key().profileId();
            Cycle existing = cycles.get(profileId);
            if (existing != null && existing.connection.key().equals(connection.key())) {
                previousCompletion = existing.completion;
                existing.completion = new CompletableFuture<>();
                existing.connection = connection;
                existing.revision++;
                existing.sawResolvedUnchanged = false;
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
                Long lastStart = lastLookupStarts.get(profileId);
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
            cancelRemoval(disconnected);
            disconnected.cycle.completion.complete(RefreshResult.DISCONNECTED);
        }
        if (scheduleInitial != null) {
            scheduleAttempt(scheduleInitial, 0);
        }
        pump();
        return new RefreshSubmission(admission, completion);
    }


    public void disconnected(ConnectionKey connection) {
        Objects.requireNonNull(connection, "connection");
        CycleRemoval removed = null;
        synchronized (lock) {
            Cycle candidate = cycles.get(connection.profileId());
            if (candidate != null && candidate.connection.key().equals(connection)) {
                removed = removeCycleLocked(candidate);
            }
        }
        if (removed != null) {
            publisher.supersede(connection);
            cancelRemoval(removed);
            removed.cycle.completion.complete(RefreshResult.DISCONNECTED);
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
        List<CycleRemoval> pending;
        PublicationDispatch publication;
        Cancellable lookupPump;
        Cancellable publicationBatch;
        synchronized (lock) {
            if (closed) {
                return;
            }
            closed = true;
            pending = new ArrayList<>();
            for (Cycle cycle : new ArrayList<>(cycles.values())) {
                pending.add(removeCycleLocked(cycle));
            }
            cycles.clear();
            ready.clear();
            pendingPublications.clear();
            lastLookupStarts.clear();
            lookupPump = pumpSchedule;
            pumpSchedule = NO_SCHEDULE;
            publicationBatch = batchSchedule;
            batchSchedule = NO_SCHEDULE;
            publication = activePublication;
            activePublication = null;
            if (publication != null && publication.settled.compareAndSet(false, true)) {
                publicationsInFlight = 0;
            } else {
                publication = null;
            }
        }
        lookupPump.cancel();
        publicationBatch.cancel();
        if (publication != null) {
            publication.timeout.cancel();
            publication.cancelStage();
        }
        for (CycleRemoval removal : pending) {
            cancelRemoval(removal);
            removal.cycle.completion.complete(RefreshResult.CLOSED);
        }
        if (ownedInfrastructure != null) {
            try {
                ownedInfrastructure.close();
            } catch (Exception ignored) {

            }
        }
    }

    private void scheduleAttempt(Cycle cycle, int attemptIndex) {
        PostCommit action;
        synchronized (lock) {
            if (!isCurrentLocked(cycle)) {
                return;
            }
            cycle.waitingForRetry = attemptIndex > 0;
            action = PostCommit.scheduleAttempt(
                    reserveScheduleLocked(cycle), attemptIndex);
        }
        applyPostCommit(action);
    }

    private void scheduleCycleAt(Cycle cycle, long dueAt) {
        PostCommit action;
        synchronized (lock) {
            if (!isCurrentLocked(cycle)) {
                return;
            }
            long now = nanoTime.getAsLong();
            if (isExpiredLocked(cycle, now)) {
                action = terminalActionLocked(cycle, RefreshResult.EXPIRED);
            } else {
                action = PostCommit.scheduleAt(reserveScheduleLocked(cycle), dueAt);
            }
        }
        applyPostCommit(action);
    }

    private ScheduleReservation reserveScheduleLocked(Cycle cycle) {
        PendingSchedule token = new PendingSchedule();
        Cancellable previous = cycle.schedule;
        cycle.schedule = token;
        cycle.state = State.DELAYED;
        return new ScheduleReservation(cycle, token, previous);
    }

    private void armAttempt(ScheduleReservation reservation, int attemptIndex) {
        Duration nominal = policy.attemptOffsets().get(attemptIndex);
        final Duration offset;
        try {
            offset = Objects.requireNonNull(
                    jitter.apply(nominal, attemptIndex), "jittered attempt offset");
        } catch (RuntimeException invalidJitter) {
            finish(reservation.cycle, RefreshResult.FAILED);
            return;
        }
        if (offset.isNegative()) {
            finish(reservation.cycle, RefreshResult.FAILED);
            return;
        }
        armSchedule(
                reservation,
                saturatedAdd(reservation.cycle.attemptOriginAt, nanos(offset)));
    }

    private void armSchedule(ScheduleReservation reservation, long dueAt) {
        reservation.previous.cancel();
        long delay;
        PostCommit expired = PostCommit.none();
        boolean cancelled = false;
        synchronized (lock) {
            Cycle cycle = reservation.cycle;
            if (!isCurrentLocked(cycle) || cycle.schedule != reservation.token) {
                cancelled = true;
                delay = 0L;
            } else {
                long now = nanoTime.getAsLong();
                if (isExpiredLocked(cycle, now)) {
                    expired = terminalActionLocked(cycle, RefreshResult.EXPIRED);
                    delay = 0L;
                } else {
                    Long lastStart = lastLookupStarts.get(cycle.connection.key().profileId());
                    if (lastStart != null) {
                        dueAt = Math.max(
                                dueAt,
                                saturatedAdd(
                                        lastStart,
                                        nanos(policy.independentCycleCooldown())));
                    }
                    delay = nonNegativeDifference(dueAt, now);
                }
            }
        }
        if (cancelled) {
            reservation.token.cancel();
            return;
        }
        if (expired.kind != PostCommitKind.NONE) {
            applyPostCommit(expired);
            return;
        }
        Cancellable scheduled;
        try {
            scheduled = Objects.requireNonNull(
                    scheduler.schedule(
                            Duration.ofNanos(delay),
                            () -> makeReady(reservation.cycle, reservation.token)),
                    "scheduled attempt");
        } catch (RuntimeException rejected) {
            finish(reservation.cycle, RefreshResult.FAILED);
            return;
        }
        reservation.token.attach(scheduled);
        boolean cancelToken;
        synchronized (lock) {
            cancelToken = !isCurrentLocked(reservation.cycle)
                    || reservation.cycle.schedule != reservation.token;
        }
        if (cancelToken) {
            reservation.token.cancel();
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
                }
                UUID profileId = cycle.connection.key().profileId();
                lastLookupStarts.remove(profileId);
                lastLookupStarts.put(profileId, now);
                while (lastLookupStarts.size() > policy.maxPendingConnections()) {
                    Iterator<UUID> oldest = lastLookupStarts.keySet().iterator();
                    oldest.next();
                    oldest.remove();
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
        commitProbe.beforeCommit(CommitKind.LOOKUP, dispatch.revision);
        PostCommit action;
        synchronized (lock) {
            if (dispatch.cycle.lookupDispatch == dispatch) {
                dispatch.cycle.lookupDispatch = null;
            }
            if (lookupsInFlight <= 0) {
                throw new IllegalStateException("Lookup slot accounting underflow");
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
            if (!isCurrentLocked(dispatch.cycle)) {
                action = PostCommit.none();
            } else if (dispatch.cycle.revision != dispatch.revision) {
                action = coalescedFollowUpActionLocked(dispatch.cycle);
            } else {
                action = switch (safe.status()) {
                    case REJECTED -> terminalActionLocked(
                            dispatch.cycle, RefreshResult.REJECTED);
                    case TRANSIENT_FAILURE, THROTTLED -> retryOrFinishActionLocked(
                            dispatch.cycle, RefreshResult.EXHAUSTED, completedAt);
                    case RESOLVED -> resolvedActionLocked(
                            dispatch, safe.profile().orElseThrow());
                };
            }
        }
        applyPostCommit(action);
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

    private PostCommit resolvedActionLocked(
            LookupDispatch dispatch,
            VerifiedOfficialProfile profile) {
        if (!dispatch.connection.identity().equals(profile.identity())) {
            return terminalActionLocked(dispatch.cycle, RefreshResult.REJECTED);
        }
        PendingPublication pending = new PendingPublication(
                dispatch.cycle,
                dispatch.revision,
                profile);
        pendingPublications.put(dispatch.connection.key().profileId(), pending);
        dispatch.cycle.state = State.BATCH_QUEUED;
        if (publicationsInFlight == 0 && batchSchedule == NO_SCHEDULE) {
            PendingSchedule token = new PendingSchedule();
            batchSchedule = token;
            return PostCommit.scheduleBatch(token);
        }
        return PostCommit.none();
    }

    private void scheduleBatch() {
        PendingSchedule token;
        synchronized (lock) {
            if (closed || publicationsInFlight != 0 || pendingPublications.isEmpty()
                    || batchSchedule != NO_SCHEDULE) {
                return;
            }
            token = new PendingSchedule();
            batchSchedule = token;
        }
        armBatch(token);
    }

    private void armBatch(PendingSchedule token) {
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
        boolean cancelToken;
        synchronized (lock) {
            cancelToken = batchSchedule != token;
        }
        if (cancelToken) {
            token.cancel();
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
            scheduleCoalescedFollowUp(cycle);
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
            PublicationOutcome outcome = !failure && result != null
                    ? result.outcome(pending.cycle.connection.key())
                            .orElse(PublicationOutcome.FAILED)
                    : PublicationOutcome.FAILED;
            applyPostCommit(commitPublicationOutcome(pending, outcome));
        }
        scheduleBatch();
        pump();
    }

    private PostCommit commitPublicationOutcome(
            PendingPublication pending,
            PublicationOutcome outcome) {
        commitProbe.beforeCommit(CommitKind.PUBLICATION, pending.revision);
        synchronized (lock) {
            if (!isCurrentLocked(pending.cycle)) {
                return PostCommit.none();
            }
            long now = nanoTime.getAsLong();
            if (isExpiredLocked(pending.cycle, now)) {
                return terminalActionLocked(pending.cycle, RefreshResult.EXPIRED);
            }
            if (pending.cycle.revision != pending.revision) {
                return coalescedFollowUpActionLocked(pending.cycle);
            }
            return switch (outcome) {
                case UPDATED -> terminalActionLocked(
                        pending.cycle, RefreshResult.UPDATED);
                case UNCHANGED -> {
                    pending.cycle.sawResolvedUnchanged = true;
                    yield retryOrFinishActionLocked(
                            pending.cycle, RefreshResult.UNCHANGED, now);
                }
                case STALE -> terminalActionLocked(
                        pending.cycle, RefreshResult.STALE_CONNECTION);
                case FAILED -> terminalActionLocked(
                        pending.cycle, RefreshResult.FAILED);
            };
        }
    }

    private void scheduleCoalescedFollowUp(Cycle cycle) {
        PostCommit action;
        synchronized (lock) {
            if (!isCurrentLocked(cycle)) {
                return;
            }
            action = coalescedFollowUpActionLocked(cycle);
        }
        applyPostCommit(action);
    }

    private PostCommit coalescedFollowUpActionLocked(Cycle cycle) {
        long now = nanoTime.getAsLong();
        if (isExpiredLocked(cycle, now)) {
            return terminalActionLocked(cycle, RefreshResult.EXPIRED);
        }
        Long lastStart = lastLookupStarts.get(cycle.connection.key().profileId());
        long cooldownEnds = lastStart == null
                ? now
                : saturatedAdd(lastStart, nanos(policy.independentCycleCooldown()));
        cycle.waitingForRetry = true;
        return PostCommit.scheduleAt(
                reserveScheduleLocked(cycle), Math.max(now, cooldownEnds));
    }

    private PostCommit retryOrFinishActionLocked(
            Cycle cycle,
            RefreshResult terminal,
            long now) {
        if (isExpiredLocked(cycle, now)) {
            return terminalActionLocked(cycle, RefreshResult.EXPIRED);
        }
        int nextAttempt = cycle.attemptsDispatched;
        if (nextAttempt >= policy.attemptOffsets().size()) {
            if (cycle.sawResolvedUnchanged && terminal == RefreshResult.EXHAUSTED) {
                terminal = RefreshResult.UNCHANGED;
            }
            return terminalActionLocked(cycle, terminal);
        }
        cycle.waitingForRetry = true;
        return PostCommit.scheduleAttempt(
                reserveScheduleLocked(cycle), nextAttempt);
    }

    private void finish(Cycle cycle, RefreshResult result) {
        PostCommit action;
        synchronized (lock) {
            if (!isCurrentLocked(cycle)) {
                return;
            }
            action = terminalActionLocked(cycle, result);
        }
        applyPostCommit(action);
    }

    private PostCommit terminalActionLocked(Cycle cycle, RefreshResult result) {
        CompletableFuture<RefreshResult> completion = cycle.completion;
        CycleRemoval removal = removeCycleLocked(cycle);
        if (result == RefreshResult.EXPIRED) {
            expiredCount++;
        }
        return PostCommit.complete(removal, completion, result);
    }

    private CycleRemoval removeCycleLocked(Cycle cycle) {
        UUID profileId = cycle.connection.key().profileId();
        if (cycles.get(profileId) == cycle) {
            cycles.remove(profileId);
        }
        LookupDispatch dispatch = cycle.lookupDispatch;
        cycle.lookupDispatch = null;
        if (dispatch != null && dispatch.settled.compareAndSet(false, true)) {
            if (lookupsInFlight <= 0) {
                throw new IllegalStateException("Lookup slot accounting underflow");
            }
            lookupsInFlight--;
        } else {
            dispatch = null;
        }
        pendingPublications.remove(profileId);
        ready.removeIf(candidate -> candidate == cycle);
        Cancellable schedule = cycle.schedule;
        cycle.schedule = NO_SCHEDULE;
        cycle.state = State.TERMINAL;
        return new CycleRemoval(cycle, schedule, dispatch);
    }

    private void cancelRemoval(CycleRemoval removal) {
        removal.schedule.cancel();
        if (removal.lookup != null) {
            removal.lookup.timeout.cancel();
            removal.lookup.cancelStage();
        }
    }

    private void failPendingPublications() {
        List<PendingPublication> pending;
        synchronized (lock) {
            batchSchedule = NO_SCHEDULE;
            pending = new ArrayList<>(pendingPublications.values());
            pendingPublications.clear();
        }
        for (PendingPublication item : pending) {
            applyPostCommit(commitPublicationOutcome(item, PublicationOutcome.FAILED));
        }
        pump();
    }

    private void applyPostCommit(PostCommit action) {
        switch (action.kind) {
            case NONE -> {

            }
            case SCHEDULE_AT -> armSchedule(action.schedule, action.dueAt);
            case SCHEDULE_ATTEMPT -> armAttempt(action.schedule, action.attemptIndex);
            case SCHEDULE_BATCH -> armBatch(action.batchToken);
            case COMPLETE -> {
                cancelRemoval(action.removal);
                action.completion.complete(action.result);
            }
            default -> throw new AssertionError("Unhandled post-commit action");
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

    private void purgeLookupCooldownsLocked(long now) {
        long retention = nanos(policy.independentCycleCooldown());
        lastLookupStarts.entrySet().removeIf(
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

    enum CommitKind {
        LOOKUP,
        PUBLICATION
    }

    @FunctionalInterface
    interface CommitProbe {
        CommitProbe NOOP = (kind, revision) -> {};

        void beforeCommit(CommitKind kind, long revision);
    }

    private enum PostCommitKind {
        NONE,
        SCHEDULE_AT,
        SCHEDULE_ATTEMPT,
        SCHEDULE_BATCH,
        COMPLETE
    }

    private static final class PostCommit {
        private static final PostCommit NONE = new PostCommit(
                PostCommitKind.NONE, null, 0L, -1, null, null, null, null);

        private final PostCommitKind kind;
        private final ScheduleReservation schedule;
        private final long dueAt;
        private final int attemptIndex;
        private final PendingSchedule batchToken;
        private final CycleRemoval removal;
        private final CompletableFuture<RefreshResult> completion;
        private final RefreshResult result;

        private PostCommit(
                PostCommitKind kind,
                ScheduleReservation schedule,
                long dueAt,
                int attemptIndex,
                PendingSchedule batchToken,
                CycleRemoval removal,
                CompletableFuture<RefreshResult> completion,
                RefreshResult result) {
            this.kind = kind;
            this.schedule = schedule;
            this.dueAt = dueAt;
            this.attemptIndex = attemptIndex;
            this.batchToken = batchToken;
            this.removal = removal;
            this.completion = completion;
            this.result = result;
        }

        private static PostCommit none() {
            return NONE;
        }

        private static PostCommit scheduleAt(
                ScheduleReservation schedule,
                long dueAt) {
            return new PostCommit(
                    PostCommitKind.SCHEDULE_AT,
                    schedule,
                    dueAt,
                    -1,
                    null,
                    null,
                    null,
                    null);
        }

        private static PostCommit scheduleAttempt(
                ScheduleReservation schedule,
                int attemptIndex) {
            return new PostCommit(
                    PostCommitKind.SCHEDULE_ATTEMPT,
                    schedule,
                    0L,
                    attemptIndex,
                    null,
                    null,
                    null,
                    null);
        }

        private static PostCommit scheduleBatch(PendingSchedule batchToken) {
            return new PostCommit(
                    PostCommitKind.SCHEDULE_BATCH,
                    null,
                    0L,
                    -1,
                    batchToken,
                    null,
                    null,
                    null);
        }

        private static PostCommit complete(
                CycleRemoval removal,
                CompletableFuture<RefreshResult> completion,
                RefreshResult result) {
            return new PostCommit(
                    PostCommitKind.COMPLETE,
                    null,
                    0L,
                    -1,
                    null,
                    removal,
                    completion,
                    result);
        }
    }

    private record ScheduleReservation(
            Cycle cycle,
            PendingSchedule token,
            Cancellable previous) {}

    private record CycleRemoval(
            Cycle cycle,
            Cancellable schedule,
            LookupDispatch lookup) {}

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

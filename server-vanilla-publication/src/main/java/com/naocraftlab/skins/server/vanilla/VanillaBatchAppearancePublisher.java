package com.naocraftlab.skins.server.vanilla;

import com.naocraftlab.skins.server.BatchAppearancePublisher;
import com.naocraftlab.skins.server.BatchPublicationResult;
import com.naocraftlab.skins.server.ConnectionKey;
import com.naocraftlab.skins.server.OfficialTextureSignatureVerifier;
import com.naocraftlab.skins.server.PublicationMetrics;
import com.naocraftlab.skins.server.PublicationOutcome;
import com.naocraftlab.skins.server.PublicationRequest;
import com.naocraftlab.skins.server.SignedTexturesProperty;
import com.naocraftlab.skins.server.TextureAppearance;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.function.BooleanSupplier;


public final class VanillaBatchAppearancePublisher
        implements BatchAppearancePublisher, AutoCloseable {
    private static final long ESTIMATED_PROFILE_ENTRY_BYTES = 2_048L;
    private static final long ESTIMATED_PACKET_PAIR_OVERHEAD_BYTES = 128L;

    private final Object lock = new Object();
    private final ConnectionRegistry connections;
    private final ProfileAccess profiles;
    private final TrackingAccess tracking;
    private final PlayerInfoTransport transport;
    private final PlatformScheduler scheduler;
    private final OfficialTextureSignatureVerifier signatureVerifier;
    private final VanillaPublicationPolicy policy;
    private final ExecutorService semanticWorker;
    private final ArrayDeque<PublicationJob> jobs = new ArrayDeque<>();
    private final Map<ConnectionKey, Long> latestIntentRevisions = new LinkedHashMap<>();
    private final Map<ConnectionKey, Integer> outstandingIntentJobs = new LinkedHashMap<>();
    private long nextIntentRevision;
    private boolean active;
    private volatile boolean advancingPlatformJob;
    private boolean nextTickScheduled;
    private long budgetTickId = Long.MIN_VALUE;
    private TickBudget budgetForTick;
    private boolean closed;

    public VanillaBatchAppearancePublisher(
            ConnectionRegistry connections,
            ProfileAccess profiles,
            TrackingAccess tracking,
            PlayerInfoTransport transport,
            PlatformScheduler scheduler,
            OfficialTextureSignatureVerifier signatureVerifier,
            VanillaPublicationPolicy policy) {
        this.connections = Objects.requireNonNull(connections, "connections");
        this.profiles = Objects.requireNonNull(profiles, "profiles");
        this.tracking = Objects.requireNonNull(tracking, "tracking");
        this.transport = Objects.requireNonNull(transport, "transport");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.signatureVerifier = Objects.requireNonNull(signatureVerifier, "signatureVerifier");
        this.policy = Objects.requireNonNull(policy, "policy");
        ThreadFactory threads = action -> {
            Thread thread = new Thread(action, "nclskins-publication-planner");
            thread.setDaemon(true);
            return thread;
        };
        semanticWorker = Executors.newSingleThreadExecutor(threads);
    }

    @Override
    public CompletionStage<BatchPublicationResult> publishBatch(
            List<PublicationRequest> requests) {
        Objects.requireNonNull(requests, "requests");
        List<PublicationRequest> batch = deduplicate(requests);
        if (batch.isEmpty()) {
            return CompletableFuture.completedFuture(BatchPublicationResult.of(Map.of()));
        }
        PublicationJob job;
        boolean start;
        synchronized (lock) {
            if (closed) {
                return CompletableFuture.completedFuture(
                        BatchPublicationResult.all(batch, PublicationOutcome.FAILED));
            }
            if (nextIntentRevision > Long.MAX_VALUE - batch.size()) {
                return CompletableFuture.completedFuture(
                        BatchPublicationResult.all(batch, PublicationOutcome.FAILED));
            }
            Map<ConnectionKey, Long> revisions = new LinkedHashMap<>();
            for (PublicationRequest request : batch) {
                long revision = ++nextIntentRevision;
                latestIntentRevisions.put(request.connection(), revision);
                outstandingIntentJobs.merge(request.connection(), 1, Integer::sum);
                revisions.put(request.connection(), revision);
            }
            job = new PublicationJob(batch, revisions);
            jobs.addLast(job);
            start = !active;
            if (start) {
                active = true;
            }
        }
        job.completion.whenComplete((ignored, failure) -> {
            if (job.completion.isCancelled()) {
                cancel(job);
            }
        });
        if (start) {
            execute(this::advanceActiveJob);
        }
        return job.completion;
    }

    @Override
    public void supersede(ConnectionKey connection) {
        ConnectionKey key = Objects.requireNonNull(connection, "connection");
        synchronized (lock) {
            if (closed || !outstandingIntentJobs.containsKey(key)) {
                return;
            }
            if (nextIntentRevision == Long.MAX_VALUE) {

                latestIntentRevisions.remove(key);
            } else {
                latestIntentRevisions.put(key, ++nextIntentRevision);
            }
        }
    }

    @Override
    public void close() {
        if (!scheduler.isPlatformThread()) {
            throw new IllegalStateException("Publisher must close on the platform thread");
        }
        List<PublicationJob> pending;
        synchronized (lock) {
            if (closed) {
                return;
            }
            closed = true;
            pending = new ArrayList<>(jobs);
            jobs.clear();
            latestIntentRevisions.clear();
            outstandingIntentJobs.clear();
            active = false;
            nextTickScheduled = false;
        }
        semanticWorker.shutdownNow();
        for (PublicationJob job : pending) {
            job.cancelled = true;
            job.abortAndRetrackForClose();
            job.completeRemaining(PublicationOutcome.FAILED);
        }
    }

    private void advanceActiveJob() {
        if (!scheduler.isPlatformThread()) {
            execute(this::advanceActiveJob);
            return;
        }
        PublicationJob job;
        synchronized (lock) {
            if (closed) {
                return;
            }
            job = jobs.peekFirst();
            if (job == null) {
                active = false;
                return;
            }
        }

        long tickId = scheduler.tickId();
        TickBudget budget = budgetFor(tickId);
        NextAction next = NextAction.NEXT_TICK;
        advancingPlatformJob = true;
        try {
            if (job.cancelled) {
                next = job.abortAndRetrack(budget)
                        ? NextAction.FINISH
                        : NextAction.NEXT_TICK;
            } else {
                while (budget.canRunOperation(scheduler.nanoTime())) {
                    if (job.cancelled) {
                        next = job.abortAndRetrack(budget)
                                ? NextAction.FINISH
                                : NextAction.NEXT_TICK;
                        break;
                    }
                    if (job.slice == null) {
                        if (!job.hasRemaining()) {
                            next = NextAction.FINISH;
                            break;
                        }
                        job.slice = job.nextSlice(policy.maxBatchActors());
                    }
                    AdvanceResult result = job.slice.advance(budget, () -> job.cancelled);
                    if (result == AdvanceResult.COMPLETE) {
                        job.outcomes.putAll(job.slice.outcomes);
                        job.slice = null;
                        continue;
                    }
                    if (result == AdvanceResult.WAITING) {
                        next = NextAction.WAITING;
                    } else if (result == AdvanceResult.CANCELLED) {
                        next = job.abortAndRetrack(budget)
                                ? NextAction.FINISH
                                : NextAction.NEXT_TICK;
                    }
                    break;
                }
            }
        } catch (RuntimeException | Error unexpectedPlatformFailure) {
            job.cancelled = true;
            next = job.abortAndRetrack(budget)
                    ? NextAction.FINISH
                    : NextAction.NEXT_TICK;
        } finally {
            advancingPlatformJob = false;
            job.metrics.recordPlatformTick(
                    tickId, budget.elapsed(scheduler.nanoTime()));
        }

        switch (next) {
            case FINISH -> finish(job);
            case NEXT_TICK -> scheduleNextTick(job);
            case WAITING -> {

            }
            default -> throw new AssertionError("Unhandled publication continuation");
        }
    }

    private TickBudget budgetFor(long tickId) {
        if (budgetForTick == null || budgetTickId != tickId) {
            budgetTickId = tickId;
            budgetForTick = new TickBudget(
                    policy.maxRecipientProfileDeliveriesPerTick(),
                    policy.maxPlatformThreadTimePerTick().toNanos(),
                    scheduler.nanoTime());
        }
        return budgetForTick;
    }

    private void semanticCompleted(
            PublicationSlice slice,
            List<SemanticDecision> decisions) {
        if (!scheduler.isPlatformThread()) {
            execute(() -> semanticCompleted(slice, decisions));
            return;
        }
        PublicationJob current;
        synchronized (lock) {
            current = jobs.peekFirst();
            if (closed || current == null || current.slice != slice || current.cancelled) {
                return;
            }
        }
        slice.applySemanticDecisions(decisions);
        scheduleNextTick(current);
    }

    private void cancel(PublicationJob job) {
        boolean wakeWaitingSemantic;
        synchronized (lock) {
            job.cancelled = true;
            boolean head = jobs.peekFirst() == job;
            if (!head) {
                if (jobs.remove(job)) {
                    releaseIntentRevisions(job);
                }
            }
            wakeWaitingSemantic = head
                    && !advancingPlatformJob
                    && job.waitingForSemantic();
        }
        if (wakeWaitingSemantic) {
            scheduleNextTick(job);
        }
    }

    private void releaseIntentRevisions(PublicationJob job) {
        for (Map.Entry<ConnectionKey, Long> entry : job.intentRevisions.entrySet()) {
            ConnectionKey key = entry.getKey();
            Integer count = outstandingIntentJobs.get(key);
            if (count == null || count <= 1) {
                outstandingIntentJobs.remove(key);
                latestIntentRevisions.remove(key);
            } else {
                outstandingIntentJobs.put(key, count - 1);
                latestIntentRevisions.remove(key, entry.getValue());
            }
        }
    }

    private void finish(PublicationJob job) {
        job.fillUnresolved(PublicationOutcome.FAILED);
        PublicationJob next;
        synchronized (lock) {
            PublicationJob removed = jobs.pollFirst();
            if (removed != job) {
                job.completeRemaining(PublicationOutcome.FAILED);
                active = false;
                return;
            }
            releaseIntentRevisions(job);
            next = jobs.peekFirst();
            if (next == null) {
                active = false;
            }
        }
        if (!job.completion.isCancelled()) {
            job.completion.complete(BatchPublicationResult.of(
                    job.outcomes, job.metrics.snapshot()));
        }
        if (next != null) {
            scheduleNextTick(next);
        }
    }

    private void scheduleNextTick(PublicationJob job) {
        synchronized (lock) {
            if (closed || nextTickScheduled) {
                return;
            }
            nextTickScheduled = true;
        }
        try {
            scheduler.nextTick(() -> {
                synchronized (lock) {
                    nextTickScheduled = false;
                }
                advanceActiveJob();
            });
        } catch (RuntimeException rejected) {
            synchronized (lock) {
                nextTickScheduled = false;
            }
            job.cancelled = true;
            if (scheduler.isPlatformThread()) {
                if (job.abortAndRetrack(TickBudget.unlimited())) {
                    finish(job);
                }
            } else {
                schedulingRejected();
            }


        }
    }

    private void execute(Runnable action) {
        try {
            scheduler.execute(Objects.requireNonNull(action, "action"));
        } catch (RuntimeException rejected) {
            schedulingRejected();
        }
    }

    private void schedulingRejected() {
        if (scheduler.isPlatformThread()) {
            failAllJobsOnPlatform();
            return;
        }
        synchronized (lock) {
            boolean cleanupRequired = jobs.stream()
                    .anyMatch(PublicationJob::hasCleanupObligations);
            if (cleanupRequired) {
                for (PublicationJob job : jobs) {
                    job.cancelled = true;
                }


                return;
            }
        }
        failAllJobsWithoutCleanup();
    }

    private void failAllJobsOnPlatform() {
        PublicationJob head;
        synchronized (lock) {
            for (PublicationJob job : jobs) {
                job.cancelled = true;
            }
            if (advancingPlatformJob) {
                return;
            }
            head = jobs.peekFirst();
        }
        if (head != null && !head.abortAndRetrack(TickBudget.unlimited())) {


            return;
        }
        failAllJobsWithoutCleanup();
    }

    private void failAllJobsWithoutCleanup() {
        List<PublicationJob> failed;
        synchronized (lock) {
            failed = new ArrayList<>(jobs);
            jobs.clear();
            latestIntentRevisions.clear();
            outstandingIntentJobs.clear();
            active = false;
            nextTickScheduled = false;
        }
        for (PublicationJob job : failed) {
            job.completeRemaining(PublicationOutcome.FAILED);
        }
    }

    private final class PublicationJob {
        private final List<PublicationRequest> requests;
        private final Map<ConnectionKey, Long> intentRevisions;
        private final Map<ConnectionKey, PublicationOutcome> outcomes = new LinkedHashMap<>();
        private final CompletableFuture<BatchPublicationResult> completion = new CompletableFuture<>();
        private final MetricsAccumulator metrics = new MetricsAccumulator();
        private volatile boolean cancelled;
        private int index;
        private PublicationSlice slice;

        private PublicationJob(
                List<PublicationRequest> requests,
                Map<ConnectionKey, Long> intentRevisions) {
            this.requests = requests;
            this.intentRevisions = Map.copyOf(intentRevisions);
        }

        private boolean hasRemaining() {
            return index < requests.size();
        }

        private boolean hasCleanupObligations() {
            return slice != null && (!slice.removedPairs.isEmpty()
                    || !slice.reconciledWatcherRefreshes.isEmpty());
        }

        private boolean waitingForSemantic() {
            return slice != null && slice.phase == Phase.WAIT_SEMANTIC;
        }

        private boolean isLatest(PublicationRequest request) {
            synchronized (lock) {
                return isLatestUnderLock(request);
            }
        }

        private boolean installIfLatest(PublicationRequest request) {
            synchronized (lock) {
                if (!isLatestUnderLock(request)) {
                    return false;
                }


                profiles.install(request);
                return true;
            }
        }

        private boolean isLatestUnderLock(PublicationRequest request) {
            Long ownRevision = intentRevisions.get(request.connection());
            return ownRevision != null
                    && ownRevision.equals(latestIntentRevisions.get(request.connection()));
        }

        private PublicationSlice nextSlice(int maximum) {
            int end = Math.min(requests.size(), index + maximum);
            PublicationSlice result = new PublicationSlice(
                    this, requests.subList(index, end), metrics);
            index = end;
            return result;
        }

        private boolean abortAndRetrack(TickBudget budget) {
            if (slice == null) {
                return true;
            }
            slice.abort();
            final AdvanceResult result;
            try {
                result = slice.advance(Objects.requireNonNull(budget, "budget"), () -> false);
            } catch (RuntimeException | Error cleanupFailure) {
                return false;
            }
            if (result != AdvanceResult.COMPLETE) {
                return false;
            }
            outcomes.putAll(slice.outcomes);
            slice = null;
            return true;
        }

        private void abortAndRetrackForClose() {
            if (slice == null) {
                return;
            }
            slice.abort();
            int passes = Math.max(2, policy.maxReconciliationAttempts() + 1);
            for (int pass = 0; pass < passes && slice.hasCleanupObligations(); pass++) {
                try {
                    slice.advance(TickBudget.unlimited(), () -> false);
                } catch (RuntimeException | Error ignored) {

                }
            }
            outcomes.putAll(slice.outcomes);
            slice = null;
        }

        private void completeRemaining(PublicationOutcome outcome) {
            fillUnresolved(outcome);
            if (!completion.isCancelled()) {
                completion.complete(BatchPublicationResult.of(outcomes, metrics.snapshot()));
            }
        }

        private void fillUnresolved(PublicationOutcome outcome) {
            if (slice != null) {
                for (PublicationRequest request : slice.requests) {
                    outcomes.putIfAbsent(request.connection(), outcome);
                }
            }
            for (PublicationRequest request : requests) {
                outcomes.putIfAbsent(request.connection(), outcome);
            }
        }
    }

    private final class PublicationSlice {
        private final PublicationJob owner;
        private final List<PublicationRequest> requests;
        private final MetricsAccumulator metrics;
        private final Map<ConnectionKey, PublicationOutcome> outcomes = new LinkedHashMap<>();
        private final List<ActorState> actors = new ArrayList<>();
        private final ArrayDeque<TrackingPair> removedPairs = new ArrayDeque<>();
        private final ArrayDeque<FailedDelivery> reconciliation = new ArrayDeque<>();
        private final Set<WatcherPairKey> originalWatcherPairs = new LinkedHashSet<>();
        private final ArrayDeque<WatcherRefresh> reconciledWatcherRefreshes = new ArrayDeque<>();
        private final Set<WatcherPairKey> pendingWatcherRefreshes = new LinkedHashSet<>();
        private volatile Phase phase = Phase.PREPARE;
        private int requestIndex;
        private int actorIndex;
        private int observerIndex;
        private List<ConnectionKey> watcherRecipients = List.of();
        private List<ConnectionKey> otherRecipients = List.of();
        private List<ConnectionKey> recipients = List.of();
        private int recipientIndex;
        private int deliveryActorIndex;
        private int reconciliationDeliveries;
        private boolean semanticDispatched;
        private boolean aborted;

        private PublicationSlice(
                PublicationJob owner,
                List<PublicationRequest> requests,
                MetricsAccumulator metrics) {
            this.owner = owner;
            this.requests = List.copyOf(requests);
            this.metrics = metrics;
        }

        private AdvanceResult advance(
                TickBudget budget,
                BooleanSupplier cancellation) {
            while (budget.canRunOperation(scheduler.nanoTime())) {
                if (cancellation.getAsBoolean()) {
                    return AdvanceResult.CANCELLED;
                }
                switch (phase) {
                    case PREPARE -> prepareOne(budget);
                    case SEMANTIC -> {
                        if (dispatchSemanticComparison()) {
                            return AdvanceResult.WAITING;
                        }
                    }
                    case WAIT_SEMANTIC -> {
                        return AdvanceResult.WAITING;
                    }
                    case SNAPSHOT -> snapshotOne(budget);
                    case UNTRACK -> untrackOne(budget);
                    case INSTALL -> installOne(budget);
                    case DELIVERY_SETUP -> setupDelivery(budget);
                    case DELIVER_WATCHERS -> {
                        if (!deliverOne(budget, Phase.RETRACK)) {
                            return AdvanceResult.YIELD;
                        }
                    }
                    case RETRACK -> {
                        if (!retrackPass(budget)) {
                            return AdvanceResult.YIELD;
                        }
                    }
                    case DELIVER_OTHERS -> {
                        if (!deliverOne(budget, Phase.RECONCILE)) {
                            return AdvanceResult.YIELD;
                        }
                    }
                    case RECONCILE -> {
                        if (!reconcilePass(budget)) {
                            return AdvanceResult.YIELD;
                        }
                    }
                    case REFRESH_RECONCILED_WATCHERS -> {
                        if (!refreshReconciledWatcherOne(budget)) {
                            return AdvanceResult.YIELD;
                        }
                    }
                    case RETRACK_RECONCILED_WATCHERS -> {
                        if (!retrackPass(budget)) {
                            return AdvanceResult.YIELD;
                        }
                    }
                    case COMPLETE -> {
                        return AdvanceResult.COMPLETE;
                    }
                    default -> throw new AssertionError("Unhandled publication phase");
                }
            }
            return phase == Phase.COMPLETE ? AdvanceResult.COMPLETE : AdvanceResult.YIELD;
        }

        private void prepareOne(TickBudget budget) {
            if (requestIndex >= requests.size()) {
                phase = Phase.SEMANTIC;
                return;
            }
            PublicationRequest request = requests.get(requestIndex++);
            try {
                if (!owner.isLatest(request)) {
                    outcomes.put(request.connection(), PublicationOutcome.STALE);
                } else if (!connections.isCurrent(request)) {
                    outcomes.put(request.connection(), PublicationOutcome.STALE);
                } else {
                    LiveProfileTextures current = Objects.requireNonNull(
                            profiles.captureCurrent(request), "live profile textures");
                    Optional<SignedTexturesProperty> target = request.profile().textures();
                    boolean exactMatch = target.isEmpty()
                            ? current.status() == LiveProfileTextures.Status.ACCOUNT_DEFAULT
                            : current.containsSameProperty(target.orElseThrow());
                    if (exactMatch) {
                        outcomes.put(request.connection(), PublicationOutcome.UNCHANGED);
                    } else {
                        actors.add(new ActorState(request, current));
                    }
                }
            } catch (RuntimeException | Error failure) {
                outcomes.put(request.connection(), PublicationOutcome.FAILED);
            }
            budget.operationCompleted();
        }


        private boolean dispatchSemanticComparison() {
            if (semanticDispatched) {
                phase = Phase.WAIT_SEMANTIC;
                return true;
            }
            List<SemanticWork> work = new ArrayList<>();
            for (ActorState actor : actors) {
                if (actor.current.status() == LiveProfileTextures.Status.SIGNED) {
                    work.add(new SemanticWork(
                            actor.request,
                            actor.current.property().orElseThrow()));
                }
            }
            if (work.isEmpty()) {
                phase = Phase.SNAPSHOT;
                actorIndex = 0;
                return false;
            }
            semanticDispatched = true;
            phase = Phase.WAIT_SEMANTIC;
            try {
                semanticWorker.execute(() -> {
                    List<SemanticDecision> decisions = new ArrayList<>(work.size());
                    for (SemanticWork item : work) {
                        boolean matches = false;
                        try {
                            Optional<TextureAppearance> current = signatureVerifier.verify(
                                    item.property,
                                    item.request.profile().identity());
                            matches = current != null
                                    && current.isPresent()
                                    && current.orElseThrow().equals(
                                            item.request.profile().appearance());
                        } catch (RuntimeException verificationFailure) {

                        }
                        decisions.add(new SemanticDecision(
                                item.request.connection(), matches));
                    }
                    execute(() -> semanticCompleted(this, List.copyOf(decisions)));
                });
                return true;
            } catch (RuntimeException rejected) {
                phase = Phase.SNAPSHOT;
                actorIndex = 0;
                return false;
            }
        }

        private void applySemanticDecisions(List<SemanticDecision> decisions) {
            if (phase != Phase.WAIT_SEMANTIC || aborted) {
                return;
            }
            Map<ConnectionKey, Boolean> matches = new LinkedHashMap<>();
            for (SemanticDecision decision : decisions) {
                matches.put(decision.connection, decision.matches);
            }
            for (ActorState actor : actors) {
                if (Boolean.TRUE.equals(matches.get(actor.request.connection()))) {
                    actor.unchanged = true;
                    outcomes.put(actor.request.connection(), PublicationOutcome.UNCHANGED);
                }
            }
            phase = Phase.SNAPSHOT;
            actorIndex = 0;
        }

        private void snapshotOne(TickBudget budget) {
            if (actorIndex >= actors.size()) {
                phase = Phase.UNTRACK;
                actorIndex = 0;
                observerIndex = 0;
                return;
            }
            ActorState actor = actors.get(actorIndex++);
            if (!actor.ready()) {
                return;
            }
            try {
                if (!owner.isLatest(actor.request)
                        || !connections.isCurrent(actor.request)) {
                    actor.stale(outcomes);
                } else {
                    LinkedHashSet<ConnectionKey> unique = new LinkedHashSet<>();
                    for (ConnectionKey observer : tracking.snapshotObservers(actor.request)) {
                        ConnectionKey checked = Objects.requireNonNull(
                                observer, "tracking observer");
                        if (!sameProfile(checked, actor.request.connection())) {
                            unique.add(checked);
                        }
                    }
                    actor.observers = List.copyOf(unique);
                    for (ConnectionKey observer : actor.observers) {
                        originalWatcherPairs.add(new WatcherPairKey(
                                actor.request.connection(), observer));
                    }
                }
            } catch (RuntimeException | Error failure) {
                actor.failed(outcomes);
            }
            budget.operationCompleted();
        }

        private void untrackOne(TickBudget budget) {
            while (actorIndex < actors.size()) {
                ActorState actor = actors.get(actorIndex);
                if (!actor.ready()) {
                    nextActorObservers();
                    continue;
                }
                try {
                    if (!owner.isLatest(actor.request)
                            || !connections.isCurrent(actor.request)) {
                        actor.stale(outcomes);
                        nextActorObservers();
                    } else if (observerIndex >= actor.observers.size()) {
                        nextActorObservers();
                        continue;
                    } else {
                        ConnectionKey observer = actor.observers.get(observerIndex++);
                        if (connections.isCurrent(observer)
                                && tracking.untrack(actor.request, observer)) {
                            removedPairs.addLast(new TrackingPair(actor.request, observer));
                            metrics.watcherPair();
                        }
                    }
                } catch (RuntimeException | Error failure) {
                    actor.failed(outcomes);
                    nextActorObservers();
                }
                budget.operationCompleted();
                return;
            }
            phase = Phase.INSTALL;
            actorIndex = 0;
        }

        private void installOne(TickBudget budget) {
            if (actorIndex >= actors.size()) {
                phase = Phase.DELIVERY_SETUP;
                return;
            }
            ActorState actor = actors.get(actorIndex++);
            if (!actor.ready()) {
                return;
            }
            try {
                if (!connections.isCurrent(actor.request)) {
                    actor.stale(outcomes);
                } else if (!owner.installIfLatest(actor.request)) {
                    actor.stale(outcomes);
                } else {
                    actor.installed = true;
                }
            } catch (RuntimeException | Error failure) {
                actor.failed(outcomes);
            }
            budget.operationCompleted();
        }

        private void setupDelivery(TickBudget budget) {
            LinkedHashSet<ConnectionKey> watchers = new LinkedHashSet<>();
            for (ActorState actor : actors) {
                if (actor.installed) {
                    watchers.addAll(actor.observers);
                }
            }
            LinkedHashSet<ConnectionKey> current = new LinkedHashSet<>();
            for (ConnectionKey recipient : connections.recipients()) {
                ConnectionKey checked = Objects.requireNonNull(recipient, "recipient");
                if (connections.isCurrent(checked)) {
                    current.add(checked);
                }
            }
            List<ConnectionKey> watcherOrder = new ArrayList<>();
            for (ConnectionKey watcher : watchers) {
                if (current.remove(watcher)) {
                    watcherOrder.add(watcher);
                }
            }
            watcherRecipients = List.copyOf(watcherOrder);
            otherRecipients = List.copyOf(current);
            recipients = watcherRecipients;
            phase = Phase.DELIVER_WATCHERS;
            recipientIndex = 0;
            deliveryActorIndex = 0;
            budget.operationCompleted();
        }


        private boolean deliverOne(TickBudget budget, Phase completedPhase) {
            while (recipientIndex < recipients.size()) {
                ConnectionKey recipient = recipients.get(recipientIndex);
                try {
                    if (!connections.isCurrent(recipient)) {
                        nextRecipient();
                        budget.operationCompleted();
                        return true;
                    }
                    if (budget.remainingDeliveries() == 0) {
                        return false;
                    }
                    List<PublicationRequest> chunk = nextChunk(
                            recipient,
                            Math.min(
                                    policy.maxPacketEntries(),
                                    budget.remainingDeliveries()));
                    if (chunk.isEmpty()) {
                        if (deliveryActorIndex >= actors.size()) {
                            nextRecipient();
                            continue;
                        }
                        return true;
                    }
                    boolean delivered = false;
                    try {
                        transport.removeProfiles(recipient, chunk);
                        transport.initializeProfiles(recipient, chunk);
                        delivered = true;
                    } catch (RuntimeException | Error deliveryFailure) {

                    } finally {
                        metrics.initialDelivery(recipient, chunk.size());
                    }
                    if (!delivered) {
                        retainFailedDelivery(
                                recipient,
                                chunk,
                                0,
                                completedPhase == Phase.RETRACK);
                    }
                    budget.deliveryCompleted(chunk.size());
                    return true;
                } catch (RuntimeException | Error recipientFailure) {
                    nextRecipient();
                    budget.operationCompleted();
                    return true;
                }
            }
            phase = completedPhase;
            if (completedPhase == Phase.RETRACK) {
                recipients = List.of();
            }


            return completedPhase != Phase.RECONCILE || reconciliation.isEmpty();
        }

        private List<PublicationRequest> nextChunk(ConnectionKey recipient, int maximum) {
            List<PublicationRequest> chunk = new ArrayList<>(maximum);
            while (deliveryActorIndex < actors.size() && chunk.size() < maximum) {
                ActorState actor = actors.get(deliveryActorIndex++);
                if (!actor.installed || sameProfile(recipient, actor.request.connection())) {
                    continue;
                }
                if (!connections.isProfileVisible(recipient, actor.request)) {
                    continue;
                }
                if (!owner.isLatest(actor.request)
                        || !connections.isCurrent(actor.request)) {
                    actor.stale(outcomes);
                    continue;
                }
                chunk.add(actor.request);
            }
            return List.copyOf(chunk);
        }

        private boolean retrackPass(TickBudget budget) {
            boolean reconciledWatcherRefresh = phase == Phase.RETRACK_RECONCILED_WATCHERS;
            int attempts = removedPairs.size();
            while (attempts-- > 0 && budget.canRunOperation(scheduler.nanoTime())) {
                TrackingPair pair = removedPairs.removeFirst();
                boolean restored = false;
                try {
                    if (!connections.isCurrent(pair.actor)
                            || !connections.isCurrent(pair.observer)) {
                        restored = true;
                    } else {
                        tracking.retrack(pair.actor, pair.observer);
                        restored = true;
                    }
                } catch (RuntimeException | Error retrackFailure) {

                }
                if (!restored) {
                    removedPairs.addLast(pair);
                }
                budget.operationCompleted();
            }
            if (!removedPairs.isEmpty()) {
                return false;
            }
            if (!reconciledWatcherRefreshes.isEmpty()) {
                phase = Phase.REFRESH_RECONCILED_WATCHERS;
                return true;
            }
            if (aborted) {
                phase = Phase.COMPLETE;
                return true;
            }
            if (reconciledWatcherRefresh) {
                finalizeActorOutcomes();
                phase = Phase.COMPLETE;
                return true;
            }
            recipients = otherRecipients;
            recipientIndex = 0;
            deliveryActorIndex = 0;
            phase = Phase.DELIVER_OTHERS;
            return true;
        }

        private boolean reconcilePass(TickBudget budget) {
            if (reconciliation.isEmpty()) {
                phase = Phase.REFRESH_RECONCILED_WATCHERS;
                return true;
            }
            int entriesThisPass = reconciliation.size();
            while (entriesThisPass-- > 0 && budget.canRunOperation(scheduler.nanoTime())) {
                if (budget.remainingDeliveries() == 0) {
                    return false;
                }
                FailedDelivery failed = reconciliation.removeFirst();
                reconciliationDeliveries -= failed.actors.size();
                List<PublicationRequest> currentActors;
                try {
                    if (!connections.isCurrent(failed.recipient)) {
                        budget.operationCompleted();
                        continue;
                    }
                    currentActors = failed.actors.stream()
                            .filter(owner::isLatest)
                            .filter(connections::isCurrent)
                            .filter(actor -> connections.isProfileVisible(
                                    failed.recipient, actor))
                            .filter(actor -> !sameProfile(
                                    failed.recipient, actor.connection()))
                            .toList();
                } catch (RuntimeException | Error staleRecipient) {
                    budget.operationCompleted();
                    continue;
                }
                if (currentActors.isEmpty()) {
                    budget.operationCompleted();
                    continue;
                }
                int sendCount = Math.min(
                        currentActors.size(), budget.remainingDeliveries());
                List<PublicationRequest> sending = List.copyOf(
                        currentActors.subList(0, sendCount));
                List<PublicationRequest> tail = List.copyOf(
                        currentActors.subList(sendCount, currentActors.size()));
                if (!tail.isEmpty()) {
                    retainFailedDelivery(
                            failed.recipient,
                            tail,
                            failed.attempts,
                            failed.watcherChannel);
                }
                boolean delivered = false;
                try {
                    transport.removeProfiles(failed.recipient, sending);
                    transport.initializeProfiles(failed.recipient, sending);
                    delivered = true;
                } catch (RuntimeException | Error retryFailure) {

                }
                metrics.reconciliationAttempt(sending.size());
                if (delivered) {
                    if (failed.watcherChannel) {
                        enqueueReconciledWatcherRefreshes(failed.recipient, sending);
                    }
                } else if (failed.attempts + 1 < policy.maxReconciliationAttempts()) {
                    retainFailedDelivery(
                            failed.recipient,
                            sending,
                            failed.attempts + 1,
                            failed.watcherChannel);
                }
                budget.deliveryCompleted(sending.size());
            }
            if (!reconciliation.isEmpty()) {
                return false;
            }
            phase = Phase.REFRESH_RECONCILED_WATCHERS;
            return true;
        }

        private void enqueueReconciledWatcherRefreshes(
                ConnectionKey recipient,
                List<PublicationRequest> deliveredActors) {
            for (PublicationRequest actor : deliveredActors) {
                WatcherPairKey key = new WatcherPairKey(actor.connection(), recipient);
                if (originalWatcherPairs.contains(key) && pendingWatcherRefreshes.add(key)) {
                    reconciledWatcherRefreshes.addLast(new WatcherRefresh(
                            new TrackingPair(actor, recipient), 0));
                }
            }
        }


        private boolean refreshReconciledWatcherOne(TickBudget budget) {
            WatcherRefresh refresh = reconciledWatcherRefreshes.pollFirst();
            if (refresh == null) {
                phase = Phase.RETRACK_RECONCILED_WATCHERS;
                return true;
            }
            TrackingPair pair = refresh.pair;
            WatcherPairKey key = new WatcherPairKey(
                    pair.actor.connection(), pair.observer);
            try {
                if (!owner.isLatest(pair.actor)
                        || !connections.isCurrent(pair.actor)
                        || !connections.isCurrent(pair.observer)
                        || !connections.isProfileVisible(pair.observer, pair.actor)) {
                    pendingWatcherRefreshes.remove(key);
                } else if (tracking.untrack(pair.actor, pair.observer)) {

                    removedPairs.addLast(pair);
                    pendingWatcherRefreshes.remove(key);
                } else {


                    pendingWatcherRefreshes.remove(key);
                }
            } catch (RuntimeException | Error refreshFailure) {


                removedPairs.addLast(pair);
                int nextAttempt = refresh.attempts + 1;
                if (nextAttempt < policy.maxReconciliationAttempts()) {
                    reconciledWatcherRefreshes.addLast(new WatcherRefresh(pair, nextAttempt));
                } else {
                    pendingWatcherRefreshes.remove(key);
                    outcomes.put(pair.actor.connection(), PublicationOutcome.FAILED);
                }
                phase = Phase.RETRACK_RECONCILED_WATCHERS;
            }
            budget.operationCompleted();
            return phase != Phase.RETRACK_RECONCILED_WATCHERS;
        }

        private void retainFailedDelivery(
                ConnectionKey recipient,
                List<PublicationRequest> actors,
                int attempts,
                boolean watcherChannel) {
            if (policy.maxReconciliationAttempts() == 0 || actors.isEmpty()) {
                return;
            }
            int capacity = policy.maxReconciliationDeliveries() - reconciliationDeliveries;
            if (capacity <= 0) {
                return;
            }
            List<PublicationRequest> retained = actors.size() <= capacity
                    ? List.copyOf(actors)
                    : List.copyOf(actors.subList(0, capacity));
            reconciliation.addLast(new FailedDelivery(
                    recipient, retained, attempts, watcherChannel));
            reconciliationDeliveries += retained.size();
        }

        private void finalizeActorOutcomes() {
            for (ActorState actor : actors) {
                if (!owner.isLatest(actor.request)) {
                    outcomes.put(actor.request.connection(), PublicationOutcome.STALE);
                    continue;
                }
                if (outcomes.containsKey(actor.request.connection())) {
                    continue;
                }
                outcomes.put(
                        actor.request.connection(),
                        actor.installed
                                ? PublicationOutcome.UPDATED
                                : PublicationOutcome.FAILED);
            }
        }

        private void nextActorObservers() {
            actorIndex++;
            observerIndex = 0;
        }

        private void nextRecipient() {
            recipientIndex++;
            deliveryActorIndex = 0;
        }

        private void abort() {
            aborted = true;
            reconciliation.clear();
            reconciliationDeliveries = 0;
            for (PublicationRequest request : requests) {
                outcomes.putIfAbsent(request.connection(), PublicationOutcome.FAILED);
            }
            if (!removedPairs.isEmpty()) {
                phase = Phase.RETRACK_RECONCILED_WATCHERS;
            } else if (!reconciledWatcherRefreshes.isEmpty()) {
                phase = Phase.REFRESH_RECONCILED_WATCHERS;
            } else {
                phase = Phase.COMPLETE;
            }
        }

        private boolean hasCleanupObligations() {
            return !removedPairs.isEmpty() || !reconciledWatcherRefreshes.isEmpty();
        }
    }

    private static final class ActorState {
        private final PublicationRequest request;
        private final LiveProfileTextures current;
        private List<ConnectionKey> observers = List.of();
        private boolean unchanged;
        private boolean failed;
        private boolean stale;
        private boolean installed;

        private ActorState(
                PublicationRequest request,
                LiveProfileTextures current) {
            this.request = request;
            this.current = current;
        }

        private boolean ready() {
            return !unchanged && !failed && !stale && !installed;
        }

        private void failed(Map<ConnectionKey, PublicationOutcome> outcomes) {
            failed = true;
            outcomes.put(request.connection(), PublicationOutcome.FAILED);
        }

        private void stale(Map<ConnectionKey, PublicationOutcome> outcomes) {
            stale = true;
            outcomes.put(request.connection(), PublicationOutcome.STALE);
        }
    }

    private static final class MetricsAccumulator {
        private final Set<ConnectionKey> recipients = new LinkedHashSet<>();
        private long profileDeliveries;
        private long packetChunks;
        private long watcherPairs;
        private long platformThreadNanos;
        private long platformThreadMaxTickNanos;
        private long measuredTickId = Long.MIN_VALUE;
        private long measuredTickNanos;
        private long reconciliationAttempts;
        private long reconciliationDeliveries;

        private void initialDelivery(ConnectionKey recipient, int profiles) {
            recipients.add(recipient);
            profileDeliveries = saturatedAdd(profileDeliveries, profiles);
            packetChunks = saturatedAdd(packetChunks, 1L);
        }

        private void watcherPair() {
            watcherPairs = saturatedAdd(watcherPairs, 1L);
        }

        private void recordPlatformTick(long tickId, long nanos) {
            if (measuredTickId == tickId) {
                if (nanos <= measuredTickNanos) {
                    return;
                }
                platformThreadNanos = saturatedAdd(
                        platformThreadNanos, nanos - measuredTickNanos);
            } else {
                measuredTickId = tickId;
                platformThreadNanos = saturatedAdd(platformThreadNanos, nanos);
            }
            measuredTickNanos = nanos;
            platformThreadMaxTickNanos = Math.max(platformThreadMaxTickNanos, nanos);
        }

        private void reconciliationAttempt(int profiles) {
            reconciliationAttempts = saturatedAdd(reconciliationAttempts, 1L);
            reconciliationDeliveries = saturatedAdd(reconciliationDeliveries, profiles);
            packetChunks = saturatedAdd(packetChunks, 1L);
        }

        private PublicationMetrics snapshot() {
            long egress = saturatedAdd(
                    saturatedMultiply(
                            saturatedAdd(profileDeliveries, reconciliationDeliveries),
                            ESTIMATED_PROFILE_ENTRY_BYTES),
                    saturatedMultiply(packetChunks, ESTIMATED_PACKET_PAIR_OVERHEAD_BYTES));
            return new PublicationMetrics(
                    recipients.size(),
                    profileDeliveries,
                    packetChunks,
                    watcherPairs,
                    egress,
                    platformThreadNanos,
                    platformThreadMaxTickNanos,
                    reconciliationAttempts,
                    reconciliationDeliveries);
        }
    }

    private record TrackingPair(PublicationRequest actor, ConnectionKey observer) {}

    private record WatcherRefresh(TrackingPair pair, int attempts) {}

    private record WatcherPairKey(ConnectionKey actor, ConnectionKey observer) {}

    private record FailedDelivery(
            ConnectionKey recipient,
            List<PublicationRequest> actors,
            int attempts,
            boolean watcherChannel) {
        private FailedDelivery {
            actors = List.copyOf(actors);
        }
    }

    private record SemanticWork(
            PublicationRequest request,
            SignedTexturesProperty property) {}

    private record SemanticDecision(
            ConnectionKey connection,
            boolean matches) {}

    private static final class TickBudget {
        private final int maxDeliveries;
        private final long maxNanos;
        private final long startedAt;
        private int deliveries;
        private int operations;

        private TickBudget(int maxDeliveries, long maxNanos, long startedAt) {
            this.maxDeliveries = maxDeliveries;
            this.maxNanos = maxNanos;
            this.startedAt = startedAt;
        }

        private static TickBudget unlimited() {
            return new TickBudget(Integer.MAX_VALUE, Long.MAX_VALUE, 0L);
        }

        private boolean canRunOperation(long now) {
            return operations == 0 || elapsed(now, startedAt) < maxNanos;
        }

        private int remainingDeliveries() {
            return Math.max(0, maxDeliveries - deliveries);
        }

        private void operationCompleted() {
            operations++;
        }

        private void deliveryCompleted(int count) {
            if (count < 1 || count > remainingDeliveries()) {
                throw new IllegalArgumentException("Delivery exceeds the current tick budget");
            }
            deliveries += count;
            operations++;
        }

        private long elapsed(long now) {
            return elapsed(now, startedAt);
        }

        private static long elapsed(long now, long then) {
            if (now <= then) {
                return 0L;
            }
            long difference = now - then;
            return difference < 0L ? Long.MAX_VALUE : difference;
        }
    }

    private static List<PublicationRequest> deduplicate(List<PublicationRequest> requests) {
        LinkedHashMap<ConnectionKey, PublicationRequest> latest = new LinkedHashMap<>();
        for (PublicationRequest request : requests) {
            PublicationRequest item = Objects.requireNonNull(request, "publication request");
            latest.remove(item.connection());
            latest.put(item.connection(), item);
        }
        return List.copyOf(latest.values());
    }

    private static boolean sameProfile(ConnectionKey first, ConnectionKey second) {
        return first.profileId().equals(second.profileId());
    }

    private static long saturatedAdd(long left, long right) {
        if (right > 0L && left > Long.MAX_VALUE - right) {
            return Long.MAX_VALUE;
        }
        return left + right;
    }

    private static long saturatedMultiply(long value, long factor) {
        if (value == 0L || factor == 0L) {
            return 0L;
        }
        return value > Long.MAX_VALUE / factor ? Long.MAX_VALUE : value * factor;
    }

    private enum Phase {
        PREPARE,
        SEMANTIC,
        WAIT_SEMANTIC,
        SNAPSHOT,
        UNTRACK,
        INSTALL,
        DELIVERY_SETUP,
        DELIVER_WATCHERS,
        RETRACK,
        DELIVER_OTHERS,
        RECONCILE,
        REFRESH_RECONCILED_WATCHERS,
        RETRACK_RECONCILED_WATCHERS,
        COMPLETE
    }

    private enum AdvanceResult {
        COMPLETE,
        YIELD,
        WAITING,
        CANCELLED
    }

    private enum NextAction {
        FINISH,
        NEXT_TICK,
        WAITING
    }
}

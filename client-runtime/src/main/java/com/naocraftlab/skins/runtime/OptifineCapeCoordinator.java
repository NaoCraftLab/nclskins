package com.naocraftlab.skins.runtime;

import com.naocraftlab.skins.client.ClientExecutor;
import com.naocraftlab.skins.client.GameSessionTokenSource;
import com.naocraftlab.skins.client.PlayerAppearanceSink;
import com.naocraftlab.skins.client.PlayerAppearanceSink.CapeSource;
import com.naocraftlab.skins.client.PlayerAppearanceSink.TrackedCapePlayer;
import com.naocraftlab.skins.core.png.PngValidator;
import com.naocraftlab.skins.core.provider.AppearanceProviders;
import com.naocraftlab.skins.core.provider.BuiltinProvider;
import com.naocraftlab.skins.core.provider.ProviderCape;
import com.naocraftlab.skins.core.provider.ProviderObservation;
import com.naocraftlab.skins.core.storage.NclSkinsStorage;
import com.naocraftlab.skins.core.storage.TextureCache;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.function.BiFunction;
import java.util.function.Consumer;

public final class OptifineCapeCoordinator implements AutoCloseable {
    private static final int MAX_ACTIVE = 4;
    private static final int MAX_QUEUE = 256;

    private final GameSessionTokenSource tokenSource;
    private final NclSkinsStorage storage;
    private final TextureCache textures;
    private final PlayerAppearanceSink<?> sink;
    private final ClientExecutor clientExecutor;
    private final Executor worker;
    private final OptifineCapeReader reader;
    private final BiFunction<UUID, String, java.util.Optional<URI>> officialCapeUri;
    private final Map<UUID, CapeProjection.Identity> tracked = new HashMap<>();
    private final Map<CapeProjection.Identity, Observed> observed = new HashMap<>();
    private final ArrayDeque<CapeProjection.Identity> queue = new ArrayDeque<>();
    private final Map<CapeProjection.Identity, Request> pending = new HashMap<>();
    private final Set<CapeProjection.Identity> attempted = new HashSet<>();
    private final Set<CapeProjection.Identity> repeatPending = new LinkedHashSet<>();
    private final List<Consumer<ProviderObservation<ProviderCape>>> refreshCompletions = new ArrayList<>();
    private ProviderObservation<ProviderCape> confirmedRefreshObservation;
    private boolean explicitSweep;
    private final Map<BuiltinProvider, CapeProjection.Candidate> selfCandidates = new HashMap<>();

    private AppearanceProviders providers = AppearanceProviders.initial();
    private AppearanceProviders selfCandidateProviders = AppearanceProviders.initial();
    private Consumer<ClientOperations.OptiFineObservation> selfObservationListener = ignored -> {};
    private volatile CapeProjection.Identity selfIdentity;
    private Iterator<CapeProjection.Identity> sweep = List.<CapeProjection.Identity>of().iterator();
    private volatile long generation;
    private long selfPreparation;
    private volatile long selfEpoch;
    private long adoptionSequence;
    private Future<?> selfPreparationFuture;
    private boolean refreshPending;
    private boolean unknownSweepPending;
    private boolean unknownOnly;
    private boolean pumping;
    private CapeProjection.Identity startedIdentity;
    private ProjectionState publishedProjection;
    private volatile boolean closed;

    public OptifineCapeCoordinator(GameSessionTokenSource tokenSource, NclSkinsStorage storage,
            TextureCache textures, PlayerAppearanceSink<?> sink, ClientExecutor clientExecutor,
            Executor worker, BiFunction<UUID, String, java.util.Optional<URI>> officialCapeUri) {
        this(tokenSource, storage, textures, sink, clientExecutor, worker,
                new OptifineCapeReader(), officialCapeUri);
    }

    OptifineCapeCoordinator(GameSessionTokenSource tokenSource, NclSkinsStorage storage,
            TextureCache textures, PlayerAppearanceSink<?> sink, ClientExecutor clientExecutor,
            Executor worker, OptifineCapeReader reader) {
        this(tokenSource, storage, textures, sink, clientExecutor, worker, reader,
                (accountId, capeId) -> java.util.Optional.empty());
    }

    OptifineCapeCoordinator(GameSessionTokenSource tokenSource, NclSkinsStorage storage,
            TextureCache textures, PlayerAppearanceSink<?> sink, ClientExecutor clientExecutor,
            Executor worker, OptifineCapeReader reader,
            BiFunction<UUID, String, java.util.Optional<URI>> officialCapeUri) {
        this.tokenSource = Objects.requireNonNull(tokenSource, "tokenSource");
        this.storage = Objects.requireNonNull(storage, "storage");
        this.textures = Objects.requireNonNull(textures, "textures");
        this.sink = Objects.requireNonNull(sink, "sink");
        this.clientExecutor = Objects.requireNonNull(clientExecutor, "clientExecutor");
        this.worker = Objects.requireNonNull(worker, "worker");
        this.reader = Objects.requireNonNull(reader, "reader");
        this.officialCapeUri = Objects.requireNonNull(officialCapeUri, "officialCapeUri");
    }

    public synchronized void start() {
        if (closed) return;
        GameSessionTokenSource.SessionIdentity session = tokenSource.currentSession();
        CapeProjection.Identity identity = new CapeProjection.Identity(session.profileId(), session.profileName());
        if (identity.equals(startedIdentity)) return;
        startedIdentity = identity;
        refresh();
    }

    public synchronized void onSelfObservation(
            Consumer<ClientOperations.OptiFineObservation> listener) {
        selfObservationListener = Objects.requireNonNull(listener, "listener");
    }

    public synchronized void selfCapeCandidatesChanged(UUID accountId, String canonicalName,
            AppearanceProviders next) {
        if (closed || selfIdentity == null || !selfIdentity.equals(
                new CapeProjection.Identity(accountId, canonicalName))) return;
        if (Objects.equals(selfCandidateProviders.cape().offline(), next.cape().offline())
                && Objects.equals(selfCandidateProviders.cape().minecraft(), next.cape().minecraft())) return;
        selfCandidateProviders = next;
        scheduleSelfCandidates();
    }

    public synchronized void refresh() {
        if (closed) return;
        reloadConfiguration();
        if (!enabled()) return;
        if (!pending.isEmpty() || !queue.isEmpty() || sweep.hasNext()) {
            refreshPending = true;
            return;
        }
        attempted.clear();
        startSweep(false);
    }

    public synchronized void refresh(Consumer<ProviderObservation<ProviderCape>> completion) {
        Objects.requireNonNull(completion, "completion");
        if (closed) {
            completion.accept(null);
            return;
        }
        refreshCompletions.add(completion);
        refresh();
        if (!enabled()) finishRefreshCompletions();
    }

    public synchronized void configurationChanged() {
        if (closed) return;
        boolean wasEnabled = enabled();
        CapeProjection.Identity previousSelf = selfIdentity;
        reloadConfiguration();
        if (Objects.equals(previousSelf, selfIdentity) && wasEnabled && enabled()) {
            publish();
            return;
        }
        generation++;
        queue.clear();
        cancelPending();
        repeatPending.clear();
        sweep = List.<CapeProjection.Identity>of().iterator();
        refreshPending = false;
        confirmedRefreshObservation = null;
        finishRefreshCompletions();
        if (!enabled()) {
            releaseObservedTextures();
        } else {
            if (!wasEnabled) restoreObservedTextures();
            startSweep(true);
        }
        publish();
        pump();
    }

    public synchronized void adoptSharedSnapshot(UUID accountId, String canonicalName,
            AppearanceProviders snapshot) {
        if (closed || selfIdentity == null || !selfIdentity.equals(
                new CapeProjection.Identity(accountId, canonicalName)) || !current(selfIdentity)) return;
        long sequence = ++adoptionSequence;
        long expectedGeneration = generation;
        CapeProjection.Identity identity = selfIdentity;
        ProviderObservation<ProviderCape> nextObservation = snapshot.cape().optifine();
        Observed represented = observed.get(identity);
        ProviderCape nextCape = nextObservation.value();
        boolean changedObservation = !providers.cape().optifine().equals(nextObservation)
                || nextCape != null && represented != null && represented.png() != null
                && (!Objects.equals(nextCape.textureCacheKey(), represented.png().renderSha256())
                    || !Objects.equals(nextCape.hasElytra(), represented.png().hasElytra()));
        boolean changedCandidates = !Objects.equals(selfCandidateProviders.cape().offline(), snapshot.cape().offline())
                || !Objects.equals(selfCandidateProviders.cape().minecraft(), snapshot.cape().minecraft());
        if (changedObservation) {
            selfEpoch++;
            confirmedRefreshObservation = null;
            queue.remove(identity);
            attempted.remove(identity);
            Request request = pending.remove(identity);
            if (request != null && request.future != null) request.future.cancel(true);
        }
        providers = snapshot;
        if (changedCandidates) {
            selfCandidateProviders = snapshot;
            scheduleSelfCandidates();
        }
        ProviderCape cape = nextCape;
        if (!enabled() || !nextObservation.known() || cape == null
                || !cape.id().equals(capeId(identity)) || cape.textureCacheKey() == null) {
            clearAdoptedSelf(identity, nextObservation.known() && cape == null);
            publish();
            pump();
            return;
        }
        if (!changedObservation && represented != null && represented.png() != null
                && represented.location() != null
                && cape.textureCacheKey().equals(represented.png().renderSha256())
                && Objects.equals(cape.hasElytra(), represented.png().hasElytra())) {
            publish();
            pump();
            return;
        }
        if (changedObservation) clearAdoptedSelf(identity, false);
        publish();
        Runnable preparation = () -> {
            PngValidator.CapePng png = null;
            try {
                byte[] bytes = textures.readIfCached(cape.textureCacheKey()).orElse(null);
                if (bytes != null) {
                    PngValidator.CapePng parsed = new PngValidator().projectCanonicalCape(bytes);
                    if (parsed.renderSha256().equals(cape.textureCacheKey())
                            && Objects.equals(parsed.hasElytra(), cape.hasElytra())) png = parsed;
                }
            } catch (IOException | com.naocraftlab.skins.core.png.PngValidationException unavailable) {
                png = null;
            }
            PngValidator.CapePng validated = png;
            clientExecutor.execute(() -> completeSharedAdoption(identity, snapshot,
                    sequence, expectedGeneration, validated));
        };
        worker.execute(preparation);
        pump();
    }

    private synchronized void completeSharedAdoption(CapeProjection.Identity identity,
            AppearanceProviders snapshot, long sequence, long expectedGeneration,
            PngValidator.CapePng png) {
        if (closed || adoptionSequence != sequence || generation != expectedGeneration
                || !identity.equals(selfIdentity)
                || !current(identity) || !enabled()
                || !providers.cape().optifine().equals(snapshot.cape().optifine())) return;
        if (png == null) {
            clearAdoptedSelf(identity, false);
            publish();
            return;
        }
        replace(identity, new Observed(png, null));
    }

    private void clearAdoptedSelf(CapeProjection.Identity identity, boolean knownAbsent) {
        Observed old = observed.remove(identity);
        if (old != null && old.location() != null) {
            sink.releaseCapeTexture(identity.profileId(), CapeSource.OPTIFINE);
        }
        if (knownAbsent) observed.put(identity, new Observed(null, null));
    }

    public synchronized void trackedPlayer(UUID profileId, String canonicalName) {
        if (closed) return;
        CapeProjection.Identity identity = new CapeProjection.Identity(profileId, canonicalName);
        CapeProjection.Identity previous = tracked.put(profileId, identity);
        if (previous != null && !previous.equals(identity)) {
            remove(previous);
        }
        if (enabled() && !observed.containsKey(identity) && !attempted.contains(identity)) {
            if (queue.size() >= MAX_QUEUE) {
                unknownSweepPending = true;
                repeatPending.add(identity);
            } else {
                enqueue(identity, false);
            }
            pump();
        }
    }

    public synchronized void playerInfoUpdated(UUID profileId, String canonicalName) {
        if (closed || !enabled()) return;
        CapeProjection.Identity identity = new CapeProjection.Identity(profileId, canonicalName);
        CapeProjection.Identity previous = tracked.get(profileId);
        if (previous == null || !previous.equals(identity)) {
            trackedPlayer(profileId, canonicalName);
            return;
        }
        if (identity.equals(selfIdentity)) return;
        if (pending.containsKey(identity)) {
            repeatPending.add(identity);
            return;
        }
        if (queue.contains(identity)) return;
        attempted.remove(identity);
        if (queue.size() >= MAX_QUEUE) repeatPending.add(identity);
        else enqueue(identity, false);
        pump();
    }

    public synchronized void untrackedPlayer(UUID profileId) {
        CapeProjection.Identity removed = tracked.remove(profileId);
        if (removed != null && !removed.equals(selfIdentity)) {
            remove(removed);
            publish();
            pump();
        }
    }

    public synchronized void worldChanged() {
        if (closed) return;
        generation++;
        queue.clear();
        cancelPending();
        sweep = List.<CapeProjection.Identity>of().iterator();
        refreshPending = false;
        confirmedRefreshObservation = null;
        finishRefreshCompletions();
        for (CapeProjection.Identity identity : List.copyOf(observed.keySet())) {
            if (!identity.equals(selfIdentity)) remove(identity);
        }
        tracked.clear();
        attempted.clear();
        repeatPending.clear();
        scheduleSelfCandidates();
        publish();
    }

    public synchronized void worldEntered() {
        worldChanged();
        if (!closed && enabled()) startSweep(true);
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        generation++;
        queue.clear();
        cancelPending();
        tracked.clear();
        attempted.clear();
        repeatPending.clear();
        sweep = List.<CapeProjection.Identity>of().iterator();
        releaseObservedTextures();
        observed.clear();
        cancelSelfPreparation();
        releaseSelfCandidates();
        CapeProjection.clear();
    }

    private boolean enabled() {
        return providers.cape().enabled(BuiltinProvider.OPTIFINE);
    }

    private void reloadConfiguration() {
        GameSessionTokenSource.SessionIdentity session = tokenSource.currentSession();
        CapeProjection.Identity nextSelf = new CapeProjection.Identity(session.profileId(), session.profileName());
        boolean identityChanged = !nextSelf.equals(selfIdentity);
        if (identityChanged) {
            generation++;
            selfEpoch++;
            queue.clear();
            cancelPending();
            tracked.clear();
            attempted.clear();
            repeatPending.clear();
            sweep = List.<CapeProjection.Identity>of().iterator();
            refreshPending = false;
            unknownSweepPending = false;
            releaseObservedTextures();
            observed.clear();
            cancelSelfPreparation();
            releaseSelfCandidates();
            selfIdentity = nextSelf;
        }
        try {
            AppearanceProviders loaded = storage.loadAppearance(session.profileId()).providers();
            boolean candidateInputsChanged = identityChanged
                    || !Objects.equals(selfCandidateProviders.cape().offline(), loaded.cape().offline())
                    || !Objects.equals(selfCandidateProviders.cape().minecraft(), loaded.cape().minecraft());
            providers = loaded;
            restoreSelfObservation();
            if (candidateInputsChanged) {
                selfCandidateProviders = loaded;
                scheduleSelfCandidates();
            }
        } catch (IOException failure) {
            providers = AppearanceProviders.initial();
        }
        publish();
    }

    private void restoreSelfObservation() {
        var saved = providers.cape().optifine();
        if (!saved.known() || observed.containsKey(selfIdentity)) return;
        ProviderCape cape = saved.value();
        if (cape == null) {
            observed.put(selfIdentity, new Observed(null, null));
            return;
        }
        if (!cape.id().equals(capeId(selfIdentity))) return;
        if (cape.textureCacheKey() == null) return;
        try {
            byte[] bytes = textures.readIfCached(cape.textureCacheKey()).orElse(null);
            if (bytes == null) return;
            PngValidator.CapePng png = new PngValidator().projectCanonicalCape(bytes);
            if (!png.renderSha256().equals(cape.textureCacheKey())) return;
            String location = enabled() ? sink.registerCapeTexture(selfIdentity.profileId(),
                    CapeSource.OPTIFINE, png.renderSha256(), png.bytes()).orElse(null) : null;
            observed.put(selfIdentity, new Observed(png, location));
        } catch (IOException | com.naocraftlab.skins.core.png.PngValidationException invalid) {
            observed.remove(selfIdentity);
        }
    }

    private void scheduleSelfCandidates() {
        cancelSelfPreparation();
        releaseSelfCandidates();
        long token = ++selfPreparation;
        long expectedGeneration = generation;
        CapeProjection.Identity identity = selfIdentity;
        AppearanceProviders expected = selfCandidateProviders;
        Runnable preparation = () -> {
            Map<BuiltinProvider, Prepared> prepared = new HashMap<>();
            for (BuiltinProvider provider : List.of(BuiltinProvider.OFFLINE, BuiltinProvider.MINECRAFT)) {
                ProviderCape cape = expected.cape().observation(provider).value();
                if (cape == null) continue;
                try {
                    byte[] bytes = cape.textureCacheKey() == null ? null
                            : textures.readIfCached(cape.textureCacheKey()).orElse(null);
                    if (bytes == null && provider == BuiltinProvider.OFFLINE
                            && cape.textureCacheKey() != null
                            && java.nio.file.Files.isRegularFile(storage.capeAssetPath(
                                    identity.profileId(), cape.textureCacheKey()))) {
                        bytes = storage.readCapeAsset(identity.profileId(), cape.textureCacheKey());
                    }
                    if (bytes == null && provider == BuiltinProvider.MINECRAFT) {
                        java.util.Optional<URI> uri = officialCapeUri.apply(identity.profileId(), cape.id());
                        if (uri.isPresent()) bytes = textures.read(textures.get(uri.orElseThrow()));
                    }
                    if (bytes == null) continue;
                    var png = new PngValidator().projectCanonicalCape(bytes);
                    prepared.put(provider, new Prepared(png.renderSha256(), png.bytes(),
                            !Boolean.FALSE.equals(cape.hasElytra())));
                } catch (IOException | com.naocraftlab.skins.core.png.PngValidationException unavailable) {
                    continue;
                }
            }
            clientExecutor.execute(() -> applySelfCandidates(identity, expectedGeneration, token, prepared));
        };
        if (worker instanceof ExecutorService service) {
            Future<?> future = service.submit(preparation);
            if (selfPreparation == token) selfPreparationFuture = future;
        } else {
            worker.execute(preparation);
        }
    }

    private void cancelSelfPreparation() {
        if (selfPreparationFuture != null) selfPreparationFuture.cancel(true);
        selfPreparationFuture = null;
        selfPreparation++;
    }

    private synchronized void applySelfCandidates(CapeProjection.Identity identity,
            long expectedGeneration, long token, Map<BuiltinProvider, Prepared> prepared) {
        if (closed || generation != expectedGeneration || selfPreparation != token
                || !identity.equals(selfIdentity) || !current(identity)) return;
        for (var entry : prepared.entrySet()) {
            Prepared value = entry.getValue();
            sink.registerCapeTexture(identity.profileId(), CapeSource.valueOf(entry.getKey().name()),
                    value.sha256(), value.bytes()).ifPresent(location -> selfCandidates.put(
                    entry.getKey(), candidate(location, value.hasElytra())));
        }
        publish();
    }

    private void releaseSelfCandidates() {
        if (selfIdentity != null) {
            for (BuiltinProvider provider : selfCandidates.keySet()) {
                sink.releaseCapeTexture(selfIdentity.profileId(), CapeSource.valueOf(provider.name()));
            }
        }
        selfCandidates.clear();
    }

    private void startSweep(boolean unknownOnly) {
        this.unknownOnly = unknownOnly;
        this.explicitSweep = !unknownOnly;
        if (!unknownOnly) confirmedRefreshObservation = null;
        List<CapeProjection.Identity> identities = new ArrayList<>();
        identities.add(selfIdentity);
        Set<CapeProjection.Identity> seen = new HashSet<>();
        seen.add(selfIdentity);
        for (TrackedCapePlayer player : sink.trackedCapePlayers()) {
            CapeProjection.Identity identity = new CapeProjection.Identity(
                    player.profileId(), player.canonicalName());
            CapeProjection.Identity previous = tracked.put(player.profileId(), identity);
            if (previous != null && !previous.equals(identity)) remove(previous);
            if (seen.add(identity)) identities.add(identity);
        }
        for (CapeProjection.Identity identity : List.copyOf(tracked.values())) {
            if (!seen.contains(identity)) {
                tracked.remove(identity.profileId());
                remove(identity);
            }
        }
        sweep = identities.iterator();
        fillQueue();
        pump();
    }

    private void enqueue(CapeProjection.Identity identity, boolean discovery) {
        if (queue.size() >= MAX_QUEUE || pending.containsKey(identity) || queue.contains(identity)
                || discovery && unknownOnly
                && (observed.containsKey(identity) || attempted.contains(identity))) return;
        queue.add(identity);
    }

    private void fillQueue() {
        Iterator<CapeProjection.Identity> deferred = repeatPending.iterator();
        while (deferred.hasNext()) {
            CapeProjection.Identity identity = deferred.next();
            if (!enabled() || !current(identity)) {
                deferred.remove();
                continue;
            }
            if (pending.containsKey(identity)) continue;
            if (queue.contains(identity)) {
                deferred.remove();
                continue;
            }
            if (queue.size() >= MAX_QUEUE) break;
            attempted.remove(identity);
            queue.add(identity);
            deferred.remove();
        }
        while (queue.size() < MAX_QUEUE && sweep.hasNext()) {
            enqueue(sweep.next(), true);
        }
    }

    private void pump() {
        if (pumping || closed) return;
        pumping = true;
        try {
            fillQueue();
            while (enabled() && pending.size() < MAX_ACTIVE && !queue.isEmpty()) {
                CapeProjection.Identity identity = queue.remove();
                if (!current(identity) || pending.containsKey(identity)) continue;
                long requestGeneration = generation;
                Request request = new Request(requestGeneration, selfEpoch,
                        identity.equals(selfIdentity) ? providers.cape().optifine() : null,
                        explicitSweep);
                pending.put(identity, request);
                Runnable task = () -> {
                    OptifineCapeReader.Outcome outcome = reader.read(identity.canonicalName());
                    if (identity.equals(selfIdentity)
                            && outcome.kind() != OptifineCapeReader.Kind.FAILURE) {
                        Persisted persisted = persistSelf(identity, request, outcome);
                        clientExecutor.execute(() -> complete(identity, request, outcome, persisted));
                    } else {
                        clientExecutor.execute(() -> complete(identity, request, outcome, null));
                    }
                };
                if (worker instanceof ExecutorService service) {
                    Future<?> future = service.submit(task);
                    if (pending.get(identity) == request) request.future = future;
                } else {
                    worker.execute(task);
                }
                fillQueue();
            }
        } finally {
            pumping = false;
        }
    }

    private synchronized void complete(CapeProjection.Identity identity, Request request,
            OptifineCapeReader.Outcome outcome, Persisted persisted) {
        if (pending.get(identity) != request) return;
        pending.remove(identity);
        if (!closed && request.generation == generation
                && (!identity.equals(selfIdentity) || request.selfEpoch == selfEpoch)
                && enabled() && current(identity)) {
            attempted.add(identity);
            if (outcome.kind() == OptifineCapeReader.Kind.ABSENT) {
                if (!identity.equals(selfIdentity) || persisted != null && persisted.applied()) {
                    replace(identity, new Observed(null, null));
                    if (identity.equals(selfIdentity) && request.explicitRefresh) {
                        confirmedRefreshObservation = ProviderObservation.observed(null);
                    }
                }
            } else if (outcome.kind() == OptifineCapeReader.Kind.PRESENT) {
                if (!identity.equals(selfIdentity) || persisted != null && persisted.applied()) {
                    replace(identity, new Observed(outcome.cape(), null));
                    if (identity.equals(selfIdentity) && request.explicitRefresh) {
                        confirmedRefreshObservation = ProviderObservation.observed(persisted.cape());
                    }
                }
            }
        }
        fillQueue();
        if (pending.isEmpty() && queue.isEmpty() && !sweep.hasNext()) {
            if (refreshPending) {
                refreshPending = false;
                attempted.clear();
                startSweep(false);
            } else {
                finishRefreshCompletions();
                if (unknownSweepPending) {
                    unknownSweepPending = false;
                    startSweep(true);
                }
            }
        } else {
            pump();
        }
    }

    private void finishRefreshCompletions() {
        if (refreshCompletions.isEmpty()) return;
        List<Consumer<ProviderObservation<ProviderCape>>> completions = List.copyOf(refreshCompletions);
        refreshCompletions.clear();
        ProviderObservation<ProviderCape> confirmed = confirmedRefreshObservation;
        confirmedRefreshObservation = null;
        completions.forEach(completion -> completion.accept(confirmed));
    }

    private Persisted persistSelf(CapeProjection.Identity identity, Request request,
            OptifineCapeReader.Outcome outcome) {
        if (closed || request.generation != generation || request.selfEpoch != selfEpoch
                || !current(identity)) {
            return new Persisted(false, null);
        }
        try {
            ProviderCape cape = null;
            if (outcome.kind() == OptifineCapeReader.Kind.PRESENT) {
                String key = textures.storeObservedCape(outcome.cape());
                cape = new ProviderCape(capeId(identity), key, outcome.cape().hasElytra());
            }
            ProviderCape confirmed = cape;
            boolean[] applied = {false};
            storage.updateAppearance(identity.profileId(), current -> {
                GameSessionTokenSource.SessionIdentity session = tokenSource.currentSession();
                if (closed || request.generation != generation || request.selfEpoch != selfEpoch
                        || !identity.equals(new CapeProjection.Identity(
                                session.profileId(), session.profileName()))
                        || !current.providers().cape().enabled(BuiltinProvider.OPTIFINE)
                        || !current.providers().cape().optifine().equals(request.dispatchedObservation)) return current;
                applied[0] = true;
                return current.withProviders(new AppearanceProviders(current.providers().skin(),
                        current.providers().cape().observeOptifine(confirmed)));
            });
            return new Persisted(applied[0], cape);
        } catch (IOException | com.naocraftlab.skins.core.png.PngValidationException unavailable) {
            return new Persisted(false, null);
        }
    }

    private void replace(CapeProjection.Identity identity, Observed next) {
        Observed previous = observed.get(identity);
        if (sameObservation(previous, next)) return;
        if (previous != null && previous.location() != null) {
            sink.releaseCapeTexture(identity.profileId(), CapeSource.OPTIFINE);
        }
        if (next.png() != null) {
            var registered = sink.registerCapeTexture(identity.profileId(), CapeSource.OPTIFINE,
                    next.png().renderSha256(), next.png().bytes());
            next = new Observed(next.png(), registered.orElse(null));
        }
        observed.put(identity, next);
        if (identity.equals(selfIdentity)) {
            ProviderCape cape = next.png() == null ? null
                    : new ProviderCape(capeId(identity), next.png().renderSha256(), next.png().hasElytra());
            providers = new AppearanceProviders(providers.skin(), providers.cape().observeOptifine(cape));
            selfObservationListener.accept(new ClientOperations.OptiFineObservation(identity.profileId(),
                    identity.canonicalName(), providers.cape().configurationRevision(), cape));
        }
        publish();
    }

    private static boolean sameObservation(Observed previous, Observed next) {
        if (previous == null) return false;
        if (previous.png() == null || next.png() == null) {
            return previous.png() == null && next.png() == null;
        }
        return previous.png().renderSha256().equals(next.png().renderSha256())
                && previous.png().hasElytra() == next.png().hasElytra()
                && previous.location() != null;
    }

    private static String capeId(CapeProjection.Identity identity) {
        return "optifine:" + identity.profileId().toString().replace("-", "")
                + ":" + identity.canonicalName();
    }

    private synchronized boolean current(CapeProjection.Identity identity) {
        GameSessionTokenSource.SessionIdentity session = tokenSource.currentSession();
        if (selfIdentity == null || !selfIdentity.equals(
                new CapeProjection.Identity(session.profileId(), session.profileName()))) return false;
        return identity.equals(selfIdentity) || identity.equals(tracked.get(identity.profileId()));
    }

    private void remove(CapeProjection.Identity identity) {
        queue.remove(identity);
        attempted.remove(identity);
        repeatPending.remove(identity);
        Request request = pending.remove(identity);
        if (request != null && request.future != null) request.future.cancel(true);
        Observed removed = observed.remove(identity);
        if (removed != null && removed.location() != null) {
            sink.releaseCapeTexture(identity.profileId(), CapeSource.OPTIFINE);
        }
    }

    private void cancelPending() {
        for (Request request : pending.values()) {
            if (request.future != null) request.future.cancel(true);
        }
        pending.clear();
    }

    private void releaseObservedTextures() {
        for (var entry : observed.entrySet()) {
            if (entry.getValue().location() != null) {
                sink.releaseCapeTexture(entry.getKey().profileId(), CapeSource.OPTIFINE);
                entry.setValue(new Observed(entry.getValue().png(), null));
            }
        }
    }

    private void restoreObservedTextures() {
        for (var entry : observed.entrySet()) {
            if (entry.getValue().png() == null || !current(entry.getKey())) continue;
            var png = entry.getValue().png();
            String location = sink.registerCapeTexture(entry.getKey().profileId(), CapeSource.OPTIFINE,
                    png.renderSha256(), png.bytes()).orElse(null);
            entry.setValue(new Observed(png, location));
        }
    }

    private void publish() {
        Map<CapeProjection.Identity, ObservationKey> observations = new HashMap<>();
        for (var entry : observed.entrySet()) {
            PngValidator.CapePng png = entry.getValue().png();
            observations.put(entry.getKey(), new ObservationKey(png == null ? null : png.renderSha256(),
                    png != null && png.hasElytra(), entry.getValue().location() != null));
        }
        ProjectionState state = new ProjectionState(selfIdentity, providers.cape().order(),
                providers.cape().offline(), providers.cape().minecraft(),
                providers.cape().optifine(), Map.copyOf(observations), Map.copyOf(selfCandidates));
        if (state.equals(publishedProjection)) return;
        publishedProjection = state;
        Map<CapeProjection.Identity, CapeProjection.Candidate> capes = new HashMap<>();
        if (enabled()) {
            for (var entry : observed.entrySet()) {
                if (entry.getValue().location() != null && current(entry.getKey())) {
                    capes.put(entry.getKey(), candidate(entry.getValue().location(),
                            entry.getValue().png().hasElytra()));
                }
            }
        }
        CapeProjection.publish(new CapeProjection.Snapshot(providers.cape().order(), capes,
                selfIdentity, selfCandidates.get(BuiltinProvider.OFFLINE),
                selfCandidates.get(BuiltinProvider.MINECRAFT)));
    }

    private static CapeProjection.Candidate candidate(String location, boolean hasElytra) {
        return new CapeProjection.Candidate(location, hasElytra ? location : null, hasElytra);
    }

    private record Observed(PngValidator.CapePng png, String location) {}

    private record ObservationKey(String sha256, boolean hasElytra, boolean registered) {}

    private record ProjectionState(CapeProjection.Identity selfIdentity, List<BuiltinProvider> order,
            ProviderObservation<ProviderCape> offline, ProviderObservation<ProviderCape> minecraft,
            ProviderObservation<ProviderCape> optifine,
            Map<CapeProjection.Identity, ObservationKey> observations,
            Map<BuiltinProvider, CapeProjection.Candidate> selfCandidates) {}

    private record Persisted(boolean applied, ProviderCape cape) {}

    private record Prepared(String sha256, byte[] bytes, boolean hasElytra) {}

    private static final class Request {
        private final long generation;
        private final long selfEpoch;
        private final ProviderObservation<ProviderCape> dispatchedObservation;
        private final boolean explicitRefresh;
        private Future<?> future;

        private Request(long generation, long selfEpoch,
                ProviderObservation<ProviderCape> dispatchedObservation, boolean explicitRefresh) {
            this.generation = generation;
            this.selfEpoch = selfEpoch;
            this.dispatchedObservation = dispatchedObservation;
            this.explicitRefresh = explicitRefresh;
        }
    }
}

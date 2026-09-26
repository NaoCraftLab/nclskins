package com.naocraftlab.skins.runtime;

import com.naocraftlab.skins.client.ClientExecutor;
import com.naocraftlab.skins.client.GameSessionTokenSource;
import com.naocraftlab.skins.client.PlayerAppearanceSink;
import com.naocraftlab.skins.client.PlayerAppearanceSink.CapeSource;
import com.naocraftlab.skins.client.PlayerAppearanceSink.TrackedCapePlayer;
import com.naocraftlab.skins.core.png.PngValidator;
import com.naocraftlab.skins.core.png.SneakyCapeDecoder;
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
import java.util.LinkedHashMap;
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

public final class CapeProviderCoordinator implements AutoCloseable {
    private static final int MAX_ACTIVE = 4;
    private static final int MAX_QUEUE = 256;
    private static final int MAX_TRACKED = 512;

    private final RemoteCapeScheduler optifineSchedule = new RemoteCapeScheduler(BuiltinProvider.OPTIFINE);
    private final RemoteCapeScheduler skinMcSchedule = new RemoteCapeScheduler(BuiltinProvider.SKINMC);
    private final GameSessionTokenSource tokenSource;
    private final NclSkinsStorage storage;
    private final TextureCache textures;
    private final PlayerAppearanceSink<?> sink;
    private final OwnedCapeTextures nativeTextures;
    private final ClientExecutor clientExecutor;
    private final Executor worker;
    private final CapePreparationQueue preparations;
    private final OptifineCapeReader reader;
    private final SkinMcCapeReader skinMcReader;
    private final BiFunction<UUID, String, java.util.Optional<URI>> officialCapeUri;
    private final Map<UUID, CapeProjection.Identity> tracked = new HashMap<>();
    private final Map<CapeProjection.Identity, Observed> observed = new HashMap<>();
    private final Map<CapeProjection.Identity, Observed> skinMcObserved = new HashMap<>();
    private final Map<BuiltinProvider, CapeProjection.Candidate> selfCandidates = new HashMap<>();
    private final SneakyCapeProcessor sneakyProcessor;
    private final Map<String, String> sneakyTextureKeys = new java.util.concurrent.ConcurrentHashMap<>();
    private final CapeProjection.Registration registration;
    private final Map<CapeProjection.Identity, String> sneakyVisibleSkins = new HashMap<>();
    private final Map<CapeProjection.Identity, Observed> sneakyRemote = new HashMap<>();
    private Consumer<CapeObservationPort.Observation> observationListener = ignored -> {};
    private Observed sneakySelf;
    private String sneakySkinSha;

    private AppearanceProviders providers = AppearanceProviders.initial();
    private AppearanceProviders selfCandidateProviders = AppearanceProviders.initial();
    private volatile CapeProjection.Identity selfIdentity;
    private volatile long generation;
    private long selfPreparation;
    private volatile long selfEpoch;
    private volatile long skinMcSelfEpoch;
    private long adoptionSequence;
    private long skinMcAdoptionSequence;
    private CapeProjection.Identity startedIdentity;
    private ProjectionState publishedProjection;
    private volatile boolean closed;

    public CapeProviderCoordinator(GameSessionTokenSource tokenSource, NclSkinsStorage storage,
            TextureCache textures, PlayerAppearanceSink<?> sink, ClientExecutor clientExecutor,
            Executor worker, BiFunction<UUID, String, java.util.Optional<URI>> officialCapeUri) {
        this(tokenSource, storage, textures, sink, clientExecutor, worker,
                new OptifineCapeReader(), new SkinMcCapeReader(), officialCapeUri);
    }

    CapeProviderCoordinator(GameSessionTokenSource tokenSource, NclSkinsStorage storage,
            TextureCache textures, PlayerAppearanceSink<?> sink, ClientExecutor clientExecutor,
            Executor worker, OptifineCapeReader reader) {
        this(tokenSource, storage, textures, sink, clientExecutor, worker, reader,
                new SkinMcCapeReader(),
                (accountId, capeId) -> java.util.Optional.empty());
    }

    CapeProviderCoordinator(GameSessionTokenSource tokenSource, NclSkinsStorage storage,
            TextureCache textures, PlayerAppearanceSink<?> sink, ClientExecutor clientExecutor,
            Executor worker, OptifineCapeReader reader,
            BiFunction<UUID, String, java.util.Optional<URI>> officialCapeUri) {
        this(tokenSource, storage, textures, sink, clientExecutor, worker, reader,
                new SkinMcCapeReader(), officialCapeUri);
    }

    CapeProviderCoordinator(GameSessionTokenSource tokenSource, NclSkinsStorage storage,
            TextureCache textures, PlayerAppearanceSink<?> sink, ClientExecutor clientExecutor,
            Executor worker, OptifineCapeReader reader, SkinMcCapeReader skinMcReader,
            BiFunction<UUID, String, java.util.Optional<URI>> officialCapeUri) {
        this.tokenSource = Objects.requireNonNull(tokenSource, "tokenSource");
        this.storage = Objects.requireNonNull(storage, "storage");
        this.textures = Objects.requireNonNull(textures, "textures");
        this.sink = Objects.requireNonNull(sink, "sink");
        this.nativeTextures = new OwnedCapeTextures(sink);
        this.clientExecutor = Objects.requireNonNull(clientExecutor, "clientExecutor");
        this.worker = Objects.requireNonNull(worker, "worker");
        this.preparations = new CapePreparationQueue(worker);
        this.reader = Objects.requireNonNull(reader, "reader");
        this.skinMcReader = Objects.requireNonNull(skinMcReader, "skinMcReader");
        this.officialCapeUri = Objects.requireNonNull(officialCapeUri, "officialCapeUri");
        this.sneakyProcessor = new SneakyCapeProcessor(worker, clientExecutor, this::sneakyPrepared,
                this::retainedSneakyIdentities, this::currentSneakyPreparation);
        this.registration = CapeProjection.installEvents(new CapeProjection.Events() {
            public void trackedPlayer(UUID id, String name) { dispatch(() -> CapeProviderCoordinator.this.trackedPlayer(id, name)); }
            public void playerInfoUpdated(UUID id, String name) { dispatch(() -> CapeProviderCoordinator.this.playerInfoUpdated(id, name)); }
            public void untrackedPlayer(UUID id) { dispatch(() -> CapeProviderCoordinator.this.untrackedPlayer(id)); }
            public void worldChanged() { dispatch(CapeProviderCoordinator.this::worldChanged); }
            public void worldEntered() { dispatch(CapeProviderCoordinator.this::worldEntered); }
            public void skinTextureReady(String location, int[] pixels, long revision) {
                dispatch(() -> CapeProviderCoordinator.this.skinTextureReady(location, pixels, revision));
            }
            public boolean hasSkinTexture(String location) { return CapeProviderCoordinator.this.hasSkinTexture(location); }
            public void visibleSkin(UUID id, String name, String location) { dispatch(() -> CapeProviderCoordinator.this.visibleSkin(id, name, location)); }
        });
    }

    private void dispatch(Runnable action) {
        clientExecutor.execute(() -> {
            if (!closed && registration.active()) action.run();
        });
    }

    public synchronized void start() {
        if (closed || !registration.active()) return;
        GameSessionTokenSource.SessionIdentity session = tokenSource.currentSession();
        CapeProjection.Identity identity = new CapeProjection.Identity(session.profileId(), session.profileName());
        if (identity.equals(startedIdentity)) return;
        startedIdentity = identity;
        refresh();
        scheduleSkinMcRefresh(false);
    }

    public synchronized void onCapeObservation(Consumer<CapeObservationPort.Observation> listener) {
        observationListener = Objects.requireNonNull(listener, "listener");
    }

    public synchronized java.util.Optional<java.time.Duration> cooldownRemaining(BuiltinProvider provider) {
        if (closed || !registration.active() || provider == null) return java.util.Optional.empty();
        return switch (provider) {
            case OPTIFINE -> reader.cooldownRemaining();
            case SKINMC -> skinMcReader.cooldownRemaining();
            case OFFLINE, MINECRAFT, SNEAKY -> java.util.Optional.empty();
        };
    }

    public synchronized void selfCapeCandidatesChanged(UUID accountId, String canonicalName,
            AppearanceProviders next) {
        if (closed || !registration.active() || selfIdentity == null || !selfIdentity.equals(
                new CapeProjection.Identity(accountId, canonicalName))) return;
        if (Objects.equals(selfCandidateProviders.cape().offline(), next.cape().offline())
                && Objects.equals(selfCandidateProviders.cape().minecraft(), next.cape().minecraft())) return;
        selfCandidateProviders = next;
        scheduleSelfCandidates();
    }

    public synchronized void refresh() {
        if (closed || !registration.active()) return;
        reloadConfiguration();
        if (!enabled()) return;
        if (!optifineSchedule.pending.isEmpty() || !optifineSchedule.queue.isEmpty() || optifineSchedule.sweep.hasNext()) {
            optifineSchedule.refreshPending = true;
            return;
        }
        optifineSchedule.attempted.clear();
        optifineSchedule.startSweep(false);
    }

    public synchronized void refreshSkinMc(Consumer<ProviderObservation<ProviderCape>> completion) {
        Objects.requireNonNull(completion, "completion");
        if (closed) {
            completion.accept(null);
            return;
        }
        skinMcSchedule.refreshCompletions.add(completion);
        reloadConfiguration();
        scheduleSkinMcRefresh(false);
        if (!skinMcEnabled()) skinMcSchedule.finishRefreshCompletions();
    }

    public synchronized void refresh(Consumer<ProviderObservation<ProviderCape>> completion) {
        Objects.requireNonNull(completion, "completion");
        if (closed) {
            completion.accept(null);
            return;
        }
        optifineSchedule.refreshCompletions.add(completion);
        refresh();
        if (!enabled()) optifineSchedule.finishRefreshCompletions();
    }

    public synchronized void configurationChanged() {
        if (closed || !registration.active()) return;
        boolean wasEnabled = enabled();
        boolean wasSkinMcEnabled = skinMcEnabled();
        boolean wasSneakyEnabled = providers.cape().enabled(BuiltinProvider.SNEAKY);
        CapeProjection.Identity previousSelf = selfIdentity;
        reloadConfiguration();
        if (wasSneakyEnabled != providers.cape().enabled(BuiltinProvider.SNEAKY)) {
            sneakyProcessor.invalidate();
            registration.invalidateVisibleSkins();
            scheduleSneakySelf();
            for (CapeProjection.Identity identity : List.copyOf(sneakyVisibleSkins.keySet())) {
                updateSneakyRemote(identity);
            }
        }
        if (Objects.equals(previousSelf, selfIdentity)
                && wasEnabled == enabled() && wasSkinMcEnabled == skinMcEnabled()) {
            publish();
            return;
        }
        generation++;
        sneakyProcessor.invalidate();
        optifineSchedule.queue.clear();
        optifineSchedule.cancelPending();
        optifineSchedule.repeatPending.clear();
        optifineSchedule.sweep = List.<CapeProjection.Identity>of().iterator();
        optifineSchedule.refreshPending = false;
        optifineSchedule.confirmedRefreshObservation = null;
        optifineSchedule.finishRefreshCompletions();
        skinMcSchedule.reset();
        skinMcSchedule.finishRefreshCompletions();
        if (!enabled()) {
            releaseObservedTextures();
        } else {
            if (!wasEnabled) restoreObservedTextures();
            optifineSchedule.startSweep(true);
        }
        if (!skinMcEnabled()) releaseSkinMcTextures();
        else {
            if (!wasSkinMcEnabled) restoreSkinMcTextures();
            skinMcSchedule.startSweep(true);
        }
        publish();
        optifineSchedule.pump();
        skinMcSchedule.pump();
    }

    public synchronized void adoptSharedSnapshot(UUID accountId, String canonicalName,
            AppearanceProviders snapshot) {
        if (closed || !registration.active() || selfIdentity == null || !selfIdentity.equals(
                new CapeProjection.Identity(accountId, canonicalName)) || !current(selfIdentity)) return;
        long sequence = ++adoptionSequence;
        long expectedGeneration = generation;
        CapeProjection.Identity identity = selfIdentity;
        ProviderObservation<ProviderCape> nextObservation = snapshot.cape().optifine();
        ProviderObservation<ProviderCape> previousSkinMc = providers.cape().skinmc();
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
            optifineSchedule.confirmedRefreshObservation = null;
            optifineSchedule.queue.remove(identity);
            optifineSchedule.attempted.remove(identity);
            Request request = optifineSchedule.pending.remove(identity);
            if (request != null && request.future != null) {
            request.future.cancel(true);
            purgeCancelledWork();
        }
        }
        providers = snapshot;
        scheduleSneakySelf();
        if (sneakySelf != null) notifySneakySelf(ProviderObservation.observed(
                sneakySelf.png() == null || sneakySelf.location() == null ? null
                        : new ProviderCape("sneaky:" + sneakySkinSha,
                                sneakySelf.png().renderSha256(), sneakySelf.png().hasElytra())));
        adoptSkinMcSnapshot(identity, snapshot, previousSkinMc);
        if (changedCandidates) {
            selfCandidateProviders = snapshot;
            scheduleSelfCandidates();
        }
        ProviderCape cape = nextCape;
        if (!enabled() || !nextObservation.known() || cape == null
                || !cape.id().equals(capeId(identity)) || cape.textureCacheKey() == null) {
            clearAdoptedSelf(identity, nextObservation.known() && cape == null);
            publish();
            optifineSchedule.pump();
            return;
        }
        if (!changedObservation && represented != null && represented.png() != null
                && represented.location() != null
                && cape.textureCacheKey().equals(represented.png().renderSha256())
                && Objects.equals(cape.hasElytra(), represented.png().hasElytra())) {
            publish();
            optifineSchedule.pump();
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
        preparations.submit(CapePreparationQueue.Slot.OPTIFINE_ADOPTION, preparation);
        optifineSchedule.pump();
    }

    private synchronized void completeSharedAdoption(CapeProjection.Identity identity,
            AppearanceProviders snapshot, long sequence, long expectedGeneration,
            PngValidator.CapePng png) {
        if (closed || !registration.active() || adoptionSequence != sequence || generation != expectedGeneration
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

    private void adoptSkinMcSnapshot(CapeProjection.Identity identity, AppearanceProviders snapshot,
            ProviderObservation<ProviderCape> previousObservation) {
        ProviderObservation<ProviderCape> next = snapshot.cape().skinmc();
        Observed represented = skinMcObserved.get(identity);
        ProviderCape cape = next.value();
        boolean changed = !previousObservation.equals(next)
                || cape != null && represented != null && represented.png() != null
                && (!Objects.equals(cape.textureCacheKey(), represented.png().renderSha256())
                    || !Objects.equals(cape.hasElytra(), represented.png().hasElytra()));
        if (!changed) return;
        long sequence = ++skinMcAdoptionSequence;
        long expectedGeneration = generation;
        skinMcSelfEpoch++;
        skinMcSchedule.queue.remove(identity);
        skinMcSchedule.attempted.remove(identity);
        skinMcSchedule.refreshPending = false;
        skinMcSchedule.confirmedRefreshObservation = null;
        skinMcSchedule.finishRefreshCompletions();
        Request request = skinMcSchedule.pending.remove(identity);
        if (request != null && request.future != null) {
            request.future.cancel(true);
            purgeCancelledWork();
        }
        removeSkinMc(identity);
        if (!skinMcEnabled() || !next.known() || cape == null
                || !cape.id().equals(skinMcCapeId(identity)) || cape.textureCacheKey() == null) {
            if (next.known() && cape == null) skinMcObserved.put(identity, new Observed(null, null));
            publish();
            return;
        }
        preparations.submit(CapePreparationQueue.Slot.SKINMC_ADOPTION, () -> {
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
            clientExecutor.execute(() -> completeSkinMcSharedAdoption(identity, snapshot,
                    sequence, expectedGeneration, validated));
        });
    }

    private synchronized void completeSkinMcSharedAdoption(CapeProjection.Identity identity,
            AppearanceProviders snapshot, long sequence, long expectedGeneration,
            PngValidator.CapePng png) {
        if (closed || !registration.active() || skinMcAdoptionSequence != sequence || generation != expectedGeneration
                || !identity.equals(selfIdentity) || !current(identity) || !skinMcEnabled()
                || !providers.cape().skinmc().equals(snapshot.cape().skinmc())) return;
        if (png == null) {
            removeSkinMc(identity);
            publish();
            return;
        }
        replaceSkinMc(identity, new Observed(png, null));
    }

    private void clearAdoptedSelf(CapeProjection.Identity identity, boolean knownAbsent) {
        Observed old = observed.remove(identity);
        if (old != null && old.location() != null) {
            nativeTextures.release(identity.profileId(), CapeSource.OPTIFINE);
        }
        if (knownAbsent) observed.put(identity, new Observed(null, null));
    }

    public synchronized void trackedPlayer(UUID profileId, String canonicalName) {
        if (closed || !registration.active()) return;
        if (!tracked.containsKey(profileId) && tracked.size() >= MAX_TRACKED) return;
        CapeProjection.Identity identity = new CapeProjection.Identity(profileId, canonicalName);
        CapeProjection.Identity previous = tracked.put(profileId, identity);
        if (previous != null && !previous.equals(identity)) {
            retireTrackedIdentity(previous);
        }
        if (enabled() && !observed.containsKey(identity) && !optifineSchedule.attempted.contains(identity)) {
            if (optifineSchedule.queue.size() >= MAX_QUEUE) {
                optifineSchedule.unknownSweepPending = true;
                optifineSchedule.repeatPending.add(identity);
            } else {
                optifineSchedule.enqueue(identity, false);
            }
            optifineSchedule.pump();
        }
        if (skinMcEnabled() && !skinMcObserved.containsKey(identity)
                && !skinMcSchedule.attempted.contains(identity)) {
            skinMcSchedule.enqueue(identity, false);
            skinMcSchedule.pump();
        }
        updateSneakyRemote(identity);
    }

    public synchronized void playerInfoUpdated(UUID profileId, String canonicalName) {
        if (closed || !registration.active() || !enabled() && !skinMcEnabled()) return;
        CapeProjection.Identity identity = new CapeProjection.Identity(profileId, canonicalName);
        CapeProjection.Identity previous = tracked.get(profileId);
        if (previous == null || !previous.equals(identity)) {
            trackedPlayer(profileId, canonicalName);
            return;
        }
        if (identity.equals(selfIdentity)) return;
        if (skinMcEnabled()) {
            if (skinMcSchedule.pending.containsKey(identity) || skinMcReader.cooldownRemaining().isPresent()) {
                skinMcSchedule.repeatPending.add(identity);
            } else if (!skinMcSchedule.queue.contains(identity)) {
                skinMcSchedule.attempted.remove(identity);
                skinMcSchedule.enqueue(identity, false);
            }
            skinMcSchedule.pump();
        }
        if (!enabled()) return;
        if (optifineSchedule.pending.containsKey(identity)) {
            optifineSchedule.repeatPending.add(identity);
            return;
        }
        if (optifineSchedule.queue.contains(identity)) return;
        optifineSchedule.attempted.remove(identity);
        if (optifineSchedule.queue.size() >= MAX_QUEUE) optifineSchedule.repeatPending.add(identity);
        else optifineSchedule.enqueue(identity, false);
        optifineSchedule.pump();
    }

    public synchronized void untrackedPlayer(UUID profileId) {
        CapeProjection.Identity removed = tracked.remove(profileId);
        if (removed != null && !removed.equals(selfIdentity)) {
            retireTrackedIdentity(removed);
            publish();
            optifineSchedule.pump();
            skinMcSchedule.pump();
        }
    }

    public synchronized void worldChanged() {
        if (closed || !registration.active()) return;
        generation++;
        sneakyProcessor.invalidate();
        optifineSchedule.queue.clear();
        optifineSchedule.cancelPending();
        optifineSchedule.sweep = List.<CapeProjection.Identity>of().iterator();
        optifineSchedule.refreshPending = false;
        optifineSchedule.confirmedRefreshObservation = null;
        optifineSchedule.finishRefreshCompletions();
        for (CapeProjection.Identity identity : List.copyOf(observed.keySet())) {
            if (!identity.equals(selfIdentity)) remove(identity);
        }
        tracked.clear();
        for (CapeProjection.Identity identity : List.copyOf(sneakyRemote.keySet())) removeSneakyRemote(identity);
        sneakyVisibleSkins.clear();
        optifineSchedule.attempted.clear();
        optifineSchedule.repeatPending.clear();
        skinMcSchedule.reset();
        for (CapeProjection.Identity identity : List.copyOf(skinMcObserved.keySet())) {
            if (!identity.equals(selfIdentity)) removeSkinMc(identity);
        }
        scheduleSneakySelf();
        scheduleSelfCandidates();
        publish();
    }

    public synchronized void worldEntered() {
        worldChanged();
        if (!closed && enabled()) optifineSchedule.startSweep(true);
        if (!closed && skinMcEnabled()) skinMcSchedule.startSweep(true);
    }

    @Override
    public synchronized void close() {
        if (!clientExecutor.isClientThread()) {
            clientExecutor.execute(this::close);
            return;
        }
        if (closed) return;
        closed = true;
        generation++;
        sneakyProcessor.invalidate();
        optifineSchedule.queue.clear();
        optifineSchedule.cancelPending();
        tracked.clear();
        optifineSchedule.attempted.clear();
        optifineSchedule.repeatPending.clear();
        skinMcSchedule.reset();
        optifineSchedule.sweep = List.<CapeProjection.Identity>of().iterator();
        releaseObservedTextures();
        releaseSkinMcTextures();
        clearSneakySelf();
        for (CapeProjection.Identity identity : List.copyOf(sneakyRemote.keySet())) removeSneakyRemote(identity);
        sneakyVisibleSkins.clear();
        sneakyProcessor.close();
        sneakyTextureKeys.clear();
        preparations.close();
        observed.clear();
        skinMcObserved.clear();
        cancelSelfPreparation();
        releaseSelfCandidates();
        registration.close();
    }

    private void purgeCancelledWork() {
        if (worker instanceof java.util.concurrent.ThreadPoolExecutor pool) pool.purge();
    }

    private boolean enabled() {
        return providers.cape().enabled(BuiltinProvider.OPTIFINE);
    }

    private boolean skinMcEnabled() {
        return providers.cape().enabled(BuiltinProvider.SKINMC);
    }

    private void reloadConfiguration() {
        GameSessionTokenSource.SessionIdentity session = tokenSource.currentSession();
        CapeProjection.Identity nextSelf = new CapeProjection.Identity(session.profileId(), session.profileName());
        boolean identityChanged = !nextSelf.equals(selfIdentity);
        if (identityChanged) {
            registration.invalidateVisibleSkins();
            reader.accountChanged();
            skinMcReader.accountChanged();
            generation++;
            sneakyProcessor.invalidate();
            selfEpoch++;
            skinMcSelfEpoch++;
            optifineSchedule.queue.clear();
            optifineSchedule.cancelPending();
            tracked.clear();
            optifineSchedule.attempted.clear();
            optifineSchedule.repeatPending.clear();
            skinMcSchedule.reset();
            optifineSchedule.sweep = List.<CapeProjection.Identity>of().iterator();
            optifineSchedule.refreshPending = false;
            optifineSchedule.unknownSweepPending = false;
            releaseObservedTextures();
            releaseSkinMcTextures();
            clearSneakySelf();
            for (CapeProjection.Identity identity : List.copyOf(sneakyRemote.keySet())) removeSneakyRemote(identity);
            sneakyVisibleSkins.clear();
            observed.clear();
            skinMcObserved.clear();
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
            scheduleSneakySelf();
            if (sneakySelf != null) notifySneakySelf(ProviderObservation.observed(
                    sneakySelf.png() == null || sneakySelf.location() == null ? null
                            : new ProviderCape("sneaky:" + sneakySkinSha,
                                    sneakySelf.png().renderSha256(), sneakySelf.png().hasElytra())));
            restoreSelfObservation();
            restoreSkinMcSelfObservation();
            if (candidateInputsChanged) {
                selfCandidateProviders = loaded;
                scheduleSelfCandidates();
            }
        } catch (IOException failure) {
            providers = AppearanceProviders.initial();
            clearSneakySelf();
        }
        publish();
    }

    private boolean currentSneakyPreparation() {
        return !closed && registration.active() && selfIdentity != null && current(selfIdentity)
                && providers.cape().enabled(BuiltinProvider.SNEAKY);
    }

    private Set<String> retainedSneakyIdentities() {
        Set<String> identities = new HashSet<>();
        for (String location : sneakyVisibleSkins.values()) identities.add(sneakyKey(location));
        if (sneakySkinSha != null) identities.add("asset:" + sneakySkinSha);
        return identities;
    }

    private void clearSneakySelf() {
        if (sneakySkinSha != null) sneakyProcessor.forget("asset:" + sneakySkinSha);
        if (selfIdentity != null && sneakySelf != null && sneakySelf.location() != null) {
            nativeTextures.release(selfIdentity.profileId(), CapeSource.SNEAKY);
        }
        sneakySelf = null;
        sneakySkinSha = null;
    }

    private void scheduleSneakySelf() {
        if (selfIdentity == null || !providers.cape().enabled(BuiltinProvider.SNEAKY)) {
            clearSneakySelf();
            return;
        }
        String selected = providers.skin().resolve()
                .map(resolved -> resolved.value().sha256()).orElse(null);
        if (Objects.equals(sneakySkinSha, selected)
                && (selected == null || sneakySelf != null || sneakyProcessor.contains("asset:" + selected))) return;
        clearSneakySelf();
        sneakySkinSha = selected;
        notifySneakySelf(ProviderObservation.unknown());
        if (selected == null) return;
        sneakyProcessor.prepare("asset:" + selected, () -> {
            byte[] skin = storage.readAsset(selected);
            PngValidator.CapePng cape = new SneakyCapeDecoder().decode(skin).orElse(null);
            String cacheKey = cape == null ? null : textures.storeObservedCape(cape);
            return new SneakyCapeProcessor.Prepared(cape, cacheKey);
        });
    }

    private void completeSneakySelf(String key) {
        if (selfIdentity == null || !current(selfIdentity) || sneakySkinSha == null
                || !key.equals("asset:" + sneakySkinSha)
                || !providers.cape().enabled(BuiltinProvider.SNEAKY)) return;
        SneakyCapeProcessor.Prepared prepared = sneakyProcessor.prepared(key);
        if (prepared == null || sneakySelf != null) return;
        PngValidator.CapePng cape = prepared.cape();
        String location = cape == null ? null : nativeTextures.register(selfIdentity.profileId(),
                CapeSource.SNEAKY, cape.renderSha256(), cape.bytes()).orElse(null);
        sneakySelf = new Observed(cape, location);
        ProviderCape observedCape = cape != null && location != null
                ? new ProviderCape("sneaky:" + sneakySkinSha, prepared.cacheKey(), cape.hasElytra()) : null;
        notifySneakySelf(ProviderObservation.observed(observedCape));
    }

    private void notifySneakySelf(ProviderObservation<ProviderCape> observation) {
        if (selfIdentity == null || !providers.cape().enabled(BuiltinProvider.SNEAKY)) return;
        observationListener.accept(new CapeObservationPort.Observation(BuiltinProvider.SNEAKY,
                selfIdentity.profileId(), selfIdentity.canonicalName(),
                providers.cape().configurationRevision(), sneakySkinSha, observation));
    }

    public synchronized void skinTextureReady(String skinLocation, int[] argb) {
        skinTextureReady(skinLocation, argb, 0);
    }

    private synchronized void skinTextureReady(String skinLocation, int[] argb, long revision) {
        if (!currentSneakyPreparation() || skinLocation == null || argb == null || argb.length != 64 * 64) return;
        String key = revision == 0 ? skinLocation : "native:" + revision;
        String previous = sneakyTextureKeys.get(skinLocation);
        if (!sneakyTextureKeys.containsKey(skinLocation) && sneakyTextureKeys.size() >= MAX_TRACKED) {
            String retired = sneakyTextureKeys.remove(sneakyTextureKeys.keySet().iterator().next());
            sneakyProcessor.forget(retired);
        }
        sneakyTextureKeys.put(skinLocation, key);
        if (previous != null && !previous.equals(key)) {
            sneakyProcessor.forget(previous);
            for (var entry : List.copyOf(sneakyVisibleSkins.entrySet())) {
                if (skinLocation.equals(entry.getValue())) updateSneakyRemote(entry.getKey());
            }
            publish();
        }
        sneakyProcessor.prepare(key, argb);
    }

    private synchronized void sneakyPrepared(String skinLocation) {
        if (closed || !registration.active()) return;
        completeSneakySelf(skinLocation);
        for (var entry : List.copyOf(sneakyVisibleSkins.entrySet())) {
            if (skinLocation.equals(sneakyKey(entry.getValue()))) updateSneakyRemote(entry.getKey());
        }
        publish();
    }

    private String sneakyKey(String location) {
        return sneakyTextureKeys.getOrDefault(location, location);
    }

    public boolean hasSkinTexture(String skinLocation) {
        return sneakyProcessor.contains(sneakyKey(skinLocation));
    }

    public synchronized void visibleSkin(UUID profileId, String canonicalName, String skinLocation) {
        if (closed || !registration.active() || profileId == null || canonicalName == null) return;
        CapeProjection.Identity identity = new CapeProjection.Identity(profileId, canonicalName);
        if (identity.equals(selfIdentity) || !current(identity)) return;
        String previous = skinLocation == null ? sneakyVisibleSkins.remove(identity)
                : sneakyVisibleSkins.put(identity, skinLocation);
        if (previous != null && !Objects.equals(previous, skinLocation)
                && !sneakyVisibleSkins.containsValue(previous)) sneakyProcessor.forget(sneakyKey(previous));
        updateSneakyRemote(identity);
        publish();
    }

    private void updateSneakyRemote(CapeProjection.Identity identity) {
        String skinLocation = sneakyVisibleSkins.get(identity);
        SneakyCapeProcessor.Prepared texture = skinLocation == null ? null : sneakyProcessor.prepared(sneakyKey(skinLocation));
        if (!providers.cape().enabled(BuiltinProvider.SNEAKY) || !current(identity)
                || texture == null || texture.cape() == null) {
            removeSneakyRemote(identity);
            return;
        }
        PngValidator.CapePng cape = texture.cape();
        Observed previous = sneakyRemote.get(identity);
        if (previous != null && previous.png() != null
                && previous.png().renderSha256().equals(cape.renderSha256())
                && previous.location() != null) return;
        removeSneakyRemote(identity);
        String location = nativeTextures.register(identity.profileId(), CapeSource.SNEAKY,
                cape.renderSha256(), cape.bytes()).orElse(null);
        sneakyRemote.put(identity, new Observed(cape, location));
    }

    private void removeSneakyRemote(CapeProjection.Identity identity) {
        Observed removed = sneakyRemote.remove(identity);
        if (removed != null && removed.location() != null) {
            nativeTextures.release(identity.profileId(), CapeSource.SNEAKY);
        }
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
            String location = enabled() ? nativeTextures.register(selfIdentity.profileId(),
                    CapeSource.OPTIFINE, png.renderSha256(), png.bytes()).orElse(null) : null;
            observed.put(selfIdentity, new Observed(png, location));
        } catch (IOException | com.naocraftlab.skins.core.png.PngValidationException invalid) {
            observed.remove(selfIdentity);
        }
    }

    private void restoreSkinMcSelfObservation() {
        var saved = providers.cape().skinmc();
        if (!saved.known() || skinMcObserved.containsKey(selfIdentity)) return;
        ProviderCape cape = saved.value();
        if (cape == null) {
            skinMcObserved.put(selfIdentity, new Observed(null, null));
            return;
        }
        if (!cape.id().equals(skinMcCapeId(selfIdentity)) || cape.textureCacheKey() == null) return;
        try {
            byte[] bytes = textures.readIfCached(cape.textureCacheKey()).orElse(null);
            if (bytes == null) return;
            PngValidator.CapePng png = new PngValidator().projectCanonicalCape(bytes);
            if (!png.renderSha256().equals(cape.textureCacheKey())
                    || !Objects.equals(png.hasElytra(), cape.hasElytra())) return;
            String location = skinMcEnabled() ? nativeTextures.register(selfIdentity.profileId(),
                    CapeSource.SKINMC, png.renderSha256(), png.bytes()).orElse(null) : null;
            skinMcObserved.put(selfIdentity, new Observed(png, location));
        } catch (IOException | com.naocraftlab.skins.core.png.PngValidationException invalid) {
            skinMcObserved.remove(selfIdentity);
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
        preparations.submit(CapePreparationQueue.Slot.SELF_CANDIDATES, preparation);
    }

    private void cancelSelfPreparation() {
        selfPreparation++;
    }

    private synchronized void applySelfCandidates(CapeProjection.Identity identity,
            long expectedGeneration, long token, Map<BuiltinProvider, Prepared> prepared) {
        if (closed || !registration.active() || generation != expectedGeneration || selfPreparation != token
                || !identity.equals(selfIdentity) || !current(identity)) return;
        for (var entry : prepared.entrySet()) {
            Prepared value = entry.getValue();
            nativeTextures.register(identity.profileId(), CapeSource.valueOf(entry.getKey().name()),
                    value.sha256(), value.bytes()).ifPresent(location -> selfCandidates.put(
                    entry.getKey(), candidate(location, value.hasElytra())));
        }
        publish();
    }

    private void releaseSelfCandidates() {
        if (selfIdentity != null) {
            for (BuiltinProvider provider : selfCandidates.keySet()) {
                nativeTextures.release(selfIdentity.profileId(), CapeSource.valueOf(provider.name()));
            }
        }
        selfCandidates.clear();
    }

    private void scheduleSkinMcRefresh(boolean unknownOnly) {
        if (!skinMcEnabled()) return;
        if (!skinMcSchedule.pending.isEmpty() || !skinMcSchedule.queue.isEmpty() || skinMcSchedule.sweep.hasNext()) {
            skinMcSchedule.refreshPending = true;
            return;
        }
        skinMcSchedule.attempted.clear();
        skinMcSchedule.startSweep(unknownOnly);
    }

    private void replaceSkinMc(CapeProjection.Identity identity, Observed next) {
        Observed previous = skinMcObserved.get(identity);
        if (sameObservation(previous, next)) return;
        if (previous != null && previous.location() != null) {
            nativeTextures.release(identity.profileId(), CapeSource.SKINMC);
        }
        if (next.png() != null) {
            var registered = nativeTextures.register(identity.profileId(), CapeSource.SKINMC,
                    next.png().renderSha256(), next.png().bytes());
            next = new Observed(next.png(), registered.orElse(null));
        }
        skinMcObserved.put(identity, next);
        if (identity.equals(selfIdentity)) {
            ProviderCape cape = next.png() == null ? null
                    : new ProviderCape(skinMcCapeId(identity), next.png().renderSha256(),
                            next.png().hasElytra());
            providers = new AppearanceProviders(providers.skin(), providers.cape().observeSkinmc(cape));
            observationListener.accept(new CapeObservationPort.Observation(BuiltinProvider.SKINMC, identity.profileId(),
                    identity.canonicalName(), providers.cape().configurationRevision(), null, ProviderObservation.observed(cape)));
        }
        publish();
    }

    private void replace(CapeProjection.Identity identity, Observed next) {
        Observed previous = observed.get(identity);
        if (sameObservation(previous, next)) return;
        if (previous != null && previous.location() != null) {
            nativeTextures.release(identity.profileId(), CapeSource.OPTIFINE);
        }
        if (next.png() != null) {
            var registered = nativeTextures.register(identity.profileId(), CapeSource.OPTIFINE,
                    next.png().renderSha256(), next.png().bytes());
            next = new Observed(next.png(), registered.orElse(null));
        }
        observed.put(identity, next);
        if (identity.equals(selfIdentity)) {
            ProviderCape cape = next.png() == null ? null
                    : new ProviderCape(capeId(identity), next.png().renderSha256(), next.png().hasElytra());
            providers = new AppearanceProviders(providers.skin(), providers.cape().observeOptifine(cape));
            observationListener.accept(new CapeObservationPort.Observation(BuiltinProvider.OPTIFINE, identity.profileId(),
                    identity.canonicalName(), providers.cape().configurationRevision(), null, ProviderObservation.observed(cape)));
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

    private static String skinMcCapeId(CapeProjection.Identity identity) {
        return "skinmc:" + identity.profileId().toString().replace("-", "");
    }

    private synchronized boolean current(CapeProjection.Identity identity) {
        GameSessionTokenSource.SessionIdentity session = tokenSource.currentSession();
        if (selfIdentity == null || !selfIdentity.equals(
                new CapeProjection.Identity(session.profileId(), session.profileName()))) return false;
        return identity.equals(selfIdentity) || identity.equals(tracked.get(identity.profileId()));
    }

    private void retireTrackedIdentity(CapeProjection.Identity identity) {
        remove(identity);
        removeSkinMc(identity);
        removeSneakyRemote(identity);
        String skin = sneakyVisibleSkins.remove(identity);
        if (skin != null && !sneakyVisibleSkins.containsValue(skin)) sneakyProcessor.forget(sneakyKey(skin));
    }

    private void remove(CapeProjection.Identity identity) {
        optifineSchedule.queue.remove(identity);
        optifineSchedule.attempted.remove(identity);
        optifineSchedule.repeatPending.remove(identity);
        Request request = optifineSchedule.pending.remove(identity);
        if (request != null && request.future != null) {
            request.future.cancel(true);
            purgeCancelledWork();
        }
        Observed removed = observed.remove(identity);
        if (removed != null && removed.location() != null) {
            nativeTextures.release(identity.profileId(), CapeSource.OPTIFINE);
        }
    }

    private void removeSkinMc(CapeProjection.Identity identity) {
        skinMcSchedule.queue.remove(identity);
        skinMcSchedule.attempted.remove(identity);
        skinMcSchedule.repeatPending.remove(identity);
        Request request = skinMcSchedule.pending.remove(identity);
        if (request != null && request.future != null) {
            request.future.cancel(true);
            purgeCancelledWork();
        }
        Observed removed = skinMcObserved.remove(identity);
        if (removed != null && removed.location() != null) {
            nativeTextures.release(identity.profileId(), CapeSource.SKINMC);
        }
    }

    private void releaseObservedTextures() {
        for (var entry : observed.entrySet()) {
            if (entry.getValue().location() != null) {
                nativeTextures.release(entry.getKey().profileId(), CapeSource.OPTIFINE);
                entry.setValue(new Observed(entry.getValue().png(), null));
            }
        }
    }

    private void releaseSkinMcTextures() {
        for (var entry : skinMcObserved.entrySet()) {
            if (entry.getValue().location() != null) {
                nativeTextures.release(entry.getKey().profileId(), CapeSource.SKINMC);
                entry.setValue(new Observed(entry.getValue().png(), null));
            }
        }
    }

    private void restoreObservedTextures() {
        for (var entry : observed.entrySet()) {
            if (entry.getValue().png() == null || !current(entry.getKey())) continue;
            var png = entry.getValue().png();
            String location = nativeTextures.register(entry.getKey().profileId(), CapeSource.OPTIFINE,
                    png.renderSha256(), png.bytes()).orElse(null);
            entry.setValue(new Observed(png, location));
        }
    }

    private void restoreSkinMcTextures() {
        for (var entry : skinMcObserved.entrySet()) {
            if (entry.getValue().png() == null || !current(entry.getKey())) continue;
            var png = entry.getValue().png();
            String location = nativeTextures.register(entry.getKey().profileId(), CapeSource.SKINMC,
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
        Map<CapeProjection.Identity, ObservationKey> skinMcObservations = new HashMap<>();
        for (var entry : skinMcObserved.entrySet()) {
            PngValidator.CapePng png = entry.getValue().png();
            skinMcObservations.put(entry.getKey(), new ObservationKey(
                    png == null ? null : png.renderSha256(),
                    png != null && png.hasElytra(), entry.getValue().location() != null));
        }
        Map<CapeProjection.Identity, ObservationKey> sneakyRemoteObservations = new HashMap<>();
        for (var entry : sneakyRemote.entrySet()) {
            PngValidator.CapePng png = entry.getValue().png();
            sneakyRemoteObservations.put(entry.getKey(), new ObservationKey(
                    png == null ? null : png.renderSha256(),
                    png != null && png.hasElytra(), entry.getValue().location() != null));
        }
        ProjectionState state = new ProjectionState(selfIdentity, providers.cape().order(),
                providers.cape().offline(), providers.cape().minecraft(),
                providers.cape().optifine(), providers.cape().skinmc(),
                sneakySkinSha, sneakySelf == null || sneakySelf.png() == null ? null
                        : new ObservationKey(sneakySelf.png().renderSha256(),
                                sneakySelf.png().hasElytra(), sneakySelf.location() != null),
                Map.copyOf(observations), Map.copyOf(skinMcObservations),
                Map.copyOf(sneakyRemoteObservations), Map.copyOf(selfCandidates));
        if (state.equals(publishedProjection)) return;
        publishedProjection = state;
        Map<CapeProjection.Identity, CapeProjection.Candidate> capes = new HashMap<>();
        Map<CapeProjection.Identity, CapeProjection.Candidate> skinMcCapes = new HashMap<>();
        Map<CapeProjection.Identity, CapeProjection.Candidate> sneakyCapes = new HashMap<>();
        if (enabled()) {
            for (var entry : observed.entrySet()) {
                if (entry.getValue().location() != null && current(entry.getKey())) {
                    capes.put(entry.getKey(), candidate(entry.getValue().location(),
                            entry.getValue().png().hasElytra()));
                }
            }
        }
        if (skinMcEnabled()) {
            for (var entry : skinMcObserved.entrySet()) {
                if (entry.getValue().location() != null && current(entry.getKey())) {
                    skinMcCapes.put(entry.getKey(), candidate(entry.getValue().location(),
                            entry.getValue().png().hasElytra()));
                }
            }
        }
        if (providers.cape().enabled(BuiltinProvider.SNEAKY) && selfIdentity != null
                && sneakySelf != null && sneakySelf.location() != null) {
            sneakyCapes.put(selfIdentity, candidate(sneakySelf.location(), sneakySelf.png().hasElytra()));
        }
        if (providers.cape().enabled(BuiltinProvider.SNEAKY)) {
            for (var entry : sneakyRemote.entrySet()) {
                if (entry.getValue().location() != null && current(entry.getKey())) {
                    sneakyCapes.put(entry.getKey(), candidate(entry.getValue().location(),
                            entry.getValue().png().hasElytra()));
                }
            }
        }
        registration.publish(new CapeProjection.Snapshot(providers.cape().order(), capes, skinMcCapes, sneakyCapes,
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
            ProviderObservation<ProviderCape> skinmc,
            String sneakySkinSha,
            ObservationKey sneakyObservation,
            Map<CapeProjection.Identity, ObservationKey> observations,
            Map<CapeProjection.Identity, ObservationKey> skinMcObservations,
            Map<CapeProjection.Identity, ObservationKey> sneakyRemoteObservations,
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
    private Persisted persistRemoteSelf(BuiltinProvider provider, CapeProjection.Identity identity, Request request,
            RemoteRead outcome) {
        if (closed || !registration.active() || request.generation != generation || request.selfEpoch != (provider == BuiltinProvider.OPTIFINE ? selfEpoch : skinMcSelfEpoch)
                || !current(identity)) {
            return new Persisted(false, null);
        }
        try {
            ProviderCape cape = null;
            if (outcome.kind() == ReadKind.PRESENT) {
                String key = textures.storeObservedCape(outcome.cape());
                cape = new ProviderCape((provider == BuiltinProvider.OPTIFINE ? capeId(identity) : skinMcCapeId(identity)), key, outcome.cape().hasElytra());
            }
            ProviderCape confirmed = cape;
            boolean[] applied = {false};
            storage.updateAppearance(identity.profileId(), current -> {
                GameSessionTokenSource.SessionIdentity session = tokenSource.currentSession();
                if (closed || !registration.active() || request.generation != generation || request.selfEpoch != (provider == BuiltinProvider.OPTIFINE ? selfEpoch : skinMcSelfEpoch)
                        || !identity.equals(new CapeProjection.Identity(
                                session.profileId(), session.profileName()))
                        || !current.providers().cape().enabled(provider)
                        || !current.providers().cape().observation(provider).equals(request.dispatchedObservation)) return current;
                applied[0] = true;
                return current.withProviders(new AppearanceProviders(current.providers().skin(),
                        provider == BuiltinProvider.OPTIFINE ? current.providers().cape().observeOptifine(confirmed) : current.providers().cape().observeSkinmc(confirmed)));
            });
            return new Persisted(applied[0], cape);
        } catch (IOException | com.naocraftlab.skins.core.png.PngValidationException unavailable) {
            return new Persisted(false, null);
        }
    }

    private enum ReadKind { PRESENT, ABSENT, FAILURE }
    private record RemoteRead(ReadKind kind, PngValidator.CapePng cape, boolean rateLimited) {}

    private final class RemoteCapeScheduler {
        private final ArrayDeque<CapeProjection.Identity> queue = new ArrayDeque<>();
        private final Map<CapeProjection.Identity, Request> pending = new HashMap<>();
        private final Set<CapeProjection.Identity> attempted = new HashSet<>();
        private final Set<CapeProjection.Identity> repeatPending = new LinkedHashSet<>();
        private Iterator<CapeProjection.Identity> sweep = List.<CapeProjection.Identity>of().iterator();
        private final List<Consumer<ProviderObservation<ProviderCape>>> refreshCompletions = new ArrayList<>();
        private ProviderObservation<ProviderCape> confirmedRefreshObservation;
        private boolean explicitSweep;
        private boolean refreshPending;
        private boolean unknownSweepPending;
        private boolean unknownOnly;
        private boolean pumping;

        private final BuiltinProvider provider;
        private RemoteCapeScheduler(BuiltinProvider provider) { this.provider = provider; }
        private RemoteCapeScheduler other() { return provider == BuiltinProvider.OPTIFINE ? skinMcSchedule : optifineSchedule; }
        private boolean enabled() { return providers.cape().enabled(provider); }
        private long epoch() { return provider == BuiltinProvider.OPTIFINE ? selfEpoch : skinMcSelfEpoch; }
        private Map<CapeProjection.Identity, Observed> observations() { return provider == BuiltinProvider.OPTIFINE ? observed : skinMcObserved; }
        private java.util.Optional<java.time.Duration> cooldownRemaining() { return CapeProviderCoordinator.this.cooldownRemaining(provider); }
        private long accountEpoch() { return provider == BuiltinProvider.OPTIFINE ? reader.accountEpoch() : skinMcReader.accountEpoch(); }
        private RemoteRead read(CapeProjection.Identity identity, long accountEpoch) {
            if (provider == BuiltinProvider.OPTIFINE) {
                var result = reader.read(identity.canonicalName(), accountEpoch);
                return new RemoteRead(ReadKind.valueOf(result.kind().name()), result.cape(),
                        result.failure() == OptifineCapeReader.Failure.RATE_LIMITED);
            }
            var result = skinMcReader.read(identity.profileId(), accountEpoch);
            return new RemoteRead(ReadKind.valueOf(result.kind().name()), result.cape(),
                    result.failure() == SkinMcCapeReader.Failure.RATE_LIMITED);
        }
        private Persisted persist(CapeProjection.Identity identity, Request request, RemoteRead result) {
            return persistRemoteSelf(provider, identity, request, result);
        }
        private void replaceObservation(CapeProjection.Identity identity, Observed next) {
            if (provider == BuiltinProvider.OPTIFINE) replace(identity, next);
            else replaceSkinMc(identity, next);
        }
        private void reset() {
            queue.clear();
            cancelPending();
            attempted.clear();
            repeatPending.clear();
            sweep = List.<CapeProjection.Identity>of().iterator();
            refreshPending = false;
            unknownSweepPending = false;
            confirmedRefreshObservation = null;
            finishRefreshCompletions();
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
                if (identities.size() > MAX_TRACKED) break;
                CapeProjection.Identity identity = new CapeProjection.Identity(
                        player.profileId(), player.canonicalName());
                CapeProjection.Identity previous = tracked.put(player.profileId(), identity);
                if (previous != null && !previous.equals(identity)) {
                    retireTrackedIdentity(previous);
                }
                if (seen.add(identity)) identities.add(identity);
            }
            for (CapeProjection.Identity identity : List.copyOf(tracked.values())) {
                if (!seen.contains(identity)) {
                    tracked.remove(identity.profileId());
                    retireTrackedIdentity(identity);
                }
            }
            sweep = identities.iterator();
            fillQueue();
            pump();
            if (cooldownRemaining().isPresent()) finishRefreshCompletions();
        }

        private void enqueue(CapeProjection.Identity identity, boolean discovery) {
            if (cooldownRemaining().isPresent()) {
                if (identity.equals(selfIdentity) || tracked.containsValue(identity)) repeatPending.add(identity);
                return;
            }
            if (queue.size() >= MAX_QUEUE || pending.containsKey(identity) || queue.contains(identity)
                    || discovery && unknownOnly
                    && (observations().containsKey(identity) || attempted.contains(identity))) return;
            queue.add(identity);
        }

        private void fillQueue() {
            if (cooldownRemaining().isPresent()) {
                while (!queue.isEmpty()) repeatPending.add(queue.remove());
                while (sweep.hasNext()) {
                    CapeProjection.Identity identity = sweep.next();
                    if (current(identity)) repeatPending.add(identity);
                }
                return;
            }
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
                while (enabled() && cooldownRemaining().isEmpty()
                        && pending.size() + other().pending.size() < MAX_ACTIVE && !queue.isEmpty()) {
                    CapeProjection.Identity identity = queue.remove();
                    if (!current(identity) || pending.containsKey(identity)) continue;
                    long requestGeneration = generation;
                    Request request = new Request(requestGeneration, epoch(),
                            identity.equals(selfIdentity) ? providers.cape().observation(provider) : null,
                            explicitSweep);
                    long accountEpoch = accountEpoch();
                    pending.put(identity, request);
                    Runnable task = () -> {
                        if (closed || !registration.active() || request.generation != generation
                                || identity.equals(selfIdentity) && request.selfEpoch != epoch()
                                || !current(identity)) return;
                        RemoteRead outcome = read(identity, accountEpoch);
                        if (identity.equals(selfIdentity)
                                && outcome.kind() != ReadKind.FAILURE) {
                            Persisted persisted = persist(identity, request, outcome);
                            clientExecutor.execute(() -> { synchronized (CapeProviderCoordinator.this) { complete(identity, request, outcome, persisted); } });
                        } else {
                            clientExecutor.execute(() -> { synchronized (CapeProviderCoordinator.this) { complete(identity, request, outcome, null); } });
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

        private void complete(CapeProjection.Identity identity, Request request,
                RemoteRead outcome, Persisted persisted) {
            if (pending.get(identity) != request) return;
            pending.remove(identity);
            if (!closed && registration.active() && request.generation == generation
                    && (!identity.equals(selfIdentity) || request.selfEpoch == epoch())
                    && enabled() && current(identity)) {
                attempted.add(identity);
                if (outcome.rateLimited()) {
                    repeatPending.add(identity);
                }
                if (outcome.kind() == ReadKind.ABSENT) {
                    if (!identity.equals(selfIdentity) || persisted != null && persisted.applied()) {
                        replaceObservation(identity, new Observed(null, null));
                        if (identity.equals(selfIdentity) && request.explicitRefresh) {
                            confirmedRefreshObservation = ProviderObservation.observed(null);
                        }
                    }
                } else if (outcome.kind() == ReadKind.PRESENT) {
                    if (!identity.equals(selfIdentity) || persisted != null && persisted.applied()) {
                        replaceObservation(identity, new Observed(outcome.cape(), null));
                        if (identity.equals(selfIdentity) && request.explicitRefresh) {
                            confirmedRefreshObservation = ProviderObservation.observed(persisted.cape());
                        }
                    }
                }
            }
            fillQueue();
            if (cooldownRemaining().isPresent()) refreshPending = false;
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
            if (!other().queue.isEmpty() || other().sweep.hasNext()) other().pump();
        }

        private void finishRefreshCompletions() {
            if (refreshCompletions.isEmpty()) return;
            List<Consumer<ProviderObservation<ProviderCape>>> completions = List.copyOf(refreshCompletions);
            refreshCompletions.clear();
            ProviderObservation<ProviderCape> confirmed = confirmedRefreshObservation;
            confirmedRefreshObservation = null;
            completions.forEach(completion -> completion.accept(confirmed));
        }

        private void cancelPending() {
            for (Request request : pending.values()) {
                if (request.future != null) request.future.cancel(true);
            }
            pending.clear();
            purgeCancelledWork();
        }
    }
}

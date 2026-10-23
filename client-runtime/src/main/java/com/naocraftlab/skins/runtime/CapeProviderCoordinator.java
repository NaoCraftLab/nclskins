package com.naocraftlab.skins.runtime;

import com.naocraftlab.skins.client.ClientExecutor;
import com.naocraftlab.skins.client.GameSessionTokenSource;
import com.naocraftlab.skins.client.PlayerAppearanceSink;
import com.naocraftlab.skins.client.PlayerAppearanceSink.CapeSource;
import com.naocraftlab.skins.client.PlayerAppearanceSink.TrackedCapePlayer;
import com.naocraftlab.skins.core.png.PngValidator;
import com.naocraftlab.skins.core.png.PreparedCapeAsset;
import com.naocraftlab.skins.core.png.CapeTimeline;
import com.naocraftlab.skins.core.png.CapeIntegrityException;
import com.naocraftlab.skins.core.png.SneakyCapeDecoder;
import com.naocraftlab.skins.core.provider.AppearanceProviders;
import com.naocraftlab.skins.core.provider.BuiltinProvider;
import com.naocraftlab.skins.core.provider.ProviderCape;
import com.naocraftlab.skins.core.provider.ProviderObservation;
import com.naocraftlab.skins.core.service.AccountAppearanceStore;
import com.naocraftlab.skins.core.service.AssetStorePort;
import com.naocraftlab.skins.core.service.ProviderTextureStore;

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
    private final RemoteCapeScheduler minecraftCapesSchedule = new RemoteCapeScheduler(BuiltinProvider.MINECRAFTCAPES);
    private final Map<BuiltinProvider, ProviderEffectBinding<RemoteCapeScheduler>> externalBindings = Map.of(
            BuiltinProvider.OPTIFINE, externalBinding(BuiltinProvider.OPTIFINE, optifineSchedule),
            BuiltinProvider.SKINMC, externalBinding(BuiltinProvider.SKINMC, skinMcSchedule),
            BuiltinProvider.MINECRAFTCAPES, externalBinding(BuiltinProvider.MINECRAFTCAPES, minecraftCapesSchedule));
    private final GameSessionTokenSource tokenSource;
    private final AccountAppearanceStore storage;
    private final AssetStorePort assets;
    private final ProviderTextureStore textures;
    private final PlayerAppearanceSink<?> sink;
    private final OwnedCapeTextures nativeTextures;
    private final ClientExecutor clientExecutor;
    private final Executor worker;
    private final java.util.function.LongSupplier monotonicNanos;
    private final CapePreparationQueue preparations;
    private final CapeObservationReader reader;
    private final CapeObservationReader skinMcReader;
    private final CapeObservationReader minecraftCapesReader;
    private final BiFunction<UUID, String, java.util.Optional<URI>> officialCapeUri;
    private final Map<UUID, CapeProjection.Identity> tracked = new HashMap<>();
    private final Map<CapeProjection.Identity, Observed> observed = new HashMap<>();
    private final Map<CapeProjection.Identity, Observed> skinMcObserved = new HashMap<>();
    private final Map<CapeProjection.Identity, Observed> minecraftCapesObserved = new HashMap<>();
    private final Map<FrameKey, FrameState> frames = new HashMap<>();
    private final Set<FrameKey> frameDispatch = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final ArrayDeque<FrameKey> frameQueue = new ArrayDeque<>();
    private final Set<FrameKey> frameActive = new HashSet<>();
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
    private final Map<BuiltinProvider, CandidateBinding> candidateBindings = Map.of(
            BuiltinProvider.OFFLINE, candidateBinding(BuiltinProvider.OFFLINE, CapeSource.OFFLINE,
                    ProviderRefreshBehavior.Preparation.LOCAL_ASSET, this::prepareLocalCandidate),
            BuiltinProvider.MINECRAFT, candidateBinding(BuiltinProvider.MINECRAFT, CapeSource.MINECRAFT,
                    ProviderRefreshBehavior.Preparation.REMOTE_REFERENCE, this::prepareRemoteCandidate));
    private final SourcePreparationState<BuiltinProvider, CapeProjection.Identity, ProviderCape> selfPreparation =
            new SourcePreparationState<>(candidateBindings.keySet());
    private volatile long selfEpoch;
    private volatile long skinMcSelfEpoch;
    private volatile long minecraftCapesSelfEpoch;
    private long adoptionSequence;
    private long skinMcAdoptionSequence;
    private long minecraftCapesAdoptionSequence;
    private CapeProjection.Identity startedIdentity;
    private ProjectionState publishedProjection;
    private volatile boolean closed;

    public CapeProviderCoordinator(GameSessionTokenSource tokenSource, AccountAppearanceStore storage, AssetStorePort assets,
            ProviderTextureStore textures, PlayerAppearanceSink<?> sink, ClientExecutor clientExecutor,
            Executor worker, CapeObservationReader reader, CapeObservationReader skinMcReader,
            BiFunction<UUID, String, java.util.Optional<URI>> officialCapeUri) {
        this(tokenSource, storage, assets, textures, sink, clientExecutor, worker, reader,
                skinMcReader, null, officialCapeUri);
    }

    public CapeProviderCoordinator(GameSessionTokenSource tokenSource, AccountAppearanceStore storage, AssetStorePort assets,
            ProviderTextureStore textures, PlayerAppearanceSink<?> sink, ClientExecutor clientExecutor,
            Executor worker, CapeObservationReader reader, CapeObservationReader skinMcReader,
            CapeObservationReader minecraftCapesReader,
            BiFunction<UUID, String, java.util.Optional<URI>> officialCapeUri) {
        this(tokenSource, storage, assets, textures, sink, clientExecutor, worker, reader,
                skinMcReader, minecraftCapesReader, officialCapeUri, System::nanoTime);
    }

    CapeProviderCoordinator(GameSessionTokenSource tokenSource, AccountAppearanceStore storage, AssetStorePort assets,
            ProviderTextureStore textures, PlayerAppearanceSink<?> sink, ClientExecutor clientExecutor,
            Executor worker, CapeObservationReader reader, CapeObservationReader skinMcReader,
            CapeObservationReader minecraftCapesReader,
            BiFunction<UUID, String, java.util.Optional<URI>> officialCapeUri,
            java.util.function.LongSupplier monotonicNanos) {
        this.monotonicNanos = Objects.requireNonNull(monotonicNanos, "monotonicNanos");
        this.tokenSource = Objects.requireNonNull(tokenSource, "tokenSource");
        this.storage = Objects.requireNonNull(storage, "storage");
        this.assets = Objects.requireNonNull(assets, "assets");
        this.textures = Objects.requireNonNull(textures, "textures");
        this.sink = Objects.requireNonNull(sink, "sink");
        this.nativeTextures = new OwnedCapeTextures(sink);
        this.clientExecutor = Objects.requireNonNull(clientExecutor, "clientExecutor");
        this.worker = Objects.requireNonNull(worker, "worker");
        this.preparations = new CapePreparationQueue(worker);
        this.reader = Objects.requireNonNull(reader, "reader");
        this.skinMcReader = Objects.requireNonNull(skinMcReader, "skinMcReader");
        this.minecraftCapesReader = minecraftCapesReader;
        this.officialCapeUri = Objects.requireNonNull(officialCapeUri, "officialCapeUri");
        for (BuiltinProvider source : BuiltinProvider.values()) {
            if (!source.supportsCape()) continue;
            var behavior = ProviderRefreshBehavior.of(source, AppearanceProviders.Component.CAPE);
            if (behavior.sharedPreparation() != candidateBindings.containsKey(source)
                    || (behavior.observation() == ProviderRefreshBehavior.Observation.EXTERNAL) != externalBindings.containsKey(source))
                throw new IllegalArgumentException("Missing provider effect binding");
        }
        this.sneakyProcessor = new ProviderEffectBinding<>(
                ProviderRefreshBehavior.of(BuiltinProvider.SNEAKY, AppearanceProviders.Component.CAPE),
                new SneakyCapeProcessor(worker, clientExecutor, this::sneakyPrepared,
                        this::retainedSneakyIdentities, this::currentSneakyPreparation))
                .require(ProviderRefreshBehavior.Observation.DERIVED);
        this.registration = CapeProjection.installEvents(new CapeProjection.Events() {
            public com.naocraftlab.skins.client.TextureUseValidity captureCapeTexture(
                    UUID profileId, String installedLocation) {
                if (closed || !registration.active() || !clientExecutor.isClientThread())
                    return com.naocraftlab.skins.client.TextureUseValidity.INVALID;
                return sink.captureCapeTexture(profileId, CapeSource.MINECRAFTCAPES, installedLocation);
            }
            public void cpmChanged() {
                Runnable update = () -> {
                    synchronized (CapeProviderCoordinator.this) {
                        if (closed || !registration.active()) return;
                        publishedProjection = null;
                        publish();
                    }
                };
                if (clientExecutor.isClientThread()) update.run(); else dispatch(update);
            }
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
            public void capeFrameDemand(UUID id, String name, BuiltinProvider provider) {
                FrameKey key = new FrameKey(new CapeProjection.Identity(id, name), provider);
                if (frameDispatch.add(key)) {
                    try {
                        clientExecutor.execute(() -> {
                            frameDispatch.remove(key);
                            synchronized (CapeProviderCoordinator.this) {
                                for (FrameKey requested : requestedFrames(key)) frameDemand(requested);
                            }
                        });
                    } catch (RuntimeException rejected) {
                        frameDispatch.remove(key);
                        synchronized (CapeProviderCoordinator.this) {
                            for (FrameKey requested : requestedFrames(key)) {
                                FrameState frame = frames.get(requested);
                                if (!closed && frame != null && current(requested.identity())
                                        && frame.failures <= 3) failFrame(frame);
                            }
                        }
                    }
                }
            }
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
        scheduleMinecraftCapesRefresh(false);
    }

    public synchronized void onCapeObservation(Consumer<CapeObservationPort.Observation> listener) {
        observationListener = Objects.requireNonNull(listener, "listener");
    }

    public synchronized java.util.Optional<java.time.Duration> cooldownRemaining(BuiltinProvider provider) {
        if (closed || !registration.active() || provider == null) return java.util.Optional.empty();
        var binding = externalBindings.get(provider);
        return binding == null ? java.util.Optional.empty() : binding.require(ProviderRefreshBehavior.Observation.EXTERNAL).cooldownRemaining();
    }

    public synchronized void selfCapeCandidatesChanged(UUID accountId, String canonicalName,
            AppearanceProviders next) {
        if (closed || !registration.active() || selfIdentity == null || !selfIdentity.equals(
                new CapeProjection.Identity(accountId, canonicalName))) return;
        selfCandidateProviders = next;
        retireAbsentSelfCandidates();
        scheduleSelfCandidates();
    }

    public synchronized long observationGeneration() { return generation; }

    public synchronized void refreshScoped(ProviderRefreshUseCases.Scope scope, BuiltinProvider provider,
            Consumer<ProviderObservation<ProviderCape>> completion) {
        Objects.requireNonNull(completion, "completion");
        var binding = externalBindings.get(provider);
        RemoteCapeScheduler schedule = binding == null ? null : binding.require(ProviderRefreshBehavior.Observation.EXTERNAL);
        boolean accepted = false;
        try {
            var identity = tokenSource.currentSession();
            accepted = !closed && registration.active() && schedule != null
                    && scope.request().component() == AppearanceProviders.Component.CAPE
                    && scope.providers().contains(provider) && scope.identity().equals(identity)
                    && scope.capeGeneration() == generation && selfIdentity != null
                    && selfIdentity.equals(new CapeProjection.Identity(identity.profileId(), identity.profileName()))
                    && scope.configurationMatches(providers)
                    && scope.configurationMatches(storage.loadAppearance(identity.profileId()).providers())
                    && providers.cape().enabled(provider) && schedule.enabled();
        } catch (IOException | RuntimeException unavailable) { accepted = false; }
        if (!accepted) { completion.accept(null); return; }
        if (scope.request().provider().isPresent() && schedule.cooldownRemaining().isPresent()) {
            completion.accept(null);
            return;
        }
        schedule.refreshCompletions.add(completion);
        if (schedule.capacityWait != null || !schedule.pending.isEmpty()
                || !schedule.queue.isEmpty() || schedule.sweep.hasNext()) {
            if (schedule.attempted.contains(selfIdentity)) {
                schedule.attempted.remove(selfIdentity);
                schedule.repeatPending.add(selfIdentity);
                schedule.activeScope = scope;
                schedule.explicitSweep = true;
                schedule.confirmedRefreshObservation = null;
                schedule.fillQueue();
                schedule.pump();
            }
        } else {
            schedule.attempted.clear();
            schedule.startSweep(false, scope);
        }
    }

    public synchronized void selfCapeCandidatesChanged(UUID accountId, String canonicalName,
            AppearanceProviders next, Set<BuiltinProvider> allowedSources) {
        if (closed || !registration.active() || selfIdentity == null || !selfIdentity.equals(
                new CapeProjection.Identity(accountId, canonicalName))) return;
        selfCandidateProviders = next;
        retireAbsentSelfCandidates();
        scheduleSelfCandidates(allowedSources);
    }

    public synchronized void refresh() {
        if (closed || !registration.active()) return;
        reloadConfiguration();
        if (!enabled()) return;
        if (optifineSchedule.capacityWait != null || !optifineSchedule.pending.isEmpty()
                || !optifineSchedule.queue.isEmpty() || optifineSchedule.sweep.hasNext()) {
            optifineSchedule.retainRefresh(false);
            return;
        }
        optifineSchedule.attempted.clear();
        optifineSchedule.startSweep(false);
    }

    public synchronized void configurationChanged() {
        if (closed || !registration.active()) return;
        boolean wasEnabled = enabled();
        boolean wasSkinMcEnabled = skinMcEnabled();
        boolean wasMinecraftCapesEnabled = minecraftCapesEnabled();
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
                && wasEnabled == enabled() && wasSkinMcEnabled == skinMcEnabled()
                && wasMinecraftCapesEnabled == minecraftCapesEnabled()) {
            publish();
            return;
        }
        generation++;
        sneakyProcessor.invalidate();
        scheduleSneakySelf();
        for (CapeProjection.Identity identity : List.copyOf(sneakyVisibleSkins.keySet())) {
            updateSneakyRemote(identity);
        }
        optifineSchedule.queue.clear();
        optifineSchedule.cancelPending();
        optifineSchedule.repeatPending.clear();
        optifineSchedule.sweep = List.<CapeProjection.Identity>of().iterator();
        optifineSchedule.refreshPending = false;
        optifineSchedule.activeScope = null;
        optifineSchedule.confirmedRefreshObservation = null;
        optifineSchedule.finishRefreshCompletions();
        skinMcSchedule.reset();
        skinMcSchedule.finishRefreshCompletions();
        minecraftCapesSchedule.reset();
        minecraftCapesSchedule.finishRefreshCompletions();
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
        if (!minecraftCapesEnabled()) releaseMinecraftCapesTextures();
        else {
            if (!wasMinecraftCapesEnabled) restoreMinecraftCapesTextures();
            minecraftCapesSchedule.startSweep(true);
        }
        publish();
        optifineSchedule.pump();
        skinMcSchedule.pump();
        minecraftCapesSchedule.pump();
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
        ProviderObservation<ProviderCape> previousMinecraftCapes = providers.cape().minecraftcapes();
        Observed represented = observed.get(identity);
        ProviderCape nextCape = nextObservation.value();
        boolean changedObservation = !providers.cape().optifine().equals(nextObservation)
                || nextCape != null && represented != null && represented.visualKey() != null
                && (!Objects.equals(nextCape.textureCacheKey(), represented.visualKey())
                    || !Objects.equals(nextCape.hasElytra(), represented.hasElytra()));
        boolean changedCandidates = candidateBindings.keySet().stream().anyMatch(source ->
                selfCandidateProviders.cape().enabled(source) != snapshot.cape().enabled(source)
                || !Objects.equals(selfCandidateProviders.cape().observation(source), snapshot.cape().observation(source)));
        if (changedObservation) {
            selfEpoch++;
            optifineSchedule.confirmedRefreshObservation = null;
            optifineSchedule.queue.remove(identity);
            optifineSchedule.attempted.remove(identity);
            Request request = optifineSchedule.pending.remove(identity);
            if (request != null) request.releasePin();
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
        adoptMinecraftCapesSnapshot(identity, snapshot, previousMinecraftCapes);
        if (changedCandidates) {
            selfCandidateProviders = snapshot;
            retireAbsentSelfCandidates();
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
        if (!changedObservation && represented != null && represented.visualKey() != null
                && represented.location() != null
                && cape.textureCacheKey().equals(represented.visualKey())
                && Objects.equals(cape.hasElytra(), represented.hasElytra())) {
            publish();
            optifineSchedule.pump();
            return;
        }
        if (changedObservation) clearAdoptedSelf(identity, false);
        publish();
        Runnable preparation = () -> {
            PngValidator.CapePng png = null;
            PreparedCapeAsset asset = null;
            try {
                asset = readAccountedObserved(cape.textureCacheKey());
                if (asset != null && (!asset.visualSha256().equals(cape.textureCacheKey())
                        || !Objects.equals(asset.hasElytra(), cape.hasElytra()))) asset = null;
                if (asset == null) {
                    byte[] bytes = textures.readIfCached(cape.textureCacheKey()).orElse(null);
                    if (bytes != null) {
                        PngValidator.CapePng parsed = new PngValidator().projectCanonicalCape(bytes);
                        if (parsed.renderSha256().equals(cape.textureCacheKey())
                                && Objects.equals(parsed.hasElytra(), cape.hasElytra())) png = parsed;
                    }
                }
            } catch (IOException | com.naocraftlab.skins.core.png.PngValidationException unavailable) {
                png = null;
            }
            PngValidator.CapePng validated = png;
            PreparedCapeAsset prepared = asset;
            clientExecutor.execute(() -> completeSharedAdoption(identity, snapshot,
                    sequence, expectedGeneration, validated, prepared));
        };
        preparations.submit(CapePreparationQueue.Slot.OPTIFINE_ADOPTION, preparation);
        optifineSchedule.pump();
    }

    private synchronized void completeSharedAdoption(CapeProjection.Identity identity,
            AppearanceProviders snapshot, long sequence, long expectedGeneration,
            PngValidator.CapePng png, PreparedCapeAsset asset) {
        if (closed || !registration.active() || adoptionSequence != sequence || generation != expectedGeneration
                || !identity.equals(selfIdentity)
                || !current(identity) || !enabled()
                || !providers.cape().optifine().equals(snapshot.cape().optifine())) return;
        if (png == null && asset == null) {
            clearAdoptedSelf(identity, false);
            publish();
            return;
        }
        replace(identity, new Observed(png, asset, false, null));
    }

    private void adoptSkinMcSnapshot(CapeProjection.Identity identity, AppearanceProviders snapshot,
            ProviderObservation<ProviderCape> previousObservation) {
        ProviderObservation<ProviderCape> next = snapshot.cape().skinmc();
        Observed represented = skinMcObserved.get(identity);
        ProviderCape cape = next.value();
        boolean changed = !previousObservation.equals(next)
                || cape != null && represented == null
                || cape != null && represented != null && represented.visualKey() != null
                && (!Objects.equals(cape.textureCacheKey(), represented.visualKey())
                    || !Objects.equals(cape.hasElytra(), represented.hasElytra()));
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
        if (request != null) request.releasePin();
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
            PreparedCapeAsset asset = null;
            try {
                asset = readAccountedObserved(cape.textureCacheKey());
                if (asset != null && (!asset.visualSha256().equals(cape.textureCacheKey())
                        || !Objects.equals(asset.hasElytra(), cape.hasElytra()))) asset = null;
                if (asset == null) {
                    byte[] bytes = textures.readIfCached(cape.textureCacheKey()).orElse(null);
                    if (bytes != null) {
                        PngValidator.CapePng parsed = new PngValidator().projectCanonicalCape(bytes);
                        if (parsed.renderSha256().equals(cape.textureCacheKey())
                                && Objects.equals(parsed.hasElytra(), cape.hasElytra())) png = parsed;
                    }
                }
            } catch (IOException | com.naocraftlab.skins.core.png.PngValidationException unavailable) {
                png = null;
            }
            PngValidator.CapePng validated = png;
            PreparedCapeAsset prepared = asset;
            clientExecutor.execute(() -> completeSkinMcSharedAdoption(identity, snapshot,
                    sequence, expectedGeneration, validated, prepared));
        });
    }

    private synchronized void completeSkinMcSharedAdoption(CapeProjection.Identity identity,
            AppearanceProviders snapshot, long sequence, long expectedGeneration,
            PngValidator.CapePng png, PreparedCapeAsset asset) {
        if (closed || !registration.active() || skinMcAdoptionSequence != sequence || generation != expectedGeneration
                || !identity.equals(selfIdentity) || !current(identity) || !skinMcEnabled()
                || !providers.cape().skinmc().equals(snapshot.cape().skinmc())) return;
        if (png == null && asset == null) {
            removeSkinMc(identity);
            publish();
            return;
        }
        replaceSkinMc(identity, new Observed(png, asset, false, null));
    }

    private void adoptMinecraftCapesSnapshot(CapeProjection.Identity identity, AppearanceProviders snapshot,
            ProviderObservation<ProviderCape> previousObservation) {
        if (minecraftCapesReader == null) return;
        ProviderObservation<ProviderCape> next = snapshot.cape().minecraftcapes();
        ProviderCape cape = next.value();
        Observed represented = minecraftCapesObserved.get(identity);
        boolean changed = !previousObservation.equals(next)
                || cape != null && (represented == null || represented.asset() == null)
                || cape != null && represented != null && represented.asset() != null
                && (!Objects.equals(cape.textureCacheKey(), represented.asset().visualSha256())
                    || !Objects.equals(cape.hasElytra(), represented.asset().hasElytra())
                    || cape.capeGlint() != represented.capeGlint());
        if (!changed) return;
        long sequence = ++minecraftCapesAdoptionSequence;
        long expectedGeneration = generation;
        minecraftCapesSelfEpoch++;
        minecraftCapesSchedule.queue.remove(identity);
        minecraftCapesSchedule.attempted.remove(identity);
        minecraftCapesSchedule.refreshPending = false;
        minecraftCapesSchedule.confirmedRefreshObservation = null;
        minecraftCapesSchedule.finishRefreshCompletions();
        Request request = minecraftCapesSchedule.pending.remove(identity);
        if (request != null) request.releasePin();
        if (request != null && request.future != null) {
            request.future.cancel(true);
            purgeCancelledWork();
        }
        removeMinecraftCapes(identity);
        if (!minecraftCapesEnabled() || !next.known() || cape == null
                || !cape.id().equals(minecraftCapesCapeId(identity)) || cape.textureCacheKey() == null) {
            if (next.known() && cape == null) minecraftCapesObserved.put(identity, new Observed(null, null));
            publish();
            return;
        }
        preparations.submit(CapePreparationQueue.Slot.MINECRAFTCAPES_ADOPTION, () -> {
            PreparedCapeAsset asset = null;
            try {
                asset = readAccountedObserved(cape.textureCacheKey());
                if (asset != null && (!asset.visualSha256().equals(cape.textureCacheKey())
                        || !Objects.equals(asset.hasElytra(), cape.hasElytra()))) asset = null;
            } catch (IOException unavailable) {
                asset = null;
            }
            PreparedCapeAsset validated = asset;
            clientExecutor.execute(() -> completeMinecraftCapesSharedAdoption(identity, snapshot,
                    sequence, expectedGeneration, validated));
        });
    }

    private synchronized void completeMinecraftCapesSharedAdoption(CapeProjection.Identity identity,
            AppearanceProviders snapshot, long sequence, long expectedGeneration,
            PreparedCapeAsset asset) {
        if (closed || !registration.active() || minecraftCapesAdoptionSequence != sequence
                || generation != expectedGeneration || !identity.equals(selfIdentity)
                || !current(identity) || !minecraftCapesEnabled()
                || !providers.cape().minecraftcapes().equals(snapshot.cape().minecraftcapes())) return;
        if (asset == null) {
            removeMinecraftCapes(identity);
            publish();
            return;
        }
        replaceMinecraftCapes(identity, new Observed(null, asset,
                snapshot.cape().minecraftcapes().value().capeGlint(), null));
    }

    private void clearAdoptedSelf(CapeProjection.Identity identity, boolean knownAbsent) {
        retireFrame(new FrameKey(identity, BuiltinProvider.OPTIFINE));
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
        acceptTrackedIdentity(identity);
        if (enabled() && !observed.containsKey(identity) && !optifineSchedule.attempted.contains(identity)) {
            if (aggregateQueue() >= MAX_QUEUE) {
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
        if (minecraftCapesEnabled() && !minecraftCapesObserved.containsKey(identity)
                && !minecraftCapesSchedule.attempted.contains(identity)) {
            minecraftCapesSchedule.enqueue(identity, false);
            minecraftCapesSchedule.pump();
        }
    }

    private void acceptTrackedIdentity(CapeProjection.Identity identity) {
        CapeProjection.Identity previous = tracked.put(identity.profileId(), identity);
        if (previous != null && !previous.equals(identity)) retireTrackedIdentity(previous);
        String skinLocation = registration.visibleSkin(identity);
        if (skinLocation != null) visibleSkin(identity.profileId(), identity.canonicalName(), skinLocation);
        else {
            updateSneakyRemote(identity);
            publish();
        }
    }

    public synchronized void playerInfoUpdated(UUID profileId, String canonicalName) {
        if (closed || !registration.active() || !enabled() && !skinMcEnabled()
                && !minecraftCapesEnabled() && !providers.cape().enabled(BuiltinProvider.SNEAKY)) return;
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
        if (minecraftCapesEnabled()) {
            if (minecraftCapesSchedule.pending.containsKey(identity)
                    || minecraftCapesReader.cooldownRemaining().isPresent()) {
                minecraftCapesSchedule.repeatPending.add(identity);
            } else if (!minecraftCapesSchedule.queue.contains(identity)) {
                minecraftCapesSchedule.attempted.remove(identity);
                minecraftCapesSchedule.enqueue(identity, false);
            }
            minecraftCapesSchedule.pump();
        }
        if (!enabled()) return;
        if (optifineSchedule.pending.containsKey(identity)) {
            optifineSchedule.repeatPending.add(identity);
            return;
        }
        if (optifineSchedule.queue.contains(identity)) return;
        optifineSchedule.attempted.remove(identity);
        if (aggregateQueue() >= MAX_QUEUE) optifineSchedule.repeatPending.add(identity);
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
            minecraftCapesSchedule.pump();
        }
    }

    public synchronized void worldChanged() {
        if (closed || !registration.active()) return;
        registration.invalidateVisibleSkins();
        cancelSelfPreparation();
        generation++;
        sneakyProcessor.invalidate();
        optifineSchedule.queue.clear();
        optifineSchedule.cancelPending();
        optifineSchedule.sweep = List.<CapeProjection.Identity>of().iterator();
        optifineSchedule.refreshPending = false;
        optifineSchedule.activeScope = null;
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
        minecraftCapesSchedule.reset();
        for (CapeProjection.Identity identity : List.copyOf(skinMcObserved.keySet())) {
            if (!identity.equals(selfIdentity)) removeSkinMc(identity);
        }
        for (CapeProjection.Identity identity : List.copyOf(minecraftCapesObserved.keySet())) {
            if (!identity.equals(selfIdentity)) removeMinecraftCapes(identity);
        }
        scheduleSneakySelf();
        scheduleSelfCandidates();
        publish();
    }

    public synchronized void worldEntered() {
        worldChanged();
        if (!closed && enabled()) optifineSchedule.startSweep(true);
        if (!closed && skinMcEnabled()) skinMcSchedule.startSweep(true);
        if (!closed && minecraftCapesEnabled()) minecraftCapesSchedule.startSweep(true);
    }

    @Override
    public synchronized void close() {
        if (!clientExecutor.isClientThread()) {
            clientExecutor.execute(this::close);
            return;
        }
        if (closed) return;
        closed = true;
        cancelSelfPreparation();
        generation++;
        optifineSchedule.confirmedRefreshObservation = null;
        optifineSchedule.finishRefreshCompletions();
        sneakyProcessor.invalidate();
        optifineSchedule.queue.clear();
        optifineSchedule.cancelPending();
        tracked.clear();
        optifineSchedule.attempted.clear();
        optifineSchedule.repeatPending.clear();
        skinMcSchedule.reset();
        minecraftCapesSchedule.reset();
        optifineSchedule.sweep = List.<CapeProjection.Identity>of().iterator();
        releaseObservedTextures();
        releaseSkinMcTextures();
        releaseMinecraftCapesTextures();
        clearSneakySelf();
        for (CapeProjection.Identity identity : List.copyOf(sneakyRemote.keySet())) removeSneakyRemote(identity);
        sneakyVisibleSkins.clear();
        sneakyProcessor.close();
        sneakyTextureKeys.clear();
        preparations.close();
        observed.clear();
        skinMcObserved.clear();
        minecraftCapesObserved.clear();
        for (FrameKey key : List.copyOf(frames.keySet())) retireFrame(key);
        CapePreparationQueue.pinObservedFor(this, Set.of());
        frameQueue.clear();
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

    private boolean minecraftCapesEnabled() {
        return minecraftCapesReader != null && providers.cape().enabled(BuiltinProvider.MINECRAFTCAPES);
    }

    private int aggregatePending() {
        return optifineSchedule.pending.size() + skinMcSchedule.pending.size()
                + minecraftCapesSchedule.pending.size();
    }

    private int aggregateQueue() {
        return optifineSchedule.queue.size() + skinMcSchedule.queue.size()
                + minecraftCapesSchedule.queue.size();
    }

    private void reloadConfiguration() {
        GameSessionTokenSource.SessionIdentity session = tokenSource.currentSession();
        CapeProjection.Identity nextSelf = new CapeProjection.Identity(session.profileId(), session.profileName());
        boolean identityChanged = !nextSelf.equals(selfIdentity);
        if (identityChanged) {
            if (selfIdentity != null) CapeProjection.retireSkins();
            cancelSelfPreparation();
            registration.invalidateVisibleSkins();
            reader.accountChanged();
            skinMcReader.accountChanged();
            if (minecraftCapesReader != null) minecraftCapesReader.accountChanged();
            generation++;
            sneakyProcessor.invalidate();
            selfEpoch++;
            skinMcSelfEpoch++;
            minecraftCapesSelfEpoch++;
            optifineSchedule.queue.clear();
            optifineSchedule.cancelPending();
            tracked.clear();
            optifineSchedule.attempted.clear();
            optifineSchedule.repeatPending.clear();
            skinMcSchedule.reset();
            minecraftCapesSchedule.reset();
            optifineSchedule.sweep = List.<CapeProjection.Identity>of().iterator();
            optifineSchedule.refreshPending = false;
            optifineSchedule.activeScope = null;
            optifineSchedule.unknownSweepPending = false;
            releaseObservedTextures();
            releaseSkinMcTextures();
            releaseMinecraftCapesTextures();
            clearSneakySelf();
            for (CapeProjection.Identity identity : List.copyOf(sneakyRemote.keySet())) removeSneakyRemote(identity);
            sneakyVisibleSkins.clear();
            observed.clear();
            skinMcObserved.clear();
            minecraftCapesObserved.clear();
            releaseSelfCandidates();
            selfIdentity = nextSelf;
        }
        try {
            AppearanceProviders loaded = storage.loadAppearance(session.profileId()).providers();
            boolean candidateInputsChanged = identityChanged
                    || candidateBindings.keySet().stream().anyMatch(source ->
                            selfCandidateProviders.cape().enabled(source) != loaded.cape().enabled(source)
                            || !Objects.equals(selfCandidateProviders.cape().observation(source), loaded.cape().observation(source)));
            providers = loaded;
            scheduleSneakySelf();
            if (sneakySelf != null) notifySneakySelf(ProviderObservation.observed(
                    sneakySelf.png() == null || sneakySelf.location() == null ? null
                            : new ProviderCape("sneaky:" + sneakySkinSha,
                                    sneakySelf.png().renderSha256(), sneakySelf.png().hasElytra())));
            restoreSelfObservation();
            restoreSkinMcSelfObservation();
            restoreMinecraftCapesSelfObservation();
            if (candidateInputsChanged) {
                selfCandidateProviders = loaded;
                retireAbsentSelfCandidates();
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
            byte[] skin = assets.readAsset(selected);
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
            PreparedCapeAsset prepared = readAccountedObserved(cape.textureCacheKey());
            if (prepared != null && prepared.visualSha256().equals(cape.textureCacheKey())
                    && Objects.equals(prepared.hasElytra(), cape.hasElytra())) {
                replacePrepared(selfIdentity, BuiltinProvider.OPTIFINE,
                        new Observed(null, prepared, false, null));
                return;
            }
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
            PreparedCapeAsset prepared = readAccountedObserved(cape.textureCacheKey());
            if (prepared != null && prepared.visualSha256().equals(cape.textureCacheKey())
                    && Objects.equals(prepared.hasElytra(), cape.hasElytra())) {
                replacePrepared(selfIdentity, BuiltinProvider.SKINMC,
                        new Observed(null, prepared, false, null));
                return;
            }
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

    private void restoreMinecraftCapesSelfObservation() {
        if (minecraftCapesReader == null || selfIdentity == null) return;
        var saved = providers.cape().minecraftcapes();
        if (!saved.known() || minecraftCapesObserved.containsKey(selfIdentity)) return;
        ProviderCape cape = saved.value();
        if (cape == null) {
            minecraftCapesObserved.put(selfIdentity, new Observed(null, null));
            return;
        }
        if (!cape.id().equals(minecraftCapesCapeId(selfIdentity)) || cape.textureCacheKey() == null) return;
        try {
            PreparedCapeAsset asset = readAccountedObserved(cape.textureCacheKey());
            if (asset == null || !asset.visualSha256().equals(cape.textureCacheKey())
                    || !Objects.equals(asset.hasElytra(), cape.hasElytra())) return;
            minecraftCapesObserved.put(selfIdentity, new Observed(null, asset, cape.capeGlint(), null));
            if (minecraftCapesEnabled()) restoreMinecraftCapesTextures();
        } catch (IOException invalid) {
            minecraftCapesObserved.remove(selfIdentity);
        }
    }

    private void scheduleSelfCandidates() {
        scheduleSelfCandidates(candidateBindings.keySet());
    }

    private void scheduleSelfCandidates(Set<BuiltinProvider> allowedSources) {
        CapeProjection.Identity context = selfIdentity;
        for (BuiltinProvider source : candidateBindings.keySet()) {
            ProviderCape observation = selfCandidateProviders.cape().observation(source).value();
            selfPreparation.offer(source, context, observation, selfCandidateProviders.cape().enabled(source),
                    allowedSources.contains(source));
        }
        var pending = selfPreparation.pending();
        preparations.submit(CapePreparationQueue.Slot.SELF_CANDIDATES, this::prepareSelfCandidates,
                () -> pending.forEach(selfPreparation::rejectPending));
    }

    private void prepareSelfCandidates() {
        var batch = selfPreparation.issue();
        Map<SourcePreparationState.Ticket<BuiltinProvider, CapeProjection.Identity, ProviderCape>, Prepared> prepared = new HashMap<>();
        try {
            for (var ticket : batch) {
                if (!currentCandidate(ticket)) continue;
                try {
                    Prepared value = candidateBindings.get(ticket.source()).binding().effect().prepare(ticket);
                    if (value != null) prepared.put(ticket, value);
                } catch (IOException | com.naocraftlab.skins.core.png.PngValidationException unavailable) {
                    continue;
                }
            }
            clientExecutor.execute(() -> applySelfCandidates(batch, prepared));
        } catch (RuntimeException rejected) {
            batch.forEach(ticket -> selfPreparation.complete(ticket, false));
        }
    }

    private Prepared prepareLocalCandidate(SourcePreparationState.Ticket<BuiltinProvider, CapeProjection.Identity, ProviderCape> ticket)
            throws IOException, com.naocraftlab.skins.core.png.PngValidationException {
        ProviderCape cape = ticket.observation();
        if (cape.textureCacheKey() == null) return null;
        PreparedCapeAsset asset = textures.readLocalPreparedCape(ticket.context().profileId(), cape.textureCacheKey()).orElse(null);
        if (asset != null) return new Prepared(asset.visualSha256(), null, asset.hasElytra(), asset);
        byte[] bytes = textures.readIfCached(cape.textureCacheKey()).orElse(null);
        if (bytes == null) bytes = textures.readLocalCape(ticket.context().profileId(), cape.textureCacheKey()).orElse(null);
        return prepareCandidate(bytes, cape);
    }

    private Prepared prepareRemoteCandidate(SourcePreparationState.Ticket<BuiltinProvider, CapeProjection.Identity, ProviderCape> ticket)
            throws IOException, com.naocraftlab.skins.core.png.PngValidationException {
        ProviderCape cape = ticket.observation();
        byte[] bytes = cape.textureCacheKey() == null ? null : textures.readIfCached(cape.textureCacheKey()).orElse(null);
        if (bytes == null && ticket.remoteRead() && currentCandidate(ticket)) {
            var uri = officialCapeUri.apply(ticket.context().profileId(), cape.id());
            if (uri.isPresent() && currentCandidate(ticket)) bytes = textures.load(uri.orElseThrow());
        }
        return prepareCandidate(bytes, cape);
    }

    private Prepared prepareCandidate(byte[] bytes, ProviderCape cape)
            throws com.naocraftlab.skins.core.png.PngValidationException {
        if (bytes == null) return null;
        var png = new PngValidator().projectCanonicalCape(bytes);
        return new Prepared(png.renderSha256(), png.bytes(), !Boolean.FALSE.equals(cape.hasElytra()));
    }

    private synchronized boolean currentCandidate(SourcePreparationState.Ticket<BuiltinProvider, CapeProjection.Identity, ProviderCape> ticket) {
        var context = ticket.context();
        return !closed && registration.active() && context.equals(selfIdentity)
                && current(context) && selfPreparation.current(ticket)
                && selfCandidateProviders.cape().enabled(ticket.source())
                && Objects.equals(ticket.observation(), selfCandidateProviders.cape().observation(ticket.source()).value());
    }

    private void cancelSelfPreparation() { selfPreparation.invalidate(); }

    private synchronized void applySelfCandidates(
            List<SourcePreparationState.Ticket<BuiltinProvider, CapeProjection.Identity, ProviderCape>> batch,
            Map<SourcePreparationState.Ticket<BuiltinProvider, CapeProjection.Identity, ProviderCape>, Prepared> prepared) {
        boolean changed = false;
        for (var ticket : batch) {
            boolean adopted = false;
            try {
                if (!currentCandidate(ticket)) continue;
                Prepared value = prepared.get(ticket);
                if (value == null) continue;
                var identity = ticket.context();
                if (value.asset() != null) {
                    FrameKey key = new FrameKey(identity, ticket.source());
                    retireFrame(key);
                    frames.put(key, new FrameState(value.asset()));
                    frameDemand(key);
                    adopted = true;
                } else {
                    var location = nativeTextures.register(identity.profileId(), candidateBindings.get(ticket.source()).source(),
                            value.sha256(), value.bytes());
                    if (location.isPresent()) {
                        retireFrame(new FrameKey(identity, ticket.source()));
                        selfCandidates.put(ticket.source(), candidate(location.orElseThrow(), value.hasElytra()));
                        adopted = true;
                    }
                }
                changed |= adopted;
            } finally { selfPreparation.complete(ticket, adopted); }
        }
        if (changed) publish();
    }

    private static ProviderEffectBinding<RemoteCapeScheduler> externalBinding(BuiltinProvider source, RemoteCapeScheduler schedule) {
        var binding = new ProviderEffectBinding<>(ProviderRefreshBehavior.of(source, AppearanceProviders.Component.CAPE), schedule);
        binding.require(ProviderRefreshBehavior.Observation.EXTERNAL);
        return binding;
    }

    private static CandidateBinding candidateBinding(BuiltinProvider provider, CapeSource source,
            ProviderRefreshBehavior.Preparation preparation, CandidateEffect effect) {
        var behavior = ProviderRefreshBehavior.of(provider, AppearanceProviders.Component.CAPE);
        if (!behavior.sharedPreparation() || behavior.preparation() != preparation)
            throw new IllegalArgumentException("Incompatible candidate effect");
        return new CandidateBinding(source, new ProviderEffectBinding<>(behavior, effect));
    }

    private record CandidateBinding(CapeSource source, ProviderEffectBinding<CandidateEffect> binding) {}
    @FunctionalInterface
    private interface CandidateEffect {
        Prepared prepare(SourcePreparationState.Ticket<BuiltinProvider, CapeProjection.Identity, ProviderCape> ticket)
                throws IOException, com.naocraftlab.skins.core.png.PngValidationException;
    }

    private void retireAbsentSelfCandidates() {
        if (selfIdentity == null) return;
        boolean changed = false;
        for (BuiltinProvider provider : candidateBindings.keySet()) {
            if (selfCandidateProviders.cape().enabled(provider) && selfCandidateProviders.cape().observation(provider).value() != null) continue;
            FrameKey key = new FrameKey(selfIdentity, provider);
            if (frames.containsKey(key)) {
                retireFrame(key);
                changed = true;
            }
            if (selfCandidates.remove(provider) != null) {
                nativeTextures.release(selfIdentity.profileId(), CapeSource.valueOf(provider.name()));
                changed = true;
            }
        }
        if (changed) publish();
    }

    private void releaseSelfCandidates() {
        if (selfIdentity != null) {
            for (BuiltinProvider source : candidateBindings.keySet()) retireFrame(new FrameKey(selfIdentity, source));
            for (BuiltinProvider provider : selfCandidates.keySet()) {
                nativeTextures.release(selfIdentity.profileId(), CapeSource.valueOf(provider.name()));
            }
        }
        selfCandidates.clear();
    }

    private void scheduleSkinMcRefresh(boolean unknownOnly) {
        if (!skinMcEnabled()) return;
        if (skinMcSchedule.capacityWait != null || !skinMcSchedule.pending.isEmpty()
                || !skinMcSchedule.queue.isEmpty() || skinMcSchedule.sweep.hasNext()) {
            skinMcSchedule.retainRefresh(unknownOnly);
            return;
        }
        skinMcSchedule.attempted.clear();
        skinMcSchedule.startSweep(unknownOnly);
    }

    private void scheduleMinecraftCapesRefresh(boolean unknownOnly) {
        if (!minecraftCapesEnabled()) return;
        if (minecraftCapesSchedule.capacityWait != null || !minecraftCapesSchedule.pending.isEmpty()
                || !minecraftCapesSchedule.queue.isEmpty() || minecraftCapesSchedule.sweep.hasNext()) {
            minecraftCapesSchedule.retainRefresh(unknownOnly);
            return;
        }
        minecraftCapesSchedule.attempted.clear();
        minecraftCapesSchedule.startSweep(unknownOnly);
    }

    private void replaceSkinMc(CapeProjection.Identity identity, Observed next) {
        if (next.asset() != null) {
            replacePrepared(identity, BuiltinProvider.SKINMC, next);
            return;
        }
        Observed previous = skinMcObserved.get(identity);
        if (sameObservation(previous, next)) return;
        retireFrame(new FrameKey(identity, BuiltinProvider.SKINMC));
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

    private void replaceMinecraftCapes(CapeProjection.Identity identity, Observed next) {
        replacePrepared(identity, BuiltinProvider.MINECRAFTCAPES, next);
    }

    private void replacePrepared(CapeProjection.Identity identity, BuiltinProvider provider, Observed next) {
        Map<CapeProjection.Identity, Observed> observations = switch (provider) {
            case OPTIFINE -> observed;
            case SKINMC -> skinMcObserved;
            case MINECRAFTCAPES -> minecraftCapesObserved;
            default -> throw new IllegalArgumentException("Not a remote prepared cape provider");
        };
        Observed previous = observations.get(identity);
        FrameKey key = new FrameKey(identity, provider);
        boolean samePixels = previous != null && previous.asset() != null && next.asset() != null
                && previous.asset().visualSha256().equals(next.asset().visualSha256())
                && (previous.location() != null || frames.containsKey(key));
        if (samePixels && previous.capeGlint() == next.capeGlint()) return;
        PreparedCapeAsset activeAsset = samePixels ? previous.asset() : next.asset();
        if (!samePixels) retireFrame(key);
        String location = samePixels ? previous.location() : null;
        if (activeAsset != null && location == null) {
            frames.put(key, new FrameState(activeAsset));
        }
        if (activeAsset == null || location == null && !frames.containsKey(key)) {
            nativeTextures.release(identity.profileId(), CapeSource.valueOf(provider.name()));
        }
        Observed installed = new Observed(null, activeAsset, next.capeGlint(), location);
        observations.put(identity, installed);
        if (location == null && frames.containsKey(key)) frameDemand(key);
        if (identity.equals(selfIdentity)) {
            ProviderCape cape = activeAsset == null ? null
                    : new ProviderCape(switch (provider) {
                                case OPTIFINE -> capeId(identity);
                                case SKINMC -> skinMcCapeId(identity);
                                case MINECRAFTCAPES -> minecraftCapesCapeId(identity);
                                default -> throw new IllegalStateException("Unexpected prepared provider");
                            },
                            activeAsset.visualSha256(), activeAsset.hasElytra(), next.capeGlint());
            providers = new AppearanceProviders(providers.skin(), switch (provider) {
                case OPTIFINE -> providers.cape().observeOptifine(cape);
                case SKINMC -> providers.cape().observeSkinmc(cape);
                case MINECRAFTCAPES -> providers.cape().observeMinecraftcapes(cape);
                default -> throw new IllegalStateException("Unexpected prepared provider");
            });
            observationListener.accept(new CapeObservationPort.Observation(provider,
                    identity.profileId(), identity.canonicalName(), providers.cape().configurationRevision(),
                    null, ProviderObservation.observed(cape)));
        }
        publish();
    }

    private void replace(CapeProjection.Identity identity, Observed next) {
        if (next.asset() != null) {
            replacePrepared(identity, BuiltinProvider.OPTIFINE, next);
            return;
        }
        Observed previous = observed.get(identity);
        if (sameObservation(previous, next)) return;
        retireFrame(new FrameKey(identity, BuiltinProvider.OPTIFINE));
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
        if (previous.asset() != null || next.asset() != null) return false;
        if (previous.png() == null || next.png() == null) {
            return previous.png() == null && next.png() == null;
        }
        return previous.png().renderSha256().equals(next.png().renderSha256())
                && previous.png().hasElytra() == next.png().hasElytra()
                && previous.location() != null;
    }

    private PreparedCapeAsset readAccountedObserved(String visualKey) throws IOException {
        if (!CapePreparationQueue.reconcileObservedCache(textures)) return null;
        return textures.readObservedPreparedCape(visualKey).orElse(null);
    }

    private List<FrameKey> requestedFrames(FrameKey request) {
        List<FrameKey> requested = new ArrayList<>();
        for (BuiltinProvider provider : providers.cape().order()) {
            if (!providers.cape().enabled(provider)) continue;
            FrameKey key = new FrameKey(request.identity(), provider);
            FrameState frame = frames.get(key);
            if (frame != null && (frame.installedIndex < 0 || provider == request.provider())) {
                requested.add(key);
            }
            if (provider == request.provider()) break;
        }
        return requested;
    }

    private void frameDemand(FrameKey key) {
        if (closed || !registration.active() || !current(key.identity())) return;
        FrameState state = frames.get(key);
        if (state == null || state.failures > 3
                || state.failures > 0 && monotonicNanos.getAsLong() - state.retryAtNanos < 0) return;
        int desired = state.installedIndex < 0 ? 0 : state.timeline.frameAt(Math.max(0,
                (monotonicNanos.getAsLong() - state.startedNanos) / 1_000_000L));
        if (desired == state.installedIndex || frameActive.contains(key) || frameQueue.contains(key)) return;
        if (frameQueue.size() >= MAX_QUEUE) return;
        frameQueue.add(key);
        pumpFrames();
    }

    @SuppressWarnings("try")
    private void pumpFrames() {
        while (!closed && frameActive.size() < MAX_ACTIVE && !frameQueue.isEmpty()) {
            FrameKey key = frameQueue.remove();
            FrameState state = frames.get(key);
            if (state == null || !current(key.identity()) || state.failures > 3
                    || state.failures > 0 && monotonicNanos.getAsLong() - state.retryAtNanos < 0) continue;
            int index = state.installedIndex < 0 ? 0 : state.timeline.frameAt(Math.max(0,
                    (monotonicNanos.getAsLong() - state.startedNanos) / 1_000_000L));
            if (index == state.installedIndex) continue;
            frameActive.add(key);
            try { worker.execute(() -> {
                byte[] rgba = null;
                CapePreparationQueue.Reservation cpu = null;
                Throwable frameFailure = null;
                try {
                    cpu = CapePreparationQueue.reserveCpu(Math.multiplyExact(state.asset.frameBytes(), 2L),
                            () -> !closed && !state.retired);
                    rgba = state.readFrame(index);
                } catch (IOException | RuntimeException unavailable) {
                    frameFailure = unavailable;
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                } catch (Error failure) {
                    if (cpu != null) cpu.close();
                    throw failure;
                }
                byte[][] ready = {rgba};
                Throwable readFailure = frameFailure;
                CapePreparationQueue.Reservation heldCpu = cpu;
                try { clientExecutor.execute(() -> {
                    synchronized (CapeProviderCoordinator.this) {
                        boolean handedToRegistry = false;
                        try {
                            frameActive.remove(key);
                            if (ready[0] != null && !closed && frames.get(key) == state
                                    && current(key.identity())) {
                                handedToRegistry = true;
                                java.util.Optional<String> registered;
                                try {
                                    registered = nativeTextures.registerFrame(key.identity().profileId(),
                                            CapeSource.valueOf(key.provider().name()),
                                            state.asset.frameSha256().get(index),
                                            state.asset.width(), state.asset.height(), ready[0], heldCpu);
                                } catch (RuntimeException unavailable) {
                                    failFrame(state);
                                    pumpFrames();
                                    return;
                                }
                                ready[0] = null;
                                if (registered.isPresent()) {
                                    heldCpu.sourcePixelsReleased(state.asset.frameBytes());
                                }
                                if (registered.isPresent()) {
                                    state.installedIndex = index;
                                    state.failures = 0;
                                    if (!state.started) {
                                        state.startedNanos = monotonicNanos.getAsLong();
                                        state.started = true;
                                    }
                                    if (key.provider() == BuiltinProvider.OFFLINE
                                            && key.identity().equals(selfIdentity)) {
                                        selfCandidates.put(BuiltinProvider.OFFLINE,
                                                candidate(registered.orElseThrow(), state.asset.hasElytra()));
                                        publish();
                                    } else if (key.provider() == BuiltinProvider.OPTIFINE
                                            || key.provider() == BuiltinProvider.SKINMC
                                            || key.provider() == BuiltinProvider.MINECRAFTCAPES) {
                                        Map<CapeProjection.Identity, Observed> observations = switch (key.provider()) {
                                            case OPTIFINE -> observed;
                                            case SKINMC -> skinMcObserved;
                                            case MINECRAFTCAPES -> minecraftCapesObserved;
                                            default -> throw new IllegalStateException("Unexpected prepared provider");
                                        };
                                        Observed current = observations.get(key.identity());
                                        if (current != null && current.asset() == state.asset) {
                                            observations.put(key.identity(), new Observed(null,
                                                    state.asset, current.capeGlint(), registered.orElseThrow()));
                                            publish();
                                        }
                                    }
                                } else {
                                    failFrame(state);
                                }
                            } else if (ready[0] == null && !closed && frames.get(key) == state
                                    && current(key.identity())) {
                                if (CapeIntegrityException.isIntegrityFailure(readFailure)) retireCorruptVisual(key, state);
                                else failFrame(state);
                            }
                            pumpFrames();
                            frameDemand(key);
                        } finally {
                            ready[0] = null;
                            if (!handedToRegistry && heldCpu != null) heldCpu.close();
                        }
                    }
                }); }
                catch (RuntimeException rejected) {
                    if (heldCpu != null) heldCpu.close();
                    synchronized (CapeProviderCoordinator.this) {
                        frameActive.remove(key);
                        if (!closed && frames.get(key) == state && current(key.identity())) failFrame(state);
                    }
                }
            }); }
            catch (java.util.concurrent.RejectedExecutionException rejected) {
                frameActive.remove(key);
                failFrame(state);
            }
        }
    }

    private void failFrame(FrameState state) {
        state.failures++;
        long millis = switch (state.failures) {
            case 1 -> 250L;
            case 2 -> 1_000L;
            default -> 5_000L;
        };
        state.retryAtNanos = monotonicNanos.getAsLong() + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(millis);
    }

    private void retireCorruptVisual(FrameKey key, FrameState state) {
        boolean ownsVisual = state.installedIndex >= 0;
        if (key.provider() == BuiltinProvider.OFFLINE) {
            if (ownsVisual) selfCandidates.remove(key.provider());
        }
        else {
            Map<CapeProjection.Identity, Observed> observations = switch (key.provider()) {
                case OPTIFINE -> observed;
                case SKINMC -> skinMcObserved;
                case MINECRAFTCAPES -> minecraftCapesObserved;
                default -> throw new IllegalStateException("Unexpected prepared provider");
            };
            Observed value = observations.get(key.identity());
            if (value != null && value.asset() == state.asset) {
                observations.put(key.identity(), new Observed(null, state.asset, value.capeGlint(), null));
            }
        }
        publish();
        if (key.provider() != BuiltinProvider.OFFLINE || ownsVisual) {
            nativeTextures.release(key.identity().profileId(), CapeSource.valueOf(key.provider().name()));
        }
        retireFrame(key);
    }

    private void retireFrame(FrameKey key) {
        FrameState removed = frames.remove(key);
        frameQueue.remove(key);
        frameDispatch.remove(key);
        if (removed != null) {
            removed.retired = true;
            CapePreparationQueue.wakeWaiters();
            try { removed.closeBacking(); }
            catch (IOException ignored) { }
        }
    }

    private record FrameKey(CapeProjection.Identity identity, BuiltinProvider provider) {}

    private static final class FrameState {
        private final PreparedCapeAsset asset;
        private PreparedCapeAsset.BackingLease backing;
        private volatile boolean reopenBacking;
        private final CapeTimeline timeline;
        private long startedNanos;
        private boolean started;
        private int failures;
        private long retryAtNanos;
        private int installedIndex = -1;
        private volatile boolean retired;

        private synchronized byte[] readFrame(int index) throws IOException {
            if (retired) return null;
            if (backing == null || reopenBacking) {
                PreparedCapeAsset.BackingLease previous = backing;
                backing = null;
                if (previous != null) previous.close();
                backing = asset.openBackingLease();
                reopenBacking = false;
            }
            try {
                return backing.readFrame(index);
            } catch (java.nio.channels.ClosedChannelException unavailable) {
                reopenBacking = true;
                throw unavailable;
            }
        }

        private synchronized void closeBacking() throws IOException {
            PreparedCapeAsset.BackingLease previous = backing;
            backing = null;
            if (previous != null) previous.close();
        }

        private FrameState(PreparedCapeAsset asset) {
            this.asset = asset;
            int[] delays = asset.delaysMillis().stream().mapToInt(Integer::intValue).toArray();
            this.timeline = new CapeTimeline(delays,
                    asset.repeatCount() == 0 ? 0 : (long) asset.repeatCount() + 1);
        }
    }

    private static String capeId(CapeProjection.Identity identity) {
        return "optifine:" + identity.profileId().toString().replace("-", "")
                + ":" + identity.canonicalName();
    }

    private static String skinMcCapeId(CapeProjection.Identity identity) {
        return "skinmc:" + identity.profileId().toString().replace("-", "");
    }

    private static String minecraftCapesCapeId(CapeProjection.Identity identity) {
        return "minecraftcapes:" + identity.profileId().toString().replace("-", "");
    }

    private synchronized boolean current(CapeProjection.Identity identity) {
        GameSessionTokenSource.SessionIdentity session = tokenSource.currentSession();
        if (selfIdentity == null || !selfIdentity.equals(
                new CapeProjection.Identity(session.profileId(), session.profileName()))) return false;
        return identity.equals(selfIdentity) || identity.equals(tracked.get(identity.profileId()));
    }

    private void retireTrackedIdentity(CapeProjection.Identity identity) {
        registration.retireVisibleSkin(identity);
        remove(identity);
        removeSkinMc(identity);
        removeMinecraftCapes(identity);
        removeSneakyRemote(identity);
        String skin = sneakyVisibleSkins.remove(identity);
        if (skin != null && !sneakyVisibleSkins.containsValue(skin)) sneakyProcessor.forget(sneakyKey(skin));
    }

    private void remove(CapeProjection.Identity identity) {
        retireFrame(new FrameKey(identity, BuiltinProvider.OPTIFINE));
        optifineSchedule.queue.remove(identity);
        optifineSchedule.attempted.remove(identity);
        optifineSchedule.repeatPending.remove(identity);
        Request request = optifineSchedule.pending.remove(identity);
        if (request != null) request.releasePin();
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
        retireFrame(new FrameKey(identity, BuiltinProvider.SKINMC));
        skinMcSchedule.queue.remove(identity);
        skinMcSchedule.attempted.remove(identity);
        skinMcSchedule.repeatPending.remove(identity);
        Request request = skinMcSchedule.pending.remove(identity);
        if (request != null) request.releasePin();
        if (request != null && request.future != null) {
            request.future.cancel(true);
            purgeCancelledWork();
        }
        Observed removed = skinMcObserved.remove(identity);
        if (removed != null && removed.location() != null) {
            nativeTextures.release(identity.profileId(), CapeSource.SKINMC);
        }
    }

    private void removeMinecraftCapes(CapeProjection.Identity identity) {
        retireFrame(new FrameKey(identity, BuiltinProvider.MINECRAFTCAPES));
        minecraftCapesSchedule.queue.remove(identity);
        minecraftCapesSchedule.attempted.remove(identity);
        minecraftCapesSchedule.repeatPending.remove(identity);
        Request request = minecraftCapesSchedule.pending.remove(identity);
        if (request != null) request.releasePin();
        if (request != null && request.future != null) {
            request.future.cancel(true);
            purgeCancelledWork();
        }
        Observed removed = minecraftCapesObserved.remove(identity);
        if (removed != null && removed.location() != null) {
            nativeTextures.release(identity.profileId(), CapeSource.MINECRAFTCAPES);
        }
    }

    private void releaseObservedTextures() {
        for (var entry : observed.entrySet()) {
            retireFrame(new FrameKey(entry.getKey(), BuiltinProvider.OPTIFINE));
            if (entry.getValue().location() != null) {
                nativeTextures.release(entry.getKey().profileId(), CapeSource.OPTIFINE);
                entry.setValue(new Observed(entry.getValue().png(), entry.getValue().asset(),
                        false, null));
            }
        }
    }

    private void releaseSkinMcTextures() {
        for (var entry : skinMcObserved.entrySet()) {
            retireFrame(new FrameKey(entry.getKey(), BuiltinProvider.SKINMC));
            if (entry.getValue().location() != null) {
                nativeTextures.release(entry.getKey().profileId(), CapeSource.SKINMC);
                entry.setValue(new Observed(entry.getValue().png(), entry.getValue().asset(),
                        false, null));
            }
        }
    }

    private void releaseMinecraftCapesTextures() {
        for (var entry : minecraftCapesObserved.entrySet()) {
            retireFrame(new FrameKey(entry.getKey(), BuiltinProvider.MINECRAFTCAPES));
            Observed value = entry.getValue();
            if (value.location() != null) {
                nativeTextures.release(entry.getKey().profileId(), CapeSource.MINECRAFTCAPES);
                entry.setValue(new Observed(null, value.asset(), value.capeGlint(), null));
            }
        }
    }

    private void restoreObservedTextures() {
        for (var entry : observed.entrySet()) {
            if (entry.getValue().asset() != null && current(entry.getKey())) {
                replacePrepared(entry.getKey(), BuiltinProvider.OPTIFINE, entry.getValue());
                continue;
            }
            if (entry.getValue().png() == null || !current(entry.getKey())) continue;
            var png = entry.getValue().png();
            String location = nativeTextures.register(entry.getKey().profileId(), CapeSource.OPTIFINE,
                    png.renderSha256(), png.bytes()).orElse(null);
            entry.setValue(new Observed(png, location));
        }
    }

    private void restoreSkinMcTextures() {
        for (var entry : skinMcObserved.entrySet()) {
            if (entry.getValue().asset() != null && current(entry.getKey())) {
                replacePrepared(entry.getKey(), BuiltinProvider.SKINMC, entry.getValue());
                continue;
            }
            if (entry.getValue().png() == null || !current(entry.getKey())) continue;
            var png = entry.getValue().png();
            String location = nativeTextures.register(entry.getKey().profileId(), CapeSource.SKINMC,
                    png.renderSha256(), png.bytes()).orElse(null);
            entry.setValue(new Observed(png, location));
        }
    }

    private void restoreMinecraftCapesTextures() {
        for (var entry : List.copyOf(minecraftCapesObserved.entrySet())) {
            Observed value = entry.getValue();
            if (value.asset() != null && current(entry.getKey())) {
                replaceMinecraftCapes(entry.getKey(),
                        new Observed(null, value.asset(), value.capeGlint(), null));
            }
        }
    }

    private void publish() {
        refreshObservedCachePins();
        Map<CapeProjection.Identity, ObservationKey> observations = new HashMap<>();
        for (var entry : observed.entrySet()) {
            PngValidator.CapePng png = entry.getValue().png();
            PreparedCapeAsset asset = entry.getValue().asset();
            observations.put(entry.getKey(), new ObservationKey(asset != null ? asset.visualSha256()
                    : png == null ? null : png.renderSha256(),
                    asset != null ? asset.hasElytra() : png != null && png.hasElytra(),
                    entry.getValue().location()));
        }
        Map<CapeProjection.Identity, ObservationKey> skinMcObservations = new HashMap<>();
        for (var entry : skinMcObserved.entrySet()) {
            PngValidator.CapePng png = entry.getValue().png();
            PreparedCapeAsset asset = entry.getValue().asset();
            skinMcObservations.put(entry.getKey(), new ObservationKey(
                    asset != null ? asset.visualSha256() : png == null ? null : png.renderSha256(),
                    asset != null ? asset.hasElytra() : png != null && png.hasElytra(),
                    entry.getValue().location()));
        }
        Map<CapeProjection.Identity, ObservationKey> sneakyRemoteObservations = new HashMap<>();
        for (var entry : sneakyRemote.entrySet()) {
            PngValidator.CapePng png = entry.getValue().png();
            sneakyRemoteObservations.put(entry.getKey(), new ObservationKey(
                    png == null ? null : png.renderSha256(),
                    png != null && png.hasElytra(), entry.getValue().location()));
        }
        Map<CapeProjection.Identity, ObservationKey> minecraftCapesObservations = new HashMap<>();
        for (var entry : minecraftCapesObserved.entrySet()) {
            PreparedCapeAsset asset = entry.getValue().asset();
            minecraftCapesObservations.put(entry.getKey(), new ObservationKey(
                    asset == null ? null : asset.visualSha256(),
                    asset != null && asset.hasElytra(), entry.getValue().location(),
                    entry.getValue().capeGlint()));
        }
        ProjectionState state = new ProjectionState(selfIdentity, providers.cape().order(),
                providers.cape().offline(), providers.cape().minecraft(),
                providers.cape().optifine(), providers.cape().skinmc(), providers.cape().minecraftcapes(),
                sneakySkinSha, sneakySelf == null || sneakySelf.png() == null ? null
                        : new ObservationKey(sneakySelf.png().renderSha256(),
                                sneakySelf.png().hasElytra(), sneakySelf.location()),
                Map.copyOf(observations), Map.copyOf(skinMcObservations),
                Map.copyOf(minecraftCapesObservations),
                Map.copyOf(sneakyRemoteObservations), Map.copyOf(selfCandidates));
        if (state.equals(publishedProjection)) return;
        publishedProjection = state;
        Map<CapeProjection.Identity, CapeProjection.Candidate> capes = new HashMap<>();
        Map<CapeProjection.Identity, CapeProjection.Candidate> skinMcCapes = new HashMap<>();
        Map<CapeProjection.Identity, CapeProjection.Candidate> minecraftCapes = new HashMap<>();
        Map<CapeProjection.Identity, CapeProjection.Candidate> sneakyCapes = new HashMap<>();
        if (enabled()) {
            for (var entry : observed.entrySet()) {
                if (entry.getValue().location() != null && current(entry.getKey())) {
                    capes.put(entry.getKey(), candidate(entry.getValue().location(),
                            entry.getValue().asset() != null ? entry.getValue().asset().hasElytra()
                                    : entry.getValue().png().hasElytra()));
                }
            }
        }
        if (skinMcEnabled()) {
            for (var entry : skinMcObserved.entrySet()) {
                if (entry.getValue().location() != null && current(entry.getKey())) {
                    skinMcCapes.put(entry.getKey(), candidate(entry.getValue().location(),
                            entry.getValue().asset() != null ? entry.getValue().asset().hasElytra()
                                    : entry.getValue().png().hasElytra()));
                }
            }
        }
        if (minecraftCapesEnabled()) {
            for (var entry : minecraftCapesObserved.entrySet()) {
                Observed value = entry.getValue();
                if (value.location() != null && value.asset() != null && current(entry.getKey())) {
                    String location = value.location();
                    minecraftCapes.put(entry.getKey(), new CapeProjection.Candidate(location,
                            value.asset().hasElytra() ? location : null,
                            value.asset().hasElytra(), value.capeGlint()));
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
                minecraftCapes, registration.cpmCandidates(), selfIdentity, selfCandidates.get(BuiltinProvider.OFFLINE),
                selfCandidates.get(BuiltinProvider.MINECRAFT)));
    }

    private void refreshObservedCachePins() {
        Set<String> keys = new HashSet<>();
        for (Map<CapeProjection.Identity, Observed> source : List.of(
                observed, skinMcObserved, minecraftCapesObserved)) {
            for (Observed value : source.values()) {
                if (value.asset() != null) keys.add(value.asset().visualSha256());
            }
        }
        for (BuiltinProvider provider : List.of(BuiltinProvider.OPTIFINE,
                BuiltinProvider.SKINMC, BuiltinProvider.MINECRAFTCAPES)) {
            ProviderCape value = providers.cape().observation(provider).value();
            if (value != null && value.textureCacheKey() != null) keys.add(value.textureCacheKey());
        }
        CapePreparationQueue.pinObservedFor(this, keys);
    }

    private static CapeProjection.Candidate candidate(String location, boolean hasElytra) {
        return new CapeProjection.Candidate(location, hasElytra ? location : null, hasElytra);
    }

    private record Observed(PngValidator.CapePng png, PreparedCapeAsset asset,
            boolean capeGlint, String location) {
        private Observed(PngValidator.CapePng png, String location) {
            this(png, null, false, location);
        }
        private String visualKey() {
            return asset != null ? asset.visualSha256() : png == null ? null : png.renderSha256();
        }
        private boolean hasElytra() {
            return asset != null ? asset.hasElytra() : png != null && png.hasElytra();
        }
    }

    private record ObservationKey(String sha256, boolean hasElytra, String location,
            boolean capeGlint) {
        private ObservationKey(String sha256, boolean hasElytra, String location) {
            this(sha256, hasElytra, location, false);
        }
    }

    private record ProjectionState(CapeProjection.Identity selfIdentity, List<BuiltinProvider> order,
            ProviderObservation<ProviderCape> offline, ProviderObservation<ProviderCape> minecraft,
            ProviderObservation<ProviderCape> optifine,
            ProviderObservation<ProviderCape> skinmc,
            ProviderObservation<ProviderCape> minecraftcapes,
            String sneakySkinSha,
            ObservationKey sneakyObservation,
            Map<CapeProjection.Identity, ObservationKey> observations,
            Map<CapeProjection.Identity, ObservationKey> skinMcObservations,
            Map<CapeProjection.Identity, ObservationKey> minecraftCapesObservations,
            Map<CapeProjection.Identity, ObservationKey> sneakyRemoteObservations,
            Map<BuiltinProvider, CapeProjection.Candidate> selfCandidates) {}

    private record Persisted(boolean applied, ProviderCape cape) {}

    private record Prepared(String sha256, byte[] bytes, boolean hasElytra, PreparedCapeAsset asset) {
        private Prepared(String sha256, byte[] bytes, boolean hasElytra) {
            this(sha256, bytes, hasElytra, null);
        }
    }

    private static final class Request {
        private final long generation;
        private final long selfEpoch;
        private final ProviderObservation<ProviderCape> dispatchedObservation;
        private final boolean explicitRefresh;
        private final ProviderRefreshUseCases.Scope scope;
        private Future<?> future;
        private CapePreparationQueue.ObservedPin pin;
        private boolean cancelled;

        private Request(long generation, long selfEpoch,
                ProviderObservation<ProviderCape> dispatchedObservation, boolean explicitRefresh,
                ProviderRefreshUseCases.Scope scope) {
            this.scope = scope;
            this.generation = generation;
            this.selfEpoch = selfEpoch;
            this.dispatchedObservation = dispatchedObservation;
            this.explicitRefresh = explicitRefresh;
        }

        private synchronized void adoptPin(CapePreparationQueue.ObservedPin next) {
            if (next == null) return;
            if (cancelled) next.close();
            else pin = next;
        }

        private synchronized void releasePin() {
            cancelled = true;
            if (pin != null) {
                pin.close();
                pin = null;
            }
        }
    }
    private boolean currentScope(ProviderRefreshUseCases.Scope scope) {
        if (scope == null) return true;
        try {
            return !closed && scope.identity().equals(tokenSource.currentSession())
                    && scope.capeGeneration() == generation
                    && scope.configurationMatches(storage.loadAppearance(scope.identity().profileId()).providers());
        } catch (IOException | RuntimeException unavailable) { return false; }
    }

    private boolean currentPublishedScope(ProviderRefreshUseCases.Scope scope) {
        return scope == null || !closed && scope.identity().equals(tokenSource.currentSession())
                && scope.capeGeneration() == generation && scope.configurationMatches(providers);
    }

    private Persisted persistRemoteSelf(BuiltinProvider provider, CapeProjection.Identity identity, Request request,
            RemoteRead outcome) {
        if (closed || !registration.active() || request.generation != generation || request.selfEpoch != epoch(provider)
                || !current(identity) || !currentScope(request.scope)) {
            return new Persisted(false, null);
        }
        try {
            ProviderCape cape = null;
            if (outcome.kind() == ReadKind.PRESENT) {
                if (outcome.asset() != null) {
                    String id = switch (provider) {
                        case OPTIFINE -> capeId(identity);
                        case SKINMC -> skinMcCapeId(identity);
                        case MINECRAFTCAPES -> minecraftCapesCapeId(identity);
                        default -> throw new IllegalStateException("Not a remote cape provider");
                    };
                    cape = new ProviderCape(id, outcome.asset().visualSha256(),
                            outcome.asset().hasElytra(), outcome.capeGlint());
                } else {
                    String key = textures.storeObservedCape(outcome.cape());
                    cape = new ProviderCape((provider == BuiltinProvider.OPTIFINE ? capeId(identity) : skinMcCapeId(identity)), key, outcome.cape().hasElytra());
                }
            }
            ProviderCape confirmed = cape;
            boolean[] applied = {false};
            storage.updateAppearance(identity.profileId(), current -> {
                GameSessionTokenSource.SessionIdentity session = tokenSource.currentSession();
                if (closed || !registration.active() || request.generation != generation || request.selfEpoch != epoch(provider)
                        || !identity.equals(new CapeProjection.Identity(
                                session.profileId(), session.profileName()))
                        || !current.providers().cape().enabled(provider)
                        || request.scope != null && !request.scope.configurationMatches(current.providers())
                        || !current.providers().cape().observation(provider).equals(request.dispatchedObservation)) return current;
                applied[0] = true;
                return current.withProviders(new AppearanceProviders(current.providers().skin(),
                        switch (provider) {
                            case OPTIFINE -> current.providers().cape().observeOptifine(confirmed);
                            case SKINMC -> current.providers().cape().observeSkinmc(confirmed);
                            case MINECRAFTCAPES -> current.providers().cape().observeMinecraftcapes(confirmed);
                            default -> throw new IllegalStateException("Not a remote cape provider");
                        }));
            });
            return new Persisted(applied[0], cape);
        } catch (IOException | com.naocraftlab.skins.core.png.PngValidationException unavailable) {
            return new Persisted(false, null);
        }
    }

    private long epoch(BuiltinProvider provider) {
        return switch (provider) {
            case OPTIFINE -> selfEpoch;
            case SKINMC -> skinMcSelfEpoch;
            case MINECRAFTCAPES -> minecraftCapesSelfEpoch;
            default -> throw new IllegalStateException("Not a remote cape provider");
        };
    }

    private enum ReadKind { PRESENT, ABSENT, FAILURE, CAPACITY }
    private record RemoteRead(ReadKind kind, PngValidator.CapePng cape,
            PreparedCapeAsset asset, boolean capeGlint, boolean rateLimited,
            CapePreparationQueue.ObservedPin pin) {
        private RemoteRead(ReadKind kind, PngValidator.CapePng cape,
                PreparedCapeAsset asset, boolean capeGlint, boolean rateLimited) {
            this(kind, cape, asset, capeGlint, rateLimited, null);
        }
    }

    private final class RemoteCapeScheduler {
        private final ArrayDeque<CapeProjection.Identity> queue = new ArrayDeque<>();
        private final Map<CapeProjection.Identity, Request> pending = new HashMap<>();
        private final Set<CapeProjection.Identity> attempted = new HashSet<>();
        private final Set<CapeProjection.Identity> repeatPending = new LinkedHashSet<>();
        private Iterator<CapeProjection.Identity> sweep = List.<CapeProjection.Identity>of().iterator();
        private final List<Consumer<ProviderObservation<ProviderCape>>> refreshCompletions = new ArrayList<>();
        private ProviderObservation<ProviderCape> confirmedRefreshObservation;
        private boolean explicitSweep;
        private ProviderRefreshUseCases.Scope activeScope;
        private boolean refreshPending;
        private boolean unknownSweepPending;
        private boolean unknownOnly;
        private boolean pumping;
        private CapePreparationQueue.CapacityNotice capacityWait;
        private static final long READ_SCRATCH_BYTES = CapePreparationQueue.CAPE_PREPARATION_PEAK + 64L * 1024;

        private final BuiltinProvider provider;
        private RemoteCapeScheduler(BuiltinProvider provider) { this.provider = provider; }
        private boolean enabled() { return providers.cape().enabled(provider)
                && (provider != BuiltinProvider.MINECRAFTCAPES || minecraftCapesReader != null); }
        private long epoch() { return switch (provider) {
            case OPTIFINE -> selfEpoch;
            case SKINMC -> skinMcSelfEpoch;
            case MINECRAFTCAPES -> minecraftCapesSelfEpoch;
            default -> throw new IllegalStateException("Not a remote cape provider");
        }; }
        private Map<CapeProjection.Identity, Observed> observations() { return switch (provider) {
            case OPTIFINE -> observed;
            case SKINMC -> skinMcObserved;
            case MINECRAFTCAPES -> minecraftCapesObserved;
            default -> throw new IllegalStateException("Not a remote cape provider");
        }; }
        private java.util.Optional<java.time.Duration> cooldownRemaining() {
            return switch (provider) {
                case OPTIFINE -> reader.cooldownRemaining();
                case SKINMC -> skinMcReader.cooldownRemaining();
                case MINECRAFTCAPES -> minecraftCapesReader == null ? java.util.Optional.empty() : minecraftCapesReader.cooldownRemaining();
                default -> throw new IllegalStateException("Not a remote cape provider");
            };
        }
        private long accountEpoch() { return switch (provider) {
            case OPTIFINE -> reader.accountEpoch();
            case SKINMC -> skinMcReader.accountEpoch();
            case MINECRAFTCAPES -> minecraftCapesReader.accountEpoch();
            default -> throw new IllegalStateException("Not a remote cape provider");
        }; }
        private RemoteRead read(CapeProjection.Identity identity, long accountEpoch) {
            try {
                if (!CapePreparationQueue.reconcileObservedCache(textures)) {
                    return new RemoteRead(ReadKind.CAPACITY, null, null, false, false);
                }
            } catch (IOException unavailable) {
                return new RemoteRead(ReadKind.FAILURE, null, null, false, false);
            }
            CapePreparationQueue.Reservation scratch = CapePreparationQueue.tryReserveScratch(READ_SCRATCH_BYTES);
            if (scratch == null) return new RemoteRead(ReadKind.CAPACITY, null, null, false, false);
            try {
                CapeObservationReader source = switch (provider) {
                    case OPTIFINE -> reader;
                    case SKINMC -> skinMcReader;
                    case MINECRAFTCAPES -> minecraftCapesReader;
                    default -> throw new IllegalStateException("Not a remote cape provider");
                };
                var observed = source.observe(identity.profileId(), identity.canonicalName(), accountEpoch);
                RemoteRead result = new RemoteRead(ReadKind.valueOf(observed.kind().name()),
                        observed.cape(), observed.asset(), observed.capeGlint(), observed.rateLimited());
                if (result.asset() == null) return result;
                PreparedCapeAsset asset = null;
                CapePreparationQueue.ObservedPin pin = null;
                try {
                    asset = textures.readObservedPreparedCape(result.asset().visualSha256()).orElse(null);
                    if (asset == null) {
                        String key = textures.storeObservedPreparedCape(result.asset());
                        asset = textures.readObservedPreparedCape(key).orElse(null);
                        if (asset != null) {
                            pin = CapePreparationQueue.retainObservedCache(textures, asset, scratch);
                            scratch = null;
                        }
                    } else {
                        pin = CapePreparationQueue.pinObservedCache(asset);
                    }
                } catch (IOException invalid) {
                    asset = null;
                    if (pin != null) pin.close();
                } finally {
                    try { source.discard(result.asset()); }
                    catch (IOException ignored) {}
                }
                if (asset == null) return new RemoteRead(ReadKind.FAILURE, null, null, false, false);
                return new RemoteRead(result.kind(), null, asset, result.capeGlint(), result.rateLimited(), pin);
            } finally {
                if (scratch != null) scratch.close();
            }
        }

        private Persisted persist(CapeProjection.Identity identity, Request request, RemoteRead result) {
            return persistRemoteSelf(provider, identity, request, result);
        }
        private void replaceObservation(CapeProjection.Identity identity, Observed next) {
            if (provider == BuiltinProvider.OPTIFINE) replace(identity, next);
            else if (provider == BuiltinProvider.SKINMC) replaceSkinMc(identity, next);
            else replaceMinecraftCapes(identity, next);
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
            activeScope = null;
            finishRefreshCompletions();
        }

        private void retainRefresh(boolean unknownOnly) {
            if (pending.isEmpty()) {
                startSweep(unknownOnly);
            } else {
                refreshPending = true;
            }
        }

        private void startSweep(boolean unknownOnly) {
            startSweep(unknownOnly, null);
        }

        private void startSweep(boolean unknownOnly, ProviderRefreshUseCases.Scope scope) {
            this.activeScope = scope;
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
                acceptTrackedIdentity(identity);
                if (seen.add(identity)) identities.add(identity);
            }
            for (CapeProjection.Identity identity : List.copyOf(tracked.values())) {
                if (!seen.contains(identity)) {
                    tracked.remove(identity.profileId());
                    retireTrackedIdentity(identity);
                }
            }
            publish();
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
            if (aggregateQueue() >= MAX_QUEUE || pending.containsKey(identity) || queue.contains(identity)
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
                if (aggregateQueue() >= MAX_QUEUE) break;
                attempted.remove(identity);
                queue.add(identity);
                deferred.remove();
            }
            while (aggregateQueue() < MAX_QUEUE && sweep.hasNext()) {
                enqueue(sweep.next(), true);
            }
        }

        private void pump() {
            if (pumping || closed || capacityWait != null) return;
            pumping = true;
            try {
                fillQueue();
                while (enabled() && cooldownRemaining().isEmpty()
                        && aggregatePending() < MAX_ACTIVE && !queue.isEmpty()) {
                    CapeProjection.Identity identity = queue.remove();
                    if (!current(identity) || pending.containsKey(identity)) continue;
                    long requestGeneration = generation;
                    Request request = new Request(requestGeneration, epoch(),
                            identity.equals(selfIdentity) ? providers.cape().observation(provider) : null,
                            explicitSweep, activeScope);
                    long accountEpoch = accountEpoch();
                    pending.put(identity, request);
                    Runnable task = () -> {
                        if (closed || !registration.active() || request.generation != generation
                                || identity.equals(selfIdentity) && request.selfEpoch != epoch()
                                || !current(identity) || !currentScope(request.scope)) {
                            synchronized (CapeProviderCoordinator.this) { rejected(identity, request); }
                            return;
                        }
                        RemoteRead outcome;
                        try { outcome = read(identity, accountEpoch); }
                        catch (RuntimeException unavailable) {
                            synchronized (CapeProviderCoordinator.this) { rejected(identity, request); }
                            return;
                        }
                        request.adoptPin(outcome.pin());
                        synchronized (CapeProviderCoordinator.this) {
                            if (pending.get(identity) != request || closed) {
                                request.releasePin();
                                return;
                            }
                        }
                        try {
                            Persisted persisted = identity.equals(selfIdentity)
                                    && (outcome.kind() == ReadKind.PRESENT || outcome.kind() == ReadKind.ABSENT)
                                    ? persist(identity, request, outcome) : null;
                            clientExecutor.execute(() -> {
                                try { synchronized (CapeProviderCoordinator.this) {
                                    complete(identity, request, outcome, persisted);
                                } } finally { request.releasePin(); }
                            });
                        } catch (RuntimeException rejected) {
                            request.releasePin();
                            synchronized (CapeProviderCoordinator.this) {
                                rejected(identity, request);
                            }
                        }
                    };
                    try {
                        if (worker instanceof ExecutorService service) {
                            Future<?> future = service.submit(task);
                            if (pending.get(identity) == request) request.future = future;
                        } else {
                            worker.execute(task);
                        }
                    } catch (java.util.concurrent.RejectedExecutionException rejected) {
                        rejected(identity, request);
                    }
                    fillQueue();
                }
            } finally {
                pumping = false;
            }
        }

        private void rejected(CapeProjection.Identity identity, Request request) {
            pending.remove(identity, request);
            request.releasePin();
            confirmedRefreshObservation = null;
            finishRefreshCompletions();
        }

        private void complete(CapeProjection.Identity identity, Request request,
                RemoteRead outcome, Persisted persisted) {
            if (pending.get(identity) != request) return;
            pending.remove(identity);
            if (outcome.kind() == ReadKind.CAPACITY && !closed && registration.active()
                    && request.generation == generation
                    && (!identity.equals(selfIdentity) || request.selfEpoch == epoch())
                    && enabled() && current(identity) && currentPublishedScope(request.scope)) {
                repeatPending.add(identity);
                awaitCapacity();
                return;
            }
            if (!closed && registration.active() && request.generation == generation
                    && (!identity.equals(selfIdentity) || request.selfEpoch == epoch())
                    && enabled() && current(identity) && currentPublishedScope(request.scope)) {
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
                        replaceObservation(identity, new Observed(outcome.cape(), outcome.asset(),
                                outcome.capeGlint(), null));
                        if (request.explicitRefresh) {
                            FrameKey key = new FrameKey(identity, provider);
                            FrameState frame = frames.get(key);
                            if (frame != null && frame.failures > 0) {
                                frame.failures = 0;
                                frame.reopenBacking = true;
                                frameDemand(key);
                            }
                        }
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
                    if (repeatPending.isEmpty()) activeScope = null;
                    if (unknownSweepPending) {
                        unknownSweepPending = false;
                        startSweep(true);
                    }
                }
            } else {
                pump();
            }
            for (RemoteCapeScheduler other : List.of(optifineSchedule, skinMcSchedule, minecraftCapesSchedule)) {
                if (other != this && (!other.queue.isEmpty() || other.sweep.hasNext())) other.pump();
            }
        }

        private void awaitCapacity() {
            if (capacityWait != null) return;
            capacityWait = CapePreparationQueue.capacityNotice(READ_SCRATCH_BYTES, 0,
                    notice -> {
                        try { clientExecutor.execute(() -> {
                            synchronized (CapeProviderCoordinator.this) {
                                if (capacityWait != notice) return;
                                capacityWait = null;
                                notice.close();
                                if (!closed && registration.active() && enabled()) pump();
                            }
                        }); } catch (RuntimeException rejected) {
                            synchronized (CapeProviderCoordinator.this) {
                                if (capacityWait != notice) return;
                                capacityWait = null;
                                confirmedRefreshObservation = null;
                                finishRefreshCompletions();
                            }
                            notice.close();
                        }
                    });
            capacityWait.arm();
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
            if (capacityWait != null) {
                capacityWait.close();
                capacityWait = null;
            }
            for (Request request : pending.values()) {
                request.releasePin();
                if (request.future != null) request.future.cancel(true);
            }
            pending.clear();
            purgeCancelledWork();
        }
    }
}

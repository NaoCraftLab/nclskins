package com.naocraftlab.skins.runtime;

import com.naocraftlab.skins.client.SkinModel;
import com.naocraftlab.skins.core.provider.BuiltinProvider;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

public final class CapeProjection {
    private static final AtomicReference<Registration> CURRENT = new AtomicReference<>();
    private static final RetainedSkinPixels PIXELS = new RetainedSkinPixels();
    private static volatile java.util.function.Consumer<SkinChange> skinChanged = id -> {};

    private static volatile java.util.function.Consumer<UUID> skinLookup = id -> {};

    public static void skinLookup(java.util.function.Consumer<UUID> lookup) {
        skinLookup = Objects.requireNonNull(lookup);
    }

    public static EffectiveSkinSource findSkinSource(UUID id) {
        EffectiveSkinSource source = skinSource(id);
        if (source == null || !source.preview()) skinLookup.accept(id);
        return skinSource(id);
    }

    public static void skinModels(java.util.function.Consumer<SkinChange> changed) {
        skinChanged = Objects.requireNonNull(changed);
    }

    public static CpmAccessoryObserver cpmObserver(EffectiveSkinSource source, String name) {
        return new CpmAccessoryObserver(source, name, source.nextObserver());
    }

    static boolean cpmObservation(EffectiveSkinSource source, String name,
            CpmAccessoryObservation observation) {
        Registration registration = CURRENT.get();
        if (registration == null || !registration.active() || source == null
                || registration.skinSources.get(source.cacheId()) != source
                || !source.accessories(observation)) return false;
        registration.cpmIdentities.put(source.cacheId(), new Identity(source.cacheId(), name));
        registration.events.cpmChanged();
        return true;
    }

    public static void invalidateCpmObservations() {
        Registration registration = CURRENT.get();
        if (registration == null || !registration.active()) return;
        registration.skinSources.values().forEach(source -> source.accessories(new CpmAccessoryObservation(
                source.accessories().generation() + 1, CpmAccessoryObservation.Availability.UNKNOWN, false, false)));
        registration.events.cpmChanged();
    }

    public static Candidate cpmCandidate(EffectiveSkinSource source) {
        if (source == null || !source.active()) return null;
        CpmAccessoryObservation observation = source.accessories();
        return observation.present() ? new Candidate(null, null, observation.elytra(), false,
                observation.cape(), observation.elytra(), source, observation) : null;
    }

    public static boolean cpmWins(UUID scope) {
        return cpmWins(skinSource(scope));
    }

    public static boolean cpmWins(EffectiveSkinSource source) {
        Registration registration = CURRENT.get();
        if (registration == null || source == null || !source.active()
                || registration.skinSources.get(source.cacheId()) != source) return false;
        CapeInputs inputs = registration.capeInputs.get(source.cacheId());
        if (inputs == null) return false;
        return EffectiveCapeResolver.resolve(registration.snapshot, source.cacheId(), inputs.name(),
                inputs.offline(), inputs.minecraft(), inputs.self()).provider() == BuiltinProvider.CPM;
    }

    private record CapeInputs(String name, Candidate offline, Candidate minecraft, boolean self) {}

    public record SkinChange(UUID cacheId, Registration registration, EffectiveSkinSource source) {
        public SkinChange(UUID cacheId, Registration registration) {
            this(cacheId, registration, registration.skinSources.get(cacheId));
        }
        public boolean active() {
            return registration.active() && registration.skinSources.get(cacheId) == source
                    && (source == null || source.active());
        }
    }

    public static EffectiveSkinSource effectiveSkin(UUID cacheId, UUID subject, String location,
            SkinModel model, boolean preview) {
        Registration registration = CURRENT.get();
        if (registration == null || !registration.active()) return null;
        long revision = PIXELS.revision(location);
        EffectiveSkinSource previous = registration.skinSources.get(cacheId);
        if (previous != null && previous.location().equals(location) && previous.model() == model
                && previous.subject().equals(subject) && previous.preview() == preview
                && previous.revision() == revision) return previous;
        if (previous == null && registration.skinSources.size() >= 513) return null;
        int[] pixels = PIXELS.read(location);
        EffectiveSkinSource next = new EffectiveSkinSource(cacheId, subject, location, model, preview,
                revision, pixels);
        registration.skinSources.put(cacheId, next);
        try { if (previous != null) previous.retire(); }
        finally { skinChanged.accept(new SkinChange(cacheId, registration)); }
        return next;
    }

    public static com.naocraftlab.skins.client.PreviewAccessories previewAccessories(UUID id,
            String location, SkinModel model, com.naocraftlab.skins.client.PreviewRenderer.LiveReason reason) {
        EffectiveSkinSource source = skinSource(id);
        if (source == null || !source.active() || !source.preview()
                || !source.location().equals(location) || source.model() != model)
            return com.naocraftlab.skins.client.PreviewAccessories.unavailable(reason);
        var observation = source.accessories();
        return new com.naocraftlab.skins.client.PreviewAccessories(source.inspectionScope(),
                observation.generation(), com.naocraftlab.skins.client.PreviewAccessories.Availability.valueOf(
                        observation.availability().name()), observation.cape(), observation.elytra(), reason);
    }

    public static EffectiveSkinSource skinSource(UUID cacheId) {
        Registration registration = CURRENT.get();
        return registration == null ? null : registration.skinSources.get(cacheId);
    }

    public static void retireSkin(UUID cacheId) {
        Registration registration = CURRENT.get();
        if (registration == null) return;
        registration.cpmIdentities.remove(cacheId);
        registration.capeInputs.remove(cacheId);
        EffectiveSkinSource previous = registration.skinSources.remove(cacheId);
        if (previous != null) {
            try { previous.retire(); }
            finally { skinChanged.accept(new SkinChange(cacheId, registration)); }
        }
    }

    public static void skinTextureReady(String skinLocation, int[] argb) {
        skinTextureReady(skinLocation, argb, null);
    }

    public static void skinTextureReady(String skinLocation, int[] argb, Object nativeTexture) {
        if (skinLocation == null || argb == null || argb.length != 64 * 64) return;
        RetainedSkinPixels.Snapshot pixels = PIXELS.capture(skinLocation, argb, nativeTexture);
        Registration registration = CURRENT.get();
        if (registration != null && pixels != null) {
            registration.events.skinTextureReady(skinLocation, pixels.argb(), pixels.revision());
        }
    }

    public static void visibleSkin(UUID profileId, String canonicalName, String skinLocation) {
        Identity identity = new Identity(profileId, canonicalName);
        Registration registration = CURRENT.get();
        Events events = registration == null ? null : registration.events;
        if (skinLocation != null && events != null && !events.hasSkinTexture(skinLocation)) {
            RetainedSkinPixels.Snapshot retained = PIXELS.snapshot(skinLocation);
            if (retained != null) events.skinTextureReady(skinLocation, retained.argb(), retained.revision());
        }
        if (registration == null) return;
        if (!registration.visibleSkins.containsKey(identity) && registration.visibleSkins.size() >= 513) return;
        String previous = skinLocation == null ? registration.visibleSkins.remove(identity)
                : registration.visibleSkins.put(identity, skinLocation);
        if (Objects.equals(previous, skinLocation)) return;
        if (events != null) events.visibleSkin(profileId, canonicalName, skinLocation);
    }

    public static Registration installEvents(Events events) {
        Registration registration = new Registration(Objects.requireNonNull(events, "events"));
        Registration previous = CURRENT.getAndSet(registration);
        if (previous != null) previous.close();
        return registration;
    }

    public static void trackedPlayer(UUID profileId, String canonicalName) {
        Registration registration = CURRENT.get();
        Events events = registration == null ? null : registration.events;
        if (events != null) events.trackedPlayer(profileId, canonicalName);
    }

    public static void playerInfoUpdated(UUID profileId, String canonicalName) {
        Registration registration = CURRENT.get();
        Events events = registration == null ? null : registration.events;
        if (events != null) events.playerInfoUpdated(profileId, canonicalName);
    }

    public static void untrackedPlayer(UUID profileId) {
        retireSkin(profileId);
        Registration registration = CURRENT.get();
        if (registration != null) registration.visibleSkins.keySet().removeIf(identity -> identity.profileId().equals(profileId));
        Events events = registration == null ? null : registration.events;
        if (events != null) events.untrackedPlayer(profileId);
    }

    public static void worldChanged() {
        retireSkins();
        invalidateVisibleSkins();
        Registration registration = CURRENT.get();
        Events events = registration == null ? null : registration.events;
        if (events != null) events.worldChanged();
    }

    public static void worldEntered() {
        retireSkins();
        invalidateVisibleSkins();
        Registration registration = CURRENT.get();
        Events events = registration == null ? null : registration.events;
        if (events != null) events.worldEntered();
    }

    static void invalidateVisibleSkins() {
        Registration registration = CURRENT.get();
        if (registration != null) registration.invalidateVisibleSkins();
    }

    public static void retireSkins() {
        Registration registration = CURRENT.get();
        if (registration == null) return;
        retireSources(registration, true, false);
    }

    public static void retirePreviewSkins() {
        Registration registration = CURRENT.get();
        if (registration == null) return;
        retireSources(registration, true, true);
    }

    private static void retireSources(Registration registration, boolean refresh, boolean previewOnly) {
        var sources = new java.util.LinkedHashMap<UUID, EffectiveSkinSource>();
        registration.skinSources.forEach((id, source) -> {
            if ((!previewOnly || source.preview()) && registration.skinSources.remove(id, source)) {
                registration.cpmIdentities.remove(id);
                registration.capeInputs.remove(id);
                sources.put(id, source);
            }
        });
        Throwable failure = null;
        for (var entry : sources.entrySet()) {
            try {
                try { entry.getValue().retire(); }
                finally { if (refresh) skinChanged.accept(new SkinChange(entry.getKey(), registration)); }
            } catch (RuntimeException | Error problem) {
                if (failure == null) failure = problem; else failure.addSuppressed(problem);
            }
        }
        if (failure instanceof RuntimeException problem) throw problem;
        if (failure instanceof Error problem) throw problem;
    }

    private static Snapshot snapshot() {
        Registration registration = CURRENT.get();
        return registration == null ? Snapshot.empty() : registration.snapshot;
    }

    public static final class Registration implements AutoCloseable {
        private final Events events;
        private final Map<Identity, String> visibleSkins = new ConcurrentHashMap<>();
        private final Map<UUID, EffectiveSkinSource> skinSources = new ConcurrentHashMap<>();
        private final Map<UUID, Identity> cpmIdentities = new ConcurrentHashMap<>();
        private final Map<UUID, CapeInputs> capeInputs = new ConcurrentHashMap<>();

        public Map<Identity, Candidate> cpmCandidates() {
            Map<Identity, Candidate> result = new java.util.HashMap<>();
            skinSources.forEach((id, source) -> {
                Identity identity = cpmIdentities.get(id);
                Candidate candidate = source.preview() ? null : cpmCandidate(source);
                if (identity != null && candidate != null) result.put(identity, candidate);
            });
            return Map.copyOf(result);
        }
        private volatile Snapshot snapshot = Snapshot.empty();
        private volatile boolean closed;

        private Registration(Events events) { this.events = events; }

        public boolean active() { return !closed && CURRENT.get() == this; }

        String visibleSkin(Identity identity) {
            return active() ? visibleSkins.get(identity) : null;
        }

        void retireVisibleSkin(Identity identity) {
            if (active()) visibleSkins.remove(identity);
        }

        public void publish(Snapshot next) {
            if (active()) snapshot = Objects.requireNonNull(next, "snapshot");
        }

        public void invalidateVisibleSkins() {
            if (active()) visibleSkins.clear();
        }

        @Override
        public void close() {
            closed = true;
            CURRENT.compareAndSet(this, null);
            visibleSkins.clear();
            cpmIdentities.clear();
            capeInputs.clear();
            snapshot = Snapshot.empty();
            retireSources(this, false, false);
        }
    }

    public static Result resolve(UUID profileId, String canonicalName, Candidate offline,
            Candidate minecraft, boolean self) {
        Result result = EffectiveCapeResolver.resolve(snapshot(), profileId, canonicalName, offline, minecraft, self);
        Registration registration = CURRENT.get();
        if (registration != null) {
            if (registration.capeInputs.containsKey(profileId) || registration.capeInputs.size() < 513)
                registration.capeInputs.put(profileId, new CapeInputs(canonicalName, offline, minecraft, self));
            registration.events.capeFrameDemand(profileId, canonicalName, result.provider());
        }
        return result;
    }

    public static Result material(UUID profileId, String canonicalName, boolean self) {
        Registration registration = CURRENT.get();
        CapeInputs inputs = registration == null ? null : registration.capeInputs.get(profileId);
        if (inputs != null && (!inputs.name().equals(canonicalName) || inputs.self() != self)) inputs = null;
        return EffectiveCapeResolver.resolve(registration == null ? Snapshot.empty() : registration.snapshot,
                profileId, canonicalName, inputs == null ? null : inputs.offline(),
                inputs == null ? null : inputs.minecraft(), self);
    }

    public static com.naocraftlab.skins.client.TextureUseValidity captureCapeTexture(
            UUID profileId, String canonicalName, boolean self, String installedLocation) {
        Registration registration = CURRENT.get();
        if (registration == null || !registration.active() || profileId == null
                || canonicalName == null || installedLocation == null)
            return com.naocraftlab.skins.client.TextureUseValidity.INVALID;
        Result selected = material(profileId, canonicalName, self);
        if (selected.provider() != BuiltinProvider.MINECRAFTCAPES || !selected.capeGlint()
                || !installedLocation.equals(selected.capeLocation()))
            return com.naocraftlab.skins.client.TextureUseValidity.INVALID;
        return registration.events.captureCapeTexture(profileId, installedLocation);
    }

    public static Optional<Result> resolveSelf(UUID profileId, String canonicalName) {
        Snapshot snapshot = snapshot();
        if (!new Identity(profileId, canonicalName).equals(snapshot.selfIdentity())) {
            return Optional.empty();
        }
        Result result = EffectiveCapeResolver.resolve(snapshot, profileId, canonicalName, null, null, true);
        Registration registration = CURRENT.get();
        if (registration != null) {
            registration.events.capeFrameDemand(profileId, canonicalName, result.provider());
        }
        return Optional.of(result);
    }

    public record Identity(UUID profileId, String canonicalName) {
        public Identity {
            Objects.requireNonNull(profileId, "profileId");
            Objects.requireNonNull(canonicalName, "canonicalName");
        }
    }

    public record Candidate(String capeLocation, String elytraLocation, boolean hasElytra,
            boolean capeGlint, boolean nativeCape, boolean nativeElytra,
            EffectiveSkinSource source, CpmAccessoryObservation observation) {
        public Candidate(String capeLocation, String elytraLocation, boolean hasElytra,
                boolean capeGlint, boolean nativeCape, boolean nativeElytra) {
            this(capeLocation, elytraLocation, hasElytra, capeGlint, nativeCape, nativeElytra, null, null);
        }

        public Candidate(String capeLocation, String elytraLocation, boolean hasElytra,
                boolean capeGlint) {
            this(capeLocation, elytraLocation, hasElytra, capeGlint, false, false);
        }

        public boolean present() {
            return (source == null || source.active() && source.accessories() == observation)
                    && (capeLocation != null || elytraLocation != null || nativeCape || nativeElytra);
        }

        public Candidate(String capeLocation, String elytraLocation, boolean hasElytra) {
            this(capeLocation, elytraLocation, hasElytra, false);
        }
    }

    public record Result(String capeLocation, String elytraLocation, boolean hasElytra,
            BuiltinProvider provider, boolean capeGlint, boolean nativeCape, boolean nativeElytra) {
        public Result(String capeLocation, String elytraLocation, boolean hasElytra,
                BuiltinProvider provider, boolean capeGlint) {
            this(capeLocation, elytraLocation, hasElytra, provider, capeGlint, false, false);
        }

        public Result(String capeLocation, String elytraLocation, boolean hasElytra,
                BuiltinProvider provider) {
            this(capeLocation, elytraLocation, hasElytra, provider, false);
        }
    }

    public record Snapshot(List<BuiltinProvider> order, Map<Identity, Candidate> optifine,
            Map<Identity, Candidate> skinmc,
            Map<Identity, Candidate> sneaky,
            Map<Identity, Candidate> minecraftcapes,
            Map<Identity, Candidate> cpm,
            Identity selfIdentity, Candidate selfOffline, Candidate selfMinecraft) {
        public Snapshot {
            order = List.copyOf(Objects.requireNonNull(order, "order"));
            optifine = Map.copyOf(Objects.requireNonNull(optifine, "optifine"));
            skinmc = Map.copyOf(Objects.requireNonNull(skinmc, "skinmc"));
            sneaky = Map.copyOf(Objects.requireNonNull(sneaky, "sneaky"));
            minecraftcapes = Map.copyOf(Objects.requireNonNull(minecraftcapes, "minecraftcapes"));
            cpm = Map.copyOf(Objects.requireNonNull(cpm, "cpm"));
        }

        public Snapshot(List<BuiltinProvider> order, Map<Identity, Candidate> optifine,
                Map<Identity, Candidate> skinmc, Map<Identity, Candidate> sneaky,
                Map<Identity, Candidate> minecraftcapes,
                Identity selfIdentity, Candidate selfOffline, Candidate selfMinecraft) {
            this(order, optifine, skinmc, sneaky, minecraftcapes, Map.of(), selfIdentity,
                    selfOffline, selfMinecraft);
        }

        public Snapshot(List<BuiltinProvider> order, Map<Identity, Candidate> optifine,
                Map<Identity, Candidate> skinmc, Map<Identity, Candidate> sneaky,
                Identity selfIdentity, Candidate selfOffline, Candidate selfMinecraft) {
            this(order, optifine, skinmc, sneaky, Map.of(), selfIdentity, selfOffline,
                    selfMinecraft);
        }

        public Snapshot(List<BuiltinProvider> order, Map<Identity, Candidate> optifine,
                Map<Identity, Candidate> skinmc,
                Identity selfIdentity, Candidate selfOffline, Candidate selfMinecraft) {
            this(order, optifine, skinmc, Map.of(), Map.of(), selfIdentity, selfOffline,
                    selfMinecraft);
        }

        public Snapshot(List<BuiltinProvider> order, Map<Identity, Candidate> optifine,
                Identity selfIdentity, Candidate selfOffline, Candidate selfMinecraft) {
            this(order, optifine, Map.of(), Map.of(), Map.of(), selfIdentity, selfOffline,
                    selfMinecraft);
        }

        public static Snapshot empty() {
            return new Snapshot(List.of(BuiltinProvider.OFFLINE, BuiltinProvider.MINECRAFT),
                    Map.of(), Map.of(), Map.of(), Map.of(), null, null, null);
        }
    }

    public interface Events {
        default com.naocraftlab.skins.client.TextureUseValidity captureCapeTexture(
                UUID profileId, String installedLocation) {
            return com.naocraftlab.skins.client.TextureUseValidity.INVALID;
        }
        default void cpmChanged() {}
        void trackedPlayer(UUID profileId, String canonicalName);
        void playerInfoUpdated(UUID profileId, String canonicalName);
        void untrackedPlayer(UUID profileId);
        void worldChanged();
        void worldEntered();
        default void skinTextureReady(String skinLocation, int[] argb) {}
        default void skinTextureReady(String skinLocation, int[] argb, long revision) {
            skinTextureReady(skinLocation, argb);
        }
        default boolean hasSkinTexture(String skinLocation) { return true; }
        default void visibleSkin(UUID profileId, String canonicalName, String skinLocation) {}
        default void capeFrameDemand(UUID profileId, String canonicalName, BuiltinProvider provider) {}
    }

    private CapeProjection() {}
}

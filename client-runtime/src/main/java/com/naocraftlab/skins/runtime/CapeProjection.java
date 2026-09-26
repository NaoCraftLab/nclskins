package com.naocraftlab.skins.runtime;

import com.naocraftlab.skins.core.provider.BuiltinProvider;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.ConcurrentHashMap;

public final class CapeProjection {
    private static final AtomicReference<Registration> CURRENT = new AtomicReference<>();
    private static final RetainedSkinPixels PIXELS = new RetainedSkinPixels();

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
        CURRENT.set(registration);
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
        Registration registration = CURRENT.get();
        if (registration != null) registration.visibleSkins.keySet().removeIf(identity -> identity.profileId().equals(profileId));
        Events events = registration == null ? null : registration.events;
        if (events != null) events.untrackedPlayer(profileId);
    }

    public static void worldChanged() {
        invalidateVisibleSkins();
        Registration registration = CURRENT.get();
        Events events = registration == null ? null : registration.events;
        if (events != null) events.worldChanged();
    }

    public static void worldEntered() {
        invalidateVisibleSkins();
        Registration registration = CURRENT.get();
        Events events = registration == null ? null : registration.events;
        if (events != null) events.worldEntered();
    }

    static void invalidateVisibleSkins() {
        Registration registration = CURRENT.get();
        if (registration != null) registration.invalidateVisibleSkins();
    }

    private static Snapshot snapshot() {
        Registration registration = CURRENT.get();
        return registration == null ? Snapshot.empty() : registration.snapshot;
    }

    public static final class Registration implements AutoCloseable {
        private final Events events;
        private final Map<Identity, String> visibleSkins = new ConcurrentHashMap<>();
        private volatile Snapshot snapshot = Snapshot.empty();
        private volatile boolean closed;

        private Registration(Events events) { this.events = events; }

        public boolean active() { return !closed && CURRENT.get() == this; }

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
            snapshot = Snapshot.empty();
        }
    }

    public static Result resolve(UUID profileId, String canonicalName, Candidate offline,
            Candidate minecraft, boolean self) {
        return EffectiveCapeResolver.resolve(snapshot(), profileId, canonicalName, offline, minecraft, self);
    }

    public static Optional<Result> resolveSelf(UUID profileId, String canonicalName) {
        Snapshot snapshot = snapshot();
        if (!new Identity(profileId, canonicalName).equals(snapshot.selfIdentity())) {
            return Optional.empty();
        }
        return Optional.of(EffectiveCapeResolver.resolve(snapshot, profileId, canonicalName, null, null, true));
    }

    public record Identity(UUID profileId, String canonicalName) {
        public Identity {
            Objects.requireNonNull(profileId, "profileId");
            Objects.requireNonNull(canonicalName, "canonicalName");
        }
    }

    public record Candidate(String capeLocation, String elytraLocation, boolean hasElytra) {}

    public record Result(String capeLocation, String elytraLocation, boolean hasElytra,
            BuiltinProvider provider) {}

    public record Snapshot(List<BuiltinProvider> order, Map<Identity, Candidate> optifine,
            Map<Identity, Candidate> skinmc,
            Map<Identity, Candidate> sneaky,
            Identity selfIdentity, Candidate selfOffline, Candidate selfMinecraft) {
        public Snapshot {
            order = List.copyOf(Objects.requireNonNull(order, "order"));
            optifine = Map.copyOf(Objects.requireNonNull(optifine, "optifine"));
            skinmc = Map.copyOf(Objects.requireNonNull(skinmc, "skinmc"));
            sneaky = Map.copyOf(Objects.requireNonNull(sneaky, "sneaky"));
        }

        public Snapshot(List<BuiltinProvider> order, Map<Identity, Candidate> optifine,
                Map<Identity, Candidate> skinmc,
                Identity selfIdentity, Candidate selfOffline, Candidate selfMinecraft) {
            this(order, optifine, skinmc, Map.of(), selfIdentity, selfOffline, selfMinecraft);
        }

        public Snapshot(List<BuiltinProvider> order, Map<Identity, Candidate> optifine,
                Identity selfIdentity, Candidate selfOffline, Candidate selfMinecraft) {
            this(order, optifine, Map.of(), Map.of(), selfIdentity, selfOffline, selfMinecraft);
        }

        public static Snapshot empty() {
            return new Snapshot(List.of(BuiltinProvider.OFFLINE, BuiltinProvider.MINECRAFT),
                    Map.of(), Map.of(), Map.of(), null, null, null);
        }
    }

    public interface Events {
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
    }

    private CapeProjection() {}
}

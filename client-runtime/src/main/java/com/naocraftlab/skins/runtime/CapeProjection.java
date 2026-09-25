package com.naocraftlab.skins.runtime;

import com.naocraftlab.skins.core.provider.BuiltinProvider;

import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.ConcurrentHashMap;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;

public final class CapeProjection {
    private static final AtomicReference<Snapshot> CURRENT = new AtomicReference<>(Snapshot.empty());
    private static final AtomicReference<Events> EVENTS = new AtomicReference<>();
    private static final Map<Identity, String> VISIBLE_SKINS = new ConcurrentHashMap<>();
    private static final ReferenceQueue<Object> NATIVE_SKIN_QUEUE = new ReferenceQueue<>();
    private static final Map<String, NativeSkin> NATIVE_SKINS = new HashMap<>();

    public static void skinTextureReady(String skinLocation, int[] argb) {
        skinTextureReady(skinLocation, argb, null);
    }

    public static void skinTextureReady(String skinLocation, int[] argb, Object nativeTexture) {
        if (nativeTexture != null && skinLocation != null && argb != null && argb.length == 64 * 64) {
            synchronized (NATIVE_SKINS) {
                drainNativeSkins();
                NATIVE_SKINS.put(skinLocation, new NativeSkin(nativeTexture, skinLocation, argb.clone()));
            }
        }
        Events events = EVENTS.get();
        if (events != null) events.skinTextureReady(skinLocation, argb.clone());
    }

    public static void visibleSkin(UUID profileId, String canonicalName, String skinLocation) {
        Identity identity = new Identity(profileId, canonicalName);
        Events events = EVENTS.get();
        if (skinLocation != null && events != null && !events.hasSkinTexture(skinLocation)) {
            int[] retained = null;
            synchronized (NATIVE_SKINS) {
                drainNativeSkins();
                NativeSkin nativeSkin = NATIVE_SKINS.get(skinLocation);
                if (nativeSkin != null) {
                    if (nativeSkin.get() == null) NATIVE_SKINS.remove(skinLocation, nativeSkin);
                    else retained = nativeSkin.argb().clone();
                }
            }
            if (retained != null) events.skinTextureReady(skinLocation, retained);
        }
        String previous = skinLocation == null ? VISIBLE_SKINS.remove(identity)
                : VISIBLE_SKINS.put(identity, skinLocation);
        if (Objects.equals(previous, skinLocation)) return;
        if (events != null) events.visibleSkin(profileId, canonicalName, skinLocation);
    }

    public static void installEvents(Events events) {
        VISIBLE_SKINS.clear();
        EVENTS.set(Objects.requireNonNull(events, "events"));
    }

    public static void clearEvents() {
        EVENTS.set(null);
    }

    public static void trackedPlayer(UUID profileId, String canonicalName) {
        Events events = EVENTS.get();
        if (events != null) events.trackedPlayer(profileId, canonicalName);
    }

    public static void playerInfoUpdated(UUID profileId, String canonicalName) {
        Events events = EVENTS.get();
        if (events != null) events.playerInfoUpdated(profileId, canonicalName);
    }

    public static void untrackedPlayer(UUID profileId) {
        VISIBLE_SKINS.keySet().removeIf(identity -> identity.profileId().equals(profileId));
        Events events = EVENTS.get();
        if (events != null) events.untrackedPlayer(profileId);
    }

    public static void worldChanged() {
        VISIBLE_SKINS.clear();
        Events events = EVENTS.get();
        if (events != null) events.worldChanged();
    }

    public static void worldEntered() {
        VISIBLE_SKINS.clear();
        Events events = EVENTS.get();
        if (events != null) events.worldEntered();
    }

    public static void invalidateVisibleSkins() {
        VISIBLE_SKINS.clear();
    }

    public static void publish(Snapshot snapshot) {
        CURRENT.set(Objects.requireNonNull(snapshot, "snapshot"));
    }

    public static void clear() {
        CURRENT.set(Snapshot.empty());
        VISIBLE_SKINS.clear();
    }

    public static Result resolve(UUID profileId, String canonicalName, Candidate offline,
            Candidate minecraft, boolean self) {
        return resolve(CURRENT.get(), profileId, canonicalName, offline, minecraft, self);
    }

    public static Optional<Result> resolveSelf(UUID profileId, String canonicalName) {
        Snapshot snapshot = CURRENT.get();
        if (!new Identity(profileId, canonicalName).equals(snapshot.selfIdentity())) {
            return Optional.empty();
        }
        return Optional.of(resolve(snapshot, profileId, canonicalName, null, null, true));
    }

    private static Result resolve(Snapshot snapshot, UUID profileId, String canonicalName,
            Candidate offline, Candidate minecraft, boolean self) {
        Identity identity = new Identity(profileId, canonicalName);
        Candidate optifine = snapshot.optifine().get(identity);
        Candidate skinmc = snapshot.skinmc().get(identity);
        Candidate sneaky = snapshot.sneaky().get(identity);
        if (self && identity.equals(snapshot.selfIdentity())) {
            offline = snapshot.selfOffline();
            minecraft = snapshot.selfMinecraft();
        }
        for (BuiltinProvider provider : snapshot.order()) {
            Candidate candidate = switch (provider) {
                case OFFLINE -> self ? offline : null;
                case MINECRAFT -> minecraft;
                case OPTIFINE -> optifine;
                case SKINMC -> skinmc;
                case SNEAKY -> sneaky;
            };
            if (candidate != null && candidate.capeLocation() != null) {
                return new Result(candidate.capeLocation(), candidate.elytraLocation(),
                        candidate.hasElytra(), provider);
            }
        }
        return new Result(null, null, false, null);
    }

    public record Identity(UUID profileId, String canonicalName) {
        public Identity {
            Objects.requireNonNull(profileId, "profileId");
            Objects.requireNonNull(canonicalName, "canonicalName");
        }
    }

    public record Candidate(String capeLocation, String elytraLocation, boolean hasElytra) {}

    private static final class NativeSkin extends WeakReference<Object> {
        private final String location;
        private final int[] argb;

        private NativeSkin(Object owner, String location, int[] argb) {
            super(owner, NATIVE_SKIN_QUEUE);
            this.location = location;
            this.argb = argb;
        }

        private int[] argb() { return argb; }
    }

    private static void drainNativeSkins() {
        NativeSkin expired;
        while ((expired = (NativeSkin) NATIVE_SKIN_QUEUE.poll()) != null) {
            NATIVE_SKINS.remove(expired.location, expired);
        }
    }

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
        default boolean hasSkinTexture(String skinLocation) { return true; }
        default void visibleSkin(UUID profileId, String canonicalName, String skinLocation) {}
    }

    private CapeProjection() {}
}

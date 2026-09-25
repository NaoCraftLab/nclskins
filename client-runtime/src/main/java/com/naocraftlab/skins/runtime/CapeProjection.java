package com.naocraftlab.skins.runtime;

import com.naocraftlab.skins.core.provider.BuiltinProvider;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

public final class CapeProjection {
    private static final AtomicReference<Snapshot> CURRENT = new AtomicReference<>(Snapshot.empty());
    private static final AtomicReference<Events> EVENTS = new AtomicReference<>();

    public static void installEvents(Events events) {
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
        Events events = EVENTS.get();
        if (events != null) events.untrackedPlayer(profileId);
    }

    public static void worldChanged() {
        Events events = EVENTS.get();
        if (events != null) events.worldChanged();
    }

    public static void worldEntered() {
        Events events = EVENTS.get();
        if (events != null) events.worldEntered();
    }

    public static void publish(Snapshot snapshot) {
        CURRENT.set(Objects.requireNonNull(snapshot, "snapshot"));
    }

    public static void clear() {
        CURRENT.set(Snapshot.empty());
    }

    public static Result resolve(UUID profileId, String canonicalName, Candidate offline,
            Candidate minecraft, boolean self) {
        Snapshot snapshot = CURRENT.get();
        Identity identity = new Identity(profileId, canonicalName);
        Candidate optifine = snapshot.optifine().get(identity);
        if (self && identity.equals(snapshot.selfIdentity())) {
            offline = snapshot.selfOffline();
            minecraft = snapshot.selfMinecraft();
        }
        for (BuiltinProvider provider : snapshot.order()) {
            Candidate candidate = switch (provider) {
                case OFFLINE -> self ? offline : null;
                case MINECRAFT -> minecraft;
                case OPTIFINE -> optifine;
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

    public record Result(String capeLocation, String elytraLocation, boolean hasElytra,
            BuiltinProvider provider) {}

    public record Snapshot(List<BuiltinProvider> order, Map<Identity, Candidate> optifine,
            Identity selfIdentity, Candidate selfOffline, Candidate selfMinecraft) {
        public Snapshot {
            order = List.copyOf(Objects.requireNonNull(order, "order"));
            optifine = Map.copyOf(Objects.requireNonNull(optifine, "optifine"));
        }

        public static Snapshot empty() {
            return new Snapshot(List.of(BuiltinProvider.OFFLINE, BuiltinProvider.MINECRAFT),
                    Map.of(), null, null, null);
        }
    }

    public interface Events {
        void trackedPlayer(UUID profileId, String canonicalName);
        void playerInfoUpdated(UUID profileId, String canonicalName);
        void untrackedPlayer(UUID profileId);
        void worldChanged();
        void worldEntered();
    }

    private CapeProjection() {}
}

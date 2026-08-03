package com.naocraftlab.skins.server.runtime;

import com.naocraftlab.skins.server.ConnectionKey;
import com.naocraftlab.skins.server.ConnectionSnapshot;
import com.naocraftlab.skins.server.IdentityAssurance;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;


public final class ConnectionGenerationRegistry<C> implements AutoCloseable {
    private final Map<C, Entry<C>> byConnection = new IdentityHashMap<>();
    private final Map<UUID, Entry<C>> byProfile = new HashMap<>();
    private long nextGeneration;
    private boolean closed;

    public Registration connected(
            C connection,
            UUID profileId,
            String profileName,
            IdentityAssurance assurance) {
        C checkedConnection = Objects.requireNonNull(connection, "connection");
        UUID checkedProfileId = Objects.requireNonNull(profileId, "profileId");
        String checkedProfileName = Objects.requireNonNull(profileName, "profileName");
        IdentityAssurance checkedAssurance = Objects.requireNonNull(assurance, "assurance");
        if (closed) {
            throw new IllegalStateException("Connection generation registry is closed");
        }

        Entry<C> existing = byConnection.get(checkedConnection);
        if (existing != null) {
            ConnectionSnapshot snapshot = existing.snapshot;
            if (!snapshot.key().profileId().equals(checkedProfileId)
                    || !snapshot.profileName().equals(checkedProfileName)
                    || byProfile.get(checkedProfileId) != existing) {
                throw new IllegalArgumentException(
                        "Native connection identity is already bound to another profile");
            }
            if (snapshot.assurance() == checkedAssurance) {
                return new Registration(snapshot, Optional.empty());
            }


        }
        if (nextGeneration == Long.MAX_VALUE) {
            throw new IllegalStateException("Connection generation space exhausted");
        }

        ConnectionSnapshot snapshot = new ConnectionSnapshot(
                new ConnectionKey(checkedProfileId, ++nextGeneration),
                checkedProfileName,
                checkedAssurance);
        Entry<C> created = new Entry<>(checkedConnection, snapshot);
        Entry<C> replaced = byProfile.put(checkedProfileId, created);
        if (replaced != null) {
            byConnection.remove(replaced.connection, replaced);
        }
        byConnection.put(checkedConnection, created);
        return new Registration(
                snapshot,
                replaced == null
                        ? Optional.empty()
                        : Optional.of(replaced.snapshot.key()));
    }

    public Optional<ConnectionSnapshot> snapshot(ConnectionKey key) {
        Objects.requireNonNull(key, "key");
        if (closed) {
            return Optional.empty();
        }
        Entry<C> entry = byProfile.get(key.profileId());
        return entry != null && entry.snapshot.key().equals(key)
                ? Optional.of(entry.snapshot)
                : Optional.empty();
    }

    public Optional<ConnectionKey> keyFor(C connection) {
        Objects.requireNonNull(connection, "connection");
        if (closed) {
            return Optional.empty();
        }
        Entry<C> entry = byConnection.get(connection);
        if (entry == null
                || byProfile.get(entry.snapshot.key().profileId()) != entry) {
            return Optional.empty();
        }
        return Optional.of(entry.snapshot.key());
    }

    public boolean matches(ConnectionKey key, C connection) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(connection, "connection");
        if (closed) {
            return false;
        }
        Entry<C> entry = byProfile.get(key.profileId());
        return entry != null
                && entry.snapshot.key().equals(key)
                && entry.connection == connection
                && byConnection.get(connection) == entry;
    }

    public Optional<ConnectionKey> disconnected(C connection) {
        Objects.requireNonNull(connection, "connection");
        if (closed) {
            return Optional.empty();
        }
        Entry<C> removed = byConnection.remove(connection);
        if (removed == null) {
            return Optional.empty();
        }
        byProfile.remove(removed.snapshot.key().profileId(), removed);
        return Optional.of(removed.snapshot.key());
    }

    @Override
    public void close() {
        closed = true;
        byConnection.clear();
        byProfile.clear();
    }

    private static final class Entry<C> {
        private final C connection;
        private final ConnectionSnapshot snapshot;

        private Entry(C connection, ConnectionSnapshot snapshot) {
            this.connection = connection;
            this.snapshot = snapshot;
        }
    }


    public record Registration(
            ConnectionSnapshot snapshot,
            Optional<ConnectionKey> superseded) {
        public Registration {
            Objects.requireNonNull(snapshot, "snapshot");
            superseded = Objects.requireNonNull(superseded, "superseded");
        }

        @Override
        public String toString() {
            return "Registration[connection=redacted, superseded="
                    + superseded.isPresent() + ']';
        }
    }
}

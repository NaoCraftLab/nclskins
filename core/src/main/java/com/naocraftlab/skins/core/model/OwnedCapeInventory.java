package com.naocraftlab.skins.core.model;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;


public record OwnedCapeInventory(
        int schemaVersion,
        UUID accountId,
        List<OwnedCapeEntry> capes,
        Instant verifiedAt) {
    public static final int CURRENT_SCHEMA_VERSION = 1;

    public OwnedCapeInventory {
        if (schemaVersion != CURRENT_SCHEMA_VERSION) {
            throw new IllegalArgumentException("unsupported owned cape schema: " + schemaVersion);
        }
        Objects.requireNonNull(accountId, "accountId");
        capes = List.copyOf(Objects.requireNonNull(capes, "capes"));
        Objects.requireNonNull(verifiedAt, "verifiedAt");
        Set<String> ids = new HashSet<>();
        if (capes.stream().anyMatch(cape -> !ids.add(Objects.requireNonNull(cape, "cape").id()))) {
            throw new IllegalArgumentException("duplicate owned cape id");
        }
    }

    public static OwnedCapeInventory empty(UUID accountId, Instant now) {
        return new OwnedCapeInventory(CURRENT_SCHEMA_VERSION, accountId, List.of(), now);
    }

    public Optional<OwnedCapeEntry> find(String capeId) {
        Objects.requireNonNull(capeId, "capeId");
        return capes.stream().filter(cape -> cape.id().equals(capeId)).findFirst();
    }
}

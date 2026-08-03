package com.naocraftlab.skins.core.storage;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

record IdentityRegistryState(
        int schemaVersion,
        Map<UUID, String> observedNames,
        Map<UUID, UUID> uuidAliases,
        Map<String, UUID> nameAliases,
        Set<UUID> verifiedAccounts,
        Instant updatedAt) {
    static final int CURRENT_SCHEMA_VERSION = 1;

    IdentityRegistryState {
        if (schemaVersion != CURRENT_SCHEMA_VERSION) {
            throw new IllegalArgumentException("unsupported identity registry schema: " + schemaVersion);
        }
        observedNames = Map.copyOf(Objects.requireNonNull(observedNames, "observedNames"));
        uuidAliases = Map.copyOf(Objects.requireNonNull(uuidAliases, "uuidAliases"));
        nameAliases = Map.copyOf(Objects.requireNonNull(nameAliases, "nameAliases"));
        verifiedAccounts = Set.copyOf(Objects.requireNonNull(verifiedAccounts, "verifiedAccounts"));
        Objects.requireNonNull(updatedAt, "updatedAt");
    }

    static IdentityRegistryState empty(Instant now) {
        return new IdentityRegistryState(
                CURRENT_SCHEMA_VERSION, Map.of(), Map.of(), Map.of(), Set.of(), now);
    }
}

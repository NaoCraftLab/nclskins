package com.naocraftlab.skins.runtime;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

final class CatalogEquivalenceIndex<T> {
    private final Map<PreparedCatalogSnapshot.VisualIdentity, List<T>> candidates;

    private CatalogEquivalenceIndex(Map<PreparedCatalogSnapshot.VisualIdentity, List<T>> candidates) {
        var copy = new LinkedHashMap<PreparedCatalogSnapshot.VisualIdentity, List<T>>();
        candidates.forEach((identity, values) -> copy.put(identity, List.copyOf(values)));
        this.candidates = Map.copyOf(copy);
    }

    static <T> CatalogEquivalenceIndex<T> build(List<T> entries,
            Function<T, PreparedCatalogSnapshot.VisualIdentity> identity) {
        var candidates = new LinkedHashMap<PreparedCatalogSnapshot.VisualIdentity, List<T>>();
        for (T entry : entries) candidates.computeIfAbsent(identity.apply(entry), ignored -> new ArrayList<>()).add(entry);
        return new CatalogEquivalenceIndex<>(candidates);
    }

    List<T> candidates(PreparedCatalogSnapshot.VisualIdentity identity) {
        return candidates.getOrDefault(identity, List.of());
    }

    Optional<T> first(PreparedCatalogSnapshot.VisualIdentity identity) {
        return candidates(identity).stream().findFirst();
    }
}

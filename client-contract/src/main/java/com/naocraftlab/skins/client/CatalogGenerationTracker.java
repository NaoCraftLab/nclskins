package com.naocraftlab.skins.client;

import java.util.List;
import java.util.Objects;

public final class CatalogGenerationTracker {
    private Object resourceManager;
    private List<Object> packs = List.of();
    private List<String> selectedPackIds = List.of();
    private long generation;

    public synchronized long observe(
            Object currentResourceManager,
            List<?> currentPacks,
            List<String> currentSelectedPackIds) {
        Objects.requireNonNull(currentResourceManager, "currentResourceManager");
        Objects.requireNonNull(currentPacks, "currentPacks");
        Objects.requireNonNull(currentSelectedPackIds, "currentSelectedPackIds");
        if (resourceManager != currentResourceManager
                || !sameIdentity(packs, currentPacks)
                || !selectedPackIds.equals(currentSelectedPackIds)) {
            resourceManager = currentResourceManager;
            packs = currentPacks.stream()
                    .map(pack -> Objects.requireNonNull(pack, "currentPacks contains null"))
                    .map(Object.class::cast)
                    .toList();
            selectedPackIds = List.copyOf(currentSelectedPackIds);
            generation = generation == Long.MAX_VALUE ? 1L : generation + 1L;
        }
        return generation;
    }

    private static boolean sameIdentity(List<Object> previous, List<?> current) {
        if (previous.size() != current.size()) {
            return false;
        }
        for (int index = 0; index < previous.size(); index++) {
            if (previous.get(index) != current.get(index)) {
                return false;
            }
        }
        return true;
    }
}

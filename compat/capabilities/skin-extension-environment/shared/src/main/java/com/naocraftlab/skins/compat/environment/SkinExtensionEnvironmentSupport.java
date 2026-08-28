package com.naocraftlab.skins.compat.environment;

import com.naocraftlab.skins.client.SkinCatalogSource;
import com.naocraftlab.skins.client.SkinExtensionEnvironmentSource;
import com.naocraftlab.skins.client.SkinExtensionResourceDetector;

import java.io.IOException;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;


public final class SkinExtensionEnvironmentSupport implements SkinExtensionEnvironmentSource {
    private final SkinCatalogSource resources;
    private final State ears;
    private final State entityModelFeatures;
    private final State entityTextureFeatures;
    private long observedResourceGeneration = Long.MIN_VALUE;
    private long environmentGeneration;
    private Snapshot snapshot;

    public SkinExtensionEnvironmentSupport(
            SkinCatalogSource resources,
            State ears,
            State entityModelFeatures,
            State entityTextureFeatures) {
        this.resources = Objects.requireNonNull(resources, "resources");
        this.ears = Objects.requireNonNull(ears, "ears");
        this.entityModelFeatures = Objects.requireNonNull(
                entityModelFeatures, "entityModelFeatures");
        this.entityTextureFeatures = Objects.requireNonNull(
                entityTextureFeatures, "entityTextureFeatures");
    }

    @Override
    public synchronized Snapshot snapshot() {
        final long resourceGeneration;
        try {
            resourceGeneration = resources.generation();
        } catch (RuntimeException unavailable) {
            return installUnavailableSnapshot();
        }
        if (snapshot != null && observedResourceGeneration == resourceGeneration) {
            return snapshot;
        }
        observedResourceGeneration = resourceGeneration;
        environmentGeneration = nextGeneration(environmentGeneration);
        if (resourceGeneration < 0) {
            return snapshot = snapshot(State.UNKNOWN, State.UNKNOWN);
        }
        try {
            SkinExtensionResourceDetector.Result result =
                    new SkinExtensionResourceDetector(resources).detect();
            State freshMoves = withModelRuntime(result.freshMoves());
            State justExpressions = withModelRuntime(result.justExpressions());
            return snapshot = snapshot(freshMoves, justExpressions);
        } catch (IOException | RuntimeException unavailable) {
            return snapshot = snapshot(State.UNKNOWN, State.UNKNOWN);
        }
    }

    private Snapshot installUnavailableSnapshot() {
        if (snapshot == null
                || snapshot.consumers().get(Consumer.FRESH_MOVES) != State.UNKNOWN
                || snapshot.consumers().get(Consumer.JUST_EXPRESSIONS) != State.UNKNOWN) {
            environmentGeneration = nextGeneration(environmentGeneration);
            snapshot = snapshot(State.UNKNOWN, State.UNKNOWN);
        }
        observedResourceGeneration = Long.MIN_VALUE;
        return snapshot;
    }

    private Snapshot snapshot(State freshMoves, State justExpressions) {
        EnumMap<Consumer, State> states = new EnumMap<>(Consumer.class);
        states.put(Consumer.EARS, ears);
        states.put(Consumer.FRESH_MOVES, freshMoves);
        states.put(Consumer.JUST_EXPRESSIONS, justExpressions);
        return new Snapshot(environmentGeneration, Map.copyOf(states));
    }

    private State withModelRuntime(boolean activeResourceProfile) {
        if (!activeResourceProfile) {
            return State.INACTIVE;
        }
        if (entityModelFeatures == State.INACTIVE
                || entityTextureFeatures == State.INACTIVE) {
            return State.MISSING_PREREQUISITE;
        }
        if (entityModelFeatures == State.UNKNOWN || entityTextureFeatures == State.UNKNOWN) {
            return State.UNKNOWN;
        }
        return State.ACTIVE;
    }

    private static long nextGeneration(long current) {
        return current == Long.MAX_VALUE ? 1L : current + 1L;
    }
}

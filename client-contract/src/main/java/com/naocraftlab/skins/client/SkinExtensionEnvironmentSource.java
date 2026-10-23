package com.naocraftlab.skins.client;

import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;


@FunctionalInterface
public interface SkinExtensionEnvironmentSource {
    Snapshot snapshot();

    default java.util.Optional<CpmModelFileConversion> modelFileConversion() { return java.util.Optional.empty(); }

    default Subscription subscribe(Runnable changed) { return () -> {}; }
    interface Subscription extends AutoCloseable { @Override void close(); }

    static SkinExtensionEnvironmentSource unknown() {
        return () -> Snapshot.unknown(0);
    }

    enum Consumer {
        EARS,
        FRESH_MOVES,
        JUST_EXPRESSIONS,
        CUSTOMIZABLE_PLAYER_MODELS
    }

    enum State {
        ACTIVE,
        INACTIVE,
        MISSING_PREREQUISITE,
        UNKNOWN
    }

    record Snapshot(long generation, Map<Consumer, State> consumers, boolean cpmPresent) {
        public Snapshot(long generation, Map<Consumer, State> consumers) {
            this(generation, consumers, false);
        }

        public Snapshot {
            if (generation < 0) {
                throw new IllegalArgumentException("generation must be non-negative");
            }
            Objects.requireNonNull(consumers, "consumers");
            EnumMap<Consumer, State> normalized = new EnumMap<>(Consumer.class);
            for (Consumer consumer : Consumer.values()) {
                normalized.put(consumer, Objects.requireNonNullElse(
                        consumers.get(consumer), State.UNKNOWN));
            }
            consumers = Map.copyOf(normalized);
        }

        public static Snapshot unknown(long generation) {
            return new Snapshot(generation, Map.of());
        }
    }
}

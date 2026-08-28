package com.naocraftlab.skins.core.compatibility;

import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;


public record SkinExtensionEnvironment(long generation, Map<SkinConsumer, SkinConsumerState> consumers) {
    public SkinExtensionEnvironment {
        if (generation < 0) {
            throw new IllegalArgumentException("generation must be non-negative");
        }
        Objects.requireNonNull(consumers, "consumers");
        EnumMap<SkinConsumer, SkinConsumerState> normalized = new EnumMap<>(SkinConsumer.class);
        for (SkinConsumer consumer : SkinConsumer.values()) {
            normalized.put(consumer, Objects.requireNonNullElse(
                    consumers.get(consumer), SkinConsumerState.UNKNOWN));
        }
        consumers = Map.copyOf(normalized);
    }

    public static SkinExtensionEnvironment unknown(long generation) {
        return new SkinExtensionEnvironment(generation, Map.of());
    }

    public SkinConsumerState state(SkinConsumer consumer) {
        return consumers.get(Objects.requireNonNull(consumer, "consumer"));
    }
}

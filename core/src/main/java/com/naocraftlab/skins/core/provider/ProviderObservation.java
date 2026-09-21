package com.naocraftlab.skins.core.provider;

import java.util.Optional;

public record ProviderObservation<T>(boolean known, T value) {
    public ProviderObservation {
        if (!known && value != null) {
            throw new IllegalArgumentException("Unknown observation cannot contain a value");
        }
    }

    public static <T> ProviderObservation<T> unknown() {
        return new ProviderObservation<>(false, null);
    }

    public static <T> ProviderObservation<T> observed(T value) {
        return new ProviderObservation<>(true, value);
    }

    public Optional<T> optionalValue() {
        return Optional.ofNullable(value);
    }
}

package com.naocraftlab.skins.client;

import java.util.Objects;
import java.util.Optional;


@FunctionalInterface
public interface SignedTextureVerifier {
    Optional<String> verify(String value, String signature);

    static SignedTextureVerifier rejecting() {
        return (value, signature) -> {
            Objects.requireNonNull(value, "value");
            Objects.requireNonNull(signature, "signature");
            return Optional.empty();
        };
    }
}

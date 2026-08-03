package com.naocraftlab.skins.server;

import java.util.Objects;


public final class SignedTexturesProperty {
    private final String value;
    private final String signature;

    public SignedTexturesProperty(String value, String signature) {
        this.value = Objects.requireNonNull(value, "value");
        this.signature = Objects.requireNonNull(signature, "signature");
        if (value.isBlank() || signature.isBlank()) {
            throw new IllegalArgumentException("Signed texture property must be complete");
        }
    }

    public String value() {
        return value;
    }

    public String signature() {
        return signature;
    }


    @Override
    public boolean equals(Object candidate) {
        return this == candidate;
    }


    @Override
    public int hashCode() {
        return System.identityHashCode(this);
    }

    @Override
    public String toString() {
        return "SignedTexturesProperty[redacted]";
    }
}

package com.naocraftlab.skins.compat.server;

import com.naocraftlab.skins.server.SignedTexturesProperty;
import java.util.Objects;
import java.util.Optional;


final class CurrentProfileTextures {
    enum Status {
        ACCOUNT_DEFAULT,
        SIGNED,
        INVALID
    }

    private final Status status;
    private final SignedTexturesProperty property;

    private CurrentProfileTextures(Status status, SignedTexturesProperty property) {
        this.status = Objects.requireNonNull(status, "status");
        this.property = property;
    }

    static CurrentProfileTextures accountDefault() {
        return new CurrentProfileTextures(Status.ACCOUNT_DEFAULT, null);
    }

    static CurrentProfileTextures signed(SignedTexturesProperty property) {
        return new CurrentProfileTextures(
                Status.SIGNED, Objects.requireNonNull(property, "property"));
    }

    static CurrentProfileTextures invalid() {
        return new CurrentProfileTextures(Status.INVALID, null);
    }

    Status status() {
        return status;
    }

    Optional<SignedTexturesProperty> property() {
        return Optional.ofNullable(property);
    }

    boolean containsSameProperty(SignedTexturesProperty candidate) {
        Objects.requireNonNull(candidate, "candidate");
        return status == Status.SIGNED
                && property.value().equals(candidate.value())
                && property.signature().equals(candidate.signature());
    }

    @Override
    public String toString() {
        return "CurrentProfileTextures[status=" + status + ']';
    }
}

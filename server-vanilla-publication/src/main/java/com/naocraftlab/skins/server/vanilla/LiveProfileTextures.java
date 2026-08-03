package com.naocraftlab.skins.server.vanilla;

import com.naocraftlab.skins.server.SignedTexturesProperty;
import java.util.Objects;
import java.util.Optional;


public final class LiveProfileTextures {
    public enum Status {
        ACCOUNT_DEFAULT,
        SIGNED,
        INVALID
    }

    private static final LiveProfileTextures ACCOUNT_DEFAULT =
            new LiveProfileTextures(Status.ACCOUNT_DEFAULT, null);
    private static final LiveProfileTextures INVALID =
            new LiveProfileTextures(Status.INVALID, null);

    private final Status status;
    private final SignedTexturesProperty property;

    private LiveProfileTextures(Status status, SignedTexturesProperty property) {
        this.status = Objects.requireNonNull(status, "status");
        this.property = property;
    }

    public static LiveProfileTextures accountDefault() {
        return ACCOUNT_DEFAULT;
    }

    public static LiveProfileTextures signed(SignedTexturesProperty property) {
        return new LiveProfileTextures(
                Status.SIGNED, Objects.requireNonNull(property, "property"));
    }

    public static LiveProfileTextures invalid() {
        return INVALID;
    }

    public Status status() {
        return status;
    }

    public Optional<SignedTexturesProperty> property() {
        return Optional.ofNullable(property);
    }

    public boolean containsSameProperty(SignedTexturesProperty candidate) {
        Objects.requireNonNull(candidate, "candidate");
        return status == Status.SIGNED
                && property.value().equals(candidate.value())
                && property.signature().equals(candidate.signature());
    }

    @Override
    public String toString() {
        return "LiveProfileTextures[status=" + status + ']';
    }
}

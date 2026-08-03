package com.naocraftlab.skins.server;

import java.util.Objects;


public record PublicationRequest(
        ConnectionKey connection,
        VerifiedOfficialProfile profile) {
    public PublicationRequest {
        Objects.requireNonNull(connection, "connection");
        Objects.requireNonNull(profile, "profile");
        if (!connection.profileId().equals(profile.identity().profileId())) {
            throw new IllegalArgumentException("Publication identity does not match connection");
        }
    }

    @Override
    public String toString() {
        return "PublicationRequest[redacted]";
    }
}

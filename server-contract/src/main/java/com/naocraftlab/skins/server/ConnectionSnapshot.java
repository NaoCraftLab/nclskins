package com.naocraftlab.skins.server;

import java.util.Objects;


public final class ConnectionSnapshot {
    private final ConnectionKey key;
    private final String profileName;
    private final IdentityAssurance assurance;

    public ConnectionSnapshot(
            ConnectionKey key,
            String profileName,
            IdentityAssurance assurance) {
        this.key = Objects.requireNonNull(key, "key");
        this.profileName = Objects.requireNonNull(profileName, "profileName");
        this.assurance = Objects.requireNonNull(assurance, "assurance");
        if (profileName.isBlank()) {
            throw new IllegalArgumentException("Profile name must not be blank");
        }
    }

    public ConnectionKey key() {
        return key;
    }

    public String profileName() {
        return profileName;
    }

    public IdentityAssurance assurance() {
        return assurance;
    }

    public ServerPlayerIdentity identity() {
        return new ServerPlayerIdentity(key.profileId(), profileName);
    }


    @Override
    public String toString() {
        return "ConnectionSnapshot[assurance=" + assurance + ']';
    }
}

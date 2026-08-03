package com.naocraftlab.skins.server;

import java.util.Objects;
import java.util.UUID;


public final class ServerPlayerIdentity {
    private final UUID profileId;
    private final String profileName;

    public ServerPlayerIdentity(UUID profileId, String profileName) {
        this.profileId = Objects.requireNonNull(profileId, "profileId");
        this.profileName = Objects.requireNonNull(profileName, "profileName");
        if (profileName.isBlank()) {
            throw new IllegalArgumentException("Profile name must not be blank");
        }
    }

    public UUID profileId() {
        return profileId;
    }

    public String profileName() {
        return profileName;
    }

    @Override
    public boolean equals(Object candidate) {
        if (this == candidate) {
            return true;
        }
        if (!(candidate instanceof ServerPlayerIdentity other)) {
            return false;
        }
        return profileId.equals(other.profileId) && profileName.equals(other.profileName);
    }

    @Override
    public int hashCode() {
        return Objects.hash(profileId, profileName);
    }


    @Override
    public String toString() {
        return "ServerPlayerIdentity[redacted]";
    }
}

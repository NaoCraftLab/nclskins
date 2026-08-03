package com.naocraftlab.skins.core.model;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;


public record RemoteProfile(
        UUID id,
        String name,
        List<RemoteSkin> skins,
        List<RemoteCape> capes,
        Set<String> profileActions) {

    public RemoteProfile {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(name, "name");
        name = name.trim();
        if (name.isEmpty() || name.length() > 64) {
            throw new IllegalArgumentException("name is invalid");
        }
        skins = List.copyOf(Objects.requireNonNull(skins, "skins"));
        capes = List.copyOf(Objects.requireNonNull(capes, "capes"));
        profileActions = Set.copyOf(Objects.requireNonNull(profileActions, "profileActions"));
    }

    public Optional<RemoteSkin> activeSkin() {
        return skins.stream().filter(skin -> skin.state() == RemoteAssetState.ACTIVE).findFirst();
    }

    public Optional<RemoteCape> activeCape() {
        return capes.stream().filter(cape -> cape.state() == RemoteAssetState.ACTIVE).findFirst();
    }

    public boolean ownsCape(String capeId) {
        Objects.requireNonNull(capeId, "capeId");
        return capes.stream().anyMatch(cape -> cape.id().equals(capeId));
    }
}

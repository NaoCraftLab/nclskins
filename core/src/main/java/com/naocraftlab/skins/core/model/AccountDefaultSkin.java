package com.naocraftlab.skins.core.model;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record AccountDefaultSkin(String skinId, SkinVariant variant) {
    private static final List<String> SKINS = List.of(
            "alex", "ari", "efe", "kai", "makena", "noor", "steve", "sunny", "zuri");

    public static AccountDefaultSkin forProfile(UUID profileId) {
        int index = Math.floorMod(Objects.requireNonNull(profileId, "profileId").hashCode(), 18);
        return new AccountDefaultSkin(SKINS.get(index % 9),
                index < 9 ? SkinVariant.SLIM : SkinVariant.CLASSIC);
    }
}

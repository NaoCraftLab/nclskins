package com.naocraftlab.skins.compat.client;

import java.util.UUID;
import net.minecraft.client.User;

public final class MinecraftUserProfileId {
    private MinecraftUserProfileId() {}

    public static UUID read(User user) {
        return user.getProfileId();
    }
}

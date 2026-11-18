package com.naocraftlab.skins.compat.client;

import java.util.UUID;
import net.minecraft.client.User;

public final class MinecraftUserProfileId {
    private MinecraftUserProfileId() {}

    public static UUID read(User user) {
        UUID canonical = canonical(user);
        return canonical != null ? canonical : user.getProfileId();
    }

    public static UUID canonical(User user) {
        if (user.getClass() != User.class) return null;
        String value = user.getUuid();
        if (value == null || value.length() != 32 && value.length() != 36) return null;
        boolean dashed = value.length() == 36;
        for (int i = 0; i < value.length(); i++) {
            char character = value.charAt(i);
            if (dashed && (i == 8 || i == 13 || i == 18 || i == 23)) {
                if (character != '-') return null;
            } else if (!(character >= '0' && character <= '9'
                    || character >= 'a' && character <= 'f'
                    || character >= 'A' && character <= 'F')) return null;
        }
        return dashed ? UUID.fromString(value) : new UUID(
                Long.parseUnsignedLong(value, 0, 16, 16), Long.parseUnsignedLong(value, 16, 32, 16));
    }
}

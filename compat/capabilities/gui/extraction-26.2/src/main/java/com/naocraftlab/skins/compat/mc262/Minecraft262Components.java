package com.naocraftlab.skins.compat.mc262;

import com.naocraftlab.skins.runtime.UiMessage;
import java.util.Objects;
import net.minecraft.network.chat.Component;


final class Minecraft262Components {
    private Minecraft262Components() {}

    static Component resolve(UiMessage message) {
        Objects.requireNonNull(message, "message");
        if (message.literal()) {
            return Component.literal(message.key());
        }
        Object[] arguments = message.arguments().stream()
                .map(Minecraft262Components::resolveArgument)
                .toArray();
        return Component.translatable(message.key(), arguments);
    }

    static String resolveString(UiMessage message) {
        return resolve(message).getString();
    }

    private static Object resolveArgument(Object argument) {
        return argument instanceof UiMessage nested ? resolve(nested) : argument;
    }
}

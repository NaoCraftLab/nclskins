package com.naocraftlab.skins.compat.mc12111;

import com.naocraftlab.skins.runtime.UiMessage;
import net.minecraft.network.chat.Component;


final class Minecraft12111Components {
    private Minecraft12111Components() {}

    static Component resolve(UiMessage message) {
        if (message.literal()) {
            return Component.literal(message.key());
        }
        return Component.translatable(
                message.key(),
                message.arguments().stream()
                        .map(value -> value instanceof UiMessage nested ? resolve(nested) : value)
                        .toArray());
    }

    static String resolveString(UiMessage message) {
        return resolve(message).getString();
    }
}

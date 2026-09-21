package com.naocraftlab.skins.compat.keybindings;

import com.mojang.blaze3d.platform.InputConstants;
import com.naocraftlab.skins.client.ScreenKeybinding;
import com.naocraftlab.skins.compat.loader.MinecraftClientHookAdapter;
import com.naocraftlab.skins.runtime.ScreenKeybindingDispatcher;
import java.util.Arrays;
import java.util.Collection;
import java.util.function.Function;
import java.util.function.Consumer;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;

public final class ScreenKeybindings {
    private static final ScreenKeyMapping[] MAPPINGS = Arrays.stream(ScreenKeybinding.values())
            .map(ScreenKeyMapping::new).toArray(ScreenKeyMapping[]::new);
    private static final ScreenKeybindingDispatcher DISPATCHER = new ScreenKeybindingDispatcher();
    private static Function<InputConstants.Key, Collection<KeyMapping>> matcher = key ->
            Arrays.stream(MAPPINGS).filter(mapping -> mapping.saveString().equals(key.getName()))
                    .map(mapping -> (KeyMapping) mapping).toList();
    private static ClientLevel pendingLevel;

    private ScreenKeybindings() {}

    public static void register(Consumer<KeyMapping> register) {
        for (KeyMapping mapping : MAPPINGS) register.accept(mapping);
    }

    public static void matcher(Function<InputConstants.Key, Collection<KeyMapping>> value) {
        matcher = value;
    }

    public static void press(long window, InputConstants.Key key, int action) {
        Minecraft client = Minecraft.getInstance();
        if (action != 1 || !ScreenKeyMapping.isCurrentWindow(client, window)
                || !MinecraftClientHookAdapter.instance().keybindingContextActive()) return;
        if (pendingLevel != client.level) DISPATCHER.drain(false);
        pendingLevel = client.level;
        Collection<KeyMapping> matches = matcher.apply(key);
        for (ScreenKeyMapping mapping : MAPPINGS) {
            if (!mapping.isUnbound() && matches.contains(mapping)) DISPATCHER.press(mapping.binding(), true);
        }
    }

    public static void tick(Minecraft client) {
        boolean eligible = pendingLevel == client.level
                && MinecraftClientHookAdapter.instance().keybindingContextActive();
        pendingLevel = null;
        for (KeyMapping mapping : MAPPINGS) while (mapping.consumeClick()) {}
        DISPATCHER.drain(eligible).ifPresent(MinecraftClientHookAdapter.instance()::openDestination);
    }
}

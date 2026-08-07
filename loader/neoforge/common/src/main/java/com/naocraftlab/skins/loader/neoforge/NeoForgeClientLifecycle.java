package com.naocraftlab.skins.loader.neoforge;

import com.naocraftlab.skins.compat.loader.MinecraftClientHookAdapter;
import java.nio.file.Path;
import java.util.Objects;
import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.GameShuttingDownEvent;


final class NeoForgeClientLifecycle {
    private NeoForgeClientLifecycle() {}

    static void install(MinecraftClientHookAdapter hooks, Path configurationDirectory) {
        MinecraftClientHookAdapter checked = Objects.requireNonNull(hooks, "hooks");
        checked.initialize(Objects.requireNonNull(configurationDirectory, "configurationDirectory"));
        NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post ignored) ->
                checked.tick(Minecraft.getInstance()));
        NeoForge.EVENT_BUS.addListener((GameShuttingDownEvent ignored) -> checked.close());
    }
}

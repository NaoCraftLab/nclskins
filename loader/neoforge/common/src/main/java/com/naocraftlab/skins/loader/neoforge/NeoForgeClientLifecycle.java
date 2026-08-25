package com.naocraftlab.skins.loader.neoforge;

import com.naocraftlab.skins.compat.config.MinecraftConfigurationBridge;
import com.naocraftlab.skins.compat.config.YaclConfigurationScreenFactory;
import com.naocraftlab.skins.compat.loader.MinecraftClientHookAdapter;
import java.nio.file.Path;
import java.util.Objects;
import net.minecraft.client.Minecraft;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.GameShuttingDownEvent;


final class NeoForgeClientLifecycle {
    private NeoForgeClientLifecycle() {}

    static void install(MinecraftClientHookAdapter hooks, Path configurationDirectory) {
        MinecraftClientHookAdapter checked = Objects.requireNonNull(hooks, "hooks");
        MinecraftConfigurationBridge.configureScreenFactory(
                ModList.get().isLoaded("yet_another_config_lib_v3")
                        ? YaclConfigurationScreenFactory::create
                        : null);
        checked.initialize(Objects.requireNonNull(configurationDirectory, "configurationDirectory"));
        NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post ignored) ->
                checked.tick(Minecraft.getInstance()));
        NeoForge.EVENT_BUS.addListener((GameShuttingDownEvent ignored) -> checked.close());
    }
}

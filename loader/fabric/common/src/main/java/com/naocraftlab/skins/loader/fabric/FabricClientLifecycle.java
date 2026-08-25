package com.naocraftlab.skins.loader.fabric;

import com.naocraftlab.skins.compat.config.MinecraftConfigurationBridge;
import com.naocraftlab.skins.compat.config.YaclConfigurationScreenFactory;
import com.naocraftlab.skins.compat.loader.MinecraftClientHookAdapter;
import java.nio.file.Path;
import java.util.Objects;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;


final class FabricClientLifecycle {
    private FabricClientLifecycle() {}

    static void install(MinecraftClientHookAdapter hooks, Path configurationDirectory) {
        MinecraftClientHookAdapter checked = Objects.requireNonNull(hooks, "hooks");
        MinecraftConfigurationBridge.configureScreenFactory(
                FabricLoader.getInstance().isModLoaded("yet_another_config_lib_v3")
                        ? YaclConfigurationScreenFactory::create
                        : null);
        checked.initialize(Objects.requireNonNull(configurationDirectory, "configurationDirectory"));
        ClientTickEvents.END_CLIENT_TICK.register(checked::tick);
        ClientLifecycleEvents.CLIENT_STOPPING.register(ignored -> checked.close());
    }
}

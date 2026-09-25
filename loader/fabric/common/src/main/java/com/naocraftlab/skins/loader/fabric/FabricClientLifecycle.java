package com.naocraftlab.skins.loader.fabric;

import com.naocraftlab.skins.client.ClientLifecycleGate;
import com.naocraftlab.skins.compat.config.MinecraftConfigurationBridge;
import com.naocraftlab.skins.compat.config.YaclConfigurationScreenFactory;
import com.naocraftlab.skins.compat.loader.MinecraftClientHookAdapter;
import com.naocraftlab.skins.runtime.CapeProjection;
import java.nio.file.Path;
import java.util.Objects;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;


final class FabricClientLifecycle {
    private FabricClientLifecycle() {}

    static void install(
            ClientLifecycleGate<MinecraftClientHookAdapter> lifecycle,
            MinecraftClientHookAdapter hooks,
            Path configurationDirectory) {
        ClientLifecycleGate<MinecraftClientHookAdapter> checkedLifecycle =
                Objects.requireNonNull(lifecycle, "lifecycle");
        MinecraftClientHookAdapter checked = Objects.requireNonNull(hooks, "hooks");
        MinecraftConfigurationBridge.configureScreenFactory(
                FabricLoader.getInstance().isModLoaded("yet_another_config_lib_v3")
                        ? YaclConfigurationScreenFactory::create
                        : null);
        Path checkedDirectory = Objects.requireNonNull(
                configurationDirectory, "configurationDirectory");
        if (!checkedLifecycle.install(checked, current -> current.initialize(checkedDirectory))) {
            return;
        }
        FabricScreenKeybindings.register();
        com.naocraftlab.skins.compat.fancymenu.FancyMenuIntegration.install(
                FabricLoader.getInstance().isModLoaded("fancymenu"), checked::openDestination, checked.diagnostics());

        ClientTickEvents.END_CLIENT_TICK.register(
                client -> checkedLifecycle.dispatch(current -> current.tick(client)));
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> CapeProjection.worldChanged());
        ClientLifecycleEvents.CLIENT_STOPPING.register(ignored -> checkedLifecycle.close());
    }
}

package com.naocraftlab.skins.loader.neoforge;

import com.naocraftlab.skins.client.ClientLifecycleGate;
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

    static void install(
            ClientLifecycleGate<MinecraftClientHookAdapter> lifecycle,
            MinecraftClientHookAdapter hooks,
            Path configurationDirectory) {
        ClientLifecycleGate<MinecraftClientHookAdapter> checkedLifecycle =
                Objects.requireNonNull(lifecycle, "lifecycle");
        MinecraftClientHookAdapter checked = Objects.requireNonNull(hooks, "hooks");
        MinecraftConfigurationBridge.configureScreenFactory(
                ModList.get().isLoaded("yet_another_config_lib_v3")
                        ? YaclConfigurationScreenFactory::create
                        : null);
        Path checkedDirectory = Objects.requireNonNull(
                configurationDirectory, "configurationDirectory");
        if (!checkedLifecycle.install(checked, current -> current.initialize(checkedDirectory))) {
            return;
        }
        com.naocraftlab.skins.compat.fancymenu.FancyMenuIntegration.install(
                ModList.get().isLoaded("fancymenu"), checked::openDestination, checked.diagnostics());

        NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post ignored) ->
                checkedLifecycle.dispatch(current -> current.tick(Minecraft.getInstance())));
        NeoForge.EVENT_BUS.addListener((GameShuttingDownEvent ignored) -> checkedLifecycle.close());
    }
}

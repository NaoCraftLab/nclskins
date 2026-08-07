package com.naocraftlab.skins.loader.fabric;

import com.naocraftlab.skins.compat.loader.MinecraftClientHookAdapter;
import java.nio.file.Path;
import java.util.Objects;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;


final class FabricClientLifecycle {
    private FabricClientLifecycle() {}

    static void install(MinecraftClientHookAdapter hooks, Path configurationDirectory) {
        MinecraftClientHookAdapter checked = Objects.requireNonNull(hooks, "hooks");
        checked.initialize(Objects.requireNonNull(configurationDirectory, "configurationDirectory"));
        ClientTickEvents.END_CLIENT_TICK.register(checked::tick);
        ClientLifecycleEvents.CLIENT_STOPPING.register(ignored -> checked.close());
    }
}

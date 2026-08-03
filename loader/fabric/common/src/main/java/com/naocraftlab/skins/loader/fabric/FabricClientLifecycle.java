package com.naocraftlab.skins.loader.fabric;

import com.naocraftlab.skins.compat.loader.MinecraftClientHookAdapter;
import java.util.Objects;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;


final class FabricClientLifecycle {
    private FabricClientLifecycle() {}

    static void install(MinecraftClientHookAdapter hooks) {
        MinecraftClientHookAdapter checked = Objects.requireNonNull(hooks, "hooks");
        checked.initialize();
        ClientTickEvents.END_CLIENT_TICK.register(checked::tick);
        ClientLifecycleEvents.CLIENT_STOPPING.register(ignored -> checked.close());
    }
}

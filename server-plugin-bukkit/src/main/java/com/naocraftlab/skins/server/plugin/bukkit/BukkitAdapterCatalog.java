package com.naocraftlab.skins.server.plugin.bukkit;

import com.naocraftlab.skins.server.plugin.adapter.legacy.authlib4.LegacyAuthlib4NativeAdapter;
import com.naocraftlab.skins.server.plugin.adapter.paper.authlib4.PaperAuthlib4NativeAdapter;
import com.naocraftlab.skins.server.plugin.adapter.paper.authlib6.PaperAuthlib6NativeAdapter;
import com.naocraftlab.skins.server.plugin.adapter.paper.authlib7.PaperAuthlib7NativeAdapter;
import com.naocraftlab.skins.server.plugin.adapter.paper.authlib9.PaperAuthlib9NativeAdapter;
import com.naocraftlab.skins.server.plugin.common.ExactAdapterSelector;
import com.naocraftlab.skins.server.plugin.common.ServerRuntimeIdentity;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;


final class BukkitAdapterCatalog {
    private BukkitAdapterCatalog() {
    }

    static ExactAdapterSelector<BukkitNativeAdapter> selector() {
        Map<ServerRuntimeIdentity, Supplier<? extends BukkitNativeAdapter>> registrations =
                new LinkedHashMap<>();
        register(registrations, "1.20.1", ServerRuntimeIdentity.Family.CRAFTBUKKIT,
                LegacyAuthlib4NativeAdapter::new);
        register(registrations, "1.20.1", ServerRuntimeIdentity.Family.SPIGOT,
                LegacyAuthlib4NativeAdapter::new);
        registerPaperFamily(registrations, "1.20.1", PaperAuthlib4NativeAdapter::new);
        registerPaperFamily(registrations, "1.21.1", PaperAuthlib6NativeAdapter::new);
        registerPaperFamily(registrations, "1.21.11", PaperAuthlib7NativeAdapter::new);
        register(registrations, "26.1.1", ServerRuntimeIdentity.Family.PAPER,
                PaperAuthlib7NativeAdapter::new);
        registerPaperFamily(registrations, "26.1.2", PaperAuthlib7NativeAdapter::new);
        registerPaperFamily(registrations, "26.2", PaperAuthlib9NativeAdapter::new);
        return new ExactAdapterSelector<>(registrations);
    }

    private static void registerPaperFamily(
            Map<ServerRuntimeIdentity, Supplier<? extends BukkitNativeAdapter>> registrations,
            String version,
            Function<ServerRuntimeIdentity, ? extends BukkitNativeAdapter> factory) {
        register(registrations, version, ServerRuntimeIdentity.Family.PAPER, factory);
        register(registrations, version, ServerRuntimeIdentity.Family.PURPUR, factory);
        if (!version.equals("1.21.1")) {
            register(registrations, version, ServerRuntimeIdentity.Family.FOLIA, factory);
        }
    }

    private static void register(
            Map<ServerRuntimeIdentity, Supplier<? extends BukkitNativeAdapter>> registrations,
            String version,
            ServerRuntimeIdentity.Family family,
            Function<ServerRuntimeIdentity, ? extends BukkitNativeAdapter> factory) {
        ServerRuntimeIdentity identity = new ServerRuntimeIdentity(
                version,
                family,
                family == ServerRuntimeIdentity.Family.FOLIA
                        ? ServerRuntimeIdentity.ThreadingModel.REGIONIZED
                        : ServerRuntimeIdentity.ThreadingModel.CLASSIC);
        registrations.put(identity, () -> factory.apply(identity));
    }
}

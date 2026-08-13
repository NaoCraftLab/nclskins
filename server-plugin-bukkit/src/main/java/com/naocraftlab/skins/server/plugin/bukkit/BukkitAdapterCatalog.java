package com.naocraftlab.skins.server.plugin.bukkit;

import com.naocraftlab.skins.server.plugin.adapter.legacy1201.Legacy1201NativeAdapter;
import com.naocraftlab.skins.server.plugin.adapter.paper1201.Paper1201NativeAdapter;
import com.naocraftlab.skins.server.plugin.adapter.paper1211.Paper1211NativeAdapter;
import com.naocraftlab.skins.server.plugin.adapter.paper12111.Paper12111NativeAdapter;
import com.naocraftlab.skins.server.plugin.adapter.paper261.Paper261NativeAdapter;
import com.naocraftlab.skins.server.plugin.adapter.paper262.Paper262NativeAdapter;
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
                Legacy1201NativeAdapter::new);
        register(registrations, "1.20.1", ServerRuntimeIdentity.Family.SPIGOT,
                Legacy1201NativeAdapter::new);
        registerPaperFamily(registrations, "1.20.1", Paper1201NativeAdapter::new);
        registerPaperFamily(registrations, "1.21.1", Paper1211NativeAdapter::new);
        registerPaperFamily(registrations, "1.21.11", Paper12111NativeAdapter::new);
        register(registrations, "26.1.1", ServerRuntimeIdentity.Family.PAPER,
                Paper261NativeAdapter::new);
        registerPaperFamily(registrations, "26.1.2", Paper261NativeAdapter::new);
        registerPaperFamily(registrations, "26.2", Paper262NativeAdapter::new);
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

package com.naocraftlab.skins.server.plugin.adapter.paper.authlib10;

import com.naocraftlab.skins.server.plugin.bukkit.AbstractBukkitNativeAdapter;
import com.naocraftlab.skins.server.plugin.bukkit.BukkitNativeAdapter;
import com.naocraftlab.skins.server.plugin.common.ServerRuntimeIdentity;

import java.util.logging.Logger;


public final class PaperAuthlib10NativeAdapter extends AbstractBukkitNativeAdapter {
    public PaperAuthlib10NativeAdapter(ServerRuntimeIdentity identity) {
        super("paper-authlib10", identity, "authlib-v10");
    }

    @Override
    protected BukkitNativeAdapter.AbiVerification verifyExactAbi(
            ClassLoader classLoader,
            String craftServerPackage,
            Class<?> serverPlayerClass,
            Logger logger) throws ReflectiveOperationException {
        return requireProfilePropertyApi(
                classLoader, craftServerPackage, "authlib-v10");
    }
}

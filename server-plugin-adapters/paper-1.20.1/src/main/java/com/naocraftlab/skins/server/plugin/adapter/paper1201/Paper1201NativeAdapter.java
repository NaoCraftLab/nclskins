package com.naocraftlab.skins.server.plugin.adapter.paper1201;

import com.naocraftlab.skins.server.plugin.bukkit.AbstractBukkitNativeAdapter;
import com.naocraftlab.skins.server.plugin.bukkit.BukkitNativeAdapter;
import com.naocraftlab.skins.server.plugin.common.ServerRuntimeIdentity;

import java.util.logging.Logger;


public final class Paper1201NativeAdapter extends AbstractBukkitNativeAdapter {
    public Paper1201NativeAdapter(ServerRuntimeIdentity identity) {
        super("paper-1.20.1", identity);
    }

    @Override
    protected boolean usesLegacyRuntimeMappings() {
        return true;
    }

    @Override
    protected BukkitNativeAdapter.AbiVerification verifyExactAbi(
            ClassLoader classLoader,
            Class<?> serverPlayerClass,
            Logger logger) throws ReflectiveOperationException {
        return requireProfilePropertyApi(classLoader, "authlib-v4");
    }
}

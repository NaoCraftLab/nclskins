package com.naocraftlab.skins.server.plugin.adapter.paper261;

import com.naocraftlab.skins.server.plugin.bukkit.AbstractBukkitNativeAdapter;
import com.naocraftlab.skins.server.plugin.bukkit.BukkitNativeAdapter;
import com.naocraftlab.skins.server.plugin.common.ServerRuntimeIdentity;

import java.util.logging.Logger;


public final class Paper261NativeAdapter extends AbstractBukkitNativeAdapter {
    public Paper261NativeAdapter(ServerRuntimeIdentity identity) {
        super("paper-26.1-family", identity);
    }

    @Override
    protected BukkitNativeAdapter.AbiVerification verifyExactAbi(
            ClassLoader classLoader,
            Class<?> serverPlayerClass,
            Logger logger) throws ReflectiveOperationException {
        return requireProfilePropertyApi(classLoader, "authlib-v7");
    }
}

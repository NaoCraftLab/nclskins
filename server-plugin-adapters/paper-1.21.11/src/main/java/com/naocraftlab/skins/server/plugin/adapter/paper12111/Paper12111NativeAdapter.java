package com.naocraftlab.skins.server.plugin.adapter.paper12111;

import com.naocraftlab.skins.server.plugin.bukkit.AbstractBukkitNativeAdapter;
import com.naocraftlab.skins.server.plugin.bukkit.BukkitNativeAdapter;
import com.naocraftlab.skins.server.plugin.common.ServerRuntimeIdentity;

import java.util.logging.Logger;


public final class Paper12111NativeAdapter extends AbstractBukkitNativeAdapter {
    public Paper12111NativeAdapter(ServerRuntimeIdentity identity) {
        super("paper-1.21.11", identity);
    }

    @Override
    protected BukkitNativeAdapter.AbiVerification verifyExactAbi(
            ClassLoader classLoader,
            Class<?> serverPlayerClass,
            Logger logger) throws ReflectiveOperationException {
        return requireProfilePropertyApi(classLoader, "authlib-v7");
    }
}

package com.naocraftlab.skins.server.plugin.adapter.paper262;

import com.naocraftlab.skins.server.plugin.bukkit.AbstractBukkitNativeAdapter;
import com.naocraftlab.skins.server.plugin.bukkit.BukkitNativeAdapter;
import com.naocraftlab.skins.server.plugin.common.ServerRuntimeIdentity;

import java.util.logging.Logger;


public final class Paper262NativeAdapter extends AbstractBukkitNativeAdapter {
    public Paper262NativeAdapter(ServerRuntimeIdentity identity) {
        super("paper-26.2", identity);
    }

    @Override
    protected BukkitNativeAdapter.AbiVerification verifyExactAbi(
            ClassLoader classLoader,
            Class<?> serverPlayerClass,
            Logger logger) throws ReflectiveOperationException {
        return requireProfilePropertyApi(classLoader, "authlib-v9");
    }
}

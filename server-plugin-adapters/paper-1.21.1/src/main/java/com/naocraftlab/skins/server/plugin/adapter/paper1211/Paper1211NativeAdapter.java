package com.naocraftlab.skins.server.plugin.adapter.paper1211;

import com.naocraftlab.skins.server.plugin.bukkit.AbstractBukkitNativeAdapter;
import com.naocraftlab.skins.server.plugin.bukkit.BukkitNativeAdapter;
import com.naocraftlab.skins.server.plugin.common.ServerRuntimeIdentity;

import java.util.logging.Logger;


public final class Paper1211NativeAdapter extends AbstractBukkitNativeAdapter {
    public Paper1211NativeAdapter(ServerRuntimeIdentity identity) {
        super("paper-1.21.1", identity);
    }

    @Override
    protected BukkitNativeAdapter.AbiVerification verifyExactAbi(
            ClassLoader classLoader,
            Class<?> serverPlayerClass,
            Logger logger) throws ReflectiveOperationException {
        return requireProfilePropertyApi(classLoader, "authlib-v6");
    }
}

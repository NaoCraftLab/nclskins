package com.naocraftlab.skins.server.plugin.adapter.paper.authlib7;

import com.naocraftlab.skins.server.plugin.bukkit.AbstractBukkitNativeAdapter;
import com.naocraftlab.skins.server.plugin.bukkit.BukkitNativeAdapter;
import com.naocraftlab.skins.server.plugin.common.ServerRuntimeIdentity;

import java.util.logging.Logger;


public final class PaperAuthlib7NativeAdapter extends AbstractBukkitNativeAdapter {
    public PaperAuthlib7NativeAdapter(ServerRuntimeIdentity identity) {
        super("paper-authlib7", identity, "authlib-v7");
    }

    @Override
    protected BukkitNativeAdapter.AbiVerification verifyExactAbi(
            ClassLoader classLoader,
            String craftServerPackage,
            Class<?> serverPlayerClass,
            Logger logger) throws ReflectiveOperationException {
        return requireProfilePropertyApi(
                classLoader, craftServerPackage, "authlib-v7");
    }
}

package com.naocraftlab.skins.server.plugin.adapter.paper.authlib9;

import com.naocraftlab.skins.server.plugin.bukkit.AbstractBukkitNativeAdapter;
import com.naocraftlab.skins.server.plugin.bukkit.BukkitNativeAdapter;
import com.naocraftlab.skins.server.plugin.common.ServerRuntimeIdentity;

import java.util.logging.Logger;


public final class PaperAuthlib9NativeAdapter extends AbstractBukkitNativeAdapter {
    public PaperAuthlib9NativeAdapter(ServerRuntimeIdentity identity) {
        super("paper-authlib9", identity);
    }

    @Override
    protected BukkitNativeAdapter.AbiVerification verifyExactAbi(
            ClassLoader classLoader,
            String craftServerPackage,
            Class<?> serverPlayerClass,
            Logger logger) throws ReflectiveOperationException {
        return requireProfilePropertyApi(
                classLoader, craftServerPackage, "authlib-v9");
    }
}

package com.naocraftlab.skins.server.plugin.adapter.paper.authlib4;

import com.naocraftlab.skins.server.plugin.bukkit.AbstractBukkitNativeAdapter;
import com.naocraftlab.skins.server.plugin.bukkit.BukkitNativeAdapter;
import com.naocraftlab.skins.server.plugin.common.ServerRuntimeIdentity;

import java.util.logging.Logger;


public final class PaperAuthlib4NativeAdapter extends AbstractBukkitNativeAdapter {
    public PaperAuthlib4NativeAdapter(ServerRuntimeIdentity identity) {
        super("paper-authlib4", identity);
    }

    @Override
    protected boolean usesLegacyRuntimeMappings() {
        return true;
    }

    @Override
    protected BukkitNativeAdapter.AbiVerification verifyExactAbi(
            ClassLoader classLoader,
            String craftServerPackage,
            Class<?> serverPlayerClass,
            Logger logger) throws ReflectiveOperationException {
        return requireProfilePropertyApi(
                classLoader, craftServerPackage, "authlib-v4");
    }
}

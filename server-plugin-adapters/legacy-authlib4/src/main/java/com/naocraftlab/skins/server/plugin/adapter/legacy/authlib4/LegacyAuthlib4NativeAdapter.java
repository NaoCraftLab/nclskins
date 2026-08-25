package com.naocraftlab.skins.server.plugin.adapter.legacy.authlib4;

import com.naocraftlab.skins.server.plugin.bukkit.AbstractBukkitNativeAdapter;
import com.naocraftlab.skins.server.plugin.bukkit.BukkitNativeAdapter;
import com.naocraftlab.skins.server.plugin.bukkit.ExactLegacyPublicationBackend;
import com.naocraftlab.skins.server.plugin.common.ServerRuntimeIdentity;

import java.util.logging.Logger;


public final class LegacyAuthlib4NativeAdapter extends AbstractBukkitNativeAdapter {
    public LegacyAuthlib4NativeAdapter(ServerRuntimeIdentity identity) {
        super("legacy-authlib4", identity, "authlib-v4");
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
        BukkitNativeAdapter.AbiVerification authlib = requireProfilePropertyApi(
                classLoader, craftServerPackage, "authlib-v4");
        return BukkitNativeAdapter.AbiVerification.compatible(
                authlib.diagnostic(), authlib.signatureVerifier(),
                ExactLegacyPublicationBackend.resolve(classLoader, craftServerPackage));
    }
}

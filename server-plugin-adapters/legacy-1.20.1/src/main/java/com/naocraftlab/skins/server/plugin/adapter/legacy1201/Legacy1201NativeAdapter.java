package com.naocraftlab.skins.server.plugin.adapter.legacy1201;

import com.naocraftlab.skins.server.plugin.bukkit.AbstractBukkitNativeAdapter;
import com.naocraftlab.skins.server.plugin.bukkit.BukkitNativeAdapter;
import com.naocraftlab.skins.server.plugin.common.ServerRuntimeIdentity;

import java.util.logging.Logger;


public final class Legacy1201NativeAdapter extends AbstractBukkitNativeAdapter {
    public Legacy1201NativeAdapter(ServerRuntimeIdentity identity) {
        super("legacy-1.20.1", identity);
    }

    @Override
    protected boolean usesLegacyRuntimeMappings() {
        return true;
    }

    @Override
    protected BukkitNativeAdapter.AbiVerification verifyProfileAbi(
            ClassLoader classLoader,
            Class<?> craftPlayerClass,
            Class<?> serverPlayerClass) throws ReflectiveOperationException {
        java.lang.reflect.Method profile = craftPlayerClass.getMethod("getProfile");
        return profile.getReturnType().getName().equals("com.mojang.authlib.GameProfile")
                ? BukkitNativeAdapter.AbiVerification.compatible(
                "legacy-1.20.1 CraftPlayer#getProfile")
                : BukkitNativeAdapter.AbiVerification.incompatible(
                "legacy-1.20.1 invalid CraftPlayer#getProfile descriptor");
    }

    @Override
    protected BukkitNativeAdapter.AbiVerification verifyExactAbi(
            ClassLoader classLoader,
            Class<?> serverPlayerClass,
            Logger logger) throws ReflectiveOperationException {
        return requireProfilePropertyApi(classLoader, "authlib-v4");
    }
}

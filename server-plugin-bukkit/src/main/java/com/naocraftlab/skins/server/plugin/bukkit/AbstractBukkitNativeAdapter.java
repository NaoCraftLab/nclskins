package com.naocraftlab.skins.server.plugin.bukkit;

import com.naocraftlab.skins.core.config.ServerConfiguration;
import com.naocraftlab.skins.server.plugin.common.ServerRuntimeIdentity;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.reflect.Method;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;


public abstract class AbstractBukkitNativeAdapter implements BukkitNativeAdapter {
    private final String id;
    private final ServerRuntimeIdentity identity;

    protected AbstractBukkitNativeAdapter(String id, ServerRuntimeIdentity identity) {
        this.id = Objects.requireNonNull(id, "id");
        this.identity = Objects.requireNonNull(identity, "identity");
    }

    @Override
    public final String id() {
        return id;
    }

    @Override
    public final ServerRuntimeIdentity identity() {
        return identity;
    }

    @Override
    public final AbiVerification verifyAbi(
            ClassLoader classLoader,
            String craftServerPackage,
            Logger logger) {
        Objects.requireNonNull(classLoader, "classLoader");
        Objects.requireNonNull(craftServerPackage, "craftServerPackage");
        Objects.requireNonNull(logger, "logger");
        try {
            String craftPlayerName = craftServerPackage + ".entity.CraftPlayer";
            Class<?> craftPlayer = Class.forName(craftPlayerName, false, classLoader);
            Method getHandle = craftPlayer.getMethod("getHandle");
            Class<?> serverPlayer = getHandle.getReturnType();
            AbiVerification profile = verifyProfileAbi(classLoader, craftPlayer, serverPlayer);
            if (!profile.compatible()) {
                return profile;
            }
            return verifyExactAbi(classLoader, serverPlayer, logger);
        } catch (ReflectiveOperationException | LinkageError failure) {
            logger.log(Level.FINE, id + " exact ABI probe failed", failure);
            return AbiVerification.incompatible(
                    id + " missing exact ABI leaf: " + failure.getClass().getSimpleName());
        }
    }

    protected abstract AbiVerification verifyExactAbi(
            ClassLoader classLoader,
            Class<?> serverPlayerClass,
            Logger logger) throws ReflectiveOperationException;

    protected AbiVerification verifyProfileAbi(
            ClassLoader classLoader,
            Class<?> craftPlayerClass,
            Class<?> serverPlayerClass) throws ReflectiveOperationException {
        if (usesLegacyRuntimeMappings()) {
            Method profile = craftPlayerClass.getMethod("getProfile");
            return profile.getReturnType().getName().equals("com.mojang.authlib.GameProfile")
                    ? AbiVerification.compatible(id + " CraftPlayer#getProfile")
                    : AbiVerification.incompatible(
                    id + " invalid CraftPlayer#getProfile descriptor");
        }
        Method gameProfile = serverPlayerClass.getMethod("getGameProfile");
        return gameProfile.getReturnType().getName().equals("com.mojang.authlib.GameProfile")
                ? AbiVerification.compatible(id + " live profile")
                : AbiVerification.incompatible(
                id + " getGameProfile return type is " + gameProfile.getReturnType().getName());
    }

    @Override
    public final BukkitRefreshEngine createEngine(
            JavaPlugin plugin,
            ServerConfiguration configuration,
            BukkitRefreshEngine.PublicationListener listener) {
        return new ReflectiveBukkitRefreshEngine(
                plugin, configuration, identity.threadingModel()
                == ServerRuntimeIdentity.ThreadingModel.REGIONIZED,
                usesLegacyRuntimeMappings(),
                listener);
    }

    protected boolean usesLegacyRuntimeMappings() {
        return false;
    }

    protected final AbiVerification requireProfilePropertyApi(
            ClassLoader classLoader,
            String expectedAuthlibFamily) throws ReflectiveOperationException {
        Class<?> property = Class.forName(
                "com.mojang.authlib.properties.Property", false, classLoader);
        boolean constructor = java.util.Arrays.stream(property.getConstructors())
                .anyMatch(candidate -> candidate.getParameterCount() == 3);
        boolean accessors = java.util.Arrays.stream(property.getMethods())
                .anyMatch(method -> method.getName().equals("hasSignature")
                        && method.getParameterCount() == 0);
        return constructor && accessors
                ? AbiVerification.compatible(id + " authlib=" + expectedAuthlibFamily)
                : AbiVerification.incompatible(id + " lacks signed textures property ABI");
    }
}

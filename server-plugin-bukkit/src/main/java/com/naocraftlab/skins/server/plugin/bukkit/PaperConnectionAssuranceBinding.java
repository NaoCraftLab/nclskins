package com.naocraftlab.skins.server.plugin.bukkit;

import org.bukkit.Bukkit;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

public final class PaperConnectionAssuranceBinding implements BukkitConnectionAssurance {
    private final Method configuration;
    private final Field proxies;
    private final Field velocity;
    private final Field enabled;
    private final Field onlineMode;

    private PaperConnectionAssuranceBinding(
            Method configuration,
            Field proxies,
            Field velocity,
            Field enabled,
            Field onlineMode) {
        this.configuration = configuration;
        this.proxies = proxies;
        this.velocity = velocity;
        this.enabled = enabled;
        this.onlineMode = onlineMode;
    }

    public static PaperConnectionAssuranceBinding resolve(ClassLoader classLoader)
            throws ReflectiveOperationException {
        Class<?> rootType = Class.forName(
                "io.papermc.paper.configuration.GlobalConfiguration", false, classLoader);
        Method configuration = rootType.getMethod("get");
        if (!Modifier.isStatic(configuration.getModifiers())
                || configuration.getReturnType() != rootType) {
            throw new NoSuchMethodException("GlobalConfiguration#get() exact descriptor");
        }
        Field proxies = exactPublicField(rootType, "proxies");
        Field velocity = exactPublicField(proxies.getType(), "velocity");
        Field enabled = exactBooleanField(velocity.getType(), "enabled");
        Field onlineMode = exactBooleanField(velocity.getType(), "onlineMode");
        return new PaperConnectionAssuranceBinding(
                configuration, proxies, velocity, enabled, onlineMode);
    }

    @Override
    public boolean assured(boolean trustedProxyForwarding) {
        if (Bukkit.getOnlineMode()) return true;
        return trustedProxyForwarding
                && (velocityModernEnabled() || LegacyConnectionAssurance.bungeeGuardEnabled());
    }

    private boolean velocityModernEnabled() {
        try {
            Object root = configuration.invoke(null);
            Object proxySettings = proxies.get(root);
            Object velocitySettings = velocity.get(proxySettings);
            return enabled.getBoolean(velocitySettings)
                    && onlineMode.getBoolean(velocitySettings);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException(
                    "Verified Paper connection-assurance binding failed", failure);
        }
    }

    private static Field exactPublicField(Class<?> owner, String name)
            throws NoSuchFieldException {
        return owner.getField(name);
    }

    private static Field exactBooleanField(Class<?> owner, String name)
            throws NoSuchFieldException {
        Field field = exactPublicField(owner, name);
        if (field.getType() != boolean.class) {
            throw new NoSuchFieldException(owner.getName() + '#' + name + " is not boolean");
        }
        return field;
    }
}

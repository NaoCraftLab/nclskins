package com.naocraftlab.skins.server.plugin.bukkit;

import com.naocraftlab.skins.server.plugin.common.BungeeGuardCompatibility;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Field;
import java.lang.reflect.Method;


final class ProxyConnectionAssurance {
    private ProxyConnectionAssurance() {
    }

    static boolean assured(boolean trustedProxyForwarding) {
        if (Bukkit.getOnlineMode()) {
            return true;
        }
        return trustedProxyForwarding && (velocityModernEnabled() || bungeeGuardEnabled());
    }

    @SuppressWarnings("deprecation")
    private static boolean bungeeGuardEnabled() {
        Plugin plugin = Bukkit.getPluginManager().getPlugin("BungeeGuard");
        if (plugin == null || !plugin.isEnabled()) {
            return false;
        }
        return BungeeGuardCompatibility.isSupportedVersion(
                plugin.getDescription().getVersion());
    }

    private static boolean velocityModernEnabled() {
        try {
            Class<?> configuration = Class.forName(
                    "io.papermc.paper.configuration.GlobalConfiguration",
                    false,
                    ProxyConnectionAssurance.class.getClassLoader());
            Method get = configuration.getMethod("get");
            Object root = get.invoke(null);
            Field proxies = configuration.getField("proxies");
            Object proxySettings = proxies.get(root);
            Field velocity = proxySettings.getClass().getField("velocity");
            Object velocitySettings = velocity.get(proxySettings);
            return velocitySettings.getClass().getField("enabled").getBoolean(velocitySettings)
                    && velocitySettings.getClass().getField("onlineMode")
                    .getBoolean(velocitySettings);
        } catch (ReflectiveOperationException | LinkageError unavailable) {
            return false;
        }
    }
}

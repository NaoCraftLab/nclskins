package com.naocraftlab.skins.server.plugin.bukkit;

import com.naocraftlab.skins.server.plugin.common.BungeeGuardCompatibility;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

final class LegacyConnectionAssurance implements BukkitConnectionAssurance {
    @Override
    public boolean assured(boolean trustedProxyForwarding) {
        return Bukkit.getOnlineMode()
                || trustedProxyForwarding && bungeeGuardEnabled();
    }

    @SuppressWarnings("deprecation")
    static boolean bungeeGuardEnabled() {
        Plugin plugin = Bukkit.getPluginManager().getPlugin("BungeeGuard");
        return plugin != null && plugin.isEnabled()
                && BungeeGuardCompatibility.isSupportedVersion(
                plugin.getDescription().getVersion());
    }
}

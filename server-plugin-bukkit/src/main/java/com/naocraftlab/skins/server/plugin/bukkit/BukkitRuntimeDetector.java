package com.naocraftlab.skins.server.plugin.bukkit;

import com.naocraftlab.skins.server.plugin.common.ServerRuntimeIdentity;
import org.bukkit.Bukkit;

import java.util.Locale;


final class BukkitRuntimeDetector {
    private BukkitRuntimeDetector() {
    }

    static Detection detect() {
        String name = Bukkit.getName();
        String normalized = name.toLowerCase(Locale.ROOT);
        ServerRuntimeIdentity.Family family;
        if (classPresent("io.papermc.paper.threadedregions.RegionizedServer")) {
            family = ServerRuntimeIdentity.Family.FOLIA;
        } else if (normalized.contains("purpur")) {
            family = ServerRuntimeIdentity.Family.PURPUR;
        } else if (classPresent("io.papermc.paper.configuration.GlobalConfiguration")
                || normalized.contains("paper")) {
            family = ServerRuntimeIdentity.Family.PAPER;
        } else if (normalized.contains("spigot")) {
            family = ServerRuntimeIdentity.Family.SPIGOT;
        } else if (normalized.contains("craftbukkit")) {
            family = ServerRuntimeIdentity.Family.CRAFTBUKKIT;
        } else {
            return Detection.unsupported("unknown Bukkit-family runtime " + name);
        }
        ServerRuntimeIdentity.ThreadingModel threading =
                family == ServerRuntimeIdentity.Family.FOLIA
                        ? ServerRuntimeIdentity.ThreadingModel.REGIONIZED
                        : ServerRuntimeIdentity.ThreadingModel.CLASSIC;
        String minecraftVersion = minecraftVersion(Bukkit.getBukkitVersion());
        try {
            return Detection.supported(new ServerRuntimeIdentity(
                    minecraftVersion, family, threading));
        } catch (IllegalArgumentException invalidVersion) {
            return Detection.unsupported("unsupported Minecraft version "
                    + minecraftVersion);
        }
    }

    static String minecraftVersion(String bukkitVersion) {
        int qualifier = bukkitVersion.indexOf('-');
        int paperBuild = bukkitVersion.indexOf(".build.");
        int end = bukkitVersion.length();
        if (qualifier >= 0) {
            end = Math.min(end, qualifier);
        }
        if (paperBuild >= 0) {
            end = Math.min(end, paperBuild);
        }
        return bukkitVersion.substring(0, end);
    }

    private static boolean classPresent(String name) {
        try {
            Class.forName(name, false, BukkitRuntimeDetector.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException | LinkageError absent) {
            return false;
        }
    }

    record Detection(ServerRuntimeIdentity identity, String diagnostic) {
        static Detection supported(ServerRuntimeIdentity identity) {
            return new Detection(identity, "");
        }

        static Detection unsupported(String diagnostic) {
            return new Detection(null, diagnostic);
        }

        boolean supported() {
            return identity != null;
        }
    }
}

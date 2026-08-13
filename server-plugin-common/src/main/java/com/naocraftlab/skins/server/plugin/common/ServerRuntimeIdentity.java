package com.naocraftlab.skins.server.plugin.common;

import java.util.Locale;
import java.util.Objects;


public record ServerRuntimeIdentity(
        String minecraftVersion,
        Family family,
        ThreadingModel threadingModel) {
    public ServerRuntimeIdentity {
        if (!Objects.requireNonNull(minecraftVersion, "minecraftVersion")
                .matches("(?:1\\.[0-9]+\\.[0-9]+|[0-9]+\\.[0-9]+(?:\\.[0-9]+)?)")) {
            throw new IllegalArgumentException("Invalid exact Minecraft version");
        }
        Objects.requireNonNull(family, "family");
        Objects.requireNonNull(threadingModel, "threadingModel");
        if ((family == Family.FOLIA) != (threadingModel == ThreadingModel.REGIONIZED)) {
            throw new IllegalArgumentException("Only Folia uses the regionized threading model");
        }
    }

    public enum Family {
        CRAFTBUKKIT,
        SPIGOT,
        PAPER,
        PURPUR,
        FOLIA;

        public String id() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    public enum ThreadingModel {
        CLASSIC,
        REGIONIZED
    }
}

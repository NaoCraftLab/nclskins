package com.naocraftlab.skins.compat.server;

import com.naocraftlab.skins.server.IdentityAssurance;
import java.util.Objects;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;


@FunctionalInterface
public interface MinecraftServerIdentityAttestor {
    IdentityAssurance attest(MinecraftServer server, ServerPlayer player);

    static MinecraftServerIdentityAttestor authenticatedOnly() {
        return (server, player) -> {
            Objects.requireNonNull(player, "player");
            return Objects.requireNonNull(server, "server").usesAuthentication()
                    ? IdentityAssurance.ONLINE
                    : IdentityAssurance.OFFLINE;
        };
    }
}

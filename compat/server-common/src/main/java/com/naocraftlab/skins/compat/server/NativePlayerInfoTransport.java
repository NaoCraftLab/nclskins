package com.naocraftlab.skins.compat.server;

import java.util.List;
import net.minecraft.server.level.ServerPlayer;


interface NativePlayerInfoTransport {
    void removeProfiles(ServerPlayer recipient, List<ServerPlayer> actors);

    void initializeProfiles(ServerPlayer recipient, List<ServerPlayer> actors);
}

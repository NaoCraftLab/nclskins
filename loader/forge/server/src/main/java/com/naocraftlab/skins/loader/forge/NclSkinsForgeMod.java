package com.naocraftlab.skins.loader.forge;

import com.naocraftlab.skins.compat.server.MinecraftServerLifecycle;
import com.naocraftlab.skins.compat.server.MinecraftAppearanceRefreshNetwork;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLPaths;


@Mod(NclSkinsForgeMod.MOD_ID)
public final class NclSkinsForgeMod {
    public static final String MOD_ID = "nclskins";

    public NclSkinsForgeMod() {
        MinecraftAppearanceRefreshNetwork.install();
        MinecraftForge.EVENT_BUS.addListener(NclSkinsForgeMod::serverStarting);
        MinecraftForge.EVENT_BUS.addListener(NclSkinsForgeMod::serverStopped);
        MinecraftForge.EVENT_BUS.addListener(NclSkinsForgeMod::playerLoggedIn);
        MinecraftForge.EVENT_BUS.addListener(NclSkinsForgeMod::playerLoggedOut);

    }
    private static void serverStarting(ServerStartingEvent event) {
        MinecraftServerLifecycle.started(event.getServer(), FMLPaths.CONFIGDIR.get());
    }

    private static void serverStopped(ServerStoppedEvent event) {
        MinecraftServerLifecycle.stopped(event.getServer());
    }

    private static void playerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            MinecraftServerLifecycle.connected(player.getServer(), player);
        }
    }

    private static void playerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            MinecraftServerLifecycle.disconnected(player.getServer(), player);
        }
    }
}

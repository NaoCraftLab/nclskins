package com.naocraftlab.skins.server.plugin.bungee;

import com.naocraftlab.skins.server.plugin.common.BungeeGuardCompatibility;
import com.naocraftlab.skins.server.plugin.common.PluginChannels;
import com.naocraftlab.skins.server.plugin.common.ProxyRefreshProtocol;
import com.naocraftlab.skins.server.plugin.common.RelayRevisionFence;
import com.naocraftlab.skins.server.plugin.common.SemanticVersion;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.connection.Server;
import net.md_5.bungee.api.event.PlayerDisconnectEvent;
import net.md_5.bungee.api.event.PluginMessageEvent;
import net.md_5.bungee.api.event.ServerConnectedEvent;
import net.md_5.bungee.api.plugin.Listener;
import net.md_5.bungee.api.plugin.Plugin;
import net.md_5.bungee.event.EventHandler;

import java.security.SecureRandom;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;


public final class NclSkinsBungeePlugin extends Plugin implements Listener {
    private final SecureRandom random = new SecureRandom();
    private final ProxyRefreshProtocol protocol = new ProxyRefreshProtocol();
    private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();
    private SemanticVersion version;
    private boolean active;

    @Override
    public void onEnable() {
        if (getProxy().getName().toLowerCase(Locale.ROOT).contains("waterfall")) {
            getLogger().severe("NCL Skins Plugin does not support EOL Waterfall; relay disabled");
            return;
        }
        Plugin bungeeGuard = getProxy().getPluginManager().getPlugin("BungeeGuard");
        if (!supportedBungeeGuard(bungeeGuard)) {
            getLogger().severe("NCL Skins Plugin requires BungeeGuard 1.4.0+; relay disabled");
            return;
        }
        String implementation = getClass().getPackage().getImplementationVersion();
        version = SemanticVersion.parse(implementation == null
                ? getDescription().getVersion() : implementation);
        getProxy().registerChannel(PluginChannels.PROXY_REFRESH);
        getProxy().getPluginManager().registerListener(this, this);
        active = true;
        getLogger().info("NCL_SKINS_PROXY_READY platform=bungeecord protocol=proxy-refresh-v1");
    }

    @Override
    public void onDisable() {
        if (active) {
            getProxy().unregisterChannel(PluginChannels.PROXY_REFRESH);
        }
        sessions.values().forEach(session -> session.fence.clear());
        sessions.clear();
        active = false;
    }

    @EventHandler
    public void onPluginMessage(PluginMessageEvent event) {
        if (!PluginChannels.PROXY_REFRESH.equals(event.getTag())) {
            return;
        }
        event.setCancelled(true);
        if (!(event.getSender() instanceof Server)
                || !(event.getReceiver() instanceof ProxiedPlayer player)) {
            return;
        }
        final ProxyRefreshProtocol.Message message;
        try {
            message = protocol.decode(event.getData());
        } catch (ProxyRefreshProtocol.ProtocolException malformed) {
            getLogger().warning("Rejected malformed bounded NCL proxy relay payload");
            return;
        }
        Session session = sessions.get(player.getUniqueId());
        if (session == null) {
            return;
        }
        if (message instanceof ProxyRefreshProtocol.Dirty dirty
                && session.fence.acceptDirty(dirty.nonce(), dirty.revision())) {
            session.latestRevision = dirty.revision();
        } else if (message instanceof ProxyRefreshProtocol.State state
                && session.fence.acceptState(state.nonce(), state.revision())) {
            session.latestRevision = state.revision();
        }
    }

    @EventHandler
    public void onServerConnected(ServerConnectedEvent event) {
        ProxiedPlayer player = event.getPlayer();
        Session session = sessions.computeIfAbsent(player.getUniqueId(), ignored -> new Session());
        byte[] nonce = new byte[ProxyRefreshProtocol.NONCE_BYTES];
        random.nextBytes(nonce);
        session.fence.bind(nonce);
        event.getServer().sendData(
                PluginChannels.PROXY_REFRESH,
                protocol.encode(new ProxyRefreshProtocol.Bind(
                        nonce, List.of("proxy-refresh-v1"), version)));
        if (session.latestRevision > 0
                && session.fence.acceptDirty(nonce, session.latestRevision)) {
            event.getServer().sendData(
                    PluginChannels.PROXY_REFRESH,
                    protocol.encode(new ProxyRefreshProtocol.Refresh(
                            nonce, session.latestRevision)));
        }
    }

    @EventHandler
    public void onDisconnect(PlayerDisconnectEvent event) {
        Session session = sessions.remove(event.getPlayer().getUniqueId());
        if (session != null) {
            session.fence.clear();
        }
    }

    private static boolean supportedBungeeGuard(Plugin plugin) {
        if (plugin == null) {
            return false;
        }
        return BungeeGuardCompatibility.isSupportedVersion(
                plugin.getDescription().getVersion());
    }

    private static final class Session {
        private final RelayRevisionFence fence = new RelayRevisionFence();
        private long latestRevision;
    }
}

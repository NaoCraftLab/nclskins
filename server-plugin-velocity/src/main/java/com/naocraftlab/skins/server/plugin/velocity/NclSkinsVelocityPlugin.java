package com.naocraftlab.skins.server.plugin.velocity;

import com.google.inject.Inject;
import com.naocraftlab.skins.diagnostics.DiagnosticDetails;
import com.naocraftlab.skins.diagnostics.DiagnosticEvent;
import com.naocraftlab.skins.diagnostics.Slf4jDiagnosticSink;
import com.naocraftlab.skins.server.SignedTexturesProperty;
import com.naocraftlab.skins.server.plugin.common.PluginChannels;
import com.naocraftlab.skins.server.plugin.common.ProxyRefreshProtocol;
import com.naocraftlab.skins.server.plugin.common.RelayRevisionFence;
import com.naocraftlab.skins.server.plugin.common.SemanticVersion;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.event.player.ServerPostConnectEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import com.velocitypowered.api.util.GameProfile;
import org.slf4j.Logger;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;


@Plugin(
        id = "nclskins-plugin",
        name = "NCL Skins Plugin",
        authors = {"NaoCraftLab"},
        url = "https://naocraftlab.com/skins")
public final class NclSkinsVelocityPlugin {
    private static final MinecraftChannelIdentifier CHANNEL =
            MinecraftChannelIdentifier.from(PluginChannels.PROXY_REFRESH);
    private final ProxyServer proxy;
    private final Logger logger;
    private final SecureRandom random = new SecureRandom();
    private final ProxyRefreshProtocol protocol = new ProxyRefreshProtocol();
    private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();
    private final SemanticVersion version;
    private final Slf4jDiagnosticSink diagnostics;

    @Inject
    public NclSkinsVelocityPlugin(ProxyServer proxy, Logger logger) {
        this.proxy = proxy;
        this.logger = logger;
        this.diagnostics = new Slf4jDiagnosticSink(logger);
        String implementation = getClass().getPackage()
                .getImplementationVersion();
        this.version = SemanticVersion.parse(implementation == null
                ? "1.0.0-alpha.1" : implementation);
    }

    @Subscribe
    public void onInitialize(ProxyInitializeEvent event) {
        proxy.getChannelRegistrar().register(CHANNEL);
        diagnostics.report(DiagnosticEvent.PROXY_READY, DiagnosticDetails::none);
    }

    @Subscribe
    public void onPluginMessage(PluginMessageEvent event) {
        if (!CHANNEL.equals(event.getIdentifier())) {
            return;
        }
        event.setResult(PluginMessageEvent.ForwardResult.handled());
        if (event.getSource() instanceof Player) {
            return;
        }
        if (!(event.getSource() instanceof ServerConnection connection)) {
            return;
        }
        Player player = connection.getPlayer();
        final ProxyRefreshProtocol.Message message;
        try {
            message = protocol.decode(event.getData());
        } catch (ProxyRefreshProtocol.ProtocolException malformed) {
            diagnostics.report(
                    DiagnosticEvent.RELAY_MALFORMED,
                    () -> DiagnosticDetails.failure(malformed));
            return;
        }
        Session session = sessions.get(player.getUniqueId());
        if (session == null) {
            return;
        }
        if (message instanceof ProxyRefreshProtocol.Dirty dirty) {
            session.fence.acceptDirty(dirty.nonce(), dirty.revision());
        } else if (message instanceof ProxyRefreshProtocol.State state
                && session.fence.acceptState(state.nonce(), state.revision())) {
            replaceTextures(player, state.signedTextures().orElse(null));
        }
    }

    @Subscribe
    public void onServerConnected(ServerPostConnectEvent event) {
        Player player = event.getPlayer();
        byte[] nonce = new byte[ProxyRefreshProtocol.NONCE_BYTES];
        random.nextBytes(nonce);
        Session session = sessions.computeIfAbsent(player.getUniqueId(), ignored -> new Session());
        session.fence.bind(nonce);
        player.getCurrentServer().ifPresent(connection -> connection.sendPluginMessage(
                CHANNEL,
                protocol.encode(new ProxyRefreshProtocol.Bind(
                        nonce, List.of("proxy-refresh-v1"), version))));
    }

    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        Session session = sessions.remove(event.getPlayer().getUniqueId());
        if (session != null) {
            session.fence.clear();
        }
    }

    @Subscribe
    public void onShutdown(ProxyShutdownEvent event) {
        proxy.getChannelRegistrar().unregister(CHANNEL);
        sessions.values().forEach(session -> session.fence.clear());
        sessions.clear();
        diagnostics.close();
    }

    private static void replaceTextures(Player player, SignedTexturesProperty textures) {
        List<GameProfile.Property> properties = new ArrayList<>();
        for (GameProfile.Property property : player.getGameProfileProperties()) {
            if (!property.getName().equals("textures")) {
                properties.add(property);
            }
        }
        if (textures != null) {
            properties.add(new GameProfile.Property(
                    "textures", textures.value(), textures.signature()));
        }
        player.setGameProfileProperties(List.copyOf(properties));
    }

    private static final class Session {
        private final RelayRevisionFence fence = new RelayRevisionFence();
    }
}

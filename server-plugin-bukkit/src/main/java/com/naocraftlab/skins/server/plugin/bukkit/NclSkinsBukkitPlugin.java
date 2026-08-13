package com.naocraftlab.skins.server.plugin.bukkit;

import com.naocraftlab.skins.core.config.ConfigurationException;
import com.naocraftlab.skins.core.config.ServerConfiguration;
import com.naocraftlab.skins.core.config.ServerConfigurationRepository;
import com.naocraftlab.skins.server.Admission;
import com.naocraftlab.skins.server.RefreshSubmission;
import com.naocraftlab.skins.server.VerifiedOfficialProfile;
import com.naocraftlab.skins.server.plugin.common.ExactAdapterSelector;
import com.naocraftlab.skins.server.plugin.common.PluginChannels;
import com.naocraftlab.skins.server.plugin.common.ProxyRefreshProtocol;
import com.naocraftlab.skins.server.plugin.common.SemanticVersion;
import com.naocraftlab.skins.server.plugin.common.ServerCapabilityProtocol;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.messaging.PluginMessageListener;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;


public final class NclSkinsBukkitPlugin extends JavaPlugin
        implements PluginMessageListener, Listener {
    private static final String INTERNAL_REFRESH = "_refresh_official_profile_v1";
    private final ServerCapabilityProtocol capabilities = new ServerCapabilityProtocol();
    private final ProxyRefreshProtocol relay = new ProxyRefreshProtocol();
    private final Map<UUID, ProxyRefreshProtocol.Bind> proxyBindings = new ConcurrentHashMap<>();
    private final Map<UUID, AtomicLong> proxyRevisions = new ConcurrentHashMap<>();
    private SemanticVersion implementationVersion;
    private BukkitNativeAdapter adapter;
    private ServerConfiguration configuration;
    private BukkitRefreshEngine engine;

    @Override
    @SuppressWarnings("deprecation")
    public void onEnable() {
        try {
            implementationVersion = SemanticVersion.parse(getDescription().getVersion());
            configuration = ServerConfigurationRepository.bundled(
                    getDataFolder().toPath(), getClassLoader()).load();
        } catch (IllegalArgumentException | ConfigurationException failure) {
            fail("invalid plugin version or server configuration: " + failure.getMessage());
            return;
        }

        BukkitRuntimeDetector.Detection detection = BukkitRuntimeDetector.detect();
        if (!detection.supported()) {
            fail(detection.diagnostic());
            return;
        }
        ExactAdapterSelector.Selection<BukkitNativeAdapter> selection =
                BukkitAdapterCatalog.selector().select(detection.identity());
        if (!selection.supported()) {
            fail("unsupported exact runtime " + detection.identity());
            return;
        }
        adapter = selection.load();
        BukkitNativeAdapter.AbiVerification abi = adapter.verifyAbi(
                getClassLoader(), Bukkit.getServer().getClass().getPackageName(), getLogger());
        if (!abi.compatible()) {
            fail(abi.diagnostic());
            return;
        }
        if (!Bukkit.getOnlineMode()) {
            if (!configuration.realtimeRefresh().trustedProxyForwarding()) {
                fail("offline backend requires explicit trustedProxyForwarding");
                return;
            }
            if (!ProxyConnectionAssurance.assured(true)) {
                fail("offline backend has no active Velocity modern forwarding or BungeeGuard 1.4.0+");
                return;
            }
            if (adapter.identity().family()
                    == com.naocraftlab.skins.server.plugin.common.ServerRuntimeIdentity.Family.SPIGOT
                    && !enabledPlugin("ProtocolLib")) {
                fail("Spigot proxy backend requires ProtocolLib for BungeeGuard");
                return;
            }
        }
        try {
            engine = adapter.createEngine(this, configuration, this::published);
        } catch (RuntimeException failure) {
            fail("native publication engine failed exact ABI binding: " + failure.getMessage());
            return;
        }

        getServer().getMessenger().registerIncomingPluginChannel(
                this, PluginChannels.CAPABILITIES, this);
        getServer().getMessenger().registerOutgoingPluginChannel(
                this, PluginChannels.CAPABILITIES);
        getServer().getMessenger().registerIncomingPluginChannel(
                this, PluginChannels.PROXY_REFRESH, this);
        getServer().getMessenger().registerOutgoingPluginChannel(
                this, PluginChannels.PROXY_REFRESH);
        PluginCommand command = getCommand("nclskin");
        if (command == null) {
            fail("plugin.yml lacks the internal nclskin command");
            return;
        }
        command.setExecutor(this::executeCommand);
        getServer().getPluginManager().registerEvents(this, this);
        for (Player player : Bukkit.getOnlinePlayers()) {
            engine.connected(player);
        }
        getLogger().info("NCL_SKINS_PLUGIN_READY platform=" + Bukkit.getName()
                + " minecraft=" + adapter.identity().minecraftVersion()
                + " adapter=" + adapter.id()
                + " upstreamChannel=" + upstreamChannel());
    }

    @Override
    public void onDisable() {
        proxyBindings.clear();
        proxyRevisions.clear();
        if (engine != null) {
            engine.close();
            engine = null;
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void joined(PlayerJoinEvent event) {
        if (engine != null) {
            engine.connected(event.getPlayer());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void quit(PlayerQuitEvent event) {
        proxyBindings.remove(event.getPlayer().getUniqueId());
        proxyRevisions.remove(event.getPlayer().getUniqueId());
        if (engine != null) {
            engine.disconnected(event.getPlayer());
        }
    }

    @Override
    public void onPluginMessageReceived(String channel, Player player, byte[] message) {
        if (PluginChannels.CAPABILITIES.equals(channel)) {
            if (!capabilities.isRequest(message)) {
                return;
            }
            byte[] response = capabilities.encodeResponse(new ServerCapabilityProtocol.Response(
                    implementationVersion,
                    List.of("command-v1", "capabilities-v1", "proxy-refresh-v1")));
            player.sendPluginMessage(this, PluginChannels.CAPABILITIES, response);
            return;
        }
        if (!PluginChannels.PROXY_REFRESH.equals(channel)) {
            return;
        }
        final ProxyRefreshProtocol.Message decoded;
        try {
            decoded = relay.decode(message);
        } catch (ProxyRefreshProtocol.ProtocolException malformed) {
            getLogger().warning("Rejected malformed bounded proxy relay payload");
            return;
        }
        if (decoded instanceof ProxyRefreshProtocol.Bind bind) {
            proxyBindings.put(player.getUniqueId(), bind);
            proxyRevisions.remove(player.getUniqueId());
        } else if (decoded instanceof ProxyRefreshProtocol.Refresh refresh) {
            ProxyRefreshProtocol.Bind bind = proxyBindings.get(player.getUniqueId());
            if (bind == null || !java.security.MessageDigest.isEqual(
                    bind.nonce(), refresh.nonce())) {
                getLogger().warning("Rejected stale proxy refresh fence");
                return;
            }
            if (refresh.revision() <= 0L) {
                getLogger().warning("Rejected stale proxy refresh revision");
                return;
            }
            AtomicLong accepted = proxyRevisions.computeIfAbsent(
                    player.getUniqueId(), ignored -> new AtomicLong());
            long previous = accepted.getAndUpdate(current ->
                    refresh.revision() > current ? refresh.revision() : current);
            if (refresh.revision() <= previous) {
                getLogger().warning("Rejected stale proxy refresh revision");
                return;
            }
            request(player, false);
        }
    }

    private boolean executeCommand(
            CommandSender sender,
            Command command,
            String label,
            String[] arguments) {
        if (!(sender instanceof Player player) || arguments.length != 1
                || !INTERNAL_REFRESH.equals(arguments[0])) {
            return false;
        }
        if (!configuration.realtimeRefresh().enabled()
                || !ProxyConnectionAssurance.assured(
                configuration.realtimeRefresh().trustedProxyForwarding())) {
            return true;
        }
        request(player, true);
        return true;
    }

    private void request(Player player, boolean dirty) {
        if (engine == null) {
            return;
        }
        RefreshSubmission submission = engine.request(player);
        if (dirty && (submission.admission() == Admission.ACCEPTED
                || submission.admission() == Admission.COALESCED)) {
            ProxyRefreshProtocol.Bind bind = proxyBindings.get(player.getUniqueId());
            if (bind != null) {
                long revision = proxyRevisions.computeIfAbsent(
                        player.getUniqueId(), ignored -> new AtomicLong()).incrementAndGet();
                player.sendPluginMessage(this, PluginChannels.PROXY_REFRESH,
                        relay.encode(new ProxyRefreshProtocol.Dirty(bind.nonce(), revision)));
            }
        }
    }

    private void published(Player player, VerifiedOfficialProfile profile) {
        ProxyRefreshProtocol.Bind bind = proxyBindings.get(player.getUniqueId());
        AtomicLong revision = proxyRevisions.get(player.getUniqueId());
        if (bind == null || revision == null || revision.get() <= 0L || !player.isOnline()) {
            return;
        }
        player.sendPluginMessage(this, PluginChannels.PROXY_REFRESH,
                relay.encode(new ProxyRefreshProtocol.State(
                        bind.nonce(), revision.get(), profile.textures())));
    }

    private String upstreamChannel() {
        if (adapter.identity().minecraftVersion().equals("26.1.1")) {
            return "ALPHA";
        }
        if (adapter.identity().family()
                != com.naocraftlab.skins.server.plugin.common.ServerRuntimeIdentity.Family.FOLIA) {
            return "STABLE";
        }
        return switch (adapter.identity().minecraftVersion()) {
            case "1.20.1" -> "ALPHA";
            case "26.2" -> "BETA";
            default -> "STABLE";
        };
    }

    private boolean enabledPlugin(String name) {
        org.bukkit.plugin.Plugin plugin = getServer().getPluginManager().getPlugin(name);
        return plugin != null && plugin.isEnabled();
    }

    private void fail(String diagnostic) {
        getLogger().severe("NCL Skins Plugin disabled: " + diagnostic);
        getServer().getPluginManager().disablePlugin(this);
    }
}

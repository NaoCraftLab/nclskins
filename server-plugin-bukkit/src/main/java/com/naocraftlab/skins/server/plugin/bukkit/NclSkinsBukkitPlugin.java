package com.naocraftlab.skins.server.plugin.bukkit;

import com.naocraftlab.skins.core.config.ConfigurationException;
import com.naocraftlab.skins.core.config.ServerConfiguration;
import com.naocraftlab.skins.core.config.ServerConfigurationRepository;
import com.naocraftlab.skins.diagnostics.DiagnosticDetails;
import com.naocraftlab.skins.diagnostics.DiagnosticEvent;
import com.naocraftlab.skins.diagnostics.DiagnosticStatus;
import com.naocraftlab.skins.diagnostics.JulDiagnosticSink;
import com.naocraftlab.skins.server.Admission;
import com.naocraftlab.skins.server.RefreshResult;
import com.naocraftlab.skins.server.RefreshSubmission;
import com.naocraftlab.skins.server.VerifiedOfficialProfile;
import com.naocraftlab.skins.server.plugin.common.ExactAdapterSelector;
import com.naocraftlab.skins.server.plugin.common.PluginChannels;
import com.naocraftlab.skins.server.plugin.common.ProxyRefreshProtocol;
import com.naocraftlab.skins.server.plugin.common.SemanticVersion;
import com.naocraftlab.skins.server.plugin.common.ServerCapabilityProtocol;
import org.apache.logging.log4j.LogManager;
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
import java.util.logging.Level;


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
    private JulDiagnosticSink diagnostics;

    @Override
    @SuppressWarnings("deprecation")
    public void onEnable() {
        diagnostics = new JulDiagnosticSink(
                getLogger(),
                Level.CONFIG,
                () -> LogManager.getLogger(getClass().getCanonicalName()).isDebugEnabled());
        try {
            implementationVersion = SemanticVersion.parse(getDescription().getVersion());
            configuration = ServerConfigurationRepository.bundled(
                    getDataFolder().toPath(), getClassLoader()).load();
        } catch (IllegalArgumentException | ConfigurationException failure) {
            fail(DiagnosticStatus.INVALID_CONFIGURATION, failure);
            return;
        }

        BukkitRuntimeDetector.Detection detection = BukkitRuntimeDetector.detect();
        if (!detection.supported()) {
            fail(DiagnosticStatus.UNSUPPORTED_RUNTIME, null);
            return;
        }
        ExactAdapterSelector.Selection<BukkitNativeAdapter> selection =
                BukkitAdapterCatalog.selector().select(detection.identity());
        if (!selection.supported()) {
            fail(DiagnosticStatus.UNSUPPORTED_RUNTIME, null);
            return;
        }
        adapter = selection.load();
        BukkitNativeAdapter.AbiVerification abi = adapter.verifyAbi(
                getClassLoader(), Bukkit.getServer().getClass().getPackageName(), getLogger());
        if (!abi.compatible()) {
            fail(DiagnosticStatus.ABI_INCOMPATIBLE, null);
            return;
        }
        if (!Bukkit.getOnlineMode()) {
            if (!configuration.realtimeRefresh().trustedProxyForwarding()) {
                fail(DiagnosticStatus.TRUST_REQUIREMENT_MISSING, null);
                return;
            }
            if (!ProxyConnectionAssurance.assured(true)) {
                fail(DiagnosticStatus.TRUST_REQUIREMENT_MISSING, null);
                return;
            }
            if (adapter.identity().family()
                    == com.naocraftlab.skins.server.plugin.common.ServerRuntimeIdentity.Family.SPIGOT
                    && !enabledPlugin("ProtocolLib")) {
                fail(DiagnosticStatus.TRUST_REQUIREMENT_MISSING, null);
                return;
            }
        }
        try {
            engine = adapter.createEngine(this, configuration, this::published, diagnostics);
        } catch (RuntimeException failure) {
            fail(DiagnosticStatus.ABI_INCOMPATIBLE, failure);
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
            fail(DiagnosticStatus.INTERNAL_METADATA_MISSING, null);
            return;
        }
        command.setExecutor(this::executeCommand);
        getServer().getPluginManager().registerEvents(this, this);
        for (Player player : Bukkit.getOnlinePlayers()) {
            engine.connected(player);
        }
        diagnostics.report(DiagnosticEvent.PLUGIN_READY, DiagnosticDetails::none);
    }

    @Override
    public void onDisable() {
        proxyBindings.clear();
        proxyRevisions.clear();
        if (engine != null) {
            engine.close();
            engine = null;
        }
        if (diagnostics != null) {
            diagnostics.close();
            diagnostics = null;
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
            diagnostics.report(
                    DiagnosticEvent.RELAY_MALFORMED,
                    () -> DiagnosticDetails.failure(malformed));
            return;
        }
        if (decoded instanceof ProxyRefreshProtocol.Bind bind) {
            proxyBindings.put(player.getUniqueId(), bind);
            proxyRevisions.remove(player.getUniqueId());
        } else if (decoded instanceof ProxyRefreshProtocol.Refresh refresh) {
            ProxyRefreshProtocol.Bind bind = proxyBindings.get(player.getUniqueId());
            if (bind == null || !java.security.MessageDigest.isEqual(
                    bind.nonce(), refresh.nonce())) {
                diagnostics.report(DiagnosticEvent.RELAY_STALE, DiagnosticDetails::none);
                return;
            }
            if (refresh.revision() <= 0L) {
                diagnostics.report(DiagnosticEvent.RELAY_STALE, DiagnosticDetails::none);
                return;
            }
            AtomicLong accepted = proxyRevisions.computeIfAbsent(
                    player.getUniqueId(), ignored -> new AtomicLong());
            long previous = accepted.getAndUpdate(current ->
                    refresh.revision() > current ? refresh.revision() : current);
            if (refresh.revision() <= previous) {
                diagnostics.report(DiagnosticEvent.RELAY_STALE, DiagnosticDetails::none);
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
        observe(submission);
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

    private boolean enabledPlugin(String name) {
        org.bukkit.plugin.Plugin plugin = getServer().getPluginManager().getPlugin(name);
        return plugin != null && plugin.isEnabled();
    }

    private void observe(RefreshSubmission submission) {
        if (submission.admission() == Admission.OVERLOADED) {
            diagnostics.report(
                    DiagnosticEvent.PLUGIN_REFRESH_OVERLOADED,
                    () -> DiagnosticDetails.status(DiagnosticStatus.OVERLOADED));
        }
        submission.completion().whenComplete((result, failure) -> {
            if (failure != null) {
                diagnostics.report(
                        DiagnosticEvent.PLUGIN_REFRESH_FAILED,
                        () -> DiagnosticDetails.failure(failure));
                return;
            }
            if (result == RefreshResult.REJECTED) {
                diagnostics.report(
                        DiagnosticEvent.PLUGIN_REFRESH_REJECTED,
                        () -> DiagnosticDetails.status(DiagnosticStatus.REJECTED));
            } else if (result == RefreshResult.OVERLOADED) {
                diagnostics.report(
                        DiagnosticEvent.PLUGIN_REFRESH_OVERLOADED,
                        () -> DiagnosticDetails.status(DiagnosticStatus.OVERLOADED));
            } else if (result == RefreshResult.EXPIRED) {
                diagnostics.report(
                        DiagnosticEvent.PLUGIN_REFRESH_EXPIRED,
                        () -> DiagnosticDetails.status(DiagnosticStatus.EXPIRED));
            } else if (result == RefreshResult.FAILED || result == RefreshResult.EXHAUSTED) {
                DiagnosticStatus status = result == RefreshResult.FAILED
                        ? DiagnosticStatus.FAILED : DiagnosticStatus.EXHAUSTED;
                diagnostics.report(
                        DiagnosticEvent.PLUGIN_REFRESH_FAILED,
                        () -> DiagnosticDetails.status(status));
            }
        });
    }

    private void fail(DiagnosticStatus status, Throwable failure) {
        if (failure == null) {
            diagnostics.report(
                    DiagnosticEvent.PLUGIN_STARTUP_FAILED,
                    () -> DiagnosticDetails.status(status));
        } else {
            diagnostics.report(
                    DiagnosticEvent.PLUGIN_STARTUP_FAILED,
                    () -> DiagnosticDetails.statusFailure(status, failure));
        }
        getServer().getPluginManager().disablePlugin(this);
    }
}

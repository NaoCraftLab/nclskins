package com.naocraftlab.skins.server.plugin.bukkit;

import com.naocraftlab.skins.core.config.ServerConfiguration;
import com.naocraftlab.skins.diagnostics.DiagnosticDetails;
import com.naocraftlab.skins.diagnostics.DiagnosticEvent;
import com.naocraftlab.skins.diagnostics.DiagnosticSink;
import com.naocraftlab.skins.server.Admission;
import com.naocraftlab.skins.server.BatchAppearancePublisher;
import com.naocraftlab.skins.server.BatchPublicationResult;
import com.naocraftlab.skins.server.ConnectionKey;
import com.naocraftlab.skins.server.ConnectionSnapshot;
import com.naocraftlab.skins.server.IdentityAssurance;
import com.naocraftlab.skins.server.PublicationOutcome;
import com.naocraftlab.skins.server.PublicationRequest;
import com.naocraftlab.skins.server.RefreshResult;
import com.naocraftlab.skins.server.RefreshSubmission;
import com.naocraftlab.skins.server.runtime.OfficialProfileResolutionService;
import com.naocraftlab.skins.server.runtime.OfficialSessionProfileClient;
import com.naocraftlab.skins.server.runtime.ServerAppearanceRefreshCoordinator;
import com.naocraftlab.skins.server.runtime.ServerAppearanceRefreshService;
import com.naocraftlab.skins.server.runtime.ServerRefreshPolicy;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

final class BukkitAppearanceRefreshEngine implements BukkitRefreshEngine {
    private final Object lock = new Object();
    private final BukkitExecution execution;
    private final BukkitPublicationBackend publicationBackend;
    private final BukkitConnectionAssurance connectionAssurance;
    private final PublicationListener listener;
    private final DiagnosticSink diagnostics;
    private final Map<UUID, LiveConnection> connections = new LinkedHashMap<>();
    private final NativePublisher publisher;
    private final ServerAppearanceRefreshService service;
    private long nextGeneration;
    private boolean closed;

    BukkitAppearanceRefreshEngine(
            JavaPlugin plugin,
            ServerConfiguration configuration,
            boolean regionized,
            AuthlibSignatureVerifier signatureVerifier,
            BukkitPublicationBackend publicationBackend,
            BukkitConnectionAssurance connectionAssurance,
            PublicationListener listener,
            DiagnosticSink diagnostics) {
        Objects.requireNonNull(plugin, "plugin");
        ServerConfiguration.RealtimeRefresh refresh = Objects.requireNonNull(
                configuration, "configuration").realtimeRefresh();
        this.listener = Objects.requireNonNull(listener, "listener");
        this.diagnostics = Objects.requireNonNull(diagnostics, "diagnostics");
        this.publicationBackend = Objects.requireNonNull(publicationBackend, "publicationBackend");
        this.connectionAssurance = Objects.requireNonNull(
                connectionAssurance, "connectionAssurance");
        execution = new BukkitExecution(plugin, regionized);
        ServerRefreshPolicy defaults = ServerRefreshPolicy.defaults(
                refresh.trustedProxyForwarding(), Bukkit.getMaxPlayers());
        ServerRefreshPolicy policy = new ServerRefreshPolicy(
                refresh.trustedProxyForwarding(), defaults.maxPendingConnections(),
                refresh.maxConcurrentLookups(), refresh.lookupRatePerSecond(),
                refresh.lookupBurst(), defaults.attemptOffsets(), defaults.maxQueueAge(),
                defaults.lookupCycleDeadline(), defaults.independentCycleCooldown(),
                defaults.batchWindow(), defaults.maxBatchActors(),
                defaults.maxReconciliationAttempts(), defaults.maxPacketEntries(),
                defaults.maxRecipientProfileDeliveriesPerTick(),
                defaults.maxPlatformThreadTimePerTick());
        publisher = new NativePublisher();
        OfficialProfileResolutionService resolver = new OfficialProfileResolutionService(
                new OfficialSessionProfileClient(),
                Objects.requireNonNull(signatureVerifier, "signatureVerifier"));
        service = new ServerAppearanceRefreshService(
                policy, new ServerAppearanceRefreshCoordinator(resolver, publisher, policy));
    }

    @Override
    public void connected(Player player) {
        Objects.requireNonNull(player, "player");
        ConnectionKey superseded = null;
        synchronized (lock) {
            if (closed) return;
            LiveConnection current = connections.get(player.getUniqueId());
            if (current != null && current.player == player
                    && current.name.equals(player.getName())) return;
            if (nextGeneration == Long.MAX_VALUE) {
                throw new IllegalStateException("Connection generation exhausted");
            }
            if (current != null) superseded = current.key;
            connections.put(player.getUniqueId(), new LiveConnection(
                    player, player.getName(),
                    new ConnectionKey(player.getUniqueId(), ++nextGeneration), assurance()));
        }
        if (superseded != null) service.disconnected(superseded);
    }

    @Override
    public void disconnected(Player player) {
        ConnectionKey removed = null;
        synchronized (lock) {
            LiveConnection current = connections.get(player.getUniqueId());
            if (current != null && current.player == player) {
                connections.remove(player.getUniqueId());
                removed = current.key;
            }
        }
        if (removed != null) service.disconnected(removed);
    }

    @Override
    public RefreshSubmission request(Player player) {
        connected(player);
        LiveConnection connection;
        synchronized (lock) {
            connection = connections.get(player.getUniqueId());
        }
        if (connection == null || connection.player != player) {
            return new RefreshSubmission(Admission.INELIGIBLE,
                    CompletableFuture.completedFuture(RefreshResult.INELIGIBLE));
        }
        return service.request(new ConnectionSnapshot(
                connection.key, connection.name, connection.assurance));
    }

    @Override
    public void close() {
        synchronized (lock) {
            if (closed) return;
            closed = true;
            connections.clear();
        }
        service.close();
        publisher.close();
    }

    private IdentityAssurance assurance() {
        if (Bukkit.getOnlineMode()) return IdentityAssurance.ONLINE;
        return connectionAssurance.assured(true)
                ? IdentityAssurance.TRUSTED_PROXY : IdentityAssurance.OFFLINE;
    }

    private LiveConnection resolve(ConnectionKey key) {
        synchronized (lock) {
            LiveConnection result = connections.get(key.profileId());
            return result != null && result.key.equals(key) ? result : null;
        }
    }

    private final class NativePublisher implements BatchAppearancePublisher, AutoCloseable {
        private boolean publisherClosed;

        @Override
        public CompletionStage<BatchPublicationResult> publishBatch(
                List<PublicationRequest> requests) {
            if (publisherClosed) {
                return CompletableFuture.completedFuture(
                        BatchPublicationResult.all(requests, PublicationOutcome.FAILED));
            }
            Map<ConnectionKey, PublicationOutcome> outcomes = new LinkedHashMap<>();
            List<CompletableFuture<Void>> futures = new ArrayList<>();
            for (PublicationRequest request : requests) {
                CompletableFuture<Void> future = publishOne(request).handle((updated, failure) -> {
                    if (failure != null) {
                        diagnostics.report(DiagnosticEvent.SERVER_PUBLICATION_FAILED,
                                () -> DiagnosticDetails.failure(failure));
                    }
                    synchronized (outcomes) {
                        outcomes.put(request.connection(), failure == null && updated
                                ? PublicationOutcome.UPDATED
                                : failure == null ? PublicationOutcome.STALE
                                : PublicationOutcome.FAILED);
                    }
                    return null;
                });
                futures.add(future);
            }
            return CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new))
                    .thenApply(ignored -> BatchPublicationResult.of(outcomes));
        }

        private CompletableFuture<Boolean> publishOne(PublicationRequest request) {
            LiveConnection actor = resolve(request.connection());
            if (actor == null || !actor.name.equals(request.profile().identity().profileName())) {
                return CompletableFuture.completedFuture(false);
            }
            NativePublication publication = new NativePublication(actor, request);
            return execution.player(actor.player, publication::installAndSnapshot)
                    .thenCompose(ignored -> publication.refreshObservers())
                    .thenApply(ignored -> {
                        LiveConnection current = resolve(request.connection());
                        if (current == null) return false;
                        listener.published(current.player, request.profile());
                        return true;
                    });
        }

        @Override
        public void supersede(ConnectionKey connection) {
        }

        @Override
        public void close() {
            publisherClosed = true;
        }
    }

    private final class NativePublication {
        private final LiveConnection actor;
        private final PublicationRequest request;
        private BukkitPublicationBackend.Publication publication;
        private List<Player> observers = List.of();

        private NativePublication(LiveConnection actor, PublicationRequest request) {
            this.actor = actor;
            this.request = request;
        }

        private void installAndSnapshot() {
            if (resolve(request.connection()) != actor || !actor.player.isOnline()) {
                throw new IllegalStateException("Stale actor connection");
            }
            publication = publicationBackend.installAndSnapshot(actor.player, request.profile());
            observers = publication.observers().stream()
                    .filter(observer -> observer != actor.player && observer.isOnline()).toList();
        }

        private CompletableFuture<Void> refreshObservers() {
            CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
            for (Player observer : observers) {
                chain = chain.thenCompose(ignored -> execution.player(observer,
                        () -> BukkitObserverPublication.refresh(
                                () -> publication.untrack(observer),
                                () -> publication.sendPlayerInfo(observer),
                                () -> publication.retrack(observer),
                                () -> publication.isTracking(observer))));
            }
            return chain;
        }
    }

    private static final class LiveConnection {
        private final Player player;
        private final String name;
        private final ConnectionKey key;
        private final IdentityAssurance assurance;

        private LiveConnection(
                Player player, String name, ConnectionKey key, IdentityAssurance assurance) {
            this.player = player;
            this.name = name;
            this.key = key;
            this.assurance = assurance;
        }
    }
}

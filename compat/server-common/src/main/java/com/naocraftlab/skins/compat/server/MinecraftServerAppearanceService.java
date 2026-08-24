package com.naocraftlab.skins.compat.server;

import com.naocraftlab.skins.server.Admission;
import com.naocraftlab.skins.server.ConnectionKey;
import com.naocraftlab.skins.server.ConnectionSnapshot;
import com.naocraftlab.skins.server.IdentityAssurance;
import com.naocraftlab.skins.server.RefreshSubmission;
import com.naocraftlab.skins.server.ServerRefreshHealthSnapshot;
import com.naocraftlab.skins.server.runtime.OfficialProfileResolutionService;
import com.naocraftlab.skins.server.runtime.OfficialSessionProfileClient;
import com.naocraftlab.skins.server.runtime.ServerAppearanceRefreshCoordinator;
import com.naocraftlab.skins.server.runtime.ServerAppearanceRefreshService;
import com.naocraftlab.skins.server.runtime.ServerRefreshPolicy;
import com.naocraftlab.skins.diagnostics.DiagnosticDetails;
import com.naocraftlab.skins.diagnostics.DiagnosticEvent;
import com.naocraftlab.skins.diagnostics.DiagnosticSink;
import com.naocraftlab.skins.diagnostics.DiagnosticStatus;
import com.naocraftlab.skins.diagnostics.Slf4jDiagnosticSink;
import com.naocraftlab.skins.server.RefreshResult;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.slf4j.LoggerFactory;


public final class MinecraftServerAppearanceService implements AutoCloseable {
    private static final Map<MinecraftServer, MinecraftServerAppearanceService> REGISTERED =
            new IdentityHashMap<>();

    private final MinecraftServer server;
    private final MinecraftServerConnectionRegistry connections;
    private final MinecraftServerAppearancePublisher publisher;
    private final ServerAppearanceRefreshService refreshService;
    private final ServerRefreshPolicy policy;
    private final MinecraftServerIdentityAttestor identityAttestor;
    private final DiagnosticSink diagnostics;
    private boolean closed;

    private MinecraftServerAppearanceService(
            MinecraftServer server,
            MinecraftServerConnectionRegistry connections,
            MinecraftServerAppearancePublisher publisher,
            ServerAppearanceRefreshService refreshService,
            ServerRefreshPolicy policy,
            MinecraftServerIdentityAttestor identityAttestor,
            DiagnosticSink diagnostics) {
        this.server = server;
        this.connections = connections;
        this.publisher = publisher;
        this.refreshService = refreshService;
        this.policy = policy;
        this.identityAttestor = identityAttestor;
        this.diagnostics = diagnostics;
    }

    static MinecraftServerAppearanceService register(
            MinecraftServer server, MinecraftServerRefreshConfig config) {
        return register(
                server,
                config,
                MinecraftServerIdentityAttestor.authenticatedOnly());
    }


    static MinecraftServerAppearanceService register(
            MinecraftServer server,
            MinecraftServerRefreshConfig config,
            MinecraftServerIdentityAttestor identityAttestor) {
        Objects.requireNonNull(server, "server");
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(identityAttestor, "identityAttestor");
        if (!server.isSameThread()) {
            throw new IllegalStateException("Server appearance service must start on the server thread");
        }
        synchronized (REGISTERED) {
            MinecraftServerAppearanceService existing = REGISTERED.get(server);
            if (existing != null) {
                return existing;
            }
            ServerRefreshPolicy policy = config.policy(server.getPlayerList().getMaxPlayers());
            DiagnosticSink diagnostics = new Slf4jDiagnosticSink(LoggerFactory.getLogger("nclskins"));
            MinecraftServerConnectionRegistry connections =
                    new MinecraftServerConnectionRegistry(server);
            MinecraftServerAppearancePublisher publisher =
                    new MinecraftServerAppearancePublisher(server, connections, policy);
            OfficialProfileResolutionService resolver = new OfficialProfileResolutionService(
                    new OfficialSessionProfileClient(),
                    new MinecraftOfficialTextureSignatureVerifier(server));
            ServerAppearanceRefreshCoordinator coordinator =
                    new ServerAppearanceRefreshCoordinator(resolver, publisher, policy);
            MinecraftServerAppearanceService created = new MinecraftServerAppearanceService(
                    server,
                    connections,
                    publisher,
                    new ServerAppearanceRefreshService(policy, coordinator),
                    policy,
                    identityAttestor,
                    diagnostics);
            REGISTERED.put(server, created);
            return created;
        }
    }

    public static Optional<MinecraftServerAppearanceService> registered(MinecraftServer server) {
        Objects.requireNonNull(server, "server");
        synchronized (REGISTERED) {
            return Optional.ofNullable(REGISTERED.get(server));
        }
    }

    public static void closeRegistered(MinecraftServer server) {
        Objects.requireNonNull(server, "server");
        registered(server).ifPresent(MinecraftServerAppearanceService::close);
    }

    public void connected(ServerPlayer player) {
        requireServerThread();
        if (!closed) {
            ServerPlayer checked = Objects.requireNonNull(player, "player");
            captureConnection(checked);
        }
    }

    public void disconnected(ServerPlayer player) {
        requireServerThread();
        if (!closed) {
            connections.disconnected(Objects.requireNonNull(player, "player"))
                    .ifPresent(refreshService::disconnected);
        }
    }

    public void disconnected(ServerGamePacketListenerImpl listener) {
        requireServerThread();
        if (!closed) {
            connections.disconnected(Objects.requireNonNull(listener, "listener"))
                    .ifPresent(refreshService::disconnected);
        }
    }

    public boolean eligible(ServerPlayer player) {
        requireServerThread();
        if (closed) {
            return false;
        }


        return refreshService.eligible(identityAssurance(
                Objects.requireNonNull(player, "player")));
    }

    public RefreshSubmission request(ServerPlayer player) {
        requireServerThread();
        if (closed) {
            return RefreshSubmissionClosed.INSTANCE;
        }
        ConnectionSnapshot connection = captureConnection(
                Objects.requireNonNull(player, "player"));
        RefreshSubmission submission = refreshService.request(connection);
        submission.completion().whenComplete((result, failure) -> observe(result, failure));
        return submission;
    }

    public ServerRefreshHealthSnapshot health() {
        return refreshService.health();
    }

    @Override
    public void close() {
        requireServerThread();
        synchronized (REGISTERED) {
            if (closed) {
                return;
            }
            closed = true;
            REGISTERED.remove(server, this);
        }
        refreshService.close();
        publisher.close();
        connections.close();
        diagnostics.close();
    }

    private void requireServerThread() {
        if (!server.isSameThread()) {
            throw new IllegalStateException("Server appearance lifecycle must run on the server thread");
        }
    }

    private ConnectionSnapshot captureConnection(ServerPlayer player) {
        MinecraftServerConnectionRegistry.ConnectionRegistration registration =
                connections.connected(player, identityAssurance(player));
        registration.superseded().ifPresent(refreshService::disconnected);
        return registration.snapshot();
    }

    private IdentityAssurance identityAssurance(ServerPlayer player) {
        final IdentityAssurance attested;
        try {
            attested = Objects.requireNonNull(
                    identityAttestor.attest(server, player), "attested assurance");
        } catch (RuntimeException invalidEvidence) {
            diagnostics.report(
                    DiagnosticEvent.SERVER_IDENTITY_ATTESTATION_FAILED,
                    () -> DiagnosticDetails.failure(invalidEvidence));
            return IdentityAssurance.OFFLINE;
        }
        if (attested == IdentityAssurance.ONLINE) {
            return server.usesAuthentication()
                    ? IdentityAssurance.ONLINE
                    : IdentityAssurance.OFFLINE;
        }
        if (attested == IdentityAssurance.TRUSTED_PROXY
                && policy.trustedProxyForwarding()) {
            return IdentityAssurance.TRUSTED_PROXY;
        }
        return IdentityAssurance.OFFLINE;
    }

    private void observe(RefreshResult result, Throwable failure) {
        if (failure != null) {
            diagnostics.report(
                    DiagnosticEvent.SERVER_PUBLICATION_FAILED,
                    () -> DiagnosticDetails.failure(failure));
        } else if (result == RefreshResult.REJECTED) {
            diagnostics.report(
                    DiagnosticEvent.PLUGIN_REFRESH_REJECTED,
                    () -> DiagnosticDetails.status(DiagnosticStatus.REJECTED));
        } else if (result == RefreshResult.OVERLOADED || result == RefreshResult.EXPIRED) {
            DiagnosticEvent event = result == RefreshResult.OVERLOADED
                    ? DiagnosticEvent.PLUGIN_REFRESH_OVERLOADED
                    : DiagnosticEvent.PLUGIN_REFRESH_EXPIRED;
            DiagnosticStatus status = result == RefreshResult.OVERLOADED
                    ? DiagnosticStatus.OVERLOADED : DiagnosticStatus.EXPIRED;
            diagnostics.report(event, () -> DiagnosticDetails.status(status));
        } else if (result == RefreshResult.FAILED || result == RefreshResult.EXHAUSTED) {
            diagnostics.report(
                    DiagnosticEvent.SERVER_PUBLICATION_FAILED,
                    () -> DiagnosticDetails.status(result == RefreshResult.FAILED
                            ? DiagnosticStatus.FAILED : DiagnosticStatus.EXHAUSTED));
        }
    }

    private static final class RefreshSubmissionClosed {
        private static final RefreshSubmission INSTANCE = new RefreshSubmission(
                Admission.CLOSED,
                java.util.concurrent.CompletableFuture.completedFuture(
                        com.naocraftlab.skins.server.RefreshResult.CLOSED));
    }
}

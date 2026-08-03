package com.naocraftlab.skins.server.runtime;

import com.naocraftlab.skins.server.Admission;
import com.naocraftlab.skins.server.ConnectionKey;
import com.naocraftlab.skins.server.ConnectionSnapshot;
import com.naocraftlab.skins.server.IdentityAssurance;
import com.naocraftlab.skins.server.RefreshResult;
import com.naocraftlab.skins.server.RefreshSubmission;
import com.naocraftlab.skins.server.ServerRefreshHealthSnapshot;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;


public final class ServerAppearanceRefreshService implements AutoCloseable {
    private final ServerRefreshPolicy policy;
    private final ServerAppearanceRefreshCoordinator coordinator;
    private volatile boolean closed;

    public ServerAppearanceRefreshService(
            ServerRefreshPolicy policy,
            ServerAppearanceRefreshCoordinator coordinator) {
        this.policy = Objects.requireNonNull(policy, "policy");
        this.coordinator = Objects.requireNonNull(coordinator, "coordinator");
    }

    public boolean eligible(ConnectionSnapshot connection) {
        Objects.requireNonNull(connection, "connection");
        return eligible(connection.assurance());
    }


    public boolean eligible(IdentityAssurance assurance) {
        Objects.requireNonNull(assurance, "assurance");
        return !closed && switch (assurance) {
            case ONLINE -> true;
            case TRUSTED_PROXY -> policy.trustedProxyForwarding();
            case OFFLINE -> false;
        };
    }

    public RefreshSubmission request(ConnectionSnapshot connection) {
        Objects.requireNonNull(connection, "connection");
        if (closed) {
            return immediate(Admission.CLOSED, RefreshResult.CLOSED);
        }
        if (!eligible(connection)) {
            return immediate(Admission.INELIGIBLE, RefreshResult.INELIGIBLE);
        }
        return coordinator.request(connection);
    }

    public void disconnected(ConnectionKey connection) {
        coordinator.disconnected(Objects.requireNonNull(connection, "connection"));
    }

    public ServerRefreshHealthSnapshot health() {
        return coordinator.health();
    }

    @Override
    public void close() {
        closed = true;
        coordinator.close();
    }

    private static RefreshSubmission immediate(Admission admission, RefreshResult result) {
        return new RefreshSubmission(
                admission,
                CompletableFuture.completedFuture(result));
    }
}

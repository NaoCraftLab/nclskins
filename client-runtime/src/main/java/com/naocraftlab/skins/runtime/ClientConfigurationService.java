package com.naocraftlab.skins.runtime;

import com.naocraftlab.skins.core.config.ClientConfiguration;
import com.naocraftlab.skins.core.config.ServerConfiguration;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

public final class ClientConfigurationService implements ConfigurationUseCases {
    private final ConfigurationStore store;
    private final Path activeDataRoot;
    private final Path defaultDataRoot;
    private volatile ClientConfiguration client;

    public ClientConfigurationService(ConfigurationStore store, ClientConfiguration initial,
            Path activeDataRoot, Path defaultDataRoot) {
        this.store = Objects.requireNonNull(store, "store");
        this.client = Objects.requireNonNull(initial, "initial");
        this.activeDataRoot = Objects.requireNonNull(activeDataRoot, "activeDataRoot");
        this.defaultDataRoot = Objects.requireNonNull(defaultDataRoot, "defaultDataRoot");
    }

    @Override
    public ClientConfiguration client() {
        return client;
    }

    @Override
    public Path activeDataRoot() {
        return activeDataRoot;
    }

    @Override
    public synchronized ConfigurationSession openSession(ServerConfigurationAccess access) {
        Objects.requireNonNull(access, "access");
        return new ConfigurationSession(client,
                access.visible() ? Optional.of(store.loadServer()) : Optional.empty(),
                access, activeDataRoot, defaultDataRoot);
    }

    @Override
    public synchronized void save(ServerConfigurationAccess access, ClientConfiguration configuration,
            Optional<ServerConfiguration> server) {
        Objects.requireNonNull(access, "access");
        Objects.requireNonNull(server, "server");
        ClientConfiguration checked = Objects.requireNonNull(configuration, "configuration");
        store.saveClient(checked);
        client = checked;
        if (access.visible()) {
            server.ifPresent(store::saveServer);
        }
    }
}

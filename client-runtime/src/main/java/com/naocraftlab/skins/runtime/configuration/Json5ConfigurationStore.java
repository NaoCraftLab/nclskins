package com.naocraftlab.skins.runtime.configuration;

import com.naocraftlab.skins.core.config.ClientConfiguration;
import com.naocraftlab.skins.core.config.Json5ConfigurationRepository;
import com.naocraftlab.skins.core.config.ServerConfiguration;
import com.naocraftlab.skins.runtime.ConfigurationStore;
import java.util.Objects;

public final class Json5ConfigurationStore implements ConfigurationStore {
    private final Json5ConfigurationRepository repository;

    public Json5ConfigurationStore(Json5ConfigurationRepository repository) {
        this.repository = Objects.requireNonNull(repository, "repository");
    }

    @Override
    public ClientConfiguration loadClient() {
        return repository.loadClient();
    }

    @Override
    public void saveClient(ClientConfiguration configuration) {
        repository.saveClient(configuration);
    }

    @Override
    public ServerConfiguration loadServer() {
        return repository.loadServer();
    }

    @Override
    public void saveServer(ServerConfiguration configuration) {
        repository.saveServer(configuration);
    }
}

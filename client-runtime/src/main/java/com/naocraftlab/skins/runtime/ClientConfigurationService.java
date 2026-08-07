package com.naocraftlab.skins.runtime;

import com.naocraftlab.skins.core.config.ClientConfiguration;
import com.naocraftlab.skins.core.config.Json5ConfigurationRepository;
import com.naocraftlab.skins.core.config.ServerConfiguration;
import com.naocraftlab.skins.core.storage.NclSkinsStorage;

import java.nio.file.Path;
import java.util.Objects;


public final class ClientConfigurationService {
    private final Path configurationDirectory;
    private final Json5ConfigurationRepository repository;
    private final Path activeDataRoot;
    private volatile ClientConfiguration client;

    public ClientConfigurationService(Path configurationDirectory) {
        this(
                configurationDirectory,
                Json5ConfigurationRepository.bundled(configurationDirectory));
    }

    ClientConfigurationService(
            Path configurationDirectory,
            Json5ConfigurationRepository repository) {
        this.configurationDirectory = Objects.requireNonNull(
                configurationDirectory, "configurationDirectory").toAbsolutePath().normalize();
        this.repository = Objects.requireNonNull(repository, "repository");
        client = repository.loadClient();
        activeDataRoot = client.dataRoot(NclSkinsStorage.defaultRoot());
    }

    public Path configurationDirectory() {
        return configurationDirectory;
    }

    public ClientConfiguration client() {
        return client;
    }

    public Path activeDataRoot() {
        return activeDataRoot;
    }

    public synchronized void saveClient(ClientConfiguration configuration) {
        ClientConfiguration checked = Objects.requireNonNull(configuration, "configuration");
        repository.saveClient(checked);
        client = checked;
    }

    public ServerConfiguration loadServer() {
        return repository.loadServer();
    }

    public void saveServer(ServerConfiguration configuration) {
        repository.saveServer(Objects.requireNonNull(configuration, "configuration"));
    }
}

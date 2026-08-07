package com.naocraftlab.skins.compat.server;

import com.naocraftlab.skins.core.config.ConfigurationException;
import com.naocraftlab.skins.core.config.Json5ConfigurationRepository;
import com.naocraftlab.skins.core.config.ServerConfiguration;
import com.naocraftlab.skins.server.runtime.ServerRefreshPolicy;
import java.nio.file.Path;
import java.util.Objects;


public final class MinecraftServerRefreshConfig {
    static final String FILE_NAME = Json5ConfigurationRepository.SERVER_FILE_NAME;

    private final ServerConfiguration configuration;

    private MinecraftServerRefreshConfig(ServerConfiguration configuration) {
        this.configuration = Objects.requireNonNull(configuration, "configuration");
    }

    static MinecraftServerRefreshConfig load(Path configDirectory) throws ConfigException {
        try {
            return new MinecraftServerRefreshConfig(
                    Json5ConfigurationRepository.bundled(
                            Objects.requireNonNull(configDirectory, "configDirectory"))
                            .loadServer());
        } catch (ConfigurationException failure) {
            throw new ConfigException("Unable to load server refresh configuration", failure);
        }
    }

    boolean enabled() {
        return configuration.realtimeRefresh().enabled();
    }

    ServerRefreshPolicy policy(int serverMaxPlayers) {
        ServerConfiguration.RealtimeRefresh refresh = configuration.realtimeRefresh();
        ServerRefreshPolicy defaults = ServerRefreshPolicy.defaults(
                refresh.trustedProxyForwarding(), serverMaxPlayers);
        return new ServerRefreshPolicy(
                refresh.trustedProxyForwarding(),
                defaults.maxPendingConnections(),
                refresh.maxConcurrentLookups(),
                refresh.lookupRatePerSecond(),
                refresh.lookupBurst(),
                defaults.attemptOffsets(),
                defaults.maxQueueAge(),
                defaults.lookupCycleDeadline(),
                defaults.independentCycleCooldown(),
                defaults.batchWindow(),
                defaults.maxBatchActors(),
                defaults.maxReconciliationAttempts());
    }

    public static final class ConfigException extends Exception {
        private static final long serialVersionUID = 1L;

        private ConfigException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}

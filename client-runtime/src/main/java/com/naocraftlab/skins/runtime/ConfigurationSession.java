package com.naocraftlab.skins.runtime;

import com.naocraftlab.skins.core.config.ClientConfiguration;
import com.naocraftlab.skins.core.config.ServerConfiguration;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

public record ConfigurationSession(
        ClientConfiguration client,
        Optional<ServerConfiguration> server,
        ServerConfigurationAccess serverAccess,
        Path activeDataRoot,
        Path defaultDataRoot) {
    public ConfigurationSession {
        Objects.requireNonNull(client, "client");
        Objects.requireNonNull(server, "server");
        Objects.requireNonNull(serverAccess, "serverAccess");
        Objects.requireNonNull(activeDataRoot, "activeDataRoot");
        Objects.requireNonNull(defaultDataRoot, "defaultDataRoot");
        if (!serverAccess.visible() && server.isPresent()) {
            throw new IllegalArgumentException("Remote session cannot contain server configuration");
        }
    }
}

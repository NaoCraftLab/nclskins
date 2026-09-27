package com.naocraftlab.skins.runtime;

import com.naocraftlab.skins.core.config.ClientConfiguration;
import com.naocraftlab.skins.core.config.ServerConfiguration;
import java.nio.file.Path;
import java.util.Optional;

public interface ConfigurationUseCases {
    ClientConfiguration client();
    Path activeDataRoot();
    ConfigurationSession openSession(ServerConfigurationAccess access);
    void save(ServerConfigurationAccess access, ClientConfiguration client,
            Optional<ServerConfiguration> server);
}

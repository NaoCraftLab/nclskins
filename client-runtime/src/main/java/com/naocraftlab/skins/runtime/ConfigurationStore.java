package com.naocraftlab.skins.runtime;

import com.naocraftlab.skins.core.config.ClientConfiguration;
import com.naocraftlab.skins.core.config.ServerConfiguration;

public interface ConfigurationStore {
    ClientConfiguration loadClient();
    void saveClient(ClientConfiguration configuration);
    ServerConfiguration loadServer();
    void saveServer(ServerConfiguration configuration);
}

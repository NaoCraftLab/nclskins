package com.naocraftlab.skins.server.vanilla;

import com.naocraftlab.skins.server.ConnectionKey;
import com.naocraftlab.skins.server.PublicationRequest;
import java.util.List;


public interface PlayerInfoTransport {
    void removeProfiles(ConnectionKey recipient, List<PublicationRequest> actors);

    void initializeProfiles(ConnectionKey recipient, List<PublicationRequest> actors);
}

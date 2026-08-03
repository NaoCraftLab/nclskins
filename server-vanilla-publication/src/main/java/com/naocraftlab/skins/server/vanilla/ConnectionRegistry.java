package com.naocraftlab.skins.server.vanilla;

import com.naocraftlab.skins.server.ConnectionKey;
import com.naocraftlab.skins.server.PublicationRequest;
import java.util.List;


public interface ConnectionRegistry {
    boolean isCurrent(PublicationRequest actor);

    boolean isCurrent(ConnectionKey connection);

    List<ConnectionKey> recipients();


    boolean isProfileVisible(ConnectionKey recipient, PublicationRequest actor);
}

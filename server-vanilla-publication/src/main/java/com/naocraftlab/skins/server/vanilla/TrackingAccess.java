package com.naocraftlab.skins.server.vanilla;

import com.naocraftlab.skins.server.ConnectionKey;
import com.naocraftlab.skins.server.PublicationRequest;
import java.util.List;


public interface TrackingAccess {
    List<ConnectionKey> snapshotObservers(PublicationRequest actor);


    boolean untrack(PublicationRequest actor, ConnectionKey observer);

    void retrack(PublicationRequest actor, ConnectionKey observer);
}

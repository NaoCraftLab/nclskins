package com.naocraftlab.skins.server.vanilla;

import com.naocraftlab.skins.server.PublicationRequest;


public interface ProfileAccess {
    LiveProfileTextures captureCurrent(PublicationRequest actor);

    void install(PublicationRequest actor);
}

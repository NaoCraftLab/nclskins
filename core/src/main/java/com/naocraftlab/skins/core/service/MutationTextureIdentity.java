package com.naocraftlab.skins.core.service;

import java.io.IOException;
import java.net.URI;

public interface MutationTextureIdentity {
    boolean cachedContentMatches(URI texture, String sha256) throws IOException;
}

package com.naocraftlab.skins.runtime.update;

import java.net.URI;
import java.util.Objects;

public record UpdateCandidate(NclVersion version, UpdateChannel channel, URI url) {
    public UpdateCandidate {
        Objects.requireNonNull(version, "version");
        Objects.requireNonNull(channel, "channel");
        Objects.requireNonNull(url, "url");
    }
}

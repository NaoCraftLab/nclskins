package com.naocraftlab.skins.runtime.update;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public record UpdateCatalog(
        Map<NclVersion, Release> releases,
        Map<String, Target> targets) {
    public UpdateCatalog {
        releases = Map.copyOf(Objects.requireNonNull(releases, "releases"));
        targets = Map.copyOf(Objects.requireNonNull(targets, "targets"));
    }

    public record Release(UpdateChannel channel, URI url) {
        public Release {
            Objects.requireNonNull(channel, "channel");
            Objects.requireNonNull(url, "url");
        }
    }

    public record Target(String loader, String minecraftVersion, List<NclVersion> versions) {
        public Target {
            Objects.requireNonNull(loader, "loader");
            Objects.requireNonNull(minecraftVersion, "minecraftVersion");
            versions = List.copyOf(Objects.requireNonNull(versions, "versions"));
        }
    }
}

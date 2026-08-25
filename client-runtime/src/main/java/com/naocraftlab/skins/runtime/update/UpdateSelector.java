package com.naocraftlab.skins.runtime.update;

import java.util.Comparator;
import java.util.Objects;
import java.util.Optional;

public final class UpdateSelector {
    public Optional<UpdateCandidate> select(
            UpdateCatalog catalog,
            String targetId,
            String currentVersion,
            UpdateChannel allowedChannel) {
        Objects.requireNonNull(catalog, "catalog");
        Objects.requireNonNull(targetId, "targetId");
        Objects.requireNonNull(allowedChannel, "allowedChannel");
        NclVersion current = NclVersion.parse(currentVersion);
        UpdateCatalog.Target target = catalog.targets().get(targetId);
        if (target == null) {
            return Optional.empty();
        }
        return target.versions().stream()
                .filter(version -> version.isNewerThan(current))
                .filter(version -> allowedChannel.allows(version.channel()))
                .max(Comparator.naturalOrder())
                .map(version -> {
                    UpdateCatalog.Release release = catalog.releases().get(version);
                    return new UpdateCandidate(version, release.channel(), release.url());
                });
    }
}

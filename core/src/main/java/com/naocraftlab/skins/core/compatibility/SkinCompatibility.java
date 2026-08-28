package com.naocraftlab.skins.core.compatibility;

import java.util.List;
import java.util.Objects;


public record SkinCompatibility(
        SkinCompatibilityStatus status,
        List<SkinFeature> supportedFeatures,
        List<SkinConflictReason> activeConflicts) {
    public SkinCompatibility {
        Objects.requireNonNull(status, "status");
        supportedFeatures = List.copyOf(Objects.requireNonNull(supportedFeatures, "supportedFeatures"));
        activeConflicts = List.copyOf(Objects.requireNonNull(activeConflicts, "activeConflicts"));
        if ((status == SkinCompatibilityStatus.INCOMPATIBLE) != !activeConflicts.isEmpty()) {
            throw new IllegalArgumentException("incompatible status and active conflicts must agree");
        }
        if (status == SkinCompatibilityStatus.ORDINARY && !supportedFeatures.isEmpty()) {
            throw new IllegalArgumentException("ordinary compatibility cannot contain features");
        }
    }
}

package com.naocraftlab.skins.core.compatibility;

import java.util.List;
import java.util.Objects;


public record SkinFeatureEvidence(
        List<SkinFeature> supportedFeatures,
        List<SkinConflictReason> potentialConflicts) {
    public static final SkinFeatureEvidence ORDINARY = new SkinFeatureEvidence(List.of(), List.of());

    public SkinFeatureEvidence {
        supportedFeatures = List.copyOf(Objects.requireNonNull(supportedFeatures, "supportedFeatures"));
        potentialConflicts = List.copyOf(Objects.requireNonNull(potentialConflicts, "potentialConflicts"));
        if (supportedFeatures.stream().distinct().count() != supportedFeatures.size()) {
            throw new IllegalArgumentException("supportedFeatures must not contain duplicates");
        }
        if (potentialConflicts.stream().distinct().count() != potentialConflicts.size()) {
            throw new IllegalArgumentException("potentialConflicts must not contain duplicates");
        }
    }

    public boolean isOrdinary() {
        return supportedFeatures.isEmpty() && potentialConflicts.isEmpty();
    }
}

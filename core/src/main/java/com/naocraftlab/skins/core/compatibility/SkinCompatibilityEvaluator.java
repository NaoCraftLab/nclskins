package com.naocraftlab.skins.core.compatibility;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;


public final class SkinCompatibilityEvaluator {
    public SkinCompatibility evaluate(
            SkinFeatureEvidence evidence,
            SkinExtensionEnvironment environment) {
        Objects.requireNonNull(evidence, "evidence");
        Objects.requireNonNull(environment, "environment");
        List<SkinConflictReason> active = new ArrayList<>();
        boolean missingExpressiveRuntime = expressiveState(
                environment, SkinConsumerState.MISSING_PREREQUISITE);
        if (missingExpressiveRuntime) {
            active.add(SkinConflictReason.MISSING_EXPRESSIVE_RUNTIME);
        }
        for (SkinConflictReason reason : evidence.potentialConflicts()) {
            for (SkinConsumer consumer : SkinConsumer.values()) {
                if (reason.affects(consumer)
                        && environment.state(consumer) == SkinConsumerState.ACTIVE) {
                    active.add(reason);
                    break;
                }
            }
        }
        SkinCompatibilityStatus status = !active.isEmpty()
                ? SkinCompatibilityStatus.INCOMPATIBLE
                : evidence.supportedFeatures().isEmpty()
                        ? SkinCompatibilityStatus.ORDINARY
                        : SkinCompatibilityStatus.EXTENDED;
        return new SkinCompatibility(status, evidence.supportedFeatures(), active);
    }

    private static boolean expressiveState(
            SkinExtensionEnvironment environment, SkinConsumerState state) {
        return environment.state(SkinConsumer.FRESH_MOVES) == state
                || environment.state(SkinConsumer.JUST_EXPRESSIONS) == state;
    }
}

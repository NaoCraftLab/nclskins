package com.naocraftlab.skins.core.service;

import com.naocraftlab.skins.core.api.ApiFailureKind;
import com.naocraftlab.skins.core.model.MutationResult;
import com.naocraftlab.skins.core.model.RemoteProfile;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;


public record PresetApplicationOutcome(
        MutationResult result,
        ApplicationPhase phase,
        RemoteProfile beforeProfile,
        RemoteProfile afterProfile,
        AppliedAppearance appliedAppearance,
        ApiFailureKind failureKind,
        Set<RecoveryAction> recoveryActions,
        RemoteAppearanceImpact remoteAppearanceImpact,
        String userMessage) {

    public PresetApplicationOutcome {
        Objects.requireNonNull(result, "result");
        Objects.requireNonNull(phase, "phase");
        recoveryActions = Set.copyOf(Objects.requireNonNull(recoveryActions, "recoveryActions"));
        Objects.requireNonNull(remoteAppearanceImpact, "remoteAppearanceImpact");
        Objects.requireNonNull(userMessage, "userMessage");
    }


    public PresetApplicationOutcome(
            MutationResult result,
            ApplicationPhase phase,
            RemoteProfile beforeProfile,
            RemoteProfile afterProfile,
            AppliedAppearance appliedAppearance,
            ApiFailureKind failureKind,
            Set<RecoveryAction> recoveryActions,
            String userMessage) {
        this(
                result,
                phase,
                beforeProfile,
                afterProfile,
                appliedAppearance,
                failureKind,
                recoveryActions,
                conservativeRemoteImpact(result),
                userMessage);
    }

    public Optional<RemoteProfile> optionalBeforeProfile() {
        return Optional.ofNullable(beforeProfile);
    }

    public Optional<AppliedAppearance> optionalAppliedAppearance() {
        return Optional.ofNullable(appliedAppearance);
    }

    public Optional<ApiFailureKind> optionalFailureKind() {
        return Optional.ofNullable(failureKind);
    }

    private static RemoteAppearanceImpact conservativeRemoteImpact(MutationResult result) {
        Objects.requireNonNull(result, "result");
        return switch (result) {
            case APPLIED, PARTIAL -> RemoteAppearanceImpact.CONFIRMED_CHANGED;
            case UNKNOWN -> RemoteAppearanceImpact.UNCERTAIN;
            case FAILED, SESSION_EXPIRED -> RemoteAppearanceImpact.NONE;
        };
    }
}

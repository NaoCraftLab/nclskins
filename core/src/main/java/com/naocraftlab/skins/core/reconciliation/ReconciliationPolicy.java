package com.naocraftlab.skins.core.reconciliation;

import com.naocraftlab.skins.core.api.ApiFailureKind;
import com.naocraftlab.skins.core.model.AppearanceSyncStatus;
import java.util.Objects;
import java.util.UUID;

public final class ReconciliationPolicy {
    public enum Trigger { LOCAL_INTENT, PROCESS_START, RECONNECT, RATE_LIMIT_EXPIRED, EXPLICIT_RETRY, SESSION_REFRESHED }
    public enum SessionFailure { IDENTITY_CHANGED, TOKEN_UNAVAILABLE, SOURCE_FAILED }
    public enum Action { STALE, CACHED, READ, SETTLE_OFFICIAL, SETTLE_UNKNOWN, ATTEMPT_FULL, ATTEMPT_CAPE }
    public enum Observation { MATCH, SKIN_MATCH, DIFFERENT, UNRESOLVED }
    public enum Validation { MANUAL, TOKEN_UNAVAILABLE, FRESH, CACHED, TRANSIENT }
    public record Revision(UUID account, long intent, long skinActivation, long capeActivation,
            boolean skinEnabled, boolean capeEnabled) {
        public Revision { Objects.requireNonNull(account); }
    }
    public record Checkpoint(Trigger trigger, Revision expected, Revision current, boolean hasIntent,
            AppearanceSyncStatus status, boolean cooldown, boolean automaticAllowed,
            ApiFailureKind cachedFailure, boolean identityMismatch) {
        public Checkpoint {
            Objects.requireNonNull(trigger); Objects.requireNonNull(current); Objects.requireNonNull(status);
        }
    }
    public record Observed(Trigger trigger, boolean sameDelivery, boolean hasIntent,
            AppearanceSyncStatus status, boolean valid, ApiFailureKind failure, Observation observation) {
        public Observed { Objects.requireNonNull(trigger); Objects.requireNonNull(status); Objects.requireNonNull(observation); }
    }

    public boolean explicit(Trigger trigger) {
        return trigger == Trigger.RATE_LIMIT_EXPIRED || trigger == Trigger.EXPLICIT_RETRY
                || trigger == Trigger.SESSION_REFRESHED;
    }

    public Action checkpoint(Checkpoint input) {
        if (input.expected() != null && !input.expected().equals(input.current())) return Action.STALE;
        if (!input.current().skinEnabled() && !input.current().capeEnabled()) {
            return input.hasIntent() ? Action.SETTLE_OFFICIAL : Action.CACHED;
        }
        if (input.cooldown()) return Action.CACHED;
        boolean reconnect = input.trigger() == Trigger.RECONNECT
                && input.cachedFailure() == ApiFailureKind.TOKEN_UNAVAILABLE;
        if (!explicit(input.trigger()) && !reconnect && !input.automaticAllowed()) {
            return input.hasIntent() && (input.status() == AppearanceSyncStatus.ATTEMPTING
                    || input.identityMismatch() && input.status() == AppearanceSyncStatus.PENDING)
                    ? Action.SETTLE_UNKNOWN : Action.CACHED;
        }
        if (!explicit(input.trigger()) && terminalCheckpoint(input.hasIntent(), input.status())) return Action.CACHED;
        return Action.READ;
    }

    public boolean terminalCheckpoint(boolean hasIntent, AppearanceSyncStatus status) {
        return hasIntent && (status == AppearanceSyncStatus.OFFICIAL
                || status == AppearanceSyncStatus.PARTIAL || status == AppearanceSyncStatus.UNKNOWN);
    }

    public Validation validation(Trigger trigger, AppearanceSyncStatus status,
            ApiFailureKind cachedFailure, boolean startupObserved) {
        return switch (trigger) {
            case RATE_LIMIT_EXPIRED, EXPLICIT_RETRY -> Validation.MANUAL;
            case SESSION_REFRESHED -> Validation.TOKEN_UNAVAILABLE;
            case RECONNECT -> status == AppearanceSyncStatus.ATTEMPTING ? Validation.FRESH
                    : cachedFailure == ApiFailureKind.TOKEN_UNAVAILABLE ? Validation.TOKEN_UNAVAILABLE : Validation.TRANSIENT;
            case PROCESS_START -> startupObserved ? Validation.CACHED : Validation.TRANSIENT;
            case LOCAL_INTENT -> status == AppearanceSyncStatus.ATTEMPTING ? Validation.FRESH : Validation.TRANSIENT;
        };
    }

    public Action observed(Observed input) {
        if (!input.sameDelivery() || !input.hasIntent()) return Action.CACHED;
        if (input.status() == AppearanceSyncStatus.ATTEMPTING) {
            return input.valid() && input.observation() == Observation.MATCH ? Action.SETTLE_OFFICIAL : Action.SETTLE_UNKNOWN;
        }
        if (!input.valid()) {
            return input.status() == AppearanceSyncStatus.PENDING && !automaticRetry(input.failure())
                    ? Action.SETTLE_UNKNOWN : Action.CACHED;
        }
        if (input.status() == AppearanceSyncStatus.UNKNOWN) {
            if (!explicit(input.trigger())) return Action.CACHED;
            return switch (input.observation()) {
                case MATCH -> Action.SETTLE_OFFICIAL;
                case UNRESOLVED -> Action.CACHED;
                case DIFFERENT, SKIN_MATCH -> Action.ATTEMPT_FULL;
            };
        }
        if (input.status() == AppearanceSyncStatus.PARTIAL) {
            if (!explicit(input.trigger())) return Action.CACHED;
            return switch (input.observation()) {
                case MATCH -> Action.SETTLE_OFFICIAL;
                case SKIN_MATCH -> Action.ATTEMPT_CAPE;
                case DIFFERENT, UNRESOLVED -> Action.CACHED;
            };
        }
        if (input.status() != AppearanceSyncStatus.PENDING) return Action.CACHED;
        return switch (input.observation()) {
            case MATCH -> Action.SETTLE_OFFICIAL;
            case SKIN_MATCH -> Action.ATTEMPT_CAPE;
            case DIFFERENT, UNRESOLVED -> Action.ATTEMPT_FULL;
        };
    }

    public Action sessionFailure(AppearanceSyncStatus status, boolean sameIntent, SessionFailure failure) {
        Objects.requireNonNull(status);
        Objects.requireNonNull(failure);
        return sameIntent && (status == AppearanceSyncStatus.ATTEMPTING
                || failure == SessionFailure.IDENTITY_CHANGED && status == AppearanceSyncStatus.PENDING)
                ? Action.SETTLE_UNKNOWN : Action.CACHED;
    }

    public static boolean automaticRetry(ApiFailureKind failure) {
        return failure == ApiFailureKind.TOKEN_UNAVAILABLE || failure == ApiFailureKind.NETWORK
                || failure == ApiFailureKind.SERVER_ERROR || failure == ApiFailureKind.RATE_LIMITED;
    }
}

package com.naocraftlab.skins.core.reconciliation;

import com.naocraftlab.skins.core.api.ApiFailureKind;
import com.naocraftlab.skins.core.model.AppearanceSyncStatus;
import org.junit.jupiter.api.Test;
import java.util.UUID;
import static com.naocraftlab.skins.core.reconciliation.ReconciliationPolicy.*;
import static com.naocraftlab.skins.core.model.AppearanceSyncStatus.*;
import static org.junit.jupiter.api.Assertions.*;

class ReconciliationPolicyTest {
    private final ReconciliationPolicy policy = new ReconciliationPolicy();
    private final Revision current = new Revision(new UUID(0, 1), 7, 2, 3, true, true);

    @Test
    void automaticTerminalCheckpointsNeverReadAndExplicitOfficialOnlyValidates() {
        for (Trigger trigger : Trigger.values()) {
            for (AppearanceSyncStatus status : new AppearanceSyncStatus[]{OFFICIAL, PARTIAL, UNKNOWN}) {
                var input = new Checkpoint(trigger, current, current, true, status, false, true, null, false);
                Action expected = switch (trigger) {
                    case LOCAL_INTENT, PROCESS_START, RECONNECT -> Action.CACHED;
                    case EXPLICIT_RETRY, RATE_LIMIT_EXPIRED, SESSION_REFRESHED -> Action.READ;
                };
                assertEquals(expected, policy.checkpoint(input));
                assertEquals(expected, policy.checkpoint(input));
            }
            assertEquals(Action.CACHED, policy.observed(new Observed(trigger, true, true,
                    OFFICIAL, true, null, Observation.DIFFERENT)));
        }
    }

    @Test
    void recoveryTableDistinguishesPartialUnknownAndInterruptedWrites() {
        Action[] pending = {Action.SETTLE_OFFICIAL, Action.ATTEMPT_CAPE, Action.ATTEMPT_FULL, Action.ATTEMPT_FULL};
        Action[] partial = {Action.SETTLE_OFFICIAL, Action.ATTEMPT_CAPE, Action.CACHED, Action.CACHED};
        Action[] unknown = {Action.SETTLE_OFFICIAL, Action.ATTEMPT_FULL, Action.ATTEMPT_FULL, Action.CACHED};
        for (Observation observation : Observation.values()) {
            int index = observation.ordinal();
            for (Trigger trigger : Trigger.values()) {
                assertEquals(pending[index], policy.observed(new Observed(trigger, true, true, PENDING, true, null, observation)));
                boolean explicit = trigger == Trigger.EXPLICIT_RETRY || trigger == Trigger.RATE_LIMIT_EXPIRED || trigger == Trigger.SESSION_REFRESHED;
                assertEquals(explicit ? partial[index] : Action.CACHED,
                        policy.observed(new Observed(trigger, true, true, PARTIAL, true, null, observation)));
                assertEquals(explicit ? unknown[index] : Action.CACHED,
                        policy.observed(new Observed(trigger, true, true, UNKNOWN, true, null, observation)));
                assertEquals(observation == Observation.MATCH ? Action.SETTLE_OFFICIAL : Action.SETTLE_UNKNOWN,
                        policy.observed(new Observed(trigger, true, true, ATTEMPTING, true, null, observation)));
            }
        }
    }

    @Test
    void revisionActivationAccountAndAvailabilityFenceBeforeReads() {
        for (Revision stale : new Revision[]{
                new Revision(current.account(), 6, 2, 3, true, true),
                new Revision(current.account(), 7, 1, 3, true, true),
                new Revision(current.account(), 7, 2, 2, true, true),
                new Revision(new UUID(0, 2), 7, 2, 3, true, true)}) {
            assertEquals(Action.STALE, policy.checkpoint(new Checkpoint(Trigger.EXPLICIT_RETRY,
                    stale, current, true, PENDING, false, true, null, false)));
        }
        assertEquals(Action.CACHED, policy.checkpoint(new Checkpoint(Trigger.EXPLICIT_RETRY,
                current, current, true, PENDING, true, true, null, false)));
        assertEquals(Action.CACHED, policy.checkpoint(new Checkpoint(Trigger.LOCAL_INTENT,
                current, current, true, PENDING, false, false, ApiFailureKind.TOKEN_UNAVAILABLE, false)));
        assertEquals(Action.READ, policy.checkpoint(new Checkpoint(Trigger.RECONNECT,
                current, current, true, PENDING, false, false, ApiFailureKind.TOKEN_UNAVAILABLE, false)));
        assertEquals(Action.SETTLE_UNKNOWN, policy.checkpoint(new Checkpoint(Trigger.LOCAL_INTENT,
                current, current, true, PENDING, false, false, ApiFailureKind.TOKEN_UNAVAILABLE, true)));
        assertEquals(Action.CACHED, policy.observed(new Observed(Trigger.EXPLICIT_RETRY, false, true,
                PENDING, true, null, Observation.DIFFERENT)));
    }

    @Test
    void sessionAcquisitionFailureNeverMakesUnsentPendingUncertain() {
        for (SessionFailure failure : SessionFailure.values()) {
            for (AppearanceSyncStatus status : AppearanceSyncStatus.values()) {
                assertEquals(Action.CACHED, policy.sessionFailure(status, false, failure));
                Action expected = status == ATTEMPTING || status == PENDING && failure == SessionFailure.IDENTITY_CHANGED
                        ? Action.SETTLE_UNKNOWN : Action.CACHED;
                assertEquals(expected, policy.sessionFailure(status, true, failure));
            }
        }
    }

    @Test
    void validationModesPreserveStartupReuseAndOrphanObservation() {
        assertEquals(Validation.CACHED, policy.validation(Trigger.PROCESS_START, PENDING, null, true));
        assertEquals(Validation.TRANSIENT, policy.validation(Trigger.PROCESS_START, PENDING, null, false));
        assertEquals(Validation.FRESH, policy.validation(Trigger.RECONNECT, ATTEMPTING, ApiFailureKind.TOKEN_UNAVAILABLE, false));
        assertEquals(Validation.TOKEN_UNAVAILABLE, policy.validation(Trigger.RECONNECT, PENDING, ApiFailureKind.TOKEN_UNAVAILABLE, false));
        assertEquals(Validation.MANUAL, policy.validation(Trigger.EXPLICIT_RETRY, OFFICIAL, null, false));
        assertEquals(Action.CACHED, policy.observed(new Observed(Trigger.RECONNECT, true, true, PENDING,
                false, ApiFailureKind.TOKEN_UNAVAILABLE, Observation.UNRESOLVED)));
        assertEquals(Action.SETTLE_UNKNOWN, policy.observed(new Observed(Trigger.RECONNECT, true, true, ATTEMPTING,
                false, ApiFailureKind.NETWORK, Observation.UNRESOLVED)));
    }
}

package com.naocraftlab.skins.runtime;

import com.naocraftlab.skins.client.GameSessionTokenSource;
import com.naocraftlab.skins.core.model.*;
import com.naocraftlab.skins.core.provider.AppearanceProviders;
import com.naocraftlab.skins.core.reconciliation.ReconciliationPolicy;
import com.naocraftlab.skins.core.reconciliation.ReconciliationPolicy.*;
import com.naocraftlab.skins.core.service.*;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static com.naocraftlab.skins.runtime.AccountReconciliationPort.*;
import static org.junit.jupiter.api.Assertions.*;

class AccountReconciliationExecutorTest {
    @Test
    void controlledEffectsPreserveTerminalCheckpointCounts() throws Exception {
        for (AppearanceSyncStatus status : List.of(AppearanceSyncStatus.OFFICIAL, AppearanceSyncStatus.PARTIAL, AppearanceSyncStatus.UNKNOWN)) {
            for (Trigger trigger : List.of(Trigger.LOCAL_INTENT, Trigger.PROCESS_START, Trigger.RECONNECT)) {
                var effects = new Effects(status, Observation.DIFFERENT);
                new AccountReconciliationExecutor().execute(effects, null, trigger);
                assertEquals(List.of(0, 0, 0, 0), effects.counts());
            }
        }
        var official = new Effects(AppearanceSyncStatus.OFFICIAL, Observation.DIFFERENT);
        new AccountReconciliationExecutor().execute(official, null, Trigger.EXPLICIT_RETRY);
        assertEquals(List.of(1, 1, 0, 0), official.counts());
    }

    @Test
    void controlledEffectsPreservePartialRecoveryAndObservationOnlySettlement() throws Exception {
        var partial = new Effects(AppearanceSyncStatus.PARTIAL, Observation.SKIN_MATCH);
        new AccountReconciliationExecutor().execute(partial, null, Trigger.EXPLICIT_RETRY);
        assertEquals(List.of(1, 1, 0, 1), partial.counts());
        assertEquals(0, partial.settlements);
        var match = new Effects(AppearanceSyncStatus.UNKNOWN, Observation.MATCH);
        new AccountReconciliationExecutor().execute(match, null, Trigger.EXPLICIT_RETRY);
        assertEquals(List.of(1, 1, 0, 0), match.counts());
        assertEquals(1, match.settlements);
        assertEquals(AppearanceSyncStatus.OFFICIAL, match.state.syncStatus());
        var orphan = new Effects(AppearanceSyncStatus.ATTEMPTING, Observation.DIFFERENT);
        new AccountReconciliationExecutor().execute(orphan, null, Trigger.RECONNECT);
        assertEquals(List.of(1, 1, 0, 0), orphan.counts());
        assertEquals(AppearanceSyncStatus.UNKNOWN, orphan.state.syncStatus());
    }

    @Test
    void observationSupersessionDiscardsOldDecisionBeforeMutation() throws Exception {
        var effects = new Effects(AppearanceSyncStatus.PENDING, Observation.DIFFERENT);
        effects.supersede = true;
        var result = new AccountReconciliationExecutor().execute(effects, null, Trigger.RECONNECT).orElseThrow();
        assertEquals(2, result.appearance().intentRevision());
        assertEquals(List.of(1, 1, 0, 0), effects.counts());
        assertEquals(0, effects.settlements);
    }

    @Test
    void blockedOrphanSettlementPrecedesAccountSnapshotRead() throws Exception {
        var effects = new Effects(AppearanceSyncStatus.ATTEMPTING, Observation.UNRESOLVED);
        effects.automaticAllowed = false;
        new AccountReconciliationExecutor().execute(effects, null, Trigger.LOCAL_INTENT);
        assertEquals(List.of("settle", "observed"), effects.events);
        assertEquals(AppearanceSyncStatus.UNKNOWN, effects.state.syncStatus());
        assertEquals(List.of(0, 0, 0, 0), effects.counts());
    }

    private static final class Effects implements AccountReconciliationEffects {
        private final UUID id = new UUID(0, 1);
        private final Observation observation;
        private AccountAppearanceState state;
        private boolean supersede;
        private boolean automaticAllowed = true;
        private final java.util.List<String> events = new java.util.ArrayList<>();
        private int scopes, reads, full, capes, settlements;
        private Effects(AppearanceSyncStatus status, Observation observation) {
            this.observation = observation;
            this.state = state(1, status);
        }
        private AccountAppearanceState state(long revision, AppearanceSyncStatus status) {
            return new AccountAppearanceState(AccountAppearanceState.CURRENT_SCHEMA_VERSION, id, revision,
                    null, null, null, null, status, status == AppearanceSyncStatus.OFFICIAL ? revision : 0, Instant.EPOCH);
        }
        List<Integer> counts() { return List.of(scopes, reads, full, capes); }
        public AccountAppearanceState load() { return state; }
        public SessionValidation cached() {
            return new SessionValidation(SessionStatus.VALID, new GameSessionTokenSource.SessionIdentity(id, "Fixture"),
                    new RemoteProfile(id, "Fixture", List.of(), List.of(), java.util.Set.of()), null, "Valid");
        }
        public boolean automaticAllowed() { return automaticAllowed; }
        public boolean cooldown() { return false; }
        public boolean startupObserved() { return false; }
        public SessionValidation validate(ReconciliationPolicy.Validation mode) { reads++; return cached(); }
        public Optional<ReconciliationResult> withSession(AccountAppearanceState checkpoint, Scoped operation)
                throws java.io.IOException, com.naocraftlab.skins.core.png.PngValidationException {
            scopes++; return operation.execute(this);
        }
        public ObservedAccount observe(SessionValidation validation, AppearanceProviders expected) {
            if (supersede) state = state(2, AppearanceSyncStatus.PENDING);
            return observed();
        }
        public ObservedAccount observed() { events.add("observed"); return new ObservedAccount(AccountState.empty(id, Instant.EPOCH), Optional.empty()); }
        public Observation compare(AccountAppearanceState state, SessionValidation validation) { return observation; }
        public AccountAppearanceStore.AppearanceIntentUpdate normalizeCape(AccountAppearanceState state) { throw new AssertionError("Unexpected normalization"); }
        public AccountAppearanceState settle(AccountAppearanceState expected, AppearanceSyncStatus status) {
            events.add("settle"); settlements++; state = state(expected.intentRevision(), status); return state;
        }
        public ReconciliationResult full(AccountAppearanceState state, SessionValidation validation) { full++; return result(state, validation, observed()); }
        public ReconciliationResult cape(AccountAppearanceState state, SessionValidation validation) { capes++; return result(state, validation, observed()); }
        public ReconciliationResult result(AccountAppearanceState state, SessionValidation validation, ObservedAccount observed) {
            return new ReconciliationResult(observed.account(), validation, Optional.empty(), new DurableAppearance(id,
                    state.intentRevision(), state.syncStatus(), Optional.empty(), Optional.empty(), state.optionalOuterLayerVisibility(),
                    state.providers()), Optional.empty());
        }
    }
}

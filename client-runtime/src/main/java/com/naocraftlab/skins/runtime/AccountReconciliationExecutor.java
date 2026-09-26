package com.naocraftlab.skins.runtime;

import com.naocraftlab.skins.core.model.AccountAppearanceState;
import com.naocraftlab.skins.core.model.AppearanceSyncStatus;
import com.naocraftlab.skins.core.png.PngValidationException;
import com.naocraftlab.skins.core.provider.BuiltinProvider;
import com.naocraftlab.skins.core.reconciliation.ReconciliationPolicy;
import com.naocraftlab.skins.core.reconciliation.ReconciliationPolicy.*;
import com.naocraftlab.skins.core.service.SessionStatus;
import com.naocraftlab.skins.core.service.SessionValidation;
import java.io.IOException;
import java.util.Optional;
import static com.naocraftlab.skins.runtime.AccountReconciliationPort.*;

final class AccountReconciliationExecutor {
    private final ReconciliationPolicy policy = new ReconciliationPolicy();

    Optional<ReconciliationResult> execute(AccountReconciliationEffects effects,
            ReconciliationKey expected, Trigger trigger) throws IOException, PngValidationException {
        AccountAppearanceState checkpoint = effects.load();
        Revision current = revision(checkpoint);
        Revision requested = expected == null ? null : new Revision(expected.accountId(), expected.intentRevision(),
                expected.skinActivation(), expected.capeActivation(), current.skinEnabled(), current.capeEnabled());
        SessionValidation cached = effects.cached();
        Action decision = policy.checkpoint(new Checkpoint(trigger, requested, current, checkpoint.hasIntent(),
                checkpoint.syncStatus(), effects.cooldown(), effects.automaticAllowed(), cached.failureKind(),
                cached.status() == SessionStatus.UUID_MISMATCH));
        if (decision == Action.STALE) return Optional.empty();
        if (decision != Action.READ) {
            AccountAppearanceState settled = switch (decision) {
                case SETTLE_OFFICIAL -> effects.settle(checkpoint, AppearanceSyncStatus.OFFICIAL);
                case SETTLE_UNKNOWN -> effects.settle(checkpoint, AppearanceSyncStatus.UNKNOWN);
                default -> checkpoint;
            };
            return Optional.of(effects.result(settled, cached, effects.observed()));
        }
        return effects.withSession(checkpoint, scoped -> observe(scoped, checkpoint, trigger));
    }

    private Optional<ReconciliationResult> observe(AccountReconciliationEffects effects,
            AccountAppearanceState checkpoint, Trigger trigger) throws IOException, PngValidationException {
        boolean explicit = policy.explicit(trigger);
        SessionValidation validation = explicit ? validate(effects, trigger, checkpoint.syncStatus()) : null;
        if (checkpoint.hasIntent() && (checkpoint.syncStatus() == AppearanceSyncStatus.OFFICIAL
                || !explicit && policy.terminalCheckpoint(true, checkpoint.syncStatus()))) {
            return Optional.of(effects.result(checkpoint, validation == null ? effects.cached() : validation, effects.observed()));
        }
        if (validation == null) validation = validate(effects, trigger, checkpoint.syncStatus());
        var observed = effects.observe(validation, checkpoint.providers());
        AccountAppearanceState state = effects.load();
        if (!revision(state).equals(revision(checkpoint)) || !state.hasIntent()) {
            return Optional.of(effects.result(state, validation, observed));
        }
        boolean valid = validation.valid() && validation.profile() != null;
        if (state.syncStatus() != AppearanceSyncStatus.ATTEMPTING && valid
                && state.providers().cape().enabled(BuiltinProvider.MINECRAFT)
                && state.capeId() != null && state.syncStatus() != AppearanceSyncStatus.OFFICIAL
                && !validation.profile().ownsCape(state.capeId())) {
            var normalized = effects.normalizeCape(state);
            state = normalized.state();
            if (!normalized.updated()) return Optional.of(effects.result(state, validation, observed));
        }
        Action decision = policy.observed(new Observed(trigger, true, true, state.syncStatus(), valid,
                validation.failureKind(), valid && (state.syncStatus() == AppearanceSyncStatus.PENDING
                        || state.syncStatus() == AppearanceSyncStatus.ATTEMPTING
                        || state.syncStatus() == AppearanceSyncStatus.PARTIAL
                        || state.syncStatus() == AppearanceSyncStatus.UNKNOWN)
                        ? effects.compare(state, validation) : Observation.UNRESOLVED));
        return Optional.of(finish(effects, state, validation, observed, decision));
    }

    private SessionValidation validate(AccountReconciliationEffects effects, Trigger trigger, AppearanceSyncStatus status) {
        return effects.validate(policy.validation(trigger, status, effects.cached().failureKind(), effects.startupObserved()));
    }

    private ReconciliationResult finish(AccountReconciliationEffects effects, AccountAppearanceState state,
            SessionValidation validation, AccountReconciliationEffects.ObservedAccount observed, Action decision)
            throws IOException, PngValidationException {
        return switch (decision) {
            case SETTLE_OFFICIAL, SETTLE_UNKNOWN -> effects.result(effects.settle(state,
                    decision == Action.SETTLE_OFFICIAL ? AppearanceSyncStatus.OFFICIAL : AppearanceSyncStatus.UNKNOWN), validation, observed);
            case ATTEMPT_FULL -> effects.full(state, validation);
            case ATTEMPT_CAPE -> effects.cape(state, validation);
            default -> effects.result(state, validation, observed);
        };
    }

    private static Revision revision(AccountAppearanceState state) {
        return new Revision(state.accountId(), state.intentRevision(),
                state.providers().skin().minecraftDelivery().activation(), state.providers().cape().minecraftDelivery().activation(),
                state.providers().skin().enabled(BuiltinProvider.MINECRAFT), state.providers().cape().enabled(BuiltinProvider.MINECRAFT));
    }
}

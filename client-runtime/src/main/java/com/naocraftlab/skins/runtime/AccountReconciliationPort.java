package com.naocraftlab.skins.runtime;

import com.naocraftlab.skins.core.reconciliation.ReconciliationPolicy.Trigger;
import com.naocraftlab.skins.client.GameSessionTokenSource;
import com.naocraftlab.skins.client.OuterLayerVisibility;
import com.naocraftlab.skins.core.model.AccountState;
import com.naocraftlab.skins.core.model.AppearanceSyncStatus;
import com.naocraftlab.skins.core.provider.AppearanceProviders;
import com.naocraftlab.skins.core.service.AppliedAppearance;
import com.naocraftlab.skins.core.service.PresetApplicationOutcome;
import com.naocraftlab.skins.core.service.SessionValidation;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public interface AccountReconciliationPort {
    GameSessionTokenSource.SessionIdentity sessionIdentity() throws Exception;
    Optional<DurableAppearance> durableAppearance() throws Exception;
    Optional<ReconciliationResult> reconcileAppearance(Trigger trigger) throws Exception;

    default Optional<ReconciliationResult> reconcileAppearance(
            ReconciliationKey expected, Trigger trigger) throws Exception {
        Objects.requireNonNull(expected, "expected");
        Objects.requireNonNull(trigger, "trigger");
        if (!reconciliationKey().filter(expected::equals).isPresent()) {
            return Optional.empty();
        }
        return reconcileAppearance(trigger);
    }

    default Optional<ReconciliationKey> reconciliationKey() throws Exception {
        UUID currentAccountId = sessionIdentity().profileId();
        Optional<DurableAppearance> durable = durableAppearance();
        if (durable.isPresent()
                && !durable.orElseThrow().accountId().equals(currentAccountId)) {
            return Optional.empty();
        }
        return Optional.of(durable
                .map(DurableAppearance::reconciliationKey)
                .orElseGet(() -> new ReconciliationKey(currentAccountId, 0)));
    }

    record ReconciliationKey(UUID accountId, long intentRevision, long skinActivation, long capeActivation) {
        public ReconciliationKey(UUID accountId, long intentRevision) {
            this(accountId, intentRevision, 0, 0);
        }

        public ReconciliationKey {
            Objects.requireNonNull(accountId, "accountId");
            if (intentRevision < 0) {
                throw new IllegalArgumentException("intentRevision must not be negative");
            }
        }
    }

    record DurableAppearance(
            UUID accountId,
            long intentRevision,
            AppearanceSyncStatus syncStatus,
            Optional<UUID> activePresetId,
            Optional<AppliedAppearance> localAppearance,
            Optional<OuterLayerVisibility> outerLayerVisibility,
            AppearanceProviders providers) {
        public DurableAppearance(
                UUID accountId,
                long intentRevision,
                AppearanceSyncStatus syncStatus,
                Optional<UUID> activePresetId,
                Optional<AppliedAppearance> localAppearance,
                Optional<OuterLayerVisibility> outerLayerVisibility) {
            this(
                    accountId,
                    intentRevision,
                    syncStatus,
                    activePresetId,
                    localAppearance,
                    outerLayerVisibility,
                    AppearanceProviders.initial());
        }

        public DurableAppearance {
            Objects.requireNonNull(providers, "providers");
            Objects.requireNonNull(accountId, "accountId");
            if (intentRevision < 0) {
                throw new IllegalArgumentException("intentRevision must not be negative");
            }
            Objects.requireNonNull(syncStatus, "syncStatus");
            activePresetId = Objects.requireNonNull(activePresetId, "activePresetId");
            localAppearance = Objects.requireNonNull(localAppearance, "localAppearance");
            outerLayerVisibility = Objects.requireNonNull(outerLayerVisibility, "outerLayerVisibility");
        }

        public ReconciliationKey reconciliationKey() {
            return new ReconciliationKey(accountId, intentRevision,
                    providers.skin().minecraftDelivery().activation(),
                    providers.cape().minecraftDelivery().activation());
        }
    }

    record ReconciliationResult(
            AccountState account,
            SessionValidation session,
            Optional<UUID> currentOfficialSkinId,
            DurableAppearance appearance,
            Optional<PresetApplicationOutcome> outcome) {
        public ReconciliationResult {
            Objects.requireNonNull(account, "account");
            Objects.requireNonNull(session, "session");
            currentOfficialSkinId = Objects.requireNonNull(currentOfficialSkinId, "currentOfficialSkinId");
            Objects.requireNonNull(appearance, "appearance");
            outcome = Objects.requireNonNull(outcome, "outcome");
            if (!account.accountId().equals(appearance.accountId())) {
                throw new IllegalArgumentException("reconciliation appearance belongs to another account");
            }
        }
    }

}

package com.naocraftlab.skins.runtime;

import com.naocraftlab.skins.core.api.ApiFailureKind;
import com.naocraftlab.skins.core.model.AccountAppearanceState;
import com.naocraftlab.skins.core.model.AppearanceSyncStatus;
import com.naocraftlab.skins.core.model.MutationResult;
import com.naocraftlab.skins.core.provider.*;
import com.naocraftlab.skins.core.service.AccountAppearanceStore;
import com.naocraftlab.skins.core.service.PresetApplicationOutcome;
import java.io.IOException;
import java.time.Clock;
import java.util.UUID;

final class AccountDeliveryService {
    private final AccountAppearanceStore storage;
    private final Clock clock;
    AccountDeliveryService(AccountAppearanceStore storage, Clock clock) {
        this.storage = java.util.Objects.requireNonNull(storage);
        this.clock = java.util.Objects.requireNonNull(clock);
    }
    AccountAppearanceState claimAppearance(
            UUID accountId,
            AccountAppearanceState expected,
            AppearanceSyncStatus expectedStatus,
            boolean claimSkin,
            boolean claimCape) throws IOException {
        return storage.updateAppearance(accountId, current -> {
            if (!sameDelivery(current, expected)
                    || current.syncStatus() != expectedStatus) {
                return current;
            }
            return withAppearanceStatus(
                    current,
                    AppearanceSyncStatus.ATTEMPTING,
                    current.settledRevision(),
                    new AppearanceProviders(
                            claimDelivery(current.providers().skin(), claimSkin),
                            claimDelivery(current.providers().cape(), claimCape)));
        });
    }

    AccountAppearanceState afterSessionFailure(UUID accountId, long expectedRevision,
            com.naocraftlab.skins.core.reconciliation.ReconciliationPolicy.SessionFailure failure) throws IOException {
        AccountAppearanceState current = storage.loadAppearance(accountId);
        var decision = new com.naocraftlab.skins.core.reconciliation.ReconciliationPolicy().sessionFailure(
                current.syncStatus(), current.intentRevision() == expectedRevision, failure);
        return decision == com.naocraftlab.skins.core.reconciliation.ReconciliationPolicy.Action.SETTLE_UNKNOWN
                ? settleAppearance(accountId, current.intentRevision(), current.syncStatus(), AppearanceSyncStatus.UNKNOWN)
                : current;
    }

    private static <T> ProviderChannel<T> claimDelivery(
            ProviderChannel<T> channel, boolean selected) {
        if (!selected
                || !channel.enabled(BuiltinProvider.MINECRAFT)
                || channel.minecraftDelivery().status() == ProviderDelivery.Status.UNKNOWN) {
            return channel;
        }
        return channel.settle(
                channel.minecraftDelivery(), ProviderDelivery.Status.ATTEMPTING, null);
    }

    AccountAppearanceState settleAppearance(
            UUID accountId,
            long revision,
            AppearanceSyncStatus expectedStatus,
            AppearanceSyncStatus status) throws IOException {
        return storage.updateAppearance(accountId, current -> {
            if (current.intentRevision() != revision || current.syncStatus() != expectedStatus) {
                return current;
            }
            long settled = status == AppearanceSyncStatus.OFFICIAL
                    ? revision
                    : current.settledRevision();
            return copyAppearanceStatus(current, status, settled);
        });
    }

    AccountAppearanceState settleAfterMutation(
            UUID accountId,
            AccountAppearanceState expected,
            AppearanceSyncStatus expectedStatus,
            AppearanceSyncStatus status,
            PresetApplicationOutcome outcome) throws IOException {
        try {
            return storage.updateAppearance(accountId, current -> {
                if (!sameDelivery(current, expected) || current.syncStatus() != expectedStatus) {
                    if (current.intentRevision() > expected.intentRevision()
                            && sameActivation(current, expected)
                            && current.syncStatus() == AppearanceSyncStatus.UNKNOWN
                            && (outcome.result() == MutationResult.APPLIED || outcome.result() == MutationResult.PARTIAL)) {
                        AppearanceProviders providers = new AppearanceProviders(
                                resumeSupersedingDelivery(
                                        current.providers().skin(), expected.providers().skin()),
                                resumeSupersedingDelivery(
                                        current.providers().cape(), expected.providers().cape()));
                        if (providers.equals(current.providers())) {
                            return current;
                        }
                        return withAppearanceStatus(
                                current,
                                supersedingRecoveryStatus(current, providers),
                                current.settledRevision(),
                                providers);
                    }
                    return current;
                }
                return copyAppearanceStatus(current, status,
                        status == AppearanceSyncStatus.OFFICIAL
                                ? current.intentRevision() : current.settledRevision());
            });
        } catch (IOException | RuntimeException localFailure) {

            throw new RemoteMutationSettlementException(outcome.remoteAppearanceImpact());
        }
    }

    private static <T> ProviderChannel<T> resumeSupersedingDelivery(
            ProviderChannel<T> current, ProviderChannel<T> previous) {
        ProviderDelivery delivery = current.minecraftDelivery();
        ProviderDelivery previousDelivery = previous.minecraftDelivery();
        if (!current.enabled(BuiltinProvider.MINECRAFT)
                || !previous.enabled(BuiltinProvider.MINECRAFT)
                || delivery.status() != ProviderDelivery.Status.UNKNOWN
                || previousDelivery.status() != ProviderDelivery.Status.ATTEMPTING
                || delivery.activation() != previousDelivery.activation()
                || delivery.intentRevision() <= previousDelivery.intentRevision()) {
            return current;
        }
        return current.settle(delivery, ProviderDelivery.Status.PENDING, null);
    }

    private static AppearanceSyncStatus supersedingRecoveryStatus(
            AccountAppearanceState current, AppearanceProviders providers) {
        if (hasMinecraftDeliveryStatus(providers, ProviderDelivery.Status.UNKNOWN)) {
            return AppearanceSyncStatus.UNKNOWN;
        }
        if (hasMinecraftDeliveryStatus(providers, ProviderDelivery.Status.ATTEMPTING)) {
            return AppearanceSyncStatus.ATTEMPTING;
        }
        if (hasMinecraftDeliveryStatus(providers, ProviderDelivery.Status.PENDING)) {
            return AppearanceSyncStatus.PENDING;
        }
        return current.syncStatus();
    }

    AccountAppearanceState copyAppearanceStatus(
            AccountAppearanceState current,
            AppearanceSyncStatus status,
            long settledRevision) {
        return withAppearanceStatus(current, status, settledRevision, new AppearanceProviders(
                deliveryStatus(current.providers().skin(), status, true),
                deliveryStatus(current.providers().cape(), status, false)));
    }

    private AccountAppearanceState withAppearanceStatus(
            AccountAppearanceState current,
            AppearanceSyncStatus status,
            long settledRevision,
            AppearanceProviders providers) {
        return new AccountAppearanceState(
                current.schemaVersion(),
                current.accountId(),
                current.intentRevision(),
                current.activePresetId(),
                current.skinSha256(),
                current.skinVariant(),
                current.capeId(),
                current.outerLayerVisibility(),
                status,
                settledRevision,
                clock.instant(),
                providers);
    }

    private static <T> ProviderChannel<T> deliveryStatus(
            ProviderChannel<T> channel, AppearanceSyncStatus status, boolean skin) {
        if (!channel.enabled(BuiltinProvider.MINECRAFT)
                || channel.minecraftDelivery().status() == ProviderDelivery.Status.CONFIRMED) {
            return channel;
        }
        ProviderDelivery.Status delivery = switch (status) {
            case ATTEMPTING -> ProviderDelivery.Status.ATTEMPTING;
            case OFFICIAL -> ProviderDelivery.Status.CONFIRMED;
            case UNKNOWN -> ProviderDelivery.Status.UNKNOWN;
            case PARTIAL -> skin ? ProviderDelivery.Status.CONFIRMED : ProviderDelivery.Status.PENDING;
            case PENDING -> channel.minecraftDelivery().status() == ProviderDelivery.Status.UNKNOWN
                    ? ProviderDelivery.Status.UNKNOWN : ProviderDelivery.Status.PENDING;
            case LOCAL_ONLY -> ProviderDelivery.Status.IDLE;
        };
        return channel.settle(channel.minecraftDelivery(), delivery, channel.desired());
    }

    static boolean sameDelivery(AccountAppearanceState current, AccountAppearanceState expected) {
        return current.intentRevision() == expected.intentRevision() && sameActivation(current, expected);
    }

    static boolean sameActivation(AccountAppearanceState current, AccountAppearanceState expected) {
        return current.providers().skin().minecraftDelivery().activation()
                            == expected.providers().skin().minecraftDelivery().activation()
                    && current.providers().cape().minecraftDelivery().activation()
                            == expected.providers().cape().minecraftDelivery().activation()
                    && current.providers().skin().enabled(BuiltinProvider.MINECRAFT)
                            == expected.providers().skin().enabled(BuiltinProvider.MINECRAFT)
                    && current.providers().cape().enabled(BuiltinProvider.MINECRAFT)
                            == expected.providers().cape().enabled(BuiltinProvider.MINECRAFT);
    }

    static AppearanceSyncStatus activeEditStatus(
            AccountAppearanceState current, AppearanceProviders revised) {
        boolean assignedMinecraft = assignedMinecraft(current.intentRevision() + 1, revised);
        if (!assignedMinecraft) {
            return current.syncStatus();
        }
        if (hasMinecraftDeliveryStatus(revised, ProviderDelivery.Status.UNKNOWN)) {
            return AppearanceSyncStatus.UNKNOWN;
        }
        if (hasMinecraftDeliveryStatus(revised, ProviderDelivery.Status.ATTEMPTING)) {
            return AppearanceSyncStatus.ATTEMPTING;
        }
        return AppearanceSyncStatus.PENDING;
    }

    private static boolean hasMinecraftDeliveryStatus(
            AppearanceProviders providers, ProviderDelivery.Status status) {
        return providers.skin().enabled(BuiltinProvider.MINECRAFT)
                        && providers.skin().minecraftDelivery().status() == status
                || providers.cape().enabled(BuiltinProvider.MINECRAFT)
                        && providers.cape().minecraftDelivery().status() == status;
    }

    static AppearanceSyncStatus settlementStatus(PresetApplicationOutcome outcome) {
        return switch (outcome.result()) {
            case APPLIED -> AppearanceSyncStatus.OFFICIAL;
            case PARTIAL -> AppearanceSyncStatus.PARTIAL;
            case UNKNOWN -> AppearanceSyncStatus.UNKNOWN;
            case FAILED -> allowsAutomaticMutationRetry(outcome)
                    ? AppearanceSyncStatus.PENDING
                    : AppearanceSyncStatus.UNKNOWN;
            case SESSION_EXPIRED -> AppearanceSyncStatus.UNKNOWN;
        };
    }

    private static boolean allowsAutomaticCheckpointRetry(ApiFailureKind failureKind) {
        return com.naocraftlab.skins.core.reconciliation.ReconciliationPolicy.automaticRetry(failureKind);
    }

    private static boolean allowsAutomaticMutationRetry(PresetApplicationOutcome outcome) {
        ApiFailureKind failureKind = outcome.failureKind();
        return allowsAutomaticCheckpointRetry(failureKind);
    }

    static boolean assignedMinecraft(long revision, AppearanceProviders providers) {
        return providers.skin().minecraftDelivery().intentRevision() == revision
                || providers.cape().minecraftDelivery().intentRevision() == revision;
    }
}

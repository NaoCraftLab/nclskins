package com.naocraftlab.skins.runtime;

import com.naocraftlab.skins.core.model.*;
import com.naocraftlab.skins.core.provider.*;
import com.naocraftlab.skins.core.service.*;
import com.naocraftlab.skins.core.png.PngValidationException;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import static com.naocraftlab.skins.runtime.AccountDeliveryService.*;
import static com.naocraftlab.skins.runtime.AccountReconciliationPort.*;

public final class AccountMutationExecutor {
    private final AccountAppearanceStore storage;
    private final AssetStorePort assets;
    private final Clock clock;
    private final AccountDeliveryService delivery;
    public AccountMutationExecutor(AccountAppearanceStore storage, AssetStorePort assets, Clock clock, AccountDeliveryService delivery) {
        this.storage = java.util.Objects.requireNonNull(storage);
        this.assets = java.util.Objects.requireNonNull(assets);
        this.clock = java.util.Objects.requireNonNull(clock);
        this.delivery = java.util.Objects.requireNonNull(delivery);
    }
    ReconciliationResult applyFullIntent(
            UUID accountId,
            AccountMutationEffects effects,
            AccountAppearanceState appearance,
            AppearanceSyncStatus expectedStatus,
            SessionValidation validation) throws IOException, PngValidationException {
        long revision = appearance.intentRevision();

        PresetApplicationRequest request = requestFromAppearance(appearance);
        AccountAppearanceState claimed = delivery.claimAppearance(
                accountId, appearance, expectedStatus, request.writeSkin(), request.writeCape());
        if (claimed.intentRevision() != revision
                || claimed.syncStatus() != AppearanceSyncStatus.ATTEMPTING) {
            return effects.result(claimed, validation);
        }
        PresetApplicationOutcome outcome = effects.apply(request, () -> appearanceStillCurrent(accountId, appearance));
        AccountAppearanceState settled = delivery.settleAfterMutation(
                accountId,
                claimed,
                AppearanceSyncStatus.ATTEMPTING,
                settlementStatus(outcome),
                outcome);
        return effects.afterMutation(settled, outcome, appearance.providers());
    }

    ReconciliationResult applyCapeRecovery(
            UUID accountId,
            AccountMutationEffects effects,
            AccountAppearanceState appearance,
            SessionValidation validation) throws IOException {
        long revision = appearance.intentRevision();
        AccountAppearanceState claimed = delivery.claimAppearance(
                accountId, appearance, AppearanceSyncStatus.PARTIAL, false, true);
        if (claimed.intentRevision() != revision
                || claimed.syncStatus() != AppearanceSyncStatus.ATTEMPTING) {
            return effects.result(claimed, validation);
        }
        PresetApplicationOutcome outcome = effects.retryCape(claimed.capeId(), () -> appearanceStillCurrent(accountId, appearance));
        AppearanceSyncStatus status = switch (outcome.result()) {
            case APPLIED -> AppearanceSyncStatus.OFFICIAL;
            case UNKNOWN -> AppearanceSyncStatus.UNKNOWN;
            case PARTIAL, FAILED, SESSION_EXPIRED -> AppearanceSyncStatus.PARTIAL;
        };
        AccountAppearanceState settled = delivery.settleAfterMutation(
                accountId,
                claimed,
                AppearanceSyncStatus.ATTEMPTING,
                status,
                outcome);
        return effects.afterMutation(settled, outcome, appearance.providers());
    }

    ReconciliationResult applyPendingCapeDelta(
            UUID accountId,
            AccountMutationEffects effects,
            AccountAppearanceState appearance,
            SessionValidation validation) throws IOException {
        long revision = appearance.intentRevision();
        AccountAppearanceState claimed = delivery.claimAppearance(
                accountId, appearance, AppearanceSyncStatus.PENDING, false, true);
        if (claimed.intentRevision() != revision
                || claimed.syncStatus() != AppearanceSyncStatus.ATTEMPTING) {
            return effects.result(claimed, validation);
        }
        PresetApplicationOutcome outcome = effects.retryCape(claimed.capeId(), () -> appearanceStillCurrent(accountId, appearance));
        AccountAppearanceState settled = delivery.settleAfterMutation(
                accountId,
                claimed,
                AppearanceSyncStatus.ATTEMPTING,
                settlementStatus(outcome),
                outcome);
        return effects.afterMutation(settled, outcome, appearance.providers());
    }

    private PresetApplicationRequest requestFromAppearance(AccountAppearanceState appearance)
            throws IOException, PngValidationException {
        boolean writeSkin = needsMinecraftDelivery(appearance.providers().skin());
        boolean writeCape = needsMinecraftDelivery(appearance.providers().cape());
        ResolvedSkinAsset resolved = null;
        SkinReference skin = SkinReference.accountDefault();
        if (writeSkin && appearance.skinSha256() != null) {
            UUID assetId = UUID.randomUUID();
            resolved = new ResolvedSkinAsset(
                    assetId,
                    appearance.skinSha256(),
                    appearance.skinVariant(),
                    assets.readAsset(appearance.skinSha256()));
            skin = SkinReference.asset(assetId);
        }
        Instant now = clock.instant();
        UUID presetId = appearance.activePresetId() == null
                ? UUID.randomUUID()
                : appearance.activePresetId();
        AppearancePreset preset = new AppearancePreset(
                presetId,
                "Durable appearance",
                skin,
                appearance.capeId(),
                appearance.outerLayerVisibility(),
                now,
                now);
        return new PresetApplicationRequest(preset, resolved,
                writeSkin,
                writeCape);
    }
    private boolean appearanceStillCurrent(UUID accountId, AccountAppearanceState expected) {
        try {
            AccountAppearanceState current = storage.loadAppearance(accountId);
            return sameDelivery(current, expected)
                    && current.syncStatus() == AppearanceSyncStatus.ATTEMPTING;
        } catch (IOException unavailableState) {
            return false;
        }
    }
    static boolean needsMinecraftDelivery(ProviderChannel<?> channel) {
        return channel.enabled(BuiltinProvider.MINECRAFT)
                && channel.minecraftDelivery().status() != ProviderDelivery.Status.CONFIRMED;
    }
}

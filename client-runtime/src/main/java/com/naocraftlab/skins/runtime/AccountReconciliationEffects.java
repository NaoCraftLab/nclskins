package com.naocraftlab.skins.runtime;

import com.naocraftlab.skins.core.model.AccountAppearanceState;
import com.naocraftlab.skins.core.model.AccountState;
import com.naocraftlab.skins.core.model.AppearanceSyncStatus;
import com.naocraftlab.skins.core.png.PngValidationException;
import com.naocraftlab.skins.core.provider.AppearanceProviders;
import com.naocraftlab.skins.core.reconciliation.ReconciliationPolicy;
import com.naocraftlab.skins.core.service.AccountAppearanceStore;
import com.naocraftlab.skins.core.service.SessionValidation;
import java.io.IOException;
import java.util.Optional;
import java.util.UUID;
import static com.naocraftlab.skins.runtime.AccountReconciliationPort.*;

interface AccountReconciliationEffects {
    AccountAppearanceState load() throws IOException;
    SessionValidation cached();
    boolean automaticAllowed();
    boolean cooldown();
    boolean startupObserved();
    SessionValidation validate(ReconciliationPolicy.Validation mode);
    Optional<ReconciliationResult> withSession(AccountAppearanceState checkpoint, Scoped operation)
            throws IOException, PngValidationException;
    ObservedAccount observe(SessionValidation validation, AppearanceProviders expected) throws IOException, PngValidationException;
    ObservedAccount observed() throws IOException;
    ReconciliationPolicy.Observation compare(AccountAppearanceState state, SessionValidation validation);
    AccountAppearanceStore.AppearanceIntentUpdate normalizeCape(AccountAppearanceState state) throws IOException;
    AccountAppearanceState settle(AccountAppearanceState expected, AppearanceSyncStatus status) throws IOException;
    ReconciliationResult full(AccountAppearanceState state, SessionValidation validation) throws IOException, PngValidationException;
    ReconciliationResult cape(AccountAppearanceState state, SessionValidation validation) throws IOException;
    ReconciliationResult result(AccountAppearanceState state, SessionValidation validation, ObservedAccount observed) throws IOException;

    record ObservedAccount(AccountState account, Optional<UUID> currentOfficialSkinId) {
        public ObservedAccount {
            java.util.Objects.requireNonNull(account);
            java.util.Objects.requireNonNull(currentOfficialSkinId);
        }
    }
    interface Scoped {
        Optional<ReconciliationResult> execute(AccountReconciliationEffects effects) throws IOException, PngValidationException;
    }
}

package com.naocraftlab.skins.core.service;

import com.naocraftlab.skins.core.model.*;
import java.io.IOException;
import java.util.UUID;
import java.util.function.UnaryOperator;
import java.util.function.BiFunction;

public interface AccountAppearanceStore extends MutationGuard {
    AccountAppearanceState loadAppearance(UUID accountId) throws IOException;
    AccountAppearanceState updateAppearance(UUID accountId, UnaryOperator<AccountAppearanceState> update) throws IOException;
    AccountAppearanceState updateAppearanceIntent(UUID accountId, BiFunction<AccountAppearanceState, Long, AccountAppearanceState> update) throws IOException;
    AppearanceIntentUpdate updateAppearanceIntentIfCurrent(UUID accountId, long revision, AppearanceSyncStatus status, BiFunction<AccountAppearanceState, Long, AccountAppearanceState> update) throws IOException;
    ActivePresetAppearanceIntentUpdate updateAppearanceIntentIfPresetActive(UUID accountId, UUID presetId, LibraryStatePort.AppearanceIntentFromAccount update) throws IOException;
    OwnedCapeInventory loadOwnedCapes(UUID accountId) throws IOException;
    OwnedCapeInventory saveOwnedCapes(OwnedCapeInventory inventory) throws IOException;
    OwnedCapeInventory updateOwnedCapes(UUID accountId, UnaryOperator<OwnedCapeInventory> update) throws IOException;
    record AppearanceIntentUpdate(AccountAppearanceState state, boolean updated) {
        public AppearanceIntentUpdate { java.util.Objects.requireNonNull(state); }
    }
    record ActivePresetAppearanceIntentUpdate(AccountState account, AccountAppearanceState state, boolean updated) {
        public ActivePresetAppearanceIntentUpdate {
            java.util.Objects.requireNonNull(account);
            java.util.Objects.requireNonNull(state);
            if (!account.accountId().equals(state.accountId())) throw new IllegalArgumentException("Account and appearance UUIDs differ");
        }
    }
}

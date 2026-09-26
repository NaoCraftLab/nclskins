package com.naocraftlab.skins.core.storage;

import com.naocraftlab.skins.core.service.AccountAppearanceStore;
import com.naocraftlab.skins.core.service.LibraryStatePort;
import com.naocraftlab.skins.core.model.*;
import java.io.IOException;
import java.util.UUID;
import java.util.function.UnaryOperator;
import java.util.function.BiFunction;

public final class AccountAppearanceStorageAdapter implements AccountAppearanceStore {
    private final NclSkinsStorage storage;
    public AccountAppearanceStorageAdapter(NclSkinsStorage storage) {
        this.storage = java.util.Objects.requireNonNull(storage);
    }
    public Lease acquireRemoteMutationLock(UUID accountId) throws IOException {
        ProcessFileLock lock = storage.acquireRemoteMutationLock(accountId);
        return lock::close;
    }
    public AccountAppearanceState loadAppearance(UUID accountId) throws IOException {
        return storage.loadAppearance(accountId);
    }
    public AccountAppearanceState updateAppearance(UUID accountId, UnaryOperator<AccountAppearanceState> update) throws IOException {
        return storage.updateAppearance(accountId, update);
    }
    public AccountAppearanceState updateAppearanceIntent(UUID accountId, BiFunction<AccountAppearanceState, Long, AccountAppearanceState> update) throws IOException {
        return storage.updateAppearanceIntent(accountId, update);
    }
    public AppearanceIntentUpdate updateAppearanceIntentIfCurrent(UUID accountId, long revision, AppearanceSyncStatus status, BiFunction<AccountAppearanceState, Long, AccountAppearanceState> update) throws IOException {
        var result = storage.updateAppearanceIntentIfCurrent(accountId, revision, status, update);
        return new AppearanceIntentUpdate(result.state(), result.updated());
    }
    public ActivePresetAppearanceIntentUpdate updateAppearanceIntentIfPresetActive(UUID accountId, UUID presetId, LibraryStatePort.AppearanceIntentFromAccount update) throws IOException {
        var result = storage.updateAppearanceIntentIfPresetActive(accountId, presetId, update::apply);
        return new ActivePresetAppearanceIntentUpdate(result.account(), result.state(), result.updated());
    }
    public OwnedCapeInventory loadOwnedCapes(UUID accountId) throws IOException {
        return storage.loadOwnedCapes(accountId);
    }
    public OwnedCapeInventory saveOwnedCapes(OwnedCapeInventory inventory) throws IOException {
        return storage.saveOwnedCapes(inventory);
    }
    public OwnedCapeInventory updateOwnedCapes(UUID accountId, UnaryOperator<OwnedCapeInventory> update) throws IOException {
        return storage.updateOwnedCapes(accountId, update);
    }
}

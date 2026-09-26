package com.naocraftlab.skins.core.storage;

import com.naocraftlab.skins.core.model.AccountState;
import com.naocraftlab.skins.core.model.PersonalCapeEntry;
import com.naocraftlab.skins.core.png.PngValidationException;
import com.naocraftlab.skins.core.service.AccountWriteGuard;
import com.naocraftlab.skins.core.service.AssetStorePort;
import com.naocraftlab.skins.core.service.LibraryStatePort;
import java.io.IOException;
import java.util.Objects;
import java.util.UUID;
import java.util.function.UnaryOperator;

public final class LibraryStorageAdapter implements LibraryStatePort, AssetStorePort {
    private final NclSkinsStorage storage;

    public LibraryStorageAdapter(NclSkinsStorage storage) {
        this.storage = Objects.requireNonNull(storage, "storage");
    }

    @Override
    public AccountState loadOrCreateAccount(UUID accountId) throws IOException {
        return storage.loadOrCreateAccount(accountId);
    }

    @Override
    public AccountState updateAccount(UUID accountId, UnaryOperator<AccountState> update,
            AccountWriteGuard guard) throws IOException {
        return storage.updateAccount(accountId, update, guard);
    }

    @Override
    public AccountAppearanceMutationResult mutateAccountAndAppearance(UUID accountId,
            AccountAppearanceMutation mutation) throws IOException {
        var result = storage.mutateAccountAndAppearance(accountId, (account, appearance, revision) -> {
            var plan = mutation.apply(account, appearance, revision);
            return new NclSkinsStorage.AccountAppearanceMutationPlan(plan.account(), plan.appearance(),
                    plan.accountUpdated(), plan.appearanceUpdated());
        });
        return new AccountAppearanceMutationResult(result.account(), result.appearance(),
                result.accountUpdated(), result.appearanceUpdated());
    }

    @Override
    public PersonalCapeEntry importCape(UUID accountId, String name, byte[] bytes, AccountWriteGuard guard)
            throws IOException, PngValidationException {
        return storage.importCape(accountId, name, bytes, guard);
    }

    @Override
    public AccountState renameCape(UUID accountId, UUID entryId, String name) throws IOException {
        return storage.renameCape(accountId, entryId, name);
    }

    @Override
    public AccountAppearanceMutationResult deleteCape(UUID accountId, UUID entryId) throws IOException {
        var result = storage.deleteCape(accountId, entryId);
        return new AccountAppearanceMutationResult(result.account(), result.appearance(),
                result.accountUpdated(), result.appearanceUpdated());
    }

    @Override
    public AccountState discardCapeIfUnreferenced(UUID accountId, UUID entryId) throws IOException {
        return storage.discardCapeIfUnreferenced(accountId, entryId);
    }

    @Override
    public Asset storeAsset(byte[] bytes) throws IOException, PngValidationException {
        var asset = storage.storeAsset(bytes);
        return new Asset(asset.sha256(), asset.pngInfo(), asset.alreadyPresent());
    }

    @Override
    public byte[] readAsset(String sha256) throws IOException, PngValidationException {
        return storage.readAsset(sha256);
    }
}

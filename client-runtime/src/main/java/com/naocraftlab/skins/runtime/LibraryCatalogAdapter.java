package com.naocraftlab.skins.runtime;

import com.naocraftlab.skins.core.model.AccountState;
import com.naocraftlab.skins.core.model.PersonalCapeEntry;
import com.naocraftlab.skins.core.png.PngValidationException;
import com.naocraftlab.skins.core.service.LibraryService;
import com.naocraftlab.skins.core.service.LibraryStatePort;
import com.naocraftlab.skins.core.service.AssetStorePort;
import java.io.IOException;
import java.util.Objects;
import java.util.UUID;

final class LibraryCatalogAdapter implements CatalogAccountAccess {
    private final LibraryService library;
    private final LibraryStatePort storage;
    private final AssetStorePort assets;
    private final CurrentAccount current;

    LibraryCatalogAdapter(LibraryService library, LibraryStatePort storage, AssetStorePort assets, CurrentAccount current) {
        this.library = Objects.requireNonNull(library, "library");
        this.storage = Objects.requireNonNull(storage, "storage");
        this.assets = Objects.requireNonNull(assets, "assets");
        this.current = Objects.requireNonNull(current, "current");
    }

    @Override public UUID currentAccountId() throws IOException { return current.get(); }
    @Override public AccountState load(UUID accountId) throws IOException { return library.load(accountId); }
    @Override public byte[] readAsset(String hash) throws IOException, PngValidationException { return assets.readAsset(hash); }
    @Override public byte[] resolveSkin(AccountState account, UUID assetId) throws IOException, PngValidationException {
        return library.resolveSkin(account, assetId).pngBytes();
    }
    @Override public PersonalCapeEntry importCape(UUID accountId, String name, byte[] bytes, com.naocraftlab.skins.core.service.AccountWriteGuard guard)
            throws IOException, PngValidationException {
        requireCurrent(accountId);
        return storage.importCape(accountId, name, bytes, current -> { requireCurrent(accountId); guard.verify(current); });
    }
    @Override public void requireCurrent(UUID accountId) throws IOException {
        if (!accountId.equals(currentAccountId())) throw new IOException("Account changed");
    }

    @FunctionalInterface interface CurrentAccount { UUID get() throws IOException; }
}

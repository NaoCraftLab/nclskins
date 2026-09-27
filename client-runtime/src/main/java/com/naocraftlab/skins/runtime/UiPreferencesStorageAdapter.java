package com.naocraftlab.skins.runtime;

import com.naocraftlab.skins.core.model.AccountUiPreferences;
import com.naocraftlab.skins.core.model.AddSourceTab;
import com.naocraftlab.skins.core.model.EditorTab;
import com.naocraftlab.skins.core.model.SkinVariant;
import com.naocraftlab.skins.core.provider.AppearanceProviders;
import com.naocraftlab.skins.core.storage.NclSkinsStorage;
import java.io.IOException;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public final class UiPreferencesStorageAdapter implements UiPreferencesPort {
    private final NclSkinsStorage storage;
    private final LibraryCatalogAdapter.CurrentAccount currentAccount;

    public UiPreferencesStorageAdapter(NclSkinsStorage storage, LibraryCatalogAdapter.CurrentAccount currentAccount) {
        this.storage = Objects.requireNonNull(storage, "storage");
        this.currentAccount = Objects.requireNonNull(currentAccount, "currentAccount");
    }

    @Override
    public Optional<AccountUiPreferences> loadUiPreferences() throws IOException {
        UUID accountId = currentAccount.get();
        return Optional.of(storage.loadUiPreferences(accountId).preferences());
    }

    @Override
    public void setSelectedProvidersTab(UUID accountId, AppearanceProviders.Component tab) throws IOException {
        storage.setSelectedProvidersTab(accountId, tab);
    }

    @Override
    public void setSelectedAddSourceTab(UUID accountId, AddSourceTab tab) throws IOException {
        storage.setSelectedAddSourceTab(accountId, tab);
    }

    @Override
    public void setSelectedAddSourceTab(AddSourceTab tab) throws IOException {
        UUID accountId = currentAccount.get();
        storage.setSelectedAddSourceTab(accountId, Objects.requireNonNull(tab, "tab"));
    }

    @Override
    public void setCollapsedCapeCollections(UUID accountId, Set<String> values) throws IOException {
        storage.setCollapsedCapeCollections(accountId, values);
    }

    @Override
    public void setSelectedEditorTab(UUID accountId, EditorTab tab) throws IOException {
        storage.setSelectedEditorTab(
                Objects.requireNonNull(accountId, "accountId"),
                Objects.requireNonNull(tab, "tab"));
    }

    @Override
    public void setCollectionCollapsed(String collectionId, boolean collapsed) throws IOException {
        UUID accountId = currentAccount.get();
        storage.setCollectionCollapsed(
                accountId, Objects.requireNonNull(collectionId, "collectionId"), collapsed);
    }

    @Override
    public void replaceCollapsedCollectionIds(Set<String> collectionIds) throws IOException {
        UUID accountId = currentAccount.get();
        storage.replaceCollapsedCollectionIds(
                accountId, Objects.requireNonNull(collectionIds, "collectionIds"));
    }

    @Override
    public void setPreferredSkinVariant(SkinVariant variant) throws IOException {
        UUID accountId = currentAccount.get();
        storage.setPreferredSkinVariant(accountId, Objects.requireNonNull(variant, "variant"));
    }
}

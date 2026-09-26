package com.naocraftlab.skins.runtime;

import com.naocraftlab.skins.core.model.AccountUiPreferences;
import com.naocraftlab.skins.core.model.AddSourceTab;
import com.naocraftlab.skins.core.model.EditorTab;
import com.naocraftlab.skins.core.model.SkinVariant;
import com.naocraftlab.skins.core.provider.AppearanceProviders;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public interface UiPreferencesPort {
    Optional<AccountUiPreferences> loadUiPreferences() throws Exception;

    void setSelectedProvidersTab(UUID accountId, AppearanceProviders.Component tab) throws Exception;

    void setSelectedAddSourceTab(UUID accountId, AddSourceTab tab) throws Exception;

    void setSelectedAddSourceTab(AddSourceTab tab) throws Exception;

    void setCollapsedCapeCollections(UUID accountId, Set<String> values) throws Exception;

    void setSelectedEditorTab(UUID accountId, EditorTab tab) throws Exception;

    void setCollectionCollapsed(String collectionId, boolean collapsed) throws Exception;

    void replaceCollapsedCollectionIds(Set<String> collectionIds) throws Exception;

    void setPreferredSkinVariant(SkinVariant variant) throws Exception;
}

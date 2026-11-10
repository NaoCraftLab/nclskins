package com.naocraftlab.skins.core.model;

import com.naocraftlab.skins.core.provider.AppearanceProviders;
import java.util.HashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;


public record AccountUiPreferences(
        int schemaVersion,
        UUID accountId,
        AddSourceTab selectedAddSourceTab,
        EditorTab selectedEditorTab,
        Optional<SkinVariant> preferredSkinVariant,
        Set<String> collapsedCollectionIds,
        Set<String> collapsedCapeCollections,
        AppearanceProviders.Component selectedProvidersTab,
        Set<String> collapsedGallerySections,
        CapePreviewMode preferredCapeMode) {
    public static final String GALLERY_FAVORITES = "gallery.favorites";
    public static final String GALLERY_YOUR_APPEARANCES = "gallery.your_appearances";

    public AccountUiPreferences(int schemaVersion, UUID accountId, AddSourceTab selectedAddSourceTab,
            EditorTab selectedEditorTab, Optional<SkinVariant> preferredSkinVariant,
            Set<String> collapsedCollectionIds, Set<String> collapsedCapeCollections,
            AppearanceProviders.Component selectedProvidersTab, Set<String> collapsedGallerySections) {
        this(schemaVersion, accountId, selectedAddSourceTab, selectedEditorTab, preferredSkinVariant,
                collapsedCollectionIds, collapsedCapeCollections, selectedProvidersTab, collapsedGallerySections,
                CapePreviewMode.CAPE);
    }

    public AccountUiPreferences withPreferredCapeMode(CapePreviewMode mode) {
        return new AccountUiPreferences(schemaVersion, accountId, selectedAddSourceTab, selectedEditorTab,
                preferredSkinVariant, collapsedCollectionIds, collapsedCapeCollections, selectedProvidersTab,
                collapsedGallerySections, mode);
    }

    public AccountUiPreferences(int schemaVersion, UUID accountId, AddSourceTab selectedAddSourceTab,
            EditorTab selectedEditorTab, Optional<SkinVariant> preferredSkinVariant,
            Set<String> collapsedCollectionIds, Set<String> collapsedCapeCollections,
            AppearanceProviders.Component selectedProvidersTab) {
        this(schemaVersion, accountId, selectedAddSourceTab, selectedEditorTab, preferredSkinVariant,
                collapsedCollectionIds, collapsedCapeCollections, selectedProvidersTab, Set.of());
    }

    public AccountUiPreferences withGallerySections(java.util.Map<String, Boolean> collapsed) {
        Objects.requireNonNull(collapsed, "collapsed");
        Set<String> replacement = new HashSet<>(collapsedGallerySections);
        collapsed.forEach((id, value) -> {
            requireGallerySectionId(id);
            if (Objects.requireNonNull(value, "collapsed value")) replacement.add(id);
            else replacement.remove(id);
        });
        return new AccountUiPreferences(schemaVersion, accountId, selectedAddSourceTab, selectedEditorTab,
                preferredSkinVariant, collapsedCollectionIds, collapsedCapeCollections, selectedProvidersTab,
                replacement, preferredCapeMode);
    }

    public static void requireGallerySectionId(String id) {
        if (!GALLERY_FAVORITES.equals(id) && !GALLERY_YOUR_APPEARANCES.equals(id)) {
            throw new IllegalArgumentException("unknown Gallery section");
        }
    }
    public AccountUiPreferences(int schemaVersion, UUID accountId, AddSourceTab selectedAddSourceTab,
            EditorTab selectedEditorTab, Optional<SkinVariant> preferredSkinVariant,
            Set<String> collapsedCollectionIds, Set<String> collapsedCapeCollections) {
        this(schemaVersion, accountId, selectedAddSourceTab, selectedEditorTab, preferredSkinVariant,
                collapsedCollectionIds, collapsedCapeCollections, AppearanceProviders.Component.SKIN);
    }

    public AccountUiPreferences withSelectedProvidersTab(AppearanceProviders.Component tab) {
        return new AccountUiPreferences(schemaVersion, accountId, selectedAddSourceTab, selectedEditorTab,
                preferredSkinVariant, collapsedCollectionIds, collapsedCapeCollections, tab, collapsedGallerySections,
                preferredCapeMode);
    }

    public static final int CURRENT_SCHEMA_VERSION = 2;

    public AccountUiPreferences(int schemaVersion, UUID accountId, AddSourceTab selectedAddSourceTab,
            EditorTab selectedEditorTab, Optional<SkinVariant> preferredSkinVariant, Set<String> collapsedCollectionIds) {
        this(schemaVersion, accountId, selectedAddSourceTab, selectedEditorTab, preferredSkinVariant, collapsedCollectionIds, Set.of());
    }

    public AccountUiPreferences withCollapsedCapeCollections(Set<String> values) {
        return new AccountUiPreferences(schemaVersion, accountId, selectedAddSourceTab, selectedEditorTab,
                preferredSkinVariant, collapsedCollectionIds, values, selectedProvidersTab, collapsedGallerySections,
                preferredCapeMode);
    }

    public AccountUiPreferences {
        if (schemaVersion != CURRENT_SCHEMA_VERSION) {
            throw new IllegalArgumentException("unsupported UI preferences schema: " + schemaVersion);
        }
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(preferredCapeMode, "preferredCapeMode");
        Objects.requireNonNull(selectedProvidersTab, "selectedProvidersTab");
        Objects.requireNonNull(selectedAddSourceTab, "selectedAddSourceTab");
        Objects.requireNonNull(selectedEditorTab, "selectedEditorTab");
        preferredSkinVariant = Objects.requireNonNull(preferredSkinVariant, "preferredSkinVariant");
        Objects.requireNonNull(collapsedCollectionIds, "collapsedCollectionIds");
        for (String collectionId : collapsedCollectionIds) {
            requireCollectionId(collectionId);
        }
        collapsedCollectionIds = Set.copyOf(collapsedCollectionIds);
        Objects.requireNonNull(collapsedCapeCollections, "collapsedCapeCollections");
        for (String collectionId : collapsedCapeCollections) {
            requireCollectionId(collectionId);
        }
        collapsedCapeCollections = Set.copyOf(collapsedCapeCollections);
        collapsedGallerySections = Set.copyOf(collapsedGallerySections);
        collapsedGallerySections.forEach(AccountUiPreferences::requireGallerySectionId);
    }


    public AccountUiPreferences(
            int schemaVersion,
            UUID accountId,
            AddSourceTab selectedAddSourceTab,
            Set<String> collapsedCollectionIds) {
        this(
                schemaVersion,
                accountId,
                selectedAddSourceTab,
                EditorTab.APPEARANCE,
                Optional.empty(),
                collapsedCollectionIds);
    }

    public AccountUiPreferences(
            int schemaVersion,
            UUID accountId,
            AddSourceTab selectedAddSourceTab,
            Optional<SkinVariant> preferredSkinVariant,
            Set<String> collapsedCollectionIds) {
        this(
                schemaVersion,
                accountId,
                selectedAddSourceTab,
                EditorTab.APPEARANCE,
                preferredSkinVariant,
                collapsedCollectionIds);
    }

    public static AccountUiPreferences defaults(UUID accountId) {
        return new AccountUiPreferences(
                CURRENT_SCHEMA_VERSION,
                accountId,
                AddSourceTab.CATALOG,
                EditorTab.APPEARANCE,
                Optional.empty(),
                Set.of());
    }

    public AccountUiPreferences withSelectedAddSourceTab(AddSourceTab selectedTab) {
        return new AccountUiPreferences(
                schemaVersion,
                accountId,
                Objects.requireNonNull(selectedTab, "selectedTab"),
                selectedEditorTab,
                preferredSkinVariant,
                collapsedCollectionIds, collapsedCapeCollections, selectedProvidersTab, collapsedGallerySections,
                preferredCapeMode);
    }

    public AccountUiPreferences withSelectedEditorTab(EditorTab selectedTab) {
        return new AccountUiPreferences(
                schemaVersion,
                accountId,
                selectedAddSourceTab,
                Objects.requireNonNull(selectedTab, "selectedTab"),
                preferredSkinVariant,
                collapsedCollectionIds, collapsedCapeCollections, selectedProvidersTab, collapsedGallerySections,
                preferredCapeMode);
    }


    public AccountUiPreferences withPreferredSkinVariant(SkinVariant variant) {
        return new AccountUiPreferences(
                schemaVersion,
                accountId,
                selectedAddSourceTab,
                selectedEditorTab,
                Optional.of(Objects.requireNonNull(variant, "variant")),
                collapsedCollectionIds, collapsedCapeCollections, selectedProvidersTab, collapsedGallerySections,
                preferredCapeMode);
    }

    public AccountUiPreferences withCollectionCollapsed(String collectionId, boolean collapsed) {
        requireCollectionId(collectionId);
        Set<String> replacement = new HashSet<>(collapsedCollectionIds);
        if (collapsed) {
            replacement.add(collectionId);
        } else {
            replacement.remove(collectionId);
        }
        return withCollapsedCollectionIds(replacement);
    }

    public AccountUiPreferences withCollapsedCollectionIds(Set<String> collectionIds) {
        Objects.requireNonNull(collectionIds, "collectionIds");
        return new AccountUiPreferences(
                schemaVersion,
                accountId,
                selectedAddSourceTab,
                selectedEditorTab,
                preferredSkinVariant,
                collectionIds, collapsedCapeCollections, selectedProvidersTab, collapsedGallerySections,
                preferredCapeMode);
    }

    private static void requireCollectionId(String collectionId) {
        Objects.requireNonNull(collectionId, "collectionId");
        if (collectionId.isBlank()) {
            throw new IllegalArgumentException("collectionId must not be blank");
        }
    }
}

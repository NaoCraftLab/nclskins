package com.naocraftlab.skins.core.model;

import java.util.HashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;


public record AccountUiPreferences(
        int schemaVersion,
        UUID accountId,
        AddSourceTab selectedAddSourceTab,
        Optional<SkinVariant> preferredSkinVariant,
        Set<String> collapsedCollectionIds) {
    public static final int CURRENT_SCHEMA_VERSION = 1;

    public AccountUiPreferences {
        if (schemaVersion != CURRENT_SCHEMA_VERSION) {
            throw new IllegalArgumentException("unsupported UI preferences schema: " + schemaVersion);
        }
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(selectedAddSourceTab, "selectedAddSourceTab");
        preferredSkinVariant = Objects.requireNonNull(preferredSkinVariant, "preferredSkinVariant");
        Objects.requireNonNull(collapsedCollectionIds, "collapsedCollectionIds");
        for (String collectionId : collapsedCollectionIds) {
            requireCollectionId(collectionId);
        }
        collapsedCollectionIds = Set.copyOf(collapsedCollectionIds);
    }


    public AccountUiPreferences(
            int schemaVersion,
            UUID accountId,
            AddSourceTab selectedAddSourceTab,
            Set<String> collapsedCollectionIds) {
        this(schemaVersion, accountId, selectedAddSourceTab, Optional.empty(), collapsedCollectionIds);
    }

    public static AccountUiPreferences defaults(UUID accountId) {
        return new AccountUiPreferences(
                CURRENT_SCHEMA_VERSION,
                accountId,
                AddSourceTab.CATALOG,
                Optional.empty(),
                Set.of());
    }

    public AccountUiPreferences withSelectedAddSourceTab(AddSourceTab selectedTab) {
        return new AccountUiPreferences(
                schemaVersion,
                accountId,
                Objects.requireNonNull(selectedTab, "selectedTab"),
                preferredSkinVariant,
                collapsedCollectionIds);
    }


    public AccountUiPreferences withPreferredSkinVariant(SkinVariant variant) {
        return new AccountUiPreferences(
                schemaVersion,
                accountId,
                selectedAddSourceTab,
                Optional.of(Objects.requireNonNull(variant, "variant")),
                collapsedCollectionIds);
    }

    public AccountUiPreferences withCollectionCollapsed(String collectionId, boolean collapsed) {
        requireCollectionId(collectionId);
        Set<String> replacement = new HashSet<>(collapsedCollectionIds);
        if (collapsed) {
            replacement.add(collectionId);
        } else {
            replacement.remove(collectionId);
        }
        return new AccountUiPreferences(
                schemaVersion,
                accountId,
                selectedAddSourceTab,
                preferredSkinVariant,
                replacement);
    }

    private static void requireCollectionId(String collectionId) {
        Objects.requireNonNull(collectionId, "collectionId");
        if (collectionId.isBlank()) {
            throw new IllegalArgumentException("collectionId must not be blank");
        }
    }
}

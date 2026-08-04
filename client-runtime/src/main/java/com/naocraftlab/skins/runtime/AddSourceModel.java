package com.naocraftlab.skins.runtime;

import com.naocraftlab.skins.client.CatalogCollectionOrder;
import com.naocraftlab.skins.client.CatalogText;
import com.naocraftlab.skins.client.PersonalSkinCatalog;
import com.naocraftlab.skins.client.SkinCatalogSource;
import com.naocraftlab.skins.client.SkinModel;
import com.naocraftlab.skins.core.model.AccountUiPreferences;
import com.naocraftlab.skins.core.model.AddSourceTab;
import com.naocraftlab.skins.core.model.SkinVariant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;


public final class AddSourceModel {
    public enum CatalogFilter {
        ALL,
        CLASSIC,
        SLIM;

        public CatalogFilter next() {
            return switch (this) {
                case ALL -> CLASSIC;
                case CLASSIC -> SLIM;
                case SLIM -> ALL;
            };
        }

        public CatalogFilter previous() {
            return switch (this) {
                case ALL -> SLIM;
                case CLASSIC -> ALL;
                case SLIM -> CLASSIC;
            };
        }
    }

    private final AddSourceTab selectedTab;
    private final List<SkinCatalogSource.CollectionDescriptor> collections;
    private final Set<String> collapsedCollectionIds;
    private final String query;
    private final String playerInput;
    private final String urlInput;
    private final CatalogFilter filter;
    private final SkinVariant preferredVariant;
    private final int scrollOffset;
    private final long focusToken;
    private final Optional<String> focusWidgetId;
    private final Optional<PersonalSkinDeletion> personalSkinDeletion;
    private final TextResolver textResolver;

    private AddSourceModel(
            AddSourceTab selectedTab,
            List<SkinCatalogSource.CollectionDescriptor> collections,
            Set<String> collapsedCollectionIds,
            String query,
            String playerInput,
            String urlInput,
            CatalogFilter filter,
            SkinVariant preferredVariant,
            int scrollOffset,
            long focusToken,
            Optional<String> focusWidgetId,
            Optional<PersonalSkinDeletion> personalSkinDeletion,
            TextResolver textResolver) {
        this.selectedTab = Objects.requireNonNull(selectedTab, "selectedTab");
        this.collections = List.copyOf(Objects.requireNonNull(collections, "collections"));
        this.collapsedCollectionIds = Set.copyOf(
                Objects.requireNonNull(collapsedCollectionIds, "collapsedCollectionIds"));
        this.query = Objects.requireNonNull(query, "query");
        this.playerInput = Objects.requireNonNull(playerInput, "playerInput");
        this.urlInput = Objects.requireNonNull(urlInput, "urlInput");
        this.filter = Objects.requireNonNull(filter, "filter");
        this.preferredVariant = Objects.requireNonNull(preferredVariant, "preferredVariant");
        if (scrollOffset < 0 || focusToken < 0) {
            throw new IllegalArgumentException("scroll offset and focus token must not be negative");
        }
        this.scrollOffset = scrollOffset;
        this.focusToken = focusToken;
        this.focusWidgetId = Objects.requireNonNull(focusWidgetId, "focusWidgetId");
        this.focusWidgetId.ifPresent(value -> {
            if (value.isBlank()) {
                throw new IllegalArgumentException("focus widget id must not be blank");
            }
        });
        this.personalSkinDeletion = Objects.requireNonNull(
                personalSkinDeletion, "personalSkinDeletion");
        this.textResolver = Objects.requireNonNull(textResolver, "textResolver");

        Set<String> collectionIds = new HashSet<>();
        for (SkinCatalogSource.CollectionDescriptor collection : this.collections) {
            Objects.requireNonNull(collection, "collections contains null");
            if (!collectionIds.add(collection.id())) {
                throw new IllegalArgumentException(
                        "Duplicate catalog collection id: " + collection.id());
            }
        }
    }

    public static AddSourceModel open(
            AccountUiPreferences preferences,
            List<SkinCatalogSource.CollectionDescriptor> collections) {
        return open(preferences, collections, SkinVariant.CLASSIC);
    }

    public static AddSourceModel open(
            AccountUiPreferences preferences,
            List<SkinCatalogSource.CollectionDescriptor> collections,
            SkinVariant fallbackVariant) {
        return open(preferences, collections, fallbackVariant, message -> message.key());
    }

    public static AddSourceModel open(
            AccountUiPreferences preferences,
            List<SkinCatalogSource.CollectionDescriptor> collections,
            SkinVariant fallbackVariant,
            TextResolver textResolver) {
        Objects.requireNonNull(preferences, "preferences");
        Objects.requireNonNull(fallbackVariant, "fallbackVariant");
        Objects.requireNonNull(textResolver, "textResolver");
        AddSourceTab tab = preferences.selectedAddSourceTab();
        return new AddSourceModel(
                tab,
                collections,
                preferences.collapsedCollectionIds(),
                "",
                "",
                "",
                CatalogFilter.ALL,
                preferences.preferredSkinVariant().orElse(fallbackVariant),
                0,
                tab == AddSourceTab.CATALOG ? 1 : 0,
                tab == AddSourceTab.CATALOG
                        ? Optional.of("add.catalog.search")
                        : Optional.empty(),
                Optional.empty(),
                textResolver);
    }

    public AddSourceTab selectedTab() {
        return selectedTab;
    }

    public List<SkinCatalogSource.CollectionDescriptor> collections() {
        Map<String, String> names = new HashMap<>();
        collections.forEach(collection -> names.put(collection.id(), collectionName(collection)));
        return collections.stream().sorted(collectionComparator(names)).toList();
    }


    public List<SkinCatalogSource.CollectionDescriptor> visibleCollections() {
        return collections().stream()
                .filter(collection -> !visibleSkins(collection).isEmpty())
                .toList();
    }

    public Set<String> collapsedCollectionIds() {
        return collapsedCollectionIds;
    }

    public String query() {
        return query;
    }

    public String playerInput() {
        return playerInput;
    }

    public String urlInput() {
        return urlInput;
    }

    public AddSourceModel withPlayerInput(String value) {
        return copy(selectedTab, collapsedCollectionIds, query,
                Objects.requireNonNull(value, "value"), urlInput, filter, preferredVariant,
                scrollOffset, focusToken, focusWidgetId, personalSkinDeletion);
    }

    public AddSourceModel withUrlInput(String value) {
        return copy(selectedTab, collapsedCollectionIds, query,
                playerInput, Objects.requireNonNull(value, "value"), filter, preferredVariant,
                scrollOffset, focusToken, focusWidgetId, personalSkinDeletion);
    }

    public CatalogFilter filter() {
        return filter;
    }

    public SkinVariant preferredVariant() {
        return preferredVariant;
    }

    public int scrollOffset() {
        return scrollOffset;
    }

    public Optional<Long> focusToken() {
        return focusToken == 0 ? Optional.empty() : Optional.of(focusToken);
    }

    public Optional<String> focusWidgetId() {
        return focusWidgetId;
    }

    public Optional<PersonalSkinDeletion> personalSkinDeletion() {
        return personalSkinDeletion;
    }

    public AddSourceModel withSelectedTab(AddSourceTab tab) {
        Objects.requireNonNull(tab, "tab");
        if (tab == selectedTab) {
            return this;
        }
        long nextFocus = tab == AddSourceTab.CATALOG ? Math.max(1, focusToken + 1) : focusToken;
        return copy(
                tab,
                collapsedCollectionIds,
                query,
                filter,
                preferredVariant,
                scrollOffset,
                nextFocus,
                tab == AddSourceTab.CATALOG
                        ? Optional.of("add.catalog.search")
                        : Optional.empty(),
                Optional.empty());
    }

    public AddSourceModel withQuery(String value) {
        Objects.requireNonNull(value, "value");
        if (value.equals(query)) {
            return this;
        }
        return copy(
                selectedTab,
                collapsedCollectionIds,
                value,
                filter,
                preferredVariant,
                0,
                focusToken,
                focusWidgetId,
                personalSkinDeletion);
    }

    public AddSourceModel cycleFilter() {
        return cycleFilter(false);
    }

    public AddSourceModel cycleFilter(boolean reverse) {
        CatalogFilter nextFilter = reverse ? filter.previous() : filter.next();
        SkinVariant nextPreferred = switch (nextFilter) {
            case CLASSIC -> SkinVariant.CLASSIC;
            case SLIM -> SkinVariant.SLIM;
            case ALL -> preferredVariant;
        };
        return copy(
                selectedTab,
                collapsedCollectionIds,
                query,
                nextFilter,
                nextPreferred,
                0,
                focusToken,
                focusWidgetId,
                personalSkinDeletion);
    }

    public AddSourceModel withCollectionCollapsed(String collectionId, boolean collapsed) {
        Objects.requireNonNull(collectionId, "collectionId");
        java.util.HashSet<String> changed = new java.util.HashSet<>(collapsedCollectionIds);
        if (collapsed) {
            changed.add(collectionId);
        } else {
            changed.remove(collectionId);
        }
        return copy(
                selectedTab,
                Set.copyOf(changed),
                query,
                filter,
                preferredVariant,
                scrollOffset,
                focusToken,
                focusWidgetId,
                personalSkinDeletion);
    }

    public AddSourceModel withScrollOffset(int value) {
        int bounded = Math.max(0, value);
        if (bounded == scrollOffset) {
            return this;
        }
        return copy(
                selectedTab,
                collapsedCollectionIds,
                query,
                filter,
                preferredVariant,
                bounded,
                focusToken,
                focusWidgetId,
                personalSkinDeletion);
    }

    public boolean collectionCollapsed(String collectionId) {
        return collapsedCollectionIds.contains(collectionId);
    }

    public AddSourceModel requestPersonalSkinDeletion(
            SkinCatalogSource.CollectionDescriptor collection,
            SkinCatalogSource.SkinDescriptor skin) {
        Objects.requireNonNull(collection, "collection");
        Objects.requireNonNull(skin, "skin");
        if (collection.order().kind() != CatalogCollectionOrder.Kind.PERSONAL
                || collection.skins().stream().noneMatch(value -> value.id().equals(skin.id()))) {
            return this;
        }
        long nextFocus = Math.max(1, focusToken + 1);
        return new AddSourceModel(
                selectedTab,
                collections,
                collapsedCollectionIds,
                query,
                playerInput,
                urlInput,
                filter,
                preferredVariant,
                scrollOffset,
                nextFocus,
                Optional.of("add.catalog.delete.cancel"),
                Optional.of(new PersonalSkinDeletion(
                        collection.id(),
                        skin.id(),
                        skinName(skin),
                        "add.catalog.delete:" + skin.id())),
                textResolver);
    }

    public AddSourceModel cancelPersonalSkinDeletion() {
        if (personalSkinDeletion.isEmpty()) {
            return this;
        }
        PersonalSkinDeletion deletion = personalSkinDeletion.orElseThrow();
        return new AddSourceModel(
                selectedTab,
                collections,
                collapsedCollectionIds,
                query,
                playerInput,
                urlInput,
                filter,
                preferredVariant,
                scrollOffset,
                Math.max(1, focusToken + 1),
                Optional.of(deletion.returnFocusWidgetId()),
                Optional.empty(),
                textResolver);
    }

    public AddSourceModel removeConfirmedPersonalSkin() {
        PersonalSkinDeletion deletion = personalSkinDeletion.orElseThrow(
                () -> new IllegalStateException("no personal skin deletion is pending"));
        List<SkinCatalogSource.CollectionDescriptor> sortedCollections = collections();
        SkinCatalogSource.CollectionDescriptor personal = sortedCollections.stream()
                .filter(collection -> collection.id().equals(deletion.collectionId()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("personal collection is unavailable"));
        List<SkinCatalogSource.SkinDescriptor> visible = visibleSkins(personal);
        int removedIndex = -1;
        for (int index = 0; index < visible.size(); index++) {
            if (visible.get(index).id().equals(deletion.sha256())) {
                removedIndex = index;
                break;
            }
        }

        List<SkinCatalogSource.CollectionDescriptor> nextCollections = new java.util.ArrayList<>();
        for (SkinCatalogSource.CollectionDescriptor collection : collections) {
            if (collection.order().kind() != CatalogCollectionOrder.Kind.PERSONAL) {
                nextCollections.add(collection);
                continue;
            }
            List<SkinCatalogSource.SkinDescriptor> remaining = collection.skins().stream()
                    .filter(skin -> !skin.id().equals(deletion.sha256()))
                    .toList();
            if (!remaining.isEmpty()) {
                nextCollections.add(new SkinCatalogSource.CollectionDescriptor(
                        collection.id(),
                        collection.nameText(),
                        collection.descriptionText(),
                        collection.authorsText(),
                        remaining,
                        collection.order()));
            }
        }

        String nextFocusId;
        List<SkinCatalogSource.SkinDescriptor> remainingVisible = visible.stream()
                .filter(skin -> !skin.id().equals(deletion.sha256()))
                .toList();
        if (!remainingVisible.isEmpty()) {
            int nextIndex = removedIndex < 0
                    ? 0
                    : Math.min(removedIndex, remainingVisible.size() - 1);
            SkinCatalogSource.SkinDescriptor next = remainingVisible.get(nextIndex);
            nextFocusId = "add.catalog.skin:"
                    + personal.id()
                    + ":"
                    + next.id();
        } else if (nextCollections.stream().anyMatch(
                collection -> collection.order().kind() == CatalogCollectionOrder.Kind.PERSONAL)) {
            nextFocusId = "add.catalog.collection:" + personal.id();
        } else if (!nextCollections.isEmpty()) {
            SkinCatalogSource.CollectionDescriptor nextCollection = nextCollections.stream()
                    .sorted(collectionComparator(nextCollections.stream().collect(
                            java.util.stream.Collectors.toMap(
                                    SkinCatalogSource.CollectionDescriptor::id,
                                    this::collectionName))))
                    .findFirst()
                    .orElseThrow();
            nextFocusId = "add.catalog.collection:" + nextCollection.id();
        } else {
            nextFocusId = "add.catalog.search";
        }

        return new AddSourceModel(
                selectedTab,
                nextCollections,
                collapsedCollectionIds,
                query,
                playerInput,
                urlInput,
                filter,
                preferredVariant,
                remainingVisible.isEmpty() ? 0 : scrollOffset,
                Math.max(1, focusToken + 1),
                Optional.of(nextFocusId),
                Optional.empty(),
                textResolver);
    }

    public List<SkinCatalogSource.SkinDescriptor> visibleSkins(
            SkinCatalogSource.CollectionDescriptor collection) {
        Objects.requireNonNull(collection, "collection");
        String needle = query.toLowerCase(Locale.ROOT);
        Map<String, String> names = new HashMap<>();
        collection.skins().forEach(skin -> names.put(skin.id(), skinName(skin)));
        java.util.stream.Stream<SkinCatalogSource.SkinDescriptor> visible =
                collection.skins().stream()
                        .filter(skin -> needle.isEmpty()
                                || names.get(skin.id())
                                        .toLowerCase(Locale.ROOT)
                                        .contains(needle))
                        .filter(this::matchesFilter);
        if (collection.order().kind() == CatalogCollectionOrder.Kind.RESOURCE_PACK) {
            visible = visible.sorted(Comparator
                    .comparing(
                            (SkinCatalogSource.SkinDescriptor skin) -> names.get(skin.id()),
                            String.CASE_INSENSITIVE_ORDER)
                    .thenComparing(skin -> names.get(skin.id()))
                    .thenComparing(SkinCatalogSource.SkinDescriptor::id));
        }
        return visible.toList();
    }

    public String collectionName(SkinCatalogSource.CollectionDescriptor collection) {
        Objects.requireNonNull(collection, "collection");
        return resolve(collection.nameText());
    }

    public String skinName(SkinCatalogSource.SkinDescriptor skin) {
        Objects.requireNonNull(skin, "skin");
        return resolve(skin.nameText());
    }

    public Optional<String> collectionInfo(SkinCatalogSource.CollectionDescriptor collection) {
        return metadata(collection.descriptionText(), collection.authorsText());
    }

    public Optional<String> skinInfo(
            SkinCatalogSource.CollectionDescriptor collection,
            SkinCatalogSource.SkinDescriptor skin) {
        Objects.requireNonNull(collection, "collection");
        return metadata(skin.descriptionText(), skin.authorsText());
    }

    private Optional<String> metadata(
            Optional<CatalogText> description, Optional<CatalogText> authors) {
        StringBuilder result = new StringBuilder();
        description.map(this::resolve).filter(value -> !value.isBlank()).ifPresent(value ->
                result.append(value.trim()));
        authors.map(this::resolve).filter(value -> !value.isBlank()).ifPresent(value -> {
            if (!result.isEmpty()) {
                result.append('\n');
            }
            result.append(value.trim());
        });
        return result.isEmpty() ? Optional.empty() : Optional.of(result.toString());
    }

    public AddSourceModel renamedPersonalSkin(String sha256, String displayName) {
        List<SkinCatalogSource.CollectionDescriptor> renamed = collections.stream().map(collection -> {
            if (collection.order().kind() != CatalogCollectionOrder.Kind.PERSONAL) {
                return collection;
            }
            List<SkinCatalogSource.SkinDescriptor> skins = collection.skins().stream()
                    .map(skin -> skin.id().equals(sha256)
                            ? new SkinCatalogSource.SkinDescriptor(
                                    skin.id(), CatalogText.literal(displayName),
                                    skin.descriptionText(), skin.authorsText(), skin.models())
                            : skin)
                    .toList();
            return new SkinCatalogSource.CollectionDescriptor(
                    collection.id(), collection.nameText(), collection.descriptionText(),
                    collection.authorsText(), skins, collection.order());
        }).toList();
        return new AddSourceModel(
                selectedTab, renamed, collapsedCollectionIds, query, playerInput, urlInput,
                filter, preferredVariant, scrollOffset, focusToken, focusWidgetId,
                personalSkinDeletion, textResolver);
    }

    public SkinVariant selectedVariant(SkinCatalogSource.SkinDescriptor skin) {
        Objects.requireNonNull(skin, "skin");
        return switch (filter) {
            case CLASSIC -> requireModel(skin, SkinModel.CLASSIC, SkinVariant.CLASSIC);
            case SLIM -> requireModel(skin, SkinModel.SLIM, SkinVariant.SLIM);
            case ALL -> preferredVariant == SkinVariant.SLIM
                    ? preferredOrFallback(
                            skin,
                            SkinModel.SLIM,
                            SkinVariant.SLIM,
                            SkinModel.CLASSIC,
                            SkinVariant.CLASSIC)
                    : preferredOrFallback(
                            skin,
                            SkinModel.CLASSIC,
                            SkinVariant.CLASSIC,
                            SkinModel.SLIM,
                            SkinVariant.SLIM);
        };
    }

    private boolean matchesFilter(SkinCatalogSource.SkinDescriptor skin) {
        return switch (filter) {
            case ALL -> true;
            case CLASSIC -> skin.models().contains(SkinModel.CLASSIC);
            case SLIM -> skin.models().contains(SkinModel.SLIM);
        };
    }

    private static SkinVariant requireModel(
            SkinCatalogSource.SkinDescriptor skin, SkinModel model, SkinVariant variant) {
        if (!skin.models().contains(model)) {
            throw new IllegalArgumentException("Catalog skin does not provide " + model);
        }
        return variant;
    }

    private static SkinVariant preferredOrFallback(
            SkinCatalogSource.SkinDescriptor skin,
            SkinModel preferredModel,
            SkinVariant preferredVariant,
            SkinModel fallbackModel,
            SkinVariant fallbackVariant) {
        return skin.models().contains(preferredModel)
                ? preferredVariant
                : requireModel(skin, fallbackModel, fallbackVariant);
    }

    private AddSourceModel copy(
            AddSourceTab nextTab,
            Set<String> nextCollapsed,
            String nextQuery,
            CatalogFilter nextFilter,
            SkinVariant nextPreferredVariant,
            int nextScrollOffset,
            long nextFocusToken,
            Optional<String> nextFocusWidgetId,
            Optional<PersonalSkinDeletion> nextPersonalSkinDeletion) {
        return copy(nextTab, nextCollapsed, nextQuery, playerInput, urlInput, nextFilter,
                nextPreferredVariant, nextScrollOffset, nextFocusToken, nextFocusWidgetId,
                nextPersonalSkinDeletion);
    }

    private AddSourceModel copy(
            AddSourceTab nextTab,
            Set<String> nextCollapsed,
            String nextQuery,
            String nextPlayerInput,
            String nextUrlInput,
            CatalogFilter nextFilter,
            SkinVariant nextPreferredVariant,
            int nextScrollOffset,
            long nextFocusToken,
            Optional<String> nextFocusWidgetId,
            Optional<PersonalSkinDeletion> nextPersonalSkinDeletion) {
        return new AddSourceModel(
                nextTab,
                collections,
                nextCollapsed,
                nextQuery,
                nextPlayerInput,
                nextUrlInput,
                nextFilter,
                nextPreferredVariant,
                nextScrollOffset,
                nextFocusToken,
                nextFocusWidgetId,
                nextPersonalSkinDeletion,
                textResolver);
    }

    private static Comparator<SkinCatalogSource.CollectionDescriptor> collectionComparator(
            Map<String, String> names) {
        return Comparator
                .comparingInt(AddSourceModel::collectionGroup)
                .thenComparingInt(AddSourceModel::personalCollectionOrder)
                .thenComparingInt(collection -> collection.order().sourceOrderKnown()
                        ? collection.order().sourceOrder()
                        : Integer.MAX_VALUE)
                .thenComparing(
                        collection -> names.get(collection.id()), String.CASE_INSENSITIVE_ORDER)
                .thenComparing(collection -> names.get(collection.id()))
                .thenComparing(SkinCatalogSource.CollectionDescriptor::id);
    }

    private static int collectionGroup(SkinCatalogSource.CollectionDescriptor collection) {
        if (collection.order().kind() == CatalogCollectionOrder.Kind.PERSONAL) {
            return 0;
        }
        if (collection.order().kind() == CatalogCollectionOrder.Kind.VANILLA) {
            return 3;
        }
        return collection.order().sourceOrderKnown() ? 1 : 2;
    }

    private static int personalCollectionOrder(
            SkinCatalogSource.CollectionDescriptor collection) {
        if (PersonalSkinCatalog.COLLECTION_ID.equals(collection.id())) {
            return 0;
        }
        if (PersonalSkinCatalog.OTHER_PLAYERS_COLLECTION_ID
                .equals(collection.id())) {
            return 1;
        }
        return 2;
    }

    private String resolve(CatalogText text) {
        String resolved = Objects.requireNonNull(
                textResolver.resolve(text), "resolved catalog text");
        return resolved.isBlank() ? text.fallback() : resolved;
    }

    public record PersonalSkinDeletion(
            String collectionId,
            String sha256,
            String displayName,
            String returnFocusWidgetId) {
        public PersonalSkinDeletion {
            Objects.requireNonNull(collectionId, "collectionId");
            Objects.requireNonNull(sha256, "sha256");
            Objects.requireNonNull(displayName, "displayName");
            Objects.requireNonNull(returnFocusWidgetId, "returnFocusWidgetId");
            if (collectionId.isBlank()
                    || !sha256.matches("[0-9a-f]{64}")
                    || displayName.isBlank()
                    || returnFocusWidgetId.isBlank()) {
                throw new IllegalArgumentException("invalid personal skin deletion");
            }
        }
    }
}

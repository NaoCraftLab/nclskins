package com.naocraftlab.skins.runtime;

import com.naocraftlab.skins.client.CapeCatalogSource;
import com.naocraftlab.skins.client.CatalogText;
import com.naocraftlab.skins.core.model.AccountState;
import com.naocraftlab.skins.core.model.LocalCapeReference;
import com.naocraftlab.skins.core.provider.AppearanceProviders;
import com.naocraftlab.skins.core.provider.BuiltinProvider;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public record CapeCatalogModel(
        List<Card> cards, AppearanceProviders providers, LocalCapeReference offline,
        Optional<String> minecraft, String query, int filter, Set<String> collapsed,
        Card inspected, UUID editing, boolean deleting, String renameValue,
        UiMessage importError, TextResolver textResolver) {
    private static final String PERSONAL_COLLECTION = "OFFLINE";
    private static final String MINECRAFT_COLLECTION = "MINECRAFT";
    private static final String RESOURCE_PREFIX = "resource:";

    public CapeCatalogModel {
        cards = List.copyOf(cards);
        collapsed = Set.copyOf(collapsed);
        minecraft = Objects.requireNonNull(minecraft, "minecraft");
        textResolver = Objects.requireNonNull(textResolver, "textResolver");
    }

    public CapeCatalogModel(List<Card> cards, AppearanceProviders providers, LocalCapeReference offline,
            Optional<String> minecraft, String query, int filter, Set<String> collapsed,
            Card inspected, UUID editing, boolean deleting, String renameValue, boolean formatError) {
        this(cards, providers, offline, minecraft, query, filter, collapsed, inspected, editing,
                deleting, renameValue,
                formatError ? UiMessage.error("nclskins.capes.format_error") : null,
                UiMessage::key);
    }

    public CapeCatalogModel(List<Card> cards, AppearanceProviders providers, LocalCapeReference offline,
            Optional<String> minecraft, String query, int filter, Set<String> collapsed,
            Card inspected, UUID editing, boolean deleting, String renameValue, boolean formatError,
            TextResolver textResolver) {
        this(cards, providers, offline, minecraft, query, filter, collapsed, inspected, editing,
                deleting, renameValue,
                formatError ? UiMessage.error("nclskins.capes.format_error") : null,
                textResolver);
    }

    public boolean formatError() {
        return importError != null && importError.key().equals("nclskins.capes.format_error");
    }

    public CapeCatalogModel withImportError(UiMessage message) {
        return new CapeCatalogModel(cards, providers, offline, minecraft, query, filter, collapsed,
                inspected, editing, deleting, renameValue, message, textResolver);
    }

    public static CapeCatalogModel open(AccountState account, AppearanceProviders providers,
            LocalCapeReference offline, Optional<String> minecraft,
            List<PresetEditorModel.CapeChoice> owned, TextResolver text) {
        return open(account, providers, offline, minecraft, owned, List.of(), Map.of(),
                Long.MIN_VALUE, text);
    }

    public static CapeCatalogModel open(AccountState account, AppearanceProviders providers,
            LocalCapeReference offline, Optional<String> minecraft,
            List<PresetEditorModel.CapeChoice> owned,
            List<CapeCatalogSource.CollectionDescriptor> resourceCollections,
            Map<ClientOperations.ResourceCapeKey, String> sourceHashes,
            long resourceGeneration,
            TextResolver text) {
        Objects.requireNonNull(account, "account");
        Objects.requireNonNull(resourceCollections, "resourceCollections");
        Objects.requireNonNull(sourceHashes, "sourceHashes");
        List<Card> cards = new ArrayList<>();
        Map<String, ClientOperations.ResourceCapeSelection> firstResourceByIdentity =
                new LinkedHashMap<>();
        for (CapeCatalogSource.CollectionDescriptor collection : resourceCollections) {
            for (CapeCatalogSource.CapeDescriptor cape : collection.capes()) {
                String sourceHash = sourceHashes.get(new ClientOperations.ResourceCapeKey(
                        collection.id(), cape.id()));
                if (sourceHash == null) {
                    continue;
                }
                String displayName = resolved(text, cape.nameText());
                firstResourceByIdentity.putIfAbsent(
                        cape.contentIdentity(),
                        new ClientOperations.ResourceCapeSelection(
                                collection.id(), cape.id(), displayName,
                                cape.contentIdentity(), sourceHash, resourceGeneration,
                                cape.renderSupport()
                                        == CapeCatalogSource.RenderSupport.CAPE_AND_ELYTRA));
            }
        }

        UiMessage personalLabel = UiMessage.info("nclskins.capes.personal");
        cards.add(Card.personalService(
                "import", personalLabel, UiMessage.info("nclskins.capes.import"), true));
        cards.add(Card.personalService(
                "none", personalLabel, UiMessage.info("nclskins.editor.no_cape"), false));
        account.personalCapes().stream()
                .filter(entry -> !firstResourceByIdentity.containsKey(entry.renderSha256()))
                .forEach(entry -> cards.add(Card.personal(
                        entry.texture().entryId().toString(), entry.name(), entry.texture())));

        for (CapeCatalogSource.CollectionDescriptor collection : resourceCollections) {
            String collectionName = resolved(text, collection.nameText());
            Optional<String> collectionInfo = metadata(
                    text, collection.descriptionText(), collection.authorsText());
            for (CapeCatalogSource.CapeDescriptor cape : collection.capes()) {
                ClientOperations.ResourceCapeKey key = new ClientOperations.ResourceCapeKey(
                        collection.id(), cape.id());
                String sourceHash = sourceHashes.get(key);
                if (sourceHash == null) {
                    continue;
                }
                String displayName = resolved(text, cape.nameText());
                ClientOperations.ResourceCapeSelection resource =
                        new ClientOperations.ResourceCapeSelection(
                                collection.id(), cape.id(), displayName,
                                cape.contentIdentity(), sourceHash, resourceGeneration,
                                cape.renderSupport()
                                        == CapeCatalogSource.RenderSupport.CAPE_AND_ELYTRA);
                cards.add(Card.resource(
                        cape.id(), RESOURCE_PREFIX + collection.id(), collectionName,
                        collectionInfo, displayName,
                        metadata(text, cape.descriptionText(), cape.authorsText()), resource));
            }
        }

        UiMessage minecraftLabel = UiMessage.info("nclskins.capes.minecraft");
        cards.add(Card.minecraft(
                "none", minecraftLabel, UiMessage.info("nclskins.editor.no_cape"), "", null));
        owned.stream().filter(choice -> choice.id().isPresent()).forEach(choice -> cards.add(
                Card.minecraft(choice.id().orElseThrow(), minecraftLabel, choice.label(),
                        text.resolve(choice.label()), choice.hasElytra())));

        Card remapped = null;
        if (offline != null && offline.entryId() != null) {
            String selectedIdentity = account.personalCapes().stream()
                    .filter(entry -> entry.texture().equals(offline))
                    .map(com.naocraftlab.skins.core.model.PersonalCapeEntry::renderSha256)
                    .findFirst()
                    .orElse(null);
            ClientOperations.ResourceCapeSelection owner = firstResourceByIdentity.get(selectedIdentity);
            if (owner != null) {
                remapped = cards.stream().filter(card -> owner.equals(card.resource()))
                        .findFirst().orElse(null);
            }
        }
        LocalCapeReference displayedOffline = remapped == null ? offline : new LocalCapeReference(
                null, remapped.resource().sourceSha256(), remapped.resource().hasElytra());
        return new CapeCatalogModel(cards, providers, displayedOffline, minecraft, "", 0, Set.of(),
                remapped, null, false, "", false, text);
    }

    private static String resolved(TextResolver text, CatalogText value) {
        String resolved = Objects.requireNonNull(text.resolve(value), "resolved catalog text").trim();
        return resolved.isEmpty() ? value.fallback() : resolved;
    }

    private static Optional<String> metadata(TextResolver text,
            Optional<CatalogText> description, Optional<CatalogText> authors) {
        StringBuilder result = new StringBuilder();
        description.map(value -> resolved(text, value)).filter(value -> !value.isBlank())
                .ifPresent(value -> result.append(value.trim()));
        authors.map(value -> resolved(text, value)).filter(value -> !value.isBlank())
                .ifPresent(value -> {
                    if (!result.isEmpty()) {
                        result.append('\n');
                    }
                    result.append(value.trim());
                });
        return result.isEmpty() ? Optional.empty() : Optional.of(result.toString());
    }

    public CapeCatalogModel withOwnedClassification(
            List<com.naocraftlab.skins.core.model.OwnedCapeEntry> owned) {
        List<Card> updated = cards.stream().map(card -> {
            if (card.provider() != BuiltinProvider.MINECRAFT || card.service()) {
                return card;
            }
            Boolean classification = owned.stream()
                    .filter(entry -> entry.id().equals(card.key()))
                    .map(com.naocraftlab.skins.core.model.OwnedCapeEntry::hasElytra)
                    .filter(Objects::nonNull).findFirst().orElse(card.hasElytra());
            return card.withHasElytra(classification);
        }).toList();
        Card inspection = inspected == null ? null : updated.stream()
                .filter(card -> card.widgetId().equals(inspected.widgetId()))
                .findFirst().orElse(inspected);
        return new CapeCatalogModel(updated, providers, offline, minecraft, query, filter,
                collapsed, inspection, editing, deleting, renameValue, importError, textResolver);
    }

    public CapeCatalogModel refreshed(AccountState account, AppearanceProviders currentProviders,
            LocalCapeReference currentOffline, Optional<String> currentMinecraft,
            List<PresetEditorModel.CapeChoice> owned) {
        List<Card> resourceCards = cards.stream().filter(card -> card.resource() != null).toList();
        Set<String> resourceIdentities = resourceCards.stream()
                .map(card -> card.resource().contentIdentity())
                .collect(java.util.stream.Collectors.toSet());
        List<Card> updated = new ArrayList<>();
        UiMessage personalLabel = UiMessage.info("nclskins.capes.personal");
        updated.add(Card.personalService(
                "import", personalLabel, UiMessage.info("nclskins.capes.import"), true));
        updated.add(Card.personalService(
                "none", personalLabel, UiMessage.info("nclskins.editor.no_cape"), false));
        account.personalCapes().stream()
                .filter(entry -> !resourceIdentities.contains(entry.renderSha256()))
                .forEach(entry -> updated.add(Card.personal(
                        entry.texture().entryId().toString(), entry.name(), entry.texture())));
        updated.addAll(resourceCards);
        UiMessage minecraftLabel = UiMessage.info("nclskins.capes.minecraft");
        updated.add(Card.minecraft(
                "none", minecraftLabel, UiMessage.info("nclskins.editor.no_cape"), "", null));
        owned.stream().filter(choice -> choice.id().isPresent()).forEach(choice -> updated.add(
                Card.minecraft(choice.id().orElseThrow(), minecraftLabel, choice.label(),
                        textResolver.resolve(choice.label()), choice.hasElytra())));

        Card nextInspection = inspected;
        LocalCapeReference nextOffline = currentOffline;
        if (nextInspection != null) {
            String widgetId = nextInspection.widgetId();
            nextInspection = updated.stream().filter(card -> card.widgetId().equals(widgetId))
                    .findFirst().orElse(null);
        }
        if (nextInspection == null && currentOffline != null && currentOffline.entryId() != null) {
            String selectedIdentity = account.personalCapes().stream()
                    .filter(entry -> entry.texture().equals(currentOffline))
                    .map(com.naocraftlab.skins.core.model.PersonalCapeEntry::renderSha256)
                    .findFirst().orElse(null);
            if (selectedIdentity != null) {
                nextInspection = updated.stream().filter(card -> card.resource() != null)
                        .filter(card -> card.resource().contentIdentity().equals(selectedIdentity))
                        .findFirst().orElse(null);
                if (nextInspection != null) {
                    nextOffline = new LocalCapeReference(
                            null,
                            nextInspection.resource().sourceSha256(),
                            nextInspection.resource().hasElytra());
                }
            }
        }
        UUID nextEditing = editing != null && updated.stream()
                .anyMatch(card -> card.local() != null && editing.equals(card.local().entryId()))
                ? editing : null;
        return new CapeCatalogModel(updated, currentProviders, nextOffline, currentMinecraft,
                query, filter, collapsed, nextInspection, nextEditing,
                nextEditing != null && deleting, nextEditing == null ? "" : renameValue,
                importError, textResolver);
    }

    public CapeCatalogModel withQuery(String value) {
        return copy(offline, minecraft, value, filter, collapsed, inspected, null, false, "", false);
    }

    public CapeCatalogModel cycleFilter(boolean reverse) {
        return copy(offline, minecraft, query, Math.floorMod(filter + (reverse ? -1 : 1), 3),
                collapsed, inspected, null, false, "", false);
    }

    public List<String> visibleCollections() {
        LinkedHashMap<String, Boolean> collections = new LinkedHashMap<>();
        String search = query.trim().toLowerCase(Locale.ROOT);
        for (Card card : cards) {
            if (collectionEnabled(card.collectionId()) && matches(card, search)) {
                collections.putIfAbsent(card.collectionId(), Boolean.TRUE);
            }
        }
        return List.copyOf(collections.keySet());
    }

    public List<Card> matches(String collectionId) {
        if (!collectionEnabled(collectionId)) {
            return List.of();
        }
        String search = query.trim().toLowerCase(Locale.ROOT);
        return cards.stream().filter(card -> card.collectionId().equals(collectionId))
                .filter(card -> matches(card, search)).toList();
    }

    private boolean matches(Card card, String search) {
        if (card.service()) {
            return search.isEmpty();
        }
        return card.name().toLowerCase(Locale.ROOT).contains(search)
                && (filter == 0 || card.hasElytra() != null
                        && card.hasElytra() == (filter == 1));
    }

    public List<Card> matches(BuiltinProvider provider) {
        return matches(provider.name());
    }

    private boolean collectionEnabled(String collectionId) {
        BuiltinProvider provider = MINECRAFT_COLLECTION.equals(collectionId)
                ? BuiltinProvider.MINECRAFT : BuiltinProvider.OFFLINE;
        return providers.cape().enabled(provider);
    }

    public CapeCatalogModel toggle(String collectionId) {
        Set<String> next = new HashSet<>(collapsed);
        if (!next.remove(collectionId)) {
            next.add(collectionId);
        }
        return copy(offline, minecraft, query, filter, next, inspected, null, false, "", false);
    }

    public CapeCatalogModel toggle(BuiltinProvider provider) {
        return toggle(provider.name());
    }

    public CapeCatalogModel toggleAll() {
        Set<String> next = new HashSet<>(collapsed);
        List<String> collections = visibleCollections();
        boolean expand = collections.stream().anyMatch(collapsed::contains);
        if (expand) {
            next.removeAll(collections);
        } else {
            next.addAll(collections);
        }
        return copy(offline, minecraft, query, filter, next, inspected, null, false, "", false);
    }

    public CapeCatalogModel choose(Card card) {
        if (card.importCard()) {
            return this;
        }
        LocalCapeReference nextOffline = card.provider() == BuiltinProvider.OFFLINE
                ? card.resource() == null
                        ? card.local()
                        : new LocalCapeReference(
                                null, card.resource().sourceSha256(), card.resource().hasElytra())
                : offline;
        Optional<String> nextMinecraft = card.provider() == BuiltinProvider.MINECRAFT
                ? card.key().equals("none") ? Optional.empty() : Optional.of(card.key())
                : minecraft;
        return copy(nextOffline, nextMinecraft, query, filter, collapsed, card, null, false, "", false);
    }

    public CapeCatalogModel inspect(BuiltinProvider provider) {
        return inspectObserved(provider, null);
    }

    public CapeCatalogModel inspect(BuiltinProvider provider, AccountState account) {
        var observation = providers.cape().observation(provider);
        String visualIdentity = provider == BuiltinProvider.OFFLINE && observation.value() != null
                ? account.personalCapes().stream()
                        .filter(entry -> entry.texture().entryId().toString().equals(observation.value().id())
                                || entry.texture().sha256().equals(observation.value().textureCacheKey()))
                        .map(com.naocraftlab.skins.core.model.PersonalCapeEntry::renderSha256)
                        .findFirst().orElse(null)
                : null;
        return inspectObserved(provider, visualIdentity);
    }

    private CapeCatalogModel inspectObserved(BuiltinProvider provider, String visualIdentity) {
        var observation = providers.cape().observation(provider);
        if (!observation.known()) {
            return this;
        }
        String key = observation.value() == null ? "none" : observation.value().id();
        Card selected = cards.stream()
                .filter(card -> card.provider() == provider && card.key().equals(key))
                .findFirst().orElse(null);
        if (provider == BuiltinProvider.OFFLINE && visualIdentity != null) {
            selected = resourceOwner(visualIdentity).orElse(selected);
        }
        if (selected == null && provider == BuiltinProvider.OFFLINE
                && observation.value() != null
                && observation.value().textureCacheKey() != null) {
            selected = resourceOwnerBySourceHash(observation.value().textureCacheKey()).orElse(null);
        }
        if (selected == null && observation.value() != null) {
            var value = observation.value();
            LocalCapeReference reference = provider == BuiltinProvider.OFFLINE
                    && value.textureCacheKey() != null
                    ? new LocalCapeReference(null, value.textureCacheKey(),
                            !Boolean.FALSE.equals(value.hasElytra()))
                    : null;
            selected = provider == BuiltinProvider.OFFLINE
                    ? Card.personal(key,
                            textResolver.resolve(UiMessage.info("options.modelPart.cape")), reference)
                    : Card.minecraft(key, UiMessage.info("nclskins.capes.minecraft"),
                            UiMessage.info("options.modelPart.cape"), "", value.hasElytra());
        }
        Set<String> expanded = new HashSet<>(collapsed);
        expanded.remove(selected == null ? provider.name() : selected.collectionId());
        return copy(offline, minecraft, "", 0, expanded, selected, null, false, "", false);
    }

    public CapeCatalogModel resetInspection() {
        return copy(offline, minecraft, query, filter, collapsed, null, null, false, "", false);
    }

    public CapeCatalogModel edit(UUID id, boolean delete) {
        String name = cards.stream().filter(card -> card.key().equals(id.toString()))
                .map(Card::name).findFirst().orElse("");
        return copy(offline, minecraft, query, filter, collapsed, inspected, id, delete, name, false);
    }

    public CapeCatalogModel rename(String value) {
        return copy(offline, minecraft, query, filter, collapsed, inspected, editing, deleting,
                value, false);
    }

    public CapeCatalogModel cancelEdit() {
        return copy(offline, minecraft, query, filter, collapsed, inspected, null, false, "", false);
    }

    public CapeCatalogModel error(boolean value) {
        return copy(offline, minecraft, query, filter, collapsed, inspected, editing, deleting,
                renameValue, value);
    }

    public Optional<String> previewCape() {
        if (inspected != null) {
            return inspected.texture();
        }
        Optional<Card> selectedResourceCard = selectedResourceCard();
        if (selectedResourceCard.isPresent()) {
            return selectedResourceCard.orElseThrow().texture();
        }
        for (BuiltinProvider provider : providers.cape().order()) {
            if (provider == BuiltinProvider.OFFLINE && offline != null) {
                return Optional.of("provider:cape:" + offline.sha256());
            }
            if (provider == BuiltinProvider.MINECRAFT && minecraft.isPresent()) {
                return minecraft;
            }
        }
        return Optional.empty();
    }

    public boolean previewHasElytra() {
        if (inspected != null) {
            return !Boolean.FALSE.equals(inspected.hasElytra());
        }
        Optional<ClientOperations.ResourceCapeSelection> selectedResource = selectedResource();
        if (selectedResource.isPresent()) {
            return selectedResource.orElseThrow().hasElytra();
        }
        for (BuiltinProvider provider : providers.cape().order()) {
            if (provider == BuiltinProvider.OFFLINE && offline != null) {
                return offline.hasElytra();
            }
            if (provider == BuiltinProvider.MINECRAFT && minecraft.isPresent()) {
                return cards.stream()
                        .filter(card -> card.provider() == provider
                                && minecraft.filter(card.key()::equals).isPresent())
                        .noneMatch(card -> Boolean.FALSE.equals(card.hasElytra()));
            }
        }
        return true;
    }

    public CapeCatalogModel withCollapsed(Set<String> value) {
        return copy(offline, minecraft, query, filter, value, inspected, editing, deleting,
                renameValue, formatError());
    }

    public CapeCatalogModel reveal(Card card) {
        Set<String> next = new HashSet<>(collapsed);
        next.remove(card.collectionId());
        return copy(offline, minecraft, "", 0, next, inspected, null, false, "", false);
    }

    public boolean selected(Card card) {
        if (inspected != null && inspected.provider() == card.provider()) {
            return inspected.widgetId().equals(card.widgetId());
        }
        if (card.resource() != null) {
            return selectedResource().filter(card.resource()::equals).isPresent();
        }
        return !card.importCard() && (card.provider() == BuiltinProvider.OFFLINE
                ? offline == null
                        ? card.collectionId().equals(PERSONAL_COLLECTION) && card.key().equals("none")
                        : offline.equals(card.local())
                : minecraft.map(card.key()::equals).orElse(card.key().equals("none")));
    }

    public Optional<ClientOperations.ResourceCapeSelection> selectedResource() {
        if (offline == null || offline.entryId() != null) {
            return Optional.empty();
        }
        return cards.stream().map(Card::resource).filter(Objects::nonNull)
                .filter(resource -> resource.sourceSha256().equals(offline.sha256()))
                .findFirst();
    }

    private Optional<Card> selectedResourceCard() {
        Optional<ClientOperations.ResourceCapeSelection> selected = selectedResource();
        return selected.flatMap(resource -> cards.stream()
                .filter(card -> resource.equals(card.resource())).findFirst());
    }

    public Optional<Card> resourceOwner(String renderSha256) {
        return cards.stream().filter(card -> card.resource() != null)
                .filter(card -> card.resource().contentIdentity().equals(renderSha256))
                .findFirst();
    }

    private Optional<Card> resourceOwnerBySourceHash(String sourceSha256) {
        return cards.stream().filter(card -> card.resource() != null)
                .filter(card -> card.resource().sourceSha256().equals(sourceSha256))
                .findFirst();
    }

    public Optional<String> collectionInfo(String collectionId) {
        return cards.stream().filter(card -> card.collectionId().equals(collectionId))
                .map(Card::collectionInfo).findFirst().orElse(Optional.empty());
    }

    public UiMessage collectionLabel(String collectionId) {
        return cards.stream().filter(card -> card.collectionId().equals(collectionId))
                .map(Card::collectionLabel).findFirst().orElseThrow();
    }

    private CapeCatalogModel copy(LocalCapeReference local, Optional<String> owned, String search,
            int selection, Set<String> disclosure, Card inspection, UUID edit, boolean delete,
            String rename, boolean error) {
        return new CapeCatalogModel(cards, providers, local, owned, search, selection, disclosure,
                inspection, edit, delete, rename, error, textResolver);
    }

    public record Card(
            String key, BuiltinProvider provider, String collectionId,
            UiMessage collectionLabel, Optional<String> collectionInfo, UiMessage label,
            String name, Optional<String> info, LocalCapeReference local,
            ClientOperations.ResourceCapeSelection resource, Boolean hasElytra,
            boolean importCard) {
        public Card {
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(provider, "provider");
            Objects.requireNonNull(collectionId, "collectionId");
            Objects.requireNonNull(collectionLabel, "collectionLabel");
            collectionInfo = Objects.requireNonNull(collectionInfo, "collectionInfo");
            Objects.requireNonNull(label, "label");
            Objects.requireNonNull(name, "name");
            info = Objects.requireNonNull(info, "info");
        }

        public Card(String key, BuiltinProvider provider, UiMessage label, String name,
                LocalCapeReference local, Boolean hasElytra, boolean importCard) {
            this(key, provider, provider.name(),
                    UiMessage.info(provider == BuiltinProvider.OFFLINE
                            ? "nclskins.capes.personal" : "nclskins.capes.minecraft"),
                    Optional.empty(), label, name, Optional.empty(), local, null, hasElytra,
                    importCard);
        }

        static Card personalService(String key, UiMessage collectionLabel, UiMessage label,
                boolean importCard) {
            return new Card(key, BuiltinProvider.OFFLINE, PERSONAL_COLLECTION, collectionLabel,
                    Optional.empty(), label, "", Optional.empty(), null, null, null, importCard);
        }

        static Card personal(String key, String name, LocalCapeReference local) {
            return new Card(key, BuiltinProvider.OFFLINE, PERSONAL_COLLECTION,
                    UiMessage.info("nclskins.capes.personal"), Optional.empty(),
                    UiMessage.literal(name, UiMessage.Severity.INFO), name, Optional.empty(),
                    local, null, local == null ? null : local.hasElytra(), false);
        }

        static Card resource(String key, String collectionId, String collectionName,
                Optional<String> collectionInfo, String name, Optional<String> info,
                ClientOperations.ResourceCapeSelection resource) {
            return new Card(key, BuiltinProvider.OFFLINE, collectionId,
                    UiMessage.literal(collectionName, UiMessage.Severity.INFO), collectionInfo,
                    UiMessage.literal(name, UiMessage.Severity.INFO), name, info, null, resource,
                    resource.hasElytra(), false);
        }

        static Card minecraft(String key, UiMessage collectionLabel, UiMessage label, String name,
                Boolean hasElytra) {
            return new Card(key, BuiltinProvider.MINECRAFT, MINECRAFT_COLLECTION,
                    collectionLabel, Optional.empty(), label, name, Optional.empty(), null, null,
                    hasElytra, false);
        }

        public boolean service() {
            return importCard || key.equals("none");
        }

        public Optional<String> texture() {
            if (service()) {
                return Optional.empty();
            }
            if (resource != null) {
                return Optional.of("resource:cape:" + resource.generation() + ":"
                        + resource.collectionId() + ":" + resource.capeId() + ":"
                        + resource.sourceSha256());
            }
            return Optional.of(local == null ? key : "provider:cape:" + local.sha256());
        }

        public String widgetId() {
            if (resource != null) {
                return "editor.cape_item.RESOURCE." + resource.collectionId() + "." + key;
            }
            return "editor.cape_item." + provider.name() + "." + key;
        }

        private Card withHasElytra(Boolean value) {
            return new Card(key, provider, collectionId, collectionLabel, collectionInfo, label,
                    name, info, local, resource, value, importCard);
        }
    }
}

package com.naocraftlab.skins.client;

import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;


@FunctionalInterface
public interface SkinCatalogSource {

    byte[] load(String collectionId, String skinId, SkinModel model) throws IOException;


    default List<CollectionDescriptor> collections() {
        return MinecraftSkinCatalog.collections();
    }


    default long generation() {
        return Long.MIN_VALUE;
    }


    static SkinCatalogSource resourcePacksBeforeVanilla(
            SkinCatalogSource resourcePacks, SkinCatalogSource vanilla) {
        Objects.requireNonNull(resourcePacks, "resourcePacks");
        Objects.requireNonNull(vanilla, "vanilla");
        return new SkinCatalogSource() {
            @Override
            public byte[] load(String collectionId, String skinId, SkinModel model)
                    throws IOException {
                return MinecraftSkinCatalog.COLLECTION_ID.equals(collectionId)
                        ? vanilla.load(collectionId, skinId, model)
                        : resourcePacks.load(collectionId, skinId, model);
            }

            @Override
            public List<CollectionDescriptor> collections() {
                java.util.ArrayList<CollectionDescriptor> combined = new java.util.ArrayList<>();
                resourcePacks.collections().stream()
                        .filter(collection ->
                                !MinecraftSkinCatalog.COLLECTION_ID.equals(collection.id()))
                        .forEach(combined::add);
                vanilla.collections().stream()
                        .filter(collection ->
                                MinecraftSkinCatalog.COLLECTION_ID.equals(collection.id()))
                        .forEach(combined::add);
                return List.copyOf(combined);
            }

            @Override
            public long generation() {
                long resourceGeneration = resourcePacks.generation();
                long vanillaGeneration = vanilla.generation();
                if (resourceGeneration == Long.MIN_VALUE || vanillaGeneration == Long.MIN_VALUE) {
                    return Long.MIN_VALUE;
                }
                return 31L * resourceGeneration + vanillaGeneration;
            }
        };
    }


    default byte[] classic() throws IOException {
        return load(
                MinecraftSkinCatalog.COLLECTION_ID,
                MinecraftSkinCatalog.STEVE_SKIN_ID,
                SkinModel.CLASSIC);
    }


    default byte[] slim() throws IOException {
        return load(
                MinecraftSkinCatalog.COLLECTION_ID,
                MinecraftSkinCatalog.ALEX_SKIN_ID,
                SkinModel.SLIM);
    }


    static byte[] ownedCopy(byte[] pngBytes) {
        Objects.requireNonNull(pngBytes, "pngBytes");
        if (pngBytes.length == 0) {
            throw new IllegalArgumentException("Catalog skin PNG must not be empty");
        }
        return pngBytes.clone();
    }


    record CollectionDescriptor(
            String id,
            CatalogText nameText,
            Optional<CatalogText> descriptionText,
            Optional<CatalogText> authorsText,
            List<SkinDescriptor> skins,
            CatalogCollectionOrder order) {
        public CollectionDescriptor {
            id = requireStableId(id, "id");
            nameText = requireNameText(nameText, "nameText");
            descriptionText = optionalCatalogText(descriptionText, "descriptionText");
            authorsText = optionalCatalogText(authorsText, "authorsText");
            skins = List.copyOf(Objects.requireNonNull(skins, "skins"));
            order = Objects.requireNonNull(order, "order");

            Set<String> ids = new HashSet<>();
            for (SkinDescriptor skin : skins) {
                Objects.requireNonNull(skin, "skins contains null");
                if (!ids.add(skin.id())) {
                    throw new IllegalArgumentException("Duplicate catalog skin id: " + skin.id());
                }
            }
        }


        public CollectionDescriptor(
                String id,
                String name,
                Optional<String> description,
                Optional<String> author,
                List<SkinDescriptor> skins) {
            this(
                    id,
                    CatalogText.literal(requireText(name, "name")),
                    literalOptional(description, "description"),
                    literalOptional(author, "author"),
                    skins,
                    CatalogCollectionOrder.unspecified());
        }

        public String name() {
            return nameText.fallback();
        }

        public Optional<String> description() {
            return fallbackText(descriptionText);
        }

        public Optional<String> author() {
            return fallbackText(authorsText);
        }

        public String sourceId() {
            return order.sourceId();
        }
    }


    record SkinDescriptor(
            String id,
            CatalogText nameText,
            Optional<CatalogText> descriptionText,
            Optional<CatalogText> authorsText,
            List<SkinModel> models) {
        public SkinDescriptor {
            id = requireStableId(id, "id");
            nameText = requireNameText(nameText, "nameText");
            descriptionText = optionalCatalogText(descriptionText, "descriptionText");
            authorsText = optionalCatalogText(authorsText, "authorsText");
            models = List.copyOf(Objects.requireNonNull(models, "models"));
            if (models.isEmpty()) {
                throw new IllegalArgumentException("Catalog skin must declare at least one model");
            }

            Set<SkinModel> uniqueModels = new HashSet<>();
            for (SkinModel model : models) {
                Objects.requireNonNull(model, "models contains null");
                if (!uniqueModels.add(model)) {
                    throw new IllegalArgumentException("Duplicate catalog skin model: " + model);
                }
            }
        }


        public SkinDescriptor(
                String id,
                String name,
                Optional<String> description,
                Optional<String> author,
                List<SkinModel> models) {
            this(
                    id,
                    CatalogText.literal(requireText(name, "name")),
                    literalOptional(description, "description"),
                    literalOptional(author, "author"),
                    models);
        }

        public String name() {
            return nameText.fallback();
        }

        public Optional<String> description() {
            return fallbackText(descriptionText);
        }

        public Optional<String> author() {
            return fallbackText(authorsText);
        }
    }

    private static String requireText(String value, String field) {
        Objects.requireNonNull(value, field);
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }

    private static String requireStableId(String value, String field) {
        requireText(value, field);
        if (!value.matches("[a-z0-9][a-z0-9_-]{0,127}")) {
            throw new IllegalArgumentException(field + " must be a stable catalog id");
        }
        return value;
    }

    private static CatalogText requireNameText(CatalogText value, String field) {
        Objects.requireNonNull(value, field);
        requireText(value.fallback(), field + ".fallback");
        return value;
    }

    private static Optional<CatalogText> optionalCatalogText(
            Optional<CatalogText> value, String field) {
        Objects.requireNonNull(value, field);
        value.ifPresent(text -> Objects.requireNonNull(text, field + " contains null"));
        return value;
    }

    private static Optional<CatalogText> literalOptional(Optional<String> value, String field) {
        Objects.requireNonNull(value, field);
        return value.map(text -> CatalogText.literal(requireText(text, field)));
    }

    private static Optional<String> fallbackText(Optional<CatalogText> value) {
        return value.map(CatalogText::fallback).filter(text -> !text.isBlank());
    }
}

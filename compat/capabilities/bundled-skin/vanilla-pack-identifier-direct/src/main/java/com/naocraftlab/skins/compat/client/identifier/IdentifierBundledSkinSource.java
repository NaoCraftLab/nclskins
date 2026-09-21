package com.naocraftlab.skins.compat.client.identifier;

import com.naocraftlab.skins.client.MinecraftSkinCatalog;
import com.naocraftlab.skins.client.CapeCatalogSource;
import com.naocraftlab.skins.client.CatalogGenerationTracker;
import com.naocraftlab.skins.client.ResourcePackCatalogDiscovery;
import com.naocraftlab.skins.client.ResourcePackSkinCatalog;
import com.naocraftlab.skins.client.ResourcePackCapeCatalog;
import com.naocraftlab.skins.client.SkinCatalogSource;
import com.naocraftlab.skins.client.SkinModel;
import com.naocraftlab.skins.core.png.PngValidator;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;


public final class IdentifierBundledSkinSource implements SkinCatalogSource {
    private final CatalogGenerationTracker generations = new CatalogGenerationTracker();
    private final ResourcePackCatalogDiscovery.SnapshotCache snapshots =
            new ResourcePackCatalogDiscovery.SnapshotCache();

    @Override
    public byte[] load(String collectionId, String skinId, SkinModel model) throws IOException {
        if (!MinecraftSkinCatalog.COLLECTION_ID.equals(collectionId)) {
            return loadActiveResource(collectionId, skinId, model);
        }
        String path = MinecraftSkinCatalog.texturePath(collectionId, skinId, model);
        Identifier location = Identifier.withDefaultNamespace(path);
        var resource = Minecraft.getInstance()
                .getVanillaPackResources()
                .getResource(PackType.CLIENT_RESOURCES, location);
        if (resource == null) {
            throw new IOException("Catalog skin variant is unavailable: " + path);
        }
        try (InputStream input = resource.get()) {
            return readBounded(input);
        }
    }

    @Override
    public List<CollectionDescriptor> collections() {
        List<CollectionDescriptor> collections = new ArrayList<>(resourceSnapshot().skinCollections());
        collections.addAll(MinecraftSkinCatalog.collections());
        return List.copyOf(collections);
    }

    @Override
    public List<CapeCatalogSource.CollectionDescriptor> capeCollections() {
        return resourceSnapshot().capeCollections();
    }

    @Override
    public byte[] loadResource(String identifier) throws IOException {
        return findResource(identifier)
                .orElseThrow(() -> new IOException("Minecraft resource is unavailable"));
    }

    @Override
    public java.util.Optional<byte[]> findResource(String identifier) throws IOException {
        final Identifier location;
        try {
            location = Identifier.parse(identifier);
        } catch (RuntimeException invalid) {
            throw new IOException("Invalid Minecraft resource identifier", invalid);
        }
        java.util.Optional<Resource> resource = Minecraft.getInstance()
                .getResourceManager()
                .getResource(location);
        if (resource.isEmpty()) {
            return java.util.Optional.empty();
        }
        try (InputStream input = resource.orElseThrow().open()) {
            return java.util.Optional.of(readBounded(input));
        }
    }

    @Override
    public long generation() {
        ResourceManager resources = Minecraft.getInstance().getResourceManager();
        return generations.observe(
                resources,
                resources.listPacks().toList(),
                Minecraft.getInstance().getResourcePackRepository().getSelectedIds().stream().toList());
    }

    private static byte[] loadActiveResource(
            String collectionId, String skinId, SkinModel model) throws IOException {
        Identifier location = Identifier.fromNamespaceAndPath(
                collectionId, ResourcePackCatalogDiscovery.texturePath(skinId, model));
        Resource resource = Minecraft.getInstance()
                .getResourceManager()
                .getResource(location)
                .orElseThrow(() -> new IOException(
                        "Catalog skin variant is unavailable: " + location));
        try (InputStream input = resource.open()) {
            return readBounded(input);
        }
    }

    private ResourcePackCatalogDiscovery.Snapshot resourceSnapshot() {
        return snapshots.get(generation(), this::scanResourcePacks);
    }

    private ResourcePackCatalogDiscovery.Snapshot scanResourcePacks() {
        ResourceManager resources = Minecraft.getInstance().getResourceManager();
        Map<String, Integer> menuRanks = selectedPackMenuRanks();
        List<ResourcePackSkinCatalog.Variant> skinVariants = new ArrayList<>();
        resources.listResources(
                        ResourcePackCatalogDiscovery.PLAYER_TEXTURE_ROOT,
                        location -> ResourcePackCatalogDiscovery.isCandidatePath(location.getPath()))
                .forEach((location, resource) -> {
                    String sourcePackId = resource.sourcePackId();
                    ResourcePackCatalogDiscovery.variant(
                                    location.getNamespace(),
                                    location.getPath(),
                                    sourcePackId,
                                    menuRanks.getOrDefault(sourcePackId, -1))
                            .ifPresent(skinVariants::add);
                });
        List<ResourcePackCapeCatalog.Variant> capeVariants = new ArrayList<>();
        resources.listResources(
                        ResourcePackCatalogDiscovery.CAPE_TEXTURE_ROOT,
                        location -> ResourcePackCatalogDiscovery.isCapeCandidatePath(location.getPath()))
                .forEach((location, resource) -> ResourcePackCatalogDiscovery.capeVariant(
                                location.getNamespace(),
                                location.getPath(),
                                resource.sourcePackId(),
                                menuRanks.getOrDefault(resource.sourcePackId(), -1),
                                resources.getResource(Identifier.fromNamespaceAndPath(
                                        location.getNamespace(),
                                        location.getPath() + ".mcmeta")).isPresent())
                        .ifPresent(capeVariants::add));
        return new ResourcePackCatalogDiscovery.Snapshot(
                ResourcePackSkinCatalog.build(skinVariants),
                ResourcePackCapeCatalog.build(capeVariants));
    }

    private static Map<String, Integer> selectedPackMenuRanks() {
        return ResourcePackCatalogDiscovery.selectedPackMenuRanks(
                Minecraft.getInstance()
                        .getResourcePackRepository()
                        .getSelectedIds()
                        .stream()
                        .toList());
    }

    private static byte[] readBounded(InputStream input) throws IOException {
        byte[] png = input.readNBytes(PngValidator.DEFAULT_MAX_BYTES + 1);
        if (png.length > PngValidator.DEFAULT_MAX_BYTES) {
            throw new IOException("Bundled vanilla player texture is oversized");
        }
        if (png.length == 0) {
            throw new IOException("Catalog skin PNG is empty");
        }
        return SkinCatalogSource.ownedCopy(png);
    }
}

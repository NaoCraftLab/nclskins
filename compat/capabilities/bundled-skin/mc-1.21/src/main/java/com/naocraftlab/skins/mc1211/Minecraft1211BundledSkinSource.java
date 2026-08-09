package com.naocraftlab.skins.mc1211;

import com.naocraftlab.skins.client.MinecraftSkinCatalog;
import com.naocraftlab.skins.client.CatalogGenerationTracker;
import com.naocraftlab.skins.client.ResourcePackCatalogDiscovery;
import com.naocraftlab.skins.client.ResourcePackSkinCatalog;
import com.naocraftlab.skins.client.SkinCatalogSource;
import com.naocraftlab.skins.client.SkinModel;
import com.naocraftlab.skins.core.png.PngValidator;
import com.naocraftlab.skins.core.resourcepack.ResourcePackCollectionIndex;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;


public final class Minecraft1211BundledSkinSource implements SkinCatalogSource {
    private final CatalogGenerationTracker generations = new CatalogGenerationTracker();

    @Override
    public byte[] load(String collectionId, String skinId, SkinModel model) throws IOException {
        if (!MinecraftSkinCatalog.COLLECTION_ID.equals(collectionId)) {
            return loadActiveResource(collectionId, skinId, model);
        }
        String path = MinecraftSkinCatalog.texturePath(collectionId, skinId, model);
        ResourceLocation location = ResourceLocation.withDefaultNamespace(path);
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
        List<CollectionDescriptor> collections = new ArrayList<>(scanResourcePacks());
        collections.addAll(MinecraftSkinCatalog.collections());
        return List.copyOf(collections);
    }

    @Override
    public byte[] loadResource(String identifier) throws IOException {
        final ResourceLocation location;
        try {
            location = ResourceLocation.parse(identifier);
        } catch (RuntimeException invalid) {
            throw new IOException("Invalid Minecraft resource identifier", invalid);
        }
        Resource resource = Minecraft.getInstance().getResourceManager().getResource(location)
                .orElseThrow(() -> new IOException("Minecraft resource skin is unavailable"));
        try (InputStream input = resource.open()) {
            return readBounded(input);
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
        ResourceLocation location = ResourceLocation.fromNamespaceAndPath(
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

    private static List<CollectionDescriptor> scanResourcePacks() {
        ResourceManager resources = Minecraft.getInstance().getResourceManager();
        Map<String, Integer> menuRanks = selectedPackMenuRanks();
        Map<String, PackResources> packsById = new LinkedHashMap<>();
        resources.listPacks().forEach(pack -> packsById.putIfAbsent(pack.packId(), pack));
        ResourceLocation indexLocation = ResourceLocation.fromNamespaceAndPath(
                ResourcePackCatalogDiscovery.INDEX_NAMESPACE,
                ResourcePackCatalogDiscovery.INDEX_PATH);
        Set<ResourceLocation> effectiveLocations = new LinkedHashSet<>();
        for (Resource index : resources.getResourceStack(indexLocation)) {
            PackResources pack = packsById.get(index.sourcePackId());
            if (pack == null) {
                continue;
            }
            try (InputStream input = index.open()) {
                for (String collectionId : ResourcePackCollectionIndex.read(input)) {
                    pack.listResources(
                            PackType.CLIENT_RESOURCES,
                            collectionId,
                            ResourcePackCatalogDiscovery.PLAYER_TEXTURE_ROOT,
                            (location, supplier) -> {
                                if (ResourcePackCatalogDiscovery.isCandidatePath(location.getPath())) {
                                    effectiveLocations.add(location);
                                }
                            });
                }
            } catch (IOException | RuntimeException invalidIndex) {

            }
        }
        List<ResourcePackSkinCatalog.Variant> variants = new ArrayList<>();
        for (ResourceLocation location : effectiveLocations) {
            resources.getResource(location).ifPresent(resource -> {
                String sourcePackId = resource.sourcePackId();
                ResourcePackCatalogDiscovery.variant(
                                location.getNamespace(),
                                location.getPath(),
                                sourcePackId,
                                menuRanks.getOrDefault(sourcePackId, -1))
                        .ifPresent(variants::add);
            });
        }
        return ResourcePackSkinCatalog.build(variants);
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

package com.naocraftlab.skins.compat.v1_20_1.client;

import com.naocraftlab.skins.client.MinecraftSkinCatalog;
import com.naocraftlab.skins.client.CatalogGenerationTracker;
import com.naocraftlab.skins.client.ResourcePackCatalogDiscovery;
import com.naocraftlab.skins.client.ResourcePackSkinCatalog;
import com.naocraftlab.skins.client.SkinCatalogSource;
import com.naocraftlab.skins.client.SkinModel;
import com.naocraftlab.skins.core.png.PngValidator;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;


public final class Minecraft1201BundledSkinSource implements SkinCatalogSource {
    private final CatalogGenerationTracker generations = new CatalogGenerationTracker();

    @Override
    public byte[] load(String collectionId, String skinId, SkinModel model) throws IOException {
        if (!MinecraftSkinCatalog.COLLECTION_ID.equals(collectionId)) {
            return loadActiveResource(collectionId, skinId, model);
        }
        String path = MinecraftSkinCatalog.texturePath(collectionId, skinId, model);
        ResourceLocation location = java.util.Objects.requireNonNull(
                ResourceLocation.tryBuild("minecraft", path));
        Resource resource = Minecraft.getInstance()
                .getResourceManager()
                .getResourceStack(location)
                .stream()
                .filter(candidate -> "vanilla".equals(candidate.sourcePackId()))
                .findFirst()
                .orElseThrow(() -> new IOException("Bundled vanilla player texture is unavailable"));
        try (InputStream input = resource.open()) {
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
        ResourceLocation location = ResourceLocation.tryParse(identifier);
        if (location == null) {
            throw new IOException("Invalid Minecraft resource identifier");
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
        ResourceLocation location = ResourceLocation.tryBuild(
                collectionId, ResourcePackCatalogDiscovery.texturePath(skinId, model));
        if (location == null) {
            throw new IOException("Invalid catalog skin resource identifier");
        }
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
        List<ResourcePackSkinCatalog.Variant> variants = new ArrayList<>();
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
                            .ifPresent(variants::add);
                });
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

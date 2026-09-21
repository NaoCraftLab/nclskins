package com.naocraftlab.skins.loader.fabric;

import com.naocraftlab.skins.client.BuiltInClientPackDescriptor;
import java.util.Objects;
import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.fabricmc.fabric.api.resource.ResourcePackActivationType;
import net.fabricmc.fabric.api.resource.SimpleSynchronousResourceReloadListener;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.ResourceManager;


final class FabricBuiltInPackRegistrar {
    private static final BuiltInClientPackDescriptor PACK =
            BuiltInClientPackDescriptor.MOJANG_COLLECTIONS;

    private FabricBuiltInPackRegistrar() {}

    static void registerMojangCollections(Runnable resourcesReloaded) {
        Objects.requireNonNull(resourcesReloaded, "resourcesReloaded");
        ResourceLocation id = Objects.requireNonNull(
                ResourceLocation.tryParse(PACK.packId()),
                "Mojang Collections pack ID");
        boolean registered = ResourceManagerHelper.registerBuiltinResourcePack(
                id,
                FabricLoader.getInstance().getModContainer(PACK.namespace()).orElseThrow(),
                Component.translatable(PACK.displayTranslationKey()),
                ResourcePackActivationType.DEFAULT_ENABLED);
        if (!registered) {
            throw new IllegalStateException(
                    "Missing built-in resource pack " + PACK.nestedSource());
        }
        ResourceLocation listenerId = Objects.requireNonNull(
                ResourceLocation.tryParse(PACK.reloadListenerId()),
                "Cape catalog reload listener ID");
        ResourceManagerHelper.get(PackType.CLIENT_RESOURCES).registerReloadListener(
                new SimpleSynchronousResourceReloadListener() {
                    @Override
                    public ResourceLocation getFabricId() {
                        return listenerId;
                    }

                    @Override
                    public void onResourceManagerReload(ResourceManager ignored) {
                        resourcesReloaded.run();
                    }
                });
    }
}

package com.naocraftlab.skins.loader.fabric;

import com.naocraftlab.skins.client.BuiltInClientPackDescriptor;
import java.util.Objects;
import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.fabricmc.fabric.api.resource.SimpleSynchronousResourceReloadListener;
import net.fabricmc.fabric.api.resource.v1.ResourceLoader;
import net.fabricmc.fabric.api.resource.v1.pack.PackActivationType;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.ResourceManager;


@SuppressWarnings({"deprecation", "removal"})
final class FabricBuiltInPackRegistrar {
    private static final BuiltInClientPackDescriptor PACK =
            BuiltInClientPackDescriptor.MOJANG_COLLECTIONS;

    private FabricBuiltInPackRegistrar() {}

    static void registerMojangCollections(Runnable resourcesReloaded) {
        Objects.requireNonNull(resourcesReloaded, "resourcesReloaded");
        Identifier id = Identifier.fromNamespaceAndPath(PACK.namespace(), PACK.packPath());
        boolean registered = ResourceLoader.registerBuiltinPack(
                id,
                FabricLoader.getInstance().getModContainer(PACK.namespace()).orElseThrow(),
                Component.translatable(PACK.displayTranslationKey()),
                PackActivationType.DEFAULT_ENABLED);
        if (!registered) {
            throw new IllegalStateException(
                    "Missing built-in resource pack " + PACK.nestedSource());
        }
        Identifier listenerId = Identifier.fromNamespaceAndPath(
                PACK.namespace(), PACK.reloadListenerPath());
        ResourceManagerHelper.get(PackType.CLIENT_RESOURCES).registerReloadListener(
                new SimpleSynchronousResourceReloadListener() {
                    @Override
                    public Identifier getFabricId() {
                        return listenerId;
                    }

                    @Override
                    public void onResourceManagerReload(ResourceManager ignored) {
                        resourcesReloaded.run();
                    }
                });
    }
}

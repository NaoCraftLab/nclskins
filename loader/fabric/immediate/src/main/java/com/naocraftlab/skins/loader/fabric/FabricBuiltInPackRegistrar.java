package com.naocraftlab.skins.loader.fabric;

import java.util.Objects;
import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.fabricmc.fabric.api.resource.ResourcePackActivationType;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;


final class FabricBuiltInPackRegistrar {
    private static final String MOD_ID = "nclskins";
    private static final String PACK_PATH = "mojang_collections";
    private static final String PACK_NAME_KEY = "pack.nclskins.mojang_collections.name";

    private FabricBuiltInPackRegistrar() {}

    static void registerMojangCollections() {
        ResourceLocation id = Objects.requireNonNull(
                ResourceLocation.tryParse(MOD_ID + ':' + PACK_PATH),
                "Mojang Collections pack ID");
        boolean registered = ResourceManagerHelper.registerBuiltinResourcePack(
                id,
                FabricLoader.getInstance().getModContainer(MOD_ID).orElseThrow(),
                Component.translatable(PACK_NAME_KEY),
                ResourcePackActivationType.DEFAULT_ENABLED);
        if (!registered) {
            throw new IllegalStateException(
                    "Missing built-in resource pack resourcepacks/" + PACK_PATH);
        }
    }
}

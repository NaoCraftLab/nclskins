package com.naocraftlab.skins.loader.fabric;

import net.fabricmc.fabric.api.resource.v1.ResourceLoader;
import net.fabricmc.fabric.api.resource.v1.pack.PackActivationType;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;


final class FabricBuiltInPackRegistrar {
    private static final String MOD_ID = "nclskins";
    private static final String PACK_PATH = "mojang_collections";
    private static final String PACK_NAME_KEY = "pack.nclskins.mojang_collections.name";

    private FabricBuiltInPackRegistrar() {}

    static void registerMojangCollections() {
        Identifier id = Identifier.fromNamespaceAndPath(MOD_ID, PACK_PATH);
        boolean registered = ResourceLoader.registerBuiltinPack(
                id,
                FabricLoader.getInstance().getModContainer(MOD_ID).orElseThrow(),
                Component.translatable(PACK_NAME_KEY),
                PackActivationType.DEFAULT_ENABLED);
        if (!registered) {
            throw new IllegalStateException(
                    "Missing built-in resource pack resourcepacks/" + PACK_PATH);
        }
    }
}

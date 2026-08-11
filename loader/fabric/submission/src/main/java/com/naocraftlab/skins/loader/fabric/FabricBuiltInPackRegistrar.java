package com.naocraftlab.skins.loader.fabric;

import net.fabricmc.fabric.api.resource.v1.ResourceLoader;
import net.fabricmc.fabric.api.resource.v1.pack.PackActivationType;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;


final class FabricBuiltInPackRegistrar {
    private static final String MOD_ID = "nclskins";
    private static final String PACK_PATH = "mojang_collections";

    private FabricBuiltInPackRegistrar() {}

    static void registerMojangCollections() {
        boolean registered = ResourceLoader.registerBuiltinPack(
                Identifier.fromNamespaceAndPath(MOD_ID, PACK_PATH),
                FabricLoader.getInstance().getModContainer(MOD_ID).orElseThrow(),
                Component.translatable("pack.nclskins.mojang_collections.name"),
                PackActivationType.DEFAULT_ENABLED);
        if (!registered) {
            throw new IllegalStateException(
                    "Missing built-in resource pack resourcepacks/" + PACK_PATH);
        }
    }
}

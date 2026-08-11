package com.naocraftlab.skins.loader.neoforge;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.PackSource;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.RegisterPictureInPictureRenderersEvent;
import net.neoforged.neoforge.event.AddPackFindersEvent;


@EventBusSubscriber(modid = NclSkinsNeoForgeMod.MOD_ID, value = Dist.CLIENT)
public final class NclSkinsNeoForgeClient {
    private static final String PACK_NAME_KEY = "pack.nclskins.mojang_collections.name";

    private NclSkinsNeoForgeClient() {}

    @SubscribeEvent
    public static void addPackFinders(AddPackFindersEvent event) {
        event.addPackFinders(
                Identifier.fromNamespaceAndPath(
                        NclSkinsNeoForgeMod.MOD_ID, "resourcepacks/mojang_collections"),
                PackType.CLIENT_RESOURCES,
                Component.translatable(PACK_NAME_KEY),
                PackSource.BUILT_IN,
                false,
                Pack.Position.BOTTOM);
    }

    @SubscribeEvent
    public static void registerPictureInPictureRenderers(
            RegisterPictureInPictureRenderersEvent event) {
        NeoForgePipRendererRegistration.register(event);
    }

    @SubscribeEvent
    public static void clientSetup(FMLClientSetupEvent event) {
        NeoForgeConfigScreenRegistrar.register();
        event.enqueueWork(NeoForgeClientBridge::install);
    }
}

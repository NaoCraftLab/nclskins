package com.naocraftlab.skins.loader.neoforge;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.PackSource;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.event.AddPackFindersEvent;


@SuppressWarnings("removal")
@EventBusSubscriber(
        modid = NclSkinsNeoForgeMod.MOD_ID,
        value = Dist.CLIENT,
        bus = EventBusSubscriber.Bus.MOD)
public final class NclSkinsNeoForgeClient {
    private static final String PACK_NAME_KEY = "pack.nclskins.mojang_collections.name";

    private NclSkinsNeoForgeClient() {}

    @SubscribeEvent
    public static void addPackFinders(AddPackFindersEvent event) {
        event.addPackFinders(
                ResourceLocation.fromNamespaceAndPath(
                        NclSkinsNeoForgeMod.MOD_ID, "resourcepacks/mojang_collections"),
                PackType.CLIENT_RESOURCES,
                Component.translatable(PACK_NAME_KEY),
                PackSource.BUILT_IN,
                false,
                Pack.Position.BOTTOM);
    }

    @SubscribeEvent
    public static void clientSetup(FMLClientSetupEvent event) {
        NeoForgeConfigScreenRegistrar.register();
        event.enqueueWork(NeoForgeClientBridge::install);
    }
}

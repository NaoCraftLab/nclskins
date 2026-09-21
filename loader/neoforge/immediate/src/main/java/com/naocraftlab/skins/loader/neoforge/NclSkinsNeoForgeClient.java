package com.naocraftlab.skins.loader.neoforge;

import com.naocraftlab.skins.client.BuiltInClientPackDescriptor;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.PackSource;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.RegisterClientReloadListenersEvent;
import net.neoforged.neoforge.event.AddPackFindersEvent;


@SuppressWarnings("removal")
@EventBusSubscriber(
        modid = NclSkinsNeoForgeMod.MOD_ID,
        value = Dist.CLIENT,
        bus = EventBusSubscriber.Bus.MOD)
public final class NclSkinsNeoForgeClient {
    private static final BuiltInClientPackDescriptor PACK =
            BuiltInClientPackDescriptor.MOJANG_COLLECTIONS;

    private NclSkinsNeoForgeClient() {}

    @SubscribeEvent
    public static void registerKeyMappings(net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent event) {
        com.naocraftlab.skins.compat.keybindings.ScreenKeybindings.matcher(
                new net.neoforged.neoforge.client.settings.KeyMappingLookup()::getAll);
        com.naocraftlab.skins.compat.keybindings.ScreenKeybindings.register(mapping -> {
            mapping.setKeyConflictContext(net.neoforged.neoforge.client.settings.KeyConflictContext.IN_GAME);
            event.register(mapping);
        });
    }

    @SubscribeEvent
    public static void addPackFinders(AddPackFindersEvent event) {
        event.addPackFinders(
                ResourceLocation.fromNamespaceAndPath(
                        PACK.namespace(), PACK.nestedSource()),
                PackType.CLIENT_RESOURCES,
                Component.translatable(PACK.displayTranslationKey()),
                PackSource.BUILT_IN,
                false,
                Pack.Position.BOTTOM);
    }

    @SubscribeEvent
    public static void registerClientReloadListeners(
            RegisterClientReloadListenersEvent event) {
        event.registerReloadListener(
                (ResourceManagerReloadListener) ignored ->
                        NeoForgeClientBridge.resourcesReloaded());
    }

    @SubscribeEvent
    public static void clientSetup(FMLClientSetupEvent event) {
        NeoForgeConfigScreenRegistrar.register();
        event.enqueueWork(NeoForgeClientBridge::install);
    }
}

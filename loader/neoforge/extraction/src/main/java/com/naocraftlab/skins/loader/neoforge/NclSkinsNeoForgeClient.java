package com.naocraftlab.skins.loader.neoforge;

import com.naocraftlab.skins.client.BuiltInClientPackDescriptor;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.PackSource;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.AddClientReloadListenersEvent;
import net.neoforged.neoforge.client.event.RegisterPictureInPictureRenderersEvent;
import net.neoforged.neoforge.event.AddPackFindersEvent;


@EventBusSubscriber(modid = NclSkinsNeoForgeMod.MOD_ID, value = Dist.CLIENT)
public final class NclSkinsNeoForgeClient {
    private static final BuiltInClientPackDescriptor PACK =
            BuiltInClientPackDescriptor.MOJANG_COLLECTIONS;

    private NclSkinsNeoForgeClient() {}

    @SubscribeEvent
    public static void registerKeyMappings(net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent event) {
        event.registerCategory(com.naocraftlab.skins.compat.keybindings.ScreenKeyMapping.category());
        com.naocraftlab.skins.compat.keybindings.ScreenKeybindings.matcher(
                key -> {
                    var lookup = new net.neoforged.neoforge.client.settings.KeyMappingLookup();
                    for (var mapping : net.minecraft.client.Minecraft.getInstance().options.keyMappings) {
                        lookup.put(mapping.getKey(), mapping);
                    }
                    return lookup.getAll(key);
                });
        com.naocraftlab.skins.compat.keybindings.ScreenKeybindings.register(mapping -> {
            mapping.setKeyConflictContext(net.neoforged.neoforge.client.settings.KeyConflictContext.IN_GAME);
            event.register(mapping);
        });
    }

    @SubscribeEvent
    public static void addPackFinders(AddPackFindersEvent event) {
        event.addPackFinders(
                Identifier.fromNamespaceAndPath(
                        PACK.namespace(), PACK.nestedSource()),
                PackType.CLIENT_RESOURCES,
                Component.translatable(PACK.displayTranslationKey()),
                PackSource.BUILT_IN,
                false,
                Pack.Position.BOTTOM);
    }

    @SubscribeEvent
    public static void addClientReloadListeners(AddClientReloadListenersEvent event) {
        event.addListener(
                Identifier.fromNamespaceAndPath(
                        PACK.namespace(), PACK.reloadListenerPath()),
                (ResourceManagerReloadListener) ignored ->
                        NeoForgeClientBridge.resourcesReloaded());
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

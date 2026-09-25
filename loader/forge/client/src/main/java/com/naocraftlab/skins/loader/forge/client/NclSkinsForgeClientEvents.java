package com.naocraftlab.skins.loader.forge.client;

import com.naocraftlab.skins.client.BuiltInClientPackDescriptor;
import com.naocraftlab.skins.client.ClientLifecycleGate;
import com.naocraftlab.skins.compat.loader.MinecraftClientHookAdapter;
import com.naocraftlab.skins.runtime.CapeProjection;
import com.naocraftlab.skins.compat.config.MinecraftConfigurationBridge;
import com.naocraftlab.skins.compat.config.YaclConfigurationScreenFactory;
import com.naocraftlab.skins.loader.forge.NclSkinsForgeMod;
import java.nio.file.Path;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.PackSource;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.ConfigScreenHandler;
import net.minecraftforge.client.event.RegisterClientReloadListenersEvent;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.event.GameShuttingDownEvent;
import net.minecraftforge.event.AddPackFindersEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.loading.FMLPaths;
import net.minecraftforge.resource.PathPackResources;


public final class NclSkinsForgeClientEvents {
    private static final BuiltInClientPackDescriptor PACK =
            BuiltInClientPackDescriptor.MOJANG_COLLECTIONS;
    private static final PackSource OPTIONAL_BUILT_IN_SOURCE =
            PackSource.create(PackSource.BUILT_IN::decorate, false);
    private static final MinecraftClientHookAdapter CLIENT_HOOKS =
            MinecraftClientHookAdapter.instance();
    private static final ClientLifecycleGate<MinecraftClientHookAdapter> LIFECYCLE =
            new ClientLifecycleGate<>(
                    MinecraftClientHookAdapter::resourcesReloaded,
                    MinecraftClientHookAdapter::close);

    private NclSkinsForgeClientEvents() {}

    @Mod.EventBusSubscriber(
            modid = NclSkinsForgeMod.MOD_ID,
            bus = Mod.EventBusSubscriber.Bus.MOD,
            value = Dist.CLIENT)
    public static final class ModBus {
        private ModBus() {}

        @SubscribeEvent
        public static void registerKeyMappings(net.minecraftforge.client.event.RegisterKeyMappingsEvent event) {
            com.naocraftlab.skins.compat.keybindings.ScreenKeybindings.matcher(
                    new net.minecraftforge.client.settings.KeyMappingLookup()::getAll);
            com.naocraftlab.skins.compat.keybindings.ScreenKeybindings.register(mapping -> {
                mapping.setKeyConflictContext(net.minecraftforge.client.settings.KeyConflictContext.IN_GAME);
                event.register(mapping);
            });
        }

        @SubscribeEvent
        public static void clientSetup(FMLClientSetupEvent event) {
            MinecraftConfigurationBridge.configureScreenFactory(
                    ModList.get().isLoaded("yet_another_config_lib_v3")
                            ? YaclConfigurationScreenFactory::create
                            : null);
            LIFECYCLE.install(
                    CLIENT_HOOKS,
                    current -> current.initialize(FMLPaths.CONFIGDIR.get()));
            event.enqueueWork(() -> com.naocraftlab.skins.compat.fancymenu.FancyMenuIntegration.install(
                    ModList.get().isLoaded("fancymenu"), CLIENT_HOOKS::openDestination, CLIENT_HOOKS.diagnostics()));
            ModList.get()
                    .getModContainerById(NclSkinsForgeMod.MOD_ID)
                    .orElseThrow(() -> new IllegalStateException(
                            "NCL Skins mod container is missing"))
                    .registerExtensionPoint(
                            ConfigScreenHandler.ConfigScreenFactory.class,
                            () -> new ConfigScreenHandler.ConfigScreenFactory(
                                    MinecraftConfigurationBridge::createScreen));
        }

        @SubscribeEvent
        public static void registerClientReloadListeners(
                RegisterClientReloadListenersEvent event) {
            event.registerReloadListener(
                    (ResourceManagerReloadListener) ignored -> resourcesReloaded());
        }

        @SubscribeEvent
        public static void addPackFinders(AddPackFindersEvent event) {
            if (event.getPackType() != PackType.CLIENT_RESOURCES) {
                return;
            }
            Path root = ModList.get()
                    .getModFileById(NclSkinsForgeMod.MOD_ID)
                    .getFile()
                    .findResource(PACK.nestedSource().split("/"));
            Pack pack = Pack.readMetaAndCreate(
                    PACK.packId(),
                    Component.translatable(PACK.displayTranslationKey()),
                    false,
                    ignored -> new PathPackResources(PACK.packId(), true, root),
                    PackType.CLIENT_RESOURCES,
                    Pack.Position.BOTTOM,
                    OPTIONAL_BUILT_IN_SOURCE);
            if (pack == null) {
                throw new IllegalStateException(
                        "Invalid built-in resource pack " + PACK.nestedSource());
            }
            event.addRepositorySource(acceptor -> acceptor.accept(pack));
        }
    }

    @Mod.EventBusSubscriber(
            modid = NclSkinsForgeMod.MOD_ID,
            bus = Mod.EventBusSubscriber.Bus.FORGE,
            value = Dist.CLIENT)
    public static final class ForgeBus {
        private ForgeBus() {}

        @SubscribeEvent
        public static void afterScreenInit(ScreenEvent.Init.Post event) {
            LIFECYCLE.dispatch(current -> current.afterScreenInit(
                    Minecraft.getInstance(), event.getScreen(), event.getScreen().width,
                    event.getScreen().height, event::addListener));
        }

        @SubscribeEvent
        public static void onScreenClosing(ScreenEvent.Closing event) {
            LIFECYCLE.dispatch(current -> current.screenRemoved(event.getScreen()));
        }

        @SubscribeEvent
        public static void afterClientTick(TickEvent.ClientTickEvent event) {
            if (event.phase == TickEvent.Phase.END) {
                LIFECYCLE.dispatch(current -> current.tick(Minecraft.getInstance()));
            }
        }

        @SubscribeEvent
        public static void playerLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
            CapeProjection.worldChanged();
        }

        @SubscribeEvent
        public static void gameShuttingDown(GameShuttingDownEvent event) {
            LIFECYCLE.close();
        }
    }

    private static void resourcesReloaded() {
        LIFECYCLE.resourcesReloaded();
    }
}

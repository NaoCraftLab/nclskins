package com.naocraftlab.skins.forge.v1_20_1.client;

import com.naocraftlab.skins.compat.loader.MinecraftClientHookAdapter;
import com.naocraftlab.skins.compat.config.MinecraftConfigurationBridge;
import com.naocraftlab.skins.forge.v1_20_1.NclSkinsForgeMod;
import java.nio.file.Path;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.PackSource;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.ConfigScreenHandler;
import net.minecraftforge.client.event.ScreenEvent;
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
    private static final String PACK_ID = "nclskins:mojang_collections";
    private static final String PACK_NAME_KEY = "pack.nclskins.mojang_collections.name";
    private static final PackSource OPTIONAL_BUILT_IN_SOURCE =
            PackSource.create(PackSource.BUILT_IN::decorate, false);
    private static final MinecraftClientHookAdapter CLIENT_HOOKS =
            MinecraftClientHookAdapter.instance();

    private NclSkinsForgeClientEvents() {}

    @Mod.EventBusSubscriber(
            modid = NclSkinsForgeMod.MOD_ID,
            bus = Mod.EventBusSubscriber.Bus.MOD,
            value = Dist.CLIENT)
    public static final class ModBus {
        private ModBus() {}

        @SubscribeEvent
        public static void clientSetup(FMLClientSetupEvent event) {
            CLIENT_HOOKS.initialize(FMLPaths.CONFIGDIR.get());
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
        public static void addPackFinders(AddPackFindersEvent event) {
            if (event.getPackType() != PackType.CLIENT_RESOURCES) {
                return;
            }
            Path root = ModList.get()
                    .getModFileById(NclSkinsForgeMod.MOD_ID)
                    .getFile()
                    .findResource("resourcepacks", "mojang_collections");
            Pack pack = Pack.readMetaAndCreate(
                    PACK_ID,
                    Component.translatable(PACK_NAME_KEY),
                    false,
                    ignored -> new PathPackResources(PACK_ID, true, root),
                    PackType.CLIENT_RESOURCES,
                    Pack.Position.BOTTOM,
                    OPTIONAL_BUILT_IN_SOURCE);
            if (pack == null) {
                throw new IllegalStateException(
                        "Invalid built-in resource pack resourcepacks/mojang_collections");
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
            CLIENT_HOOKS.afterScreenInit(
                    Minecraft.getInstance(),
                    event.getScreen(),
                    event.getScreen().width,
                    event.getScreen().height,
                    event::addListener);
        }

        @SubscribeEvent
        public static void afterScreenRender(ScreenEvent.Render.Post event) {
            CLIENT_HOOKS.afterScreenFrame(
                    event.getScreen(),
                    new MinecraftClientHookAdapter.Frame(
                            event.getGuiGraphics(), event.getMouseX(), event.getMouseY()));
        }

        @SubscribeEvent
        public static void onScreenClosing(ScreenEvent.Closing event) {
            CLIENT_HOOKS.screenRemoved(event.getScreen());
        }

        @SubscribeEvent
        public static void afterClientTick(TickEvent.ClientTickEvent event) {
            if (event.phase == TickEvent.Phase.END) {
                CLIENT_HOOKS.tick(Minecraft.getInstance());
            }
        }

        @SubscribeEvent
        public static void gameShuttingDown(GameShuttingDownEvent event) {
            CLIENT_HOOKS.close();
        }
    }
}

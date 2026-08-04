package com.naocraftlab.skins.mc1211;

import com.mojang.blaze3d.systems.RenderSystem;
import com.naocraftlab.skins.client.BackEquipmentPreviewRenderer;
import com.naocraftlab.skins.client.CurrentPlayerAppearanceSource;
import com.naocraftlab.skins.client.PreviewRenderer;
import com.naocraftlab.skins.client.TextureRegistry;
import com.naocraftlab.skins.compat.client.MinecraftClientExecutor;
import com.naocraftlab.skins.compat.client.MinecraftFilePicker;
import com.naocraftlab.skins.compat.client.MinecraftGameSessionTokenSource;
import com.naocraftlab.skins.compat.client.MinecraftServerAppearanceRefreshNotifier;
import com.naocraftlab.skins.compat.gui.immediate.ImmediateScreenCapabilities;
import com.naocraftlab.skins.compat.gui.immediate.NclSkinsImmediateScreen;
import com.naocraftlab.skins.compat.gui.immediate.MinecraftSignedTextureVerifier;
import com.naocraftlab.skins.compat.gui.immediate.NativeScrollController;
import com.naocraftlab.skins.runtime.ClientRuntime;
import com.naocraftlab.skins.runtime.ClientProcessHost;
import com.naocraftlab.skins.runtime.TextResolver;
import com.naocraftlab.skins.runtime.UiMessage;
import com.naocraftlab.skins.runtime.ViewSpec;
import java.util.Objects;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import org.lwjgl.opengl.GL11;


public final class Minecraft1211Client implements ImmediateScreenCapabilities {
    private static final Minecraft1211Client INSTANCE = new Minecraft1211Client();

    private final Minecraft1211AppearanceCapability appearance =
            new Minecraft1211AppearanceCapability();
    private final CurrentPlayerAppearanceSource currentAppearance =
            new Minecraft1211CurrentPlayerAppearanceSource(appearance::installedAppearance);
    private final ClientRuntime runtime = ClientRuntime.createDefaultWithDeterministicAppearance(
            new MinecraftGameSessionTokenSource(),
            new Minecraft1211BundledSkinSource(),
            currentAppearance,
            new MinecraftClientExecutor(),
            new MinecraftFilePicker(),
            TextResolver.withCatalogTranslations(
                    message -> resolve(message).getString(),
                    (key, fallback) -> Language.getInstance().getOrDefault(key, fallback)),
            new MinecraftSignedTextureVerifier(),
            appearance,
            new Minecraft1211OuterLayerVisibilityController(),
            new MinecraftServerAppearanceRefreshNotifier());

    private final ClientProcessHost<Object> process =
            new ClientProcessHost<>(runtime, appearance::close);

    private Minecraft1211Client() {}

    public static Minecraft1211Client instance() {
        return INSTANCE;
    }

    public void initialize() {
        runtime.verifyStorageAccess();
    }

    public void warmSession() {
        if (process.closed()) {
            return;
        }
        process.warmSession();
    }

    public void tick(Minecraft minecraft) {
        Objects.requireNonNull(minecraft, "minecraft");


        if (process.closed()) {
            return;
        }
        appearance.maintain();
        Object connection = minecraft.getConnection();
        boolean playerReady = connection != null
                && minecraft.player != null
                && minecraft.getConnection().getPlayerInfo(minecraft.player.getUUID()) != null;
        process.tick(
                connection,
                playerReady);
    }

    public Screen createScreen(Screen parent) {
        return new NclSkins1211Screen(parent, this);
    }

    CurrentPlayerAppearanceSource currentAppearanceSource() {
        return currentAppearance;
    }

    public void openOrToggle(Minecraft minecraft, Screen current) {
        Objects.requireNonNull(minecraft, "minecraft");
        if (current instanceof NclSkinsImmediateScreen immediate) {
            immediate.onClose();
        } else {
            minecraft.setScreen(createScreen(current));
        }
    }

    public void close() {
        process.close();
    }

    @Override
    public ClientRuntime runtime() {
        return runtime;
    }

    @Override
    public TextureRegistry createTextureRegistry() {
        return new MinecraftTextureRegistry();
    }

    @Override
    public PreviewRenderer<GuiGraphics> createSimplePreviewRenderer() {
        return new Minecraft1211SimplePreviewRenderer(Minecraft.getInstance());
    }

    @Override
    public PreviewRenderer<GuiGraphics> createEditorPreviewRenderer() {
        return new VanillaAppearancePreviewRenderer(Minecraft.getInstance());
    }

    @Override
    public BackEquipmentPreviewRenderer<GuiGraphics> createBackEquipmentPreviewRenderer() {
        return new Minecraft1211SimplePreviewRenderer(Minecraft.getInstance());
    }

    @Override
    public void finishPreviewPass(GuiGraphics graphics) {
        graphics.flush();
        RenderSystem.clear(GL11.GL_DEPTH_BUFFER_BIT, Minecraft.ON_OSX);
    }

    @Override
    public NativeScrollController createScrollController() {
        return new Minecraft1211ScrollController(Minecraft.getInstance());
    }

    @Override
    public void renderPanel(GuiGraphics graphics, ViewSpec.Panel panel) {
        com.naocraftlab.skins.runtime.Bounds bounds = panel.bounds();
        if (panel.style() == ViewSpec.Panel.Style.VANILLA_LIST) {
            NclSkinsVanillaScreenStyle.renderListPanel(
                    graphics, bounds.x(), bounds.y(), bounds.width(), bounds.height());
        } else {
            NclSkinsVanillaScreenStyle.renderFramePanel(graphics, bounds, panel.style());
        }
    }

    @Override
    public void renderScrollbar(GuiGraphics graphics, ViewSpec.Scrollbar scrollbar) {
        NclSkinsVanillaScreenStyle.renderHorizontalScrollbar(
                graphics,
                scrollbar.track().x(),
                scrollbar.track().y(),
                scrollbar.track().width(),
                scrollbar.thumb().x(),
                scrollbar.thumb().width());
    }

    private static Component resolve(UiMessage message) {
        if (message.literal()) {
            return Component.literal(message.key());
        }
        Object[] arguments = message.arguments().stream()
                .map(argument -> argument instanceof UiMessage nested ? resolve(nested) : argument)
                .toArray();
        return Component.translatable(message.key(), arguments);
    }
}

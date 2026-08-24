package com.naocraftlab.skins.mc1211;

import com.mojang.blaze3d.systems.RenderSystem;
import com.naocraftlab.skins.client.BackEquipmentPreviewRenderer;
import com.naocraftlab.skins.client.CurrentPlayerAppearanceSource;
import com.naocraftlab.skins.client.FilePicker;
import com.naocraftlab.skins.client.PreviewRenderer;
import com.naocraftlab.skins.client.TextureRegistry;
import com.naocraftlab.skins.compat.gui.immediate.ImmediateScreenCapabilities;
import com.naocraftlab.skins.compat.gui.immediate.NclSkinsImmediateScreen;
import com.naocraftlab.skins.compat.gui.immediate.NativeScrollController;
import com.naocraftlab.skins.generated.TargetClientBindings;
import com.naocraftlab.skins.diagnostics.Slf4jDiagnosticSink;
import com.naocraftlab.skins.runtime.ClientRuntime;
import com.naocraftlab.skins.runtime.ClientApplicationHost;
import com.naocraftlab.skins.runtime.ClientCapabilityProvider;
import com.naocraftlab.skins.runtime.TextResolver;
import com.naocraftlab.skins.runtime.UiMessage;
import com.naocraftlab.skins.runtime.ViewSpec;
import java.nio.file.Path;
import java.util.Objects;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import org.lwjgl.opengl.GL11;
import org.slf4j.LoggerFactory;


public final class Minecraft1211Client implements ImmediateScreenCapabilities {
    private static final Minecraft1211Client INSTANCE = new Minecraft1211Client();

    private final ClientCapabilityProvider.Provision provision =
            TargetClientBindings.provision();
    private final CurrentPlayerAppearanceSource currentAppearance =
            provision.capabilities().currentAppearance();
    private ClientApplicationHost<Object> application;
    private boolean terminallyClosed;

    private Minecraft1211Client() {}

    public static Minecraft1211Client instance() {
        return INSTANCE;
    }

    public FilePicker nativeFileDialog() {
        return provision.capabilities().nativeFileDialog();
    }

    public synchronized void initialize(Path dataRoot) {
        if (terminallyClosed) {
            throw new IllegalStateException("NCL Skins client is terminally closed");
        }
        if (application == null) {
            application = new ClientApplicationHost<>(
                    provision.capabilities(),
                    TextResolver.withCatalogTranslations(
                            message -> resolve(message).getString(),
                            (key, fallback) -> Language.getInstance().getOrDefault(key, fallback)),
                    Objects.requireNonNull(dataRoot, "dataRoot"),
                    new Slf4jDiagnosticSink(LoggerFactory.getLogger("nclskins")),
                    provision::closeNative);
        }
        application.verifyStorageAccess();
    }

    public void warmSession() {
        application().warmSession();
    }

    public void tick(Minecraft minecraft) {
        Objects.requireNonNull(minecraft, "minecraft");


        ClientApplicationHost<Object> current = application();
        if (current.closed()) {
            return;
        }
        provision.maintain();
        Object connection = minecraft.getConnection();
        boolean playerReady = connection != null
                && minecraft.player != null
                && minecraft.getConnection().getPlayerInfo(minecraft.player.getUUID()) != null;
        current.tick(
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

    public synchronized void close() {
        if (terminallyClosed) {
            return;
        }
        terminallyClosed = true;
        if (application != null) {
            application.close();
            application = null;
        } else {
            provision.closeNative();
        }
    }

    @Override
    public ClientRuntime runtime() {
        return application().runtime();
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
        return new VanillaAppearancePreviewRenderer(
                Minecraft.getInstance(), runtime().diagnostics());
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

    private synchronized ClientApplicationHost<Object> application() {
        if (application == null) {
            throw new IllegalStateException("NCL Skins client is not initialized");
        }
        return application;
    }
}

package com.naocraftlab.skins.compat.client.resourcelocation.playerinfo;

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
import com.naocraftlab.skins.runtime.ViewChromeMetrics;
import java.nio.file.Path;
import java.util.Objects;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import org.lwjgl.opengl.GL11;
import org.slf4j.LoggerFactory;


public final class ImmediateClientRuntime implements ImmediateScreenCapabilities {
    private static final ImmediateClientRuntime INSTANCE = new ImmediateClientRuntime();

    private final ClientCapabilityProvider.Provision provision =
            TargetClientBindings.provision();
    private final CurrentPlayerAppearanceSource currentAppearance =
            provision.capabilities().currentAppearance();
    private ClientApplicationHost<Object> application;
    private boolean terminallyClosed;

    private ImmediateClientRuntime() {}

    public static ImmediateClientRuntime instance() {
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
        return new PlayerInfoScreen(parent, this);
    }

    CurrentPlayerAppearanceSource.PlayerAppearance currentPlayerAppearance() {
        return currentAppearance.currentPlayerAppearance();
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
    public ViewChromeMetrics viewChromeMetrics() {
        return new ViewChromeMetrics(38);
    }

    @Override
    public TextureRegistry createTextureRegistry() {
        return new ResourceLocationTextureRegistry();
    }

    @Override
    public PreviewRenderer<GuiGraphics> createSimplePreviewRenderer() {
        return new SimplePreviewRenderer();
    }

    @Override
    public PreviewRenderer<GuiGraphics> createEditorPreviewRenderer() {
        return new RemotePlayerPreviewRenderer(runtime().diagnostics());
    }

    @Override
    public BackEquipmentPreviewRenderer<GuiGraphics> createBackEquipmentPreviewRenderer() {
        return new SimplePreviewRenderer();
    }

    @Override
    public void finishPreviewPass(GuiGraphics graphics) {
        graphics.flush();
        RenderSystem.clear(GL11.GL_DEPTH_BUFFER_BIT, Minecraft.ON_OSX);
    }

    @Override
    public NativeScrollController createScrollController() {
        return new ImmediateScrollController(Minecraft.getInstance());
    }

    @Override
    public void renderPanel(
            GuiGraphics graphics, ViewSpec.Panel panel, int textureU, int textureV) {
        com.naocraftlab.skins.runtime.Bounds bounds = panel.bounds();
        if (bounds.width() <= 0 || bounds.height() <= 0) {
            return;
        }
        if (panel.style() == ViewSpec.Panel.Style.VANILLA_LIST) {
            graphics.fill(
                    bounds.x(),
                    bounds.y(),
                    bounds.right(),
                    bounds.bottom(),
                    0x80000000);
            return;
        }
        graphics.setColor(0.125F, 0.125F, 0.125F, 1.0F);
        graphics.blit(
                Screen.BACKGROUND_LOCATION,
                bounds.x(),
                bounds.y(),
                0.0F,
                0.0F,
                bounds.width(),
                bounds.height(),
                32,
                32);
        graphics.setColor(1.0F, 1.0F, 1.0F, 1.0F);
    }

    @Override
    public void renderScrollbar(GuiGraphics graphics, ViewSpec.Scrollbar scrollbar) {
        com.naocraftlab.skins.runtime.Bounds track = scrollbar.track();
        com.naocraftlab.skins.runtime.Bounds thumb = scrollbar.thumb();
        graphics.fill(track.x(), track.y(), track.right(), track.bottom(), 0xFF000000);
        graphics.fill(thumb.x(), thumb.y(), thumb.right(), thumb.bottom(), 0xFF808080);
        graphics.fill(
                thumb.x(),
                thumb.y(),
                Math.max(thumb.x(), thumb.right() - 1),
                Math.max(thumb.y(), thumb.bottom() - 1),
                0xFFC0C0C0);
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

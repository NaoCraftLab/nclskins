package com.naocraftlab.skins.compat.client.resourcelocation.playerinfo;

import java.util.Optional;
import com.naocraftlab.skins.runtime.Bounds;
import com.mojang.blaze3d.systems.RenderSystem;
import com.naocraftlab.skins.client.BackEquipmentPreviewRenderer;
import com.naocraftlab.skins.client.FilePicker;
import com.naocraftlab.skins.client.PreviewRenderer;
import com.naocraftlab.skins.client.TextureRegistry;
import com.naocraftlab.skins.compat.gui.immediate.ImmediateScreenCapabilities;
import com.naocraftlab.skins.compat.gui.immediate.NclSkinsImmediateScreen;
import com.naocraftlab.skins.compat.gui.immediate.NativeScrollController;
import com.naocraftlab.skins.compat.config.MinecraftConfigurationBridge;
import com.naocraftlab.skins.generated.TargetClientBindings;
import com.naocraftlab.skins.diagnostics.Slf4jDiagnosticSink;
import com.naocraftlab.skins.runtime.ClientRuntime;
import com.naocraftlab.skins.runtime.ClientApplicationHost;
import com.naocraftlab.skins.runtime.ClientCapabilityProvider;
import com.naocraftlab.skins.runtime.TextResolver;
import com.naocraftlab.skins.runtime.UiMessage;
import com.naocraftlab.skins.runtime.ViewSpec;
import com.naocraftlab.skins.runtime.ViewChromeMetrics;
import com.naocraftlab.skins.runtime.NativeGuiIcon;
import java.nio.file.Path;
import java.util.Objects;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
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
            application = com.naocraftlab.skins.runtime.composition.ClientCompositionRoot.createApplication(
                    provision.capabilities(),
                    TextResolver.withCatalogTranslations(
                            TextResolver.withLayout(message -> resolve(message).getString(),
                                    (message, width) -> Minecraft.getInstance().font.wordWrapHeight(resolve(message), Math.max(1, width))),
                            (key, fallback) -> Language.getInstance().getOrDefault(key, fallback)),
                    Objects.requireNonNull(dataRoot, "dataRoot"),
                    MinecraftConfigurationBridge.service()::client,
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
        Object connection = minecraft.getConnection();
        boolean playerReady = connection != null
                && minecraft.player != null
                && minecraft.getConnection().getPlayerInfo(minecraft.player.getUUID()) != null;
        provision.maintain(playerReady);
        current.tick(
                connection,
                playerReady);
    }

    public void resourcesReloaded() {
        provision.markNativeResourcesDirty();
        runtime().resourcesReloaded();
    }

    public Screen createScreen(Screen parent, com.naocraftlab.skins.client.ScreenDestination destination) {
        return new PlayerInfoScreen(parent, this, destination);
    }

    public Screen createScreen(Screen parent) {
        return new PlayerInfoScreen(parent, this);
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
    public void renderProviderArrow(GuiGraphics graphics, String action, int x, int y, boolean highlighted) {
        int u = action.equals("select") ? 0 : action.equals("remove") ? 32 : action.equals("up") ? 96 : 64;
        graphics.blit(ResourceLocation.tryParse("minecraft:textures/gui/resource_packs.png"), x, y, (float) u, highlighted ? 32.0F : 0.0F, 32, 32, 256, 256);
    }

    @Override
    public void renderNativeIcon(
            GuiGraphics graphics,
            NativeGuiIcon icon,
            int x,
            int y,
            int width,
            int height,
            boolean active) {
        float tint = active ? 1.0F : 0.5F;
        graphics.setColor(tint, tint, tint, 1.0F);
        try {
            ResourceLocation resource = ResourceLocation.tryParse(
                    "realms:textures/gui/realms/"
                            + (icon == NativeGuiIcon.ACCEPT ? "accept_icon" : "reject_icon")
                            + ".png");
            graphics.blit(resource, x, y, 0.0F, 0.0F, width, height, 37, 18);
        } finally {
            graphics.setColor(1.0F, 1.0F, 1.0F, 1.0F);
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
            GuiGraphics graphics,
            ViewSpec.Panel panel,
            int textureU,
            int textureV,
            Optional<Bounds> selectedVerticalTabBounds,
            Optional<Bounds> verticalTabGroupBounds) {
        NativeSurfaceAdapter.renderPanel(graphics, panel, textureU, textureV, selectedVerticalTabBounds, verticalTabGroupBounds);
    }

    @Override
    public void renderScrollbar(GuiGraphics graphics, ViewSpec.Scrollbar scrollbar) {
        NativeSurfaceAdapter.renderScrollbar(graphics, scrollbar);
    }

    @Override
    public void renderVerticalTab(
            GuiGraphics graphics,
            int x,
            int y,
            int width,
            int height,
            boolean selected,
            boolean highlighted,
        boolean active) {
        NativeSurfaceAdapter.renderVerticalTab(graphics, x, y, width, height, selected, highlighted, active);
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

package com.naocraftlab.skins.compat.client.resourcelocation.playerinfo;

import java.util.Optional;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import com.naocraftlab.skins.runtime.VerticalTabStyle;
import com.naocraftlab.skins.runtime.Bounds;
import com.mojang.math.Axis;
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
    private static final net.minecraft.resources.ResourceLocation TAB_BUTTON =
            new net.minecraft.resources.ResourceLocation("textures/gui/tab_button.png");
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
            application = new ClientApplicationHost<>(
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
        Bounds bounds = panel.bounds();
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
        if (panel.style() == ViewSpec.Panel.Style.VANILLA_TAB_CONTENT) {
            VerticalTabStyle.backgroundSegments(bounds)
                    .forEach(segment -> renderLightDirtBackground(graphics, segment));
            RenderSystem.enableBlend();
            selectedVerticalTabBounds
                    .map(tab -> VerticalTabStyle.separatorSegments(bounds, tab))
                    .orElseGet(() -> java.util.List.of(new Bounds(
                            bounds.x(), bounds.y(), Math.min(2, bounds.width()), bounds.height())))
                    .forEach(segment -> renderVerticalSeparator(graphics, segment));
            RenderSystem.disableBlend();
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

    public void renderVerticalTab(
            GuiGraphics graphics,
            int x,
            int y,
            int width,
            int height,
            boolean selected,
            boolean highlighted,
        boolean active) {
        int textureY = selected ? (highlighted ? 24 : 0) : (highlighted ? 72 : 48);
        Bounds underlay = VerticalTabStyle.underlay(
                new Bounds(x, y, width, height), selected);
        renderLightDirtBackground(graphics, underlay);
        Bounds edge = VerticalTabStyle.rightEdge(new Bounds(x, y, width, height));
        graphics.enableScissor(x, y, edge.x(), edge.bottom());
        graphics.pose().pushPose();
        try {
            graphics.pose().translate(x, y + height, 0.0F);
            graphics.pose().mulPose(Axis.ZP.rotationDegrees(-90.0F));
            graphics.blitNineSliced(
                    TAB_BUTTON,
                    0,
                    0,
                    height,
                    width,
                    2,
                    2,
                    2,
                    0,
                    130,
                    24,
                    0,
                    textureY);
        } finally {
            graphics.pose().popPose();
            graphics.disableScissor();
        }
    }

    private static void renderLightDirtBackground(GuiGraphics graphics, Bounds bounds) {
        graphics.blit(
                CreateWorldScreen.LIGHT_DIRT_BACKGROUND,
                bounds.x(),
                bounds.y(),
                (float) bounds.x(),
                (float) bounds.y(),
                bounds.width(),
                bounds.height(),
                32,
                32);
    }

    private static void renderVerticalSeparator(GuiGraphics graphics, Bounds segment) {
        graphics.pose().pushPose();
        graphics.pose().translate(segment.x(), segment.bottom(), 0.0F);
        graphics.pose().mulPose(Axis.ZP.rotationDegrees(-90.0F));
        graphics.blit(
                CreateWorldScreen.HEADER_SEPERATOR,
                0,
                0,
                0.0F,
                0.0F,
                segment.height(),
                segment.width(),
                32,
                2);
        graphics.pose().popPose();
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

package com.naocraftlab.skins.compat.client.resourcelocation.skinlookup;

import java.util.Optional;
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
import com.naocraftlab.skins.runtime.Bounds;
import com.naocraftlab.skins.runtime.ViewSpec;
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
        return new SkinLookupScreen(parent, this, destination);
    }

    public Screen createScreen(Screen parent) {
        return new SkinLookupScreen(parent, this);
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
        String sprite = "transferable_list/" + (action.equals("select") ? "select" : action.equals("remove") ? "unselect" : "move_" + action) + (highlighted ? "_highlighted" : "");
        graphics.blitSprite(ResourceLocation.tryParse("minecraft:" + sprite), x, y, 32, 32);
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
            graphics.blitSprite(
                    ResourceLocation.withDefaultNamespace(
                            "pending_invite/" + (icon == NativeGuiIcon.ACCEPT ? "accept" : "reject")),
                    x,
                    y,
                    width,
                    height);
        } finally {
            graphics.setColor(1.0F, 1.0F, 1.0F, 1.0F);
        }
    }

    @Override
    public ClientRuntime runtime() {
        return application().runtime();
    }

    @Override
    public TextureRegistry createTextureRegistry() {
        return new ResourceLocationTextureRegistry();
    }

    @Override
    public PreviewRenderer<GuiGraphics> createSimplePreviewRenderer() {
        return new SimplePreviewRenderer(Minecraft.getInstance());
    }

    @Override
    public PreviewRenderer<GuiGraphics> createEditorPreviewRenderer() {
        return new VanillaAppearancePreviewRenderer(
                Minecraft.getInstance(), runtime().diagnostics());
    }

    @Override
    public BackEquipmentPreviewRenderer<GuiGraphics> createBackEquipmentPreviewRenderer() {
        return new SimplePreviewRenderer(Minecraft.getInstance());
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
            Optional<com.naocraftlab.skins.runtime.Bounds> selectedVerticalTabBounds,
            Optional<com.naocraftlab.skins.runtime.Bounds> verticalTabGroupBounds) {
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

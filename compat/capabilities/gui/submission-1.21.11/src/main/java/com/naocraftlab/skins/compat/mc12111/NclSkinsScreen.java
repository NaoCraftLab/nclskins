package com.naocraftlab.skins.compat.mc12111;

import com.mojang.blaze3d.platform.cursor.CursorTypes;
import com.naocraftlab.skins.client.BackEquipmentPreviewRenderer;
import com.naocraftlab.skins.client.CurrentPlayerAppearanceSource.PlayerAppearance;
import com.naocraftlab.skins.client.FilePicker;
import com.naocraftlab.skins.client.PreviewRenderer;
import com.naocraftlab.skins.client.SkinModel;
import com.naocraftlab.skins.client.TextureRegistry.TextureHandle;
import com.naocraftlab.skins.client.TextureRegistry.TextureKind;
import com.naocraftlab.skins.compat.mc12111.mixin.ScreenRenderablesAccessor;
import com.naocraftlab.skins.compat.mc262.Minecraft262TextureRegistry;
import com.naocraftlab.skins.core.model.SkinReference;
import com.naocraftlab.skins.core.model.SkinVariant;
import com.naocraftlab.skins.runtime.Bounds;
import com.naocraftlab.skins.runtime.CatalogCardStyle;
import com.naocraftlab.skins.runtime.ClientRuntime;
import com.naocraftlab.skins.runtime.ClientSnapshot;
import com.naocraftlab.skins.runtime.CollectionHeaderStyle;
import com.naocraftlab.skins.runtime.InfoButtonStyle;
import com.naocraftlab.skins.runtime.MarqueeRouting;
import com.naocraftlab.skins.runtime.MarqueeText;
import com.naocraftlab.skins.runtime.PointerRouting;
import com.naocraftlab.skins.runtime.PreviewAssetCache;
import com.naocraftlab.skins.runtime.UiMessage;
import com.naocraftlab.skins.runtime.ViewHostPolicy;
import com.naocraftlab.skins.runtime.ViewSpec;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.tabs.GridLayoutTab;
import net.minecraft.client.gui.components.tabs.Tab;
import net.minecraft.client.gui.components.tabs.TabManager;
import net.minecraft.client.gui.components.tabs.TabNavigationBar;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.InputWithModifiers;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;


public final class NclSkinsScreen extends Screen {
    private static final Identifier MENU_LIST_BACKGROUND =
            Identifier.withDefaultNamespace("textures/gui/menu_list_background.png");
    private static final Identifier INWORLD_MENU_LIST_BACKGROUND =
            Identifier.withDefaultNamespace("textures/gui/inworld_menu_list_background.png");
    private static final Identifier SCROLLER = Identifier.withDefaultNamespace("widget/scroller");
    private static final Identifier SCROLLER_BACKGROUND =
            Identifier.withDefaultNamespace("widget/scroller_background");
    private static final int ACTION_ICON_RENDER_SIZE = 16;
    private static final int ACTION_ICON_TEXTURE_SIZE = 20;
    private static final int DECORATION_ICON_SIZE = 32;
    private static final int COLLECTION_HEADER_TRAILING_INFO_WIDTH = 14;
    private static final int OFFSCREEN_MOUSE_COORDINATE = -1_000_000;
    private static final int LEFT_MOUSE_BUTTON = 0;
    private static final int TEXT_COLOR = 0xFFE8EDF6;
    private static final int MUTED_COLOR = 0xFF9BA8BC;
    private static final int ERROR_COLOR = 0xFFFF9A9A;
    private static final int ACTIVE_TEXT_COLOR = 0xFF8EE6A5;

    private final Screen parent;
    private final ClientRuntime runtime;
    private final Minecraft12111ScrollController scrollController =
            new Minecraft12111ScrollController();
    private final Map<String, AbstractWidget> widgets = new HashMap<>();
    private final Map<String, NativeTabGroup> nativeTabGroups = new HashMap<>();
    private final Map<String, SkinKey> previewKeys = new HashMap<>();
    private final Map<String, Minecraft12111SimplePreviewRenderer> bakedRenderers =
            new HashMap<>();
    private final Minecraft12111PreviewRenderer editorRenderer =
            new Minecraft12111PreviewRenderer();

    private Minecraft262TextureRegistry textureRegistry;
    private PreviewAssetCache<SkinKey> skinTextures;
    private PreviewAssetCache<String> capeTextures;
    private ClientRuntime.Subscription subscription;
    private ViewSpec view;
    private List<NativeWidgetSignature> nativeWidgetSignatures = List.of();
    private List<NativeTabSignature> nativeTabSignatures = List.of();
    private boolean activeScreen;
    private boolean rebuilding;
    private boolean syncingTabSelection;
    private boolean pointerCaptured;
    private String pendingTabSelection;
    private long consumedFocusToken;
    private boolean updatingText;
    private int mouseX;
    private int mouseY;

    public NclSkinsScreen(Screen parent) {
        super(Component.translatable("nclskins.title"));
        this.parent = parent;
        runtime = NclSkins12111ClientRuntime.runtime();
    }

    public static void initializeClientRuntime(java.nio.file.Path dataRoot) {
        NclSkins12111ClientRuntime.initialize(dataRoot);
    }

    public static FilePicker nativeFileDialog() {
        return NclSkins12111ClientRuntime.nativeFileDialog();
    }

    public static void warmSessionSnapshot() {
        NclSkins12111ClientRuntime.warmup();
    }

    public static void onClientTick(Minecraft client) {
        NclSkins12111ClientRuntime.tick(client);
    }

    public static void closeClientRuntime() {
        NclSkins12111ClientRuntime.close();
    }

    @Override
    protected void init() {
        activeScreen = true;
        if (runtime.closed()) {
            return;
        }
        rebuilding = true;
        if (textureRegistry == null) {
            textureRegistry = new Minecraft262TextureRegistry();
            skinTextures = new PreviewAssetCache<>(textureRegistry, TextureKind.PLAYER_SKIN);
            capeTextures = new PreviewAssetCache<>(textureRegistry, TextureKind.IMAGE);
        }
        runtime.reopen();
        if (subscription == null) {
            subscription = runtime.subscribe(this::snapshotChanged);
        }
        nativeWidgetSignatures = List.of();
        nativeTabSignatures = List.of();
        rebuilding = false;
        refresh();
    }

    private void snapshotChanged(ClientSnapshot snapshot) {
        if (!activeScreen || minecraft == null || minecraft.screen != this) {
            return;
        }
        minecraft.execute(() -> {
            if (!activeScreen || minecraft.screen != this) {
                return;
            }
            if (snapshot.lifecycle() == ClientSnapshot.Lifecycle.CLOSED || runtime.closed()) {
                minecraft.setScreen(parent);
            } else {
                refresh();
            }
        });
    }

    private void refresh() {
        if (minecraft == null || minecraft.screen != this || runtime.closed()) {
            return;
        }
        view = runtime.view(width, height, mouseX, mouseY);
        List<NativeWidgetSignature> nextWidgetSignatures = nativeWidgetSignatures(view);
        List<NativeTabSignature> nextTabSignatures = nativeTabSignatures(view);
        if (!rebuilding && (!nextWidgetSignatures.equals(nativeWidgetSignatures)
                || !nextTabSignatures.equals(nativeTabSignatures))) {
            String focused = focusedWidgetId();
            rebuildNativeWidgets(view);
            nativeWidgetSignatures = nextWidgetSignatures;
            nativeTabSignatures = nextTabSignatures;
            if (!applyFocusRequest(view) && focused != null) {
                focusWidget(focused);
            }
        } else {
            updateNativeWidgets(view);
            syncNativeTabs(view);
            applyFocusRequest(view);
        }
        nativeWidgetSignatures = nextWidgetSignatures;
        nativeTabSignatures = nextTabSignatures;
        scrollController.synchronize(view.scrollSurfaces().stream().findFirst());
        synchronizePreviewAssets(view);
    }

    private void rebuildNativeWidgets(ViewSpec next) {
        clearWidgets();
        clearFocus();
        widgets.clear();
        nativeTabGroups.clear();
        addNativeTabGroups(next);
        for (ViewSpec.Widget spec : next.widgets()) {
            AbstractWidget widget = createWidget(spec);
            widgets.put(spec.id(), widget);
            addRenderableWidget(widget);
        }
        updateNativeWidgets(next);
        syncNativeTabs(next);
    }

    private void addNativeTabGroups(ViewSpec next) {
        for (ViewSpec.TabGroup group : next.tabGroups()) {
            Map<Tab, String> ids = new IdentityHashMap<>();
            List<Tab> tabs = new ArrayList<>();
            TabManager manager = new TabManager(
                    ignored -> {},
                    ignored -> {},
                    selected -> {
                        if (!syncingTabSelection) {
                            pendingTabSelection = ids.get(selected);
                        }
                    },
                    ignored -> {});
            int selected = -1;
            for (int index = 0; index < group.tabs().size(); index++) {
                ViewSpec.Tab spec = group.tabs().get(index);
                Tab tab = new GridLayoutTab(Minecraft12111Components.resolve(spec.label()));
                ids.put(tab, spec.id());
                tabs.add(tab);
                if (spec.selected()) {
                    selected = index;
                }
            }
            TabNavigationBar bar = TabNavigationBar.builder(manager, group.bounds().width())
                    .addTabs(tabs.toArray(Tab[]::new))
                    .build();
            bar.arrangeElements();
            NativeTabGroup nativeGroup = new NativeTabGroup(manager, bar, List.copyOf(tabs));
            nativeTabGroups.put(group.id(), nativeGroup);
            addRenderableWidget(bar);
            selectNativeTab(nativeGroup, selected);
        }
    }

    private AbstractWidget createWidget(ViewSpec.Widget spec) {
        if (spec.kind() == ViewSpec.WidgetKind.TEXT_FIELD) {
            Bounds bounds = spec.bounds();
            EditBox edit = new EditBox(
                    font,
                    bounds.x(),
                    bounds.y(),
                    bounds.width(),
                    bounds.height(),
                    Minecraft12111Components.resolve(spec.label()));
            edit.setMaxLength(spec.maxLength());
            edit.setHint(spec.hint().map(Minecraft12111Components::resolve).orElse(Component.empty()));
            edit.setValue(spec.value().orElse(""));
            edit.setEditable(spec.enabled());
            edit.active = spec.enabled();
            edit.setResponder(value -> {
                if (!updatingText) {
                    runtime.dispatchText(spec.id(), value);
                }
            });
            return edit;
        }
        ActionButton button = new ActionButton(
                spec.bounds(),
                Minecraft12111Components.resolve(spec.label()),
                spec.kind(),
                spec.icon(),
                spec.collectionHeaderHasTrailingInfo(),
                CatalogCardStyle.selectionSelected(spec),
                !spec.visible(),
                input -> runtime.dispatchWidget(spec.id(), input.hasShiftDown()));
        spec.hint()
                .or(() -> spec.kind() == ViewSpec.WidgetKind.ICON_BUTTON
                                || spec.kind() == ViewSpec.WidgetKind.INFO_BUTTON
                        ? Optional.of(spec.label())
                        : Optional.empty())
                .map(Minecraft12111Components::resolve)
                .map(Tooltip::create)
                .ifPresent(button::setTooltip);
        return button;
    }

    private void updateNativeWidgets(ViewSpec next) {
        for (ViewSpec.Widget spec : next.widgets()) {
            AbstractWidget widget = widgets.get(spec.id());
            if (widget == null) {
                continue;
            }
            Bounds bounds = spec.bounds();
            widget.active = spec.enabled();
            widget.visible = spec.visible() || ownsDecoration(next, spec.id());
            widget.setMessage(Minecraft12111Components.resolve(spec.label()));
            widget.setWidth(bounds.width());
            widget.setHeight(bounds.height());
            widget.setX(bounds.x());
            widget.setY(bounds.y());
            if (widget instanceof ActionButton button) {
                button.update(spec);
            }
            if (widget instanceof EditBox edit) {
                edit.setEditable(spec.enabled());
                String desired = spec.value().orElse("");
                if (!edit.getValue().equals(desired) && !edit.isFocused()) {
                    updatingText = true;
                    try {
                        edit.setValue(desired);
                    } finally {
                        updatingText = false;
                    }
                }
            }
        }
    }

    private void syncNativeTabs(ViewSpec next) {
        for (ViewSpec.TabGroup group : next.tabGroups()) {
            NativeTabGroup nativeGroup = nativeTabGroups.get(group.id());
            if (nativeGroup == null || nativeGroup.tabs().size() != group.tabs().size()) {
                continue;
            }
            int selected = -1;
            for (int index = 0; index < group.tabs().size(); index++) {
                ViewSpec.Tab tab = group.tabs().get(index);
                nativeGroup.bar().setTabActiveState(index, tab.enabled());
                if (tab.selected()) {
                    selected = index;
                }
            }
            selectNativeTab(nativeGroup, selected);
        }
    }

    private void selectNativeTab(NativeTabGroup group, int selected) {
        if (selected < 0 || selected >= group.tabs().size()) {
            return;
        }
        syncingTabSelection = true;
        try {
            group.manager().setCurrentTab(group.tabs().get(selected), false);
        } finally {
            syncingTabSelection = false;
        }
    }

    private boolean applyFocusRequest(ViewSpec current) {
        Optional<ViewSpec.FocusRequest> request = current.focusRequest()
                .filter(value -> value.token() > consumedFocusToken);
        if (request.isEmpty()) {
            return false;
        }
        ViewSpec.FocusRequest value = request.orElseThrow();
        consumedFocusToken = value.token();
        return focusWidget(value.widgetId());
    }

    private boolean focusWidget(String id) {
        AbstractWidget widget = widgets.get(id);
        if (widget == null || !widget.isActive()) {
            return false;
        }
        setInitialFocus(widget);
        return true;
    }

    private String focusedWidgetId() {
        return widgets.entrySet().stream()
                .filter(entry -> entry.getValue().isFocused())
                .map(Map.Entry::getKey)
                .findFirst()
                .orElse(null);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        if (runtime.closed()) {
            return;
        }
        this.mouseX = mouseX;
        this.mouseY = mouseY;
        refresh();
        ViewSpec current = view;
        scrollController.render(
                graphics,
                OFFSCREEN_MOUSE_COORDINATE,
                OFFSCREEN_MOUSE_COORDINATE,
                partialTick);
        publishNativeScroll(current);
        renderListPanels(graphics, current);
        if (renderSelectableCardBackgrounds(graphics, current)) {
            graphics.nextStratum();
        }
        renderPreviews(graphics, current);
        renderBackEquipment(graphics, current);
        graphics.nextStratum();
        renderFramePanels(graphics, current);
        renderScrollbar(graphics, current, mouseX, mouseY);
        graphics.nextStratum();
        renderWidgetsClipped(graphics, current, mouseX, mouseY, partialTick);
        renderProgressDecorations(graphics, current);
        renderIconDecorations(graphics, current, mouseX, mouseY);
        renderTexts(graphics, current, mouseX, mouseY);
        renderTooltips(graphics, current, mouseX, mouseY);
        runtime.acknowledgeViewRendered(current);
    }

    private boolean renderSelectableCardBackgrounds(GuiGraphics graphics, ViewSpec current) {
        boolean rendered = false;
        for (ViewSpec.Widget widget : current.widgets()) {
            if (!CatalogCardStyle.selectionBackgroundBehindContent(widget.kind())) {
                continue;
            }
            int color = CatalogCardStyle.selectableBackgroundColor(
                    CatalogCardStyle.selectionSelected(widget));
            if (color == CatalogCardStyle.TRANSPARENT_BACKGROUND_COLOR) {
                continue;
            }
            Bounds bounds = widget.bounds();
            clipped(graphics, current, widget.id(), () -> graphics.fill(
                    bounds.x(), bounds.y(), bounds.right(), bounds.bottom(), color));
            rendered = true;
        }
        return rendered;
    }

    private void renderWidgetsClipped(
            GuiGraphics graphics,
            ViewSpec current,
            int mouseX,
            int mouseY,
            float partialTick) {
        Optional<String> pointerOwner = ViewHostPolicy.pointerOwnerAt(current, mouseX, mouseY)
                .map(ViewSpec.Widget::id);
        for (Renderable renderable : ((ScreenRenderablesAccessor) (Object) this).nclskins$renderables()) {
            String widgetId = widgets.entrySet().stream()
                    .filter(entry -> entry.getValue() == renderable)
                    .map(Map.Entry::getKey)
                    .findFirst()
                    .orElse(null);
            if (widgetId == null) {
                renderable.render(graphics, mouseX, mouseY, partialTick);
                continue;
            }
            boolean receivesPointer = pointerOwner.map(widgetId::equals).orElse(true);
            boolean insideClip = ViewHostPolicy.pointerInsideClip(
                    current, widgetId, mouseX, mouseY);
            int nativeMouseX = receivesPointer && insideClip ? mouseX : OFFSCREEN_MOUSE_COORDINATE;
            int nativeMouseY = receivesPointer && insideClip ? mouseY : OFFSCREEN_MOUSE_COORDINATE;
            clipped(graphics, current, widgetId, () -> renderable.render(
                    graphics, nativeMouseX, nativeMouseY, partialTick));
        }
    }

    private void renderProgressDecorations(GuiGraphics graphics, ViewSpec current) {
        for (ViewSpec.ProgressDecoration decoration : current.progressDecorations()) {
            ViewSpec.Widget owner = current.widget(decoration.ownerWidgetId()).orElseThrow();
            Bounds bounds = owner.bounds();
            int innerWidth = Math.max(0, bounds.width() - 2);
            int progressWidth = Math.min(
                    innerWidth,
                    Math.max(0, (int) Math.ceil(innerWidth * decoration.fraction())));
            if (progressWidth == 0) {
                continue;
            }
            int left = bounds.x() + 1;
            int bottom = bounds.bottom() - 1;
            int top = Math.max(bounds.y() + 1, bottom - decoration.height());
            clipped(graphics, current, owner.id(), () -> graphics.fill(
                    left, top, left + progressWidth, bottom, decoration.color()));
        }
    }

    private void renderIconDecorations(
            GuiGraphics graphics, ViewSpec current, int mouseX, int mouseY) {
        for (ViewSpec.IconDecoration decoration : current.iconDecorations()) {
            boolean hovered = current.widget(decoration.ownerWidgetId())
                    .filter(widget -> widget.bounds().contains(mouseX, mouseY))
                    .filter(widget -> ViewHostPolicy.pointerInsideClip(
                            current, widget.id(), mouseX, mouseY))
                    .isPresent();
            boolean focused = Optional.ofNullable(widgets.get(decoration.ownerWidgetId()))
                    .map(AbstractWidget::isFocused)
                    .orElse(false);
            float opacity = hovered || focused
                    ? decoration.activeOpacity()
                    : decoration.idleOpacity();
            int color = Math.round(opacity * 255.0F) << 24 | 0x00FFFFFF;
            Bounds bounds = decoration.bounds();
            clipped(graphics, current, decoration.id(), () -> graphics.blit(
                    RenderPipelines.GUI_TEXTURED,
                    actionIconTexture(decoration.icon()),
                    bounds.x(),
                    bounds.y(),
                    0.0F,
                    0.0F,
                    bounds.width(),
                    bounds.height(),
                    DECORATION_ICON_SIZE,
                    DECORATION_ICON_SIZE,
                    color));
        }
    }

    private static boolean ownsDecoration(ViewSpec view, String widgetId) {
        return view.iconDecorations().stream()
                .anyMatch(decoration -> decoration.ownerWidgetId().equals(widgetId));
    }

    private static Identifier actionIconTexture(String icon) {
        if (icon.isBlank() || icon.indexOf(':') >= 0) {
            throw new IllegalArgumentException("Unsupported action icon: " + icon);
        }
        return Identifier.fromNamespaceAndPath(
                "nclskins", "textures/gui/icons/" + icon + ".png");
    }

    private void renderListPanels(GuiGraphics graphics, ViewSpec current) {
        Identifier background = minecraft.level == null
                ? MENU_LIST_BACKGROUND
                : INWORLD_MENU_LIST_BACKGROUND;
        for (ViewSpec.Panel panel : current.panels()) {
            if (panel.style() != ViewSpec.Panel.Style.VANILLA_LIST) {
                continue;
            }
            Bounds b = panel.bounds();
            graphics.blit(
                    RenderPipelines.GUI_TEXTURED,
                    background,
                    b.x(), b.y(), (float) b.x(), (float) b.y(), b.width(), b.height(), 32, 32);
            blitSeparator(
                    graphics,
                    minecraft.level == null ? HEADER_SEPARATOR : INWORLD_HEADER_SEPARATOR,
                    b.x(), b.y() - 2, b.width());
            blitSeparator(
                    graphics,
                    minecraft.level == null ? FOOTER_SEPARATOR : INWORLD_FOOTER_SEPARATOR,
                    b.x(), b.bottom(), b.width());
        }
    }

    private void renderFramePanels(GuiGraphics graphics, ViewSpec current) {
        Identifier background = minecraft.level == null
                ? MENU_LIST_BACKGROUND
                : INWORLD_MENU_LIST_BACKGROUND;
        boolean tabBarOwnsHeaderSeparator = !current.tabGroups().isEmpty();
        for (ViewSpec.Panel panel : current.panels()) {
            if (panel.style() == ViewSpec.Panel.Style.VANILLA_LIST) {
                continue;
            }
            Bounds b = panel.bounds();
            graphics.blit(
                    RenderPipelines.GUI_TEXTURED,
                    background,
                    b.x(), b.y(), 0.0F, (float) b.y(), b.width(), b.height(), 32, 32);
            if (panel.style() == ViewSpec.Panel.Style.VANILLA_HEADER
                    && !tabBarOwnsHeaderSeparator) {
                blitSeparator(
                        graphics,
                        minecraft.level == null ? HEADER_SEPARATOR : INWORLD_HEADER_SEPARATOR,
                        b.x(), b.bottom() - 2, b.width());
            } else if (panel.style() == ViewSpec.Panel.Style.VANILLA_FOOTER) {
                blitSeparator(
                        graphics,
                        minecraft.level == null ? FOOTER_SEPARATOR : INWORLD_FOOTER_SEPARATOR,
                        b.x(), b.y(), b.width());
            }
        }
    }

    private static void blitSeparator(
            GuiGraphics graphics, Identifier texture, int x, int y, int width) {
        graphics.blit(RenderPipelines.GUI_TEXTURED, texture, x, y, 0.0F, 0.0F, width, 2, 32, 2);
    }

    private void renderPreviews(GuiGraphics graphics, ViewSpec current) {
        for (ViewSpec.Preview preview : current.previews()) {
            TextureHandle skin;
            SkinModel model;
            if (preview.requiresLoadedSkin()) {
                Optional<TextureHandle> loaded = Optional.ofNullable(previewKeys.get(preview.id()))
                        .flatMap(skinTextures::handle);
                if (loaded.isEmpty()) {
                    continue;
                }
                skin = loaded.orElseThrow();
                model = preview.variant() == SkinVariant.SLIM ? SkinModel.SLIM : SkinModel.CLASSIC;
            } else {
                PlayerAppearance appearance = runtime.currentPlayerAppearance().orElse(null);
                if (appearance == null) {
                    continue;
                }
                skin = appearance.skin();
                model = appearance.model();
            }
            Optional<TextureHandle> cape = preview.capeId().flatMap(capeTextures::handle);
            PreviewRenderer.PreviewAppearance appearance = new PreviewRenderer.PreviewAppearance(
                    skin,
                    model,
                    cape,
                    cape.isPresent() ? preview.capeMode() : PreviewRenderer.CapeMode.OFF,
                    preview.outerLayerVisibility());
            Bounds b = preview.bounds();
            PreviewRenderer.PreviewRequest request = new PreviewRenderer.PreviewRequest(
                    appearance, b.x(), b.y(), b.width(), b.height(),
                    preview.yawDegrees(), preview.pitchDegrees(), preview.scale(), preview.intent());
            clipped(graphics, current, preview.id(), () -> {
                if ("preset_editor".equals(current.screenId()) && minecraft.level != null) {
                    editorRenderer.render(graphics, request);
                } else {
                    bakedRenderer("preview:" + preview.id()).render(graphics, request);
                }
            });
        }
    }

    private void renderBackEquipment(GuiGraphics graphics, ViewSpec current) {
        for (ViewSpec.BackEquipmentPreview preview : current.backEquipmentPreviews()) {
            capeTextures.handle(preview.capeId()).ifPresent(texture -> clipped(
                    graphics,
                    current,
                    preview.id(),
                    () -> bakedRenderer("equipment:" + preview.id()).render(
                            graphics,
                            new BackEquipmentPreviewRenderer.Request(
                                    texture,
                                    preview.mode(),
                                    preview.bounds().x(),
                                    preview.bounds().y(),
                                    preview.bounds().width(),
                                    preview.bounds().height()))));
        }
    }

    private void renderTexts(
            GuiGraphics graphics, ViewSpec current, int mouseX, int mouseY) {
        for (ViewSpec.Text text : current.texts()) {
            clipped(graphics, current, text.id(), () ->
                    renderText(graphics, current, text, mouseX, mouseY));
        }
    }

    private void renderText(
            GuiGraphics graphics,
            ViewSpec current,
            ViewSpec.Text text,
            int mouseX,
            int mouseY) {
        Component component = Minecraft12111Components.resolve(text.message());
        int color = textColor(text);
        if (font.width(component) > text.bounds().width()
                && MarqueeRouting.active(
                        current,
                        text,
                        mouseX,
                        mouseY,
                        id -> Optional.ofNullable(widgets.get(id))
                                .map(AbstractWidget::isFocused)
                                .orElse(false))) {
            int offset = MarqueeText.offset(
                    font.width(component), text.bounds().width(), System.currentTimeMillis());
            graphics.enableScissor(
                    text.bounds().x(),
                    text.bounds().y(),
                    text.bounds().right(),
                    text.bounds().bottom());
            try {
                graphics.drawString(
                        font,
                        component,
                        text.bounds().x() - offset,
                        text.bounds().y(),
                        color);
            } finally {
                graphics.disableScissor();
            }
            return;
        }
        String fitted = font.plainSubstrByWidth(component.getString(), text.bounds().width());
        Component visible = Component.literal(fitted);
        int x = switch (text.alignment()) {
            case LEFT -> text.bounds().x();
            case CENTER -> text.bounds().x() + text.bounds().width() / 2;
            case RIGHT -> text.bounds().right();
        };
        if (text.alignment() == ViewSpec.Text.Alignment.CENTER) {
            graphics.drawCenteredString(font, visible, x, text.bounds().y(), color);
        } else {
            graphics.drawString(
                    font,
                    visible,
                    text.alignment() == ViewSpec.Text.Alignment.RIGHT
                            ? x - font.width(visible)
                            : x,
                    text.bounds().y(),
                    color);
        }
    }

    private static int textColor(ViewSpec.Text text) {
        UiMessage message = text.message();
        if (message.severity() == UiMessage.Severity.ERROR) {
            return ERROR_COLOR;
        }
        if (text.id().endsWith(".state")
                && "nclskins.gallery.active".equals(message.key())) {
            return ACTIVE_TEXT_COLOR;
        }
        if ("gallery.title".equals(text.id())
                || "editor.title".equals(text.id())
                || text.id().endsWith(".name")) {
            return TEXT_COLOR;
        }
        return MUTED_COLOR;
    }

    private void renderScrollbar(
            GuiGraphics graphics, ViewSpec current, int mouseX, int mouseY) {
        current.scrollbar().ifPresent(scrollbar -> {
            Bounds track = scrollbar.track();
            Bounds thumb = scrollbar.thumb();
            graphics.blitSprite(RenderPipelines.GUI_TEXTURED, SCROLLER_BACKGROUND,
                    track.x(), track.y(), track.width(), track.height());
            graphics.blitSprite(RenderPipelines.GUI_TEXTURED, SCROLLER,
                    thumb.x(), thumb.y(), thumb.width(), thumb.height());
            if (track.contains(mouseX, mouseY)) {
                graphics.requestCursor(pointerCaptured
                        ? scrollbar.orientation() == ViewSpec.Scrollbar.Orientation.VERTICAL
                                ? CursorTypes.RESIZE_NS
                                : CursorTypes.RESIZE_EW
                        : CursorTypes.POINTING_HAND);
            }
        });
    }

    private void renderTooltips(GuiGraphics graphics, ViewSpec current, int x, int y) {
        current.tooltipRegions().stream()
                .filter(region -> region.hitBounds(font.width(Minecraft12111Components.resolve(region.text())))
                        .contains(x, y))
                .filter(region -> current.clipFor(region.id())
                        .filter(clip -> !clip.contains(x, y))
                        .isEmpty())
                .findFirst()
                .ifPresent(region -> graphics.setTooltipForNextFrame(
                        font, Minecraft12111Components.resolve(region.tooltip()), x, y));
    }

    private void synchronizePreviewAssets(ViewSpec current) {
        Set<SkinKey> skins = new HashSet<>();
        Set<String> capes = new HashSet<>();
        for (ViewSpec.Preview preview : current.previews()) {
            SkinKey key = new SkinKey(
                    preview.skin(), preview.imageRevision(), preview.catalogImage(),
                    preview.externalImage(), preview.variant());
            previewKeys.put(preview.id(), key);
            if (preview.requiresLoadedSkin()) {
                skins.add(key);
                skinTextures.request(
                        key,
                        () -> runtime.loadSkinPreview(preview),
                        () -> runtime.reportSkinPreviewFailure(preview));
            }
            preview.capeId().ifPresent(capeId -> {
                capes.add(capeId);
                capeTextures.request(
                        capeId,
                        () -> runtime.loadCapePreview(preview),
                        () -> runtime.reportCapePreviewFailure(preview));
            });
        }
        for (ViewSpec.BackEquipmentPreview preview : current.backEquipmentPreviews()) {
            capes.add(preview.capeId());
            capeTextures.request(
                    preview.capeId(), () -> runtime.loadCapePreview(preview.capeId()), () -> {});
        }
        skinTextures.retain(skins);
        capeTextures.retain(capes);
        Set<String> bakedIds = new HashSet<>();
        current.previews().forEach(preview -> bakedIds.add("preview:" + preview.id()));
        current.backEquipmentPreviews().forEach(
                preview -> bakedIds.add("equipment:" + preview.id()));
        closeMissingBakedRenderers(bakedIds);
    }

    private Minecraft12111SimplePreviewRenderer bakedRenderer(String id) {
        return bakedRenderers.computeIfAbsent(
                id, ignored -> new Minecraft12111SimplePreviewRenderer());
    }

    private void closeMissingBakedRenderers(Set<String> retainedIds) {
        var iterator = bakedRenderers.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            if (!retainedIds.contains(entry.getKey())) {
                entry.getValue().close();
                iterator.remove();
            }
        }
    }

    private static void clipped(
            GuiGraphics graphics, ViewSpec current, String elementId, Runnable draw) {
        Optional<Bounds> clip = current.clipFor(elementId);
        clip.ifPresent(value -> graphics.enableScissor(
                value.x(), value.y(), value.right(), value.bottom()));
        try {
            draw.run();
        } finally {
            if (clip.isPresent()) {
                graphics.disableScissor();
            }
        }
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (runtime.closed()) {
            return false;
        }
        ViewSpec current = runtime.view(
                width, height, (int) event.x(), (int) event.y());
        view = current;
        Optional<ViewSpec.Widget> owner = ViewHostPolicy.pointerOwnerAt(
                current, event.x(), event.y());
        if (owner.isPresent()) {
            ViewSpec.Widget spec = owner.orElseThrow();
            AbstractWidget widget = widgets.get(spec.id());
            if (widget != null
                    && ViewHostPolicy.pointerInsideClip(
                            current, spec.id(), event.x(), event.y())) {
                boolean consumed = widget.mouseClicked(event, doubleClick);
                if (consumed) {
                    setFocused(widget);
                    widget.setFocused(true);
                    if (event.button() == LEFT_MOUSE_BUTTON
                            && spec.kind() == ViewSpec.WidgetKind.TEXT_FIELD
                            && spec.selectAllOnPrimaryClick()
                            && widget instanceof EditBox edit
                            && !edit.getValue().isEmpty()) {
                        edit.setCursorPosition(edit.getValue().length());
                        edit.setHighlightPos(0);
                    }
                }
                return true;
            }
        }
        List<MaskedNativeWidget> masked = maskWidgetsOutsideClip(
                current, event.x(), event.y());
        boolean nativeConsumed;
        try {
            nativeConsumed = super.mouseClicked(event, doubleClick);
        } finally {
            ViewSpec latest = runtime.closed()
                    ? current
                    : runtime.view(width, height, (int) event.x(), (int) event.y());
            view = latest;
            restoreMaskedWidgets(masked, latest);
        }
        if (nativeConsumed) {
            dispatchPendingTabSelection();
            return true;
        }
        PointerRouting.Hit hit = PointerRouting.hit(current, event.x(), event.y());
        if (event.button() == LEFT_MOUSE_BUTTON
                && (hit.scrollbar() || hit.preview("editor.preview"))) {
            pointerCaptured = true;
            runtime.pointerPressed(event.x(), event.y(), event.button());
            return true;
        }
        return hit.anyInteractiveSurface();
    }

    private List<MaskedNativeWidget> maskWidgetsOutsideClip(
            ViewSpec current, double x, double y) {
        List<MaskedNativeWidget> masked = new ArrayList<>();
        for (Map.Entry<String, AbstractWidget> entry : widgets.entrySet()) {
            AbstractWidget widget = entry.getValue();
            if (widget.active
                    && !ViewHostPolicy.pointerInsideClip(current, entry.getKey(), x, y)) {
                masked.add(new MaskedNativeWidget(entry.getKey(), widget, true));
                widget.active = false;
            }
        }
        return masked;
    }

    private void restoreMaskedWidgets(
            List<MaskedNativeWidget> masked, ViewSpec latest) {
        for (MaskedNativeWidget entry : masked) {
            if (widgets.get(entry.id()) != entry.widget()) {
                continue;
            }
            entry.widget().active = latest.widget(entry.id())
                    .map(ViewSpec.Widget::enabled)
                    .orElse(entry.active());
        }
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
        if (pointerCaptured && event.button() == LEFT_MOUSE_BUTTON) {
            runtime.pointerDragged(event.x(), event.y(), event.button(), dragX, dragY);
            return true;
        }
        return super.mouseDragged(event, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (pointerCaptured && event.button() == LEFT_MOUSE_BUTTON) {
            pointerCaptured = false;
            runtime.pointerReleased(event.x(), event.y(), event.button());
            return true;
        }
        return super.mouseReleased(event);
    }

    @Override
    public boolean mouseScrolled(double x, double y, double horizontal, double vertical) {
        if (runtime.closed()) {
            return false;
        }
        ViewSpec current = runtime.view(width, height, (int) x, (int) y);
        PointerRouting.Hit hit = PointerRouting.hit(current, x, y);
        Optional<ViewSpec.ScrollSurface> surface = PointerRouting.scrollSurface(current, x, y);
        if (surface.isEmpty() && hit.scrollbar()) {
            surface = current.scrollSurfaces().stream().findFirst();
        }
        if (surface.isPresent()) {
            scrollController.synchronize(surface);
            boolean consumed = scrollController.forwardScroll(x, y, horizontal, vertical);
            publishNativeScroll(current);
            return consumed;
        }
        if (hit.preview("editor.preview")) {
            runtime.pointerScrolled(x, y, horizontal, vertical);
            return true;
        }
        return super.mouseScrolled(x, y, horizontal, vertical);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (event.isEscape()) {
            onClose();
            return true;
        }
        if ((event.key() == GLFW.GLFW_KEY_ENTER || event.key() == GLFW.GLFW_KEY_KP_ENTER)
                && focusedWidgetId() != null) {
            String focused = focusedWidgetId();
            AbstractWidget widget = widgets.get(focused);
            if (widget instanceof EditBox edit) {
                Optional<String> submit = ViewHostPolicy.submitAction(
                        view, focused, edit.isFocused(), edit.getValue());
                if (submit.isPresent()) {
                    runtime.dispatchWidget(submit.orElseThrow());
                    return true;
                }
            }
        }
        for (NativeTabGroup group : nativeTabGroups.values()) {
            if (group.bar().keyPressed(event)) {
                dispatchPendingTabSelection();
                return true;
            }
        }
        boolean consumed = super.keyPressed(event);
        dispatchPendingTabSelection();
        return consumed;
    }

    private void dispatchPendingTabSelection() {
        String selected = pendingTabSelection;
        pendingTabSelection = null;
        if (selected != null) {
            runtime.dispatchWidget(selected);
        }
    }

    private void publishNativeScroll(ViewSpec current) {
        scrollController.surfaceId().flatMap(current::scrollSurface).ifPresent(surface -> {
            double offset = scrollController.offsetPixels();
            if (Math.abs(offset - surface.offsetPixels()) > 0.001) {
                runtime.nativeScrollPositionChanged(surface.id(), offset);
                scrollController.acceptedRuntimeOffset(offset);
            }
        });
    }

    @Override
    public void onClose() {
        if (!runtime.closed()) {
            runtime.escapePressed();
        }
        if (runtime.closed() && minecraft != null && minecraft.screen == this) {
            minecraft.setScreen(parent);
        }
    }

    @Override
    public void removed() {
        activeScreen = false;
        pointerCaptured = false;
        try {
            if (subscription != null) {
                subscription.close();
                subscription = null;
            }
            if (skinTextures != null) {
                skinTextures.close();
                skinTextures = null;
            }
            if (capeTextures != null) {
                capeTextures.close();
                capeTextures = null;
            }
            if (textureRegistry != null) {
                textureRegistry.close();
                textureRegistry = null;
            }
            bakedRenderers.values().forEach(Minecraft12111SimplePreviewRenderer::close);
            bakedRenderers.clear();
            widgets.clear();
            nativeTabGroups.clear();
            editorRenderer.close();
            runtime.closeScreen();
        } finally {
            super.removed();
        }
    }

    private static List<NativeWidgetSignature> nativeWidgetSignatures(ViewSpec current) {
        return current.widgets().stream().map(NativeWidgetSignature::new).toList();
    }

    private static List<NativeTabSignature> nativeTabSignatures(ViewSpec current) {
        return current.tabGroups().stream().map(NativeTabSignature::new).toList();
    }

    private record NativeWidgetSignature(
            String id,
            ViewSpec.WidgetKind kind,
            String label,
            Optional<String> icon,
            Optional<UiMessage> hint,
            boolean visible,
            int maxLength,
            boolean selectAllOnPrimaryClick,
            Optional<String> submitActionId) {
        private NativeWidgetSignature(ViewSpec.Widget widget) {
            this(
                    widget.id(),
                    widget.kind(),
                    Minecraft12111Components.resolveString(widget.label()),
                    widget.icon(),
                    widget.hint(),
                    widget.visible(),
                    widget.maxLength(),
                    widget.selectAllOnPrimaryClick(),
                    widget.submitActionId());
        }
    }

    private record NativeTabSignature(
            String id, Bounds bounds, List<NativeTabItemSignature> tabs) {
        private NativeTabSignature(ViewSpec.TabGroup group) {
            this(
                    group.id(),
                    group.bounds(),
                    group.tabs().stream().map(NativeTabItemSignature::new).toList());
        }
    }

    private record NativeTabItemSignature(String id, String label) {
        private NativeTabItemSignature(ViewSpec.Tab tab) {
            this(tab.id(), Minecraft12111Components.resolveString(tab.label()));
        }
    }

    private record NativeTabGroup(
            TabManager manager, TabNavigationBar bar, List<Tab> tabs) {
    }

    private record MaskedNativeWidget(
            String id, AbstractWidget widget, boolean active) {
    }

    private record SkinKey(
            SkinReference reference,
            String imageRevision,
            Optional<ViewSpec.CatalogImage> catalogImage,
            Optional<ViewSpec.ExternalImage> externalImage,
            SkinVariant variant) {
        private SkinKey {
            Objects.requireNonNull(reference, "reference");
            Objects.requireNonNull(imageRevision, "imageRevision");
            Objects.requireNonNull(catalogImage, "catalogImage");
            Objects.requireNonNull(externalImage, "externalImage");
            Objects.requireNonNull(variant, "variant");
        }
    }

    private static final class ActionButton extends AbstractButton {
        private final Consumer<InputWithModifiers> action;
        private final ViewSpec.WidgetKind kind;
        private Optional<String> icon;
        private boolean trailingInfo;
        private boolean selected;
        private final boolean transparent;

        private ActionButton(
                Bounds bounds,
                Component label,
                ViewSpec.WidgetKind kind,
                Optional<String> icon,
                boolean trailingInfo,
                boolean selected,
                boolean transparent,
                Consumer<InputWithModifiers> action) {
            super(bounds.x(), bounds.y(), bounds.width(), bounds.height(), label);
            this.kind = Objects.requireNonNull(kind, "kind");
            this.icon = Objects.requireNonNull(icon, "icon");
            this.trailingInfo = trailingInfo;
            this.selected = selected;
            this.transparent = transparent;
            this.action = Objects.requireNonNull(action, "action");
        }

        private void update(ViewSpec.Widget spec) {
            icon = spec.icon();
            trailingInfo = spec.collectionHeaderHasTrailingInfo();
            selected = CatalogCardStyle.selectionSelected(spec);
        }

        @Override
        public void onPress(InputWithModifiers input) {
            action.accept(input);
        }

        @Override
        protected void renderContents(
                GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            var font = Minecraft.getInstance().font;
            if (transparent) {
                return;
            }
            switch (kind) {
                case BUTTON -> {
                    renderDefaultSprite(graphics);
                    graphics.drawCenteredString(
                            font,
                            getMessage(),
                            getX() + getWidth() / 2,
                            getY() + (getHeight() - font.lineHeight) / 2,
                            active ? 0xFFFFFFFF : 0xFFA0A0A0);
                }
                case ICON_BUTTON -> {
                    renderDefaultSprite(graphics);
                    Identifier texture = actionIconTexture(icon.orElseThrow());
                    graphics.blit(
                            RenderPipelines.GUI_TEXTURED,
                            texture,
                            getX() + (getWidth() - ACTION_ICON_RENDER_SIZE) / 2,
                            getY() + (getHeight() - ACTION_ICON_RENDER_SIZE) / 2,
                            0.0F,
                            0.0F,
                            ACTION_ICON_RENDER_SIZE,
                            ACTION_ICON_RENDER_SIZE,
                            ACTION_ICON_TEXTURE_SIZE,
                            ACTION_ICON_TEXTURE_SIZE,
                            ACTION_ICON_TEXTURE_SIZE,
                            ACTION_ICON_TEXTURE_SIZE);
                }
                case INFO_BUTTON -> graphics.drawCenteredString(
                        font,
                        Component.literal("i"),
                        getX() + getWidth() / 2,
                        getY() + Math.max(0, (getHeight() - font.lineHeight) / 2),
                        InfoButtonStyle.labelColor(active, isHoveredOrFocused()));
                case CATALOG_DELETE -> {
                    int background = isHoveredOrFocused() ? 0xCC7A3030 : 0x99302020;
                    graphics.fill(getX(), getY(), getX() + getWidth(), getY() + getHeight(), background);
                    graphics.drawCenteredString(
                            font,
                            Component.literal("×"),
                            getX() + getWidth() / 2,
                            getY() + (getHeight() - font.lineHeight) / 2,
                            active ? 0xFFFFFFFF : 0xFF777777);
                }
                case CATALOG_CARD -> fillIfVisible(
                        graphics, CatalogCardStyle.backgroundColor(active, isHoveredOrFocused()));
                case SELECTABLE_CARD, CAPE_CARD -> fillIfVisible(
                        graphics,
                        CatalogCardStyle.selectableForegroundColor(
                                selected, active, isHoveredOrFocused()));
                case COLLECTION_HEADER -> renderCollectionHeader(graphics, font);
                case TEXT_FIELD -> throw new IllegalStateException("Text field uses EditBox");
            }
        }

        private void renderCollectionHeader(GuiGraphics graphics, net.minecraft.client.gui.Font font) {
            Bounds bounds = new Bounds(getX(), getY(), getWidth(), getHeight());
            CollectionHeaderStyle.Palette palette =
                    CollectionHeaderStyle.palette(active, isHoveredOrFocused());
            fillIfVisible(graphics, palette.backgroundColor());
            int trailingWidth = trailingInfo
                    ? COLLECTION_HEADER_TRAILING_INFO_WIDTH
                    : 0;
            String fitted = font.plainSubstrByWidth(
                    getMessage().getString(),
                    CollectionHeaderStyle.maximumLabelWidth(bounds, trailingWidth));
            CollectionHeaderStyle.Geometry geometry = CollectionHeaderStyle.geometry(
                    bounds, font.lineHeight, font.width(fitted), trailingWidth);
            graphics.drawString(font, fitted, geometry.textX(), geometry.textY(), palette.labelColor());
            if (geometry.hasLine()) {
                graphics.hLine(
                        geometry.lineStart(),
                        geometry.lineEndExclusive() - 1,
                        geometry.lineY(),
                        palette.lineColor());
            }
        }

        private void fillIfVisible(GuiGraphics graphics, int color) {
            if (color != CatalogCardStyle.TRANSPARENT_BACKGROUND_COLOR) {
                graphics.fill(getX(), getY(), getX() + getWidth(), getY() + getHeight(), color);
            }
        }

        @Override
        public void updateWidgetNarration(NarrationElementOutput output) {
            defaultButtonNarrationText(output);
        }
    }
}

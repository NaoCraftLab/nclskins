package com.naocraftlab.skins.compat.mc262;

import com.mojang.blaze3d.platform.cursor.CursorTypes;
import com.naocraftlab.skins.client.CurrentPlayerAppearanceSource.PlayerAppearance;
import com.naocraftlab.skins.client.PreviewRenderer;
import com.naocraftlab.skins.client.SkinModel;
import com.naocraftlab.skins.client.TextureRegistry.TextureHandle;
import com.naocraftlab.skins.client.TextureRegistry.TextureKind;
import com.naocraftlab.skins.compat.mc262.mixin.ScreenRenderablesAccessor;
import com.naocraftlab.skins.core.model.SkinReference;
import com.naocraftlab.skins.core.model.SkinVariant;
import com.naocraftlab.skins.runtime.Bounds;
import com.naocraftlab.skins.runtime.CatalogCardStyle;
import com.naocraftlab.skins.runtime.ClientRuntime;
import com.naocraftlab.skins.runtime.ClientSnapshot;
import com.naocraftlab.skins.runtime.CollectionHeaderStyle;
import com.naocraftlab.skins.runtime.InfoButtonStyle;
import com.naocraftlab.skins.runtime.PreviewAssetCache;
import com.naocraftlab.skins.runtime.PointerRouting;
import com.naocraftlab.skins.runtime.UiMessage;
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
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.tabs.GridLayoutTab;
import net.minecraft.client.gui.components.tabs.MenuTabBar;
import net.minecraft.client.gui.components.tabs.Tab;
import net.minecraft.client.gui.components.tabs.TabManager;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.InputWithModifiers;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;


public final class NclSkinsScreen extends Screen {
    private static final Identifier MENU_LIST_BACKGROUND =
            Identifier.withDefaultNamespace("textures/gui/menu_list_background.png");
    private static final Identifier INWORLD_MENU_LIST_BACKGROUND =
            Identifier.withDefaultNamespace("textures/gui/inworld_menu_list_background.png");
    private static final Identifier SCROLLER_SPRITE =
            Identifier.withDefaultNamespace("widget/scroller");
    private static final Identifier SCROLLER_BACKGROUND_SPRITE =
            Identifier.withDefaultNamespace("widget/scroller_background");
    private static final int ACTION_ICON_SIZE = 15;
    private static final int COLLECTION_HEADER_TRAILING_INFO_WIDTH = 14;
    private static final int OFFSCREEN_MOUSE_COORDINATE = -1_000_000;
    private static final Set<String> APPROVED_ACTION_ICONS = Set.of(
            "edit",
            "plus",
            "duplicate",
            "delete",
            "cape",
            "elytra",
            "head_on",
            "head_off",
            "body_all_on",
            "body_all_off",
            "body_both_arms_off",
            "body_left_arm_off",
            "body_right_arm_off",
            "body_only_arms_on",
            "legs_all_on",
            "legs_all_off",
            "legs_left_off",
            "legs_right_off");
    private static final int TEXT_COLOR = 0xFFE8EDF6;
    private static final int MUTED_COLOR = 0xFF9BA8BC;
    private static final int ERROR_COLOR = 0xFFFF9A9A;
    private static final int ACTIVE_TEXT_COLOR = 0xFF8EE6A5;
    private static final int LEFT_MOUSE_BUTTON = 0;

    private final Screen parent;
    private final ClientRuntime runtime;
    private final Map<String, AbstractWidget> nativeWidgets = new HashMap<>();
    private final Map<String, NativeTabGroup> nativeTabGroups = new HashMap<>();
    private final Map<String, Minecraft262SimplePreviewRenderer> galleryRenderers = new HashMap<>();
    private final Map<String, SkinKey> previewSkinKeys = new HashMap<>();

    private Minecraft262TextureRegistry textureRegistry;
    private PreviewAssetCache<SkinKey> skinTextures;
    private PreviewAssetCache<String> capeTextures;
    private Minecraft262PreviewRenderer editorRenderer;
    private ClientRuntime.Subscription subscription;
    private ViewSpec currentView;
    private List<WidgetSignature> widgetSignature = List.of();
    private List<TabGroupSignature> tabGroupSignature = List.of();
    private boolean activeScreen;
    private boolean rebuilding;
    private boolean syncingTabSelection;
    private boolean pointerCaptured;
    private String pendingTabSelection;
    private long consumedFocusToken;
    private int lastMouseX;
    private int lastMouseY;

    public NclSkinsScreen(Screen parent) {
        super(Component.translatable("nclskins.title"));
        this.parent = parent;
        this.runtime = NclSkins262ClientRuntime.runtime();
    }


    public static void initializeClientRuntime() {
        NclSkins262ClientRuntime.verifyStorageAccess();
    }


    public static void warmSessionSnapshot() {
        NclSkins262ClientRuntime.warmup();
    }

    public static void onClientTick(Minecraft client) {
        NclSkins262ClientRuntime.tick(client);
    }

    public static void closeClientRuntime() {
        NclSkins262ClientRuntime.close();
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
        if (editorRenderer == null) {
            editorRenderer = new Minecraft262PreviewRenderer();
        }

        runtime.reopen();
        currentView = runtime.view(width, height, lastMouseX, lastMouseY);
        nativeWidgets.clear();
        nativeTabGroups.clear();
        addNativeTabGroups(currentView);
        addNativeWidgets(currentView);
        widgetSignature = signatures(currentView);
        tabGroupSignature = tabSignatures(currentView);
        syncPreviewAssets(currentView);

        if (subscription == null) {
            subscription = runtime.subscribe(this::snapshotChanged);
        }
        rebuilding = false;
    }

    @Override
    protected void setInitialFocus() {
        super.setInitialFocus();
        if (currentView != null) {
            applyFocusRequest(currentView);
        }
    }

    @Override
    protected void clearWidgets() {
        super.clearWidgets();


        nativeWidgets.clear();
        nativeTabGroups.clear();
        widgetSignature = List.of();
        tabGroupSignature = List.of();
    }

    private void snapshotChanged(ClientSnapshot snapshot) {
        if (runtime.closed()
                || !activeScreen
                || minecraft == null
                || minecraft.gui.screen() != this) {
            return;
        }
        if (snapshot.lifecycle() == ClientSnapshot.Lifecycle.CLOSED) {
            minecraft.gui.setScreen(parent);
            return;
        }
        ViewSpec next = runtime.view(width, height, lastMouseX, lastMouseY);
        currentView = next;
        syncPreviewAssets(next);
        List<WidgetSignature> nextSignature = signatures(next);
        List<TabGroupSignature> nextTabSignature = tabSignatures(next);
        if (!rebuilding
                && (!nextSignature.equals(widgetSignature)
                        || !nextTabSignature.equals(tabGroupSignature))) {


            String focusedWidgetId = currentFocusedWidgetId();
            long focusTokenBeforeRebuild = consumedFocusToken;
            rebuildWidgets();
            if (focusedWidgetId != null && consumedFocusToken == focusTokenBeforeRebuild) {
                focusWidget(focusedWidgetId);
            }
        } else {
            syncNativeWidgetState(next);
            syncNativeTabState(next);
            applyFocusRequest(next);
        }
    }

    private void addNativeWidgets(ViewSpec view) {
        for (ViewSpec.Widget spec : view.widgets()) {
            if (!spec.visible() && !ownsDecoration(view, spec.id())) {
                continue;
            }
            Bounds bounds = spec.bounds();
            AbstractWidget widget;
            if (spec.kind() == ViewSpec.WidgetKind.TEXT_FIELD) {
                EditBox field = new EditBox(
                        font,
                        bounds.x(),
                        bounds.y(),
                        bounds.width(),
                        bounds.height(),
                        Minecraft262Components.resolve(spec.label()));
                field.setMaxLength(spec.maxLength());
                field.setValue(spec.value().orElse(""));
                spec.hint().ifPresent(hint -> field.setHint(Minecraft262Components.resolve(hint)));
                field.setEditable(spec.enabled());
                field.active = spec.enabled();
                field.setResponder(value -> runtime.dispatchText(spec.id(), value));
                widget = field;
            } else if (spec.kind() == ViewSpec.WidgetKind.CATALOG_CARD) {
                CatalogCardWidget card = new CatalogCardWidget(
                        bounds,
                        Minecraft262Components.resolve(spec.label()),
                        () -> runtime.dispatchWidget(spec.id()));
                card.active = spec.enabled();
                widget = card;
            } else if (spec.kind() == ViewSpec.WidgetKind.CAPE_CARD) {
                CapeCardWidget card = new CapeCardWidget(
                        bounds,
                        Minecraft262Components.resolve(spec.label()),
                        capeCardSelected(spec),
                        () -> runtime.dispatchWidget(spec.id()));
                card.active = spec.enabled();
                widget = card;
            } else if (spec.kind() == ViewSpec.WidgetKind.COLLECTION_HEADER) {
                CollectionHeaderWidget header = new CollectionHeaderWidget(
                        font,
                        bounds,
                        Minecraft262Components.resolve(spec.label()),
                        spec.collectionHeaderHasTrailingInfo(),
                        () -> runtime.dispatchWidget(spec.id()));
                header.active = spec.enabled();
                widget = header;
            } else if (spec.kind() == ViewSpec.WidgetKind.CATALOG_DELETE) {
                CatalogDeleteWidget delete = new CatalogDeleteWidget(
                        font,
                        bounds,
                        Minecraft262Components.resolve(spec.label()),
                        () -> runtime.dispatchWidget(spec.id()));
                delete.active = spec.enabled();
                widget = delete;
            } else if (spec.kind() == ViewSpec.WidgetKind.ICON_BUTTON) {
                IconButtonWidget iconButton = new IconButtonWidget(
                        bounds,
                        Minecraft262Components.resolve(spec.label()),
                        actionIconTexture(spec.icon().orElseThrow(() ->
                                new IllegalArgumentException("Missing icon for " + spec.id()))),
                        input -> runtime.dispatchWidget(spec.id(), input.hasShiftDown()));
                iconButton.active = spec.enabled();
                spec.hint().ifPresent(hint -> iconButton.setTooltip(
                        Tooltip.create(Minecraft262Components.resolve(hint))));
                if (spec.hint().isEmpty()) {
                    iconButton.setTooltip(
                            Tooltip.create(Minecraft262Components.resolve(spec.label())));
                }
                widget = iconButton;
            } else if (spec.kind() == ViewSpec.WidgetKind.INFO_BUTTON) {
                InfoButtonWidget infoButton = new InfoButtonWidget(
                        font,
                        bounds,
                        Minecraft262Components.resolve(spec.label()),
                        () -> runtime.dispatchWidget(spec.id()));
                infoButton.active = spec.enabled();
                infoButton.setTooltip(Tooltip.create(Minecraft262Components.resolve(
                        spec.hint().orElse(spec.label()))));
                widget = infoButton;
            } else {
                if (!spec.visible()) {
                    widget = new TransparentButtonWidget(
                            bounds,
                            Minecraft262Components.resolve(spec.label()),
                            input -> runtime.dispatchWidget(spec.id(), input.hasShiftDown()));
                    widget.active = spec.enabled();
                } else {
                    Button button = Button.builder(
                                    Minecraft262Components.resolve(spec.label()),
                                    ignored -> runtime.dispatchWidget(spec.id()))
                            .bounds(bounds.x(), bounds.y(), bounds.width(), bounds.height())
                            .build();
                    button.active = spec.enabled();
                    spec.hint().ifPresent(hint -> button.setTooltip(
                            Tooltip.create(Minecraft262Components.resolve(hint))));
                    widget = button;
                }
            }
            nativeWidgets.put(spec.id(), widget);
            addRenderableWidget(widget);
        }
    }

    private static Identifier actionIconTexture(String icon) {
        if (!APPROVED_ACTION_ICONS.contains(icon)) {
            throw new IllegalArgumentException("Unsupported action icon: " + icon);
        }
        return Identifier.fromNamespaceAndPath(
                "nclskins", "textures/gui/icons/" + icon + ".png");
    }

    private void addNativeTabGroups(ViewSpec view) {
        for (ViewSpec.TabGroup group : view.tabGroups()) {
            Map<Tab, String> idsByTab = new HashMap<>();
            List<Tab> tabs = new ArrayList<>();
            TabManager manager = new TabManager(
                    ignored -> {},
                    ignored -> {},
                    selected -> {
                        if (!syncingTabSelection) {
                            String selectedId = idsByTab.get(selected);
                            if (selectedId != null) {
                                pendingTabSelection = selectedId;
                            }
                        }
                    },
                    ignored -> {});
            MenuTabBar.Builder builder = MenuTabBar.builder(manager, group.bounds().width());
            int selectedIndex = -1;
            for (int index = 0; index < group.tabs().size(); index++) {
                ViewSpec.Tab spec = group.tabs().get(index);
                Tab tab = new GridLayoutTab(Minecraft262Components.resolve(spec.label()));
                idsByTab.put(tab, spec.id());
                tabs.add(tab);
                builder.addTab(tab);
                if (spec.selected()) {
                    selectedIndex = index;
                }
            }
            MenuTabBar bar = builder.build();
            bar.arrangeElements(group.bounds().width());
            for (int index = 0; index < group.tabs().size(); index++) {
                bar.setTabActiveState(index, group.tabs().get(index).enabled());
            }

            NativeTabGroup nativeGroup = new NativeTabGroup(manager, bar, List.copyOf(tabs));
            nativeTabGroups.put(group.id(), nativeGroup);
            addRenderableWidget(bar);
            selectNativeTab(nativeGroup, selectedIndex);
        }
    }

    private void syncNativeWidgetState(ViewSpec view) {
        for (ViewSpec.Widget spec : view.widgets()) {
            AbstractWidget widget = nativeWidgets.get(spec.id());
            if (widget == null) {
                continue;
            }
            widget.active = spec.enabled();
            widget.visible = spec.visible() || ownsDecoration(view, spec.id());
            Bounds bounds = spec.bounds();
            widget.setRectangle(bounds.width(), bounds.height(), bounds.x(), bounds.y());
            if (widget instanceof IconButtonWidget iconButton) {
                widget.setMessage(Minecraft262Components.resolve(spec.label()));
                iconButton.setIcon(actionIconTexture(spec.icon().orElseThrow(() ->
                        new IllegalArgumentException("Missing icon for " + spec.id()))));
                widget.setTooltip(Tooltip.create(Minecraft262Components.resolve(
                        spec.hint().orElse(spec.label()))));
            }
            if (widget instanceof InfoButtonWidget) {
                widget.setMessage(Minecraft262Components.resolve(spec.label()));
                widget.setTooltip(Tooltip.create(Minecraft262Components.resolve(
                        spec.hint().orElse(spec.label()))));
            }
            if (widget instanceof CapeCardWidget capeCard) {
                capeCard.setSelected(capeCardSelected(spec));
            }
            if (widget instanceof CollectionHeaderWidget header) {
                header.setTrailingInfo(spec.collectionHeaderHasTrailingInfo());
            }
            if (widget instanceof EditBox field) {
                field.setEditable(spec.enabled());
                String value = spec.value().orElse("");
                if (!field.getValue().equals(value) && !field.isFocused()) {
                    field.setValue(value);
                }
            }
        }
    }

    private static boolean capeCardSelected(ViewSpec.Widget spec) {
        return spec.value().filter("selected"::equals).isPresent();
    }

    private void syncNativeTabState(ViewSpec view) {
        for (ViewSpec.TabGroup group : view.tabGroups()) {
            NativeTabGroup nativeGroup = nativeTabGroups.get(group.id());
            if (nativeGroup == null || nativeGroup.tabs().size() != group.tabs().size()) {
                continue;
            }
            int selectedIndex = -1;
            for (int index = 0; index < group.tabs().size(); index++) {
                ViewSpec.Tab tab = group.tabs().get(index);
                nativeGroup.bar().setTabActiveState(index, tab.enabled());
                if (tab.selected()) {
                    selectedIndex = index;
                }
            }
            selectNativeTab(nativeGroup, selectedIndex);
        }
    }

    private void selectNativeTab(NativeTabGroup group, int selectedIndex) {
        if (selectedIndex < 0 || selectedIndex >= group.tabs().size()) {
            return;
        }
        syncingTabSelection = true;
        try {
            group.manager().setCurrentTab(group.tabs().get(selectedIndex), false, false);
        } finally {
            syncingTabSelection = false;
        }
    }

    private void applyFocusRequest(ViewSpec view) {
        if (view.focusRequest().isEmpty()) {
            consumedFocusToken = 0;
            return;
        }
        ViewSpec.FocusRequest request = view.focusRequest().orElseThrow();
        if (request.token() > consumedFocusToken && focusWidget(request.widgetId())) {
            consumedFocusToken = request.token();
        }
    }

    private boolean focusWidget(String widgetId) {
        AbstractWidget target = nativeWidgets.get(widgetId);
        if (target != null && target.visible && target.active) {
            setFocused(target);
            return true;
        }
        return false;
    }

    private String currentFocusedWidgetId() {
        Object focused = getFocused();
        return nativeWidgets.entrySet().stream()
                .filter(entry -> entry.getValue() == focused)
                .map(Map.Entry::getKey)
                .findFirst()
                .orElse(null);
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractBackground(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {


        if (runtime.closed()) {
            return;
        }
        lastMouseX = mouseX;
        lastMouseY = mouseY;
        ViewSpec view = runtime.view(width, height, mouseX, mouseY);
        currentView = view;

        for (ViewSpec.Panel panel : view.panels()) {
            if (panel.style() == ViewSpec.Panel.Style.VANILLA_LIST) {
                drawClipped(graphics, view, panel.id(), () ->
                        drawVanillaListPanel(graphics, panel.bounds()));
            }
        }
        drawPreviews(graphics, view);
        drawCapeTextures(graphics, view);


        graphics.nextStratum();
        drawFrameBackgrounds(graphics, view);
        drawFrameSeparators(graphics, view);
        drawScrollbar(graphics, view, mouseX, mouseY);
        graphics.nextStratum();
        extractRenderablesClipped(graphics, view, mouseX, mouseY, partialTick);
        drawIconDecorations(graphics, view, mouseX, mouseY);
        drawTexts(graphics, view);
        runtime.acknowledgeViewRendered(view);
    }

    private void drawIconDecorations(
            GuiGraphicsExtractor graphics, ViewSpec view, int mouseX, int mouseY) {
        for (ViewSpec.IconDecoration decoration : view.iconDecorations()) {
            boolean hovered = view.widget(decoration.ownerWidgetId())
                    .filter(widget -> widget.bounds().contains(mouseX, mouseY))
                    .filter(widget -> pointerInsideClip(view, widget.id(), mouseX, mouseY))
                    .isPresent();
            boolean focused = Optional.ofNullable(nativeWidgets.get(decoration.ownerWidgetId()))
                    .map(AbstractWidget::isFocused)
                    .orElse(false);
            float opacity = hovered || focused
                    ? decoration.activeOpacity()
                    : decoration.idleOpacity();
            int color = Math.round(opacity * 255.0F) << 24 | 0x00FFFFFF;
            Bounds bounds = decoration.bounds();
            drawClipped(graphics, view, decoration.id(), () -> graphics.blit(
                    RenderPipelines.GUI_TEXTURED,
                    actionIconTexture(decoration.icon()),
                    bounds.x(),
                    bounds.y(),
                    0.0F,
                    0.0F,
                    bounds.width(),
                    bounds.height(),
                    ACTION_ICON_SIZE,
                    ACTION_ICON_SIZE,
                    color));
        }
    }

    private static boolean ownsDecoration(ViewSpec view, String widgetId) {
        return view.iconDecorations().stream()
                .anyMatch(decoration -> decoration.ownerWidgetId().equals(widgetId));
    }

    private void extractRenderablesClipped(
            GuiGraphicsExtractor graphics,
            ViewSpec view,
            int mouseX,
            int mouseY,
            float partialTick) {
        Map<Renderable, String> nativeIds = new IdentityHashMap<>();
        nativeWidgets.forEach((id, widget) -> nativeIds.put(widget, id));
        Optional<String> pointerOwner = pointerOwnerAt(view, mouseX, mouseY).map(ViewSpec.Widget::id);
        for (Renderable renderable : ((ScreenRenderablesAccessor) (Object) this).nclskins$renderables()) {
            String widgetId = nativeIds.get(renderable);
            if (widgetId == null) {
                renderable.extractRenderState(graphics, mouseX, mouseY, partialTick);
                continue;
            }
            boolean receivesPointer = pointerOwner.map(widgetId::equals).orElse(true);
            boolean pointerInsideClip = pointerInsideClip(view, widgetId, mouseX, mouseY);
            int widgetMouseX = receivesPointer && pointerInsideClip
                    ? mouseX
                    : OFFSCREEN_MOUSE_COORDINATE;
            int widgetMouseY = receivesPointer && pointerInsideClip
                    ? mouseY
                    : OFFSCREEN_MOUSE_COORDINATE;
            drawClipped(graphics, view, widgetId, () -> renderable.extractRenderState(
                    graphics, widgetMouseX, widgetMouseY, partialTick));
        }
    }

    private void drawPreviews(GuiGraphicsExtractor graphics, ViewSpec view) {
        PlayerAppearance fallback = runtime.currentPlayerAppearance()
                .orElseThrow(() -> new IllegalStateException("Current-player appearance capability is unavailable"));
        Set<String> visibleIds = new HashSet<>();
        for (ViewSpec.Preview preview : view.previews()) {
            visibleIds.add(preview.id());
            Optional<TextureHandle> loadedSkin = Optional.ofNullable(previewSkinKeys.get(preview.id()))
                    .flatMap(skinTextures::handle);
            if (preview.catalogImage().isPresent() && loadedSkin.isEmpty()) {
                continue;
            }
            TextureHandle skin = loadedSkin.orElse(fallback.skin());
            Optional<TextureHandle> cape = preview.capeId().flatMap(capeTextures::handle);
            PreviewRenderer.CapeMode capeMode = cape.isPresent()
                    ? preview.capeMode()
                    : PreviewRenderer.CapeMode.OFF;
            PreviewRenderer.PreviewAppearance appearance = new PreviewRenderer.PreviewAppearance(
                    skin,
                    preview.variant() == SkinVariant.SLIM ? SkinModel.SLIM : SkinModel.CLASSIC,
                    cape,
                    capeMode,
                    preview.outerLayerVisibility());
            Bounds bounds = preview.bounds();
            PreviewRenderer.PreviewRequest request = new PreviewRenderer.PreviewRequest(
                    appearance,
                    bounds.x(),
                    bounds.y(),
                    bounds.width(),
                    bounds.height(),
                    preview.yawDegrees(),
                    preview.pitchDegrees(),
                    preview.scale());
            if ("preset_editor".equals(view.screenId())) {
                drawClipped(graphics, view, preview.id(), () -> editorRenderer.render(graphics, request));
            } else {
                drawClipped(graphics, view, preview.id(), () -> galleryRenderers
                        .computeIfAbsent(preview.id(), ignored -> new Minecraft262SimplePreviewRenderer())
                        .render(graphics, request));
            }
        }
        galleryRenderers.keySet().removeIf(id -> !visibleIds.contains(id));
    }

    private void drawVanillaListPanel(GuiGraphicsExtractor graphics, Bounds bounds) {
        Identifier background = minecraft.level == null
                ? MENU_LIST_BACKGROUND
                : INWORLD_MENU_LIST_BACKGROUND;
        graphics.blit(
                RenderPipelines.GUI_TEXTURED,
                background,
                bounds.x(),
                bounds.y(),
                (float) bounds.x(),
                (float) bounds.y(),
                bounds.width(),
                bounds.height(),
                32,
                32);
        Identifier header = minecraft.level == null ? HEADER_SEPARATOR : INWORLD_HEADER_SEPARATOR;
        Identifier footer = minecraft.level == null ? FOOTER_SEPARATOR : INWORLD_FOOTER_SEPARATOR;
        graphics.blit(
                RenderPipelines.GUI_TEXTURED,
                header,
                bounds.x(),
                bounds.y() - 2,
                0.0F,
                0.0F,
                bounds.width(),
                2,
                32,
                2);
        graphics.blit(
                RenderPipelines.GUI_TEXTURED,
                footer,
                bounds.x(),
                bounds.bottom(),
                0.0F,
                0.0F,
                bounds.width(),
                2,
                32,
                2);
    }

    private void drawFrameBackgrounds(GuiGraphicsExtractor graphics, ViewSpec view) {
        Identifier background = minecraft.level == null
                ? MENU_LIST_BACKGROUND
                : INWORLD_MENU_LIST_BACKGROUND;
        for (ViewSpec.Panel panel : view.panels()) {
            if (panel.style() != ViewSpec.Panel.Style.VANILLA_HEADER
                    && panel.style() != ViewSpec.Panel.Style.VANILLA_FOOTER) {
                continue;
            }
            Bounds bounds = panel.bounds();
            graphics.blit(
                    RenderPipelines.GUI_TEXTURED,
                    background,
                    bounds.x(),
                    bounds.y(),
                    0.0F,
                    (float) bounds.y(),
                    bounds.width(),
                    bounds.height(),
                    32,
                    32);
        }
    }

    private void drawFrameSeparators(GuiGraphicsExtractor graphics, ViewSpec view) {
        Identifier header = minecraft.level == null ? HEADER_SEPARATOR : INWORLD_HEADER_SEPARATOR;
        Identifier footer = minecraft.level == null ? FOOTER_SEPARATOR : INWORLD_FOOTER_SEPARATOR;
        boolean tabBarOwnsHeaderSeparator = !view.tabGroups().isEmpty();
        for (ViewSpec.Panel panel : view.panels()) {
            Bounds bounds = panel.bounds();
            if (panel.style() == ViewSpec.Panel.Style.VANILLA_HEADER
                    && !tabBarOwnsHeaderSeparator) {
                graphics.blit(
                        RenderPipelines.GUI_TEXTURED,
                        header,
                        bounds.x(),
                        bounds.bottom() - 2,
                        0.0F,
                        0.0F,
                        bounds.width(),
                        2,
                        32,
                        2);
            } else if (panel.style() == ViewSpec.Panel.Style.VANILLA_FOOTER) {
                graphics.blit(
                        RenderPipelines.GUI_TEXTURED,
                        footer,
                        bounds.x(),
                        bounds.y(),
                        0.0F,
                        0.0F,
                        bounds.width(),
                        2,
                        32,
                        2);
            }
        }
    }

    private void drawScrollbar(GuiGraphicsExtractor graphics, ViewSpec view, int mouseX, int mouseY) {
        view.scrollbar().ifPresent(scrollbar -> {
            Bounds track = scrollbar.track();
            Bounds thumb = scrollbar.thumb();
            graphics.blitSprite(
                    RenderPipelines.GUI_TEXTURED,
                    SCROLLER_BACKGROUND_SPRITE,
                    track.x(),
                    track.y(),
                    track.width(),
                    track.height());
            graphics.blitSprite(
                    RenderPipelines.GUI_TEXTURED,
                    SCROLLER_SPRITE,
                    thumb.x(),
                    thumb.y(),
                    thumb.width(),
                    thumb.height());
            if (track.contains(mouseX, mouseY)) {
                graphics.requestCursor(pointerCaptured
                        ? scrollbar.orientation() == ViewSpec.Scrollbar.Orientation.VERTICAL
                                ? CursorTypes.RESIZE_NS
                                : CursorTypes.RESIZE_EW
                        : CursorTypes.POINTING_HAND);
            }
        });
    }

    private void drawTexts(GuiGraphicsExtractor graphics, ViewSpec view) {
        for (ViewSpec.Text text : view.texts()) {
            drawClipped(graphics, view, text.id(), () -> drawText(graphics, text));
        }
    }

    private void drawText(GuiGraphicsExtractor graphics, ViewSpec.Text text) {
            Component component = Minecraft262Components.resolve(text.message());
            String fitted = font.plainSubstrByWidth(component.getString(), text.bounds().width());
            Component visible = Component.literal(fitted);
            int color = textColor(text);
            int x = switch (text.alignment()) {
                case LEFT -> text.bounds().x();
                case CENTER -> text.bounds().x() + text.bounds().width() / 2;
                case RIGHT -> text.bounds().right();
            };
            if (text.alignment() == ViewSpec.Text.Alignment.CENTER) {
                graphics.centeredText(font, visible, x, text.bounds().y(), color);
            } else if (text.alignment() == ViewSpec.Text.Alignment.RIGHT) {
                graphics.text(font, visible, x - font.width(visible), text.bounds().y(), color);
            } else {
                graphics.text(font, visible, x, text.bounds().y(), color);
            }
    }

    private void drawCapeTextures(GuiGraphicsExtractor graphics, ViewSpec view) {
        for (ViewSpec.CapeTexture capeTexture : view.capeTextures()) {
            Optional<TextureHandle> loaded = capeTextures.handle(capeTexture.capeId());
            if (loaded.isEmpty()) {
                continue;
            }
            TextureHandle handle = loaded.orElseThrow();
            Bounds bounds = capeTexture.bounds();
            drawClipped(graphics, view, capeTexture.id(), () -> graphics.blit(
                    RenderPipelines.GUI_TEXTURED,
                    Identifier.parse(handle.location()),
                    bounds.x(),
                    bounds.y(),
                    1.0F,
                    1.0F,
                    bounds.width(),
                    bounds.height(),
                    10,
                    16,
                    handle.width(),
                    handle.height()));
        }
    }

    private static void drawClipped(
            GuiGraphicsExtractor graphics, ViewSpec view, String elementId, Runnable draw) {
        Optional<Bounds> clip = view.clipFor(elementId);
        clip.ifPresent(bounds -> graphics.enableScissor(
                bounds.x(), bounds.y(), bounds.right(), bounds.bottom()));
        try {
            draw.run();
        } finally {
            if (clip.isPresent()) {
                graphics.disableScissor();
            }
        }
    }

    private List<MaskedNativeWidget> maskWidgetsOutsideClip(
            ViewSpec view, double mouseX, double mouseY) {
        List<MaskedNativeWidget> masked = new ArrayList<>();
        Optional<String> pointerOwner = pointerOwnerAt(view, mouseX, mouseY).map(ViewSpec.Widget::id);
        for (Map.Entry<String, AbstractWidget> entry : nativeWidgets.entrySet()) {
            AbstractWidget widget = entry.getValue();
            boolean ownedByOverlay = pointerOwner
                    .map(owner -> !owner.equals(entry.getKey()))
                    .orElse(false);
            if (widget.active
                    && (ownedByOverlay
                            || !pointerInsideClip(view, entry.getKey(), mouseX, mouseY))) {
                masked.add(new MaskedNativeWidget(entry.getKey(), widget, true));
                widget.active = false;
            }
        }
        return masked;
    }

    private void restoreMaskedWidgets(List<MaskedNativeWidget> masked, ViewSpec latestView) {
        for (MaskedNativeWidget entry : masked) {
            if (nativeWidgets.get(entry.id()) != entry.widget()) {
                continue;
            }
            entry.widget().active = latestView.widget(entry.id())
                    .map(ViewSpec.Widget::enabled)
                    .orElse(entry.active());
        }
    }

    private static boolean pointerInsideClip(
            ViewSpec view, String elementId, double mouseX, double mouseY) {
        return view.clipFor(elementId)
                .map(bounds -> bounds.contains(mouseX, mouseY))
                .orElse(true);
    }

    private static Optional<ViewSpec.Widget> pointerOwnerAt(
            ViewSpec view, double mouseX, double mouseY) {
        ViewSpec.Widget owner = null;
        for (ViewSpec.Widget widget : view.widgets()) {
            if (widget.visible()
                    && widget.bounds().contains(mouseX, mouseY)
                    && pointerInsideClip(view, widget.id(), mouseX, mouseY)) {
                owner = widget;
            }
        }
        return Optional.ofNullable(owner);
    }

    private static int textColor(ViewSpec.Text text) {
        String id = text.id();
        UiMessage message = text.message();
        if (message.severity() == UiMessage.Severity.ERROR) {
            return ERROR_COLOR;
        }
        if (id.endsWith(".state") && "nclskins.gallery.active".equals(message.key())) {
            return ACTIVE_TEXT_COLOR;
        }
        if ("gallery.title".equals(id)
                || "editor.title".equals(id)
                || id.endsWith(".name")) {
            return TEXT_COLOR;
        }
        return MUTED_COLOR;
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (runtime.closed()) {
            return false;
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

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (runtime.closed()) {
            return false;
        }
        ViewSpec view = runtime.view(width, height, (int) event.x(), (int) event.y());
        currentView = view;
        Optional<ViewSpec.Widget> pointerOwner = pointerOwnerAt(view, event.x(), event.y());
        Optional<ViewSpec.Widget> priorityAction = pointerOwner.filter(widget ->
                widget.kind() == ViewSpec.WidgetKind.INFO_BUTTON
                        || widget.kind() == ViewSpec.WidgetKind.CATALOG_DELETE);
        if (priorityAction.isPresent()) {
            ViewSpec.Widget action = priorityAction.orElseThrow();
            if (event.button() == LEFT_MOUSE_BUTTON && action.enabled()) {
                runtime.dispatchWidget(action.id(), event.hasShiftDown());
            }
            return true;
        }
        if (event.button() == LEFT_MOUSE_BUTTON && event.hasShiftDown()) {
            Optional<ViewSpec.Widget> cape = view.widget("editor.cape");
            if (cape.isPresent()
                    && pointerOwner.filter(widget -> "editor.cape".equals(widget.id())).isPresent()
                    && cape.orElseThrow().visible()
                    && cape.orElseThrow().enabled()
                    && cape.orElseThrow().bounds().contains(event.x(), event.y())
                    && pointerInsideClip(view, "editor.cape", event.x(), event.y())) {
                runtime.dispatchWidget("editor.cape", true);
                return true;
            }
        }
        if (event.button() == LEFT_MOUSE_BUTTON && pointerOwner.isEmpty()) {
            for (ViewSpec.Widget widget : view.widgets()) {
                if (!widget.visible()
                        && widget.enabled()
                        && widget.bounds().contains(event.x(), event.y())
                        && pointerInsideClip(view, widget.id(), event.x(), event.y())) {
                    runtime.dispatchWidget(widget.id());
                    return true;
                }
            }
        }
        List<MaskedNativeWidget> maskedWidgets =
                maskWidgetsOutsideClip(view, event.x(), event.y());
        boolean nativeConsumed;
        try {
            nativeConsumed = super.mouseClicked(event, doubleClick);
        } finally {
            ViewSpec latestView = runtime.closed()
                    ? view
                    : runtime.view(width, height, (int) event.x(), (int) event.y());
            currentView = latestView;
            restoreMaskedWidgets(maskedWidgets, latestView);
        }
        dispatchPendingTabSelection();
        if (nativeConsumed) {
            return true;
        }
        if (pointerOwner.isPresent()) {


            return true;
        }
        if (event.button() == LEFT_MOUSE_BUTTON && capturesPointer(view, event.x(), event.y())) {
            pointerCaptured = true;
            runtime.pointerPressed(event.x(), event.y(), event.button());
            return true;
        }
        return false;
    }

    private void dispatchPendingTabSelection() {
        String selectedId = pendingTabSelection;
        pendingTabSelection = null;
        if (selectedId != null) {
            runtime.dispatchWidget(selectedId);
        }
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
        if (runtime.closed()) {
            pointerCaptured = false;
            return false;
        }
        if (pointerCaptured && event.button() == LEFT_MOUSE_BUTTON) {
            runtime.pointerDragged(event.x(), event.y(), event.button(), dragX, dragY);
            return true;
        }
        return super.mouseDragged(event, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (runtime.closed()) {
            pointerCaptured = false;
            return false;
        }
        if (pointerCaptured && event.button() == LEFT_MOUSE_BUTTON) {
            pointerCaptured = false;
            runtime.pointerReleased(event.x(), event.y(), event.button());
            return true;
        }
        return super.mouseReleased(event);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (runtime.closed()) {
            return false;
        }
        ViewSpec view = runtime.view(width, height, (int) mouseX, (int) mouseY);
        PointerRouting.Hit hit = PointerRouting.hit(view, mouseX, mouseY);
        boolean editorPreview = hit.preview("editor.preview");
        boolean gallery = PointerRouting.galleryScrollRegion(view, height, mouseY);
        boolean verticalScrollable = isVerticalScrollSurface(view, mouseX, mouseY);
        boolean catalog = PointerRouting.clipRegion(
                view, "add.catalog.viewport", mouseX, mouseY);
        boolean capeGallery = PointerRouting.clipRegion(
                view, "editor.capes", mouseX, mouseY);
        if (editorPreview
                || gallery
                || verticalScrollable
                || catalog
                || capeGallery
                || hit.scrollbar()) {
            runtime.pointerScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
    }

    private static boolean capturesPointer(ViewSpec view, double mouseX, double mouseY) {
        PointerRouting.Hit hit = PointerRouting.hit(view, mouseX, mouseY);
        return hit.scrollbar() || hit.preview("editor.preview");
    }

    private static boolean isVerticalScrollSurface(ViewSpec view, double mouseX, double mouseY) {
        return view.scrollbar()
                .filter(scrollbar -> scrollbar.orientation() == ViewSpec.Scrollbar.Orientation.VERTICAL)
                .map(ViewSpec.Scrollbar::track)
                .filter(track -> mouseX >= 0.0 && mouseX < view.width())
                .filter(track -> mouseY >= track.y() && mouseY < track.bottom())
                .isPresent();
    }

    private void syncPreviewAssets(ViewSpec view) {
        if (textureRegistry == null) {
            return;
        }
        Set<SkinKey> desiredSkins = new HashSet<>();
        Set<String> desiredCapes = new HashSet<>();
        previewSkinKeys.clear();
        for (ViewSpec.Preview preview : view.previews()) {
            SkinKey key = new SkinKey(
                    preview.skin(),
                    preview.imageRevision(),
                    preview.catalogImage(),
                    preview.variant());
            previewSkinKeys.put(preview.id(), key);
            desiredSkins.add(key);
            ensureSkin(preview, key);
            preview.capeId().ifPresent(capeId -> {
                desiredCapes.add(capeId);
                ensureCape(preview, capeId);
            });
        }
        for (ViewSpec.CapeTexture capeTexture : view.capeTextures()) {
            desiredCapes.add(capeTexture.capeId());
            capeTextures.request(
                    capeTexture.capeId(),
                    () -> runtime.loadCapePreview(capeTexture.capeId()),
                    () -> {});
        }
        skinTextures.retain(desiredSkins);
        capeTextures.retain(desiredCapes);
    }

    private void ensureSkin(ViewSpec.Preview preview, SkinKey key) {
        skinTextures.request(
                key,
                () -> runtime.loadSkinPreview(preview),
                () -> runtime.reportSkinPreviewFailure(preview));
    }

    private void ensureCape(ViewSpec.Preview preview, String capeId) {
        capeTextures.request(
                capeId,
                () -> runtime.loadCapePreview(preview),
                () -> runtime.reportCapePreviewFailure(preview));
    }

    private static List<WidgetSignature> signatures(ViewSpec view) {
        return view.widgets().stream().map(WidgetSignature::new).toList();
    }

    private static List<TabGroupSignature> tabSignatures(ViewSpec view) {
        return view.tabGroups().stream().map(TabGroupSignature::new).toList();
    }

    @Override
    public void removed() {
        activeScreen = false;
        pointerCaptured = false;
        Minecraft262TextureRegistry registry = textureRegistry;
        PreviewAssetCache<SkinKey> skins = skinTextures;
        PreviewAssetCache<String> capes = capeTextures;
        textureRegistry = null;
        skinTextures = null;
        capeTextures = null;
        try {
            if (subscription != null) {
                subscription.close();
                subscription = null;
            }
        } finally {
            try {
                if (skins != null) {
                    skins.close();
                }
            } finally {
                try {
                    if (capes != null) {
                        capes.close();
                    }
                } finally {
                    try {
                        if (registry != null) {
                            registry.close();
                        }
                    } finally {
                        previewSkinKeys.clear();
                        galleryRenderers.clear();
                        nativeWidgets.clear();
                        nativeTabGroups.clear();
                        try {
                            runtime.closeScreen();
                        } finally {
                            super.removed();
                        }
                    }
                }
            }
        }
    }

    @Override
    public void onClose() {
        if (runtime.closed()) {
            return;
        }
        ClientSnapshot snapshot = runtime.snapshot();
        if (snapshot.busy()) {
            return;
        }
        Optional<String> transientCancel = (currentView == null ? java.util.stream.Stream.<ViewSpec.Widget>empty()
                        : currentView.widgets().stream())
                .map(ViewSpec.Widget::id)
                .filter(id -> id.endsWith(".delete_cancel")
                        || id.equals("add.catalog.rename.cancel"))
                .findFirst();
        if (transientCancel.isPresent()) {
            runtime.dispatchWidget(transientCancel.orElseThrow());
            return;
        }
        if (snapshot.editor().isPresent()) {
            runtime.dispatchWidget("editor.cancel");
        } else if (snapshot.addSource().isPresent()) {
            runtime.dispatchWidget("add.cancel");
        } else {
            runtime.closeScreen();
        }
    }

    private record SkinKey(
            SkinReference reference,
            String imageRevision,
            Optional<ViewSpec.CatalogImage> catalogImage,
            SkinVariant variant) {
        private SkinKey {
            Objects.requireNonNull(reference, "reference");
            Objects.requireNonNull(imageRevision, "imageRevision");
            Objects.requireNonNull(catalogImage, "catalogImage");
            Objects.requireNonNull(variant, "variant");
        }
    }

    private record NativeTabGroup(TabManager manager, MenuTabBar bar, List<Tab> tabs) {}

    private record MaskedNativeWidget(String id, AbstractWidget widget, boolean active) {}

    private record TabGroupSignature(String id, Bounds bounds, List<TabSignature> tabs) {
        private TabGroupSignature(ViewSpec.TabGroup group) {
            this(
                    group.id(),
                    group.bounds(),
                    group.tabs().stream().map(TabSignature::new).toList());
        }
    }

    private record TabSignature(String id, String label) {
        private TabSignature(ViewSpec.Tab tab) {
            this(tab.id(), Minecraft262Components.resolveString(tab.label()));
        }
    }

    private record WidgetSignature(
            String id,
            ViewSpec.WidgetKind kind,
            String label,
            Optional<String> icon,
            Optional<UiMessage> hint,
            boolean visible,
            int maxLength) {
        private WidgetSignature(ViewSpec.Widget widget) {


            this(
                    widget.id(),
                    widget.kind(),
                    widget.kind() == ViewSpec.WidgetKind.ICON_BUTTON
                            ? ""
                            : Minecraft262Components.resolveString(widget.label()),
                    widget.kind() == ViewSpec.WidgetKind.ICON_BUTTON
                            ? Optional.empty()
                            : widget.icon(),
                    widget.kind() == ViewSpec.WidgetKind.ICON_BUTTON
                            ? Optional.empty()
                            : widget.hint(),
                    widget.visible(),
                    widget.maxLength());
        }
    }


    private static final class IconButtonWidget extends AbstractButton {
        private Identifier icon;
        private final Consumer<InputWithModifiers> onPress;

        private IconButtonWidget(
                Bounds bounds,
                Component message,
                Identifier icon,
                Consumer<InputWithModifiers> onPress) {
            super(bounds.x(), bounds.y(), bounds.width(), bounds.height(), message);
            this.icon = Objects.requireNonNull(icon, "icon");
            this.onPress = Objects.requireNonNull(onPress, "onPress");
        }

        private void setIcon(Identifier icon) {
            this.icon = Objects.requireNonNull(icon, "icon");
        }

        @Override
        public void onPress(InputWithModifiers input) {
            onPress.accept(input);
        }

        @Override
        protected void extractContents(
                GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
            extractDefaultSprite(graphics);
            graphics.blit(
                    RenderPipelines.GUI_TEXTURED,
                    icon,
                    getX() + (getWidth() - ACTION_ICON_SIZE) / 2,
                    getY() + (getHeight() - ACTION_ICON_SIZE) / 2,
                    0.0F,
                    0.0F,
                    ACTION_ICON_SIZE,
                    ACTION_ICON_SIZE,
                    ACTION_ICON_SIZE,
                    ACTION_ICON_SIZE);
        }

        @Override
        public void updateWidgetNarration(NarrationElementOutput output) {
            defaultButtonNarrationText(output);
        }
    }


    private static final class TransparentButtonWidget extends AbstractButton {
        private final Consumer<InputWithModifiers> onPress;

        private TransparentButtonWidget(
                Bounds bounds, Component message, Consumer<InputWithModifiers> onPress) {
            super(bounds.x(), bounds.y(), bounds.width(), bounds.height(), message);
            this.onPress = Objects.requireNonNull(onPress, "onPress");
        }

        @Override
        public void onPress(InputWithModifiers input) {
            onPress.accept(input);
        }

        @Override
        protected void extractContents(
                GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {

        }

        @Override
        public void updateWidgetNarration(NarrationElementOutput output) {
            defaultButtonNarrationText(output);
        }
    }


    private static final class CatalogCardWidget extends AbstractButton {
        private final Runnable onPress;

        private CatalogCardWidget(Bounds bounds, Component message, Runnable onPress) {
            super(bounds.x(), bounds.y(), bounds.width(), bounds.height(), message);
            this.onPress = Objects.requireNonNull(onPress, "onPress");
        }

        @Override
        public void onPress(InputWithModifiers input) {
            onPress.run();
        }

        @Override
        protected void extractContents(
                GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
            int backgroundColor =
                    CatalogCardStyle.backgroundColor(active, isHoveredOrFocused());
            if (backgroundColor != CatalogCardStyle.TRANSPARENT_BACKGROUND_COLOR) {
                graphics.fill(
                        getX(),
                        getY(),
                        getX() + getWidth(),
                        getY() + getHeight(),
                        backgroundColor);
            }
        }

        @Override
        public void updateWidgetNarration(NarrationElementOutput output) {
            defaultButtonNarrationText(output);
        }
    }


    private static final class CapeCardWidget extends AbstractButton {
        private final Runnable onPress;
        private boolean selected;

        private CapeCardWidget(
                Bounds bounds, Component message, boolean selected, Runnable onPress) {
            super(bounds.x(), bounds.y(), bounds.width(), bounds.height(), message);
            this.selected = selected;
            this.onPress = Objects.requireNonNull(onPress, "onPress");
        }

        private void setSelected(boolean selected) {
            this.selected = selected;
        }

        @Override
        public void onPress(InputWithModifiers input) {
            onPress.run();
        }

        @Override
        protected void extractContents(
                GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
            int color = selected
                    ? 0x665A8FCB
                    : CatalogCardStyle.backgroundColor(active, isHoveredOrFocused());
            if (color != CatalogCardStyle.TRANSPARENT_BACKGROUND_COLOR) {
                graphics.fill(getX(), getY(), getX() + getWidth(), getY() + getHeight(), color);
            }
        }

        @Override
        public void updateWidgetNarration(NarrationElementOutput output) {
            defaultButtonNarrationText(output);
        }
    }


    private static final class CatalogDeleteWidget extends AbstractButton {
        private final Font font;
        private final Runnable onPress;

        private CatalogDeleteWidget(Font font, Bounds bounds, Component message, Runnable onPress) {
            super(bounds.x(), bounds.y(), bounds.width(), bounds.height(), message);
            this.font = Objects.requireNonNull(font, "font");
            this.onPress = Objects.requireNonNull(onPress, "onPress");
        }

        @Override
        public void onPress(InputWithModifiers input) {
            onPress.run();
        }

        @Override
        protected void extractContents(
                GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
            int background = isHoveredOrFocused() ? 0xCC7A3030 : 0x99302020;
            graphics.fill(getX(), getY(), getX() + getWidth(), getY() + getHeight(), background);
            graphics.centeredText(
                    font,
                    Component.literal("×"),
                    getX() + getWidth() / 2,
                    getY() + (getHeight() - font.lineHeight) / 2,
                    active ? 0xFFFFFFFF : 0xFF777777);
        }

        @Override
        public void updateWidgetNarration(NarrationElementOutput output) {
            defaultButtonNarrationText(output);
        }
    }


    private static final class InfoButtonWidget extends AbstractButton {
        private final Font font;
        private final Runnable onPress;

        private InfoButtonWidget(Font font, Bounds bounds, Component message, Runnable onPress) {
            super(bounds.x(), bounds.y(), bounds.width(), bounds.height(), message);
            this.font = Objects.requireNonNull(font, "font");
            this.onPress = Objects.requireNonNull(onPress, "onPress");
        }

        @Override
        public void onPress(InputWithModifiers input) {
            onPress.run();
        }

        @Override
        protected void extractContents(
                GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
            graphics.centeredText(
                    font,
                    Component.literal("i"),
                    getX() + getWidth() / 2,
                    getY() + Math.max(0, (getHeight() - font.lineHeight) / 2),
                    InfoButtonStyle.labelColor(active, isHoveredOrFocused()));
        }

        @Override
        public void updateWidgetNarration(NarrationElementOutput output) {
            defaultButtonNarrationText(output);
        }
    }


    private static final class CollectionHeaderWidget extends AbstractButton {
        private final Font font;
        private final Runnable onPress;
        private boolean trailingInfo;

        private CollectionHeaderWidget(
                Font font,
                Bounds bounds,
                Component message,
                boolean trailingInfo,
                Runnable onPress) {
            super(bounds.x(), bounds.y(), bounds.width(), bounds.height(), message);
            this.font = Objects.requireNonNull(font, "font");
            this.trailingInfo = trailingInfo;
            this.onPress = Objects.requireNonNull(onPress, "onPress");
        }

        private void setTrailingInfo(boolean trailingInfo) {
            this.trailingInfo = trailingInfo;
        }

        @Override
        public void onPress(InputWithModifiers input) {
            onPress.run();
        }

        @Override
        protected void extractContents(
                GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
            Bounds bounds = new Bounds(getX(), getY(), getWidth(), getHeight());
            CollectionHeaderStyle.Palette palette =
                    CollectionHeaderStyle.palette(active, isHoveredOrFocused());
            if (palette.backgroundColor() != CollectionHeaderStyle.TRANSPARENT_BACKGROUND_COLOR) {
                graphics.fill(
                        bounds.x(),
                        bounds.y(),
                        bounds.right(),
                        bounds.bottom(),
                        palette.backgroundColor());
            }

            int trailingControlWidth = trailingInfo ? COLLECTION_HEADER_TRAILING_INFO_WIDTH : 0;
            String fitted = font.plainSubstrByWidth(
                    getMessage().getString(),
                    CollectionHeaderStyle.maximumLabelWidth(bounds, trailingControlWidth));
            Component visible = Component.literal(fitted);
            CollectionHeaderStyle.Geometry geometry = CollectionHeaderStyle.geometry(
                    bounds, font.lineHeight, font.width(visible), trailingControlWidth);
            graphics.text(
                    font,
                    visible,
                    geometry.textX(),
                    geometry.textY(),
                    palette.labelColor());

            if (geometry.hasLine()) {
                graphics.horizontalLine(
                        geometry.lineStart(),
                        geometry.lineEndExclusive() - 1,
                        geometry.lineY(),
                        palette.lineColor());
            }
        }

        @Override
        public void updateWidgetNarration(NarrationElementOutput output) {
            defaultButtonNarrationText(output);
        }
    }
}

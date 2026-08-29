package com.naocraftlab.skins.compat.client.identifier.extraction;

import com.mojang.blaze3d.platform.cursor.CursorTypes;
import com.naocraftlab.skins.client.BackEquipmentPreviewRenderer;
import com.naocraftlab.skins.client.CurrentPlayerAppearanceSource.PlayerAppearance;
import com.naocraftlab.skins.client.FilePicker;
import com.naocraftlab.skins.client.PreviewRenderer;
import com.naocraftlab.skins.client.SkinModel;
import com.naocraftlab.skins.client.TextureRegistry.TextureHandle;
import com.naocraftlab.skins.client.TextureRegistry.TextureKind;
import com.naocraftlab.skins.compat.client.identifier.IdentifierTextureRegistry;
import com.naocraftlab.skins.core.model.SkinReference;
import com.naocraftlab.skins.core.model.SkinVariant;
import com.naocraftlab.skins.runtime.Bounds;
import com.naocraftlab.skins.runtime.CatalogCardStyle;
import com.naocraftlab.skins.runtime.ClientRuntime;
import com.naocraftlab.skins.runtime.ClientSnapshot;
import com.naocraftlab.skins.runtime.CollectionHeaderStyle;
import com.naocraftlab.skins.runtime.FocusRequestLedger;
import com.naocraftlab.skins.runtime.GuiIcon;
import com.naocraftlab.skins.runtime.InfoButtonStyle;
import com.naocraftlab.skins.runtime.InteractionOrigin;
import com.naocraftlab.skins.runtime.MarqueeRouting;
import com.naocraftlab.skins.runtime.MarqueeText;
import com.naocraftlab.skins.runtime.PreviewAssetCache;
import com.naocraftlab.skins.runtime.PointerRouting;
import com.naocraftlab.skins.runtime.UiMessage;
import com.naocraftlab.skins.runtime.VanillaListSurface;
import com.naocraftlab.skins.runtime.ViewSpec;
import com.naocraftlab.skins.runtime.ViewHostPolicy;
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
import com.mojang.blaze3d.platform.InputConstants;


public final class NclSkinsScreen extends Screen {
    private static final Identifier MENU_LIST_BACKGROUND =
            Identifier.withDefaultNamespace("textures/gui/menu_list_background.png");
    private static final Identifier INWORLD_MENU_LIST_BACKGROUND =
            Identifier.withDefaultNamespace("textures/gui/inworld_menu_list_background.png");
    private static final Identifier TAB_HEADER_BACKGROUND =
            Identifier.withDefaultNamespace("textures/gui/tab_header_background.png");
    private static final Identifier SCROLLER_SPRITE =
            Identifier.withDefaultNamespace("widget/scroller");
    private static final Identifier SCROLLER_BACKGROUND_SPRITE =
            Identifier.withDefaultNamespace("widget/scroller_background");
    private static final int COLLECTION_HEADER_TRAILING_INFO_WIDTH = 14;
    private static final int OFFSCREEN_MOUSE_COORDINATE = -1_000_000;
    private static final int TEXT_COLOR = 0xFFE8EDF6;
    private static final int MUTED_COLOR = 0xFF9BA8BC;
    private static final int ERROR_COLOR = 0xFFFF9A9A;
    private static final int ACTIVE_TEXT_COLOR = 0xFF8EE6A5;
    private static final int NATIVE_LEFT_MOUSE_BUTTON = InputConstants.MOUSE_BUTTON_LEFT;
    private static final int PRODUCT_PRIMARY_POINTER_BUTTON = 0;

    private final Screen parent;
    private final ClientRuntime runtime;
    private final ExtractionScrollController scrollController = new ExtractionScrollController();
    private final Map<String, AbstractWidget> nativeWidgets = new HashMap<>();
    private final Map<String, NativeTabGroup> nativeTabGroups = new HashMap<>();
    private final Map<String, AvatarPipPreviewRenderer> galleryRenderers = new HashMap<>();
    private final Map<String, AvatarPipPreviewRenderer> backEquipmentRenderers =
            new HashMap<>();
    private final Map<String, SkinKey> previewSkinKeys = new HashMap<>();
    private final List<Renderable> orderedRenderables = new ArrayList<>();

    private IdentifierTextureRegistry textureRegistry;
    private PreviewAssetCache<SkinKey> skinTextures;
    private PreviewAssetCache<String> capeTextures;
    private AvatarRenderStatePreviewRenderer editorRenderer;
    private ClientRuntime.Subscription subscription;
    private ViewSpec currentView;
    private List<WidgetSignature> widgetSignature = List.of();
    private List<TabGroupSignature> tabGroupSignature = List.of();
    private boolean activeScreen;
    private boolean rebuilding;
    private boolean syncingTabSelection;
    private boolean pointerCaptured;
    private String pendingTabSelection;
    private final FocusRequestLedger focusRequests = new FocusRequestLedger();
    private int nativeDispatchDepth;
    private Map<String, EditBox> retainedEditBoxes = Map.of();
    private int lastMouseX;
    private int lastMouseY;
    private boolean dispatchShiftDown;
    private InteractionOrigin dispatchOrigin = InteractionOrigin.PROGRAMMATIC;

    public NclSkinsScreen(Screen parent) {
        super(Component.translatable("nclskins.title"));
        this.parent = parent;
        this.runtime = NclSkinsExtractionClientRuntime.runtime();
    }


    public static void initializeClientRuntime(java.nio.file.Path dataRoot) {
        NclSkinsExtractionClientRuntime.initialize(dataRoot);
    }

    public static FilePicker nativeFileDialog() {
        return NclSkinsExtractionClientRuntime.nativeFileDialog();
    }


    public static void warmSessionSnapshot() {
        NclSkinsExtractionClientRuntime.warmup();
    }

    public static void onClientTick(Minecraft client) {
        NclSkinsExtractionClientRuntime.tick(client);
    }

    public static void closeClientRuntime() {
        NclSkinsExtractionClientRuntime.close();
    }

    @Override
    protected void init() {
        activeScreen = true;
        if (runtime.closed()) {
            return;
        }
        rebuilding = true;
        if (textureRegistry == null) {
            textureRegistry = new IdentifierTextureRegistry();
            skinTextures = new PreviewAssetCache<>(
                    textureRegistry, TextureKind.PLAYER_SKIN, runtime.diagnostics());
            capeTextures = new PreviewAssetCache<>(
                    textureRegistry, TextureKind.IMAGE, runtime.diagnostics());
        }
        if (editorRenderer == null) {
            editorRenderer = new AvatarRenderStatePreviewRenderer(runtime.diagnostics());
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
        orderedRenderables.clear();


        nativeWidgets.clear();
        nativeTabGroups.clear();
        widgetSignature = List.of();
        tabGroupSignature = List.of();
    }

    private void snapshotChanged(ClientSnapshot snapshot) {
        if (runtime.closed()
                || !activeScreen
                || minecraft == null
                || ExtractionGuiApi.currentScreen(minecraft) != this) {
            return;
        }
        if (snapshot.lifecycle() == ClientSnapshot.Lifecycle.CLOSED) {
            ExtractionGuiApi.setScreen(minecraft, parent);
            return;
        }
        ViewSpec next = runtime.view(width, height, lastMouseX, lastMouseY);
        currentView = next;
        scrollController.synchronize(next.scrollSurfaces().stream().findFirst());
        syncPreviewAssets(next);
        List<WidgetSignature> nextSignature = signatures(next);
        List<TabGroupSignature> nextTabSignature = tabSignatures(next);
        if (!rebuilding
                && (!nextSignature.equals(widgetSignature)
                        || !nextTabSignature.equals(tabGroupSignature))) {


            String focusedWidgetId = currentFocusedWidgetId();
            boolean pendingFocusBeforeRebuild = focusRequests.pending(next).isPresent();
            retainedEditBoxes = retainedEditBoxes();
            try {
                rebuildWidgets();
            } finally {
                retainedEditBoxes = Map.of();
            }
            if (focusedWidgetId != null
                    && !(pendingFocusBeforeRebuild
                            && focusRequests.pending(next).isEmpty())) {
                focusWidget(focusedWidgetId);
            }
        } else {
            syncNativeWidgetState(next);
            syncNativeTabState(next);
            applyFocusRequest(next);
        }
    }

    private Map<String, EditBox> retainedEditBoxes() {
        Map<String, EditBox> retained = new HashMap<>();
        nativeWidgets.forEach((id, widget) -> {
            if (widget instanceof EditBox editBox) {
                retained.put(id, editBox);
            }
        });
        return retained;
    }

    private void addNativeWidgets(ViewSpec view) {
        for (ViewSpec.Widget spec : view.widgets()) {
            if (!spec.visible() && !ownsDecoration(view, spec.id())) {
                continue;
            }
            Bounds bounds = spec.bounds();
            AbstractWidget widget;
            if (spec.kind() == ViewSpec.WidgetKind.TEXT_FIELD) {
                EditBox field = retainedEditBoxes.get(spec.id());
                boolean retained = field != null;
                if (!retained) {
                    field = new EditBox(
                            font,
                            bounds.x(),
                            bounds.y(),
                            bounds.width(),
                            bounds.height(),
                            MinecraftClientComponents.resolve(spec.label()));
                }
                field.setMaxLength(spec.maxLength());
                String initialValue = spec.value().orElse("");
                if (!retained) {
                    field.setValue(initialValue);
                }
                if (spec.hint().isPresent()) {
                    field.setHint(MinecraftClientComponents.resolve(spec.hint().orElseThrow()));
                }
                field.setEditable(spec.enabled());
                field.active = spec.enabled();
                String[] responderValue = {initialValue};
                field.setResponder(value -> {
                    if (!value.equals(responderValue[0])) {
                        responderValue[0] = value;
                        runtime.dispatchText(spec.id(), value);
                    }
                });
                widget = field;
            } else if (spec.kind() == ViewSpec.WidgetKind.CATALOG_CARD) {
                CatalogCardWidget card = new CatalogCardWidget(
                        bounds,
                        MinecraftClientComponents.resolve(spec.label()),
                        () -> dispatchNativeWidget(spec.id(), false));
                card.active = spec.enabled();
                widget = card;
            } else if (spec.kind() == ViewSpec.WidgetKind.CAPE_CARD
                    || spec.kind() == ViewSpec.WidgetKind.SELECTABLE_CARD) {
                CapeCardWidget card = new CapeCardWidget(
                        bounds,
                        MinecraftClientComponents.resolve(spec.label()),
                        CatalogCardStyle.selectionSelected(spec),
                        CatalogCardStyle.selectionBackgroundBehindContent(spec.kind()),
                        () -> dispatchNativeWidget(spec.id(), false));
                card.active = spec.enabled();
                widget = card;
            } else if (spec.kind() == ViewSpec.WidgetKind.COLLECTION_HEADER) {
                CollectionHeaderWidget header = new CollectionHeaderWidget(
                        font,
                        bounds,
                        MinecraftClientComponents.resolve(spec.label()),
                        spec.collectionHeaderHasTrailingInfo(),
                        () -> dispatchNativeWidget(spec.id(), false));
                header.active = spec.enabled();
                widget = header;
            } else if (spec.kind() == ViewSpec.WidgetKind.CATALOG_DELETE) {
                CatalogDeleteWidget delete = new CatalogDeleteWidget(
                        font,
                        bounds,
                        MinecraftClientComponents.resolve(spec.label()),
                        () -> dispatchNativeWidget(spec.id(), false));
                delete.active = spec.enabled();
                widget = delete;
            } else if (spec.kind() == ViewSpec.WidgetKind.ICON_BUTTON
                    || spec.kind() == ViewSpec.WidgetKind.ICON_ONLY_BUTTON) {
                IconButtonWidget iconButton = new IconButtonWidget(
                        bounds,
                        MinecraftClientComponents.resolve(spec.label()),
                        spec.icon().orElseThrow(() ->
                                new IllegalArgumentException("Missing icon for " + spec.id())),
                        spec.kind() == ViewSpec.WidgetKind.ICON_ONLY_BUTTON,
                        input -> dispatchNativeWidget(spec.id(), input.hasShiftDown()));
                iconButton.active = spec.enabled();
                spec.hint().ifPresent(hint -> iconButton.setTooltip(
                        Tooltip.create(MinecraftClientComponents.resolve(hint))));
                if (spec.hint().isEmpty()) {
                    iconButton.setTooltip(
                            Tooltip.create(MinecraftClientComponents.resolve(spec.label())));
                }
                widget = iconButton;
            } else if (spec.kind() == ViewSpec.WidgetKind.INFO_BUTTON) {
                InfoButtonWidget infoButton = new InfoButtonWidget(
                        font,
                        bounds,
                        MinecraftClientComponents.resolve(spec.label()),
                        () -> dispatchNativeWidget(spec.id(), false));
                infoButton.active = spec.enabled();
                infoButton.setTooltip(Tooltip.create(MinecraftClientComponents.resolve(
                        spec.hint().orElse(spec.label()))));
                widget = infoButton;
            } else if (spec.kind() == ViewSpec.WidgetKind.COMPATIBILITY_INDICATOR) {
                widget = new CompatibilityIndicatorWidget(
                        bounds,
                        MinecraftClientComponents.resolve(spec.label()),
                        spec.icon().orElseThrow(() ->
                                new IllegalArgumentException("Missing icon for " + spec.id())));
                widget.active = spec.enabled();
                widget.setTooltip(Tooltip.create(MinecraftClientComponents.resolve(
                        spec.hint().orElse(spec.label()))));
            } else {
                if (spec.kind() != ViewSpec.WidgetKind.BUTTON) {
                    throw new IllegalArgumentException("Unsupported widget kind: " + spec.kind());
                }
                if (!spec.visible()) {
                    widget = new TransparentButtonWidget(
                            bounds,
                            MinecraftClientComponents.resolve(spec.label()),
                            input -> dispatchNativeWidget(spec.id(), input.hasShiftDown()));
                    widget.active = spec.enabled();
                } else {
                    Button button = Button.builder(
                                    MinecraftClientComponents.resolve(spec.label()),
                                    ignored -> dispatchNativeWidget(spec.id(), dispatchShiftDown))
                            .bounds(bounds.x(), bounds.y(), bounds.width(), bounds.height())
                            .build();
                    button.active = spec.enabled();
                    spec.hint().ifPresent(hint -> button.setTooltip(
                            Tooltip.create(MinecraftClientComponents.resolve(hint))));
                    widget = button;
                }
            }
            nativeWidgets.put(spec.id(), widget);
            addNclRenderableWidget(widget);
        }
    }

    private static Identifier iconTexture(GuiIcon icon) {
        return Identifier.fromNamespaceAndPath(
                "nclskins", icon.resourcePath());
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
            int selectedIndex = -1;
            for (int index = 0; index < group.tabs().size(); index++) {
                ViewSpec.Tab spec = group.tabs().get(index);
                Tab tab = new GridLayoutTab(MinecraftClientComponents.resolve(spec.label()));
                idsByTab.put(tab, spec.id());
                tabs.add(tab);
                if (spec.selected()) {
                    selectedIndex = index;
                }
            }
            TabNavigationBar bar = ExtractionGuiApi.buildTabBar(manager, group.bounds().width(), tabs);
            ExtractionGuiApi.arrange(bar, group.bounds().width());
            for (int index = 0; index < group.tabs().size(); index++) {
                bar.setTabActiveState(index, group.tabs().get(index).enabled());
            }

            NativeTabGroup nativeGroup = new NativeTabGroup(manager, bar, List.copyOf(tabs));
            nativeTabGroups.put(group.id(), nativeGroup);
            addNclTabBar(bar);
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
                widget.setMessage(MinecraftClientComponents.resolve(spec.label()));
                iconButton.setIcon(spec.icon().orElseThrow(() ->
                        new IllegalArgumentException("Missing icon for " + spec.id())));
                widget.setTooltip(Tooltip.create(MinecraftClientComponents.resolve(
                        spec.hint().orElse(spec.label()))));
            }
            if (widget instanceof CompatibilityIndicatorWidget indicator) {
                widget.setMessage(MinecraftClientComponents.resolve(spec.label()));
                indicator.setIcon(spec.icon().orElseThrow(() ->
                        new IllegalArgumentException("Missing icon for " + spec.id())));
                widget.setTooltip(Tooltip.create(MinecraftClientComponents.resolve(
                        spec.hint().orElse(spec.label()))));
            }
            if (widget instanceof InfoButtonWidget) {
                widget.setMessage(MinecraftClientComponents.resolve(spec.label()));
                widget.setTooltip(Tooltip.create(MinecraftClientComponents.resolve(
                        spec.hint().orElse(spec.label()))));
            }
            if (widget instanceof CapeCardWidget capeCard) {
                capeCard.setSelected(CatalogCardStyle.selectionSelected(spec));
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
            ExtractionGuiApi.select(group.manager(), group.tabs().get(selectedIndex));
        } finally {
            syncingTabSelection = false;
        }
    }

    private void applyFocusRequest(ViewSpec view) {
        if (nativeDispatchDepth > 0) {
            return;
        }
        Optional<ViewSpec.FocusRequest> pending = focusRequests.pending(view);
        if (pending.isEmpty()) {
            return;
        }
        ViewSpec.FocusRequest request = pending.orElseThrow();
        String previouslyFocused = currentFocusedWidgetId();
        if (focusWidget(request.widgetId())) {
            selectAllTextField(
                    view, request.widgetId(), previouslyFocused, InteractionOrigin.PROGRAMMATIC);
            focusRequests.acknowledge(view.screenId(), request);
            runtime.acknowledgeFocusApplied(view.screenId(), request);
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
        if (!runtime.closed()) {
            currentView = runtime.view(width, height, mouseX, mouseY);
        }
        super.extractBackground(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    protected void extractMenuBackground(GuiGraphicsExtractor graphics) {
        if (!isAddSourceView()) {
            super.extractMenuBackground(graphics);
            return;
        }
        drawCreateWorldTabBackground(graphics);
        extractMenuBackground(graphics, 0, 24, width, height);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {


        if (runtime.closed()) {
            return;
        }
        lastMouseX = mouseX;
        lastMouseY = mouseY;
        ViewSpec initialView = runtime.view(width, height, mouseX, mouseY);
        scrollController.synchronize(initialView.scrollSurfaces().stream().findFirst());
        scrollController.extractRenderState(graphics, mouseX, mouseY, partialTick);
        applyFocusRequest(initialView);
        publishNativeScroll(initialView);
        ViewSpec view = runtime.view(width, height, mouseX, mouseY);
        currentView = view;
        syncPreviewAssets(view);

        boolean editor = "preset_editor".equals(view.screenId());
        if (editor) {
            drawPreviews(graphics, view);
            graphics.nextStratum();
            drawListPanels(graphics, view);
            drawCardBackgrounds(graphics, view, mouseX, mouseY);
            graphics.nextStratum();
            drawBackEquipmentPreviews(graphics, view);
        } else {
            drawListPanels(graphics, view);
            if (drawCardBackgrounds(graphics, view, mouseX, mouseY)) {
                graphics.nextStratum();
            }
            drawPreviews(graphics, view);
            drawBackEquipmentPreviews(graphics, view);
        }


        graphics.nextStratum();
        drawFrameBackgrounds(graphics, view);
        drawFrameSeparators(graphics, view);
        drawScrollbar(graphics, view, mouseX, mouseY);
        graphics.nextStratum();
        extractRenderablesClipped(graphics, view, mouseX, mouseY, partialTick);
        drawProgressDecorations(graphics, view);
        drawIconDecorations(graphics, view, mouseX, mouseY);
        drawTexts(graphics, view, mouseX, mouseY);
        drawPreciseTooltip(graphics, view, mouseX, mouseY);
        runtime.acknowledgeViewRendered(view);
    }

    private void drawListPanels(GuiGraphicsExtractor graphics, ViewSpec view) {
        for (ViewSpec.Panel panel : view.panels()) {
            if (panel.style() == ViewSpec.Panel.Style.VANILLA_LIST) {
                drawClipped(graphics, view, panel.id(), () ->
                        drawVanillaListPanel(graphics, view, panel));
            }
        }
    }

    private void drawProgressDecorations(GuiGraphicsExtractor graphics, ViewSpec view) {
        for (ViewSpec.ProgressDecoration decoration : view.progressDecorations()) {
            ViewSpec.Widget owner = view.widget(decoration.ownerWidgetId()).orElseThrow();
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
            drawClipped(graphics, view, owner.id(), () -> graphics.fill(
                    left, top, left + progressWidth, bottom, decoration.color()));
        }
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
            int size = decoration.icon().baseCanvas();
            drawClipped(graphics, view, decoration.id(), () -> graphics.blit(
                    RenderPipelines.GUI_TEXTURED,
                    iconTexture(decoration.icon()),
                    bounds.x() + (bounds.width() - size) / 2,
                    bounds.y() + (bounds.height() - size) / 2,
                    0.0F,
                    0.0F,
                    size,
                    size,
                    size,
                    size,
                    color));
        }
    }

    private static boolean ownsDecoration(ViewSpec view, String widgetId) {
        return view.iconDecorations().stream()
                .anyMatch(decoration -> decoration.ownerWidgetId().equals(widgetId));
    }

    private boolean drawCardBackgrounds(
            GuiGraphicsExtractor graphics, ViewSpec view, int mouseX, int mouseY) {
        boolean rendered = false;
        for (ViewSpec.Widget widget : view.widgets()) {
            if (!CatalogCardStyle.backgroundBehindContent(widget.kind())) {
                continue;
            }
            boolean hovered = widget.bounds().contains(mouseX, mouseY)
                    && pointerInsideClip(view, widget.id(), mouseX, mouseY);
            boolean focused = Optional.ofNullable(nativeWidgets.get(widget.id()))
                    .map(AbstractWidget::isFocused)
                    .orElse(false);
            int color = CatalogCardStyle.backgroundBehindContentColor(
                    widget, hovered || focused);
            if (color == CatalogCardStyle.TRANSPARENT_BACKGROUND_COLOR) {
                continue;
            }
            Bounds bounds = widget.bounds();
            drawClipped(graphics, view, widget.id(), () -> graphics.fill(
                    bounds.x(), bounds.y(), bounds.right(), bounds.bottom(), color));
            rendered = true;
        }
        return rendered;
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
        for (Renderable renderable : orderedRenderables) {
            String widgetId = nativeIds.get(renderable);
            if (widgetId == null) {
                renderable.extractRenderState(graphics, mouseX, mouseY, partialTick);
                continue;
            }
            boolean receivesPointer = pointerOwner.map(widgetId::equals).orElse(true)
                    || ViewHostPolicy.compositeCardHovered(
                            view, widgetId, mouseX, mouseY);
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

    private <T extends AbstractWidget> T addNclRenderableWidget(T widget) {
        orderedRenderables.add(widget);
        return addRenderableWidget(widget);
    }

    private void addNclTabBar(TabNavigationBar bar) {
        orderedRenderables.add(bar);
        addRenderableWidget(bar);
    }

    private void drawPreviews(GuiGraphicsExtractor graphics, ViewSpec view) {
        Set<String> visibleIds = new HashSet<>();
        for (ViewSpec.Preview preview : view.previews()) {
            visibleIds.add(preview.id());
            TextureHandle skin;
            SkinModel model;
            if (preview.requiresLoadedSkin()) {
                Optional<TextureHandle> loadedSkin = Optional.ofNullable(previewSkinKeys.get(preview.id()))
                        .flatMap(skinTextures::handle);
                if (loadedSkin.isEmpty()) {
                    continue;
                }
                skin = loadedSkin.orElseThrow();
                model = preview.variant() == SkinVariant.SLIM ? SkinModel.SLIM : SkinModel.CLASSIC;
            } else {
                PlayerAppearance current = runtime.currentPlayerAppearance()
                        .orElseThrow(() -> new IllegalStateException(
                                "Current-player appearance capability is unavailable"));
                skin = current.skin();
                model = current.model();
            }
            Optional<TextureHandle> cape = preview.capeId().flatMap(capeTextures::handle);
            PreviewRenderer.CapeMode capeMode = cape.isPresent()
                    ? preview.capeMode()
                    : PreviewRenderer.CapeMode.OFF;
            PreviewRenderer.PreviewAppearance appearance = new PreviewRenderer.PreviewAppearance(
                    skin,
                    model,
                    cape,
                    capeMode,
                    preview.outerLayerVisibility());
            Bounds bounds = preview.anchorBounds();
            Bounds stage = preview.bounds();
            PreviewRenderer.PreviewRequest request = new PreviewRenderer.PreviewRequest(
                    appearance,
                    bounds.x(),
                    bounds.y(),
                    bounds.width(),
                    bounds.height(),
                    preview.yawDegrees(),
                    preview.pitchDegrees(),
                    preview.scale(),
                    preview.intent(),
                    stage.x(),
                    stage.y(),
                    stage.width(),
                    stage.height());
            if ("preset_editor".equals(view.screenId())) {
                drawClipped(graphics, view, preview.id(), () -> editorRenderer.render(graphics, request));
            } else {
                drawClipped(graphics, view, preview.id(), () -> galleryRenderers
                        .computeIfAbsent(preview.id(), ignored -> new AvatarPipPreviewRenderer())
                        .render(graphics, request));
            }
        }
        closeMissingRenderers(galleryRenderers, visibleIds);
    }

    private void drawVanillaListPanel(
            GuiGraphicsExtractor graphics, ViewSpec view, ViewSpec.Panel panel) {
        Bounds bounds = panel.bounds();
        if (bounds.width() <= 0 || bounds.height() <= 0) {
            return;
        }
        Identifier background = minecraft.level == null
                ? MENU_LIST_BACKGROUND
                : INWORLD_MENU_LIST_BACKGROUND;
        VanillaListSurface.Sample sample = VanillaListSurface.sample(view, panel);
        graphics.blit(
                RenderPipelines.GUI_TEXTURED,
                background,
                bounds.x(),
                bounds.y(),
                sample.u(),
                sample.v(),
                bounds.width(),
                bounds.height(),
                32,
                32);
        Identifier top = minecraft.level == null ? HEADER_SEPARATOR : INWORLD_HEADER_SEPARATOR;
        Identifier bottom = minecraft.level == null ? FOOTER_SEPARATOR : INWORLD_FOOTER_SEPARATOR;
        VanillaListSurface.Boundaries boundaries = VanillaListSurface.boundaries(bounds);
        graphics.blit(
                RenderPipelines.GUI_TEXTURED,
                top,
                bounds.x(), boundaries.topY(), 0.0F, 0.0F, bounds.width(), 2, 32, 2);
        graphics.blit(
                RenderPipelines.GUI_TEXTURED,
                bottom,
                bounds.x(), boundaries.bottomY(),
                0.0F, 0.0F, bounds.width(), 2, 32, 2);
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
        if ("add_source".equals(view.screenId())) {
            graphics.blit(
                    RenderPipelines.GUI_TEXTURED,
                    footer,
                    0,
                    height - 33,
                    0.0F,
                    0.0F,
                    width,
                    2,
                    32,
                    2);
        }
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

    private boolean isAddSourceView() {
        return currentView != null && "add_source".equals(currentView.screenId());
    }

    private void drawCreateWorldTabBackground(GuiGraphicsExtractor graphics) {
        graphics.blit(
                RenderPipelines.GUI_TEXTURED,
                TAB_HEADER_BACKGROUND,
                0,
                0,
                0.0F,
                0.0F,
                width,
                24,
                16,
                16);
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

    private void drawTexts(
            GuiGraphicsExtractor graphics, ViewSpec view, int mouseX, int mouseY) {
        for (ViewSpec.Text text : view.texts()) {
            drawClipped(graphics, view, text.id(), () ->
                    drawText(graphics, view, text, mouseX, mouseY));
        }
    }

    private void drawText(
            GuiGraphicsExtractor graphics,
            ViewSpec view,
            ViewSpec.Text text,
            int mouseX,
            int mouseY) {
            Component component = MinecraftClientComponents.resolve(text.message());
            int color = textColor(text);
            if (text.layout() == ViewSpec.Text.Layout.WRAP) {
                graphics.enableScissor(
                        text.bounds().x(),
                        text.bounds().y(),
                        text.bounds().right(),
                        text.bounds().bottom());
                try {
                    int lineY = text.bounds().y();
                    for (var line : font.split(component, text.bounds().width())) {
                        if (lineY + font.lineHeight > text.bounds().bottom()) {
                            break;
                        }
                        int lineWidth = font.width(line);
                        int lineX = switch (text.alignment()) {
                            case LEFT -> text.bounds().x();
                            case CENTER -> text.bounds().x()
                                    + Math.max(0, (text.bounds().width() - lineWidth) / 2);
                            case RIGHT -> text.bounds().right() - lineWidth;
                        };
                        graphics.text(font, line, lineX, lineY, color);
                        lineY += font.lineHeight;
                    }
                } finally {
                    graphics.disableScissor();
                }
                return;
            }
            if (font.width(component) > text.bounds().width()
                    && marqueeActive(view, text, mouseX, mouseY)) {
                int offset = MarqueeText.offset(
                        font.width(component),
                        text.bounds().width(),
                        System.currentTimeMillis());
                graphics.enableScissor(
                        text.bounds().x(),
                        text.bounds().y(),
                        text.bounds().right(),
                        text.bounds().bottom());
                try {
                    graphics.text(
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
                graphics.centeredText(font, visible, x, text.bounds().y(), color);
            } else if (text.alignment() == ViewSpec.Text.Alignment.RIGHT) {
                graphics.text(font, visible, x - font.width(visible), text.bounds().y(), color);
            } else {
                graphics.text(font, visible, x, text.bounds().y(), color);
            }
    }

    private void drawPreciseTooltip(
            GuiGraphicsExtractor graphics, ViewSpec view, int mouseX, int mouseY) {
        for (ViewSpec.TooltipRegion region : view.tooltipRegions()) {
            Bounds hit = region.hitBounds(
                    font.width(MinecraftClientComponents.resolve(region.text())));
            if (!hit.contains(mouseX, mouseY)
                    || view.clipFor(region.id())
                            .filter(clip -> !clip.contains(mouseX, mouseY))
                            .isPresent()) {
                continue;
            }
            graphics.setTooltipForNextFrame(
                    tooltipLines(MinecraftClientComponents.resolve(region.tooltip())),
                    mouseX,
                    mouseY);
            return;
        }
    }

    private static List<net.minecraft.util.FormattedCharSequence> tooltipLines(
            Component tooltip) {
        return tooltip.getString().lines()
                .map(Component::literal)
                .map(Component::getVisualOrderText)
                .toList();
    }

    private boolean marqueeActive(
            ViewSpec view, ViewSpec.Text text, int mouseX, int mouseY) {
        return MarqueeRouting.active(
                view,
                text,
                mouseX,
                mouseY,
                id -> Optional.ofNullable(nativeWidgets.get(id))
                        .map(AbstractWidget::isFocused)
                        .orElse(false));
    }

    private void drawBackEquipmentPreviews(GuiGraphicsExtractor graphics, ViewSpec view) {
        Set<String> visibleIds = new HashSet<>();
        for (ViewSpec.BackEquipmentPreview backEquipment : view.backEquipmentPreviews()) {
            visibleIds.add(backEquipment.id());
            Optional<TextureHandle> loaded = capeTextures.handle(backEquipment.capeId());
            if (loaded.isEmpty()) {
                continue;
            }
            Bounds bounds = backEquipment.bounds();
            drawClipped(graphics, view, backEquipment.id(), () -> backEquipmentRenderers
                    .computeIfAbsent(
                            backEquipment.id(), ignored -> new AvatarPipPreviewRenderer())
                    .render(
                            graphics,
                            new BackEquipmentPreviewRenderer.Request(
                                    loaded.orElseThrow(),
                                    backEquipment.mode(),
                                    bounds.x(),
                                    bounds.y(),
                                    bounds.width(),
                                    bounds.height())));
        }
        closeMissingRenderers(backEquipmentRenderers, visibleIds);
    }

    private static void closeMissingRenderers(
            Map<String, AvatarPipPreviewRenderer> renderers, Set<String> visibleIds) {
        var iterator = renderers.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, AvatarPipPreviewRenderer> entry = iterator.next();
            if (!visibleIds.contains(entry.getKey())) {
                entry.getValue().close();
                iterator.remove();
            }
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
        return ViewHostPolicy.pointerInsideClip(view, elementId, mouseX, mouseY);
    }

    private static Optional<ViewSpec.Widget> pointerOwnerAt(
            ViewSpec view, double mouseX, double mouseY) {
        return ViewHostPolicy.pointerOwnerAt(view, mouseX, mouseY);
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
        nativeDispatchDepth++;
        try {
        ViewSpec view = runtime.view(width, height, lastMouseX, lastMouseY);
        currentView = view;
        String focusedBefore = currentFocusedWidgetId();
        InteractionOrigin priorOrigin = dispatchOrigin;
        dispatchOrigin = InteractionOrigin.KEYBOARD;
        try {
            if (isEnterKey(event.shortcutKey()) && dispatchFocusedSubmit(view)) {
                return true;
            }
            for (NativeTabGroup group : nativeTabGroups.values()) {
                if (group.bar().keyPressed(event)) {
                    dispatchPendingTabSelection();
                    selectAllTextField(
                            currentView,
                            currentFocusedWidgetId(),
                            focusedBefore,
                            InteractionOrigin.KEYBOARD);
                    return true;
                }
            }
            Optional<ViewSpec.NavigationCommand> navigation = navigationCommand(event);
            if (navigation.isPresent()
                    && runtime.dispatchNavigation(
                            navigation.orElseThrow(), currentFocusedWidgetId())) {
                ViewSpec navigated = runtime.view(width, height, lastMouseX, lastMouseY);
                currentView = navigated;
                scrollController.synchronize(
                        navigated.scrollSurfaces().stream().findFirst());
                return true;
            }
            boolean priorShift = dispatchShiftDown;
            dispatchShiftDown = event.hasShiftDown();
            boolean consumed;
            try {
                consumed = super.keyPressed(event);
            } finally {
                dispatchShiftDown = priorShift;
            }
            dispatchPendingTabSelection();
            selectAllTextField(
                    currentView,
                    currentFocusedWidgetId(),
                    focusedBefore,
                    InteractionOrigin.KEYBOARD);
            return consumed;
        } finally {
            dispatchOrigin = priorOrigin;
        }
        } finally {
            finishNativeDispatch();
        }
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (runtime.closed()) {
            return false;
        }
        nativeDispatchDepth++;
        try {
        ViewSpec view = runtime.view(width, height, (int) event.x(), (int) event.y());
        currentView = view;
        String focusedBefore = currentFocusedWidgetId();
        Optional<ViewSpec.Widget> pointerOwner = pointerOwnerAt(view, event.x(), event.y());
        Optional<ViewSpec.Widget> priorityAction = pointerOwner.filter(widget ->
                widget.kind() == ViewSpec.WidgetKind.INFO_BUTTON
                        || widget.kind() == ViewSpec.WidgetKind.CATALOG_DELETE);
        if (priorityAction.isPresent()) {
            ViewSpec.Widget action = priorityAction.orElseThrow();
            if (event.button() == NATIVE_LEFT_MOUSE_BUTTON && action.enabled()) {
                runtime.dispatchWidget(
                        action.id(), event.hasShiftDown(), InteractionOrigin.POINTER);
            }
            return true;
        }
        if (event.button() == NATIVE_LEFT_MOUSE_BUTTON && pointerOwner.isEmpty()) {
            for (ViewSpec.Widget widget : view.widgets()) {
                if (!widget.visible()
                        && widget.enabled()
                        && widget.bounds().contains(event.x(), event.y())
                        && pointerInsideClip(view, widget.id(), event.x(), event.y())) {
                    runtime.dispatchWidget(widget.id(), false, InteractionOrigin.POINTER);
                    return true;
                }
            }
        }
        List<MaskedNativeWidget> maskedWidgets =
                maskWidgetsOutsideClip(view, event.x(), event.y());
        boolean nativeConsumed;
        boolean priorShift = dispatchShiftDown;
        InteractionOrigin priorOrigin = dispatchOrigin;
        dispatchShiftDown = event.hasShiftDown();
        dispatchOrigin = InteractionOrigin.POINTER;
        try {
            nativeConsumed = super.mouseClicked(event, doubleClick);
        } finally {
            dispatchShiftDown = priorShift;
            dispatchOrigin = priorOrigin;
            ViewSpec latestView = runtime.closed()
                    ? view
                    : runtime.view(width, height, (int) event.x(), (int) event.y());
            currentView = latestView;
            restoreMaskedWidgets(maskedWidgets, latestView);
        }
        dispatchPendingTabSelection();
        selectAllTextField(
                currentView,
                currentFocusedWidgetId(),
                focusedBefore,
                InteractionOrigin.POINTER);
        if (nativeConsumed) {
            return true;
        }
        if (pointerOwner.isPresent()) {


            return true;
        }
        if (event.button() == NATIVE_LEFT_MOUSE_BUTTON && capturesPointer(view, event.x(), event.y())) {
            pointerCaptured = true;
            runtime.pointerPressed(event.x(), event.y(), PRODUCT_PRIMARY_POINTER_BUTTON);
            return true;
        }
        return false;
        } finally {
            finishNativeDispatch();
        }
    }

    private void finishNativeDispatch() {
        nativeDispatchDepth--;
        if (nativeDispatchDepth < 0) {
            nativeDispatchDepth = 0;
            throw new IllegalStateException("native dispatch depth underflow");
        }
        if (nativeDispatchDepth == 0 && !runtime.closed()) {
            ViewSpec latest = runtime.view(width, height, lastMouseX, lastMouseY);
            currentView = latest;
            applyFocusRequest(latest);
        }
    }

    private void dispatchPendingTabSelection() {
        String selectedId = pendingTabSelection;
        pendingTabSelection = null;
        if (selectedId != null) {
            dispatchNativeWidget(selectedId, false);
        }
    }

    private boolean dispatchFocusedSubmit(ViewSpec view) {
        String focusedId = currentFocusedWidgetId();
        AbstractWidget nativeSource = nativeWidgets.get(focusedId);
        if (!(nativeSource instanceof EditBox editBox)) {
            return false;
        }
        Optional<String> actionId = ViewHostPolicy.submitAction(
                view, focusedId, editBox.isFocused(), editBox.getValue());
        if (actionId.isEmpty()) return false;
        runtime.dispatchWidget(actionId.orElseThrow(), false, InteractionOrigin.KEYBOARD);
        return true;
    }

    private void dispatchNativeWidget(String widgetId, boolean reverse) {
        runtime.dispatchWidget(widgetId, reverse, dispatchOrigin);
    }

    private void selectAllTextField(
            ViewSpec view,
            String widgetId,
            String previouslyFocused,
            InteractionOrigin origin) {
        AbstractWidget nativeSource = nativeWidgets.get(widgetId);
        if (!(nativeSource instanceof EditBox editBox)
                || !ViewHostPolicy.shouldSelectAllOnFocusAcquire(
                        view,
                        widgetId,
                        origin == InteractionOrigin.POINTER
                                ? ViewHostPolicy.FocusCause.POINTER
                                : origin == InteractionOrigin.KEYBOARD
                                        ? ViewHostPolicy.FocusCause.KEYBOARD
                                        : ViewHostPolicy.FocusCause.PROGRAMMATIC,
                        widgetId.equals(previouslyFocused),
                        editBox.isFocused(),
                        editBox.getValue())) {
            return;
        }
        editBox.setCursorPosition(editBox.getValue().length());
        editBox.setHighlightPos(0);
    }

    private static boolean isEnterKey(int keyCode) {
        return keyCode == InputConstants.KEYCODE_RETURN || keyCode == InputConstants.KEYCODE_NUMPADENTER;
    }

    private Optional<ViewSpec.NavigationCommand> navigationCommand(KeyEvent event) {
        return switch (event.shortcutKey()) {
            case InputConstants.KEYCODE_TAB -> Optional.of(event.hasShiftDown()
                    ? ViewSpec.NavigationCommand.TAB_BACKWARD
                    : ViewSpec.NavigationCommand.TAB_FORWARD);
            case InputConstants.KEYCODE_LEFT -> Optional.of(ViewSpec.NavigationCommand.LEFT);
            case InputConstants.KEYCODE_RIGHT -> Optional.of(ViewSpec.NavigationCommand.RIGHT);
            case InputConstants.KEYCODE_UP -> Optional.of(ViewSpec.NavigationCommand.UP);
            case InputConstants.KEYCODE_DOWN -> Optional.of(ViewSpec.NavigationCommand.DOWN);
            case InputConstants.KEYCODE_RETURN,
                    InputConstants.KEYCODE_NUMPADENTER,
                    InputConstants.KEYCODE_SPACE -> Optional.of(ViewSpec.NavigationCommand.ACTIVATE);
            default -> Optional.empty();
        };
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
        if (runtime.closed()) {
            pointerCaptured = false;
            return false;
        }
        if (pointerCaptured && event.button() == NATIVE_LEFT_MOUSE_BUTTON) {
            runtime.pointerDragged(
                    event.x(), event.y(), PRODUCT_PRIMARY_POINTER_BUTTON, dragX, dragY);
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
        if (pointerCaptured && event.button() == NATIVE_LEFT_MOUSE_BUTTON) {
            pointerCaptured = false;
            runtime.pointerReleased(event.x(), event.y(), PRODUCT_PRIMARY_POINTER_BUTTON);
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
        Optional<ViewSpec.ScrollSurface> nativeSurface = PointerRouting.scrollSurface(
                view, mouseX, mouseY);
        if (nativeSurface.isEmpty() && hit.scrollbar()) {
            nativeSurface = view.scrollSurfaces().stream().findFirst();
        }
        if (nativeSurface.isPresent()) {
            scrollController.synchronize(nativeSurface);
            boolean consumed = scrollController.forwardScroll(
                    mouseX, mouseY, horizontalAmount, verticalAmount);
            publishNativeScroll(view);
            return consumed;
        }
        if (hit.preview("editor.preview")) {
            runtime.pointerScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
    }

    private void publishNativeScroll(ViewSpec view) {
        Optional<String> surfaceId = scrollController.surfaceId();
        if (surfaceId.isEmpty()) {
            return;
        }
        view.scrollSurface(surfaceId.orElseThrow()).ifPresent(surface -> {
            double offset = scrollController.offsetPixels();
            if (Math.abs(offset - surface.offsetPixels()) > 0.001) {
                runtime.nativeScrollPositionChanged(surface.id(), offset);
                scrollController.acceptedRuntimeOffset(offset);
            }
        });
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
                    preview.externalImage(),
                    preview.variant());
            previewSkinKeys.put(preview.id(), key);
            if (preview.requiresLoadedSkin()) {
                desiredSkins.add(key);
                ensureSkin(preview, key, skinTextures);
            }
            preview.capeId().ifPresent(capeId -> {
                desiredCapes.add(capeId);
                ensureCape(preview, capeId);
            });
        }
        for (ViewSpec.BackEquipmentPreview backEquipment : view.backEquipmentPreviews()) {
            desiredCapes.add(backEquipment.capeId());
            capeTextures.request(
                    backEquipment.capeId(),
                    () -> runtime.loadCapePreview(backEquipment.capeId()),
                    () -> {});
        }
        skinTextures.retain(desiredSkins);
        capeTextures.retain(desiredCapes);
    }

    private void ensureSkin(
            ViewSpec.Preview preview,
            SkinKey key,
            PreviewAssetCache<SkinKey> cache) {
        cache.request(
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
        focusRequests.reset();
        IdentifierTextureRegistry registry = textureRegistry;
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
                        galleryRenderers.values().forEach(AvatarPipPreviewRenderer::close);
                        galleryRenderers.clear();
                        backEquipmentRenderers.values()
                                .forEach(AvatarPipPreviewRenderer::close);
                        backEquipmentRenderers.clear();
                        if (editorRenderer != null) {
                            editorRenderer.close();
                            editorRenderer = null;
                        }
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
        runtime.escapePressed();
        if (runtime.closed() && minecraft != null) {
            ExtractionGuiApi.setScreen(minecraft, parent);
        }
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

    private record NativeTabGroup(TabManager manager, TabNavigationBar bar, List<Tab> tabs) {}

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
            this(tab.id(), MinecraftClientComponents.resolveString(tab.label()));
        }
    }

    private record WidgetSignature(
            String id,
            ViewSpec.WidgetKind kind,
            String label,
            Optional<GuiIcon> icon,
            Optional<UiMessage> hint,
            boolean visible,
            int maxLength) {
        private WidgetSignature(ViewSpec.Widget widget) {


            this(
                    widget.id(),
                    widget.kind(),
                    (widget.kind() == ViewSpec.WidgetKind.ICON_BUTTON
                            || widget.kind() == ViewSpec.WidgetKind.ICON_ONLY_BUTTON)
                            ? ""
                            : MinecraftClientComponents.resolveString(widget.label()),
                    (widget.kind() == ViewSpec.WidgetKind.ICON_BUTTON
                            || widget.kind() == ViewSpec.WidgetKind.ICON_ONLY_BUTTON)
                            ? Optional.empty()
                            : widget.icon(),
                    (widget.kind() == ViewSpec.WidgetKind.ICON_BUTTON
                            || widget.kind() == ViewSpec.WidgetKind.ICON_ONLY_BUTTON)
                            ? Optional.empty()
                            : widget.hint(),
                    widget.visible(),
                    widget.maxLength());
        }
    }


    private static final class IconButtonWidget extends AbstractButton {
        private GuiIcon icon;
        private final boolean iconOnly;
        private final Consumer<InputWithModifiers> onPress;

        private IconButtonWidget(
                Bounds bounds,
                Component message,
                GuiIcon icon,
                boolean iconOnly,
                Consumer<InputWithModifiers> onPress) {
            super(bounds.x(), bounds.y(), bounds.width(), bounds.height(), message);
            this.icon = Objects.requireNonNull(icon, "icon");
            this.iconOnly = iconOnly;
            this.onPress = Objects.requireNonNull(onPress, "onPress");
        }

        private void setIcon(GuiIcon icon) {
            this.icon = Objects.requireNonNull(icon, "icon");
        }

        @Override
        public void onPress(InputWithModifiers input) {
            onPress.accept(input);
        }

        @Override
        protected void extractContents(
                GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
            if (!iconOnly) {
                extractDefaultSprite(graphics);
            }
            extractActionIcon(graphics, getX(), getY(), getWidth(), getHeight(), icon);
            if (iconOnly && isHoveredOrFocused()) {
                extractCardFocusFrame(graphics, getX(), getY(), getWidth(), getHeight());
            }
        }

        @Override
        public void updateWidgetNarration(NarrationElementOutput output) {
            defaultButtonNarrationText(output);
        }
    }


    private static final class CompatibilityIndicatorWidget extends AbstractButton {
        private GuiIcon icon;

        private CompatibilityIndicatorWidget(Bounds bounds, Component message, GuiIcon icon) {
            super(bounds.x(), bounds.y(), bounds.width(), bounds.height(), message);
            this.icon = Objects.requireNonNull(icon, "icon");
        }

        private void setIcon(GuiIcon icon) {
            this.icon = Objects.requireNonNull(icon, "icon");
        }

        @Override
        public void onPress(InputWithModifiers input) {

        }

        @Override
        protected void extractContents(
                GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
            extractActionIcon(graphics, getX(), getY(), getWidth(), getHeight(), icon);
            if (isHoveredOrFocused()) {
                extractCardFocusFrame(graphics, getX(), getY(), getWidth(), getHeight());
            }
        }

        @Override
        public void updateWidgetNarration(NarrationElementOutput output) {
            defaultButtonNarrationText(output);
        }
    }


    private static void extractActionIcon(
            GuiGraphicsExtractor graphics,
            int x,
            int y,
            int width,
            int height,
            GuiIcon icon) {
        int size = icon.baseCanvas();
        graphics.blit(
                RenderPipelines.GUI_TEXTURED,
                iconTexture(icon),
                x + (width - size) / 2,
                y + (height - size) / 2,
                0.0F,
                0.0F,
                size,
                size,
                size,
                size);
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
            if (isFocused()) {
                extractCardFocusFrame(graphics, getX(), getY(), getWidth(), getHeight());
            }
        }

        @Override
        public void updateWidgetNarration(NarrationElementOutput output) {
            defaultButtonNarrationText(output);
        }
    }


    private static final class CapeCardWidget extends AbstractButton {
        private final Runnable onPress;
        private final boolean selectedBackgroundBehindPreview;
        private boolean selected;

        private CapeCardWidget(
                Bounds bounds,
                Component message,
                boolean selected,
                boolean selectedBackgroundBehindPreview,
                Runnable onPress) {
            super(bounds.x(), bounds.y(), bounds.width(), bounds.height(), message);
            this.selected = selected;
            this.selectedBackgroundBehindPreview = selectedBackgroundBehindPreview;
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
            if (isFocused()) {
                extractCardFocusFrame(graphics, getX(), getY(), getWidth(), getHeight());
            }
        }

        @Override
        public void updateWidgetNarration(NarrationElementOutput output) {
            defaultButtonNarrationText(output);
        }
    }

    private static void extractCardFocusFrame(
            GuiGraphicsExtractor graphics, int x, int y, int width, int height) {
        int right = x + width;
        int bottom = y + height;
        graphics.fill(x, y, right, y + 1, CatalogCardStyle.FOCUS_FRAME_SHADOW_COLOR);
        graphics.fill(x, bottom - 1, right, bottom, CatalogCardStyle.FOCUS_FRAME_SHADOW_COLOR);
        graphics.fill(x, y + 1, x + 1, bottom - 1, CatalogCardStyle.FOCUS_FRAME_SHADOW_COLOR);
        graphics.fill(right - 1, y + 1, right, bottom - 1, CatalogCardStyle.FOCUS_FRAME_SHADOW_COLOR);
        if (width > 3 && height > 3) {
            graphics.fill(x + 1, y + 1, right - 1, y + 2, CatalogCardStyle.FOCUS_FRAME_COLOR);
            graphics.fill(x + 1, bottom - 2, right - 1, bottom - 1, CatalogCardStyle.FOCUS_FRAME_COLOR);
            graphics.fill(x + 1, y + 2, x + 2, bottom - 2, CatalogCardStyle.FOCUS_FRAME_COLOR);
            graphics.fill(right - 2, y + 2, right - 1, bottom - 2, CatalogCardStyle.FOCUS_FRAME_COLOR);
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

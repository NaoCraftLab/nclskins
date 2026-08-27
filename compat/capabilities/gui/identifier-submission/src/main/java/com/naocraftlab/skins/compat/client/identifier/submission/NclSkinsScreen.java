package com.naocraftlab.skins.compat.client.identifier.submission;

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
import com.naocraftlab.skins.runtime.InfoButtonStyle;
import com.naocraftlab.skins.runtime.InteractionOrigin;
import com.naocraftlab.skins.runtime.MarqueeRouting;
import com.naocraftlab.skins.runtime.MarqueeText;
import com.naocraftlab.skins.runtime.PointerRouting;
import com.naocraftlab.skins.runtime.PreviewAssetCache;
import com.naocraftlab.skins.runtime.UiMessage;
import com.naocraftlab.skins.runtime.VanillaListSurface;
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
    private static final Set<String> APPROVED_ACTION_ICONS = Set.of(
            "edit",
            "folder",
            "plus",
            "duplicate",
            "delete",
            "collapse_all",
            "expand_all",
            "no_cape",
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
            "body_only_left_arm",
            "body_only_right_arm",
            "legs_all_on",
            "legs_all_off",
            "legs_left_off",
            "legs_right_off");
    private static final int LEFT_MOUSE_BUTTON = 0;
    private static final int TEXT_COLOR = 0xFFE8EDF6;
    private static final int MUTED_COLOR = 0xFF9BA8BC;
    private static final int ERROR_COLOR = 0xFFFF9A9A;
    private static final int ACTIVE_TEXT_COLOR = 0xFF8EE6A5;

    private final Screen parent;
    private final ClientRuntime runtime;
    private final SubmissionScrollController scrollController =
            new SubmissionScrollController();
    private final Map<String, AbstractWidget> widgets = new HashMap<>();
    private final Map<String, NativeTabGroup> nativeTabGroups = new HashMap<>();
    private final Map<String, SkinKey> previewKeys = new HashMap<>();
    private final List<Renderable> orderedRenderables = new ArrayList<>();
    private final Map<String, SimplePreviewRenderer> bakedRenderers =
            new HashMap<>();
    private final SubmissionPreviewRenderer editorRenderer;

    private IdentifierTextureRegistry textureRegistry;
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
    private final FocusRequestLedger focusRequests = new FocusRequestLedger();
    private int nativeDispatchDepth;
    private InteractionOrigin dispatchOrigin = InteractionOrigin.PROGRAMMATIC;
    private boolean updatingText;
    private int mouseX;
    private int mouseY;

    public NclSkinsScreen(Screen parent) {
        super(Component.translatable("nclskins.title"));
        this.parent = parent;
        runtime = SubmissionClientRuntime.runtime();
        editorRenderer = new SubmissionPreviewRenderer(runtime.diagnostics());
    }

    public static void initializeClientRuntime(java.nio.file.Path dataRoot) {
        SubmissionClientRuntime.initialize(dataRoot);
    }

    public static FilePicker nativeFileDialog() {
        return SubmissionClientRuntime.nativeFileDialog();
    }

    public static void warmSessionSnapshot() {
        SubmissionClientRuntime.warmup();
    }

    public static void onClientTick(Minecraft client) {
        SubmissionClientRuntime.tick(client);
    }

    public static void closeClientRuntime() {
        SubmissionClientRuntime.close();
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
            Map<String, EditBox> retainedEdits = retainedEditBoxes();
            rebuildNativeWidgets(view, retainedEdits);
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

    private Map<String, EditBox> retainedEditBoxes() {
        Map<String, EditBox> retained = new HashMap<>();
        widgets.forEach((id, widget) -> {
            if (widget instanceof EditBox edit) {
                retained.put(id, edit);
            }
        });
        return retained;
    }

    private void rebuildNativeWidgets(ViewSpec next, Map<String, EditBox> retainedEdits) {
        clearWidgets();
        orderedRenderables.clear();
        clearFocus();
        widgets.clear();
        nativeTabGroups.clear();
        addNativeTabGroups(next);
        for (ViewSpec.Widget spec : next.widgets()) {
            AbstractWidget widget = createWidget(spec, retainedEdits);
            widgets.put(spec.id(), widget);
            addNclRenderableWidget(widget);
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
                Tab tab = new GridLayoutTab(SubmissionComponents.resolve(spec.label()));
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
            addNclTabBar(bar);
            selectNativeTab(nativeGroup, selected);
        }
    }

    private AbstractWidget createWidget(ViewSpec.Widget spec, Map<String, EditBox> retainedEdits) {
        if (spec.kind() == ViewSpec.WidgetKind.TEXT_FIELD) {
            EditBox retained = retainedEdits.get(spec.id());
            if (retained != null) {
                return retained;
            }
            Bounds bounds = spec.bounds();
            EditBox edit = new EditBox(
                    font,
                    bounds.x(),
                    bounds.y(),
                    bounds.width(),
                    bounds.height(),
                    SubmissionComponents.resolve(spec.label()));
            edit.setMaxLength(spec.maxLength());
            edit.setHint(spec.hint().map(SubmissionComponents::resolve).orElse(Component.empty()));
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
                SubmissionComponents.resolve(spec.label()),
                spec.kind(),
                spec.icon(),
                spec.collectionHeaderHasTrailingInfo(),
                CatalogCardStyle.selectionSelected(spec),
                !spec.visible(),
                input -> dispatchNativeWidget(spec.id(), input.hasShiftDown()));
        spec.hint()
                .or(() -> spec.kind() == ViewSpec.WidgetKind.ICON_BUTTON
                                || spec.kind() == ViewSpec.WidgetKind.INFO_BUTTON
                        ? Optional.of(spec.label())
                        : Optional.empty())
                .map(SubmissionComponents::resolve)
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
            widget.setMessage(SubmissionComponents.resolve(spec.label()));
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
        if (nativeDispatchDepth > 0) {
            return false;
        }
        Optional<ViewSpec.FocusRequest> request = focusRequests.pending(current);
        if (request.isEmpty()) {
            return false;
        }
        ViewSpec.FocusRequest value = request.orElseThrow();
        String before = focusedWidgetId();
        boolean focused = focusWidget(value.widgetId());
        if (focused) {
            selectAllOnFocusAcquire(current, before, InteractionOrigin.PROGRAMMATIC);
            focusRequests.acknowledge(current.screenId(), value);
            runtime.acknowledgeFocusApplied(current.screenId(), value);
        }
        return focused;
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
        boolean editor = "preset_editor".equals(current.screenId());
        if (editor) {
            renderPreviews(graphics, current);
            graphics.nextStratum();
            renderListPanels(graphics, current);
            renderCardBackgrounds(graphics, current, mouseX, mouseY);
            graphics.nextStratum();
            renderBackEquipment(graphics, current);
        } else {
            renderListPanels(graphics, current);
            if (renderCardBackgrounds(graphics, current, mouseX, mouseY)) {
                graphics.nextStratum();
            }
            renderPreviews(graphics, current);
            renderBackEquipment(graphics, current);
        }
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

    private boolean renderCardBackgrounds(
            GuiGraphics graphics, ViewSpec current, int mouseX, int mouseY) {
        boolean rendered = false;
        for (ViewSpec.Widget widget : current.widgets()) {
            if (!CatalogCardStyle.backgroundBehindContent(widget.kind())) {
                continue;
            }
            boolean hovered = widget.bounds().contains(mouseX, mouseY)
                    && ViewHostPolicy.pointerInsideClip(
                            current, widget.id(), mouseX, mouseY);
            boolean focused = Optional.ofNullable(widgets.get(widget.id()))
                    .map(AbstractWidget::isFocused)
                    .orElse(false);
            int color = CatalogCardStyle.backgroundBehindContentColor(
                    widget, hovered || focused);
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
        for (Renderable renderable : orderedRenderables) {
            String widgetId = widgets.entrySet().stream()
                    .filter(entry -> entry.getValue() == renderable)
                    .map(Map.Entry::getKey)
                    .findFirst()
                    .orElse(null);
            if (widgetId == null) {
                renderable.render(graphics, mouseX, mouseY, partialTick);
                continue;
            }
            boolean receivesPointer = pointerOwner.map(widgetId::equals).orElse(true)
                    || ViewHostPolicy.compositeCardHovered(
                            current, widgetId, mouseX, mouseY);
            boolean insideClip = ViewHostPolicy.pointerInsideClip(
                    current, widgetId, mouseX, mouseY);
            int nativeMouseX = receivesPointer && insideClip ? mouseX : OFFSCREEN_MOUSE_COORDINATE;
            int nativeMouseY = receivesPointer && insideClip ? mouseY : OFFSCREEN_MOUSE_COORDINATE;
            clipped(graphics, current, widgetId, () -> renderable.render(
                    graphics, nativeMouseX, nativeMouseY, partialTick));
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
        if (!APPROVED_ACTION_ICONS.contains(icon)) {
            throw new IllegalArgumentException("Unsupported action icon: " + icon);
        }
        return Identifier.fromNamespaceAndPath(
                "nclskins", "textures/gui/icons/" + icon + ".png");
    }

    private void renderListPanels(GuiGraphics graphics, ViewSpec current) {
        Identifier background = minecraft.level == null
                ? MENU_LIST_BACKGROUND
                : INWORLD_MENU_LIST_BACKGROUND;
        Identifier top = minecraft.level == null ? HEADER_SEPARATOR : INWORLD_HEADER_SEPARATOR;
        Identifier bottom = minecraft.level == null ? FOOTER_SEPARATOR : INWORLD_FOOTER_SEPARATOR;
        for (ViewSpec.Panel panel : current.panels()) {
            if (panel.style() != ViewSpec.Panel.Style.VANILLA_LIST) {
                continue;
            }
            Bounds b = panel.bounds();
            if (b.width() <= 0 || b.height() <= 0) {
                continue;
            }
            VanillaListSurface.Sample sample = VanillaListSurface.sample(current, panel);
            VanillaListSurface.Boundaries boundaries = VanillaListSurface.boundaries(b);
            clipped(graphics, current, panel.id(), () -> {
                graphics.blit(
                        RenderPipelines.GUI_TEXTURED,
                        background,
                        b.x(), b.y(), sample.u(), sample.v(), b.width(), b.height(), 32, 32);
                blitSeparator(graphics, top, b.x(), boundaries.topY(), b.width());
                blitSeparator(graphics, bottom, b.x(), boundaries.bottomY(), b.width());
            });
        }
    }

    private void renderFramePanels(GuiGraphics graphics, ViewSpec current) {
        Identifier background = minecraft.level == null
                ? MENU_LIST_BACKGROUND
                : INWORLD_MENU_LIST_BACKGROUND;
        if ("add_source".equals(current.screenId())) {
            int footerY = Math.max(0, current.height() - 33);
            graphics.blit(
                    RenderPipelines.GUI_TEXTURED,
                    background,
                    0, footerY, 0.0F, (float) footerY,
                    current.width(), current.height() - footerY, 32, 32);
            blitSeparator(
                    graphics,
                    minecraft.level == null ? FOOTER_SEPARATOR : INWORLD_FOOTER_SEPARATOR,
                    0, footerY, current.width());
        }
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
            Bounds b = preview.anchorBounds();
            Bounds stage = preview.bounds();
            PreviewRenderer.PreviewRequest request = new PreviewRenderer.PreviewRequest(
                    appearance, b.x(), b.y(), b.width(), b.height(),
                    preview.yawDegrees(), preview.pitchDegrees(), preview.scale(), preview.intent(),
                    stage.x(), stage.y(), stage.width(), stage.height());
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
        Component component = SubmissionComponents.resolve(text.message());
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
                .filter(region -> region.hitBounds(font.width(SubmissionComponents.resolve(region.text())))
                        .contains(x, y))
                .filter(region -> current.clipFor(region.id())
                        .filter(clip -> !clip.contains(x, y))
                        .isEmpty())
                .findFirst()
                .ifPresent(region -> graphics.setTooltipForNextFrame(
                        font, SubmissionComponents.resolve(region.tooltip()), x, y));
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

    private SimplePreviewRenderer bakedRenderer(String id) {
        return bakedRenderers.computeIfAbsent(
                id, ignored -> new SimplePreviewRenderer());
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
        nativeDispatchDepth++;
        try {
        ViewSpec current = runtime.view(
                width, height, (int) event.x(), (int) event.y());
        view = current;
        Optional<ViewSpec.Widget> owner = ViewHostPolicy.pointerOwnerAt(
                current, event.x(), event.y());
        String focusedBefore = focusedWidgetId();
        if (owner.isPresent()) {
            ViewSpec.Widget spec = owner.orElseThrow();
            AbstractWidget widget = widgets.get(spec.id());
            if (widget != null
                    && ViewHostPolicy.pointerInsideClip(
                            current, spec.id(), event.x(), event.y())) {
                boolean consumed;
                InteractionOrigin previousOrigin = dispatchOrigin;
                dispatchOrigin = InteractionOrigin.POINTER;
                try {
                    consumed = widget.mouseClicked(event, doubleClick);
                } finally {
                    dispatchOrigin = previousOrigin;
                }
                if (consumed) {
                    setFocused(widget);
                    widget.setFocused(true);
                    selectAllOnFocusAcquire(current, focusedBefore, InteractionOrigin.POINTER);
                }
                return true;
            }
        }
        List<MaskedNativeWidget> masked = maskWidgetsOutsideClip(
                current, event.x(), event.y());
        boolean nativeConsumed;
        InteractionOrigin previousOrigin = dispatchOrigin;
        dispatchOrigin = InteractionOrigin.POINTER;
        try {
            nativeConsumed = super.mouseClicked(event, doubleClick);
        } finally {
            dispatchOrigin = previousOrigin;
            ViewSpec latest = runtime.closed()
                    ? current
                    : runtime.view(width, height, (int) event.x(), (int) event.y());
            view = latest;
            restoreMaskedWidgets(masked, latest);
        }
        if (nativeConsumed) {
            dispatchPendingTabSelection();
            selectAllOnFocusAcquire(view, focusedBefore, InteractionOrigin.POINTER);
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
        } finally {
            finishNativeDispatch();
        }
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
        if (runtime.closed()) {
            return false;
        }
        nativeDispatchDepth++;
        try {
        if (event.isEscape()) {
            onClose();
            return true;
        }
        String focusedBefore = focusedWidgetId();
        InteractionOrigin previousOrigin = dispatchOrigin;
        dispatchOrigin = InteractionOrigin.KEYBOARD;
        try {
            if ((event.key() == GLFW.GLFW_KEY_ENTER || event.key() == GLFW.GLFW_KEY_KP_ENTER)
                    && focusedWidgetId() != null) {
                String focused = focusedWidgetId();
                AbstractWidget widget = widgets.get(focused);
                if (widget instanceof EditBox edit) {
                    Optional<String> submit = ViewHostPolicy.submitAction(
                            view, focused, edit.isFocused(), edit.getValue());
                    if (submit.isPresent()) {
                        runtime.dispatchWidget(
                                submit.orElseThrow(), false, InteractionOrigin.KEYBOARD);
                        return true;
                    }
                }
            }
            for (NativeTabGroup group : nativeTabGroups.values()) {
                if (group.bar().keyPressed(event)) {
                    dispatchPendingTabSelection();
                    selectAllOnFocusAcquire(view, focusedBefore, InteractionOrigin.KEYBOARD);
                    return true;
                }
            }
            Optional<ViewSpec.NavigationCommand> navigation = navigationCommand(event);
            if (navigation.isPresent()
                    && runtime.dispatchNavigation(navigation.orElseThrow(), focusedWidgetId())) {
                return true;
            }
            boolean consumed = super.keyPressed(event);
            dispatchPendingTabSelection();
            selectAllOnFocusAcquire(view, focusedBefore, InteractionOrigin.KEYBOARD);
            return consumed;
        } finally {
            dispatchOrigin = previousOrigin;
        }
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
            refresh();
        }
    }

    private void dispatchPendingTabSelection() {
        String selected = pendingTabSelection;
        pendingTabSelection = null;
        if (selected != null) {
            dispatchNativeWidget(selected, false);
        }
    }

    private void dispatchNativeWidget(String widgetId, boolean reverse) {
        runtime.dispatchWidget(widgetId, reverse, dispatchOrigin);
    }

    private Optional<ViewSpec.NavigationCommand> navigationCommand(KeyEvent event) {
        return switch (event.key()) {
            case GLFW.GLFW_KEY_TAB -> Optional.of(event.hasShiftDown()
                    ? ViewSpec.NavigationCommand.TAB_BACKWARD
                    : ViewSpec.NavigationCommand.TAB_FORWARD);
            case GLFW.GLFW_KEY_LEFT -> Optional.of(ViewSpec.NavigationCommand.LEFT);
            case GLFW.GLFW_KEY_RIGHT -> Optional.of(ViewSpec.NavigationCommand.RIGHT);
            case GLFW.GLFW_KEY_UP -> Optional.of(ViewSpec.NavigationCommand.UP);
            case GLFW.GLFW_KEY_DOWN -> Optional.of(ViewSpec.NavigationCommand.DOWN);
            case GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER, GLFW.GLFW_KEY_SPACE ->
                    Optional.of(ViewSpec.NavigationCommand.ACTIVATE);
            default -> Optional.empty();
        };
    }

    private void selectAllOnFocusAcquire(
            ViewSpec current, String previouslyFocused, InteractionOrigin origin) {
        String focused = focusedWidgetId();
        if (focused == null) {
            return;
        }
        AbstractWidget widget = widgets.get(focused);
        if (!(widget instanceof EditBox edit)) {
            return;
        }
        if (ViewHostPolicy.shouldSelectAllOnFocusAcquire(
                current,
                focused,
                origin == InteractionOrigin.POINTER
                        ? ViewHostPolicy.FocusCause.POINTER
                        : origin == InteractionOrigin.KEYBOARD
                                ? ViewHostPolicy.FocusCause.KEYBOARD
                                : ViewHostPolicy.FocusCause.PROGRAMMATIC,
                focused.equals(previouslyFocused),
                edit.isFocused(),
                edit.getValue())) {
            edit.setCursorPosition(edit.getValue().length());
            edit.setHighlightPos(0);
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
        focusRequests.reset();
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
            bakedRenderers.values().forEach(SimplePreviewRenderer::close);
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
            boolean selectAllOnFocusAcquire,
            Optional<String> submitActionId) {
        private NativeWidgetSignature(ViewSpec.Widget widget) {
            this(
                    widget.id(),
                    widget.kind(),
                    SubmissionComponents.resolveString(widget.label()),
                    widget.icon(),
                    widget.hint(),
                    widget.visible(),
                    widget.maxLength(),
                    widget.selectAllOnFocusAcquire(),
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
            this(tab.id(), SubmissionComponents.resolveString(tab.label()));
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
                case CATALOG_CARD, SELECTABLE_CARD, CAPE_CARD -> {
                }
                case COLLECTION_HEADER -> renderCollectionHeader(graphics, font);
                case TEXT_FIELD -> throw new IllegalStateException("Text field uses EditBox");
            }
            if (isFocused() && CatalogCardStyle.focusFrameSupported(kind)) {
                drawCardFocusFrame(graphics, getX(), getY(), getWidth(), getHeight());
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

    private static void drawCardFocusFrame(
            GuiGraphics graphics, int x, int y, int width, int height) {
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
}

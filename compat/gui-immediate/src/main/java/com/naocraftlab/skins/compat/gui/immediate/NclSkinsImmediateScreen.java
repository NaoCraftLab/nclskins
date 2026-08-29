package com.naocraftlab.skins.compat.gui.immediate;

import com.naocraftlab.skins.client.BackEquipmentPreviewRenderer;
import com.naocraftlab.skins.client.CurrentPlayerAppearanceSource.PlayerAppearance;
import com.naocraftlab.skins.client.PreviewRenderer;
import com.naocraftlab.skins.client.SkinModel;
import com.naocraftlab.skins.client.TextureRegistry;
import com.naocraftlab.skins.client.TextureRegistry.TextureHandle;
import com.naocraftlab.skins.client.TextureRegistry.TextureKind;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.tabs.Tab;
import net.minecraft.client.gui.components.tabs.TabManager;
import net.minecraft.client.gui.components.tabs.TabNavigationBar;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.lwjgl.glfw.GLFW;


public abstract class NclSkinsImmediateScreen extends Screen {
    private static final int COLLECTION_HEADER_TRAILING_INFO_WIDTH = 14;
    private static final int OFFSCREEN_MOUSE_COORDINATE = -1_000_000;
    private static final String PRESET_EDITOR_SCREEN_ID = "preset_editor";
    private static final int TEXT_COLOR = 0xFFE8EDF6;
    private static final int MUTED_COLOR = 0xFF9BA8BC;
    private static final int ERROR_COLOR = 0xFFFF9A9A;
    private static final int ACTIVE_TEXT_COLOR = 0xFF8EE6A5;

    private final Screen parent;
    private final ImmediateScreenCapabilities capabilities;
    private final ClientRuntime runtime;
    private final TextureRegistry textures;
    private final NativeScrollController scrollController;
    private final PreviewAssetCache<PreviewAssetKey> skinTextures;
    private final PreviewAssetCache<String> capeTextures;
    private final BackEquipmentPreviewRenderer<GuiGraphics> backEquipmentRenderer;
    private final Map<String, AbstractWidget> nativeWidgets = new LinkedHashMap<>();
    private final Map<String, NativeTabGroup> nativeTabGroups = new LinkedHashMap<>();
    private final Map<String, PreviewSlot> previewSlots = new HashMap<>();

    private ClientRuntime.Subscription subscription;
    private List<WidgetShape> widgetShapes = List.of();
    private List<ViewSpec.TabGroup> tabGroupShapes = List.of();
    private final FocusRequestLedger focusRequests = new FocusRequestLedger();
    private InteractionOrigin dispatchOrigin = InteractionOrigin.PROGRAMMATIC;
    private int nativeDispatchDepth;
    private boolean initialized;
    private boolean removed;
    private boolean updatingText;


    private String pendingTabSelection;
    private int lastMouseX;
    private int lastMouseY;

    protected NclSkinsImmediateScreen(Screen parent, ImmediateScreenCapabilities capabilities) {
        super(Component.translatable("nclskins.gallery.title"));
        this.parent = parent;
        this.capabilities = Objects.requireNonNull(capabilities, "capabilities");
        this.runtime = Objects.requireNonNull(capabilities.runtime(), "runtime");
        this.textures = Objects.requireNonNull(capabilities.createTextureRegistry(), "textures");
        this.scrollController = Objects.requireNonNull(
                capabilities.createScrollController(), "scrollController");
        this.skinTextures = new PreviewAssetCache<>(
                textures, TextureKind.PLAYER_SKIN, runtime.diagnostics());
        this.capeTextures = new PreviewAssetCache<>(
                textures, TextureKind.IMAGE, runtime.diagnostics());
        this.backEquipmentRenderer = Objects.requireNonNull(
                capabilities.createBackEquipmentPreviewRenderer(), "backEquipmentPreviewRenderer");
    }

    @Override
    protected final void init() {
        initialized = true;


        widgetShapes = List.of();
        tabGroupShapes = List.of();
        nativeWidgets.clear();
        nativeTabGroups.clear();
        if (runtime.closed()) {
            return;
        }
        runtime.reopen();
        if (subscription == null) {
            subscription = runtime.subscribe(this::snapshotChanged);
        }
        synchronizeWidgets(currentView());
    }

    @Override
    public final void render(
            GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {


        if (runtime.closed()) {
            return;
        }
        lastMouseX = mouseX;
        lastMouseY = mouseY;
        ViewSpec initialView = currentView();
        scrollController.synchronize(initialView.scrollSurfaces().stream().findFirst());
        scrollController.renderFrame(graphics, mouseX, mouseY, partialTick);
        publishNativeScroll(initialView);
        ViewSpec view = currentView();
        synchronizeWidgets(view);
        synchronizePreviews(view);


        renderEpochBackground(graphics, view, mouseX, mouseY, partialTick);
        boolean editor = "preset_editor".equals(view.screenId());
        if (editor) {
            renderPreviews(graphics, view);
            if (!view.previews().isEmpty()) {
                capabilities.finishPreviewPass(graphics);
            }
        }
        for (ViewSpec.Panel panel : view.panels()) {
            if (panel.style() != ViewSpec.Panel.Style.VANILLA_LIST) {
                continue;
            }
            VanillaListSurface.Sample sample = VanillaListSurface.sample(view, panel);
            renderClipped(graphics, view, panel.id(), () -> capabilities.renderPanel(
                    graphics, panel, sample.u(), sample.v()));
        }
        renderCardBackgrounds(graphics, view, mouseX, mouseY);
        if (!editor) {
            renderPreviews(graphics, view);
        }
        renderBackEquipmentPreviews(graphics, view);
        if ((!editor && !view.previews().isEmpty()) || !view.backEquipmentPreviews().isEmpty()) {
            capabilities.finishPreviewPass(graphics);
        }


        for (ViewSpec.Panel panel : view.panels()) {
            if (panel.style() == ViewSpec.Panel.Style.VANILLA_LIST) {
                continue;
            }
            if (!shouldRenderFramePanel(view, panel)) {
                continue;
            }
            capabilities.renderPanel(graphics, panel, 0, 0);
        }
        view.scrollbar().ifPresent(scrollbar -> renderScrollbar(graphics, scrollbar));
        for (NativeTabGroup tabGroup : nativeTabGroups.values()) {
            tabGroup.navigation().render(graphics, mouseX, mouseY, partialTick);
        }
        Optional<String> pointerOwner = pointerOwnerAt(view, mouseX, mouseY).map(ViewSpec.Widget::id);
        for (Map.Entry<String, AbstractWidget> entry : nativeWidgets.entrySet()) {
            boolean receivesPointer = pointerOwner
                    .map(entry.getKey()::equals)
                    .orElse(true)
                    || ViewHostPolicy.compositeCardHovered(
                            view, entry.getKey(), mouseX, mouseY);
            boolean pointerInsideClip = pointerInsideClip(view, entry.getKey(), mouseX, mouseY);
            int widgetMouseX = receivesPointer && pointerInsideClip
                    ? mouseX
                    : OFFSCREEN_MOUSE_COORDINATE;
            int widgetMouseY = receivesPointer && pointerInsideClip
                    ? mouseY
                    : OFFSCREEN_MOUSE_COORDINATE;
            renderClipped(graphics, view, entry.getKey(), () ->
                    entry.getValue().render(graphics, widgetMouseX, widgetMouseY, partialTick));
        }
        renderProgressDecorations(graphics, view);
        renderIconDecorations(graphics, view, mouseX, mouseY);
        for (ViewSpec.Text text : view.texts()) {
            renderClipped(graphics, view, text.id(), () ->
                    renderText(graphics, view, text, mouseX, mouseY));
        }
        renderPreciseTooltip(graphics, view, mouseX, mouseY);
        runtime.acknowledgeViewRendered(view);
    }

    private void renderPreviews(GuiGraphics graphics, ViewSpec view) {
        for (ViewSpec.Preview preview : view.previews()) {
            renderClipped(
                    graphics,
                    view,
                    preview.id(),
                    preview.bounds(),
                    () -> renderPreview(graphics, preview));
        }
    }

    private void renderProgressDecorations(GuiGraphics graphics, ViewSpec view) {
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
            renderClipped(graphics, view, owner.id(), () -> graphics.fill(
                    left, top, left + progressWidth, bottom, decoration.color()));
        }
    }


    protected abstract void renderEpochBackground(
            GuiGraphics graphics, ViewSpec view, int mouseX, int mouseY, float partialTick);

    protected boolean shouldRenderFramePanel(ViewSpec view, ViewSpec.Panel panel) {
        return true;
    }

    protected Bounds resolveTextBounds(ViewSpec view, ViewSpec.Text text) {
        return text.bounds();
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (runtime.closed()) {
            return false;
        }
        InteractionOrigin previousOrigin = dispatchOrigin;
        dispatchOrigin = InteractionOrigin.KEYBOARD;
        nativeDispatchDepth++;
        try {
            ViewSpec view = currentView();
            String focusedBefore = currentFocusedWidgetId();
            if (isEnterKey(keyCode) && dispatchFocusedSubmit(view)) {
                return true;
            }
            for (NativeTabGroup tabGroup : nativeTabGroups.values()) {
                if (tabGroup.navigation().keyPressed(keyCode)) {
                    dispatchPendingTabSelection();
                    return true;
                }
            }
            Optional<ViewSpec.NavigationCommand> command = navigationCommand(keyCode, modifiers);
            if (command.isPresent()
                    && runtime.dispatchNavigation(command.orElseThrow(), focusedBefore)) {
                return true;
            }
            boolean consumed = super.keyPressed(keyCode, scanCode, modifiers);
            dispatchPendingTabSelection();
            selectAllOnFocusEdge(
                    currentView(),
                    focusedBefore,
                    currentFocusedWidgetId(),
                    ViewHostPolicy.FocusCause.KEYBOARD);
            return consumed;
        } finally {
            dispatchOrigin = previousOrigin;
            finishNativeDispatch();
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (runtime.closed()) {
            return false;
        }
        nativeDispatchDepth++;
        try {
        ViewSpec clickView = currentView();
        String focusedBefore = currentFocusedWidgetId();
        Optional<ViewSpec.Widget> pointerOwner = pointerOwnerAt(clickView, mouseX, mouseY);
        Optional<ViewSpec.Widget> priorityAction = pointerOwner.filter(widget ->
                widget.kind() == ViewSpec.WidgetKind.INFO_BUTTON
                        || widget.kind() == ViewSpec.WidgetKind.CATALOG_DELETE);
        if (priorityAction.isPresent()) {
            ViewSpec.Widget action = priorityAction.orElseThrow();
            if (button == 0 && action.enabled()) {
                runtime.dispatchWidget(
                        action.id(), hasShiftDown(), InteractionOrigin.POINTER);
            }
            return true;
        }
        List<MaskedNativeWidget> maskedWidgets = maskWidgetsOutsideClip(clickView, mouseX, mouseY);
        boolean consumed;
        InteractionOrigin previousOrigin = dispatchOrigin;
        dispatchOrigin = InteractionOrigin.POINTER;
        try {
            consumed = super.mouseClicked(mouseX, mouseY, button);
        } finally {
            dispatchOrigin = previousOrigin;
            ViewSpec latestView = runtime.closed() ? clickView : currentView();
            restoreMaskedWidgets(maskedWidgets, latestView);
        }
        dispatchPendingTabSelection();
        ViewSpec view = currentView();
        selectAllOnFocusEdge(
                view,
                focusedBefore,
                currentFocusedWidgetId(),
                ViewHostPolicy.FocusCause.POINTER);
        if (!consumed && pointerOwner.isPresent()) {


            consumed = true;
        }
        if (!consumed) {
            Optional<ViewSpec.Widget> invisibleHit = view.widgets().stream()
                    .filter(widget -> widget.kind() == ViewSpec.WidgetKind.BUTTON
                            || widget.kind() == ViewSpec.WidgetKind.ICON_BUTTON
                            || widget.kind() == ViewSpec.WidgetKind.ICON_ONLY_BUTTON
                            || widget.kind() == ViewSpec.WidgetKind.COLLECTION_HEADER)
                    .filter(widget -> !widget.visible() && widget.enabled())
                    .filter(widget -> widget.bounds().contains(mouseX, mouseY))
                    .filter(widget -> pointerInsideClip(view, widget.id(), mouseX, mouseY))
                    .findFirst();
            if (invisibleHit.isPresent()) {
                runtime.dispatchWidget(
                        invisibleHit.orElseThrow().id(),
                        hasShiftDown(),
                        InteractionOrigin.POINTER);
                consumed = true;
            }
        }
        if (!consumed) {
            runtime.pointerPressed(mouseX, mouseY, button);
            consumed = isPointerSurface(view, mouseX, mouseY);
        }
        return consumed;
        } finally {
            finishNativeDispatch();
        }
    }

    @Override
    public boolean mouseDragged(
            double mouseX,
            double mouseY,
            int button,
            double deltaX,
            double deltaY) {
        if (runtime.closed()) {
            return false;
        }
        runtime.pointerDragged(mouseX, mouseY, button, deltaX, deltaY);
        return isPointerSurface(currentView(), mouseX, mouseY)
                || super.mouseDragged(mouseX, mouseY, button, deltaX, deltaY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (runtime.closed()) {
            return false;
        }
        runtime.pointerReleased(mouseX, mouseY, button);
        return super.mouseReleased(mouseX, mouseY, button);
    }


    protected final boolean forwardScroll(
            double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (runtime.closed()) {
            return false;
        }
        ViewSpec view = currentView();
        PointerRouting.Hit hit = PointerRouting.hit(view, mouseX, mouseY);
        Optional<ViewSpec.ScrollSurface> nativeSurface = PointerRouting.scrollSurface(
                view, mouseX, mouseY);
        if (nativeSurface.isEmpty() && hit.scrollbar()) {
            nativeSurface = view.scrollSurfaces().stream().findFirst();
        }
        if (nativeSurface.isPresent()) {
            scrollController.synchronize(nativeSurface);
            boolean consumed = scrollController.mouseScrolled(
                    mouseX, mouseY, horizontalAmount, verticalAmount);
            publishNativeScroll(view);
            return consumed;
        }
        if (hit.preview("editor.preview")) {
            runtime.pointerScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
            return true;
        }
        return false;
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

    @Override
    public final void onClose() {
        if (runtime.closed()) {
            return;
        }
        runtime.escapePressed();
        if (runtime.closed() && minecraft != null) {
            minecraft.setScreen(parent);
        }
    }

    @Override
    public final void removed() {
        if (removed) {
            return;
        }
        removed = true;
        initialized = false;
        focusRequests.reset();
        try {
            runtime.closeScreen();
            if (subscription != null) {
                subscription.close();
                subscription = null;
            }
        } finally {
            try {
                releasePreviews();
            } finally {
                try {
                    textures.close();
                } finally {
                    super.removed();
                }
            }
        }
    }

    private void snapshotChanged(ClientSnapshot next) {
        if (!initialized || removed || minecraft == null || runtime.closed()) {
            return;
        }
        if (next.lifecycle() == ClientSnapshot.Lifecycle.CLOSED) {
            if (minecraft.screen == this) {
                minecraft.setScreen(parent);
            }
            return;
        }
        synchronizeWidgets(currentView());
    }

    private ViewSpec currentView() {
        return runtime.view(
                Math.max(1, width),
                Math.max(1, height),
                lastMouseX,
                lastMouseY,
                capabilities.viewChromeMetrics());
    }

    private void synchronizeWidgets(ViewSpec view) {
        List<WidgetShape> nextShapes = view.widgets().stream().map(WidgetShape::new).toList();
        List<ViewSpec.TabGroup> nextTabGroupShapes = List.copyOf(view.tabGroups());
        String focusedWidgetId = null;
        if (!nextShapes.equals(widgetShapes) || !nextTabGroupShapes.equals(tabGroupShapes)) {
            focusedWidgetId = currentFocusedWidgetId();
            Map<String, EditBox> retainedEditBoxes = new HashMap<>();
            nativeWidgets.forEach((id, widget) -> {
                if (widget instanceof EditBox editBox) {
                    retainedEditBoxes.put(id, editBox);
                }
            });
            setFocused(null);
            clearWidgets();
            nativeWidgets.clear();
            nativeTabGroups.clear();
            widgetShapes = nextShapes;
            tabGroupShapes = nextTabGroupShapes;
            for (ViewSpec.TabGroup tabGroup : view.tabGroups()) {
                NativeTabGroup nativeTabGroup = createTabGroup(tabGroup);
                if (nativeTabGroups.putIfAbsent(tabGroup.id(), nativeTabGroup) != null) {
                    throw new IllegalArgumentException("Duplicate tab group id: " + tabGroup.id());
                }
                addRenderableWidget(nativeTabGroup.navigation());
            }
            for (ViewSpec.Widget widget : view.widgets()) {
                AbstractWidget retained = widget.kind() == ViewSpec.WidgetKind.TEXT_FIELD
                        ? retainedEditBoxes.get(widget.id())
                        : null;
                AbstractWidget nativeWidget = retained != null ? retained : createWidget(widget);
                nativeWidgets.put(widget.id(), nativeWidget);
                addRenderableWidget(nativeWidget);
            }
        }
        updatingText = true;
        try {
            for (ViewSpec.Widget widget : view.widgets()) {
                AbstractWidget nativeWidget = nativeWidgets.get(widget.id());
                if (nativeWidget == null) {
                    continue;
                }
                nativeWidget.active = widget.enabled();
                nativeWidget.visible = widget.visible() || ownsDecoration(view, widget.id());
                nativeWidget.setX(widget.bounds().x());
                nativeWidget.setY(widget.bounds().y());
                nativeWidget.setWidth(widget.bounds().width());
                if (nativeWidget instanceof CapeCardWidget capeCard) {
                    capeCard.setSelected(CatalogCardStyle.selectionSelected(widget));
                }
                if (nativeWidget instanceof CollectionHeaderWidget header) {
                    header.setTrailingInfo(widget.collectionHeaderHasTrailingInfo());
                }
                nativeWidget.setMessage(resolve(widget.label()));
                if (widget.kind() == ViewSpec.WidgetKind.ICON_BUTTON
                        || widget.kind() == ViewSpec.WidgetKind.ICON_ONLY_BUTTON) {
                    ((IconButtonWidget) nativeWidget).setIcon(widget.icon().orElseThrow());
                    nativeWidget.setTooltip(Tooltip.create(resolve(
                            widget.hint().orElse(widget.label()))));
                }
                if (widget.kind() == ViewSpec.WidgetKind.INFO_BUTTON) {
                    nativeWidget.setTooltip(Tooltip.create(resolve(
                            widget.hint().orElse(widget.label()))));
                }
                if (widget.kind() == ViewSpec.WidgetKind.COMPATIBILITY_INDICATOR) {
                    ((CompatibilityIndicatorWidget) nativeWidget).setIcon(
                            widget.icon().orElseThrow());
                    nativeWidget.setTooltip(Tooltip.create(resolve(
                            widget.hint().orElse(widget.label()))));
                }
                if (nativeWidget instanceof EditBox editBox) {
                    String value = widget.value().orElse("");
                    if (!editBox.getValue().equals(value)) {
                        editBox.setValue(value);
                    }
                }
            }
        } finally {
            updatingText = false;
        }
        if (focusedWidgetId != null) {
            AbstractWidget restored = nativeWidgets.get(focusedWidgetId);
            if (restored != null && restored.visible && restored.active) {
                setFocused(restored);
            }
        }
        consumeFocusRequest(view);
    }

    private AbstractWidget createWidget(ViewSpec.Widget widget) {
        Bounds bounds = widget.bounds();
        if (widget.kind() == ViewSpec.WidgetKind.CATALOG_CARD) {
            return new CatalogCardWidget(
                    widget.id(),
                    bounds.x(),
                    bounds.y(),
                    bounds.width(),
                    bounds.height(),
                    resolve(widget.label()));
        }
        if (widget.kind() == ViewSpec.WidgetKind.CAPE_CARD
                || widget.kind() == ViewSpec.WidgetKind.SELECTABLE_CARD) {
            return new CapeCardWidget(
                    widget.id(),
                    bounds.x(),
                    bounds.y(),
                    bounds.width(),
                    bounds.height(),
                    resolve(widget.label()),
                    CatalogCardStyle.selectionSelected(widget),
                    CatalogCardStyle.selectionBackgroundBehindContent(widget.kind()));
        }
        if (widget.kind() == ViewSpec.WidgetKind.COLLECTION_HEADER) {
            return new CollectionHeaderWidget(
                    widget.id(),
                    bounds.x(),
                    bounds.y(),
                    bounds.width(),
                    bounds.height(),
                    resolve(widget.label()),
                    widget.collectionHeaderHasTrailingInfo());
        }
        if (widget.kind() == ViewSpec.WidgetKind.CATALOG_DELETE) {
            return new CatalogDeleteWidget(
                    widget.id(),
                    bounds.x(),
                    bounds.y(),
                    bounds.width(),
                    bounds.height(),
                    resolve(widget.label()));
        }
        if (widget.kind() == ViewSpec.WidgetKind.ICON_BUTTON
                || widget.kind() == ViewSpec.WidgetKind.ICON_ONLY_BUTTON) {
            IconButtonWidget button = new IconButtonWidget(
                    widget.id(),
                    bounds.x(),
                    bounds.y(),
                    bounds.width(),
                    bounds.height(),
                    resolve(widget.label()),
                    widget.icon().orElseThrow(),
                    widget.kind() == ViewSpec.WidgetKind.ICON_ONLY_BUTTON);
            widget.hint().ifPresent(hint -> button.setTooltip(Tooltip.create(resolve(hint))));
            if (widget.hint().isEmpty()) {
                button.setTooltip(Tooltip.create(resolve(widget.label())));
            }
            return button;
        }
        if (widget.kind() == ViewSpec.WidgetKind.INFO_BUTTON) {
            InfoButtonWidget button = new InfoButtonWidget(
                    widget.id(),
                    bounds.x(),
                    bounds.y(),
                    bounds.width(),
                    bounds.height(),
                    resolve(widget.label()));
            button.setTooltip(Tooltip.create(resolve(
                    widget.hint().orElse(widget.label()))));
            return button;
        }
        if (widget.kind() == ViewSpec.WidgetKind.COMPATIBILITY_INDICATOR) {
            CompatibilityIndicatorWidget button = new CompatibilityIndicatorWidget(
                    bounds.x(),
                    bounds.y(),
                    bounds.width(),
                    bounds.height(),
                    resolve(widget.label()),
                    widget.icon().orElseThrow());
            button.setTooltip(Tooltip.create(resolve(
                    widget.hint().orElse(widget.label()))));
            return button;
        }
        if (widget.kind() == ViewSpec.WidgetKind.BUTTON) {
            if (!widget.visible()) {
                return new TransparentButtonWidget(
                        widget.id(),
                        bounds.x(),
                        bounds.y(),
                        bounds.width(),
                        bounds.height(),
                        resolve(widget.label()));
            }
            Button button = Button.builder(
                            resolve(widget.label()),
                            ignored -> dispatchNativeWidget(widget.id(), hasShiftDown()))
                    .bounds(bounds.x(), bounds.y(), bounds.width(), bounds.height())
                    .build();
            widget.hint().ifPresent(hint -> button.setTooltip(Tooltip.create(resolve(hint))));
            return button;
        }
        if (widget.kind() != ViewSpec.WidgetKind.TEXT_FIELD) {
            throw new IllegalArgumentException("Unsupported widget kind: " + widget.kind());
        }
        EditBox editBox = new EditBox(
                font,
                bounds.x(),
                bounds.y(),
                bounds.width(),
                bounds.height(),
                resolve(widget.label()));
        editBox.setMaxLength(widget.maxLength());
        String initialValue = widget.value().orElse("");
        editBox.setValue(initialValue);
        widget.hint().ifPresent(hint -> editBox.setHint(resolve(hint)));
        String[] responderValue = {initialValue};
        editBox.setResponder(value -> {
            if (!value.equals(responderValue[0])) {
                responderValue[0] = value;
            } else {
                return;
            }
            if (!updatingText) {
                runtime.dispatchText(widget.id(), value);
            }
        });
        return editBox;
    }

    private static ResourceLocation iconTexture(GuiIcon icon) {
        return Objects.requireNonNull(
                ResourceLocation.tryParse("nclskins:" + icon.resourcePath()),
                "iconTexture");
    }

    private void renderIconDecorations(
            GuiGraphics graphics, ViewSpec view, int mouseX, int mouseY) {
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
            Bounds bounds = decoration.bounds();
            renderClipped(graphics, view, decoration.id(), () -> {
                graphics.setColor(1.0F, 1.0F, 1.0F, opacity);
                try {
                    int size = decoration.icon().baseCanvas();
                    graphics.blit(
                            iconTexture(decoration.icon()),
                            bounds.x() + (bounds.width() - size) / 2,
                            bounds.y() + (bounds.height() - size) / 2,
                            0.0F,
                            0.0F,
                            size,
                            size,
                            size,
                            size);
                } finally {
                    graphics.setColor(1.0F, 1.0F, 1.0F, 1.0F);
                }
            });
        }
    }

    private static boolean ownsDecoration(ViewSpec view, String widgetId) {
        return view.iconDecorations().stream()
                .anyMatch(decoration -> decoration.ownerWidgetId().equals(widgetId));
    }

    private void renderCardBackgrounds(
            GuiGraphics graphics, ViewSpec view, int mouseX, int mouseY) {
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
            renderClipped(graphics, view, widget.id(), () -> graphics.fill(
                    bounds.x(), bounds.y(), bounds.right(), bounds.bottom(), color));
        }
    }


    private final class IconButtonWidget extends AbstractButton {
        private final String widgetId;
        private final boolean iconOnly;
        private GuiIcon icon;

        private IconButtonWidget(
                String widgetId,
                int x,
                int y,
                int width,
                int height,
                Component message,
                GuiIcon icon,
                boolean iconOnly) {
            super(x, y, width, height, message);
            this.widgetId = Objects.requireNonNull(widgetId, "widgetId");
            this.icon = Objects.requireNonNull(icon, "icon");
            this.iconOnly = iconOnly;
        }

        private void setIcon(GuiIcon icon) {
            this.icon = Objects.requireNonNull(icon, "icon");
        }

        @Override
        public void onPress() {
            dispatchNativeWidget(widgetId, hasShiftDown());
        }

        @Override
        protected void renderWidget(
                GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            if (!iconOnly) {
                super.renderWidget(graphics, mouseX, mouseY, partialTick);
            }
            renderActionIcon(
                    graphics, getX(), getY(), getWidth(), getHeight(), icon, active);
            if (iconOnly && isHoveredOrFocused()) {
                drawCardFocusFrame(graphics, getX(), getY(), getWidth(), getHeight());
            }
        }

        @Override
        public void renderString(GuiGraphics graphics, Font font, int color) {

        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput output) {
            defaultButtonNarrationText(output);
        }
    }


    private final class CompatibilityIndicatorWidget extends AbstractButton {
        private GuiIcon icon;

        private CompatibilityIndicatorWidget(
                int x,
                int y,
                int width,
                int height,
                Component message,
                GuiIcon icon) {
            super(x, y, width, height, message);
            this.icon = Objects.requireNonNull(icon, "icon");
        }

        private void setIcon(GuiIcon icon) {
            this.icon = Objects.requireNonNull(icon, "icon");
        }

        @Override
        public void onPress() {

        }

        @Override
        protected void renderWidget(
                GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            renderActionIcon(
                    graphics, getX(), getY(), getWidth(), getHeight(), icon, active);
            if (isHoveredOrFocused()) {
                drawCardFocusFrame(graphics, getX(), getY(), getWidth(), getHeight());
            }
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput output) {
            defaultButtonNarrationText(output);
        }
    }


    private static void renderActionIcon(
            GuiGraphics graphics,
            int x,
            int y,
            int width,
            int height,
            GuiIcon icon,
            boolean active) {
        int size = icon.baseCanvas();
        int iconX = x + (width - size) / 2;
        int iconY = y + (height - size) / 2;
        float tint = active ? 1.0F : 0.5F;
        graphics.setColor(tint, tint, tint, 1.0F);
        try {
            graphics.blit(
                    iconTexture(icon),
                    iconX,
                    iconY,
                    0.0F,
                    0.0F,
                    size,
                    size,
                    size,
                    size);
        } finally {
            graphics.setColor(1.0F, 1.0F, 1.0F, 1.0F);
        }
    }


    private final class TransparentButtonWidget extends AbstractButton {
        private final String widgetId;

        private TransparentButtonWidget(
                String widgetId,
                int x,
                int y,
                int width,
                int height,
                Component message) {
            super(x, y, width, height, message);
            this.widgetId = Objects.requireNonNull(widgetId, "widgetId");
        }

        @Override
        public void onPress() {
            dispatchNativeWidget(widgetId, hasShiftDown());
        }

        @Override
        protected void renderWidget(
                GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {

        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput output) {
            defaultButtonNarrationText(output);
        }
    }


    private final class CatalogCardWidget extends AbstractButton {
        private final String widgetId;

        private CatalogCardWidget(
                String widgetId,
                int x,
                int y,
                int width,
                int height,
                Component message) {
            super(x, y, width, height, message);
            this.widgetId = Objects.requireNonNull(widgetId, "widgetId");
        }

        @Override
        public void onPress() {
            dispatchNativeWidget(widgetId, hasShiftDown());
        }

        @Override
        protected void renderWidget(
                GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            if (isFocused()) {
                drawCardFocusFrame(graphics, getX(), getY(), getWidth(), getHeight());
            }
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput output) {
            defaultButtonNarrationText(output);
        }
    }


    private final class CapeCardWidget extends AbstractButton {
        private final String widgetId;
        private final boolean selectedBackgroundBehindPreview;
        private boolean selected;

        private CapeCardWidget(
                String widgetId,
                int x,
                int y,
                int width,
                int height,
                Component message,
                boolean selected,
                boolean selectedBackgroundBehindPreview) {
            super(x, y, width, height, message);
            this.widgetId = Objects.requireNonNull(widgetId, "widgetId");
            this.selected = selected;
            this.selectedBackgroundBehindPreview = selectedBackgroundBehindPreview;
        }

        private void setSelected(boolean selected) {
            this.selected = selected;
        }

        @Override
        public void onPress() {
            dispatchNativeWidget(widgetId, hasShiftDown());
        }

        @Override
        protected void renderWidget(
                GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            if (isFocused()) {
                drawCardFocusFrame(graphics, getX(), getY(), getWidth(), getHeight());
            }
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput output) {
            defaultButtonNarrationText(output);
        }
    }


    private final class CatalogDeleteWidget extends AbstractButton {
        private final String widgetId;

        private CatalogDeleteWidget(
                String widgetId,
                int x,
                int y,
                int width,
                int height,
                Component message) {
            super(x, y, width, height, message);
            this.widgetId = Objects.requireNonNull(widgetId, "widgetId");
        }

        @Override
        public void onPress() {
            dispatchNativeWidget(widgetId, hasShiftDown());
        }

        @Override
        protected void renderWidget(
                GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            int background = isHoveredOrFocused() ? 0xCC7A3030 : 0x99302020;
            graphics.fill(getX(), getY(), getX() + getWidth(), getY() + getHeight(), background);
            graphics.drawCenteredString(
                    font,
                    Component.literal("×"),
                    getX() + getWidth() / 2,
                    getY() + (getHeight() - font.lineHeight) / 2,
                    active ? 0xFFFFFFFF : 0xFF777777);
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput output) {
            defaultButtonNarrationText(output);
        }
    }


    private final class InfoButtonWidget extends AbstractButton {
        private final String widgetId;

        private InfoButtonWidget(
                String widgetId,
                int x,
                int y,
                int width,
                int height,
                Component message) {
            super(x, y, width, height, message);
            this.widgetId = Objects.requireNonNull(widgetId, "widgetId");
        }

        @Override
        public void onPress() {
            dispatchNativeWidget(widgetId, hasShiftDown());
        }

        @Override
        protected void renderWidget(
                GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            graphics.drawCenteredString(
                    font,
                    Component.literal("i"),
                    getX() + getWidth() / 2,
                    getY() + Math.max(0, (getHeight() - font.lineHeight) / 2),
                    InfoButtonStyle.labelColor(active, isHoveredOrFocused()));
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput output) {
            defaultButtonNarrationText(output);
        }
    }


    private final class CollectionHeaderWidget extends AbstractButton {
        private final String widgetId;
        private boolean trailingInfo;

        private CollectionHeaderWidget(
                String widgetId,
                int x,
                int y,
                int width,
                int height,
                Component message,
                boolean trailingInfo) {
            super(x, y, width, height, message);
            this.widgetId = Objects.requireNonNull(widgetId, "widgetId");
            this.trailingInfo = trailingInfo;
        }

        private void setTrailingInfo(boolean trailingInfo) {
            this.trailingInfo = trailingInfo;
        }

        @Override
        public void onPress() {
            dispatchNativeWidget(widgetId, hasShiftDown());
        }

        @Override
        protected void renderWidget(
                GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
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
            graphics.drawString(
                    font,
                    visible,
                    geometry.textX(),
                    geometry.textY(),
                    palette.labelColor(),
                    false);

            if (geometry.hasLine()) {
                graphics.fill(
                        geometry.lineStart(),
                        geometry.lineY(),
                        geometry.lineEndExclusive(),
                        geometry.lineY() + 1,
                        palette.lineColor());
            }
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput output) {
            defaultButtonNarrationText(output);
        }
    }

    private NativeTabGroup createTabGroup(ViewSpec.TabGroup group) {
        DispatchingTabManager manager = new DispatchingTabManager();
        List<NativeTab> tabs = group.tabs().stream()
                .map(tab -> new NativeTab(tab.id(), resolve(tab.label()), tab.enabled()))
                .toList();
        TabNavigationBar navigation = TabNavigationBar.builder(manager, group.bounds().width())
                .addTabs(tabs.toArray(Tab[]::new))
                .build();
        navigation.setWidth(group.bounds().width());
        navigation.arrangeElements();

        int selected = -1;
        for (int index = 0; index < group.tabs().size(); index++) {
            ViewSpec.Tab tab = group.tabs().get(index);
            if (tab.selected()) {
                selected = index;
            }
            if (navigation.children().get(index) instanceof AbstractWidget tabButton) {
                tabButton.active = tab.enabled();
            }
        }
        if (selected < 0) {
            throw new IllegalArgumentException("Tab group has no selected tab: " + group.id());
        }
        manager.selectInitial(tabs.get(selected));
        manager.enableDispatch();
        manager.setTabArea(new ScreenRectangle(
                group.bounds().x(),
                group.bounds().bottom(),
                group.bounds().width(),
                Math.max(1, height - group.bounds().bottom())));
        return new NativeTabGroup(navigation);
    }

    private String currentFocusedWidgetId() {
        Object focused = getFocused();
        return nativeWidgets.entrySet().stream()
                .filter(entry -> entry.getValue() == focused)
                .map(Map.Entry::getKey)
                .findFirst()
                .orElse(null);
    }

    private void consumeFocusRequest(ViewSpec view) {
        if (nativeDispatchDepth > 0) {
            return;
        }
        Optional<ViewSpec.FocusRequest> request = focusRequests.pending(view);
        if (request.isEmpty()) return;
        ViewSpec.FocusRequest focusRequest = request.orElseThrow();
        AbstractWidget target = nativeWidgets.get(focusRequest.widgetId());
        if (target == null || !target.visible || !target.active) {
            return;
        }
        String focusedBefore = currentFocusedWidgetId();
        setFocused(target);
        selectAllOnFocusEdge(
                view,
                focusedBefore,
                focusRequest.widgetId(),
                ViewHostPolicy.FocusCause.PROGRAMMATIC);
        focusRequests.acknowledge(view.screenId(), focusRequest);
        runtime.acknowledgeFocusApplied(view.screenId(), focusRequest);
    }

    private void finishNativeDispatch() {
        nativeDispatchDepth--;
        if (nativeDispatchDepth < 0) {
            nativeDispatchDepth = 0;
            throw new IllegalStateException("native dispatch depth underflow");
        }
        if (nativeDispatchDepth == 0 && !runtime.closed()) {
            consumeFocusRequest(currentView());
        }
    }

    private void dispatchPendingTabSelection() {
        String tabId = pendingTabSelection;
        pendingTabSelection = null;
        if (tabId != null) {
            runtime.dispatchWidget(tabId, false, dispatchOrigin);
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
        runtime.dispatchWidget(actionId.orElseThrow(), false, dispatchOrigin);
        return true;
    }

    private void selectAllOnFocusEdge(
            ViewSpec view,
            String previouslyFocusedId,
            String focusedWidgetId,
            ViewHostPolicy.FocusCause cause) {
        if (focusedWidgetId == null) return;
        AbstractWidget nativeSource = nativeWidgets.get(focusedWidgetId);
        if (!(nativeSource instanceof EditBox editBox)
                || !ViewHostPolicy.shouldSelectAllOnFocusAcquire(
                        view,
                        focusedWidgetId,
                        cause,
                        focusedWidgetId.equals(previouslyFocusedId),
                        editBox.isFocused(),
                        editBox.getValue())) {
            return;
        }
        editBox.setCursorPosition(editBox.getValue().length());
        editBox.setHighlightPos(0);
    }

    private static boolean isEnterKey(int keyCode) {
        return keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER;
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

    private Optional<ViewSpec.NavigationCommand> navigationCommand(
            int keyCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_TAB) {
            return Optional.of((modifiers & GLFW.GLFW_MOD_SHIFT) != 0
                    ? ViewSpec.NavigationCommand.TAB_BACKWARD
                    : ViewSpec.NavigationCommand.TAB_FORWARD);
        }
        if (keyCode == GLFW.GLFW_KEY_LEFT) return Optional.of(ViewSpec.NavigationCommand.LEFT);
        if (keyCode == GLFW.GLFW_KEY_RIGHT) return Optional.of(ViewSpec.NavigationCommand.RIGHT);
        if (keyCode == GLFW.GLFW_KEY_UP) return Optional.of(ViewSpec.NavigationCommand.UP);
        if (keyCode == GLFW.GLFW_KEY_DOWN) return Optional.of(ViewSpec.NavigationCommand.DOWN);
        if (keyCode == GLFW.GLFW_KEY_SPACE || isEnterKey(keyCode)) {
            return Optional.of(ViewSpec.NavigationCommand.ACTIVATE);
        }
        return Optional.empty();
    }

    private void dispatchNativeWidget(String widgetId, boolean reverse) {
        runtime.dispatchWidget(widgetId, reverse, dispatchOrigin);
    }

    private void renderScrollbar(GuiGraphics graphics, ViewSpec.Scrollbar scrollbar) {
        if (scrollbar.orientation() == ViewSpec.Scrollbar.Orientation.HORIZONTAL) {
            capabilities.renderScrollbar(graphics, scrollbar);
            return;
        }
        Bounds track = scrollbar.track();
        Bounds thumb = scrollbar.thumb();
        graphics.fill(track.x(), track.y(), track.right(), track.bottom(), 0xFF000000);
        graphics.fill(thumb.x(), thumb.y(), thumb.right(), thumb.bottom(), 0xFF808080);
        graphics.fill(
                thumb.x(),
                thumb.y(),
                Math.max(thumb.x(), thumb.right() - 1),
                Math.max(thumb.y(), thumb.bottom() - 1),
                0xFFC0C0C0);
    }

    private void renderText(
            GuiGraphics graphics,
            ViewSpec view,
            ViewSpec.Text text,
            int mouseX,
            int mouseY) {
        Bounds bounds = resolveTextBounds(view, text);
        Component message = resolve(text.message());
        int color = textColor(text);
        if (text.layout() == ViewSpec.Text.Layout.WRAP) {
            graphics.enableScissor(bounds.x(), bounds.y(), bounds.right(), bounds.bottom());
            try {
                int lineY = bounds.y();
                for (var line : font.split(message, bounds.width())) {
                    if (lineY + font.lineHeight > bounds.bottom()) {
                        break;
                    }
                    int lineWidth = font.width(line);
                    int lineX = switch (text.alignment()) {
                        case LEFT -> bounds.x();
                        case CENTER -> bounds.x() + Math.max(0, (bounds.width() - lineWidth) / 2);
                        case RIGHT -> bounds.right() - lineWidth;
                    };
                    graphics.drawString(font, line, lineX, lineY, color, false);
                    lineY += font.lineHeight;
                }
            } finally {
                graphics.disableScissor();
            }
            return;
        }
        if (font.width(message) > bounds.width()
                && marqueeActive(view, text, mouseX, mouseY)) {
            int offset = MarqueeText.offset(
                    font.width(message), bounds.width(), System.currentTimeMillis());
            graphics.enableScissor(bounds.x(), bounds.y(), bounds.right(), bounds.bottom());
            try {
                graphics.drawString(
                        font, message, bounds.x() - offset, bounds.y(), color, false);
            } finally {
                graphics.disableScissor();
            }
            return;
        }
        String clipped = font.plainSubstrByWidth(message.getString(), bounds.width());
        Component visible = Component.literal(clipped);
        int x = switch (text.alignment()) {
            case LEFT -> bounds.x();
            case CENTER -> bounds.x() + Math.max(0, (bounds.width() - font.width(visible)) / 2);
            case RIGHT -> bounds.right() - font.width(visible);
        };
        graphics.drawString(font, visible, x, bounds.y(), color, false);
    }

    private void renderPreciseTooltip(
            GuiGraphics graphics, ViewSpec view, int mouseX, int mouseY) {
        for (ViewSpec.TooltipRegion region : view.tooltipRegions()) {
            Bounds hit = region.hitBounds(font.width(resolve(region.text())));
            if (!hit.contains(mouseX, mouseY)
                    || view.clipFor(region.id())
                            .filter(clip -> !clip.contains(mouseX, mouseY))
                            .isPresent()) {
                continue;
            }
            graphics.renderTooltip(
                    font, tooltipLines(resolve(region.tooltip())), mouseX, mouseY);
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

    private void synchronizePreviews(ViewSpec view) {
        Set<String> requested = new HashSet<>();
        Set<PreviewAssetKey> desiredSkins = new HashSet<>();
        Set<String> desiredCapes = new HashSet<>();
        boolean editor = PRESET_EDITOR_SCREEN_ID.equals(view.screenId());
        for (ViewSpec.Preview preview : view.previews()) {
            requested.add(preview.id());
            previewSlots.compute(
                    preview.id(),
                    (ignored, existing) -> existing != null && existing.editor == editor
                            ? existing
                            : new PreviewSlot(editor));
            PreviewAssetKey key = previewAssetKey(preview);
            if (preview.requiresLoadedSkin()) {
                desiredSkins.add(key);
                skinTextures.request(
                        key,
                        () -> runtime.loadSkinPreview(preview),
                        () -> runtime.reportSkinPreviewFailure(preview));
            }
            preview.capeId().ifPresent(capeId -> {
                desiredCapes.add(capeId);
                capeTextures.request(
                        capeId,
                        () -> runtime.loadCapePreview(preview),
                        () -> runtime.reportCapePreviewFailure(preview));
            });
        }
        for (ViewSpec.BackEquipmentPreview backEquipment : view.backEquipmentPreviews()) {
            desiredCapes.add(backEquipment.capeId());
            capeTextures.request(
                    backEquipment.capeId(),
                    () -> runtime.loadCapePreview(backEquipment.capeId()),
                    () -> {});
        }
        previewSlots.keySet().removeIf(id -> !requested.contains(id));
        skinTextures.retain(desiredSkins);
        capeTextures.retain(desiredCapes);
    }

    private void renderPreview(GuiGraphics graphics, ViewSpec.Preview preview) {
        PreviewSlot slot = previewSlots.get(preview.id());
        if (slot == null) {
            return;
        }
        TextureHandle skin;
        SkinModel model;
        if (!preview.requiresLoadedSkin()) {
            PlayerAppearance borrowed = runtime.currentPlayerAppearance()
                    .orElseThrow(() -> new IllegalStateException(
                            "Current-player appearance capability is unavailable"));
            skin = borrowed.skin();
            model = borrowed.model();
        } else {
            Optional<TextureHandle> loaded = skinTextures.handle(previewAssetKey(preview));
            if (loaded.isEmpty()) {
                return;
            }
            skin = loaded.orElseThrow();
            model = preview.variant() == SkinVariant.SLIM ? SkinModel.SLIM : SkinModel.CLASSIC;
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
        slot.renderer.render(
                graphics,
                new PreviewRenderer.PreviewRequest(
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
                        stage.height()));
    }

    private void releasePreviews() {
        try {
            skinTextures.close();
        } finally {
            try {
                capeTextures.close();
            } finally {
                previewSlots.clear();
            }
        }
    }

    private Component resolve(UiMessage message) {
        if (message.literal()) {
            return Component.literal(message.key());
        }
        Object[] arguments = message.arguments().stream()
                .map(argument -> argument instanceof UiMessage nested ? resolve(nested) : argument)
                .toArray();
        return Component.translatable(message.key(), arguments);
    }

    private void renderBackEquipmentPreviews(GuiGraphics graphics, ViewSpec view) {
        for (ViewSpec.BackEquipmentPreview backEquipment : view.backEquipmentPreviews()) {
            Optional<TextureHandle> loaded = capeTextures.handle(backEquipment.capeId());
            if (loaded.isEmpty()) {
                continue;
            }
            Bounds bounds = backEquipment.bounds();
            renderClipped(
                    graphics,
                    view,
                    backEquipment.id(),
                    bounds,
                    () -> backEquipmentRenderer.render(
                            graphics,
                            new BackEquipmentPreviewRenderer.Request(
                                    loaded.orElseThrow(),
                                    backEquipment.mode(),
                                    bounds.x(),
                                    bounds.y(),
                                    bounds.width(),
                                    bounds.height())));
        }
    }

    private static void renderClipped(
            GuiGraphics graphics,
            ViewSpec view,
            String elementId,
            Bounds bounds,
            Runnable draw) {
        Bounds viewport = view.clipFor(elementId).orElse(bounds);
        int left = Math.max(bounds.x(), viewport.x());
        int top = Math.max(bounds.y(), viewport.y());
        int right = Math.min(bounds.right(), viewport.right());
        int bottom = Math.min(bounds.bottom(), viewport.bottom());
        if (right <= left || bottom <= top) {
            return;
        }
        graphics.enableScissor(left, top, right, bottom);
        try {
            draw.run();
        } finally {
            graphics.disableScissor();
        }
    }

    private static void renderClipped(
            GuiGraphics graphics, ViewSpec view, String elementId, Runnable draw) {
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

    private static boolean isPointerSurface(ViewSpec view, double mouseX, double mouseY) {
        return PointerRouting.hit(view, mouseX, mouseY).anyInteractiveSurface();
    }

    private static boolean isVerticalScrollSurface(
            ViewSpec view, double mouseX, double mouseY) {
        return view.scrollbar()
                .filter(scrollbar -> scrollbar.orientation() == ViewSpec.Scrollbar.Orientation.VERTICAL)
                .map(ViewSpec.Scrollbar::track)
                .filter(track -> mouseX >= 0.0 && mouseX < view.width())
                .filter(track -> mouseY >= track.y() && mouseY < track.bottom())
                .isPresent();
    }

    private record WidgetShape(
            String id,
            ViewSpec.WidgetKind kind,
            boolean visible,
            int height,
            int maxLength,
            Optional<UiMessage> hint) {
        private WidgetShape(ViewSpec.Widget widget) {


            this(
                    widget.id(),
                    widget.kind(),
                    widget.visible(),
                    widget.bounds().height(),
                    widget.maxLength(),
                    widget.hint());
        }
    }

    private record MaskedNativeWidget(String id, AbstractWidget widget, boolean active) {}

    private final class PreviewSlot {


        private final boolean editor;
        private final PreviewRenderer<GuiGraphics> renderer;

        private PreviewSlot(boolean editor) {
            this.editor = editor;
            renderer = Objects.requireNonNull(
                    editor
                            ? capabilities.createEditorPreviewRenderer()
                            : capabilities.createSimplePreviewRenderer(),
                    editor ? "editorPreviewRenderer" : "simplePreviewRenderer");
        }
    }

    private static PreviewAssetKey previewAssetKey(ViewSpec.Preview preview) {
        return new PreviewAssetKey(
                preview.id(),
                new PreviewIdentity(
                        preview.skin(),
                        preview.imageRevision(),
                        preview.variant(),
                        preview.capeId(),
                        preview.catalogImage(),
                        preview.externalImage()));
    }

    private record PreviewAssetKey(String previewId, PreviewIdentity identity) {
        private PreviewAssetKey {
            Objects.requireNonNull(previewId, "previewId");
            Objects.requireNonNull(identity, "identity");
        }
    }

    private record PreviewIdentity(
            SkinReference skin,
            String imageRevision,
            SkinVariant variant,
            Optional<String> capeId,
            Optional<ViewSpec.CatalogImage> catalogImage,
            Optional<ViewSpec.ExternalImage> externalImage) {
        private PreviewIdentity {
            Objects.requireNonNull(skin, "skin");
            Objects.requireNonNull(imageRevision, "imageRevision");
            Objects.requireNonNull(variant, "variant");
            capeId = Objects.requireNonNull(capeId, "capeId");
            catalogImage = Objects.requireNonNull(catalogImage, "catalogImage");
            externalImage = Objects.requireNonNull(externalImage, "externalImage");
        }
    }

    private record NativeTabGroup(TabNavigationBar navigation) {
        private NativeTabGroup {
            Objects.requireNonNull(navigation, "navigation");
        }
    }

    private record NativeTab(String id, Component title, boolean enabled) implements Tab {
        private NativeTab {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(title, "title");
        }

        @Override
        public Component getTabTitle() {
            return title;
        }

        @Override
        public void visitChildren(Consumer<AbstractWidget> visitor) {
            Objects.requireNonNull(visitor, "visitor");
        }

        @Override
        public void doLayout(ScreenRectangle area) {
            Objects.requireNonNull(area, "area");
        }
    }

    private final class DispatchingTabManager extends TabManager {
        private boolean dispatchEnabled;

        private DispatchingTabManager() {
            super(ignored -> {}, ignored -> {});
        }

        @Override
        public void setCurrentTab(Tab tab, boolean playSound) {
            if (dispatchEnabled && tab instanceof NativeTab nativeTab && !nativeTab.enabled()) {
                return;
            }
            Tab previous = getCurrentTab();
            super.setCurrentTab(tab, playSound);
            if (dispatchEnabled && previous != tab && tab instanceof NativeTab nativeTab) {
                pendingTabSelection = nativeTab.id();
            }
        }

        private void selectInitial(NativeTab tab) {
            super.setCurrentTab(tab, false);
        }

        private void enableDispatch() {
            dispatchEnabled = true;
        }
    }
}

package com.naocraftlab.skins.compat.gui.immediate;

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
import com.naocraftlab.skins.runtime.InfoButtonStyle;
import com.naocraftlab.skins.runtime.MarqueeRouting;
import com.naocraftlab.skins.runtime.PreviewAssetCache;
import com.naocraftlab.skins.runtime.PointerRouting;
import com.naocraftlab.skins.runtime.UiMessage;
import com.naocraftlab.skins.runtime.ViewSpec;
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


public abstract class NclSkinsImmediateScreen extends Screen {
    private static final int ACTION_ICON_SIZE = 15;
    private static final int COLLECTION_HEADER_TRAILING_INFO_WIDTH = 14;
    private static final int OFFSCREEN_MOUSE_COORDINATE = -1_000_000;
    private static final String PRESET_EDITOR_SCREEN_ID = "preset_editor";
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

    private final Screen parent;
    private final ImmediateScreenCapabilities capabilities;
    private final ClientRuntime runtime;
    private final TextureRegistry textures;
    private final NativeScrollController scrollController;
    private final PreviewAssetCache<PreviewAssetKey> skinTextures;
    private final PreviewAssetCache<String> capeTextures;
    private final Map<String, AbstractWidget> nativeWidgets = new LinkedHashMap<>();
    private final Map<String, NativeTabGroup> nativeTabGroups = new LinkedHashMap<>();
    private final Map<String, PreviewSlot> previewSlots = new HashMap<>();

    private ClientRuntime.Subscription subscription;
    private List<WidgetShape> widgetShapes = List.of();
    private List<ViewSpec.TabGroup> tabGroupShapes = List.of();
    private long consumedFocusToken;
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
        this.skinTextures = new PreviewAssetCache<>(textures, TextureKind.PLAYER_SKIN);
        this.capeTextures = new PreviewAssetCache<>(textures, TextureKind.IMAGE);
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


        renderEpochBackground(graphics, mouseX, mouseY, partialTick);
        for (ViewSpec.Panel panel : view.panels()) {
            if (panel.style() != ViewSpec.Panel.Style.VANILLA_LIST) {
                continue;
            }
            renderClipped(graphics, view, panel.id(), () -> capabilities.renderPanel(graphics, panel));
        }
        for (ViewSpec.Preview preview : view.previews()) {
            renderClipped(graphics, view, preview.id(), () -> renderPreview(graphics, preview));
        }
        renderCapeTextures(graphics, view);


        for (ViewSpec.Panel panel : view.panels()) {
            if (panel.style() == ViewSpec.Panel.Style.VANILLA_LIST) {
                continue;
            }
            capabilities.renderPanel(graphics, panel);
        }
        view.scrollbar().ifPresent(scrollbar -> renderScrollbar(graphics, scrollbar));
        for (NativeTabGroup tabGroup : nativeTabGroups.values()) {
            tabGroup.navigation().render(graphics, mouseX, mouseY, partialTick);
        }
        Optional<String> pointerOwner = pointerOwnerAt(view, mouseX, mouseY).map(ViewSpec.Widget::id);
        for (Map.Entry<String, AbstractWidget> entry : nativeWidgets.entrySet()) {
            boolean receivesPointer = pointerOwner
                    .map(entry.getKey()::equals)
                    .orElse(true);
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
        renderIconDecorations(graphics, view, mouseX, mouseY);
        for (ViewSpec.Text text : view.texts()) {
            renderClipped(graphics, view, text.id(), () ->
                    renderText(graphics, view, text, mouseX, mouseY));
        }
        runtime.acknowledgeViewRendered(view);
    }


    protected abstract void renderEpochBackground(
            GuiGraphics graphics, int mouseX, int mouseY, float partialTick);

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (runtime.closed()) {
            return false;
        }
        for (NativeTabGroup tabGroup : nativeTabGroups.values()) {
            if (tabGroup.navigation().keyPressed(keyCode)) {
                dispatchPendingTabSelection();
                return true;
            }
        }
        boolean consumed = super.keyPressed(keyCode, scanCode, modifiers);
        dispatchPendingTabSelection();
        return consumed;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (runtime.closed()) {
            return false;
        }
        ViewSpec clickView = currentView();
        Optional<ViewSpec.Widget> pointerOwner = pointerOwnerAt(clickView, mouseX, mouseY);
        Optional<ViewSpec.Widget> priorityAction = pointerOwner.filter(widget ->
                widget.kind() == ViewSpec.WidgetKind.INFO_BUTTON
                        || widget.kind() == ViewSpec.WidgetKind.CATALOG_DELETE);
        if (priorityAction.isPresent()) {
            ViewSpec.Widget action = priorityAction.orElseThrow();
            if (button == 0 && action.enabled()) {
                runtime.dispatchWidget(action.id(), hasShiftDown());
            }
            return true;
        }
        List<MaskedNativeWidget> maskedWidgets = maskWidgetsOutsideClip(clickView, mouseX, mouseY);
        boolean consumed;
        try {
            consumed = super.mouseClicked(mouseX, mouseY, button);
        } finally {
            ViewSpec latestView = runtime.closed() ? clickView : currentView();
            restoreMaskedWidgets(maskedWidgets, latestView);
        }
        dispatchPendingTabSelection();
        ViewSpec view = currentView();
        if (!consumed && pointerOwner.isPresent()) {


            consumed = true;
        }
        if (!consumed) {
            Optional<ViewSpec.Widget> invisibleHit = view.widgets().stream()
                    .filter(widget -> widget.kind() == ViewSpec.WidgetKind.BUTTON
                            || widget.kind() == ViewSpec.WidgetKind.ICON_BUTTON
                            || widget.kind() == ViewSpec.WidgetKind.COLLECTION_HEADER)
                    .filter(widget -> !widget.visible() && widget.enabled())
                    .filter(widget -> widget.bounds().contains(mouseX, mouseY))
                    .filter(widget -> pointerInsideClip(view, widget.id(), mouseX, mouseY))
                    .findFirst();
            if (invisibleHit.isPresent()) {
                runtime.dispatchWidget(invisibleHit.orElseThrow().id(), hasShiftDown());
                consumed = true;
            }
        }
        if (!consumed) {
            runtime.pointerPressed(mouseX, mouseY, button);
            consumed = isPointerSurface(view, mouseX, mouseY);
        }
        return consumed;
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
        return runtime.view(Math.max(1, width), Math.max(1, height), lastMouseX, lastMouseY);
    }

    private void synchronizeWidgets(ViewSpec view) {
        List<WidgetShape> nextShapes = view.widgets().stream().map(WidgetShape::new).toList();
        List<ViewSpec.TabGroup> nextTabGroupShapes = List.copyOf(view.tabGroups());
        String focusedWidgetId = null;
        if (!nextShapes.equals(widgetShapes) || !nextTabGroupShapes.equals(tabGroupShapes)) {
            focusedWidgetId = currentFocusedWidgetId();
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
                AbstractWidget nativeWidget = createWidget(widget);
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
                    capeCard.setSelected(capeCardSelected(widget));
                }
                if (nativeWidget instanceof CollectionHeaderWidget header) {
                    header.setTrailingInfo(widget.collectionHeaderHasTrailingInfo());
                }
                nativeWidget.setMessage(resolve(widget.label()));
                if (widget.kind() == ViewSpec.WidgetKind.ICON_BUTTON) {
                    ((IconButtonWidget) nativeWidget).setIconTexture(actionIconTexture(widget));
                    nativeWidget.setTooltip(Tooltip.create(resolve(
                            widget.hint().orElse(widget.label()))));
                }
                if (widget.kind() == ViewSpec.WidgetKind.INFO_BUTTON) {
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
        consumeFocusRequest(view.focusRequest());
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
        if (widget.kind() == ViewSpec.WidgetKind.CAPE_CARD) {
            return new CapeCardWidget(
                    widget.id(),
                    bounds.x(),
                    bounds.y(),
                    bounds.width(),
                    bounds.height(),
                    resolve(widget.label()),
                    capeCardSelected(widget));
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
        if (widget.kind() == ViewSpec.WidgetKind.ICON_BUTTON) {
            IconButtonWidget button = new IconButtonWidget(
                    widget.id(),
                    bounds.x(),
                    bounds.y(),
                    bounds.width(),
                    bounds.height(),
                    resolve(widget.label()),
                    actionIconTexture(widget));
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
                            ignored -> runtime.dispatchWidget(widget.id(), hasShiftDown()))
                    .bounds(bounds.x(), bounds.y(), bounds.width(), bounds.height())
                    .build();
            widget.hint().ifPresent(hint -> button.setTooltip(Tooltip.create(resolve(hint))));
            return button;
        }
        EditBox editBox = new EditBox(
                font,
                bounds.x(),
                bounds.y(),
                bounds.width(),
                bounds.height(),
                resolve(widget.label()));
        editBox.setMaxLength(widget.maxLength());
        editBox.setValue(widget.value().orElse(""));
        widget.hint().ifPresent(hint -> editBox.setHint(resolve(hint)));
        editBox.setResponder(value -> {
            if (!updatingText) {
                runtime.dispatchText(widget.id(), value);
            }
        });
        return editBox;
    }

    private static boolean capeCardSelected(ViewSpec.Widget widget) {
        return widget.value().filter("selected"::equals).isPresent();
    }

    private static ResourceLocation actionIconTexture(ViewSpec.Widget widget) {
        String icon = widget.icon().orElseThrow(
                () -> new IllegalArgumentException("Icon button has no icon: " + widget.id()));
        return actionIconTexture(icon);
    }

    private static ResourceLocation actionIconTexture(String icon) {
        if (!APPROVED_ACTION_ICONS.contains(icon)) {
            throw new IllegalArgumentException("Unsupported action icon: " + icon);
        }
        return Objects.requireNonNull(
                ResourceLocation.tryParse("nclskins:textures/gui/icons/" + icon + ".png"),
                "actionIconTexture");
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
                    graphics.blit(
                            actionIconTexture(decoration.icon()),
                            bounds.x(),
                            bounds.y(),
                            0.0F,
                            0.0F,
                            bounds.width(),
                            bounds.height(),
                            ACTION_ICON_SIZE,
                            ACTION_ICON_SIZE);
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


    private static final class ScrollingTextWidget extends AbstractWidget {
        private ScrollingTextWidget(Component message, Bounds bounds) {
            super(
                    bounds.x(),
                    bounds.y(),
                    bounds.width(),
                    bounds.height(),
                    message);
        }

        private void renderScrolling(GuiGraphics graphics, Font font, int color) {
            renderScrollingString(graphics, font, 0, color);
        }

        @Override
        protected void renderWidget(
                GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput output) {
        }
    }


    private final class IconButtonWidget extends AbstractButton {
        private final String widgetId;
        private ResourceLocation iconTexture;

        private IconButtonWidget(
                String widgetId,
                int x,
                int y,
                int width,
                int height,
                Component message,
                ResourceLocation iconTexture) {
            super(x, y, width, height, message);
            this.widgetId = Objects.requireNonNull(widgetId, "widgetId");
            this.iconTexture = Objects.requireNonNull(iconTexture, "iconTexture");
        }

        private void setIconTexture(ResourceLocation iconTexture) {
            this.iconTexture = Objects.requireNonNull(iconTexture, "iconTexture");
        }

        @Override
        public void onPress() {
            runtime.dispatchWidget(widgetId, hasShiftDown());
        }

        @Override
        protected void renderWidget(
                GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            super.renderWidget(graphics, mouseX, mouseY, partialTick);
            int iconX = getX() + (getWidth() - ACTION_ICON_SIZE) / 2;
            int iconY = getY() + (getHeight() - ACTION_ICON_SIZE) / 2;
            float tint = active ? 1.0F : 0.5F;
            graphics.setColor(tint, tint, tint, 1.0F);
            graphics.blit(
                    iconTexture,
                    iconX,
                    iconY,
                    0.0F,
                    0.0F,
                    ACTION_ICON_SIZE,
                    ACTION_ICON_SIZE,
                    ACTION_ICON_SIZE,
                    ACTION_ICON_SIZE);
            graphics.setColor(1.0F, 1.0F, 1.0F, 1.0F);
        }

        @Override
        public void renderString(GuiGraphics graphics, Font font, int color) {

        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput output) {
            defaultButtonNarrationText(output);
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
            runtime.dispatchWidget(widgetId, hasShiftDown());
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
            runtime.dispatchWidget(widgetId, hasShiftDown());
        }

        @Override
        protected void renderWidget(
                GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            int backgroundColor =
                    CatalogCardStyle.backgroundColor(active, isHoveredOrFocused());
            if (backgroundColor != CatalogCardStyle.TRANSPARENT_BACKGROUND_COLOR) {
                graphics.fill(getX(), getY(), getX() + getWidth(), getY() + getHeight(), backgroundColor);
            }
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput output) {
            defaultButtonNarrationText(output);
        }
    }


    private final class CapeCardWidget extends AbstractButton {
        private final String widgetId;
        private boolean selected;

        private CapeCardWidget(
                String widgetId,
                int x,
                int y,
                int width,
                int height,
                Component message,
                boolean selected) {
            super(x, y, width, height, message);
            this.widgetId = Objects.requireNonNull(widgetId, "widgetId");
            this.selected = selected;
        }

        private void setSelected(boolean selected) {
            this.selected = selected;
        }

        @Override
        public void onPress() {
            runtime.dispatchWidget(widgetId, hasShiftDown());
        }

        @Override
        protected void renderWidget(
                GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            int color = selected
                    ? 0x665A8FCB
                    : CatalogCardStyle.backgroundColor(active, isHoveredOrFocused());
            if (color != CatalogCardStyle.TRANSPARENT_BACKGROUND_COLOR) {
                graphics.fill(getX(), getY(), getX() + getWidth(), getY() + getHeight(), color);
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
            runtime.dispatchWidget(widgetId, hasShiftDown());
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
            runtime.dispatchWidget(widgetId, hasShiftDown());
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
            runtime.dispatchWidget(widgetId, hasShiftDown());
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

    private void consumeFocusRequest(Optional<ViewSpec.FocusRequest> request) {
        if (request.isEmpty()) {
            consumedFocusToken = 0;
            return;
        }
        ViewSpec.FocusRequest focusRequest = request.orElseThrow();
        if (focusRequest.token() <= consumedFocusToken) {
            return;
        }
        AbstractWidget target = nativeWidgets.get(focusRequest.widgetId());
        if (target == null || !target.visible || !target.active) {
            return;
        }
        setFocused(target);
        consumedFocusToken = focusRequest.token();
    }

    private void dispatchPendingTabSelection() {
        String tabId = pendingTabSelection;
        pendingTabSelection = null;
        if (tabId != null) {
            runtime.dispatchWidget(tabId);
        }
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
        Bounds bounds = text.bounds();
        Component message = resolve(text.message());
        int color = textColor(text);
        if (font.width(message) > bounds.width()
                && marqueeActive(view, text, mouseX, mouseY)) {
            new ScrollingTextWidget(message, bounds).renderScrolling(graphics, font, color);
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
            if (preview.skin().optionalAssetId().isPresent() || preview.catalogImage().isPresent()) {
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
        for (ViewSpec.CapeTexture capeTexture : view.capeTextures()) {
            desiredCapes.add(capeTexture.capeId());
            capeTextures.request(
                    capeTexture.capeId(),
                    () -> runtime.loadCapePreview(capeTexture.capeId()),
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
        if (preview.skin().optionalAssetId().isEmpty() && preview.catalogImage().isEmpty()) {
            PlayerAppearance borrowed = runtime.currentPlayerAppearance()
                    .orElseThrow(() -> new IllegalStateException(
                            "Current-player appearance capability is unavailable"));
            skin = borrowed.skin();
            model = borrowed.model();
        } else {
            Optional<TextureHandle> loaded = skinTextures.handle(
                    previewAssetKey(preview));
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
        Bounds bounds = preview.bounds();
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
                        preview.scale()));
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

    private void renderCapeTextures(GuiGraphics graphics, ViewSpec view) {
        for (ViewSpec.CapeTexture capeTexture : view.capeTextures()) {
            Optional<TextureHandle> loaded = capeTextures.handle(capeTexture.capeId());
            if (loaded.isEmpty()) {
                continue;
            }
            TextureHandle handle = loaded.orElseThrow();
            ResourceLocation location = ResourceLocation.tryParse(handle.location());
            if (location == null) {
                continue;
            }
            Bounds bounds = capeTexture.bounds();
            renderClipped(graphics, view, capeTexture.id(), () -> graphics.blit(
                    location,
                    bounds.x(),
                    bounds.y(),
                    bounds.width(),
                    bounds.height(),
                    1.0F,
                    1.0F,
                    10,
                    16,
                    handle.width(),
                    handle.height()));
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
            int maxLength) {
        private WidgetShape(ViewSpec.Widget widget) {


            this(
                    widget.id(),
                    widget.kind(),
                    widget.visible(),
                    widget.bounds().height(),
                    widget.maxLength());
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
                        preview.catalogImage()));
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
            Optional<ViewSpec.CatalogImage> catalogImage) {
        private PreviewIdentity {
            Objects.requireNonNull(skin, "skin");
            Objects.requireNonNull(imageRevision, "imageRevision");
            Objects.requireNonNull(variant, "variant");
            capeId = Objects.requireNonNull(capeId, "capeId");
            catalogImage = Objects.requireNonNull(catalogImage, "catalogImage");
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

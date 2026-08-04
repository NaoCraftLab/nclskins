package com.naocraftlab.skins.runtime;

import com.naocraftlab.skins.client.BackEquipmentPreviewRenderer;
import com.naocraftlab.skins.client.OuterLayerVisibility;
import com.naocraftlab.skins.client.PreviewRenderer;
import com.naocraftlab.skins.core.model.SkinReference;
import com.naocraftlab.skins.core.model.SkinVariant;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;


public record ViewSpec(
        String screenId,
        UiMessage title,
        int width,
        int height,
        List<Panel> panels,
        List<Text> texts,
        List<Widget> widgets,
        List<Preview> previews,
        Optional<Scrollbar> scrollbar,
        List<TabGroup> tabGroups,
        Optional<FocusRequest> focusRequest,
        List<ClipRegion> clipRegions,
        List<BackEquipmentPreview> backEquipmentPreviews,
        List<IconDecoration> iconDecorations,
        List<ScrollSurface> scrollSurfaces,
        List<TooltipRegion> tooltipRegions) {
    public ViewSpec(
            String screenId,
            UiMessage title,
            int width,
            int height,
            List<Panel> panels,
            List<Text> texts,
            List<Widget> widgets,
            List<Preview> previews,
            Optional<Scrollbar> scrollbar) {
        this(
                screenId,
                title,
                width,
                height,
                panels,
                texts,
                widgets,
                previews,
                scrollbar,
                List.of(),
                Optional.empty(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of());
    }

    public ViewSpec(
            String screenId,
            UiMessage title,
            int width,
            int height,
            List<Panel> panels,
            List<Text> texts,
            List<Widget> widgets,
            List<Preview> previews,
            Optional<Scrollbar> scrollbar,
            List<TabGroup> tabGroups,
            Optional<FocusRequest> focusRequest) {
        this(
                screenId,
                title,
                width,
                height,
                panels,
                texts,
                widgets,
                previews,
                scrollbar,
                tabGroups,
                focusRequest,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of());
    }

    public ViewSpec(
            String screenId,
            UiMessage title,
            int width,
            int height,
            List<Panel> panels,
            List<Text> texts,
            List<Widget> widgets,
            List<Preview> previews,
            Optional<Scrollbar> scrollbar,
            List<TabGroup> tabGroups,
            Optional<FocusRequest> focusRequest,
            List<ClipRegion> clipRegions,
            List<BackEquipmentPreview> backEquipmentPreviews) {
        this(
                screenId,
                title,
                width,
                height,
                panels,
                texts,
                widgets,
                previews,
                scrollbar,
                tabGroups,
                focusRequest,
                clipRegions,
                backEquipmentPreviews,
                List.of(),
                List.of(),
                List.of());
    }

    public ViewSpec(
            String screenId,
            UiMessage title,
            int width,
            int height,
            List<Panel> panels,
            List<Text> texts,
            List<Widget> widgets,
            List<Preview> previews,
            Optional<Scrollbar> scrollbar,
            List<TabGroup> tabGroups,
            Optional<FocusRequest> focusRequest,
            List<ClipRegion> clipRegions,
            List<BackEquipmentPreview> backEquipmentPreviews,
            List<IconDecoration> iconDecorations) {
        this(
                screenId,
                title,
                width,
                height,
                panels,
                texts,
                widgets,
                previews,
                scrollbar,
                tabGroups,
                focusRequest,
                clipRegions,
                backEquipmentPreviews,
                iconDecorations,
                List.of(),
                List.of());
    }

    public ViewSpec(
            String screenId,
            UiMessage title,
            int width,
            int height,
            List<Panel> panels,
            List<Text> texts,
            List<Widget> widgets,
            List<Preview> previews,
            Optional<Scrollbar> scrollbar,
            List<TabGroup> tabGroups,
            Optional<FocusRequest> focusRequest,
            List<ClipRegion> clipRegions,
            List<BackEquipmentPreview> backEquipmentPreviews,
            List<IconDecoration> iconDecorations,
            List<ScrollSurface> scrollSurfaces) {
        this(
                screenId,
                title,
                width,
                height,
                panels,
                texts,
                widgets,
                previews,
                scrollbar,
                tabGroups,
                focusRequest,
                clipRegions,
                backEquipmentPreviews,
                iconDecorations,
                scrollSurfaces,
                List.of());
    }

    public ViewSpec {
        Objects.requireNonNull(screenId, "screenId");
        Objects.requireNonNull(title, "title");
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("view dimensions must be positive");
        }
        panels = List.copyOf(Objects.requireNonNull(panels, "panels"));
        texts = List.copyOf(Objects.requireNonNull(texts, "texts"));
        widgets = List.copyOf(Objects.requireNonNull(widgets, "widgets"));
        previews = List.copyOf(Objects.requireNonNull(previews, "previews"));
        scrollbar = Objects.requireNonNull(scrollbar, "scrollbar");
        tabGroups = List.copyOf(Objects.requireNonNull(tabGroups, "tabGroups"));
        focusRequest = Objects.requireNonNull(focusRequest, "focusRequest");
        clipRegions = List.copyOf(Objects.requireNonNull(clipRegions, "clipRegions"));
        backEquipmentPreviews = List.copyOf(Objects.requireNonNull(
                backEquipmentPreviews, "backEquipmentPreviews"));
        iconDecorations = List.copyOf(Objects.requireNonNull(iconDecorations, "iconDecorations"));
        scrollSurfaces = List.copyOf(Objects.requireNonNull(scrollSurfaces, "scrollSurfaces"));
        tooltipRegions = List.copyOf(Objects.requireNonNull(tooltipRegions, "tooltipRegions"));
        if (scrollSurfaces.stream().map(ScrollSurface::id).distinct().count() != scrollSurfaces.size()) {
            throw new IllegalArgumentException("scroll surface ids must be unique");
        }
    }

    public record TooltipRegion(
            String id,
            Bounds textBounds,
            UiMessage text,
            Text.Alignment alignment,
            UiMessage tooltip) {
        public TooltipRegion {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(textBounds, "textBounds");
            Objects.requireNonNull(text, "text");
            Objects.requireNonNull(alignment, "alignment");
            Objects.requireNonNull(tooltip, "tooltip");
            if (id.isBlank()) {
                throw new IllegalArgumentException("tooltip region id must not be blank");
            }
        }

        public Bounds hitBounds(int textPixelWidth) {
            int width = Math.max(1, Math.min(textBounds.width(), textPixelWidth));
            int x = switch (alignment) {
                case LEFT -> textBounds.x();
                case CENTER -> textBounds.x() + (textBounds.width() - width) / 2;
                case RIGHT -> textBounds.right() - width;
            };
            return new Bounds(x, textBounds.y(), width, textBounds.height());
        }
    }

    public Optional<Widget> widget(String id) {
        Objects.requireNonNull(id, "id");
        return widgets.stream().filter(widget -> widget.id().equals(id)).findFirst();
    }

    public Optional<Bounds> clipFor(String elementId) {
        Objects.requireNonNull(elementId, "elementId");
        return clipRegions.stream()
                .filter(region -> region.matches(elementId))
                .map(ClipRegion::bounds)
                .findFirst();
    }

    public Optional<ScrollSurface> scrollSurface(String id) {
        Objects.requireNonNull(id, "id");
        return scrollSurfaces.stream().filter(surface -> surface.id().equals(id)).findFirst();
    }

    public Optional<ScrollSurface> scrollSurfaceAt(double x, double y) {
        return scrollSurfaces.stream()
                .filter(surface -> surface.viewport().contains(x, y))
                .findFirst();
    }

    public enum WidgetKind {
        BUTTON,
        ICON_BUTTON,

        INFO_BUTTON,
        TEXT_FIELD,
        COLLECTION_HEADER,

        CATALOG_CARD,

        CAPE_CARD,

        CATALOG_DELETE
    }


    public record TabGroup(String id, Bounds bounds, List<Tab> tabs) {
        public TabGroup {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(bounds, "bounds");
            tabs = List.copyOf(Objects.requireNonNull(tabs, "tabs"));
            if (tabs.isEmpty() || tabs.stream().filter(Tab::selected).count() != 1) {
                throw new IllegalArgumentException("a tab group must contain exactly one selected tab");
            }
        }
    }

    public record Tab(String id, UiMessage label, boolean selected, boolean enabled) {
        public Tab {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(label, "label");
        }
    }


    public record FocusRequest(String widgetId, long token) {
        public FocusRequest {
            Objects.requireNonNull(widgetId, "widgetId");
            if (token <= 0) {
                throw new IllegalArgumentException("focus token must be positive");
            }
        }
    }

    public record Panel(String id, Bounds bounds, Style style) {
        public Panel {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(bounds, "bounds");
            Objects.requireNonNull(style, "style");
        }

        public enum Style {
            VANILLA_LIST,
            VANILLA_HEADER,
            VANILLA_FOOTER
        }
    }

    public record Text(
            String id,
            Bounds bounds,
            UiMessage message,
            Alignment alignment,
            Optional<MarqueeActivation> marqueeActivation) {
        public Text(String id, Bounds bounds, UiMessage message, Alignment alignment) {
            this(id, bounds, message, alignment, Optional.empty());
        }

        public Text {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(bounds, "bounds");
            Objects.requireNonNull(message, "message");
            Objects.requireNonNull(alignment, "alignment");
            marqueeActivation = Objects.requireNonNull(marqueeActivation, "marqueeActivation");
        }

        public enum Alignment {
            LEFT,
            CENTER,
            RIGHT
        }
    }

    public record MarqueeActivation(Bounds hoverBounds, List<String> focusWidgetIds) {
        public MarqueeActivation {
            Objects.requireNonNull(hoverBounds, "hoverBounds");
            focusWidgetIds = List.copyOf(Objects.requireNonNull(focusWidgetIds, "focusWidgetIds"));
            if (focusWidgetIds.isEmpty() || focusWidgetIds.stream().anyMatch(String::isBlank)) {
                throw new IllegalArgumentException("marquee focus widget ids must not be empty or blank");
            }
        }
    }


    public record IconDecoration(
            String id,
            Bounds bounds,
            String icon,
            String ownerWidgetId,
            float idleOpacity,
            float activeOpacity) {
        public IconDecoration {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(bounds, "bounds");
            Objects.requireNonNull(icon, "icon");
            Objects.requireNonNull(ownerWidgetId, "ownerWidgetId");
            if (id.isBlank() || icon.isBlank() || icon.indexOf(':') >= 0 || ownerWidgetId.isBlank()) {
                throw new IllegalArgumentException("decoration ids must be non-blank and icon must contain no colon");
            }
            if (!Float.isFinite(idleOpacity)
                    || !Float.isFinite(activeOpacity)
                    || idleOpacity < 0.0F
                    || activeOpacity > 1.0F
                    || idleOpacity > activeOpacity) {
                throw new IllegalArgumentException("decoration opacity must satisfy 0 <= idle <= active <= 1");
            }
        }
    }

    public record Widget(
            String id,
            WidgetKind kind,
            Bounds bounds,
            UiMessage label,
            Optional<String> value,
            Optional<UiMessage> hint,
            boolean enabled,
            boolean visible,
            int maxLength,
            boolean selectAllOnPrimaryClick,
            Optional<String> submitActionId) {
        public Widget(
                String id,
                WidgetKind kind,
                Bounds bounds,
                UiMessage label,
                Optional<String> value,
                Optional<UiMessage> hint,
                boolean enabled,
                boolean visible,
                int maxLength) {
            this(
                    id,
                    kind,
                    bounds,
                    label,
                    value,
                    hint,
                    enabled,
                    visible,
                    maxLength,
                    false,
                    Optional.empty());
        }

        public Widget {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(bounds, "bounds");
            Objects.requireNonNull(label, "label");
            value = Objects.requireNonNull(value, "value");
            hint = Objects.requireNonNull(hint, "hint");
            submitActionId = Objects.requireNonNull(submitActionId, "submitActionId");
            if (maxLength < 0) {
                throw new IllegalArgumentException("maxLength must not be negative");
            }
            if (kind != WidgetKind.TEXT_FIELD
                    && (selectAllOnPrimaryClick || submitActionId.isPresent())) {
                throw new IllegalArgumentException("text-field interaction belongs only to text fields");
            }
            if (submitActionId.filter(String::isBlank).isPresent()) {
                throw new IllegalArgumentException("submit action id must not be blank");
            }
        }

        public static Widget button(String id, Bounds bounds, UiMessage label, boolean enabled) {
            return new Widget(
                    id,
                    WidgetKind.BUTTON,
                    bounds,
                    label,
                    Optional.empty(),
                    Optional.empty(),
                    enabled,
                    true,
                    0);
        }

        public static Widget iconButton(
                String id,
                Bounds bounds,
                UiMessage accessibleLabel,
                String icon,
                boolean enabled) {
            requireIconId(icon);
            return new Widget(
                    id,
                    WidgetKind.ICON_BUTTON,
                    bounds,
                    accessibleLabel,
                    Optional.of(icon),
                    Optional.of(accessibleLabel),
                    enabled,
                    true,
                    0);
        }

        public static Widget infoButton(
                String id, Bounds bounds, UiMessage accessibleInfo, boolean enabled) {
            Objects.requireNonNull(accessibleInfo, "accessibleInfo");
            return new Widget(
                    id,
                    WidgetKind.INFO_BUTTON,
                    bounds,
                    accessibleInfo,
                    Optional.empty(),
                    Optional.of(accessibleInfo),
                    enabled,
                    true,
                    0);
        }

        public static Widget collectionHeader(
                String id,
                Bounds bounds,
                UiMessage label,
                boolean enabled,
                boolean trailingInfo) {
            return new Widget(
                    id,
                    WidgetKind.COLLECTION_HEADER,
                    bounds,
                    label,
                    trailingInfo ? Optional.of("trailing_info") : Optional.empty(),
                    Optional.empty(),
                    enabled,
                    true,
                    0);
        }

        public Optional<String> icon() {
            return kind == WidgetKind.ICON_BUTTON ? value : Optional.empty();
        }

        public boolean collectionHeaderHasTrailingInfo() {
            return kind == WidgetKind.COLLECTION_HEADER
                    && value.filter("trailing_info"::equals).isPresent();
        }

        private static void requireIconId(String icon) {
            Objects.requireNonNull(icon, "icon");
            if (icon.isBlank() || icon.indexOf(':') >= 0) {
                throw new IllegalArgumentException("icon id must be non-blank and contain no colon");
            }
        }

        public static Widget textField(
                String id,
                Bounds bounds,
                UiMessage label,
                String value,
                UiMessage hint,
                boolean enabled,
                int maxLength) {
            return textField(
                    id, bounds, label, value, hint, enabled, maxLength, false, Optional.empty());
        }

        public static Widget textField(
                String id,
                Bounds bounds,
                UiMessage label,
                String value,
                UiMessage hint,
                boolean enabled,
                int maxLength,
                boolean selectAllOnPrimaryClick,
                Optional<String> submitActionId) {
            return new Widget(
                    id,
                    WidgetKind.TEXT_FIELD,
                    bounds,
                    label,
                    Optional.of(Objects.requireNonNull(value, "value")),
                    Optional.of(Objects.requireNonNull(hint, "hint")),
                    enabled,
                    true,
                    maxLength,
                    selectAllOnPrimaryClick,
                    submitActionId);
        }

        public static Widget catalogDelete(
                String id, Bounds bounds, UiMessage accessibleLabel, boolean enabled) {
            return new Widget(
                    id,
                    WidgetKind.CATALOG_DELETE,
                    bounds,
                    accessibleLabel,
                    Optional.empty(),
                    Optional.empty(),
                    enabled,
                    true,
                    0);
        }
    }


    public record Preview(
            String id,
            Bounds bounds,
            SkinReference skin,
            String imageRevision,
            SkinVariant variant,
            Optional<String> capeId,
            PreviewRenderer.CapeMode capeMode,
            OuterLayerVisibility outerLayerVisibility,
            float yawDegrees,
            float pitchDegrees,
            float scale,
            Optional<UUID> presetId,
            Optional<CatalogImage> catalogImage) {
        public Preview(
                String id,
                Bounds bounds,
                SkinReference skin,
                String imageRevision,
                SkinVariant variant,
                Optional<String> capeId,
                PreviewRenderer.CapeMode capeMode,
                OuterLayerVisibility outerLayerVisibility,
                float yawDegrees,
                float pitchDegrees,
                float scale,
                Optional<UUID> presetId) {
            this(
                    id,
                    bounds,
                    skin,
                    imageRevision,
                    variant,
                    capeId,
                    capeMode,
                    outerLayerVisibility,
                    yawDegrees,
                    pitchDegrees,
                    scale,
                    presetId,
                    Optional.empty());
        }

        public Preview {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(bounds, "bounds");
            Objects.requireNonNull(skin, "skin");
            Objects.requireNonNull(imageRevision, "imageRevision");
            if (imageRevision.isBlank()) {
                throw new IllegalArgumentException("imageRevision must not be blank");
            }
            Objects.requireNonNull(variant, "variant");
            capeId = Objects.requireNonNull(capeId, "capeId");
            Objects.requireNonNull(capeMode, "capeMode");
            Objects.requireNonNull(outerLayerVisibility, "outerLayerVisibility");
            presetId = Objects.requireNonNull(presetId, "presetId");
            catalogImage = Objects.requireNonNull(catalogImage, "catalogImage");
            if (!Float.isFinite(scale) || scale <= 0.0F) {
                throw new IllegalArgumentException("preview scale must be finite and positive");
            }
            if (capeId.isEmpty() && capeMode != PreviewRenderer.CapeMode.OFF) {
                throw new IllegalArgumentException("cape preview mode requires a cape");
            }
            if (catalogImage.isPresent() && skin.optionalAssetId().isPresent()) {
                throw new IllegalArgumentException("catalog preview must not also reference a library asset");
            }
        }

        public Preview(
                String id,
                Bounds bounds,
                SkinReference skin,
                String imageRevision,
                SkinVariant variant,
                Optional<String> capeId,
                PreviewRenderer.CapeMode capeMode,
                boolean outerLayerVisible,
                float yawDegrees,
                float pitchDegrees,
                float scale,
                Optional<UUID> presetId) {
            this(
                    id,
                    bounds,
                    skin,
                    imageRevision,
                    variant,
                    capeId,
                    capeMode,
                    outerLayerVisible
                            ? OuterLayerVisibility.allVisible()
                            : OuterLayerVisibility.noneVisible(),
                    yawDegrees,
                    pitchDegrees,
                    scale,
                    presetId,
                    Optional.empty());
        }
    }

    public record CatalogImage(String collectionId, String skinId) {
        public CatalogImage {
            collectionId = requireStableId(collectionId, "collectionId");
            skinId = requireStableId(skinId, "skinId");
        }

        private static String requireStableId(String value, String name) {
            Objects.requireNonNull(value, name);
            if (!value.matches("[a-z0-9][a-z0-9_-]{0,127}")) {
                throw new IllegalArgumentException(name + " is not a stable catalog id");
            }
            return value;
        }
    }


    public record ClipRegion(String id, Bounds bounds, List<String> elementPrefixes) {
        public ClipRegion {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(bounds, "bounds");
            elementPrefixes = List.copyOf(Objects.requireNonNull(elementPrefixes, "elementPrefixes"));
            if (id.isBlank() || elementPrefixes.isEmpty()
                    || elementPrefixes.stream().anyMatch(String::isBlank)) {
                throw new IllegalArgumentException("clip region id and prefixes must not be blank");
            }
        }

        public boolean matches(String elementId) {
            Objects.requireNonNull(elementId, "elementId");
            return elementPrefixes.stream().anyMatch(elementId::startsWith);
        }
    }


    public record BackEquipmentPreview(
            String id,
            Bounds bounds,
            String capeId,
            BackEquipmentPreviewRenderer.Mode mode) {
        public BackEquipmentPreview {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(bounds, "bounds");
            Objects.requireNonNull(capeId, "capeId");
            Objects.requireNonNull(mode, "mode");
            if (id.isBlank() || capeId.isBlank()) {
                throw new IllegalArgumentException("back-equipment preview ids must not be blank");
            }
        }
    }

    public record Scrollbar(Bounds track, Bounds thumb, int offset, int maximum, Orientation orientation) {
        public Scrollbar(Bounds track, Bounds thumb, int offset, int maximum) {
            this(track, thumb, offset, maximum, Orientation.HORIZONTAL);
        }

        public Scrollbar {
            Objects.requireNonNull(track, "track");
            Objects.requireNonNull(thumb, "thumb");
            if (offset < 0 || maximum <= 0 || offset > maximum) {
                throw new IllegalArgumentException("invalid scrollbar range");
            }
            Objects.requireNonNull(orientation, "orientation");
        }

        public enum Orientation {
            HORIZONTAL,
            VERTICAL
        }
    }

    public record ScrollSurface(
            String id,
            Bounds viewport,
            Scrollbar.Orientation orientation,
            double offsetPixels,
            double maximumPixels,
            double wheelStepPixels) {
        public ScrollSurface(
                String id,
                Bounds viewport,
                Scrollbar.Orientation orientation,
                double offsetPixels,
                double maximumPixels) {
            this(id, viewport, orientation, offsetPixels, maximumPixels, 32.0);
        }

        public ScrollSurface {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(viewport, "viewport");
            Objects.requireNonNull(orientation, "orientation");
            if (id.isBlank()) {
                throw new IllegalArgumentException("scroll surface id must not be blank");
            }
            if (!Double.isFinite(offsetPixels)
                    || !Double.isFinite(maximumPixels)
                    || !Double.isFinite(wheelStepPixels)
                    || offsetPixels < 0.0
                    || maximumPixels < 0.0
                    || offsetPixels > maximumPixels
                    || wheelStepPixels <= 0.0) {
                throw new IllegalArgumentException("invalid scroll surface range");
            }
        }
    }
}

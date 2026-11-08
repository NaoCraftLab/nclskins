package com.naocraftlab.skins.runtime;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;


public final class ViewHostPolicy {
    public static UiMessage transparentLabel(ViewSpec.Widget widget) {
        return widget.id().startsWith("gallery.preset.") && widget.id().endsWith(".more")
                ? UiMessage.literal(widget.value().filter("down"::equals).isPresent() ? "▼" : "▲", UiMessage.Severity.INFO)
                : widget.label();
    }

    public static int transparentTextY(ViewSpec.Widget widget, int lineHeight) {
        return widget.bounds().y()+Math.max(0,(widget.bounds().height()-lineHeight)/2)
                +(widget.id().startsWith("gallery.preset.") && widget.id().endsWith(".more") ? 2 : 0);
    }

    public static ViewSpec.Text galleryNameText(ViewSpec view, ViewSpec.Text text, int arrowAdvance) {
        if (!view.screenId().equals("gallery") || !text.id().startsWith("gallery.preset.")
                || !text.id().endsWith(".name") || text.marqueeActivation().isEmpty()) return text;
        var activation=text.marqueeActivation().orElseThrow();
        var card=activation.hoverBounds();
        int left=card.right()-6-Math.max(0,arrowAdvance)/2;
        int right=Math.max(text.bounds().x()+1,Math.min(card.right(),left-(card.right()-left-Math.max(0,arrowAdvance))));
        var bounds=new Bounds(text.bounds().x(),text.bounds().y(),right-text.bounds().x(),text.bounds().height());
        var moving=new Bounds(card.x(),bounds.y(),right-card.x(),bounds.height());
        return new ViewSpec.Text(text.id(),bounds,text.message(),text.alignment(),Optional.of(
                new ViewSpec.MarqueeActivation(card,activation.focusWidgetIds(),Optional.of(moving))),text.layout());
    }

    public static Optional<ViewSpec.Scrollbar> scrollbar(ViewSpec view, String surfaceId) {
        if (!surfaceId.equals("gallery.stage")) return view.scrollbar();
        return view.scrollSurface(surfaceId).filter(surface -> surface.maximumPixels()>0).map(surface -> {
            Bounds viewport=surface.viewport();
            int right=view.panels().stream().filter(panel -> panel.id().equals("gallery.workspace"))
                    .mapToInt(panel -> panel.bounds().x()).findFirst().orElse(viewport.right());
            Bounds track=new Bounds(right-6,viewport.y(),6,viewport.height());
            int height=Math.min(track.height(),Math.max(12,(int)(track.height()*track.height()/(track.height()+surface.maximumPixels()))));
            int top=track.y()+(int)Math.round((track.height()-height)*surface.offsetPixels()/surface.maximumPixels());
            return new ViewSpec.Scrollbar(track,new Bounds(track.x(),top,6,height),(int)Math.round(surface.offsetPixels()),(int)Math.ceil(surface.maximumPixels()),ViewSpec.Scrollbar.Orientation.VERTICAL);
        });
    }

    public static List<ViewSpec.Scrollbar> scrollbars(ViewSpec view) {
        var bars=new java.util.ArrayList<ViewSpec.Scrollbar>();
        view.scrollbar().filter(bar -> !view.screenId().equals("gallery") || bar.maximum()>0).ifPresent(bars::add);
        if (view.screenId().equals("gallery")) scrollbar(view,"gallery.stage").ifPresent(bars::add);
        return List.copyOf(bars);
    }

    public static Optional<ViewSpec.ScrollSurface> nativeScrollSurface(ViewSpec view, Optional<String> current) {
        return current.flatMap(view::scrollSurface).or(() -> view.scrollSurfaces().stream().findFirst());
    }

    private static final List<String> PROVIDER_ROW_WIDGET_KINDS =
            List.of("row", "up", "down", "edit", "remove", "account");

    public static boolean belongsToProviderRow(String elementId, String provider) {
        if (elementId == null || provider == null || provider.isEmpty()
                || provider.indexOf('.') >= 0) {
            return false;
        }
        for (String kind : PROVIDER_ROW_WIDGET_KINDS) {
            if (elementId.equals("providers." + kind + "." + provider)) {
                return true;
            }
        }
        String capabilityPrefix = "providers.capability." + provider + ".";
        if (!elementId.startsWith(capabilityPrefix)) {
            return false;
        }
        String capability = elementId.substring(capabilityPrefix.length());
        return !capability.isEmpty() && capability.indexOf('.') < 0;
    }

    public static boolean visibleIntersection(ViewSpec view, String elementId) {
        ViewSpec.Widget widget = view.widget(elementId).orElseThrow();
        return view.clipFor(elementId).map(clip ->
                widget.bounds().x() < clip.right()
                        && widget.bounds().right() > clip.x()
                        && widget.bounds().y() < clip.bottom()
                        && widget.bounds().bottom() > clip.y()).orElse(true);
    }

    public static boolean pointerInsideClip(
            ViewSpec view, String elementId, double pointerX, double pointerY) {
        Objects.requireNonNull(view, "view");
        Objects.requireNonNull(elementId, "elementId");
        return view.clipFor(elementId)
                .map(bounds -> bounds.contains(pointerX, pointerY))
                .orElse(true);
    }

    public static Optional<ViewSpec.Widget> pointerOwnerAt(
            ViewSpec view, double pointerX, double pointerY) {
        Objects.requireNonNull(view, "view");
        ViewSpec.Widget owner = null;
        for (ViewSpec.Widget widget : view.widgets()) {
            if (widget.visible()
                    && widget.bounds().contains(pointerX, pointerY)
                    && pointerInsideClip(view, widget.id(), pointerX, pointerY)) {
                owner = widget;
            }
        }
        return Optional.ofNullable(owner);
    }

    public static Optional<PassiveIndicatorTooltip> passiveIndicatorTooltip(
            ViewSpec view, double pointerX, double pointerY, String focusedWidgetId) {
        Objects.requireNonNull(view, "view");
        Optional<ViewSpec.Widget> hovered = pointerOwnerAt(view, pointerX, pointerY)
                .filter(widget -> widget.kind() == ViewSpec.WidgetKind.PASSIVE_INDICATOR || widget.id().equals("gallery.inspect.CPM"))
                .filter(ViewSpec.Widget::enabled)
                .filter(widget -> visibleIntersection(view, widget.id()));
        if (hovered.isPresent()) {
            return hovered.map(widget -> new PassiveIndicatorTooltip(widget, true));
        }
        return Optional.ofNullable(focusedWidgetId)
                .flatMap(view::widget)
                .filter(widget -> widget.kind() == ViewSpec.WidgetKind.PASSIVE_INDICATOR || widget.id().equals("gallery.inspect.CPM"))
                .filter(ViewSpec.Widget::visible)
                .filter(ViewSpec.Widget::enabled)
                .filter(widget -> visibleIntersection(view, widget.id()))
                .map(widget -> new PassiveIndicatorTooltip(widget, false));
    }

    public record PassiveIndicatorTooltip(ViewSpec.Widget widget, boolean hovered) {
        public List<UiMessage> lines() {
            UiMessage title = widget.label();
            return widget.hint().filter(description -> !description.equals(title))
                    .map(description -> List.of(title, description))
                    .orElseGet(() -> List.of(title));
        }

        public int x(double pointerX) {
            return hovered ? (int) pointerX : widget.bounds().x() + widget.bounds().width() / 2;
        }

        public int y(double pointerY) {
            return hovered ? (int) pointerY : widget.bounds().y() + widget.bounds().height() / 2;
        }
    }

    public static float passiveIndicatorOpacity(WidgetIcon icon, boolean highlighted) {
        Objects.requireNonNull(icon, "icon");
        if (icon == GuiIcon.STATUS_COMPATIBILITY_EXTENDED
                || icon == GuiIcon.STATUS_COMPATIBILITY_INCOMPATIBLE) return 1.0F;
        return highlighted ? 1.0F : 0.65F;
    }

    public static boolean showMissingValueIcon(ViewSpec.IconDecoration decoration) {
        return Objects.requireNonNull(decoration, "decoration").providerTexture().isEmpty();
    }

    public static Optional<String> inlineCapePointerActionAt(
            ViewSpec view, double pointerX, double pointerY) {
        return pointerOwnerAt(view, pointerX, pointerY)
                .filter(ViewSpec.Widget::enabled)
                .filter(widget -> widget.id().startsWith("editor.cape_action."))
                .filter(widget -> widget.kind() == ViewSpec.WidgetKind.BUTTON
                        || widget.kind() == ViewSpec.WidgetKind.ICON_BUTTON
                        || widget.kind() == ViewSpec.WidgetKind.ICON_ONLY_BUTTON)
                .map(ViewSpec.Widget::id);
    }

    public static boolean compositeCardHovered(
            ViewSpec view, String widgetId, double pointerX, double pointerY) {
        Objects.requireNonNull(view, "view");
        Objects.requireNonNull(widgetId, "widgetId");
        return view.widget(widgetId)
                .filter(widget -> widget.kind() == ViewSpec.WidgetKind.CATALOG_CARD)
                .filter(ViewSpec.Widget::visible)
                .filter(widget -> widget.bounds().contains(pointerX, pointerY))
                .filter(widget -> pointerInsideClip(
                        view, widget.id(), pointerX, pointerY))
                .isPresent();
    }

    public static String playerCapeKey(ViewSpec.Preview preview, UUID accountId) {
        Objects.requireNonNull(preview, "preview");
        return "player:" + Objects.requireNonNull(accountId, "accountId") + ":" + preview.id();
    }

    public static String cardCapeKey(ViewSpec.BackEquipmentPreview preview) {
        return "card:" + preview.id() + ":" + preview.capeId();
    }

    public static String decorationCapeKey(ViewSpec.IconDecoration decoration, UUID accountId) {
        Objects.requireNonNull(decoration, "decoration");
        return "decoration:" + Objects.requireNonNull(accountId, "accountId") + ":" + decoration.id();
    }

    public static boolean capeCardHovered(ViewSpec view, ViewSpec.BackEquipmentPreview preview,
            double pointerX, double pointerY) {
        int centerX = preview.bounds().x() + preview.bounds().width() / 2;
        int centerY = preview.bounds().y() + preview.bounds().height() / 2;
        return view.widgets().stream()
                .filter(widget -> widget.kind() == ViewSpec.WidgetKind.CAPE_CARD)
                .filter(widget -> widget.bounds().contains(centerX, centerY))
                .anyMatch(widget -> hoverInside(view, widget, pointerX, pointerY));
    }

    public static boolean providerCapeRowHovered(ViewSpec view, ViewSpec.IconDecoration decoration,
            double pointerX, double pointerY) {
        return view.widget(decoration.ownerWidgetId())
                .filter(widget -> widget.kind() == ViewSpec.WidgetKind.SELECTABLE_CARD)
                .filter(widget -> hoverInside(view, widget, pointerX, pointerY))
                .isPresent();
    }

    private static boolean hoverInside(ViewSpec view, ViewSpec.Widget widget,
            double pointerX, double pointerY) {
        return widget.visible() && visibleIntersection(view, widget.id())
                && widget.bounds().contains(pointerX, pointerY)
                && pointerInsideClip(view, widget.id(), pointerX, pointerY);
    }

    public static Optional<String> submitAction(
            ViewSpec view,
            String focusedWidgetId,
            boolean nativeFieldFocused,
            String nativeValue) {
        Objects.requireNonNull(view, "view");
        if (focusedWidgetId == null || !nativeFieldFocused || nativeValue == null
                || nativeValue.trim().isEmpty()) {
            return Optional.empty();
        }
        Optional<String> actionId = view.widget(focusedWidgetId)
                .filter(widget -> widget.kind() == ViewSpec.WidgetKind.TEXT_FIELD)
                .filter(ViewSpec.Widget::enabled)
                .filter(ViewSpec.Widget::visible)
                .flatMap(ViewSpec.Widget::submitActionId);
        return actionId.filter(id -> view.widget(id)
                .filter(ViewSpec.Widget::visible)
                .filter(ViewSpec.Widget::enabled)
                .isPresent());
    }

    public static boolean shouldSelectAllOnFocusAcquire(
            ViewSpec view,
            String widgetId,
            FocusCause cause,
            boolean wasFocused,
            boolean nativeFieldFocused,
            String nativeValue) {
        Objects.requireNonNull(view, "view");
        Objects.requireNonNull(cause, "cause");
        return cause != FocusCause.RESTORE
                && !wasFocused
                && nativeFieldFocused
                && nativeValue != null
                && !nativeValue.isEmpty()
                && view.widget(widgetId)
                .filter(widget -> widget.kind() == ViewSpec.WidgetKind.TEXT_FIELD)
                .filter(ViewSpec.Widget::selectAllOnFocusAcquire)
                .filter(ViewSpec.Widget::enabled)
                .filter(ViewSpec.Widget::visible)
                .isPresent();
    }

    public enum FocusCause {
        POINTER,
        KEYBOARD,
        PROGRAMMATIC,
        RESTORE
    }

    public static List<WidgetShape> widgetShapes(ViewSpec view) {
        Objects.requireNonNull(view, "view");
        return view.widgets().stream().map(WidgetShape::new).toList();
    }

    public record WidgetShape(
            String id,
            ViewSpec.WidgetKind kind,
            Optional<WidgetIcon> icon,
            boolean visible,
            int maxLength,
            boolean selectAllOnFocusAcquire,
            Optional<String> submitActionId) {
        public WidgetShape(ViewSpec.Widget widget) {
            this(
                    widget.id(),
                    widget.kind(),
                    widget.icon(),
                    widget.visible(),
                    widget.maxLength(),
                    widget.selectAllOnFocusAcquire(),
                    widget.submitActionId());
        }
    }

    public static Optional<com.naocraftlab.skins.client.PreviewRenderer.PreviewRequest> previewRequest(
            ViewSpec.Preview preview,
            Optional<com.naocraftlab.skins.client.PreviewRenderer.PreviewAppearance> loaded,
            Optional<com.naocraftlab.skins.client.PreviewRenderer.PreviewAppearance> borrowed) {
        var appearance = preview.requiresLoadedSkin() ? loaded : borrowed;
        Bounds anchor = preview.anchorBounds();
        Bounds stage = preview.bounds();
        return appearance.map(value -> new com.naocraftlab.skins.client.PreviewRenderer.PreviewRequest(
                value, anchor.x(), anchor.y(), anchor.width(), anchor.height(),
                preview.yawDegrees(), preview.pitchDegrees(), preview.scale(), preview.intent(),
                stage.x(), stage.y(), stage.width(), stage.height()));
    }

    public static boolean screenOwnedBody(String screenId, ViewSpec.Preview preview) {
        if (preview.intent() != com.naocraftlab.skins.client.PreviewRenderer.PreviewIntent.EDITOR_DRAFT) return false;
        return preview.bodySource().map(source -> switch (source.role()) {
            case EDITOR, PROVIDERS, GALLERY_MAIN -> true;
        }).orElseGet(() -> "preset_editor".equals(screenId) || "providers".equals(screenId));
    }

    public static Optional<Bounds> paintClip(ViewSpec view, String elementId) {
        var body = view.previews().stream().filter(preview -> preview.id().equals(elementId))
                .filter(preview -> preview.bodySource().filter(source -> source.role() == ViewSpec.BodyRole.GALLERY_MAIN).isPresent())
                .findFirst();
        return body.map(ViewSpec.Preview::bounds).or(() -> view.clipFor(elementId));
    }

    public static int panelOverlayColor(ViewSpec.Panel.Style style) {
        return switch (style) {
            case TRANSLUCENT_NAMEBAR -> 0x88000000;
            default -> 0;
        };
    }

    public static int panelOverlayColor(String screenId, ViewSpec.Panel.Style style) {
        return "gallery".equals(screenId) && style == ViewSpec.Panel.Style.TRANSLUCENT_NAMEBAR
                ? 0x6D000000 : panelOverlayColor(style);
    }

    public static List<Integer> cardFrameColors(ViewSpec.Widget widget, boolean focused) {
        int state = cardFrameColor(widget, false);
        if (focused) return List.of(state == 0 ? CatalogCardStyle.FOCUS_FRAME_SHADOW_COLOR : state,
                CatalogCardStyle.FOCUS_FRAME_COLOR);
        return state == 0 ? List.of() : List.of(state);
    }

    public record CardFrameSegment(Bounds bounds, int color) {}

    public static List<CardFrameSegment> galleryCapePaint(ViewSpec view, ViewSpec.Widget widget,
            double pointerX, double pointerY, boolean focused) {
        var paint=new java.util.ArrayList<CardFrameSegment>();
        if (hoverInside(view,widget,pointerX,pointerY)) paint.add(new CardFrameSegment(widget.bounds(),CatalogCardStyle.HIGHLIGHT_BACKGROUND_COLOR));
        paint.addAll(cardFrameSegments(widget,focused));
        return List.copyOf(paint);
    }

    public static List<CardFrameSegment> cardFrameSegments(ViewSpec.Widget widget, boolean focused) {
        var segments = new java.util.ArrayList<CardFrameSegment>();
        var colors = cardFrameColors(widget, focused);
        Bounds b = widget.bounds();
        for (int inset = 0; inset < colors.size(); inset++) {
            int color = colors.get(inset);
            int leading = inset == 0 && widget.value().filter("selected_active"::equals).isPresent()
                    ? 0xFF80C880 : color;
            int left = b.x() + inset;
            int top = b.y() + inset;
            int width = b.width() - inset * 2;
            int height = b.height() - inset * 2;
            if (width <= 0 || height <= 0) continue;
            segments.add(new CardFrameSegment(new Bounds(left, top, width, 1), leading));
            segments.add(new CardFrameSegment(new Bounds(left, top, 1, height), leading));
            segments.add(new CardFrameSegment(new Bounds(left, top + height - 1, width, 1), color));
            segments.add(new CardFrameSegment(new Bounds(left + width - 1, top, 1, height), color));
        }
        return List.copyOf(segments);
    }

    public static int cardFrameColor(ViewSpec.Widget widget, boolean focused) {
        if (focused) return 0xFFFFFFFF;
        return widget.value().map(value -> switch (value) {
            case "selected", "selected_active" -> 0xFF80C8FF;
            case "active" -> 0xFF80C880;
            case "menu" -> 0xFFA0A0A0;
            default -> 0;
        }).orElse(0);
    }

    private ViewHostPolicy() {
    }
}

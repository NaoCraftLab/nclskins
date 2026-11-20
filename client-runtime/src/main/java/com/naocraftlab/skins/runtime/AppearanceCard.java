package com.naocraftlab.skins.runtime;

import com.naocraftlab.skins.client.BackEquipmentPreviewRenderer;
import com.naocraftlab.skins.client.OuterLayerVisibility;
import com.naocraftlab.skins.client.PreviewRenderer;
import com.naocraftlab.skins.core.compatibility.SkinCompatibility;
import com.naocraftlab.skins.core.model.SkinReference;
import com.naocraftlab.skins.core.model.SkinVariant;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.ToIntBiFunction;

final class AppearanceCard {
    static final float SKIN_THUMBNAIL_SCALE = 0.88F;
    static final int CAPE_PREVIEW_HEIGHT = 50;
    static final int CAPE_PREVIEW_VERTICAL_INSET = 8;

    record Chrome(ViewSpec.Panel surface, ViewSpec.Widget widget) {}

    record Skin(SkinReference reference, String revision, SkinVariant variant, Optional<String> capeId,
                PreviewRenderer.CapeMode capeMode, OuterLayerVisibility outerLayers,
                float yaw, float pitch, float scale, Optional<UUID> presetId,
                Optional<ViewSpec.CatalogImage> catalogImage, Optional<ViewSpec.ExternalImage> externalImage,
                PreviewRenderer.PreviewIntent intent, boolean capeHasElytra) {
        ViewSpec.Preview present(String id, Bounds slot) {
            return new ViewSpec.Preview(id, slot, slot, reference, revision, variant, capeId, capeMode,
                    outerLayers, yaw, pitch, scale, presetId, catalogImage, externalImage, intent, capeHasElytra);
        }
    }

    record Cape(String texture, BackEquipmentPreviewRenderer.Mode mode, boolean hasElytra) {
        ViewSpec.BackEquipmentPreview present(String id, Bounds slot) {
            return new ViewSpec.BackEquipmentPreview(id, slot, texture, mode, hasElytra);
        }

        ViewSpec.BackEquipmentPreview presentCatalog(String id, Bounds card) {
            return present(id, catalogSlot(card)).asCatalogCard();
        }

        static Bounds catalogSlot(Bounds card) {
            int inset = Math.min(2, (card.width() - 1) / 2);
            return new Bounds(card.x() + inset, card.y() + CAPE_PREVIEW_VERTICAL_INSET,
                    card.width() - 2 * inset, CAPE_PREVIEW_HEIGHT);
        }
    }

    record Action(String id, UiMessage label, WidgetIcon icon, boolean enabled) {
        ViewSpec.Widget present(Bounds bounds) {
            return ViewSpec.Widget.iconButton(id, bounds, label, icon, enabled);
        }
    }

    record Rename(String id, UiMessage label, String value, UiMessage hint, boolean enabled, String submitId) {}

    static Chrome chrome(String surfaceId, String widgetId, Bounds bounds, ViewSpec.WidgetKind kind,
                         UiMessage label, Optional<String> value, boolean enabled) {
        return new Chrome(new ViewSpec.Panel(surfaceId, bounds, ViewSpec.Panel.Style.VANILLA_LIST),
                new ViewSpec.Widget(widgetId, kind, bounds, label, value, Optional.empty(), enabled, true, 0));
    }

    static ViewSpec.Text name(String id, Bounds bounds, UiMessage label, Bounds card, List<String> focusIds) {
        return new ViewSpec.Text(id, bounds, label, ViewSpec.Text.Alignment.CENTER,
                Optional.of(new ViewSpec.MarqueeActivation(card, focusIds)));
    }

    static Bounds compactNameBounds(Bounds card, boolean expanded, boolean mutable) {
        return new Bounds(card.x() + 4, expanded ? card.y() + 5 : card.bottom() - 12,
                Math.max(1, card.width() - (mutable ? 18 : 8)), 10);
    }

    static ViewSpec.Panel compactNamebar(String id, Bounds card, boolean expanded) {
        return new ViewSpec.Panel(id, expanded
                ? new Bounds(card.x(), card.y() + 2, card.width(), card.height() - 4)
                : new Bounds(card.x(), card.bottom() - 16, card.width(), 16),
                expanded ? ViewSpec.Panel.Style.COMPACT_MENU_SCRIM : ViewSpec.Panel.Style.COMPACT_NAMEBAR);
    }

    static ViewSpec.Text compactName(String id, UiMessage label, Bounds card, boolean expanded,
                                     boolean mutable, List<String> focusIds) {
        Bounds bounds = compactNameBounds(card, expanded, mutable);
        return new ViewSpec.Text(id, bounds, label, ViewSpec.Text.Alignment.LEFT,
                Optional.of(new ViewSpec.MarqueeActivation(card, focusIds,
                        Optional.of(new Bounds(card.x(), bounds.y(), Math.max(1, card.width() - (mutable ? 14 : 0)), 10)))));
    }

    static ViewSpec.Widget compactToggle(String id, Bounds card, boolean expanded, boolean enabled) {
        return new ViewSpec.Widget(id, ViewSpec.WidgetKind.TRANSPARENT_BUTTON,
                new Bounds(card.x(), expanded ? card.y() : card.bottom() - 16, card.width(), 16),
                UiMessage.info("nclskins.gallery.actions"), Optional.of(expanded ? "down" : "up"),
                Optional.empty(), enabled, true, 0);
    }

    static ViewSpec.Widget compactTitleControl(String id, Bounds card, UiMessage label, boolean enabled) {
        return new ViewSpec.Widget(id + ".title_control", ViewSpec.WidgetKind.TRANSPARENT_BUTTON,
                new Bounds(card.x(), card.bottom() - 16, card.width(), 16),
                label, Optional.of("passive_title"), Optional.empty(), enabled, true, 0)
                .withActivation(false, null);
    }

    static List<ViewSpec.Widget> compactActions(Bounds card, Optional<Rename> rename, Action left, Action right) {
        var widgets = new ArrayList<ViewSpec.Widget>();
        rename.ifPresent(field -> widgets.add(ViewSpec.Widget.textField(field.id(), CatalogCardGeometry.renameField(card),
                field.label(), field.value(), field.hint(), field.enabled(), 128, true, Optional.of(field.submitId()))));
        var row = CatalogCardGeometry.menuRow(card, compactActionY(card));
        widgets.add(left.present(row.left()));
        widgets.add(right.present(row.right()));
        return List.copyOf(widgets);
    }

    static ViewSpec.Text compactPrompt(String id, Bounds card, boolean rename, UiMessage message,
                                       ToIntBiFunction<UiMessage, Integer> wrappedHeight) {
        int width = Math.max(1, card.width() - 4);
        int top = rename ? CatalogCardGeometry.renameField(card).bottom() : card.y() + 16;
        int available = Math.max(1, compactActionY(card) - top);
        int height = Math.min(available, Math.max(1, wrappedHeight.applyAsInt(message, width)));
        return new ViewSpec.Text(id, new Bounds(card.x() + 2, top + (available - height) / 2, width, height),
                message, ViewSpec.Text.Alignment.CENTER, ViewSpec.Text.Layout.WRAP);
    }

    private static int compactActionY(Bounds card) {
        return card.bottom() - 22;
    }

    static ViewSpec.TooltipRegion info(String id, Bounds bounds, UiMessage label,
                                      ViewSpec.Text.Alignment alignment, String info) {
        return new ViewSpec.TooltipRegion(id, bounds, label, alignment, UiMessage.literal(info, UiMessage.Severity.INFO));
    }

    static ViewSpec.Widget compatibility(String id, Bounds card, int bottom, SkinCompatibility compatibility) {
        return ViewSpec.Widget.compatibilityIndicator(id, new Bounds(card.x() + 2, bottom - 20, 20, 20),
                CompatibilityMessages.accessibleLabel(compatibility), CompatibilityMessages.icon(compatibility));
    }

    static ViewSpec.IconDecoration service(String id, Bounds slot, GuiIcon icon, String owner) {
        return new ViewSpec.IconDecoration(id, CatalogCardGeometry.serviceIcon(slot), icon, owner, 0.8F, 1.0F);
    }

    private AppearanceCard() {}
}

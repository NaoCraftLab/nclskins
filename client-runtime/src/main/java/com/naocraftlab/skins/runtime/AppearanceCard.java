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

final class AppearanceCard {
    enum Role {
        PERSONAL, READ_ONLY;

        int height(int personalHeight) {
            return this == PERSONAL ? personalHeight : CatalogCardGeometry.readOnlyHeight(personalHeight);
        }

        Bounds preview(Bounds card) {
            return this == PERSONAL ? CatalogCardGeometry.previewWithActions(card) : CatalogCardGeometry.preview(card);
        }
    }

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

    static List<ViewSpec.Widget> actions(Bounds card, Optional<Rename> rename, Action left, Action right) {
        var widgets = new ArrayList<ViewSpec.Widget>();
        rename.ifPresent(field -> widgets.add(ViewSpec.Widget.textField(field.id(), CatalogCardGeometry.renameField(card),
                field.label(), field.value(), field.hint(), field.enabled(), 128, true, Optional.of(field.submitId()))));
        CatalogCardGeometry.ActionPair geometry = CatalogCardGeometry.personalActions(card);
        widgets.add(left.present(geometry.left()));
        widgets.add(right.present(geometry.right()));
        return List.copyOf(widgets);
    }

    private AppearanceCard() {}
}

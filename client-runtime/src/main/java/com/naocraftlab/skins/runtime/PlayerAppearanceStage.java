package com.naocraftlab.skins.runtime;

import com.naocraftlab.skins.client.PreviewRenderer;
import com.naocraftlab.skins.core.model.SkinReference;
import com.naocraftlab.skins.core.model.SkinVariant;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

final class PlayerAppearanceStage {
    record Appearance(SkinReference skin, String revision, SkinVariant variant, Optional<String> capeId,
                      Optional<UUID> presetId, Optional<ViewSpec.CatalogImage> catalogImage,
                      boolean capeHasElytra, PreviewRenderer.PreviewIntent intent) {}

    record Control(String id, UiMessage label, GuiIcon icon, boolean enabled) {}

    static ViewSpec.Preview present(String id, int width, int height, Bounds anchor,
                                    Appearance appearance, PreviewInteractionModel transform) {
        return new ViewSpec.Preview(id, new Bounds(0, 0, width, height), anchor,
                appearance.skin(), appearance.revision(), appearance.variant(), appearance.capeId(),
                appearance.capeId().isPresent() ? transform.capeMode() : PreviewRenderer.CapeMode.OFF,
                transform.outerLayerVisibility(), transform.yawDegrees(), transform.pitchDegrees(), transform.scale(),
                appearance.presetId(), appearance.catalogImage(), appearance.intent())
                .withCapeElytra(appearance.capeHasElytra());
    }

    static List<ViewSpec.Widget> controls(Bounds anchor, Optional<Control> capeMode, List<Control> outerLayers) {
        var widgets = new ArrayList<ViewSpec.Widget>();
        int x = anchor.x() + 2;
        capeMode.ifPresent(control -> widgets.add(widget(control, new Bounds(x, 35, 20, 20))));
        int contentHeight = Math.max(0, anchor.height() - 66);
        int stackHeight = outerLayers.size() * 20 + Math.max(0, outerLayers.size() - 1) * 2;
        int y = 33 + Math.max(0, (contentHeight - stackHeight) / 2);
        for (int index = 0; index < outerLayers.size(); index++) {
            widgets.add(widget(outerLayers.get(index), new Bounds(x, y + index * 22, 20, 20)));
        }
        return List.copyOf(widgets);
    }

    static Control capeMode(String id, PreviewInteractionModel transform, boolean enabled) {
        boolean elytra = transform.capeMode() == PreviewRenderer.CapeMode.ELYTRA;
        return new Control(id, UiMessage.info(elytra ? "item.minecraft.elytra" : "options.modelPart.cape"),
                elytra ? GuiIcon.APPEARANCE_BACK_ELYTRA : GuiIcon.APPEARANCE_BACK_CAPE, enabled);
    }

    private static ViewSpec.Widget widget(Control control, Bounds bounds) {
        return ViewSpec.Widget.iconOnlyButton(control.id(), bounds, control.label(), control.icon(), control.enabled());
    }

    private PlayerAppearanceStage() {}
}

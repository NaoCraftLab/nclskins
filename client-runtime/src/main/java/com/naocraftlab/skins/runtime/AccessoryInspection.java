package com.naocraftlab.skins.runtime;

import com.naocraftlab.skins.client.PreviewAccessories;
import com.naocraftlab.skins.client.PreviewRenderer;
import java.util.ArrayList;
import java.util.Objects;
import java.util.Optional;

final class AccessoryInspection {
    static boolean current(PreviewAccessories previous, PreviewAccessories next) {
        return next.canInspect() && Objects.equals(previous.scope(), next.scope())
                && previous.generation() == next.generation();
    }

    static UiMessage reason(PreviewAccessories value) {
        if (value.liveReason() != PreviewRenderer.LiveReason.AVAILABLE) return UiMessage.info(switch (value.liveReason()) {
            case WORLD_REQUIRED -> "nclskins.inspection.world_required";
            case LOADING -> "nclskins.inspection.loading";
            default -> "nclskins.inspection.live_unavailable";
        });
        if (value.canInspect()) return null;
        return UiMessage.info(switch (value.availability()) {
            case UNKNOWN -> "nclskins.inspection.unknown";
            case PENDING -> "nclskins.inspection.loading";
            case FAILED -> "nclskins.inspection.failed";
            case BLOCKED -> "nclskins.inspection.blocked";
            case CONFIRMED -> "nclskins.inspection.live_unavailable";
        });
    }

    static ViewSpec present(ViewSpec view, PreviewAccessories capability, boolean nativePreview,
            boolean summaryNative, PreviewRenderer.CapeMode mode, boolean busy) {
        var widgets = new ArrayList<ViewSpec.Widget>();
        for (var widget : view.widgets()) {
            if (widget.id().equals("providers.row.CPM"))
                widget = widget.withActivation(capability.canInspect(), reason(capability));
            widgets.add(widget);
        }
        String prefix = view.screenId().equals("providers") ? "providers" : "editor";
        if (nativePreview && widgets.stream().noneMatch(w -> w.id().equals(prefix + ".preview_mode"))) {
            widgets.add(ViewSpec.Widget.iconOnlyButton(prefix + ".preview_mode", new Bounds(2, 35, 20, 20),
                    UiMessage.info(mode == PreviewRenderer.CapeMode.ELYTRA ? "item.minecraft.elytra" : "options.modelPart.cape"),
                    mode == PreviewRenderer.CapeMode.ELYTRA ? GuiIcon.APPEARANCE_BACK_ELYTRA : GuiIcon.APPEARANCE_BACK_CAPE, !busy));
        }
        var texts = new ArrayList<>(view.texts());
        var icons = new ArrayList<>(view.iconDecorations().stream().map(icon -> {
            if (summaryNative && icon.ownerWidgetId().equals("providers.tab.CAPE"))
                return new ViewSpec.IconDecoration(icon.id(), icon.bounds(), GuiIcon.SOURCE_CPM,
                        icon.ownerWidgetId(), icon.idleOpacity(), icon.activeOpacity(), Optional.empty());
            if (!icon.ownerWidgetId().equals("providers.row.CPM")) return icon;
            GuiIcon glyph = capability.availability() == PreviewAccessories.Availability.CONFIRMED && !capability.present()
                    ? GuiIcon.PROVIDER_CAPE_NO_VALUE : GuiIcon.SOURCE_CPM;
            return new ViewSpec.IconDecoration(icon.id(), icon.bounds(), glyph, icon.ownerWidgetId(),
                    icon.idleOpacity(), icon.activeOpacity(), Optional.empty());
        }).toList());
        var navigation = new ArrayList<ViewSpec.NavigationNode>();
        for (var node : view.navigationNodes()) {
            boolean blocked = node.id().equals("providers.row.CPM") && !capability.canInspect();
            navigation.add(new ViewSpec.NavigationNode(node.id(), node.bounds(), node.surfaceId(),
                    node.documentOrder(), node.tabOrder(), node.enabled(), node.pattern(),
                    blocked ? Optional.empty() : node.activationActionId()));
        }
        for (var widget : widgets) {
            if (navigation.stream().noneMatch(node -> node.id().equals(widget.id())))
                navigation.add(ViewSpec.NavigationNode.control(widget, navigation.size(), navigation.size()));
        }
        var previews = view.previews().stream().map(preview -> nativePreview
                ? preview.withNativeAccessories(true, mode) : preview).toList();
        return new ViewSpec(view.screenId(), view.title(), view.width(), view.height(), view.panels(), texts,
                widgets, previews, view.scrollbar(), view.tabGroups(), view.focusRequest(), view.clipRegions(),
                view.backEquipmentPreviews(), icons, view.scrollSurfaces(), view.tooltipRegions(),
                view.progressDecorations(), navigation);
    }

    private AccessoryInspection() {}
}

package com.naocraftlab.skins.runtime;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

final class CapeCatalogPresenter {
    static int errorHeight(CapeCatalogModel model, int width) {
        return model.importError() != null
                ? 4 + model.textResolver().wrappedHeight(model.importError(), Math.max(1, width))
                : 0;
    }

    static int contentHeight(CapeCatalogModel model, int columns, int cardHeight) {
        return layout(model, columns, cardHeight).contentHeight();
    }

    static CardPosition selectedPosition(CapeCatalogModel model, int columns, int cardHeight) {
        AppearanceCollections.Layout layout = layout(model, columns, cardHeight);
        for (var section : layout.sections()) {
            List<CapeCatalogModel.Card> cards = model.matches(section.section().id());
            if (section.section().collapsed()) continue;
            for (int index = 0; index < cards.size(); index++) {
                if (selected(model, cards.get(index))) {
                    Bounds bounds = section.card(index, 0, 0, 1);
                    return new CardPosition(bounds.y(), bounds.height());
                }
            }
        }
        return new CardPosition(0, cardHeight);
    }

    static void present(
            CapeCatalogModel model,
            Bounds viewport,
            int cardWidth,
            int cardHeight,
            int columns,
            double scroll,
            boolean busy,
            com.naocraftlab.skins.client.BackEquipmentPreviewRenderer.Mode mode,
            List<ViewSpec.Widget> widgets,
            List<ViewSpec.Panel> panels,
            List<ViewSpec.Text> texts,
            List<ViewSpec.BackEquipmentPreview> previews,
            List<ViewSpec.IconDecoration> icons,
            List<ViewSpec.ClipRegion> clips,
            List<ViewSpec.NavigationNode> navigation) {
        present(model, viewport, cardWidth, cardHeight, columns, scroll, busy, mode, widgets,
                panels, texts, previews, icons, clips, new ArrayList<>(), navigation);
    }

    static void present(
            CapeCatalogModel model,
            Bounds viewport,
            int cardWidth,
            int cardHeight,
            int columns,
            double scroll,
            boolean busy,
            com.naocraftlab.skins.client.BackEquipmentPreviewRenderer.Mode mode,
            List<ViewSpec.Widget> widgets,
            List<ViewSpec.Panel> panels,
            List<ViewSpec.Text> texts,
            List<ViewSpec.BackEquipmentPreview> previews,
            List<ViewSpec.IconDecoration> icons,
            List<ViewSpec.ClipRegion> clips,
            List<ViewSpec.TooltipRegion> tooltips,
            List<ViewSpec.NavigationNode> navigation) {
        int toolbarY = viewport.y() - 28 - errorHeight(model, viewport.width());
        int disclosureX = viewport.right() - 20;
        int filterWidth = Math.min(92, Math.max(50, viewport.width() / 3));
        int filterX = disclosureX - 4 - filterWidth;
        widgets.add(ViewSpec.Widget.textField(
                "editor.cape_search",
                new Bounds(viewport.x(), toolbarY, Math.max(1, filterX - viewport.x() - 4), 20),
                UiMessage.info("nclskins.capes.search"),
                model.query(),
                UiMessage.info("nclskins.add_source.search_hint"),
                !busy,
                128,
                true,
                Optional.empty()));
        widgets.add(ViewSpec.Widget.button(
                "editor.cape_filter",
                new Bounds(filterX, toolbarY, filterWidth, 20),
                UiMessage.info(switch (model.filter()) {
                    case 1 -> "nclskins.capes.with_elytra";
                    case 2 -> "nclskins.capes.without_elytra";
                    default -> "nclskins.add_source.filter_all";
                }),
                !busy));
        boolean expand = model.visibleCollections().stream().anyMatch(model.collapsed()::contains);
        widgets.add(ViewSpec.Widget.iconButton(
                "editor.cape_disclosure",
                new Bounds(disclosureX, toolbarY, 20, 20),
                UiMessage.info(expand
                        ? "nclskins.collection.expand_all"
                        : "nclskins.collection.collapse_all"),
                expand ? GuiIcon.ACTION_EXPAND_ALL : GuiIcon.ACTION_COLLAPSE_ALL,
                !busy));
        if (model.importError() != null) {
            texts.add(new ViewSpec.Text(
                    "editor.cape_error",
                    new Bounds(viewport.x(), toolbarY + 26, viewport.width(), errorHeight(model, viewport.width()) - 4),
                    model.importError(),
                    ViewSpec.Text.Alignment.CENTER,
                    ViewSpec.Text.Layout.WRAP));
        }
        clips.add(AppearanceCollections.clip(
                "editor.capes",
                viewport,
                List.of("editor.cape_item.", "editor.cape_surface.", "editor.cape_header.", "editor.cape_action.")));

        AppearanceCollections.Layout layout = layout(model, columns, cardHeight);
        int scrollOffset = (int) Math.round(scroll);
        int ordinal = 2;
        int tabOrder = 4;
        for (var section : layout.sections()) {
            String collectionId = section.section().id();
            String headerId = "editor.cape_header." + collectionId;
            Bounds header = section.header(viewport.x(), viewport.y() - scrollOffset, viewport.width());
            var headerPresentation = AppearanceCollections.header(headerId, header,
                    UiMessage.info("nclskins.capes.collection",
                            model.collapsed().contains(collectionId) ? "▶" : "▼", model.collectionLabel(collectionId)),
                    !busy, viewport, "editor.capes", ordinal++, tabOrder++);
            navigation.add(headerPresentation.navigation());
            headerPresentation.widget().ifPresent(widgets::add);
            if (headerPresentation.widget().isPresent()) {
                model.collectionInfo(collectionId).ifPresent(info -> tooltips.add(AppearanceCard.info(
                        "editor.cape_tooltip.collection." + collectionId,
                        new Bounds(header.x() + 12, header.y() + 3, Math.max(1, header.width() - 12), 10),
                        model.collectionLabel(collectionId), ViewSpec.Text.Alignment.LEFT, info)));
            }
            if (section.section().collapsed()) continue;
            List<CapeCatalogModel.Card> cards = model.matches(collectionId);
            for (int index = 0; index < cards.size(); index++) {
                CapeCatalogModel.Card card = cards.get(index);
                Bounds bounds = section.card(index, viewport.x(), viewport.y() - scrollOffset, cardWidth);
                String id = card.widgetId();
                navigation.add(AppearanceCollections.navigation(
                        id,
                        bounds,
                        "editor.capes",
                        ordinal++,
                        tabOrder++,
                        !busy,
                        ViewSpec.NavigationPattern.GRID,
                        Optional.of(id)));
                List<ViewSpec.Widget> actions = personalActions(model, card, bounds, busy);
                for (ViewSpec.Widget action : actions) {
                    ViewSpec.NavigationNode node = ViewSpec.NavigationNode.control(action, ordinal++, tabOrder++);
                    navigation.add(new ViewSpec.NavigationNode(
                            node.id(), node.bounds(), Optional.of("editor.capes"), node.documentOrder(),
                            node.tabOrder(), node.enabled(), node.pattern(), node.activationActionId()));
                }
                if (!AppearanceCollections.visibleVertically(bounds, viewport)) {
                    continue;
                }
                String surface = "editor.cape_surface." + collectionId + "." + card.key();
                var chrome = AppearanceCard.chrome(surface, id, bounds, ViewSpec.WidgetKind.CAPE_CARD,
                        card.label(), Optional.of(model.selected(card) ? "selected" : "unselected"), !busy);
                panels.add(chrome.surface());
                widgets.add(chrome.widget());
                boolean editing = card.local() != null
                        && card.local().entryId().equals(model.editing());
                if (!(editing && !model.deleting()) && !card.importCard()) {
                    Bounds nameBounds = CatalogCardGeometry.name(bounds);
                    texts.add(AppearanceCard.name(surface + ".name", nameBounds, card.label(), bounds, List.of(id)));
                    card.info().ifPresent(info -> tooltips.add(AppearanceCard.info(
                            "editor.cape_tooltip.card." + collectionId + "." + card.key(),
                            nameBounds, card.label(), ViewSpec.Text.Alignment.CENTER, info)));
                }
                AppearanceCard.Role role = collectionId.equals("OFFLINE")
                        ? AppearanceCard.Role.PERSONAL : AppearanceCard.Role.READ_ONLY;
                Bounds previewBounds = role.preview(bounds);
                if (card.texture().isPresent()) {
                    previews.add(new AppearanceCard.Cape(card.texture().orElseThrow(), mode,
                            !Boolean.FALSE.equals(card.hasElytra())).present(surface + ".preview", previewBounds));
                } else {
                    icons.add(AppearanceCard.service(surface + ".icon", previewBounds,
                            card.importCard() ? GuiIcon.ACTION_ADD_CAPE : GuiIcon.APPEARANCE_CAPE_NONE, id));
                }
                widgets.addAll(actions);
            }
        }
    }

    private static List<ViewSpec.Widget> personalActions(
            CapeCatalogModel model, CapeCatalogModel.Card card, Bounds bounds, boolean busy) {
        if (card.local() == null) return List.of();
        String key = card.local().entryId().toString();
        boolean editing = card.local().entryId().equals(model.editing());
        Optional<AppearanceCard.Rename> rename = editing && !model.deleting()
                ? Optional.of(new AppearanceCard.Rename("editor.cape_action.name", UiMessage.info("nclskins.capes.rename"),
                        model.renameValue(), UiMessage.info("nclskins.your_skins.rename_hint"), !busy,
                        "editor.cape_action.save." + key)) : Optional.empty();
        AppearanceCard.Action left;
        AppearanceCard.Action right;
        if (editing) {
            left = new AppearanceCard.Action("editor.cape_action." + (model.deleting() ? "confirm." : "save.") + key,
                    UiMessage.info(model.deleting() ? "nclskins.capes.delete" : "nclskins.editor.save"),
                    NativeGuiIcon.ACCEPT, !busy && (model.deleting() || !model.renameValue().trim().isEmpty()));
            right = new AppearanceCard.Action("editor.cape_action.cancel." + key, UiMessage.info("gui.cancel"),
                    NativeGuiIcon.REJECT, !busy);
        } else {
            left = new AppearanceCard.Action("editor.cape_action.rename." + key, UiMessage.info("nclskins.capes.rename"),
                    GuiIcon.ACTION_RENAME, !busy && model.editing() == null);
            right = new AppearanceCard.Action("editor.cape_action.delete." + key, UiMessage.info("nclskins.capes.delete"),
                    GuiIcon.ACTION_DELETE, !busy && model.editing() == null);
        }
        return AppearanceCard.actions(bounds, rename, left, right);
    }

    private static AppearanceCollections.Layout layout(CapeCatalogModel model, int columns, int cardHeight) {
        List<AppearanceCollections.Section> sections = new ArrayList<>();
        for (String collectionId : model.visibleCollections()) {
            int count = model.matches(collectionId).size();
            if (count == 0) continue;
            AppearanceCard.Role role = collectionId.equals("OFFLINE")
                    ? AppearanceCard.Role.PERSONAL : AppearanceCard.Role.READ_ONLY;
            sections.add(new AppearanceCollections.Section(collectionId, count,
                    model.collapsed().contains(collectionId), role.height(cardHeight)));
        }
        return AppearanceCollections.layout(sections, columns);
    }

    private static boolean selected(CapeCatalogModel model, CapeCatalogModel.Card card) {
        return model.inspected() != null
                ? model.inspected().equals(card)
                : model.selected(card) && !card.service();
    }

    record CardPosition(int top, int height) {
    }

    private CapeCatalogPresenter() {
    }
}

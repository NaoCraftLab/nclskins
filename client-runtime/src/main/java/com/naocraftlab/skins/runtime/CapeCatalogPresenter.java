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
        present(model, viewport, cardWidth, cardHeight, columns, scroll, busy, mode, widgets, panels, texts, previews,
                icons, clips, tooltips, navigation, false, java.util.Set.of());
    }

    static void present(CapeCatalogModel model, Bounds viewport, int cardWidth, int cardHeight, int columns,
                        double scroll, boolean busy, com.naocraftlab.skins.client.BackEquipmentPreviewRenderer.Mode mode,
                        List<ViewSpec.Widget> widgets, List<ViewSpec.Panel> panels, List<ViewSpec.Text> texts,
                        List<ViewSpec.BackEquipmentPreview> previews, List<ViewSpec.IconDecoration> icons,
                        List<ViewSpec.ClipRegion> clips, List<ViewSpec.TooltipRegion> tooltips,
                        List<ViewSpec.NavigationNode> navigation, boolean existing, java.util.Set<String> savedAssignments) {
        int toolbarY = viewport.y() - 28 - errorHeight(model, viewport.width());
        int disclosureX = viewport.right() - 20;
        int filterWidth = Math.min(92, Math.max(50, viewport.width() / 3));
        int filterX = disclosureX - 4 - filterWidth;
        widgets.add(ViewSpec.Widget.iconButton("editor.cape_import",
                new Bounds(viewport.x(), toolbarY, 20, 20), UiMessage.info("nclskins.capes.import"),
                GuiIcon.ACTION_ADD_LOOK, !busy && model.providers().cape().enabled(com.naocraftlab.skins.core.provider.BuiltinProvider.OFFLINE)));
        widgets.add(ViewSpec.Widget.textField(
                "editor.cape_search",
                new Bounds(viewport.x() + 24, toolbarY, Math.max(1, filterX - viewport.x() - 28), 20),
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
                List.of("editor.cape_item.", "editor.cape_surface.", "editor.cape_header.", "editor.cape_action.", "editor.cape_tooltip.")));

        AppearanceCollections.Layout layout = layout(model, columns, cardHeight);
        int scrollOffset = (int) Math.round(scroll);
        int ordinal = 2;
        int tabOrder = 5;
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
                boolean expanded = card.local() != null && card.local().entryId().equals(model.editing());
                boolean renaming = expanded && !model.menuOnly() && !model.deleting();
                navigation.add(AppearanceCollections.navigation(
                        id,
                        bounds,
                        "editor.capes",
                        ordinal++,
                        tabOrder++,
                        !busy && !expanded,
                        ViewSpec.NavigationPattern.GRID,
                        card.embedded() && !model.canInspect(card) ? Optional.empty() : Optional.of(id)));
                List<ViewSpec.Widget> actions = personalActions(model, card, bounds, busy);
                ViewSpec.Widget toggle = card.local() != null && !renaming
                        ? AppearanceCard.compactToggle("editor.cape_action.more." + card.local().entryId(), bounds, expanded, !busy) : null;
                if (toggle != null) actions = java.util.stream.Stream.concat(java.util.stream.Stream.of(toggle), actions.stream()).toList();
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
                var chrome = AppearanceCard.chrome(surface, id, bounds, ViewSpec.WidgetKind.CATALOG_CARD,
                        card.label(), savedAssignments.contains(id) || card.embedded() && model.assigned(card) ? Optional.of("active")
                                : model.assigned(card) ? Optional.of(existing ? "selected" : "active")
                                : expanded ? Optional.of("menu") : Optional.empty(), !busy && !expanded);
                panels.add(chrome.surface());
                widgets.add(card.provider() == com.naocraftlab.skins.core.provider.BuiltinProvider.CPM ? chrome.widget().withActivation(model.canInspect(card),
                        AccessoryInspection.reason(model.accessories())) : chrome.widget());
                if (expanded && model.deleting()) {
                    var prompt = AppearanceCard.compactPrompt(surface + ".delete_prompt", bounds, false,
                            UiMessage.info("nclskins.capes.delete_confirmation"), model.textResolver()::wrappedHeight);
                    if (AppearanceCollections.visibleVertically(prompt.bounds(), viewport)) texts.add(prompt);
                }
                boolean noCape = card.service() && !card.importCard();
                if (!noCape) {
                    var namebar = AppearanceCard.compactNamebar(surface + ".namebar", bounds, expanded);
                    if (AppearanceCollections.visibleVertically(namebar.bounds(), viewport)) panels.add(namebar);
                }
                if (!renaming && !noCape) {
                    Bounds nameBounds = AppearanceCard.compactNameBounds(bounds, expanded, card.local() != null);
                    texts.add(AppearanceCard.compactName(surface + ".name", card.label(), bounds, expanded,
                            card.local() != null, toggle == null ? List.of(id) : List.of(id, toggle.id())));
                    card.info().ifPresent(info -> tooltips.add(AppearanceCard.info(
                            "editor.cape_tooltip.card." + collectionId + "." + card.key(),
                            nameBounds, card.label(), ViewSpec.Text.Alignment.LEFT, info)));
                }
                if (!expanded && card.texture().isPresent()) {
                    previews.add(new AppearanceCard.Cape(card.texture().orElseThrow(), mode,
                            !Boolean.FALSE.equals(card.hasElytra())).presentCatalog(surface + ".preview", bounds));
                } else if (!expanded) {
                    icons.add(AppearanceCard.service(surface + ".icon", noCape ? bounds : AppearanceCard.Cape.catalogSlot(bounds),
                            card.embedded() ? GuiIcon.SOURCE_CPM : card.importCard() ? GuiIcon.ACTION_ADD_CAPE : GuiIcon.APPEARANCE_CAPE_NONE, id));
                }
                widgets.addAll(actions);
            }
        }
    }

    private static List<ViewSpec.Widget> personalActions(
            CapeCatalogModel model, CapeCatalogModel.Card card, Bounds bounds, boolean busy) {
        if (card.local() == null || !card.local().entryId().equals(model.editing())) return List.of();
        String key = card.local().entryId().toString();
        Optional<AppearanceCard.Rename> rename = !model.menuOnly() && !model.deleting()
                ? Optional.of(new AppearanceCard.Rename("editor.cape_action.name", UiMessage.info("nclskins.capes.rename"),
                        model.renameValue(), UiMessage.info("nclskins.your_skins.rename_hint"), !busy,
                        "editor.cape_action.save." + key)) : Optional.empty();
        AppearanceCard.Action left;
        AppearanceCard.Action right;
        if (!model.menuOnly()) {
            left = new AppearanceCard.Action("editor.cape_action." + (model.deleting() ? "confirm." : "save.") + key,
                    UiMessage.info(model.deleting() ? "nclskins.capes.delete" : "nclskins.editor.save"),
                    NativeGuiIcon.ACCEPT, !busy && (model.deleting() || !model.renameValue().trim().isEmpty()));
            right = new AppearanceCard.Action("editor.cape_action.cancel." + key, UiMessage.info("gui.cancel"),
                    NativeGuiIcon.REJECT, !busy);
        } else {
            left = new AppearanceCard.Action("editor.cape_action.rename." + key, UiMessage.info("nclskins.capes.rename"),
                    GuiIcon.ACTION_RENAME, !busy);
            right = new AppearanceCard.Action("editor.cape_action.delete." + key, UiMessage.info("nclskins.capes.delete"),
                    GuiIcon.ACTION_DELETE, !busy);
        }
        return AppearanceCard.compactActions(bounds, rename, left, right);
    }

    private static AppearanceCollections.Layout layout(CapeCatalogModel model, int columns, int cardHeight) {
        List<AppearanceCollections.Section> sections = new ArrayList<>();
        for (String collectionId : model.visibleCollections()) {
            int count = model.matches(collectionId).size();
            if (count == 0) continue;
            sections.add(new AppearanceCollections.Section(collectionId, count,
                    model.collapsed().contains(collectionId), cardHeight));
        }
        return AppearanceCollections.compactLayout(sections, columns);
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

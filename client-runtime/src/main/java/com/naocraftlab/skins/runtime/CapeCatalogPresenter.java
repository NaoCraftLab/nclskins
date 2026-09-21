package com.naocraftlab.skins.runtime;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

final class CapeCatalogPresenter {
    private static final int COLLECTION_HEADER_HEIGHT = CollectionGridLayout.COLLECTION_HEADER_HEIGHT;

    static int errorHeight(CapeCatalogModel model, int width) {
        return model.importError() != null
                ? 4 + model.textResolver().wrappedHeight(model.importError(), Math.max(1, width))
                : 0;
    }

    static int contentHeight(CapeCatalogModel model, int columns, int cardHeight) {
        return layout(model, columns, cardHeight).contentHeight();
    }

    static CardPosition selectedPosition(CapeCatalogModel model, int columns, int cardHeight) {
        CapeCatalogLayout layout = layout(model, columns, cardHeight);
        for (SectionLayout section : layout.sections()) {
            for (RowLayout row : section.rows()) {
                for (int index = row.start(); index < row.end(); index++) {
                    if (selected(model, section.cards().get(index))) {
                        return new CardPosition(row.top(), row.height());
                    }
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
        clips.add(new ViewSpec.ClipRegion(
                "editor.capes",
                viewport,
                List.of("editor.cape_item.", "editor.cape_surface.", "editor.cape_header.", "editor.cape_action.")));

        CapeCatalogLayout layout = layout(model, columns, cardHeight);
        int scrollOffset = (int) Math.round(scroll);
        int ordinal = 2;
        int tabOrder = 4;
        for (SectionLayout section : layout.sections()) {
            String collectionId = section.collectionId();
            String headerId = "editor.cape_header." + collectionId;
            Bounds header = new Bounds(
                    viewport.x(),
                    viewport.y() + section.headerTop() - scrollOffset,
                    viewport.width(),
                    COLLECTION_HEADER_HEIGHT);
            navigation.add(ViewSpec.NavigationNode.card(
                    headerId,
                    header,
                    "editor.capes",
                    ordinal++,
                    tabOrder++,
                    !busy,
                    ViewSpec.NavigationPattern.GRID,
                    Optional.of(headerId)));
            if (visible(header, viewport)) {
                widgets.add(ViewSpec.Widget.collectionHeader(
                        headerId,
                        header,
                        UiMessage.info(
                                "nclskins.capes.collection",
                                model.collapsed().contains(collectionId) ? "▶" : "▼",
                                model.collectionLabel(collectionId)),
                        !busy,
                        false));
                model.collectionInfo(collectionId).ifPresent(info -> tooltips.add(new ViewSpec.TooltipRegion(
                        "editor.cape_tooltip.collection." + collectionId,
                        new Bounds(header.x() + 12, header.y() + 3, Math.max(1, header.width() - 12), 10),
                        model.collectionLabel(collectionId),
                        ViewSpec.Text.Alignment.LEFT,
                        UiMessage.literal(info, UiMessage.Severity.INFO))));
            }
            for (RowLayout row : section.rows()) {
                for (int index = row.start(); index < row.end(); index++) {
                    CapeCatalogModel.Card card = section.cards().get(index);
                    Bounds bounds = new Bounds(
                            viewport.x() + (index - row.start()) * (cardWidth + CatalogCardSizing.GAP),
                            viewport.y() + row.top() - scrollOffset,
                            cardWidth,
                            row.height());
                    String id = card.widgetId();
                    navigation.add(ViewSpec.NavigationNode.card(
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
                    if (!visible(bounds, viewport)) {
                        continue;
                    }
                    String surface = "editor.cape_surface." + collectionId + "." + card.key();
                    panels.add(new ViewSpec.Panel(surface, bounds, ViewSpec.Panel.Style.VANILLA_LIST));
                    widgets.add(new ViewSpec.Widget(
                            id,
                            ViewSpec.WidgetKind.CAPE_CARD,
                            bounds,
                            card.label(),
                            Optional.of(model.selected(card) ? "selected" : "unselected"),
                            Optional.empty(),
                            !busy,
                            true,
                            0));
                    boolean editing = card.local() != null
                            && card.local().entryId().equals(model.editing());
                    if (!(editing && !model.deleting()) && !card.importCard()) {
                        Bounds nameBounds = CatalogCardGeometry.name(bounds);
                        texts.add(new ViewSpec.Text(
                                surface + ".name",
                                nameBounds,
                                card.label(),
                                ViewSpec.Text.Alignment.CENTER,
                                Optional.of(new ViewSpec.MarqueeActivation(bounds, List.of(id)))));
                        card.info().ifPresent(info -> tooltips.add(new ViewSpec.TooltipRegion(
                                "editor.cape_tooltip.card." + collectionId + "." + card.key(),
                                nameBounds,
                                card.label(),
                                ViewSpec.Text.Alignment.CENTER,
                                UiMessage.literal(info, UiMessage.Severity.INFO))));
                    }
                    boolean personal = collectionId.equals("OFFLINE");
                    Bounds previewBounds = personal
                            ? CatalogCardGeometry.previewWithActions(bounds)
                            : CatalogCardGeometry.preview(bounds);
                    if (card.texture().isPresent()) {
                        previews.add(new ViewSpec.BackEquipmentPreview(
                                surface + ".preview",
                                previewBounds,
                                card.texture().orElseThrow(),
                                mode,
                                !Boolean.FALSE.equals(card.hasElytra())));
                    } else {
                        icons.add(new ViewSpec.IconDecoration(
                                surface + ".icon",
                                CatalogCardGeometry.serviceIcon(previewBounds),
                                card.importCard() ? GuiIcon.ACTION_ADD_CAPE : GuiIcon.APPEARANCE_CAPE_NONE,
                                id,
                                0.8F,
                                1.0F));
                    }
                    widgets.addAll(actions);
                }
            }
        }
    }

    private static List<ViewSpec.Widget> personalActions(
            CapeCatalogModel model, CapeCatalogModel.Card card, Bounds bounds, boolean busy) {
        if (card.local() == null) return List.of();
        String key = card.local().entryId().toString();
        boolean editing = card.local().entryId().equals(model.editing());
        List<ViewSpec.Widget> actions = new ArrayList<>();
        if (editing && !model.deleting()) {
            actions.add(ViewSpec.Widget.textField(
                    "editor.cape_action.name", CatalogCardGeometry.renameField(bounds),
                    UiMessage.info("nclskins.capes.rename"), model.renameValue(),
                    UiMessage.info("nclskins.your_skins.rename_hint"), !busy, 128, true,
                    Optional.of("editor.cape_action.save." + key)));
        }
        CatalogCardGeometry.ActionPair geometry = editing
                ? CatalogCardGeometry.renameActions(bounds) : CatalogCardGeometry.personalActions(bounds);
        if (editing) {
            actions.add(ViewSpec.Widget.button(
                    "editor.cape_action." + (model.deleting() ? "confirm." : "save.") + key,
                    geometry.left(), UiMessage.info(model.deleting()
                            ? "nclskins.capes.delete" : "nclskins.editor.save"),
                    !busy && (model.deleting() || !model.renameValue().trim().isEmpty())));
            actions.add(ViewSpec.Widget.button(
                    "editor.cape_action.cancel." + key, geometry.right(), UiMessage.info("gui.cancel"), !busy));
        } else {
            actions.add(ViewSpec.Widget.iconButton(
                    "editor.cape_action.rename." + key, geometry.left(), UiMessage.info("nclskins.capes.rename"),
                    GuiIcon.ACTION_RENAME, !busy && model.editing() == null));
            actions.add(ViewSpec.Widget.iconButton(
                    "editor.cape_action.delete." + key, geometry.right(), UiMessage.info("nclskins.capes.delete"),
                    GuiIcon.ACTION_DELETE, !busy && model.editing() == null));
        }
        return actions;
    }

    private static CapeCatalogLayout layout(CapeCatalogModel model, int columns, int cardHeight) {
        List<SectionLayout> sections = new ArrayList<>();
        int y = 0;
        for (String collectionId : model.visibleCollections()) {
            List<CapeCatalogModel.Card> cards = model.matches(collectionId);
            if (cards.isEmpty()) {
                continue;
            }
            int headerTop = y;
            y += COLLECTION_HEADER_HEIGHT + CollectionGridLayout.COLLECTION_HEADER_GAP;
            List<RowLayout> rows = new ArrayList<>();
            if (!model.collapsed().contains(collectionId)) {
                int rowHeight = collectionId.equals("OFFLINE")
                        ? cardHeight : CatalogCardGeometry.readOnlyHeight(cardHeight);
                for (int start = 0; start < cards.size(); start += columns) {
                    int end = Math.min(cards.size(), start + columns);
                    rows.add(new RowLayout(start, end, y, rowHeight));
                    y += rowHeight + CatalogCardSizing.GAP;
                }
                y += CollectionGridLayout.COLLECTION_BOTTOM_PADDING;
            }
            sections.add(new SectionLayout(collectionId, cards, headerTop, rows));
        }
        return new CapeCatalogLayout(sections, y);
    }

    private static boolean selected(CapeCatalogModel model, CapeCatalogModel.Card card) {
        return model.inspected() != null
                ? model.inspected().equals(card)
                : model.selected(card) && !card.service();
    }

    private static boolean visible(Bounds bounds, Bounds viewport) {
        return bounds.bottom() > viewport.y() && bounds.y() < viewport.bottom();
    }

    private record CapeCatalogLayout(List<SectionLayout> sections, int contentHeight) {
        private CapeCatalogLayout {
            sections = List.copyOf(sections);
        }
    }

    private record SectionLayout(
            String collectionId,
            List<CapeCatalogModel.Card> cards,
            int headerTop,
            List<RowLayout> rows) {
        private SectionLayout {
            cards = List.copyOf(cards);
            rows = List.copyOf(rows);
        }
    }

    private record RowLayout(int start, int end, int top, int height) {
    }

    record CardPosition(int top, int height) {
    }

    private CapeCatalogPresenter() {
    }
}

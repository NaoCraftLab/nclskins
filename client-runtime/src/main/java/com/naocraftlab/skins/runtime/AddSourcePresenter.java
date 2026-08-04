package com.naocraftlab.skins.runtime;

import com.naocraftlab.skins.client.CatalogCollectionOrder;
import com.naocraftlab.skins.client.OuterLayerVisibility;
import com.naocraftlab.skins.client.PreviewRenderer;
import com.naocraftlab.skins.client.SkinCatalogSource;
import com.naocraftlab.skins.core.model.AddSourceTab;
import com.naocraftlab.skins.core.model.SkinReference;
import com.naocraftlab.skins.core.model.SkinVariant;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;


public final class AddSourcePresenter {
    private static final int CONTROLS_TOP = 31;
    private static final int CONTENT_TOP = 58;
    private static final int FOOTER_HEIGHT = 33;
    private static final int CARD_GAP = 6;
    private static final int MAX_CARD_HEIGHT = 132;
    private static final int MIN_CARD_HEIGHT = 72;
    private static final int COLLECTION_HEADER_HEIGHT = 16;

    public ViewSpec present(AddSourceModel model, boolean busy, int width, int height) {
        return present(model, busy, Optional.empty(), width, height);
    }

    public ViewSpec present(
            AddSourceModel model,
            boolean busy,
            Optional<UiMessage> operationStatus,
            int width,
            int height) {
        return present(model, busy, operationStatus, width, height, Optional.empty());
    }

    public ViewSpec present(
            AddSourceModel model,
            boolean busy,
            Optional<UiMessage> operationStatus,
            int width,
            int height,
            Optional<PersonalSkinRename> personalRename) {
        Objects.requireNonNull(model, "model");
        operationStatus = Objects.requireNonNull(operationStatus, "operationStatus");
        personalRename = Objects.requireNonNull(personalRename, "personalRename");
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("view dimensions must be positive");
        }
        if (model.personalSkinDeletion().isPresent()) {
            return presentPersonalSkinDeletion(model, busy, operationStatus, width, height);
        }

        List<ViewSpec.Panel> panels = new ArrayList<>();
        List<ViewSpec.Text> texts = new ArrayList<>();
        List<ViewSpec.Widget> widgets = new ArrayList<>();
        List<ViewSpec.Preview> previews = new ArrayList<>();
        List<ViewSpec.ClipRegion> clipRegions = new ArrayList<>();
        panels.add(new ViewSpec.Panel(
                "add.header", new Bounds(0, 0, width, 24), ViewSpec.Panel.Style.VANILLA_HEADER));
        panels.add(new ViewSpec.Panel(
                "add.footer",
                new Bounds(0, Math.max(0, height - FOOTER_HEIGHT), width, FOOTER_HEIGHT),
                ViewSpec.Panel.Style.VANILLA_FOOTER));

        List<ViewSpec.Tab> tabs = List.of(
                new ViewSpec.Tab(
                        "add.tab.catalog",
                        UiMessage.info("nclskins.add_source.catalog_tab"),
                        model.selectedTab() == AddSourceTab.CATALOG,
                        !busy),
                new ViewSpec.Tab(
                        "add.tab.file",
                        UiMessage.info("nclskins.add_source.file_tab"),
                        model.selectedTab() == AddSourceTab.FILE,
                        !busy));

        Optional<ViewSpec.Scrollbar> scrollbar = Optional.empty();
        List<ViewSpec.ScrollSurface> scrollSurfaces = List.of();
        if (model.selectedTab() == AddSourceTab.FILE) {
            int contentWidth = Math.min(520, Math.max(220, width - 32));
            int contentX = (width - contentWidth) / 2;
            int actionWidth = Math.min(96, Math.max(76, contentWidth / 3));
            widgets.add(ViewSpec.Widget.button(
                    "add.file.choose",
                    new Bounds(contentX, 42, contentWidth, 20),
                    UiMessage.info("nclskins.add_source.choose_file"),
                    !busy));
            texts.add(new ViewSpec.Text(
                    "add.player.label",
                    new Bounds(contentX, 72, contentWidth, 10),
                    UiMessage.info("nclskins.add_source.player_label"),
                    ViewSpec.Text.Alignment.LEFT));
            widgets.add(ViewSpec.Widget.textField(
                    "add.player.input",
                    new Bounds(contentX, 84, contentWidth - actionWidth - 6, 20),
                    UiMessage.info("nclskins.add_source.player_label"),
                    model.playerInput(),
                    UiMessage.info("nclskins.add_source.player_hint"),
                    !busy,
                    36,
                    true,
                    Optional.of("add.player.load")));
            widgets.add(ViewSpec.Widget.button(
                    "add.player.load",
                    new Bounds(contentX + contentWidth - actionWidth, 84, actionWidth, 20),
                    UiMessage.info("nclskins.add_source.find_player"),
                    !busy && !model.playerInput().isBlank()));
            texts.add(new ViewSpec.Text(
                    "add.url.label",
                    new Bounds(contentX, 114, contentWidth, 10),
                    UiMessage.info("nclskins.add_source.url_label"),
                    ViewSpec.Text.Alignment.LEFT));
            widgets.add(ViewSpec.Widget.textField(
                    "add.url.input",
                    new Bounds(contentX, 126, contentWidth - actionWidth - 6, 20),
                    UiMessage.info("nclskins.add_source.url_label"),
                    model.urlInput(),
                    UiMessage.info("nclskins.add_source.url_hint"),
                    !busy,
                    2048,
                    true,
                    Optional.of("add.url.load")));
            widgets.add(ViewSpec.Widget.button(
                    "add.url.load",
                    new Bounds(contentX + contentWidth - actionWidth, 126, actionWidth, 20),
                    UiMessage.info("nclskins.add_source.download"),
                    !busy && !model.urlInput().isBlank()));
            texts.add(new ViewSpec.Text(
                    "add.url.privacy",
                    new Bounds(contentX, 151, contentWidth, 20),
                    UiMessage.info("nclskins.add_source.url_privacy"),
                    ViewSpec.Text.Alignment.LEFT));
            operationStatus.ifPresent(status -> texts.add(new ViewSpec.Text(
                    "add.import.status",
                    new Bounds(contentX, Math.min(height - 48, 176), contentWidth, 10),
                    status,
                    ViewSpec.Text.Alignment.CENTER)));
        } else {
            int filterWidth = Math.min(104, Math.max(72, width / 4));
            int controlsWidth = Math.max(120, width - 32);
            widgets.add(ViewSpec.Widget.textField(
                    "add.catalog.search",
                    new Bounds(16, CONTROLS_TOP, Math.max(40, controlsWidth - filterWidth - 6), 20),
                    UiMessage.info("nclskins.add_source.search"),
                    model.query(),
                    UiMessage.info("nclskins.add_source.search_hint"),
                    !busy,
                    128,
                    true,
                    Optional.empty()));
            widgets.add(ViewSpec.Widget.button(
                    "add.catalog.filter",
                    new Bounds(width - 16 - filterWidth, CONTROLS_TOP, filterWidth, 20),
                    filterLabel(model.filter()),
                    !busy));

            CatalogLayout layout = catalogLayout(model, width, height);
            addCatalogContent(model, busy, layout, panels, texts, widgets, previews, personalRename);
            clipRegions.add(new ViewSpec.ClipRegion(
                    "add.catalog.viewport",
                    new Bounds(0, CONTENT_TOP, width, Math.max(1, layout.contentBottom() - CONTENT_TOP)),
                    List.of(
                            "add.catalog.collection:",
                            "add.catalog.collection_info:",
                            "add.catalog.skin:",
                            "add.catalog.skin_info:",
                            "add.catalog.rename:",
                            "add.catalog.rename.",
                            "add.catalog.delete:")));
            scrollbar = layout.scrollbar();
            if (layout.maximum() > 0) {
                scrollSurfaces = List.of(new ViewSpec.ScrollSurface(
                        "add.catalog",
                        new Bounds(0, CONTENT_TOP, width, Math.max(1, layout.contentBottom() - CONTENT_TOP)),
                        ViewSpec.Scrollbar.Orientation.VERTICAL,
                        Math.min(model.scrollOffset(), layout.maximum()),
                        layout.maximum()));
            }
            if (layout.matchingSkinCount() == 0) {
                texts.add(new ViewSpec.Text(
                        "add.catalog.empty",
                        new Bounds(16, Math.max(CONTENT_TOP + 12, height / 2), Math.max(1, width - 32), 10),
                        UiMessage.info("nclskins.add_source.no_results"),
                        ViewSpec.Text.Alignment.CENTER));
            }
        }

        int cancelWidth = Math.min(200, Math.max(100, width - 32));
        widgets.add(ViewSpec.Widget.button(
                "add.cancel",
                new Bounds((width - cancelWidth) / 2, Math.max(0, height - 28), cancelWidth, 20),
                UiMessage.info("gui.cancel"),
                !busy || model.selectedTab() == AddSourceTab.FILE));

        Optional<ViewSpec.FocusRequest> focus = focusRequest(model);
        return new ViewSpec(
                "add_source",
                UiMessage.info("nclskins.add_source.title"),
                width,
                height,
                panels,
                texts,
                widgets,
                previews,
                scrollbar,
                List.of(new ViewSpec.TabGroup("add.tabs", new Bounds(0, 0, width, 24), tabs)),
                focus,
                clipRegions,
                List.of(),
                List.of(),
                scrollSurfaces);
    }

    private static ViewSpec presentPersonalSkinDeletion(
            AddSourceModel model,
            boolean busy,
            Optional<UiMessage> operationStatus,
            int width,
            int height) {
        AddSourceModel.PersonalSkinDeletion deletion =
                model.personalSkinDeletion().orElseThrow();
        int dialogWidth = Math.min(300, Math.max(220, width - 32));
        int dialogX = (width - dialogWidth) / 2;
        int buttonWidth = Math.max(90, (dialogWidth - 6) / 2);
        int buttonY = Math.max(0, height - 28);
        List<ViewSpec.Panel> panels = List.of(
                new ViewSpec.Panel(
                        "personal_delete.header",
                        new Bounds(0, 0, width, 33),
                        ViewSpec.Panel.Style.VANILLA_HEADER),
                new ViewSpec.Panel(
                        "personal_delete.footer",
                        new Bounds(0, Math.max(0, height - FOOTER_HEIGHT), width, FOOTER_HEIGHT),
                        ViewSpec.Panel.Style.VANILLA_FOOTER));
        List<ViewSpec.Text> texts = new ArrayList<>();
        texts.add(new ViewSpec.Text(
                        "personal_delete.title",
                        new Bounds(0, 8, width, 12),
                        UiMessage.info("nclskins.your_skins.delete_title"),
                        ViewSpec.Text.Alignment.CENTER));
        texts.add(new ViewSpec.Text(
                        "personal_delete.question",
                        new Bounds(dialogX, Math.max(48, height / 2 - 16), dialogWidth, 10),
                        UiMessage.info("nclskins.your_skins.delete_question", deletion.displayName()),
                        ViewSpec.Text.Alignment.CENTER));
        texts.add(new ViewSpec.Text(
                        "personal_delete.note",
                        new Bounds(dialogX, Math.max(61, height / 2), dialogWidth, 10),
                        UiMessage.info("nclskins.your_skins.delete_note"),
                        ViewSpec.Text.Alignment.CENTER));
        operationStatus.filter(status -> status.severity() == UiMessage.Severity.ERROR)
                .ifPresent(status -> texts.add(new ViewSpec.Text(
                        "personal_delete.status",
                        new Bounds(dialogX, Math.max(74, height / 2 + 16), dialogWidth, 10),
                        status,
                        ViewSpec.Text.Alignment.CENTER)));
        List<ViewSpec.Widget> widgets = List.of(
                ViewSpec.Widget.button(
                        "add.catalog.delete.confirm",
                        new Bounds(dialogX, buttonY, buttonWidth, 20),
                        UiMessage.info("nclskins.your_skins.delete_confirm"),
                        !busy),
                ViewSpec.Widget.button(
                        "add.catalog.delete.cancel",
                        new Bounds(dialogX + dialogWidth - buttonWidth, buttonY, buttonWidth, 20),
                        UiMessage.info("gui.cancel"),
                        !busy));
        return new ViewSpec(
                "personal_skin_delete",
                UiMessage.info("nclskins.your_skins.delete_title"),
                width,
                height,
                panels,
                texts,
                widgets,
                List.of(),
                Optional.empty(),
                List.of(),
                focusRequest(model));
    }

    private static Optional<ViewSpec.FocusRequest> focusRequest(AddSourceModel model) {
        if (model.focusWidgetId().isEmpty() || model.focusToken().isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new ViewSpec.FocusRequest(
                model.focusWidgetId().orElseThrow(), model.focusToken().orElseThrow()));
    }

    public int maximumScroll(AddSourceModel model, int width, int height) {
        return catalogLayout(model, width, height).maximum();
    }

    public int normalizedScrollOffset(
            AddSourceModel model, int width, int height, int desired) {
        CatalogLayout layout = catalogLayout(model, width, height);
        return Math.max(0, Math.min(layout.maximum(), desired));
    }

    public int offsetFromScrollbar(
            AddSourceModel model, int width, int height, double desiredThumbTop) {
        CatalogLayout layout = catalogLayout(model, width, height);
        if (layout.maximum() == 0 || layout.scrollbar().isEmpty()) {
            return 0;
        }
        ViewSpec.Scrollbar bar = layout.scrollbar().orElseThrow();
        int travel = Math.max(1, bar.track().height() - bar.thumb().height());
        double normalized = Math.max(
                0.0,
                Math.min(1.0, (desiredThumbTop - bar.track().y()) / travel));
        int desired = (int) Math.round(normalized * layout.maximum());
        return Math.max(0, Math.min(layout.maximum(), desired));
    }

    public int nextScrollOffset(
            AddSourceModel model, int width, int height, int direction) {
        CatalogLayout layout = catalogLayout(model, width, height);
        return Math.max(0, Math.min(
                layout.maximum(), model.scrollOffset() + Integer.signum(direction) * 32));
    }

    private static void addCatalogContent(
            AddSourceModel model,
            boolean busy,
            CatalogLayout layout,
            List<ViewSpec.Panel> panels,
            List<ViewSpec.Text> texts,
            List<ViewSpec.Widget> widgets,
            List<ViewSpec.Preview> previews,
            Optional<PersonalSkinRename> personalRename) {
        int contentBottom = layout.contentBottom();
        int y = CONTENT_TOP - Math.min(model.scrollOffset(), layout.maximum());
        for (SkinCatalogSource.CollectionDescriptor collection : model.visibleCollections()) {
            List<SkinCatalogSource.SkinDescriptor> skins = model.visibleSkins(collection);
            boolean collapsed = model.collectionCollapsed(collection.id());
            Bounds header = new Bounds(16, y, Math.max(1, layout.contentRight() - 16), COLLECTION_HEADER_HEIGHT);
            if (intersectsViewport(header, contentBottom)) {
                Optional<String> collectionInfo = model.collectionInfo(collection);
                widgets.add(ViewSpec.Widget.collectionHeader(
                        "add.catalog.collection:" + collection.id(),
                        header,
                        UiMessage.literal(
                                (collapsed ? "▶ " : "▼ ") + model.collectionName(collection),
                                UiMessage.Severity.INFO),
                        !busy,
                        collectionInfo.isPresent()));
                collectionInfo.ifPresent(info -> addIntersectingWidget(
                        widgets,
                        infoButton(
                                "add.catalog.collection_info:" + collection.id(),
                                new Bounds(header.right() - 16, header.y() + 1, 14, 14),
                                info,
                                !busy),
                        contentBottom));
            }
            y += COLLECTION_HEADER_HEIGHT + 4;
            if (collapsed) {
                continue;
            }

            for (int index = 0; index < skins.size(); index++) {
                int column = index % layout.columns();
                int row = index / layout.columns();
                int cardX = layout.cardStartX() + column * (layout.cardWidth() + CARD_GAP);
                int cardY = y + row * (layout.cardHeight() + CARD_GAP);
                Bounds card = new Bounds(cardX, cardY, layout.cardWidth(), layout.cardHeight());
                if (!intersectsViewport(card, contentBottom)) {
                    continue;
                }
                SkinCatalogSource.SkinDescriptor skin = skins.get(index);
                SkinVariant variant = model.selectedVariant(skin);
                String prefix = "add.catalog.skin:" + collection.id() + ":" + skin.id();
                panels.add(new ViewSpec.Panel(prefix, card, ViewSpec.Panel.Style.VANILLA_LIST));
                widgets.add(new ViewSpec.Widget(
                        prefix,
                        ViewSpec.WidgetKind.CATALOG_CARD,
                        card,
                        UiMessage.literal(model.skinName(skin), UiMessage.Severity.INFO),
                        Optional.empty(),
                        Optional.empty(),
                        !busy,
                        true,
                        0));
                if (collection.order().kind() == CatalogCollectionOrder.Kind.PERSONAL) {
                    addIntersectingWidget(
                            widgets,
                            new ViewSpec.Widget(
                                    "add.catalog.rename:" + skin.id(),
                                    ViewSpec.WidgetKind.BUTTON,
                                    new Bounds(card.right() - 33, card.y() + 3, 14, 14),
                                    UiMessage.literal("E", UiMessage.Severity.INFO),
                                    Optional.empty(),
                                    Optional.of(UiMessage.info("nclskins.your_skins.rename")),
                                    !busy,
                                    true,
                                    0),
                            contentBottom);
                    addIntersectingWidget(
                            widgets,
                            ViewSpec.Widget.catalogDelete(
                                    "add.catalog.delete:" + skin.id(),
                                    new Bounds(card.right() - 17, card.y() + 3, 14, 14),
                                    UiMessage.info("nclskins.your_skins.delete", model.skinName(skin)),
                                    !busy),
                            contentBottom);
                }
                Optional<String> skinInfo = model.skinInfo(collection, skin);
                skinInfo.ifPresent(info -> addIntersectingWidget(
                        widgets,
                        infoButton(
                                "add.catalog.skin_info:" + collection.id() + ":" + skin.id(),
                                new Bounds(card.x() + 3, card.y() + 3, 14, 14),
                                info,
                                !busy),
                        contentBottom));
                int nameX = card.x() + (skinInfo.isPresent() ? 20 : 4);
                int nameRight = card.right()
                        - (collection.order().kind() == CatalogCollectionOrder.Kind.PERSONAL
                                ? 23
                                : 4);
                Bounds nameBounds = new Bounds(
                        nameX,
                        card.y() + 7,
                        Math.max(1, nameRight - nameX),
                        10);
                if (intersectsViewport(nameBounds, contentBottom)) {
                    texts.add(new ViewSpec.Text(
                        prefix + ".name",
                        nameBounds,
                        UiMessage.literal(model.skinName(skin), UiMessage.Severity.INFO),
                            ViewSpec.Text.Alignment.CENTER,
                            Optional.of(new ViewSpec.MarqueeActivation(
                                    card,
                                    catalogMarqueeFocusIds(collection, skin, skinInfo.isPresent(), prefix)))));
                }
                Bounds previewBounds = new Bounds(
                        card.x() + 5,
                        card.y() + 20,
                        Math.max(1, card.width() - 10),
                        Math.max(1, layout.cardHeight() - 25));
                if (intersectsViewport(previewBounds, contentBottom)) {
                    previews.add(new ViewSpec.Preview(
                        prefix + ".preview",
                        previewBounds,
                        SkinReference.accountDefault(),
                        "catalog:" + collection.id() + ":" + skin.id() + ":" + variant.name(),
                        variant,
                        Optional.empty(),
                        PreviewRenderer.CapeMode.OFF,
                        OuterLayerVisibility.allVisible(),
                        -20.0F,
                        0.0F,
                        1.0F,
                        Optional.empty(),
                        Optional.of(new ViewSpec.CatalogImage(collection.id(), skin.id()))));
                }
                if (personalRename.filter(rename -> rename.sha256().equals(skin.id())).isPresent()) {
                    PersonalSkinRename rename = personalRename.orElseThrow();
                    addIntersectingWidget(
                            widgets,
                            ViewSpec.Widget.textField(
                                    "add.catalog.rename.name",
                                    new Bounds(card.x() + 3, card.y() + 3, card.width() - 6, 20),
                                    UiMessage.info("nclskins.your_skins.rename"),
                                    rename.value(),
                                    UiMessage.info("nclskins.your_skins.rename_hint"),
                                    !busy,
                                    128),
                            contentBottom);
                    int half = Math.max(1, (card.width() - 8) / 2);
                    addIntersectingWidget(
                            widgets,
                            ViewSpec.Widget.button(
                                    "add.catalog.rename.save",
                                    new Bounds(card.x() + 3, card.bottom() - 23, half, 20),
                                    UiMessage.info("nclskins.your_skins.rename_save"),
                                    !busy && !rename.value().trim().isEmpty()),
                            contentBottom);
                    addIntersectingWidget(
                            widgets,
                            ViewSpec.Widget.button(
                                    "add.catalog.rename.cancel",
                                    new Bounds(card.x() + 5 + half, card.bottom() - 23, card.width() - half - 8, 20),
                                    UiMessage.info("gui.cancel"),
                                    !busy),
                            contentBottom);
                }
            }
            int rows = (skins.size() + layout.columns() - 1) / layout.columns();
            y += rows * (layout.cardHeight() + CARD_GAP) + 8;
        }
    }

    private static ViewSpec.Widget infoButton(
            String id, Bounds bounds, String info, boolean enabled) {
        return ViewSpec.Widget.infoButton(
                id, bounds, UiMessage.literal(info, UiMessage.Severity.INFO), enabled);
    }

    private static List<String> catalogMarqueeFocusIds(
            SkinCatalogSource.CollectionDescriptor collection,
            SkinCatalogSource.SkinDescriptor skin,
            boolean hasInfo,
            String cardId) {
        List<String> ids = new ArrayList<>();
        ids.add(cardId);
        if (hasInfo) {
            ids.add("add.catalog.skin_info:" + collection.id() + ":" + skin.id());
        }
        if (collection.order().kind() == CatalogCollectionOrder.Kind.PERSONAL) {
            ids.add("add.catalog.rename:" + skin.id());
            ids.add("add.catalog.delete:" + skin.id());
        }
        return List.copyOf(ids);
    }

    public record PersonalSkinRename(String sha256, String value) {
        public PersonalSkinRename {
            Objects.requireNonNull(sha256, "sha256");
            Objects.requireNonNull(value, "value");
        }
    }

    private static CatalogLayout catalogLayout(AddSourceModel model, int width, int height) {
        int contentBottom = Math.max(CONTENT_TOP + 1, height - FOOTER_HEIGHT - 4);
        int contentRight = Math.max(17, width - 14);
        int available = Math.max(1, contentRight - 16);
        int columns = Math.max(2, Math.min(9, (available + CARD_GAP) / (68 + CARD_GAP)));
        int cardWidth = Math.min(
                96,
                Math.max(1, (available - (columns - 1) * CARD_GAP) / columns));
        int rowWidth = columns * cardWidth + (columns - 1) * CARD_GAP;
        int cardStartX = 16 + Math.max(0, (available - rowWidth) / 2);
        int viewportHeight = Math.max(1, contentBottom - CONTENT_TOP);
        int cardHeight = Math.min(
                MAX_CARD_HEIGHT,
                Math.max(MIN_CARD_HEIGHT, viewportHeight - COLLECTION_HEADER_HEIGHT - 12));
        int totalHeight = 0;
        int matchingSkinCount = 0;
        for (SkinCatalogSource.CollectionDescriptor collection : model.visibleCollections()) {
            List<SkinCatalogSource.SkinDescriptor> skins = model.visibleSkins(collection);
            matchingSkinCount += skins.size();
            totalHeight += COLLECTION_HEADER_HEIGHT + 4;
            if (!model.collectionCollapsed(collection.id())) {
                int rows = (skins.size() + columns - 1) / columns;
                totalHeight += rows * (cardHeight + CARD_GAP) + 8;
            }
        }
        int maximum = Math.max(0, totalHeight - viewportHeight);
        Optional<ViewSpec.Scrollbar> scrollbar = maximum == 0
                ? Optional.empty()
                : Optional.of(verticalScrollbar(
                        width,
                        contentBottom,
                        totalHeight,
                        viewportHeight,
                        Math.min(model.scrollOffset(), maximum),
                        maximum));
        return new CatalogLayout(
                columns,
                cardWidth,
                cardStartX,
                cardHeight,
                contentRight,
                contentBottom,
                maximum,
                matchingSkinCount,
                scrollbar);
    }

    private static ViewSpec.Scrollbar verticalScrollbar(
            int width,
            int contentBottom,
            int totalHeight,
            int viewportHeight,
            int offset,
            int maximum) {
        int trackHeight = Math.max(1, contentBottom - CONTENT_TOP);
        int thumbHeight = Math.max(12, (int) Math.round(trackHeight * (viewportHeight / (double) totalHeight)));
        thumbHeight = Math.min(trackHeight, thumbHeight);
        int travel = Math.max(0, trackHeight - thumbHeight);
        int thumbTop = CONTENT_TOP + (int) Math.round(travel * (offset / (double) maximum));
        return new ViewSpec.Scrollbar(
                new Bounds(Math.max(0, width - 9), CONTENT_TOP, 6, trackHeight),
                new Bounds(Math.max(0, width - 9), thumbTop, 6, Math.max(1, thumbHeight)),
                offset,
                maximum,
                ViewSpec.Scrollbar.Orientation.VERTICAL);
    }

    private static boolean intersectsViewport(Bounds bounds, int contentBottom) {
        return bounds.bottom() > CONTENT_TOP && bounds.y() < contentBottom;
    }

    private static void addIntersectingWidget(
            List<ViewSpec.Widget> widgets, ViewSpec.Widget widget, int contentBottom) {
        if (intersectsViewport(widget.bounds(), contentBottom)) {
            widgets.add(widget);
        }
    }

    private static UiMessage filterLabel(AddSourceModel.CatalogFilter filter) {
        return UiMessage.info(switch (filter) {
            case ALL -> "nclskins.add_source.filter_all";
            case CLASSIC -> "nclskins.add_source.filter_classic";
            case SLIM -> "nclskins.add_source.filter_slim";
        });
    }

    private record CatalogLayout(
            int columns,
            int cardWidth,
            int cardStartX,
            int cardHeight,
            int contentRight,
            int contentBottom,
            int maximum,
            int matchingSkinCount,
            Optional<ViewSpec.Scrollbar> scrollbar) {}
}

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
    private static final int TAB_BAR_HEIGHT = 24;
    private static final int CONTROLS_GAP = 7;
    private static final int CONTROLS_HEIGHT = 20;
    private static final int CONTROLS_TOP = TAB_BAR_HEIGHT + CONTROLS_GAP;
    private static final int CONTENT_TOP = CONTROLS_TOP + CONTROLS_HEIGHT + CONTROLS_GAP;
    private static final int DISCLOSURE_BUTTON_SIZE = 20;
    private static final int CONTROL_GAP = 6;
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
        return present(
                model,
                busy,
                operationStatus,
                width,
                height,
                personalRename,
                ViewChromeMetrics.STANDARD);
    }

    public ViewSpec present(
            AddSourceModel model,
            boolean busy,
            Optional<UiMessage> operationStatus,
            int width,
            int height,
            Optional<PersonalSkinRename> personalRename,
            ViewChromeMetrics chromeMetrics) {
        Objects.requireNonNull(model, "model");
        operationStatus = Objects.requireNonNull(operationStatus, "operationStatus");
        personalRename = Objects.requireNonNull(personalRename, "personalRename");
        chromeMetrics = Objects.requireNonNull(chromeMetrics, "chromeMetrics");
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("view dimensions must be positive");
        }
        List<ViewSpec.Panel> panels = new ArrayList<>();
        List<ViewSpec.Text> texts = new ArrayList<>();
        List<ViewSpec.Widget> widgets = new ArrayList<>();
        List<ViewSpec.Preview> previews = new ArrayList<>();
        List<ViewSpec.ClipRegion> clipRegions = new ArrayList<>();
        List<ViewSpec.TooltipRegion> tooltipRegions = new ArrayList<>();
        List<ViewSpec.NavigationNode> navigationNodes = new ArrayList<>();
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
            texts.add(new ViewSpec.Text(
                    "add.player.label",
                    new Bounds(contentX, 30, contentWidth, 10),
                    UiMessage.info("nclskins.add_source.player_label"),
                    ViewSpec.Text.Alignment.LEFT));
            widgets.add(ViewSpec.Widget.textField(
                    "add.player.input",
                    new Bounds(contentX, 41, contentWidth - actionWidth - 6, 20),
                    UiMessage.info("nclskins.add_source.player_label"),
                    model.playerInput(),
                    UiMessage.info("nclskins.add_source.player_hint"),
                    !busy,
                    36,
                    true,
                    Optional.of("add.player.load")));
            widgets.add(ViewSpec.Widget.button(
                    "add.player.load",
                    new Bounds(contentX + contentWidth - actionWidth, 41, actionWidth, 20),
                    UiMessage.info("nclskins.add_source.find_player"),
                    !busy && !model.playerInput().isBlank()));
            texts.add(new ViewSpec.Text(
                    "add.url.label",
                    new Bounds(contentX, 65, contentWidth, 10),
                    UiMessage.info("nclskins.add_source.url_label"),
                    ViewSpec.Text.Alignment.LEFT));
            widgets.add(ViewSpec.Widget.textField(
                    "add.url.input",
                    new Bounds(contentX, 76, contentWidth - actionWidth - 6, 20),
                    UiMessage.info("nclskins.add_source.url_label"),
                    model.urlInput(),
                    UiMessage.info("nclskins.add_source.url_hint"),
                    !busy,
                    2048,
                    true,
                    Optional.of("add.url.load")));
            widgets.add(ViewSpec.Widget.button(
                    "add.url.load",
                    new Bounds(contentX + contentWidth - actionWidth, 76, actionWidth, 20),
                    UiMessage.info("nclskins.add_source.download"),
                    !busy && !model.urlInput().isBlank()));
            texts.add(new ViewSpec.Text(
                    "add.url.privacy",
                    new Bounds(contentX, 99, contentWidth, 10),
                    UiMessage.info("nclskins.add_source.url_privacy"),
                    ViewSpec.Text.Alignment.LEFT));
            widgets.add(ViewSpec.Widget.button(
                    "add.file.choose",
                    new Bounds(contentX, 113, contentWidth, 20),
                    UiMessage.info("nclskins.add_source.choose_file"),
                    !busy));
            widgets.add(ViewSpec.Widget.button(
                    "add.external.launcher",
                    new Bounds(contentX, 137, contentWidth, 20),
                    UiMessage.info("nclskins.external_import.from_launcher"),
                    !busy));
            widgets.add(ViewSpec.Widget.button(
                    "add.external.mod",
                    new Bounds(contentX, 161, contentWidth, 20),
                    UiMessage.info("nclskins.external_import.from_mod"),
                    !busy));
            operationStatus.filter(status ->
                            !"nclskins.external_import.choose_source".equals(status.key()))
                    .ifPresent(status -> texts.add(new ViewSpec.Text(
                    "add.import.status",
                            new Bounds(contentX, Math.min(height - 40, 196), contentWidth, 10),
                    status,
                    ViewSpec.Text.Alignment.CENTER)));
        } else {
            int filterWidth = Math.min(104, Math.max(72, width / 4));
            int disclosureX = width - 16 - DISCLOSURE_BUTTON_SIZE;
            int filterX = disclosureX - CONTROL_GAP - filterWidth;
            widgets.add(ViewSpec.Widget.textField(
                    "add.catalog.search",
                    new Bounds(16, CONTROLS_TOP, Math.max(1, filterX - CONTROL_GAP - 16), CONTROLS_HEIGHT),
                    UiMessage.info("nclskins.add_source.search"),
                    model.query(),
                    UiMessage.info("nclskins.add_source.search_hint"),
                    !busy,
                    128,
                    true,
                    Optional.empty()));
            widgets.add(ViewSpec.Widget.button(
                    "add.catalog.filter",
                    new Bounds(filterX, CONTROLS_TOP, filterWidth, CONTROLS_HEIGHT),
                    filterLabel(model.filter()),
                    !busy));
            boolean anyCollapsed = model.anyAvailableCollectionCollapsed();
            widgets.add(ViewSpec.Widget.iconButton(
                    "add.catalog.disclosure",
                    new Bounds(disclosureX, CONTROLS_TOP, DISCLOSURE_BUTTON_SIZE, DISCLOSURE_BUTTON_SIZE),
                    UiMessage.info(anyCollapsed
                            ? "nclskins.collection.expand_all"
                            : "nclskins.collection.collapse_all"),
                    anyCollapsed ? "expand_all" : "collapse_all",
                    !busy && !model.availableCollectionIds().isEmpty()));

            CatalogLayout layout = catalogLayout(model, width, height, chromeMetrics);
            addCatalogContent(
                    model,
                    busy,
                    layout,
                    panels,
                    texts,
                    widgets,
                    previews,
                    tooltipRegions,
                    navigationNodes,
                    personalRename);
            clipRegions.add(new ViewSpec.ClipRegion(
                    "add.catalog.viewport",
                    new Bounds(0, CONTENT_TOP, width, Math.max(1, layout.contentBottom() - CONTENT_TOP)),
                    List.of(
                            "add.catalog.collection:",
                            "add.catalog.skin:",
                            "add.catalog.rename:",
                            "add.catalog.rename.",
                            "add.catalog.delete:",
                            "add.catalog.delete.",
                            "add.catalog.tooltip:")));
            scrollbar = layout.scrollbar();
            scrollSurfaces = List.of(new ViewSpec.ScrollSurface(
                    "add.catalog",
                    new Bounds(0, CONTENT_TOP, width, Math.max(1, layout.contentBottom() - CONTENT_TOP)),
                    ViewSpec.Scrollbar.Orientation.VERTICAL,
                    Math.min(model.scrollOffset(), layout.maximum()),
                    layout.maximum()));
            if (layout.matchingSkinCount() == 0) {
                texts.add(new ViewSpec.Text(
                        "add.catalog.empty",
                        new Bounds(16, Math.max(CONTENT_TOP + 12, height / 2), Math.max(1, width - 32), 10),
                        UiMessage.info("nclskins.add_source.no_results"),
                        ViewSpec.Text.Alignment.CENTER));
            }
            operationStatus.filter(status ->
                            "nclskins.your_skins.delete_failed".equals(status.key())
                                    || "nclskins.add_source.disclosure_failed".equals(status.key()))
                    .ifPresent(status -> texts.add(new ViewSpec.Text(
                    "add.catalog.status",
                    new Bounds(16, Math.max(CONTENT_TOP, height - 39), Math.max(1, width - 32), 10),
                    status,
                    ViewSpec.Text.Alignment.CENTER)));
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
                List.of(new ViewSpec.TabGroup(
                        "add.tabs", new Bounds(0, 0, width, TAB_BAR_HEIGHT), tabs)),
                focus,
                clipRegions,
                List.of(),
                List.of(),
                scrollSurfaces,
                tooltipRegions).withNavigationNodes(navigationNodes);
    }

    private static Optional<ViewSpec.FocusRequest> focusRequest(AddSourceModel model) {
        if (model.focusWidgetId().isEmpty() || model.focusToken().isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new ViewSpec.FocusRequest(
                model.focusWidgetId().orElseThrow(), model.focusToken().orElseThrow()));
    }

    public int maximumScroll(AddSourceModel model, int width, int height) {
        return maximumScroll(model, width, height, ViewChromeMetrics.STANDARD);
    }

    public int maximumScroll(
            AddSourceModel model, int width, int height, ViewChromeMetrics chromeMetrics) {
        return catalogLayout(model, width, height, chromeMetrics).maximum();
    }

    public int normalizedScrollOffset(
            AddSourceModel model, int width, int height, int desired) {
        return normalizedScrollOffset(
                model, width, height, desired, ViewChromeMetrics.STANDARD);
    }

    public int normalizedScrollOffset(
            AddSourceModel model,
            int width,
            int height,
            int desired,
            ViewChromeMetrics chromeMetrics) {
        CatalogLayout layout = catalogLayout(model, width, height, chromeMetrics);
        return Math.max(0, Math.min(layout.maximum(), desired));
    }

    public int offsetFromScrollbar(
            AddSourceModel model, int width, int height, double desiredThumbTop) {
        return offsetFromScrollbar(
                model, width, height, desiredThumbTop, ViewChromeMetrics.STANDARD);
    }

    public int offsetFromScrollbar(
            AddSourceModel model,
            int width,
            int height,
            double desiredThumbTop,
            ViewChromeMetrics chromeMetrics) {
        CatalogLayout layout = catalogLayout(model, width, height, chromeMetrics);
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
        CatalogLayout layout = catalogLayout(
                model, width, height, ViewChromeMetrics.STANDARD);
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
            List<ViewSpec.TooltipRegion> tooltipRegions,
            List<ViewSpec.NavigationNode> navigationNodes,
            Optional<PersonalSkinRename> personalRename) {
        int contentBottom = layout.contentBottom();
        boolean transientMode = model.personalSkinDeletion().isPresent()
                || personalRename.isPresent();
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
                        false));
                collectionInfo.ifPresent(info -> tooltipRegions.add(new ViewSpec.TooltipRegion(
                        "add.catalog.tooltip:collection:" + collection.id(),
                        new Bounds(
                                header.x() + 12,
                                header.y() + 3,
                                Math.max(1, header.width() - 12),
                                10),
                        UiMessage.literal(model.collectionName(collection), UiMessage.Severity.INFO),
                        ViewSpec.Text.Alignment.LEFT,
                        UiMessage.literal(info, UiMessage.Severity.INFO))));
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
                SkinCatalogSource.SkinDescriptor skin = skins.get(index);
                String prefix = "add.catalog.skin:" + collection.id() + ":" + skin.id();
                navigationNodes.add(ViewSpec.NavigationNode.card(
                        prefix,
                        card,
                        "add.catalog",
                        navigationNodes.size(),
                        -1,
                        !busy && !transientMode,
                        ViewSpec.NavigationPattern.GRID,
                        Optional.empty()));
                if (!intersectsViewport(card, contentBottom)) {
                    continue;
                }
                SkinVariant variant = model.selectedVariant(skin);
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
                Optional<String> skinInfo = model.skinInfo(collection, skin);
                boolean renamingThisCard = personalRename
                        .filter(rename -> rename.collectionId().equals(collection.id()))
                        .filter(rename -> rename.sha256().equals(skin.id()))
                        .isPresent();
                int nameX = card.x() + 4;
                int nameRight = card.right() - 4;
                Bounds nameBounds = new Bounds(
                        nameX,
                        card.y() + 7,
                        Math.max(1, nameRight - nameX),
                        10);
                if (!renamingThisCard && intersectsViewport(nameBounds, contentBottom)) {
                    texts.add(new ViewSpec.Text(
                        prefix + ".name",
                        nameBounds,
                        UiMessage.literal(model.skinName(skin), UiMessage.Severity.INFO),
                            ViewSpec.Text.Alignment.CENTER,
                            Optional.of(new ViewSpec.MarqueeActivation(
                                    card,
                                    catalogMarqueeFocusIds(collection, skin, prefix)))));
                    skinInfo.ifPresent(info -> tooltipRegions.add(new ViewSpec.TooltipRegion(
                            "add.catalog.tooltip:skin:" + collection.id() + ":" + skin.id(),
                            nameBounds,
                            UiMessage.literal(model.skinName(skin), UiMessage.Severity.INFO),
                            ViewSpec.Text.Alignment.CENTER,
                            UiMessage.literal(info, UiMessage.Severity.INFO))));
                }
                boolean personal = collection.order().kind() == CatalogCollectionOrder.Kind.PERSONAL;
                Bounds previewBounds = new Bounds(
                        card.x() + 5,
                        card.y() + 20,
                        Math.max(1, card.width() - 10),
                        Math.max(1, layout.cardHeight() - (personal ? 48 : 25)));
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
                            Optional.of(new ViewSpec.CatalogImage(collection.id(), skin.id())),
                            PreviewRenderer.PreviewIntent.ASSET_THUMBNAIL));
                }
                if (renamingThisCard) {
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
                                    128,
                                    true,
                                    Optional.of("add.catalog.rename.save")),
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
                } else if (personal) {
                    int half = Math.max(1, (card.width() - 6) / 2);
                    Bounds leftAction = new Bounds(
                            card.x() + 2, card.bottom() - 22, half, 20);
                    Bounds rightAction = new Bounds(
                            card.x() + 4 + half,
                            card.bottom() - 22,
                            Math.max(1, card.width() - half - 6),
                            20);
                    boolean pendingDelete = model.personalSkinDeletion()
                            .filter(deletion -> deletion.collectionId().equals(collection.id()))
                            .filter(deletion -> deletion.sha256().equals(skin.id()))
                            .isPresent();
                    if (pendingDelete) {
                        addIntersectingWidget(
                                widgets,
                                ViewSpec.Widget.button(
                                        "add.catalog.delete.confirm",
                                        leftAction,
                                        UiMessage.info("nclskins.your_skins.delete_confirm"),
                                        !busy),
                                contentBottom);
                        addIntersectingWidget(
                                widgets,
                                ViewSpec.Widget.button(
                                        "add.catalog.delete.cancel",
                                        rightAction,
                                        UiMessage.info("gui.cancel"),
                                        !busy),
                                contentBottom);
                    } else {
                        addIntersectingWidget(
                                widgets,
                                ViewSpec.Widget.iconButton(
                                        AddSourceModel.personalActionId(
                                                "add.catalog.rename:", collection.id(), skin.id()),
                                        leftAction,
                                        UiMessage.info("nclskins.your_skins.rename"),
                                        "edit",
                                        !busy && !transientMode),
                                contentBottom);
                        addIntersectingWidget(
                                widgets,
                                ViewSpec.Widget.iconButton(
                                        AddSourceModel.personalActionId(
                                                "add.catalog.delete:", collection.id(), skin.id()),
                                        rightAction,
                                        UiMessage.info("nclskins.your_skins.delete"),
                                        "delete",
                                        !busy && !transientMode),
                                contentBottom);
                    }
                }
            }
            int rows = (skins.size() + layout.columns() - 1) / layout.columns();
            y += rows * (layout.cardHeight() + CARD_GAP) + 8;
        }
    }

    private static List<String> catalogMarqueeFocusIds(
            SkinCatalogSource.CollectionDescriptor collection,
            SkinCatalogSource.SkinDescriptor skin,
            String cardId) {
        List<String> ids = new ArrayList<>();
        ids.add(cardId);
        if (collection.order().kind() == CatalogCollectionOrder.Kind.PERSONAL) {
            ids.add(AddSourceModel.personalActionId(
                    "add.catalog.rename:", collection.id(), skin.id()));
            ids.add(AddSourceModel.personalActionId(
                    "add.catalog.delete:", collection.id(), skin.id()));
        }
        return List.copyOf(ids);
    }

    public record PersonalSkinRename(String collectionId, String sha256, String value) {
        public PersonalSkinRename {
            Objects.requireNonNull(collectionId, "collectionId");
            Objects.requireNonNull(sha256, "sha256");
            Objects.requireNonNull(value, "value");
        }
    }

    private static CatalogLayout catalogLayout(
            AddSourceModel model,
            int width,
            int height,
            ViewChromeMetrics chromeMetrics) {
        Objects.requireNonNull(chromeMetrics, "chromeMetrics");
        List<SkinCatalogSource.CollectionDescriptor> collections = model.visibleCollections();
        List<CollectionGridLayout.Section> sections = collections.stream()
                .map(collection -> new CollectionGridLayout.Section(
                        model.visibleSkins(collection).size(),
                        model.collectionCollapsed(collection.id())))
                .toList();
        CollectionGridLayout.Layout layout = CollectionGridLayout.calculate(
                width,
                height,
                CONTENT_TOP,
                chromeMetrics.catalogFooterHeight(),
                0,
                0,
                COLLECTION_HEADER_HEIGHT,
                CARD_GAP,
                68,
                96,
                MIN_CARD_HEIGHT,
                MAX_CARD_HEIGHT,
                model.scrollOffset(),
                sections);
        return new CatalogLayout(
                layout.columns(),
                layout.cardWidth(),
                layout.cardStartX(),
                layout.cardHeight(),
                layout.contentRight(),
                layout.contentBottom(),
                layout.maximum(),
                layout.itemCount(),
                layout.scrollbar());
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

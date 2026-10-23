package com.naocraftlab.skins.runtime;

import com.naocraftlab.skins.client.CatalogCollectionOrder;
import com.naocraftlab.skins.client.OuterLayerVisibility;
import com.naocraftlab.skins.client.PreviewRenderer;
import com.naocraftlab.skins.client.SkinCatalogSource;
import com.naocraftlab.skins.core.compatibility.SkinCompatibility;
import com.naocraftlab.skins.core.compatibility.SkinCompatibilityStatus;
import com.naocraftlab.skins.core.model.AddSourceTab;
import com.naocraftlab.skins.core.model.SkinReference;
import com.naocraftlab.skins.core.model.SkinVariant;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;


public final class AddSourcePresenter {
    private static final int TAB_BAR_HEIGHT = ViewChromeMetrics.CATALOG_TAB_BAR_HEIGHT;
    private static final int CONTROLS_GAP = ViewChromeMetrics.CATALOG_CONTROLS_GAP;
    private static final int CONTROLS_HEIGHT = ViewChromeMetrics.CATALOG_CONTROLS_HEIGHT;
    private static final int CONTROLS_TOP = TAB_BAR_HEIGHT + CONTROLS_GAP;
    private static final int CONTENT_TOP = ViewChromeMetrics.CATALOG_CONTENT_TOP;
    private static final int DISCLOSURE_BUTTON_SIZE = 20;
    private static final int CONTROL_GAP = 6;
    private static final int COLLECTION_HEADER_HEIGHT = CollectionGridLayout.COLLECTION_HEADER_HEIGHT;

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
        operationStatus = projectedStatus(model, operationStatus).map(message ->
                message.key().equals("nclskins.add.cpm_publication_count_quota")
                        ? model.countQuotaMessage() : message);
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("view dimensions must be positive");
        }
        if (model.importContext().dependency().isPresent())
            return publication(model, busy, operationStatus, width, height, chromeMetrics);
        List<ViewSpec.Panel> panels = new ArrayList<>();
        List<ViewSpec.Text> texts = new ArrayList<>();
        List<ViewSpec.Widget> widgets = new ArrayList<>();
        List<ViewSpec.Preview> previews = new ArrayList<>();
        List<ViewSpec.ClipRegion> clipRegions = new ArrayList<>();
        List<ViewSpec.TooltipRegion> tooltipRegions = new ArrayList<>();
        List<ViewSpec.IconDecoration> iconDecorations = new ArrayList<>();
        List<ViewSpec.NavigationNode> navigationNodes = new ArrayList<>();
        int contextHeight = contextTitleHeight(model, width);
        if (contextHeight > 0) {
            texts.add(new ViewSpec.Text("add.context.title",
                    new Bounds(16, TAB_BAR_HEIGHT + CONTROLS_GAP, Math.max(1, width - 32), contextHeight),
                    UiMessage.info("nclskins.add_source.cpm.title"),
                    ViewSpec.Text.Alignment.CENTER, ViewSpec.Text.Layout.WRAP));
        }
        boolean publishing = operationStatus.map(value -> value.key().equals("nclskins.add.cpm_publication_loading")).orElse(false) && busy;
        boolean scoped = contextHeight > 0 || importFeedback(operationStatus);
        CatalogLayout workspace = catalogLayout(model, width, height, chromeMetrics, operationStatus);
        int scrollOffset = Math.min(model.scrollOffset(), workspace.maximum());
        int contextShift = scoped ? workspace.contentTop() - 30 - scrollOffset : 0;
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
            int contentWidth = scoped ? Math.min(520, Math.max(1, width - 32)) : Math.min(520, Math.max(220, width - 32));
            int contentX = (width - contentWidth) / 2;
            int actionWidth = Math.min(96, Math.max(scoped ? 48 : 76, contentWidth / 3));
            int privacyHeight = scoped ? model.messageHeight(UiMessage.info("nclskins.add_source.url_privacy"), contentWidth) : 10;
            int privacyExtra = privacyHeight - 10;

            texts.add(new ViewSpec.Text(
                    "add.player.label",
                    new Bounds(contentX, 30 + contextShift, contentWidth, 10),
                    UiMessage.info("nclskins.add_source.player_label"),
                    ViewSpec.Text.Alignment.LEFT));
            widgets.add(ViewSpec.Widget.textField(
                    "add.player.input",
                    new Bounds(contentX, 41 + contextShift, contentWidth - actionWidth - 6, 20),
                    UiMessage.info("nclskins.add_source.player_label"),
                    model.playerInput(),
                    UiMessage.info("nclskins.add_source.player_hint"),
                    !busy,
                    36,
                    true,
                    Optional.of("add.player.load")));
            widgets.add(ViewSpec.Widget.button(
                    "add.player.load",
                    new Bounds(contentX + contentWidth - actionWidth, 41 + contextShift, actionWidth, 20),
                    UiMessage.info("nclskins.add_source.find_player"),
                    !busy && !model.playerInput().isBlank()));
            texts.add(new ViewSpec.Text(
                    "add.url.label",
                    new Bounds(contentX, 65 + contextShift, contentWidth, 10),
                    UiMessage.info("nclskins.add_source.url_label"),
                    ViewSpec.Text.Alignment.LEFT));
            widgets.add(ViewSpec.Widget.textField(
                    "add.url.input",
                    new Bounds(contentX, 76 + contextShift, contentWidth - actionWidth - 6, 20),
                    UiMessage.info("nclskins.add_source.url_label"),
                    model.urlInput(),
                    model.urlHint(),
                    !busy,
                    2048,
                    true,
                    Optional.of("add.url.load")));
            widgets.add(ViewSpec.Widget.button(
                    "add.url.load",
                    new Bounds(contentX + contentWidth - actionWidth, 76 + contextShift, actionWidth, 20),
                    UiMessage.info("nclskins.add_source.download"),
                    !busy && !model.urlInput().isBlank()));
            texts.add(new ViewSpec.Text(
                    "add.url.privacy",
                    new Bounds(contentX, 99 + contextShift, contentWidth, privacyHeight),
                    UiMessage.info("nclskins.add_source.url_privacy"),
                    ViewSpec.Text.Alignment.LEFT, scoped ? ViewSpec.Text.Layout.WRAP : ViewSpec.Text.Layout.SINGLE_LINE));
            widgets.add(ViewSpec.Widget.button(
                    "add.file.choose",
                    new Bounds(contentX, 113 + contextShift + privacyExtra, contentWidth, 20),
                    UiMessage.info("nclskins.add_source.choose_file"),
                    !busy));
            widgets.add(ViewSpec.Widget.button(
                    "add.external.launcher",
                    new Bounds(contentX, 137 + contextShift + privacyExtra, contentWidth, 20),
                    UiMessage.info("nclskins.external_import.from_launcher"),
                    blockedSourceHint(model, AddSourceModel.SourceAction.LAUNCHER),
                    !busy && model.importContext().allows(AddSourceModel.SourceAction.LAUNCHER)));
            widgets.add(ViewSpec.Widget.button(
                    "add.external.mod",
                    new Bounds(contentX, 161 + contextShift + privacyExtra, contentWidth, 20),
                    UiMessage.info("nclskins.external_import.from_mod"),
                    blockedSourceHint(model, AddSourceModel.SourceAction.MOD),
                    !busy && model.importContext().allows(AddSourceModel.SourceAction.MOD)));
            operationStatus.filter(status -> status.severity() == UiMessage.Severity.ERROR || publishing)
                    .ifPresent(status -> texts.add(new ViewSpec.Text(
                    "add.import.status",
                            new Bounds(contentX, 187 + contextShift + privacyExtra, contentWidth, model.messageHeight(status, contentWidth)),
                    status,
                    ViewSpec.Text.Alignment.CENTER, ViewSpec.Text.Layout.WRAP)));
        } else {
            int controlsTop = scoped ? workspace.contentTop() - scrollOffset : CONTROLS_TOP;
            int filterWidth = Math.min(104, Math.max(72, width / 4));
            int disclosureX = width - 16 - DISCLOSURE_BUTTON_SIZE;
            int filterX = disclosureX - CONTROL_GAP - filterWidth;
            widgets.add(ViewSpec.Widget.textField(
                    "add.catalog.search",
                    new Bounds(16, controlsTop, Math.max(1, filterX - CONTROL_GAP - 16), CONTROLS_HEIGHT),
                    UiMessage.info("nclskins.add_source.search"),
                    model.query(),
                    UiMessage.info("nclskins.add_source.search_hint"),
                    !busy,
                    128,
                    true,
                    Optional.empty()));
            widgets.add(ViewSpec.Widget.button(
                    "add.catalog.filter",
                    new Bounds(filterX, controlsTop, filterWidth, CONTROLS_HEIGHT),
                    filterLabel(model.filter()),
                    model.importContext().requiredVariant().map(value -> requiredTypeMessage(model)),
                    !busy && !model.importContext().filterLocked()));
            boolean anyCollapsed = model.anyAvailableCollectionCollapsed();
            widgets.add(ViewSpec.Widget.iconButton(
                    "add.catalog.disclosure",
                    new Bounds(disclosureX, controlsTop, DISCLOSURE_BUTTON_SIZE, DISCLOSURE_BUTTON_SIZE),
                    UiMessage.info(anyCollapsed
                            ? "nclskins.collection.expand_all"
                            : "nclskins.collection.collapse_all"),
                    anyCollapsed ? GuiIcon.ACTION_EXPAND_ALL : GuiIcon.ACTION_COLLAPSE_ALL,
                    !busy && !model.availableCollectionIds().isEmpty()));

            CatalogLayout layout = workspace;
            addCatalogContent(
                    model,
                    busy,
                    layout,
                    panels,
                    texts,
                    widgets,
                    previews,
                    iconDecorations,
                    tooltipRegions,
                    navigationNodes,
                    personalRename);
            if (!scoped) clipRegions.add(AppearanceCollections.clip(
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
                        new Bounds(16, scoped ? layout.contentStart() + 12 - scrollOffset : Math.max(CONTENT_TOP + 12, height / 2), Math.max(1, width - 32), 10),
                        UiMessage.info("nclskins.add_source.no_results"),
                        ViewSpec.Text.Alignment.CENTER));
            }
            operationStatus.filter(status ->
                            publishing || scoped && status.severity() == UiMessage.Severity.ERROR || model.importContext().failure().isPresent()
                                    || "nclskins.your_skins.delete_failed".equals(status.key())
                                    || "nclskins.add_source.disclosure_failed".equals(status.key()))
                    .ifPresent(status -> texts.add(new ViewSpec.Text(
                    "add.catalog.status",
                    new Bounds(16, scoped ? controlsTop + CONTROLS_HEIGHT + CONTROLS_GAP : Math.max(CONTENT_TOP, height - 39), Math.max(1, width - 32),
                            scoped || model.importContext().failure().isPresent() ? model.messageHeight(status, Math.max(1, width - 32)) : 10),
                    status,
                    ViewSpec.Text.Alignment.CENTER, scoped || model.importContext().failure().isPresent()
                            ? ViewSpec.Text.Layout.WRAP : ViewSpec.Text.Layout.SINGLE_LINE)));
        }

        if (scoped) {
            for (ViewSpec.Text text : List.copyOf(texts)) {
                if (text.id().equals("add.catalog.status")) {
                    widgets.add(ViewSpec.Widget.infoButton(text.id() + ".info",
                            new Bounds(Math.max(0, text.bounds().x() - 16), text.bounds().y(), 14, 20), text.message(), !publishing));
                }
            }
            Bounds viewport = new Bounds(0, workspace.contentTop(), width, workspace.contentBottom() - workspace.contentTop());
            String surfaceId = model.selectedTab() == AddSourceTab.FILE ? "add.import" : "add.catalog";
            clipRegions.add(AppearanceCollections.clip("add.context.viewport", viewport,
                    List.of("add.player.", "add.url.", "add.file.", "add.external.", "add.import.", "add.catalog.")));
            scrollbar = workspace.scrollbar();
            scrollSurfaces = List.of(new ViewSpec.ScrollSurface(surfaceId, viewport,
                    ViewSpec.Scrollbar.Orientation.VERTICAL, scrollOffset, workspace.maximum()));
            for (ViewSpec.Widget widget : widgets) {
                if (navigationNodes.stream().noneMatch(node -> node.id().equals(widget.id()))) {
                    var node = ViewSpec.NavigationNode.control(widget, navigationNodes.size(), navigationNodes.size());
                    boolean blockedSource = widget.id().equals("add.external.launcher") || widget.id().equals("add.external.mod");
                    navigationNodes.add(ViewSpec.NavigationNode.card(widget.id(), widget.bounds(), surfaceId,
                            node.documentOrder(), node.tabOrder(), node.enabled() || !busy && blockedSource,
                            node.pattern(), widget.enabled() ? node.activationActionId() : Optional.empty()));
                }
            }
        }

        int cancelWidth = Math.min(200, Math.max(100, width - 32));
        widgets.add(ViewSpec.Widget.button(
                "add.cancel",
                new Bounds((width - cancelWidth) / 2, Math.max(0, height - 28), cancelWidth, 20),
                UiMessage.info("gui.cancel"),
                !publishing && (!busy || (model.selectedTab() == AddSourceTab.FILE || model.importContext().canCancelPending())
                        && personalRename.isEmpty() && model.personalSkinDeletion().isEmpty())));
        if (scoped) navigationNodes.add(ViewSpec.NavigationNode.control(
                widgets.get(widgets.size() - 1), navigationNodes.size(), navigationNodes.size()));

        Optional<ViewSpec.FocusRequest> focus = focusRequest(model);
        return new ViewSpec(
                "add_source",
                UiMessage.info(contextHeight > 0 ? "nclskins.add_source.cpm.title" : "nclskins.add_source.title"),
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
                iconDecorations,
                scrollSurfaces,
                tooltipRegions).withNavigationNodes(navigationNodes);
    }

    private static ViewSpec publication(AddSourceModel model, boolean busy, Optional<UiMessage> status,
            int width, int height, ViewChromeMetrics chrome) {
        var prompt = model.importContext().dependency().orElseThrow();
        String action = "add.cpm.dependency.";
        int contentWidth = Math.max(1, width - 40);
        var title = UiMessage.info("nclskins.add.cpm_dependency_title");
        var warning = UiMessage.info("nclskins.add.cpm_dependency_warning");
        int titleHeight = model.messageHeight(title, contentWidth);
        int warningHeight = model.messageHeight(warning, contentWidth);
        var error = status.filter(value -> value.severity() == UiMessage.Severity.ERROR);
        int errorHeight = error.map(value -> model.messageHeight(value, contentWidth) + 8).orElse(0);
        Bounds viewport = new Bounds(16, 8, contentWidth, Math.max(1, height - chrome.catalogFooterHeight() - 16));
        int total = titleHeight + warningHeight + errorHeight + 44;
        int maximum = Math.max(0, total - viewport.height());
        int offset = Math.min(model.scrollOffset(), maximum);
        int y = viewport.y() - offset;
        var texts = new ArrayList<ViewSpec.Text>();
        texts.add(new ViewSpec.Text("add.cpm.publication.title", new Bounds(16, y, contentWidth, titleHeight),
                title, ViewSpec.Text.Alignment.CENTER, ViewSpec.Text.Layout.WRAP));
        y += titleHeight + 8;
        texts.add(new ViewSpec.Text("add.cpm.publication.warning", new Bounds(16, y, contentWidth, warningHeight),
                warning, ViewSpec.Text.Alignment.LEFT, ViewSpec.Text.Layout.WRAP));
        y += warningHeight + 8;
        if (error.isPresent()) {
            texts.add(new ViewSpec.Text("add.cpm.publication.status", new Bounds(16, y, contentWidth, errorHeight - 8),
                    error.orElseThrow(), ViewSpec.Text.Alignment.CENTER, ViewSpec.Text.Layout.WRAP));
            y += errorHeight;
        }
        var confirm = ViewSpec.Widget.button(action + "confirm." + prompt.revision(),
                new Bounds(16, y, contentWidth, 20),
                UiMessage.info("nclskins.add.cpm_dependency_confirm"),
                Optional.of(warning), !busy);
        var cancel = ViewSpec.Widget.button(action + "cancel." + prompt.revision(),
                new Bounds(16, Math.max(0, height - 28), contentWidth, 20), UiMessage.info("gui.cancel"), true);
        Optional<ViewSpec.Scrollbar> scrollbar = Optional.empty();
        if (maximum > 0) {
            Bounds track = new Bounds(width - 12, viewport.y(), 6, viewport.height());
            int thumbHeight = Math.min(track.height(), Math.max(16, track.height() * viewport.height() / total));
            scrollbar = Optional.of(new ViewSpec.Scrollbar(track,
                    new Bounds(track.x(), track.y() + (track.height() - thumbHeight) * offset / maximum, track.width(), thumbHeight),
                    offset, maximum, ViewSpec.Scrollbar.Orientation.VERTICAL));
        }
        return new ViewSpec("add_source", title, width, height, List.of(), texts, List.of(confirm, cancel),
                List.of(), scrollbar, List.of(), focusRequest(model)
                        .filter(request -> request.widgetId().equals(confirm.id()) || request.widgetId().equals(cancel.id())),
                List.of(new ViewSpec.ClipRegion("add.import", viewport, List.of("add.cpm.publication.title",
                        "add.cpm.publication.warning", "add.cpm.publication.status", action + "confirm."))),
                List.of(), List.of(),
                List.of(new ViewSpec.ScrollSurface("add.import", viewport, ViewSpec.Scrollbar.Orientation.VERTICAL, offset, maximum)), List.of())
                .withNavigationNodes(List.of(ViewSpec.NavigationNode.card(confirm.id(), confirm.bounds(), "add.import",
                        0, 0, confirm.enabled(), ViewSpec.NavigationNode.control(confirm, 0, 0).pattern(), Optional.of(confirm.id())),
                        ViewSpec.NavigationNode.control(cancel, 1, 1)));
    }

    private static Optional<ViewSpec.FocusRequest> focusRequest(AddSourceModel model) {
        if (model.focusWidgetId().isEmpty() || model.focusToken().isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new ViewSpec.FocusRequest(
                model.focusWidgetId().orElseThrow(), model.focusToken().orElseThrow()));
    }

    private static void addCatalogContent(
            AddSourceModel model,
            boolean busy,
            CatalogLayout layout,
            List<ViewSpec.Panel> panels,
            List<ViewSpec.Text> texts,
            List<ViewSpec.Widget> widgets,
            List<ViewSpec.Preview> previews,
            List<ViewSpec.IconDecoration> iconDecorations,
            List<ViewSpec.TooltipRegion> tooltipRegions,
            List<ViewSpec.NavigationNode> navigationNodes,
            Optional<PersonalSkinRename> personalRename) {
        int contentBottom = layout.contentBottom();
        boolean transientMode = model.personalSkinDeletion().isPresent()
                || personalRename.isPresent();
        List<SkinCatalogSource.CollectionDescriptor> collections = model.visibleCollections();
        var sections = AppearanceCollections.layout(collectionSections(model, collections, layout.cardHeight()), layout.columns());
        Bounds viewport = new Bounds(0, layout.contentTop(), layout.contentRight(), Math.max(1, contentBottom - layout.contentTop()));
        int contentTop = layout.contentStart() - Math.min(model.scrollOffset(), layout.maximum());
        for (int sectionIndex = 0; sectionIndex < collections.size(); sectionIndex++) {
            SkinCatalogSource.CollectionDescriptor collection = collections.get(sectionIndex);
            var section = sections.sections().get(sectionIndex);
            List<SkinCatalogSource.SkinDescriptor> skins = model.visibleSkins(collection);
            boolean collapsed = section.section().collapsed();
            Bounds header = section.header(16, contentTop, Math.max(1, layout.contentRight() - 16));
            String headerId = "add.catalog.collection:" + collection.id();
            var headerPresentation = AppearanceCollections.header(headerId, header,
                    UiMessage.literal((collapsed ? "▶ " : "▼ ") + model.collectionName(collection), UiMessage.Severity.INFO),
                    !busy, viewport, "add.catalog", navigationNodes.size(), -1);
            navigationNodes.add(headerPresentation.navigation());
            headerPresentation.widget().ifPresent(widgets::add);
            if (headerPresentation.widget().isPresent()) {
                model.collectionInfo(collection).ifPresent(info -> tooltipRegions.add(AppearanceCard.info(
                        "add.catalog.tooltip:collection:" + collection.id(),
                        new Bounds(header.x() + 12, header.y() + 3, Math.max(1, header.width() - 12), 10),
                        UiMessage.literal(model.collectionName(collection), UiMessage.Severity.INFO),
                        ViewSpec.Text.Alignment.LEFT, info)));
            }
            if (collapsed) continue;
            for (int index = 0; index < skins.size(); index++) {
                Bounds card = section.card(index, layout.cardStartX(), contentTop, layout.cardWidth());
                SkinCatalogSource.SkinDescriptor skin = skins.get(index);
                String prefix = "add.catalog.skin:" + collection.id() + ":" + skin.id();
                navigationNodes.add(AppearanceCollections.navigation(
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
                SkinCompatibility compatibility = model.compatibility(
                        collection.id(), skin, variant);
                boolean hasCompatibility = compatibility.status()
                        != SkinCompatibilityStatus.ORDINARY;
                var chrome = AppearanceCard.chrome(prefix, prefix, card, ViewSpec.WidgetKind.CATALOG_CARD,
                        UiMessage.literal(model.skinName(skin), UiMessage.Severity.INFO), Optional.empty(), !busy);
                panels.add(chrome.surface());
                widgets.add(chrome.widget());
                Optional<String> skinInfo = model.skinInfo(collection, skin);
                boolean renamingThisCard = personalRename
                        .filter(rename -> rename.collectionId().equals(collection.id()))
                        .filter(rename -> rename.sha256().equals(skin.id()))
                        .isPresent();
                Bounds nameBounds = CatalogCardGeometry.name(card);
                if (!renamingThisCard && intersectsViewport(nameBounds, contentBottom)) {
                    texts.add(AppearanceCard.name(prefix + ".name", nameBounds,
                            UiMessage.literal(model.skinName(skin), UiMessage.Severity.INFO), card,
                            catalogMarqueeFocusIds(collection, skin, prefix)));
                    skinInfo.ifPresent(info -> tooltipRegions.add(AppearanceCard.info(
                            "add.catalog.tooltip:skin:" + collection.id() + ":" + skin.id(), nameBounds,
                            UiMessage.literal(model.skinName(skin), UiMessage.Severity.INFO), ViewSpec.Text.Alignment.CENTER, info)));
                }
                boolean personal = collection.order().kind() == CatalogCollectionOrder.Kind.PERSONAL;
                AppearanceCard.Role role = personal ? AppearanceCard.Role.PERSONAL : AppearanceCard.Role.READ_ONLY;
                Bounds previewBounds = role.preview(card);
                if (intersectsViewport(previewBounds, contentBottom)) {
                    previews.add(new AppearanceCard.Skin(SkinReference.accountDefault(),
                            "catalog:" + collection.id() + ":" + skin.id() + ":" + variant.name(), variant,
                            Optional.empty(), PreviewRenderer.CapeMode.OFF, OuterLayerVisibility.allVisible(),
                            -20.0F, 0.0F, 1.0F, Optional.empty(),
                            Optional.of(new ViewSpec.CatalogImage(collection.id(), skin.id())), Optional.empty(),
                            PreviewRenderer.PreviewIntent.ASSET_THUMBNAIL, true).present(prefix + ".preview", previewBounds));
                }
                if (hasCompatibility) {
                    String indicatorId = prefix + ".compatibility";
                    int indicatorBottom = personal
                            ? card.bottom() - 24
                            : card.bottom() - 2;
                    ViewSpec.Widget indicator = AppearanceCard.compatibility(indicatorId, card, indicatorBottom, compatibility);
                    addIntersectingWidget(widgets, indicator, contentBottom);
                }
                if (renamingThisCard || personal) {
                    boolean pendingDelete = model.personalSkinDeletion()
                            .filter(deletion -> deletion.collectionId().equals(collection.id()))
                            .filter(deletion -> deletion.sha256().equals(skin.id())).isPresent();
                    List<ViewSpec.Widget> actions = personalActions(collection, skin, card, busy, transientMode,
                            renamingThisCard ? personalRename : Optional.empty(), pendingDelete);
                    for (var action : actions) {
                        if (pendingDelete && !renamingThisCard) {
                            addIntersectingNavigableWidget(widgets, action, contentBottom, navigationNodes);
                        } else {
                            addIntersectingWidget(widgets, action, contentBottom);
                        }
                    }
                }
            }
        }
    }

    private static List<ViewSpec.Widget> personalActions(SkinCatalogSource.CollectionDescriptor collection, SkinCatalogSource.SkinDescriptor skin,
            Bounds card, boolean busy, boolean transientMode, Optional<PersonalSkinRename> rename,
            boolean pendingDelete) {
        Optional<AppearanceCard.Rename> field = rename.map(value -> new AppearanceCard.Rename(
                "add.catalog.rename.name", UiMessage.info("nclskins.your_skins.rename"), value.value(),
                UiMessage.info("nclskins.your_skins.rename_hint"), !busy, "add.catalog.rename.save"));
        AppearanceCard.Action left;
        AppearanceCard.Action right;
        if (rename.isPresent()) {
            left = new AppearanceCard.Action("add.catalog.rename.save", UiMessage.info("nclskins.your_skins.rename_save"),
                    NativeGuiIcon.ACCEPT, !busy && !rename.orElseThrow().value().trim().isEmpty());
            right = new AppearanceCard.Action("add.catalog.rename.cancel", UiMessage.info("gui.cancel"), NativeGuiIcon.REJECT, !busy);
        } else if (pendingDelete) {
            left = new AppearanceCard.Action("add.catalog.delete.confirm", UiMessage.info("nclskins.your_skins.delete_confirm"),
                    NativeGuiIcon.ACCEPT, !busy);
            right = new AppearanceCard.Action("add.catalog.delete.cancel", UiMessage.info("gui.cancel"), NativeGuiIcon.REJECT, !busy);
        } else {
            left = new AppearanceCard.Action(AddSourceModel.personalActionId("add.catalog.rename:", collection.id(), skin.id()),
                    UiMessage.info("nclskins.your_skins.rename"), GuiIcon.ACTION_RENAME, !busy && !transientMode);
            right = new AppearanceCard.Action(AddSourceModel.personalActionId("add.catalog.delete:", collection.id(), skin.id()),
                    UiMessage.info("nclskins.your_skins.delete"), GuiIcon.ACTION_DELETE, !busy && !transientMode);
        }
        return AppearanceCard.actions(card, field, left, right);
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

    private static int collectionCardHeight(
            SkinCatalogSource.CollectionDescriptor collection, int personalHeight) {
        AppearanceCard.Role role = collection.order().kind() == CatalogCollectionOrder.Kind.PERSONAL
                ? AppearanceCard.Role.PERSONAL : AppearanceCard.Role.READ_ONLY;
        return role.height(personalHeight);
    }

    private static List<AppearanceCollections.Section> collectionSections(AddSourceModel model,
            List<SkinCatalogSource.CollectionDescriptor> collections, int personalHeight) {
        return collections.stream().map(collection -> new AppearanceCollections.Section(collection.id(),
                model.visibleSkins(collection).size(), model.collectionCollapsed(collection.id()),
                collectionCardHeight(collection, personalHeight))).toList();
    }

    private static CatalogLayout catalogLayout(
            AddSourceModel model, int width, int height, ViewChromeMetrics chromeMetrics, Optional<UiMessage> status) {
        Objects.requireNonNull(chromeMetrics, "chromeMetrics");
        boolean scoped = model.importContext().canCancelPending() || importFeedback(status);
        int contentTop = scoped ? CONTROLS_TOP + contextTitleShift(model, width) : CONTENT_TOP;
        int contentWidth = Math.min(520, Math.max(1, width - 32));
        int errorHeight = status.filter(value -> value.severity() == UiMessage.Severity.ERROR
                        || value.key().equals("nclskins.add.cpm_publication_loading"))
                .map(value -> Math.max(20, model.messageHeight(value, model.selectedTab() == AddSourceTab.FILE ? contentWidth : Math.max(1, width - 32)))).orElse(0);

        int prefix = scoped ? CONTROLS_HEIGHT + CONTROLS_GAP
                + (errorHeight == 0 ? 0 : errorHeight + CONTROLS_GAP) : 0;
        boolean form = scoped && model.selectedTab() == AddSourceTab.FILE;
        if (form) prefix = 157 + model.messageHeight(UiMessage.info("nclskins.add_source.url_privacy"), contentWidth)
                + CONTROLS_GAP + errorHeight + 8;
        List<SkinCatalogSource.CollectionDescriptor> collections = model.visibleCollections();
        CollectionGridLayout.CardMetrics metrics = CatalogCardSizing.reference(width, height, chromeMetrics);
        List<CollectionGridLayout.Section> sections = AppearanceCollections.gridSections(
                form ? List.of() : collectionSections(model, collections, metrics.height()));
        CollectionGridLayout.Layout layout = CollectionGridLayout.calculate(
                width,
                height,
                contentTop,
                chromeMetrics.catalogFooterHeight(),
                prefix + (scoped && !form && sections.isEmpty() ? 24 : 0),
                0,
                COLLECTION_HEADER_HEIGHT,
                CatalogCardSizing.GAP,
                metrics,
                model.scrollOffset(),
                sections);
        return new CatalogLayout(
                layout.columns(),
                layout.cardWidth(),
                layout.cardStartX(),
                layout.cardHeight(),
                layout.contentRight(),
                contentTop,
                contentTop + prefix,
                layout.contentBottom(),
                layout.maximum(),
                layout.itemCount(),
                layout.scrollbar());
    }

    private static boolean importFeedback(Optional<UiMessage> status) {
        return status.map(value -> value.key().startsWith("nclskins.add.cpm_publication_")
                || value.key().startsWith("nclskins.add_source.cpm.")).orElse(false);
    }

    private static Optional<UiMessage> projectedStatus(AddSourceModel model, Optional<UiMessage> ordinary) {
        return model.importContext().failure().map(reason -> switch (reason) {
            case CPM_MISSING -> UiMessage.error("nclskins.add_source.cpm.dependency_missing");
            case CPM_UNAVAILABLE -> UiMessage.error("nclskins.add_source.cpm.adapter_unavailable");
            case NESTED_MODEL -> UiMessage.error("nclskins.add_source.cpm.nested_file");
            case INVALID_MODEL -> UiMessage.error("nclskins.add_source.cpm.invalid_model");
            case MODEL_UNSUPPORTED -> UiMessage.error("nclskins.add_source.cpm.model_unsupported");
            case IO_FAILED -> UiMessage.error("nclskins.add_source.file_io_error");
            case VARIANT_UNSUPPORTED -> UiMessage.error(requiredTypeMessage(model).key());
            case NONPORTABLE_OVERFLOW -> UiMessage.error("nclskins.add_source.cpm.local_overflow");
            case PORTABILITY_UNSUPPORTED -> UiMessage.error("nclskins.add_source.cpm.portability_unsupported");
            case CONVERSION_FAILED -> UiMessage.error("nclskins.add_source.cpm.conversion_failed");
            case INVALID_OUTPUT_PNG -> UiMessage.error("nclskins.add_source.cpm.invalid_output");
            case STALE_RESOURCES -> UiMessage.error("nclskins.add_source.cpm.stale_resources");
            case STALE_WORLD -> UiMessage.error("nclskins.add_source.cpm.stale_world");
            case CLOSED -> null;
        }).or(() -> model.importContext().failure().isPresent() ? Optional.empty() : ordinary);
    }

    private static Optional<UiMessage> blockedSourceHint(AddSourceModel model, AddSourceModel.SourceAction action) {
        return model.importContext().allows(action) ? Optional.empty()
                : Optional.of(UiMessage.info(action == AddSourceModel.SourceAction.LAUNCHER
                        ? "nclskins.add_source.cpm.launcher_blocked" : "nclskins.add_source.cpm.mod_blocked"));
    }

    private static UiMessage requiredTypeMessage(AddSourceModel model) {
        return UiMessage.info(model.importContext().requiredVariant().orElseThrow()
                == com.naocraftlab.skins.client.SkinModel.SLIM
                ? "nclskins.add_source.cpm.required_slim" : "nclskins.add_source.cpm.required_classic");
    }

    private static int contextTitleHeight(AddSourceModel model, int width) {
        return model.importContext().canCancelPending()
                ? model.messageHeight(UiMessage.info("nclskins.add_source.cpm.title"), Math.max(1, width - 32)) : 0;
    }

    private static int contextTitleShift(AddSourceModel model, int width) {
        int height = contextTitleHeight(model, width);
        return height == 0 ? 0 : height + CONTROLS_GAP;
    }

    private static boolean intersectsViewport(Bounds bounds, int contentBottom) {
        return AppearanceCollections.visibleVertically(bounds,
                new Bounds(0, CONTENT_TOP, 1, Math.max(1, contentBottom - CONTENT_TOP)));
    }

    private static void addIntersectingWidget(
            List<ViewSpec.Widget> widgets, ViewSpec.Widget widget, int contentBottom) {
        if (intersectsViewport(widget.bounds(), contentBottom)) {
            widgets.add(widget);
        }
    }

    private static void addIntersectingNavigableWidget(
            List<ViewSpec.Widget> widgets,
            ViewSpec.Widget widget,
            int contentBottom,
            List<ViewSpec.NavigationNode> navigationNodes) {
        if (intersectsViewport(widget.bounds(), contentBottom)) {
            widgets.add(widget);
            navigationNodes.add(ViewSpec.NavigationNode.control(
                    widget, navigationNodes.size(), -1));
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
            int contentTop,
            int contentStart,
            int contentBottom,
            int maximum,
            int matchingSkinCount,
            Optional<ViewSpec.Scrollbar> scrollbar) {}
}

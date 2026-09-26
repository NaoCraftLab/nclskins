package com.naocraftlab.skins.runtime;

import com.naocraftlab.skins.client.OuterLayerVisibility;
import com.naocraftlab.skins.client.PreviewRenderer;
import com.naocraftlab.skins.core.compatibility.SkinCompatibility;
import com.naocraftlab.skins.core.compatibility.SkinCompatibilityEvaluator;
import com.naocraftlab.skins.core.compatibility.SkinCompatibilityStatus;
import com.naocraftlab.skins.core.compatibility.SkinExtensionEnvironment;
import com.naocraftlab.skins.core.importing.ExternalImportSource;
import com.naocraftlab.skins.core.model.SkinReference;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;


public final class ExternalImportPresenter {
    private static final int CHROME_HEIGHT = 33;
    private static final int REVIEW_CONTENT_TOP_INSET = 4;
    private static final int FOOTER_HEIGHT = 33;
    private static final int HEADER_HEIGHT = 16;
    private static final int CARD_GAP = 6;
    private static final int DISCLOSURE_BUTTON_SIZE = 20;
    private static final int CONTROL_GAP = 6;

    public ViewSpec present(
            ExternalImportModel model,
            boolean busy,
            Optional<UiMessage> status,
            int width,
            int height) {
        return present(model, busy, status, width, height, SkinExtensionEnvironment.unknown(0));
    }

    public ViewSpec present(
            ExternalImportModel model,
            boolean busy,
            Optional<UiMessage> status,
            int width,
            int height,
            SkinExtensionEnvironment environment) {
        Objects.requireNonNull(model, "model");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(environment, "environment");
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("view dimensions must be positive");
        }
        return model.review().isPresent()
                ? presentReview(model, busy, status, width, height, environment)
                : presentChooser(model, busy, status, width, height);
    }

    private static ViewSpec presentChooser(
            ExternalImportModel model,
            boolean busy,
            Optional<UiMessage> status,
            int width,
            int height) {
        int contentWidth = Math.min(320, Math.max(180, width - 32));
        int x = (width - contentWidth) / 2;
        UiMessage title = UiMessage.info(model.category() == ExternalImportModel.Category.LAUNCHER
                ? "nclskins.external_import.launcher_title"
                : "nclskins.external_import.mod_title");
        List<ViewSpec.Panel> panels = List.of(
                new ViewSpec.Panel(
                        "header",
                        new Bounds(0, 0, width, CHROME_HEIGHT),
                        ViewSpec.Panel.Style.VANILLA_HEADER),
                new ViewSpec.Panel(
                        "footer",
                        new Bounds(0, Math.max(0, height - FOOTER_HEIGHT), width, FOOTER_HEIGHT),
                        ViewSpec.Panel.Style.VANILLA_FOOTER));
        List<ViewSpec.Widget> widgets = new ArrayList<>();
        List<ViewSpec.Text> texts = new ArrayList<>();
        texts.add(new ViewSpec.Text(
                "external.title",
                new Bounds(8, 12, Math.max(1, width - 16), 10),
                title,
                ViewSpec.Text.Alignment.CENTER));
        int folderWidth = 20;
        int rowGap = 2;
        int sourceButtonWidth = contentWidth - folderWidth - rowGap;
        int y = 42;
        for (ExternalImportSource source : model.category().sources()) {
            ExternalImportModel.SourceState state = model.sources().get(source);
            Optional<UiMessage> dependencyHint =
                    state.availability() == ExternalImportModel.Availability.DEPENDENCY_MISSING
                            ? Optional.of(UiMessage.info(
                            "nclskins.external_import.sqlite_dependency_required"))
                            : Optional.empty();
            Optional<String> stateKey = visibleStateKey(source, state);
            Optional<UiMessage> sourceHint = dependencyHint.isPresent()
                    ? dependencyHint
                    : stateKey.map(UiMessage::info);
            widgets.add(ViewSpec.Widget.button(
                    sourceId(source),
                    new Bounds(x, y, sourceButtonWidth, 20),
                    UiMessage.info(sourceLabel(source)),
                    sourceHint,
                    !busy && state.availability().available()));
            widgets.add(ViewSpec.Widget.iconButton(
                    folderId(source),
                    new Bounds(x + contentWidth - folderWidth, y, folderWidth, 20),
                    UiMessage.info("nclskins.external_import.choose_folder"),
                    dependencyHint.isPresent()
                            ? dependencyHint
                            : Optional.of(UiMessage.info("nclskins.external_import.choose_folder")),
                    GuiIcon.ACTION_SELECT_FOLDER,
                    !busy && state.availability()
                            != ExternalImportModel.Availability.DEPENDENCY_MISSING));
            y += 24;
        }
        int footerTop = Math.max(0, height - FOOTER_HEIGHT);
        int statusY = Math.min(
                Math.max(CHROME_HEIGHT + 4, footerTop - 14),
                y + 4);
        int statusBottom = Math.max(statusY + 1, footerTop - 4);
        status.filter(message -> message.severity() == UiMessage.Severity.ERROR)
                .ifPresent(message -> texts.add(new ViewSpec.Text(
                        "external.status",
                        new Bounds(x, statusY, contentWidth, statusBottom - statusY),
                        message,
                        ViewSpec.Text.Alignment.CENTER,
                        ViewSpec.Text.Layout.WRAP)));
        int backWidth = Math.min(200, Math.max(100, width - 32));
        widgets.add(ViewSpec.Widget.button(
                "external.back",
                new Bounds((width - backWidth) / 2, Math.max(0, height - 27), backWidth, 20),
                UiMessage.info("gui.back"),
                !busy));
        return new ViewSpec(
                "external_chooser",
                title,
                width,
                height,
                panels,
                texts,
                widgets,
                List.of(),
                Optional.empty());
    }

    private static ViewSpec presentReview(
            ExternalImportModel model,
            boolean busy,
            Optional<UiMessage> status,
            int width,
            int height,
            SkinExtensionEnvironment environment) {
        ExternalImportModel.ReviewState review = model.review().orElseThrow();
        List<ClientOperations.ExternalImportCandidate> fresh = review.candidates(false);
        List<ClientOperations.ExternalImportCandidate> duplicates = review.candidates(true);
        List<CollectionGridLayout.Section> sections = new ArrayList<>();
        if (!fresh.isEmpty()) {
            sections.add(new CollectionGridLayout.Section(
                    fresh.size(), review.collectionCollapsed(false)));
        }
        if (!duplicates.isEmpty()) {
            sections.add(new CollectionGridLayout.Section(
                    duplicates.size(), review.collectionCollapsed(true)));
        }
        CollectionGridLayout.Layout layout = reviewLayout(
                width, height, review.scrollOffset(), sections);
        List<ViewSpec.Panel> panels = new ArrayList<>();
        panels.add(new ViewSpec.Panel(
                "header", new Bounds(0, 0, width, CHROME_HEIGHT), ViewSpec.Panel.Style.VANILLA_HEADER));
        panels.add(new ViewSpec.Panel(
                "footer",
                new Bounds(0, Math.max(0, height - FOOTER_HEIGHT), width, FOOTER_HEIGHT),
                ViewSpec.Panel.Style.VANILLA_FOOTER));
        List<ViewSpec.Text> texts = new ArrayList<>();
        List<ViewSpec.Widget> widgets = new ArrayList<>();
        List<ViewSpec.Preview> previews = new ArrayList<>();
        List<ViewSpec.IconDecoration> iconDecorations = new ArrayList<>();
        List<ViewSpec.NavigationNode> navigationNodes = new ArrayList<>();
        int toggleWidth = Math.min(108, Math.max(76, width / 3));
        int disclosureX = width - 8 - DISCLOSURE_BUTTON_SIZE;
        int toggleX = disclosureX - CONTROL_GAP - toggleWidth;
        texts.add(new ViewSpec.Text(
                "external.review.title",
                new Bounds(8, 12, Math.max(1, toggleX - 16), 10),
                UiMessage.info("nclskins.external_import.review_title"),
                ViewSpec.Text.Alignment.CENTER));
        widgets.add(ViewSpec.Widget.button(
                "external.review.toggle_all",
                new Bounds(toggleX, 6, toggleWidth, 20),
                UiMessage.info(review.allSelected()
                        ? "nclskins.external_import.clear_all"
                        : "nclskins.external_import.select_all"),
                !busy));
        boolean anyCollapsed = review.anyCollectionCollapsed();
        widgets.add(ViewSpec.Widget.iconButton(
                "external.review.disclosure",
                new Bounds(disclosureX, 6, DISCLOSURE_BUTTON_SIZE, DISCLOSURE_BUTTON_SIZE),
                UiMessage.info(anyCollapsed
                        ? "nclskins.collection.expand_all"
                        : "nclskins.collection.collapse_all"),
                anyCollapsed ? GuiIcon.ACTION_EXPAND_ALL : GuiIcon.ACTION_COLLAPSE_ALL,
                !busy && !review.availableCollections().isEmpty()));
        addSection(false, fresh, review, layout, busy, environment,
                panels, texts, widgets, previews, iconDecorations, navigationNodes);
        addSection(true, duplicates, review, layout, busy, environment,
                panels, texts, widgets, previews, iconDecorations, navigationNodes);
        int selected = review.selectedIds().size();
        int footerWidth = Math.min(420, Math.max(180, width - 32));
        int importWidth = Math.max(96, (footerWidth - 6) * 2 / 3);
        int footerX = (width - footerWidth) / 2;
        widgets.add(ViewSpec.Widget.button(
                "external.review.commit",
                new Bounds(footerX, height - 27, importWidth, 20),
                UiMessage.info("nclskins.external_import.import_selected", selected),
                !busy && selected > 0));
        widgets.add(ViewSpec.Widget.button(
                "external.review.cancel",
                new Bounds(footerX + importWidth + 6, height - 27, footerWidth - importWidth - 6, 20),
                UiMessage.info("gui.cancel"),
                !busy));
        status.filter(message -> message.severity() == UiMessage.Severity.ERROR)
                .ifPresent(message -> texts.add(new ViewSpec.Text(
                        "external.review.status",
                        new Bounds(12, 35, Math.max(1, width - 24), 10),
                        message,
                        ViewSpec.Text.Alignment.LEFT)));
        Bounds viewport = new Bounds(
                0,
                CHROME_HEIGHT,
                width,
                Math.max(1, layout.contentBottom() - CHROME_HEIGHT));
        List<ViewSpec.ScrollSurface> surfaces = List.of(new ViewSpec.ScrollSurface(
                "external.review",
                viewport,
                ViewSpec.Scrollbar.Orientation.VERTICAL,
                layout.scrollOffset(),
                layout.maximum()));
        return new ViewSpec(
                "external_review",
                UiMessage.info("nclskins.external_import.review_title"),
                width,
                height,
                panels,
                texts,
                widgets,
                previews,
                layout.scrollbar(),
                List.of(),
                Optional.empty(),
                List.of(AppearanceCollections.clip(
                        "external.review.viewport",
                        viewport,
                        List.of("external.review.collection.", "external.review.card:"))),
                List.of(),
                iconDecorations,
                surfaces).withNavigationNodes(navigationNodes);
    }

    public int normalizedReviewScrollOffset(
            ExternalImportModel model, int width, int height, int desired) {
        Objects.requireNonNull(model, "model");
        ExternalImportModel.ReviewState review = model.review().orElseThrow();
        List<CollectionGridLayout.Section> sections = new ArrayList<>();
        List<ClientOperations.ExternalImportCandidate> fresh = review.candidates(false);
        List<ClientOperations.ExternalImportCandidate> duplicates = review.candidates(true);
        if (!fresh.isEmpty()) {
            sections.add(new CollectionGridLayout.Section(
                    fresh.size(), review.collectionCollapsed(false)));
        }
        if (!duplicates.isEmpty()) {
            sections.add(new CollectionGridLayout.Section(
                    duplicates.size(), review.collectionCollapsed(true)));
        }
        return reviewLayout(width, height, desired, sections).scrollOffset();
    }

    private static CollectionGridLayout.Layout reviewLayout(
            int width,
            int height,
            int scrollOffset,
            List<CollectionGridLayout.Section> sections) {
        return CollectionGridLayout.calculate(
                width,
                height,
                CHROME_HEIGHT,
                FOOTER_HEIGHT,
                REVIEW_CONTENT_TOP_INSET,
                0,
                HEADER_HEIGHT,
                CARD_GAP,
                68,
                96,
                72,
                132,
                scrollOffset,
                sections);
    }

    private static void addSection(
            boolean duplicateSection,
            List<ClientOperations.ExternalImportCandidate> candidates,
            ExternalImportModel.ReviewState review,
            CollectionGridLayout.Layout layout,
            boolean busy,
            SkinExtensionEnvironment environment,
            List<ViewSpec.Panel> panels,
            List<ViewSpec.Text> texts,
            List<ViewSpec.Widget> widgets,
            List<ViewSpec.Preview> previews,
            List<ViewSpec.IconDecoration> iconDecorations,
            List<ViewSpec.NavigationNode> navigationNodes) {
        if (candidates.isEmpty()) return;
        String sectionId = duplicateSection ? "duplicates" : "new";
        List<AppearanceCollections.Section> sections = new ArrayList<>();
        for (boolean duplicate : List.of(false, true)) {
            int count = review.candidates(duplicate).size();
            if (count > 0) sections.add(new AppearanceCollections.Section(duplicate ? "duplicates" : "new",
                    count, review.collectionCollapsed(duplicate), layout.cardHeight()));
        }
        var section = AppearanceCollections.layout(sections, layout.columns()).sections().stream()
                .filter(value -> value.section().id().equals(sectionId)).findFirst().orElseThrow();
        int contentTop = layout.contentStart() - layout.scrollOffset();
        Bounds header = section.header(16, contentTop, Math.max(1, layout.contentRight() - 16));
        String headerId = "external.review.collection." + sectionId;
        Bounds viewport = new Bounds(0, CHROME_HEIGHT, layout.contentRight(), Math.max(1, layout.contentBottom() - CHROME_HEIGHT));
        var headerPresentation = AppearanceCollections.header(headerId, header,
                UiMessage.info(collectionHeaderKey(duplicateSection, review.collectionCollapsed(duplicateSection)), candidates.size()),
                !busy, viewport, "external.review", navigationNodes.size(), -1);
        navigationNodes.add(headerPresentation.navigation());
        headerPresentation.widget().ifPresent(widgets::add);
        if (section.section().collapsed()) return;
        for (int index = 0; index < candidates.size(); index++) {
            ClientOperations.ExternalImportCandidate candidate = candidates.get(index);
            Bounds card = section.card(index, layout.cardStartX(), contentTop, layout.cardWidth());
            String id = "external.review.card:" + candidate.id();
            navigationNodes.add(AppearanceCollections.navigation(
                    id,
                    card,
                    "external.review",
                    navigationNodes.size(),
                    -1,
                    !busy,
                    ViewSpec.NavigationPattern.GRID,
                    Optional.empty()));
            if (!intersects(card, layout.contentBottom())) {
                continue;
            }
            var chrome = AppearanceCard.chrome(id, id, card, ViewSpec.WidgetKind.SELECTABLE_CARD,
                    UiMessage.literal(candidate.displayName(), UiMessage.Severity.INFO),
                    review.selectedIds().contains(candidate.id()) ? Optional.of("selected") : Optional.empty(), !busy);
            panels.add(chrome.surface());
            widgets.add(chrome.widget());
            texts.add(AppearanceCard.name(id + ".name", CatalogCardGeometry.name(card),
                    UiMessage.literal(candidate.displayName(), UiMessage.Severity.INFO), card, List.of(id)));
            Optional<String> capeId = Optional.ofNullable(candidate.capeId());
            previews.add(new AppearanceCard.Skin(SkinReference.accountDefault(),
                    "external:" + candidate.sha256() + ":" + candidate.variant().name(), candidate.variant(), capeId,
                    capeId.isPresent() ? PreviewRenderer.CapeMode.CAPE : PreviewRenderer.CapeMode.OFF,
                    OuterLayerVisibility.allVisible(), -20.0F, 0.0F, 1.0F, Optional.empty(), Optional.empty(),
                    Optional.of(new ViewSpec.ExternalImage(candidate.id())), PreviewRenderer.PreviewIntent.ASSET_THUMBNAIL, true)
                    .present(id + ".preview", AppearanceCard.Role.READ_ONLY.preview(card)));
            SkinCompatibility compatibility = new SkinCompatibilityEvaluator().evaluate(
                    candidate.featureEvidence(), environment);
            if (compatibility.status() != SkinCompatibilityStatus.ORDINARY) {
                widgets.add(AppearanceCard.compatibility(id + ".compatibility", card, card.bottom() - 2, compatibility));
            }
        }
    }

    private static boolean intersects(Bounds bounds, int bottom) {
        return AppearanceCollections.visibleVertically(bounds,
                new Bounds(0, CHROME_HEIGHT, 1, Math.max(1, bottom - CHROME_HEIGHT)));
    }

    private static String collectionHeaderKey(boolean duplicates, boolean collapsed) {
        if (duplicates) {
            return collapsed
                    ? "nclskins.external_import.duplicates_collapsed"
                    : "nclskins.external_import.duplicates_expanded";
        }
        return collapsed
                ? "nclskins.external_import.new_collapsed"
                : "nclskins.external_import.new_expanded";
    }

    private static Optional<String> visibleStateKey(
            ExternalImportSource source, ExternalImportModel.SourceState state) {
        if (state.manualFailures() > 0 && state.availability() != ExternalImportModel.Availability.AVAILABLE_MANUAL) {
            return Optional.of("nclskins.external_import.invalid_folder." + sourceKey(source));
        }
        return switch (state.availability()) {
            case PROBING, DEPENDENCY_MISSING -> Optional.empty();
            case UNAVAILABLE -> Optional.of(
                    "nclskins.external_import.unavailable." + sourceKey(source));
            case AVAILABLE_STANDARD -> Optional.empty();
            case AVAILABLE_MANUAL -> Optional.of("nclskins.external_import.available_folder");
        };
    }

    private static String sourceLabel(ExternalImportSource source) {
        return "nclskins.external_import." + sourceKey(source);
    }

    private static String sourceKey(ExternalImportSource source) {
        return switch (source) {
            case MINECRAFT_LAUNCHER -> "minecraft_launcher";
            case CURSEFORGE_APP -> "curseforge_app";
            case MODRINTH_APP -> "modrinth_app";
            case SKIN_SHUFFLE -> "skin_shuffle";
            case SKIN_SWAPPER_FAMILY -> "skin_swapper_family";
            case QUICK_SKIN -> "quick_skin";
            case PRISM_LAUNCHER -> "prism_launcher";
        };
    }

    public static String sourceId(ExternalImportSource source) {
        return "external.source." + sourceKey(source);
    }

    public static String folderId(ExternalImportSource source) {
        return "external.folder." + sourceKey(source);
    }

    public static ExternalImportSource source(String widgetId) {
        String suffix;
        if (widgetId.startsWith("external.source.")) {
            suffix = widgetId.substring("external.source.".length());
        } else if (widgetId.startsWith("external.folder.")) {
            suffix = widgetId.substring("external.folder.".length());
        } else {
            throw new IllegalArgumentException("Unknown external import widget");
        }
        return switch (suffix) {
            case "minecraft_launcher" -> ExternalImportSource.MINECRAFT_LAUNCHER;
            case "curseforge_app" -> ExternalImportSource.CURSEFORGE_APP;
            case "modrinth_app" -> ExternalImportSource.MODRINTH_APP;
            case "skin_shuffle" -> ExternalImportSource.SKIN_SHUFFLE;
            case "skin_swapper_family" -> ExternalImportSource.SKIN_SWAPPER_FAMILY;
            case "quick_skin" -> ExternalImportSource.QUICK_SKIN;
            case "prism_launcher" -> ExternalImportSource.PRISM_LAUNCHER;
            default -> throw new IllegalArgumentException("Unknown external import widget");
        };
    }
}

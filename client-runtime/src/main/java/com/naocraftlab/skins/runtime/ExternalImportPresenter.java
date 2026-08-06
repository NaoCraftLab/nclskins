package com.naocraftlab.skins.runtime;

import com.naocraftlab.skins.client.OuterLayerVisibility;
import com.naocraftlab.skins.client.PreviewRenderer;
import com.naocraftlab.skins.core.importing.ExternalImportSource;
import com.naocraftlab.skins.core.model.SkinReference;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;


public final class ExternalImportPresenter {
    private static final int CHROME_HEIGHT = 33;
    private static final int REVIEW_CONTENT_PADDING = 4;
    private static final int REVIEW_ERROR_CONTENT_PADDING = 18;
    private static final int FOOTER_HEIGHT = 33;
    private static final int HEADER_HEIGHT = 16;
    private static final int CARD_GAP = 6;

    public ViewSpec present(
            ExternalImportModel model,
            boolean busy,
            Optional<UiMessage> status,
            int width,
            int height) {
        Objects.requireNonNull(model, "model");
        Objects.requireNonNull(status, "status");
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("view dimensions must be positive");
        }
        return model.review().isPresent()
                ? presentReview(model, busy, status, width, height)
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
        int y = 42;
        for (ExternalImportSource source : model.category().sources()) {
            ExternalImportModel.SourceState state = model.sources().get(source);
            int folderWidth = 20;
            int rowGap = 2;
            widgets.add(ViewSpec.Widget.button(
                    sourceId(source),
                    new Bounds(x, y, contentWidth - folderWidth - rowGap, 20),
                    UiMessage.info(sourceLabel(source)),
                    !busy && state.availability().available()));
            widgets.add(ViewSpec.Widget.iconButton(
                    folderId(source),
                    new Bounds(x + contentWidth - folderWidth, y, folderWidth, 20),
                    UiMessage.info("nclskins.external_import.choose_folder"),
                    "folder",
                    !busy));
            Optional<String> stateKey = visibleStateKey(source, state);
            if (stateKey.isPresent()) {
                texts.add(new ViewSpec.Text(
                        sourceId(source) + ".state",
                        new Bounds(x, y + 24, contentWidth, 10),
                        UiMessage.info(stateKey.orElseThrow()),
                        ViewSpec.Text.Alignment.LEFT));
            }
            y += stateKey.isPresent() ? 38 : 24;
        }
        int statusY = Math.max(y + 22, height - 48);
        status.filter(message -> !"nclskins.external_import.choose_source".equals(message.key()))
                .ifPresent(message -> texts.add(new ViewSpec.Text(
                        "external.status",
                        new Bounds(x, statusY, contentWidth, 10),
                        message,
                        ViewSpec.Text.Alignment.CENTER)));
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
            int height) {
        ExternalImportModel.ReviewState review = model.review().orElseThrow();
        boolean showError = status.stream()
                .anyMatch(message -> message.severity() == UiMessage.Severity.ERROR);
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
        CollectionGridLayout.Layout layout = CollectionGridLayout.calculate(
                width,
                height,
                CHROME_HEIGHT,
                FOOTER_HEIGHT,
                showError ? REVIEW_ERROR_CONTENT_PADDING : REVIEW_CONTENT_PADDING,
                0,
                HEADER_HEIGHT,
                CARD_GAP,
                68,
                96,
                72,
                132,
                review.scrollOffset(),
                sections);
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
        int toggleWidth = Math.min(108, Math.max(76, width / 3));
        texts.add(new ViewSpec.Text(
                "external.review.title",
                new Bounds(8, 12, Math.max(1, width - toggleWidth - 28), 10),
                UiMessage.info("nclskins.external_import.review_title"),
                ViewSpec.Text.Alignment.CENTER));
        widgets.add(ViewSpec.Widget.button(
                "external.review.toggle_all",
                new Bounds(width - toggleWidth - 8, 6, toggleWidth, 20),
                UiMessage.info(review.allSelected()
                        ? "nclskins.external_import.clear_all"
                        : "nclskins.external_import.select_all"),
                !busy));
        addSection(false, fresh, review, layout, busy, panels, texts, widgets, previews);
        addSection(true, duplicates, review, layout, busy, panels, texts, widgets, previews);
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
                        new Bounds(12, 39, Math.max(1, width - toggleWidth - 28), 10),
                        message,
                        ViewSpec.Text.Alignment.LEFT)));
        Bounds viewport = new Bounds(
                0, CHROME_HEIGHT, width, Math.max(1, layout.contentBottom() - CHROME_HEIGHT));
        List<ViewSpec.ScrollSurface> surfaces = layout.maximum() == 0
                ? List.of()
                : List.of(new ViewSpec.ScrollSurface(
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
                List.of(new ViewSpec.ClipRegion(
                        "external.review.viewport",
                        viewport,
                        List.of("external.review.collection.", "external.review.card:"))),
                List.of(),
                List.of(),
                surfaces);
    }

    private static void addSection(
            boolean duplicateSection,
            List<ClientOperations.ExternalImportCandidate> candidates,
            ExternalImportModel.ReviewState review,
            CollectionGridLayout.Layout layout,
            boolean busy,
            List<ViewSpec.Panel> panels,
            List<ViewSpec.Text> texts,
            List<ViewSpec.Widget> widgets,
            List<ViewSpec.Preview> previews) {
        int y = layout.contentStart() - layout.scrollOffset();
        if (duplicateSection) {
            int freshCount = review.candidates(false).size();
            if (freshCount > 0) {
                y += sectionHeight(
                        freshCount,
                        review.collectionCollapsed(false),
                        layout.columns(),
                        layout.cardHeight());
            }
        }
        if (candidates.isEmpty()) {
            return;
        }
        String sectionId = duplicateSection ? "duplicates" : "new";
        Bounds header = new Bounds(16, y, Math.max(1, layout.contentRight() - 16), HEADER_HEIGHT);
        if (intersects(header, layout.contentBottom())) {
            widgets.add(ViewSpec.Widget.collectionHeader(
                    "external.review.collection." + sectionId,
                    header,
                    UiMessage.info(collectionHeaderKey(
                            duplicateSection,
                            review.collectionCollapsed(duplicateSection)), candidates.size()),
                    !busy,
                    false));
        }
        y += HEADER_HEIGHT + 4;
        if (review.collectionCollapsed(duplicateSection)) {
            return;
        }
        for (int index = 0; index < candidates.size(); index++) {
            ClientOperations.ExternalImportCandidate candidate = candidates.get(index);
            int column = index % layout.columns();
            int row = index / layout.columns();
            Bounds card = new Bounds(
                    layout.cardStartX() + column * (layout.cardWidth() + CARD_GAP),
                    y + row * (layout.cardHeight() + CARD_GAP),
                    layout.cardWidth(),
                    layout.cardHeight());
            if (!intersects(card, layout.contentBottom())) {
                continue;
            }
            String id = "external.review.card:" + candidate.id();
            panels.add(new ViewSpec.Panel(id, card, ViewSpec.Panel.Style.VANILLA_LIST));
            widgets.add(ViewSpec.Widget.selectableCard(
                    id,
                    card,
                    UiMessage.literal(candidate.displayName(), UiMessage.Severity.INFO),
                    review.selectedIds().contains(candidate.id()),
                    !busy));
            texts.add(new ViewSpec.Text(
                    id + ".name",
                    new Bounds(card.x() + 4, card.y() + 7, Math.max(1, card.width() - 8), 10),
                    UiMessage.literal(candidate.displayName(), UiMessage.Severity.INFO),
                    ViewSpec.Text.Alignment.CENTER,
                    Optional.of(new ViewSpec.MarqueeActivation(card, List.of(id)))));
            Optional<String> capeId = Optional.ofNullable(candidate.capeId());
            previews.add(new ViewSpec.Preview(
                    id + ".preview",
                    new Bounds(
                            card.x() + 5,
                            card.y() + 20,
                            Math.max(1, card.width() - 10),
                            Math.max(1, card.height() - 25)),
                    SkinReference.accountDefault(),
                    "external:" + candidate.sha256() + ":" + candidate.variant().name(),
                    candidate.variant(),
                    capeId,
                    capeId.isPresent()
                            ? PreviewRenderer.CapeMode.CAPE
                            : PreviewRenderer.CapeMode.OFF,
                    OuterLayerVisibility.allVisible(),
                    -20.0F,
                    0.0F,
                    1.0F,
                    Optional.empty(),
                    Optional.empty(),
                    Optional.of(new ViewSpec.ExternalImage(candidate.id())),
                    PreviewRenderer.PreviewIntent.ASSET_THUMBNAIL));
        }
    }

    private static int sectionHeight(int count, boolean collapsed, int columns, int cardHeight) {
        int height = HEADER_HEIGHT + 4;
        if (!collapsed) {
            height += ((count + columns - 1) / columns) * (cardHeight + CARD_GAP) + 8;
        }
        return height;
    }

    private static boolean intersects(Bounds bounds, int bottom) {
        return bounds.bottom() > CHROME_HEIGHT && bounds.y() < bottom;
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
            case PROBING -> Optional.of("nclskins.external_import.probing");
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
            case SKIN_SHUFFLE -> "skin_shuffle";
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
            case "skin_shuffle" -> ExternalImportSource.SKIN_SHUFFLE;
            case "prism_launcher" -> ExternalImportSource.PRISM_LAUNCHER;
            default -> throw new IllegalArgumentException("Unknown external import widget");
        };
    }
}

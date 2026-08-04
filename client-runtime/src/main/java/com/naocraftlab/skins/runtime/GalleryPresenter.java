package com.naocraftlab.skins.runtime;

import com.naocraftlab.skins.client.PreviewRenderer;
import com.naocraftlab.skins.core.model.AccountState;
import com.naocraftlab.skins.core.model.AppearancePreset;
import com.naocraftlab.skins.core.model.SkinAsset;
import com.naocraftlab.skins.core.model.SkinVariant;
import com.naocraftlab.skins.core.service.PresetGalleryOrder;
import com.naocraftlab.skins.core.service.RecoveryAction;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;


public final class GalleryPresenter {
    private static final int CARD_GAP = 12;
    private static final int CARD_MAX_WIDTH = 190;
    private static final int CARD_ACTION_GAP = 2;
    private static final int CARD_ACTION_SIDE_INSET = 2;
    private static final int CARD_ACTION_BOTTOM_INSET = 2;
    private static final int PREVIEW_ACTION_GAP = 4;
    private static final int VIEWPORT_SCROLLBAR_GAP = 4;
    private static final int ONE_ROW_ACTION_MIN_WIDTH = 122;
    private static final int ACTION_HEIGHT = 20;
    private static final int DECORATION_ICON_SIZE = 15;
    private static final int RECOVERY_BUTTON_WIDTH = 112;
    private static final int SCROLLBAR_HEIGHT = 6;
    private static final int FOOTER_HEIGHT = 33;
    private static final int SESSION_STATE_TEXT_WIDTH = 84;
    private static final int VIEWPORT_TOP = 58;
    private static final int SCROLLBAR_BOTTOM_OFFSET = 43;

    public ViewSpec present(
            ClientSnapshot snapshot,
            int width,
            int height,
            int mouseX,
            int mouseY,
            PreviewRenderer.CapeMode capeMode) {
        return present(
                snapshot,
                width,
                height,
                mouseX,
                mouseY,
                capeMode,
                SkinVariant.CLASSIC);
    }

    public ViewSpec present(
            ClientSnapshot snapshot,
            int width,
            int height,
            int mouseX,
            int mouseY,
            PreviewRenderer.CapeMode capeMode,
            SkinVariant currentPlayerVariant) {
        return present(snapshot, width, height, mouseX, mouseY, capeMode,
                currentPlayerVariant, "", Optional.empty());
    }

    public ViewSpec present(
            ClientSnapshot snapshot,
            int width,
            int height,
            int mouseX,
            int mouseY,
            PreviewRenderer.CapeMode capeMode,
            SkinVariant currentPlayerVariant,
            String query,
            Optional<UUID> pendingDeleteId) {
        return present(snapshot, width, height, mouseX, mouseY, capeMode,
                currentPlayerVariant, query, pendingDeleteId, snapshot.galleryOffset());
    }

    public ViewSpec present(
            ClientSnapshot snapshot,
            int width,
            int height,
            int mouseX,
            int mouseY,
            PreviewRenderer.CapeMode capeMode,
            SkinVariant currentPlayerVariant,
            String query,
            Optional<UUID> pendingDeleteId,
            double scrollPosition) {
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(capeMode, "capeMode");
        Objects.requireNonNull(currentPlayerVariant, "currentPlayerVariant");
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("view dimensions must be positive");
        }

        query = Objects.requireNonNull(query, "query");
        pendingDeleteId = Objects.requireNonNull(pendingDeleteId, "pendingDeleteId");
        GalleryLayout layout = layout(width, height);
        List<GalleryCard> cards = cards(snapshot, query);
        int visibleCount = layout.visibleCount();
        int maximum = Math.max(0, cards.size() - visibleCount);
        double visualOffset = Math.max(0.0, Math.min(scrollPosition, maximum));
        int offset = Math.min((int) Math.floor(visualOffset), maximum);
        double fraction = visualOffset - offset;
        int cardWidth = layout.cardWidth();
        int anchorTo = Math.min(cards.size(), offset + visibleCount);
        List<GalleryCard> anchorCards = cards.subList(Math.min(offset, cards.size()), anchorTo);
        int cardStep = cardWidth + CARD_GAP;
        Bounds cardViewport = layout.viewport();
        int anchorStartX = startX(
                snapshot.activePresetId(), width, cardWidth, offset, anchorCards);
        int visualAnchorStartX = anchorStartX - (int) Math.round(fraction * cardStep);
        int previousX = visualAnchorStartX - cardStep;
        int nextX = visualAnchorStartX + anchorCards.size() * cardStep;
        boolean previousCrossesViewport = previousX < cardViewport.x()
                && previousX + cardWidth > cardViewport.x();
        boolean nextCrossesViewport = nextX < cardViewport.right()
                && nextX + cardWidth > cardViewport.right();
        boolean showRestingNeighbors = visibleCount < 3;
        int from = showRestingNeighbors || previousCrossesViewport
                ? Math.max(0, offset - 1)
                : offset;
        int to = Math.min(
                cards.size(),
                anchorTo + (showRestingNeighbors
                        || fraction > 0.001
                        || nextCrossesViewport ? 1 : 0));
        List<GalleryCard> visibleCards = cards.subList(from, to);
        int startX = anchorStartX
                - (offset - from) * cardStep
                - (int) Math.round(fraction * cardStep);

        List<ViewSpec.Panel> panels = new ArrayList<>();
        panels.add(new ViewSpec.Panel(
                "header", new Bounds(0, 0, width, 33), ViewSpec.Panel.Style.VANILLA_HEADER));
        panels.add(new ViewSpec.Panel(
                "footer",
                new Bounds(0, Math.max(0, height - FOOTER_HEIGHT), width, FOOTER_HEIGHT),
                ViewSpec.Panel.Style.VANILLA_FOOTER));
        List<ViewSpec.Text> texts = new ArrayList<>();
        List<ViewSpec.Widget> widgets = new ArrayList<>();
        List<ViewSpec.Preview> previews = new ArrayList<>();
        List<ViewSpec.IconDecoration> iconDecorations = new ArrayList<>();
        widgets.add(ViewSpec.Widget.textField(
                "gallery.search",
                new Bounds(Math.max(8, width / 2 - 90), 36, Math.min(180, width - 16), 20),
                UiMessage.info("nclskins.gallery.search"),
                query,
                UiMessage.info("nclskins.gallery.search_hint"),
                !snapshot.busy(),
                128));

        int x = startX;
        for (GalleryCard card : visibleCards) {
            Bounds panelBounds = new Bounds(
                    x,
                    layout.cardTop(),
                    cardWidth,
                    layout.cardHeight());
            if (!intersects(panelBounds, cardViewport)) {
                x += cardWidth + CARD_GAP;
                continue;
            }
            panels.add(new ViewSpec.Panel(card.id(), panelBounds, ViewSpec.Panel.Style.VANILLA_LIST));
            if (card.preset().isEmpty()) {
                int iconX = x + (cardWidth - DECORATION_ICON_SIZE) / 2;
                int iconY = layout.cardTop()
                        + Math.max(
                        24,
                        (layout.cardHeight() - DECORATION_ICON_SIZE) / 2 - 10);
                iconDecorations.add(new ViewSpec.IconDecoration(
                        "gallery.add.icon",
                        new Bounds(iconX, iconY, DECORATION_ICON_SIZE, DECORATION_ICON_SIZE),
                        "plus",
                        "gallery.add",
                        0.65F,
                        1.0F));
                boolean nameSeed = matchingPresetCount(snapshot, query) == 0 && !query.isBlank();
                texts.add(new ViewSpec.Text(
                        "gallery.add.hint",
                        new Bounds(
                                x,
                                layout.cardTop() + Math.max(44, layout.cardHeight() / 2 + 10),
                                cardWidth,
                                10),
                        nameSeed
                                ? UiMessage.info(
                                        "nclskins.gallery.create_named",
                                        UntrustedDisplayName.sanitize(query, ""))
                                : UiMessage.info("nclskins.gallery.add_hint"),
                        ViewSpec.Text.Alignment.CENTER));
                widgets.add(new ViewSpec.Widget(
                        "gallery.add",
                        ViewSpec.WidgetKind.BUTTON,
                        panelBounds,
                        UiMessage.info("nclskins.gallery.add_hint"),
                        Optional.empty(),
                        Optional.empty(),
                        !snapshot.busy() && snapshot.account().isPresent(),
                        false,
                        0));
            } else {
                addPresetCard(
                        snapshot,
                        card.preset().orElseThrow(),
                        x,
                        layout.cardTop(),
                        layout.cardTop() + layout.cardHeight(),
                        cardWidth,
                        mouseX,
                        mouseY,
                        capeMode,
                        currentPlayerVariant,
                        pendingDeleteId.filter(card.preset().orElseThrow().id()::equals).isPresent(),
                        cardViewport,
                        texts,
                        widgets,
                        previews);
            }
            x += cardWidth + CARD_GAP;
        }

        Optional<RecoveryWidget> recovery = addGlobalWidgets(snapshot, width, height, widgets);
        addHeader(snapshot, width, recovery, texts);

        Optional<ViewSpec.Scrollbar> scrollbar = maximum <= 0
                ? Optional.empty()
                : Optional.of(scrollbar(
                width,
                layout.scrollbarY(),
                cards.size(),
                visibleCount,
                visualOffset,
                maximum));
        return new ViewSpec(
                "gallery",
                UiMessage.info("nclskins.gallery.title"),
                width,
                height,
                panels,
                texts,
                widgets,
                previews,
                scrollbar,
                List.of(),
                Optional.empty(),
                List.of(new ViewSpec.ClipRegion(
                        "gallery.cards",
                        cardViewport,
                        List.of("gallery.card.", "gallery.add", "gallery.preset."))),
                List.of(),
                iconDecorations,
                maximum <= 0
                        ? List.of()
                        : List.of(new ViewSpec.ScrollSurface(
                        "gallery.cards",
                        cardViewport,
                        ViewSpec.Scrollbar.Orientation.HORIZONTAL,
                        visualOffset * cardStep,
                        maximum * (double) cardStep)));
    }

    public int maximumScroll(ClientSnapshot snapshot, int width, int height, String query) {
        Objects.requireNonNull(snapshot, "snapshot");
        int cardCount = cards(snapshot, Objects.requireNonNull(query, "query")).size();
        return Math.max(0, cardCount - layout(width, height).visibleCount());
    }

    public double scrollPositionDelta(int width, int height, double pixelDelta) {
        if (!Double.isFinite(pixelDelta)) {
            throw new IllegalArgumentException("gallery scroll delta must be finite");
        }
        return pixelDelta / (layout(width, height).cardWidth() + CARD_GAP);
    }

    public int offsetFromScrollbar(
            ClientSnapshot snapshot, int width, int height, double desiredThumbLeft) {
        return offsetFromScrollbar(snapshot, width, height, "", desiredThumbLeft);
    }

    public int offsetFromScrollbar(
            ClientSnapshot snapshot,
            int width,
            int height,
            String query,
            double desiredThumbLeft) {
        return (int) Math.round(positionFromScrollbar(
                snapshot, width, height, query, desiredThumbLeft));
    }

    public double positionFromScrollbar(
            ClientSnapshot snapshot,
            int width,
            int height,
            String query,
            double desiredThumbLeft) {
        Objects.requireNonNull(snapshot, "snapshot");
        int cardCount = cards(snapshot, query).size();
        int visibleCount = layout(width, height).visibleCount();
        int maximum = Math.max(0, cardCount - visibleCount);
        if (maximum == 0) {
            return 0.0;
        }
        int trackLeft = 40;
        int trackRight = Math.max(trackLeft + 1, width - 40);
        int thumbWidth = thumbWidth(trackRight - trackLeft, cardCount, visibleCount);
        int travel = Math.max(1, trackRight - trackLeft - thumbWidth);
        double normalized = Math.max(0.0, Math.min(1.0, (desiredThumbLeft - trackLeft) / travel));
        return normalized * maximum;
    }

    private static void addPresetCard(
            ClientSnapshot snapshot,
            AppearancePreset preset,
            int x,
            int top,
            int bottom,
            int cardWidth,
            int mouseX,
            int mouseY,
            PreviewRenderer.CapeMode capeMode,
            SkinVariant currentPlayerVariant,
            boolean confirmingDelete,
            Bounds actionViewport,
            List<ViewSpec.Text> texts,
            List<ViewSpec.Widget> widgets,
            List<ViewSpec.Preview> previews) {
        String prefix = "gallery.preset." + preset.id();
        boolean active = snapshot.activePresetId().filter(preset.id()::equals).isPresent();
        texts.add(new ViewSpec.Text(
                prefix + ".name",
                new Bounds(x + 8, top + 8, cardWidth - 16, 10),
                UiMessage.literal(preset.name(), UiMessage.Severity.INFO),
                ViewSpec.Text.Alignment.CENTER,
                Optional.of(new ViewSpec.MarqueeActivation(
                        new Bounds(x, top, cardWidth, Math.max(1, bottom - top)),
                        List.of(
                                prefix + ".apply",
                                prefix + ".edit",
                                prefix + ".duplicate",
                                prefix + ".delete",
                                prefix + ".delete_confirm",
                                prefix + ".delete_cancel")))));
        int innerWidth = cardWidth;
        int applyRow = bottom - CARD_ACTION_BOTTOM_INSET - ACTION_HEIGHT;
        int secondaryRow = !confirmingDelete && cardWidth < ONE_ROW_ACTION_MIN_WIDTH
                ? applyRow - CARD_ACTION_GAP - ACTION_HEIGHT
                : applyRow;
        int previewTop = top + 20;
        int previewBottom = (confirmingDelete ? applyRow : secondaryRow) - PREVIEW_ACTION_GAP;
        float centerX = x + cardWidth / 2.0F;
        float centerY = previewTop + (previewBottom - previewTop) * 0.38F;
        float yaw = Math.max(-28.0F, Math.min(28.0F, (mouseX - centerX) * 0.22F));
        float pitch = Math.max(-16.0F, Math.min(16.0F, (centerY - mouseY) * 0.16F));
        SkinVariant variant = variantFor(snapshot.account().orElseThrow(), preset, currentPlayerVariant);
        Optional<String> capeId = preset.optionalCapeId();
        previews.add(new ViewSpec.Preview(
                prefix + ".preview",
                new Bounds(x + 8, previewTop, Math.max(1, cardWidth - 16), Math.max(1, previewBottom - previewTop)),
                preset.skin(),
                preset.skin().optionalAssetId()
                        .map(id -> "asset:" + id)
                        .orElse("current-player"),
                variant,
                capeId,
                capeId.isPresent() ? capeMode : PreviewRenderer.CapeMode.OFF,
                preset.outerLayerVisibility(),
                yaw,
                pitch,
                0.88F,
                Optional.of(preset.id())));

        if (confirmingDelete) {
            int leftWidth = Math.max(1, (innerWidth - CARD_ACTION_GAP) / 2);
            addIntersectingAction(widgets, ViewSpec.Widget.button(
                    prefix + ".delete_confirm",
                    new Bounds(x, applyRow, leftWidth, ACTION_HEIGHT),
                    UiMessage.info("nclskins.gallery.delete"),
                    !snapshot.busy()), actionViewport);
            addIntersectingAction(widgets, ViewSpec.Widget.button(
                    prefix + ".delete_cancel",
                    new Bounds(
                            x + leftWidth + CARD_ACTION_GAP,
                            applyRow,
                            innerWidth - leftWidth - CARD_ACTION_GAP,
                            ACTION_HEIGHT),
                    UiMessage.info("gui.cancel"),
                    !snapshot.busy()), actionViewport);
            return;
        }
        int actionX = x + CARD_ACTION_SIDE_INSET;
        int actionWidth = innerWidth - CARD_ACTION_SIDE_INSET * 2;
        if (cardWidth < ONE_ROW_ACTION_MIN_WIDTH) {
            addTwoRowActions(
                    snapshot,
                    prefix,
                    active,
                    actionX,
                    actionWidth,
                    secondaryRow,
                    applyRow,
                    actionViewport,
                    widgets);
            return;
        }
        int applyWidth = actionWidth - ACTION_HEIGHT * 3 - CARD_ACTION_GAP * 3;
        addIntersectingAction(widgets, ViewSpec.Widget.button(
                prefix + ".apply",
                new Bounds(actionX, applyRow, applyWidth, ACTION_HEIGHT),
                active
                        ? UiMessage.info("nclskins.gallery.active")
                        : UiMessage.info("nclskins.gallery.apply"),
                !snapshot.busy() && !active), actionViewport);
        int editX = actionX + applyWidth + CARD_ACTION_GAP;
        addIntersectingAction(widgets, compactIconAction(
                prefix + ".edit",
                new Bounds(editX, applyRow, ACTION_HEIGHT, ACTION_HEIGHT),
                "edit",
                UiMessage.info("nclskins.gallery.edit"),
                !snapshot.busy()), actionViewport);
        addIntersectingAction(widgets, compactIconAction(
                prefix + ".duplicate",
                new Bounds(
                        editX + ACTION_HEIGHT + CARD_ACTION_GAP,
                        applyRow,
                        ACTION_HEIGHT,
                        ACTION_HEIGHT),
                "duplicate",
                UiMessage.info("nclskins.gallery.duplicate"),
                !snapshot.busy()), actionViewport);
        addIntersectingAction(widgets, compactIconAction(
                prefix + ".delete",
                new Bounds(
                        editX + (ACTION_HEIGHT + CARD_ACTION_GAP) * 2,
                        applyRow,
                        ACTION_HEIGHT,
                        ACTION_HEIGHT),
                "delete",
                UiMessage.info("nclskins.gallery.delete"),
                !snapshot.busy()), actionViewport);
    }

    private static void addTwoRowActions(
            ClientSnapshot snapshot,
            String prefix,
            boolean active,
            int x,
            int innerWidth,
            int secondaryRow,
            int applyRow,
            Bounds actionViewport,
            List<ViewSpec.Widget> widgets) {
        int availableSecondaryWidth = innerWidth - CARD_ACTION_GAP * 2;
        int outerWidth = availableSecondaryWidth / 3;
        int middleWidth = availableSecondaryWidth - outerWidth * 2;
        int editX = x;
        int duplicateX = editX + outerWidth + CARD_ACTION_GAP;
        int deleteX = duplicateX + middleWidth + CARD_ACTION_GAP;
        addIntersectingAction(widgets, ViewSpec.Widget.button(
                prefix + ".apply",
                new Bounds(x, applyRow, innerWidth, ACTION_HEIGHT),
                active
                        ? UiMessage.info("nclskins.gallery.active")
                        : UiMessage.info("nclskins.gallery.apply"),
                !snapshot.busy() && !active), actionViewport);
        addIntersectingAction(widgets, compactIconAction(
                prefix + ".edit",
                new Bounds(editX, secondaryRow, outerWidth, ACTION_HEIGHT),
                "edit",
                UiMessage.info("nclskins.gallery.edit"),
                !snapshot.busy()), actionViewport);
        addIntersectingAction(widgets, compactIconAction(
                prefix + ".duplicate",
                new Bounds(duplicateX, secondaryRow, middleWidth, ACTION_HEIGHT),
                "duplicate",
                UiMessage.info("nclskins.gallery.duplicate"),
                !snapshot.busy()), actionViewport);
        addIntersectingAction(widgets, compactIconAction(
                prefix + ".delete",
                new Bounds(deleteX, secondaryRow, outerWidth, ACTION_HEIGHT),
                "delete",
                UiMessage.info("nclskins.gallery.delete"),
                !snapshot.busy()), actionViewport);
    }

    private static void addIntersectingAction(
            List<ViewSpec.Widget> widgets, ViewSpec.Widget widget, Bounds viewport) {
        if (intersects(widget.bounds(), viewport)) {
            widgets.add(widget);
        }
    }

    private static ViewSpec.Widget compactIconAction(
            String id, Bounds bounds, String icon, UiMessage accessibleLabel, boolean enabled) {
        return ViewSpec.Widget.iconButton(id, bounds, accessibleLabel, icon, enabled);
    }

    private static Optional<RecoveryWidget> addGlobalWidgets(
            ClientSnapshot snapshot, int width, int height, List<ViewSpec.Widget> widgets) {
        int doneWidth = Math.min(200, Math.max(1, width - 32));
        widgets.add(ViewSpec.Widget.button(
                "gallery.done",
                new Bounds((width - doneWidth) / 2, height - 28, doneWidth, 20),
                UiMessage.info("gui.done"),
                !snapshot.busy()));
        boolean retryVisible = snapshot.session().isEmpty()
                || !snapshot.session().orElseThrow().valid()
                || snapshot.recoveryActions().contains(RecoveryAction.REFRESH_REMOTE_PROFILE);
        if (retryVisible) {
            Bounds bounds = recoveryBounds(width);
            widgets.add(ViewSpec.Widget.button(
                    "gallery.retry_session",
                    bounds,
                    UiMessage.info("nclskins.session.retry"),
                    !snapshot.busy()
                            && !snapshot.syncInProgress()
                            && snapshot.account().isPresent()
                            && !snapshot.rateLimited()));
            return Optional.of(new RecoveryWidget(bounds));
        }
        if (snapshot.recoveryActions().contains(RecoveryAction.RETRY_CAPE)) {
            Bounds bounds = recoveryBounds(width);
            widgets.add(ViewSpec.Widget.button(
                    "gallery.retry_cape",
                    bounds,
                    UiMessage.info("nclskins.recovery.retry_cape"),
                    !snapshot.busy() && !snapshot.remoteControlsBlocked()));
            return Optional.of(new RecoveryWidget(bounds));
        }
        return Optional.empty();
    }

    private static Bounds recoveryBounds(int width) {
        int buttonWidth = Math.min(RECOVERY_BUTTON_WIDTH, Math.max(1, width - 16));
        return new Bounds(Math.max(8, width - buttonWidth - 8), 6, buttonWidth, 20);
    }

    private static void addHeader(
            ClientSnapshot snapshot,
            int width,
            Optional<RecoveryWidget> recovery,
            List<ViewSpec.Text> texts) {
        boolean offline = snapshot.session().isEmpty() || !snapshot.session().orElseThrow().valid();
        boolean connecting = snapshot.busy()
                && snapshot.status().equals(UiMessage.info("nclskins.status.checking_session"));
        boolean showSessionState = offline || connecting;
        int leftOccupied = showSessionState
                ? Math.min(SESSION_STATE_TEXT_WIDTH + 12, Math.max(0, width - 1))
                : 0;
        int rightStart = recovery.map(value -> Math.max(0, value.bounds().x() - 4)).orElse(width);
        int symmetricInset = Math.max(leftOccupied, width - rightStart);
        int titleLeft;
        int titleRight;
        if (width - symmetricInset * 2 >= 1) {
            titleLeft = symmetricInset;
            titleRight = width - symmetricInset;
        } else {
            titleLeft = Math.min(leftOccupied, Math.max(0, width - 1));
            titleRight = Math.max(titleLeft + 1, rightStart);
        }
        texts.add(new ViewSpec.Text(
                "gallery.title",
                new Bounds(titleLeft, 12, Math.max(1, titleRight - titleLeft), 10),
                UiMessage.info("nclskins.gallery.title"),
                ViewSpec.Text.Alignment.CENTER));
        if (showSessionState) {
            texts.add(new ViewSpec.Text(
                    "gallery.offline",
                    new Bounds(
                            8,
                            12,
                            Math.min(SESSION_STATE_TEXT_WIDTH, Math.max(1, width - 16)),
                            10),
                    UiMessage.info(connecting
                            ? "nclskins.session.connecting"
                            : "nclskins.session.offline"),
                    ViewSpec.Text.Alignment.LEFT));
        }
    }

    private static ViewSpec.Scrollbar scrollbar(
            int width, int y, int cardCount, int visibleCount, double offset, int maximum) {
        int trackLeft = 40;
        int trackRight = Math.max(trackLeft + 1, width - 40);
        int trackWidth = trackRight - trackLeft;
        int thumbWidth = thumbWidth(trackWidth, cardCount, visibleCount);
        int travel = Math.max(0, trackWidth - thumbWidth);
        int thumbLeft = trackLeft + (int) Math.round(travel * offset / maximum);
        return new ViewSpec.Scrollbar(
                new Bounds(trackLeft, y, trackWidth, SCROLLBAR_HEIGHT),
                new Bounds(thumbLeft, y, thumbWidth, SCROLLBAR_HEIGHT),
                (int) Math.round(offset),
                maximum);
    }

    private static int thumbWidth(int trackWidth, int cardCount, int visibleCount) {
        int proportional = Math.round(trackWidth * Math.min(cardCount, visibleCount) / (float) Math.max(1, cardCount));
        return Math.min(trackWidth, Math.max(32, proportional));
    }

    public int matchingPresetCount(ClientSnapshot snapshot, String query) {
        Objects.requireNonNull(snapshot, "snapshot");
        String normalized = Objects.requireNonNull(query, "query").trim().toLowerCase(java.util.Locale.ROOT);
        if (normalized.isEmpty()) {
            return snapshot.account().map(account -> account.presets().size()).orElse(0);
        }
        return (int) snapshot.account().stream()
                .flatMap(account -> account.presets().stream())
                .filter(preset -> preset.name().toLowerCase(java.util.Locale.ROOT).contains(normalized))
                .count();
    }

    private static List<GalleryCard> cards(ClientSnapshot snapshot, String query) {
        List<GalleryCard> cards = new ArrayList<>();
        cards.add(new GalleryCard(Optional.empty()));
        String normalized = query.trim().toLowerCase(java.util.Locale.ROOT);
        snapshot.account().ifPresent(account -> PresetGalleryOrder.arrange(
                        account.presets().stream()
                                .filter(preset -> normalized.isEmpty()
                                        || preset.name().toLowerCase(java.util.Locale.ROOT).contains(normalized))
                                .toList(),
                        snapshot.activePresetId().orElse(null))
                .forEach(preset -> cards.add(new GalleryCard(Optional.of(preset)))));
        return List.copyOf(cards);
    }

    private static GalleryLayout layout(int width, int height) {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("view dimensions must be positive");
        }
        int scrollbarY = height - SCROLLBAR_BOTTOM_OFFSET;
        int viewportBottom = scrollbarY - VIEWPORT_SCROLLBAR_GAP;
        int viewportHeight = viewportBottom - VIEWPORT_TOP;
        if (viewportHeight < 2) {
            throw new IllegalArgumentException("gallery viewport is too short");
        }
        int cardWidth = Math.min(
                CARD_MAX_WIDTH,
                (int) ((long) viewportHeight * 3L / 4L));
        int cardHeight = (cardWidth * 4 + 2) / 3;
        int cardTop = VIEWPORT_TOP + (viewportHeight - cardHeight) / 2;
        int visibleCount = (int) Math.max(
                1L,
                Math.min(3L, ((long) width + CARD_GAP) / (cardWidth + CARD_GAP)));
        return new GalleryLayout(
                new Bounds(0, VIEWPORT_TOP, width, viewportHeight),
                cardWidth,
                cardHeight,
                cardTop,
                visibleCount,
                scrollbarY);
    }

    private static int startX(
            Optional<UUID> activePresetId,
            int width,
            int cardWidth,
            int offset,
            List<GalleryCard> visibleCards) {
        int count = visibleCards.size();
        int rowWidth = cardWidth * count + CARD_GAP * Math.max(0, count - 1);
        int centered = (width - rowWidth) / 2;
        if (offset == 0
                && activePresetId.isPresent()
                && count >= 2
                && visibleCards.get(0).preset().isEmpty()
                && visibleCards.get(1).preset().map(preset -> preset.id().equals(activePresetId.orElseThrow())).orElse(false)) {
            int activeCentered = width / 2 - cardWidth / 2 - cardWidth - CARD_GAP;
            if (activeCentered >= 36 && activeCentered + rowWidth <= width - 36) {
                return activeCentered;
            }
        }
        return centered;
    }

    private static SkinVariant variantFor(
            AccountState account, AppearancePreset preset, SkinVariant currentPlayerVariant) {
        return preset.skin().optionalAssetId()
                .flatMap(id -> account.skinAssets().stream().filter(asset -> asset.id().equals(id)).findFirst())
                .map(SkinAsset::variant)
                .orElse(currentPlayerVariant);
    }

    private static boolean intersects(Bounds candidate, Bounds viewport) {
        return candidate.right() > viewport.x()
                && candidate.x() < viewport.right()
                && candidate.bottom() > viewport.y()
                && candidate.y() < viewport.bottom();
    }

    private record GalleryCard(Optional<AppearancePreset> preset) {
        private GalleryCard {
            preset = Objects.requireNonNull(preset, "preset");
        }

        private String id() {
            return preset.map(value -> "gallery.card." + value.id()).orElse("gallery.card.add");
        }
    }

    private record GalleryLayout(
            Bounds viewport,
            int cardWidth,
            int cardHeight,
            int cardTop,
            int visibleCount,
            int scrollbarY) {
        private GalleryLayout {
            Objects.requireNonNull(viewport, "viewport");
            if (cardWidth <= 0
                    || cardHeight <= 0
                    || cardTop < viewport.y()
                    || cardTop + cardHeight > viewport.bottom()
                    || visibleCount < 1
                    || visibleCount > 3) {
                throw new IllegalArgumentException("invalid gallery layout");
            }
        }
    }

    private record RecoveryWidget(Bounds bounds) {
        private RecoveryWidget {
            Objects.requireNonNull(bounds, "bounds");
        }
    }
}

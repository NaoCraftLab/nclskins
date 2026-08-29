package com.naocraftlab.skins.runtime;

import com.naocraftlab.skins.client.PreviewRenderer;
import com.naocraftlab.skins.core.compatibility.SkinCompatibility;
import com.naocraftlab.skins.core.compatibility.SkinCompatibilityStatus;
import com.naocraftlab.skins.core.model.AccountState;
import com.naocraftlab.skins.core.model.AppearancePreset;
import com.naocraftlab.skins.core.model.AppearanceSyncStatus;
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
    private static final int CARD_ACTION_GAP = 2;
    private static final int CARD_ACTION_SIDE_INSET = 2;
    private static final int CARD_ACTION_BOTTOM_INSET = 2;
    private static final int PREVIEW_ACTION_GAP = 4;
    private static final int VIEWPORT_SCROLLBAR_GAP = 4;
    private static final int ONE_ROW_ACTION_MIN_WIDTH = 122;
    private static final int ACTION_HEIGHT = 20;
    private static final int DECORATION_ICON_SIZE = 32;
    private static final int ADD_ICON_LABEL_GAP = 8;
    private static final int RECOVERY_BUTTON_WIDTH = 112;
    private static final int SCROLLBAR_HEIGHT = 6;
    private static final int FOOTER_HEIGHT = 33;
    private static final int SESSION_STATE_TEXT_WIDTH = 84;
    private static final int VIEWPORT_TOP = 58;
    private static final int SCROLLBAR_BOTTOM_OFFSET = 43;
    private static final int RATE_LIMIT_PROGRESS_COLOR = 0xFF5A8FCB;
    private static final int RATE_LIMIT_PROGRESS_HEIGHT = 2;

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
        return present(
                snapshot,
                width,
                height,
                mouseX,
                mouseY,
                capeMode,
                currentPlayerVariant,
                query,
                pendingDeleteId,
                scrollPosition,
                normalizeSelectedCardId(snapshot, query, null));
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
            double scrollPosition,
            String selectedCardId) {
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(capeMode, "capeMode");
        Objects.requireNonNull(currentPlayerVariant, "currentPlayerVariant");
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("view dimensions must be positive");
        }

        query = Objects.requireNonNull(query, "query");
        pendingDeleteId = Objects.requireNonNull(pendingDeleteId, "pendingDeleteId");
        selectedCardId = normalizeSelectedCardId(snapshot, query, selectedCardId);
        if (snapshot.lifecycle() == ClientSnapshot.Lifecycle.INITIALIZING
                && snapshot.account().isEmpty()) {
            return coldLoadingView(width, height);
        }
        GalleryLayout layout = layout(width, height);
        List<GalleryCard> cards = cards(snapshot, query);
        int cardWidth = layout.cardWidth();
        int cardStep = cardWidth + CARD_GAP;
        int contentWidth = contentWidth(cards.size(), cardWidth);
        int maximum = maximumPixels(contentWidth, layout.endpointWidth());
        double visualOffset = Math.max(0.0, Math.min(scrollPosition, maximum));
        Bounds cardViewport = layout.viewport();
        int unscrolledStart = maximum == 0
                ? cardViewport.x() + (cardViewport.width() - contentWidth) / 2
                : layout.endpointInset();
        int stripStart = unscrolledStart - (int) Math.round(visualOffset);

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
                128,
                true,
                Optional.empty()));

        for (int index = 0; index < cards.size(); index++) {
            GalleryCard card = cards.get(index);
            int x = stripStart + index * cardStep;
            Bounds panelBounds = new Bounds(
                    x,
                    layout.cardTop(),
                    cardWidth,
                    layout.cardHeight());
            if (!intersects(panelBounds, cardViewport)) {
                continue;
            }
            panels.add(new ViewSpec.Panel(card.id(), panelBounds, ViewSpec.Panel.Style.VANILLA_LIST));
            String anchorId = card.anchorId();
            widgets.add(new ViewSpec.Widget(
                    anchorId,
                    ViewSpec.WidgetKind.CATALOG_CARD,
                    panelBounds,
                    card.accessibleLabel(),
                    Optional.empty(),
                    Optional.empty(),
                    !snapshot.busy()
                            && snapshot.account().isPresent()
                            && pendingDeleteId.isEmpty(),
                    true,
                    0));
            if (card.preset().isEmpty()) {
                int iconX = x + (cardWidth - DECORATION_ICON_SIZE) / 2;
                int hintY = layout.cardTop() + Math.max(44, layout.cardHeight() / 2 + 10);
                int iconY = hintY - ADD_ICON_LABEL_GAP - DECORATION_ICON_SIZE;
                iconDecorations.add(new ViewSpec.IconDecoration(
                        "gallery.add.icon",
                        new Bounds(iconX, iconY, DECORATION_ICON_SIZE, DECORATION_ICON_SIZE),
                        GuiIcon.ACTION_ADD_LOOK,
                        "gallery.add",
                        0.65F,
                        1.0F));
                boolean nameSeed = matchingPresetCount(snapshot, query) == 0 && !query.isBlank();
                texts.add(new ViewSpec.Text(
                        "gallery.add.hint",
                        new Bounds(
                                x,
                                hintY,
                                cardWidth,
                                10),
                        nameSeed
                                ? UiMessage.info(
                                        "nclskins.gallery.create_named",
                                        UntrustedDisplayName.sanitize(query, ""))
                                : UiMessage.info("nclskins.gallery.add_hint"),
                        ViewSpec.Text.Alignment.CENTER));
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
                        pendingDeleteId.isPresent(),
                        cardViewport,
                        texts,
                        widgets,
                        previews,
                        iconDecorations);
            }
        }

        Optional<RecoveryWidget> recovery = addGlobalWidgets(snapshot, width, height, widgets);
        addHeader(snapshot, width, recovery, texts);
        List<ViewSpec.NavigationNode> navigationNodes = galleryNavigationNodes(
                cards,
                selectedCardId,
                stripStart,
                cardStep,
                layout,
                widgets,
                recovery,
                !snapshot.busy() && snapshot.account().isPresent());
        List<ViewSpec.ProgressDecoration> progressDecorations =
                rateLimitProgressDecorations(snapshot, widgets);

        Optional<ViewSpec.Scrollbar> scrollbar = Optional.of(scrollbar(
                width,
                layout.scrollbarY(),
                layout.endpointWidth(),
                contentWidth,
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
                List.of(new ViewSpec.ScrollSurface(
                        "gallery.cards",
                        cardViewport,
                        ViewSpec.Scrollbar.Orientation.HORIZONTAL,
                        visualOffset,
                        maximum)),
                List.of(),
                progressDecorations).withNavigationNodes(navigationNodes);
    }

    public String normalizeSelectedCardId(
            ClientSnapshot snapshot, String query, String selectedCardId) {
        Objects.requireNonNull(snapshot, "snapshot");
        List<GalleryCard> available = cards(snapshot, Objects.requireNonNull(query, "query"));
        if (selectedCardId != null
                && available.stream().anyMatch(card -> card.anchorId().equals(selectedCardId))) {
            return selectedCardId;
        }
        if (selectedCardId == null) {
            Optional<String> active = snapshot.activePresetId()
                    .map(id -> "gallery.card." + id)
                    .filter(id -> available.stream().anyMatch(card -> card.anchorId().equals(id)));
            if (active.isPresent()) {
                return active.orElseThrow();
            }
            return "gallery.add";
        }
        return available.stream()
                .filter(card -> card.preset().isPresent())
                .map(GalleryCard::anchorId)
                .findFirst()
                .orElse("gallery.add");
    }

    public List<String> cardIds(ClientSnapshot snapshot, String query) {
        return cards(snapshot, Objects.requireNonNull(query, "query")).stream()
                .map(GalleryCard::anchorId)
                .toList();
    }

    public List<String> cardIds(
            Optional<AccountState> account, Optional<UUID> activePresetId, String query) {
        return cards(
                        Objects.requireNonNull(account, "account"),
                        Objects.requireNonNull(activePresetId, "activePresetId"),
                        Objects.requireNonNull(query, "query"))
                .stream()
                .map(GalleryCard::anchorId)
                .toList();
    }

    private static List<ViewSpec.NavigationNode> galleryNavigationNodes(
            List<GalleryCard> cards,
            String selectedCardId,
            int stripStart,
            int cardStep,
            GalleryLayout layout,
            List<ViewSpec.Widget> widgets,
            Optional<RecoveryWidget> recovery,
            boolean cardsEnabled) {
        List<ViewSpec.NavigationNode> nodes = new ArrayList<>();
        int tabOrder = 0;
        if (recovery.isPresent()) {
            ViewSpec.Widget widget = widgets.stream()
                    .filter(candidate -> candidate.id().equals(recovery.orElseThrow().widgetId()))
                    .findFirst()
                    .orElseThrow();
            nodes.add(ViewSpec.NavigationNode.control(widget, 0, tabOrder++));
        }
        ViewSpec.Widget search = widgets.stream()
                .filter(widget -> widget.id().equals("gallery.search"))
                .findFirst()
                .orElseThrow();
        nodes.add(ViewSpec.NavigationNode.control(search, 1, tabOrder++));

        ViewSpec.NavigationNode selected = null;
        for (int index = 0; index < cards.size(); index++) {
            GalleryCard card = cards.get(index);
            Bounds bounds = new Bounds(
                    stripStart + index * cardStep,
                    layout.cardTop(),
                    layout.cardWidth(),
                    layout.cardHeight());
            ViewSpec.NavigationNode node = ViewSpec.NavigationNode.card(
                    card.anchorId(),
                    bounds,
                    "gallery.cards",
                    index,
                    card.anchorId().equals(selectedCardId) ? tabOrder : -1,
                    cardsEnabled,
                    ViewSpec.NavigationPattern.HORIZONTAL_LIST,
                    card.preset().isEmpty() ? Optional.of("gallery.add") : Optional.empty());
            nodes.add(node);
            if (card.anchorId().equals(selectedCardId)) {
                selected = node;
            }
        }
        if (selected != null) {
            tabOrder++;
        }
        String selectedActionPrefix = selectedCardId.startsWith("gallery.card.")
                ? "gallery.preset." + selectedCardId.substring("gallery.card.".length()) + "."
                : "";
        int documentOrder = cards.size() + 2;
        if (!selectedActionPrefix.isEmpty()) {
            for (ViewSpec.Widget widget : widgets) {
                if (widget.id().startsWith(selectedActionPrefix)) {
                    nodes.add(ViewSpec.NavigationNode.control(widget, documentOrder++, tabOrder++));
                }
            }
        }
        ViewSpec.Widget done = widgets.stream()
                .filter(widget -> widget.id().equals("gallery.done"))
                .findFirst()
                .orElseThrow();
        nodes.add(ViewSpec.NavigationNode.control(done, documentOrder, tabOrder));
        return List.copyOf(nodes);
    }

    public int maximumScroll(ClientSnapshot snapshot, int width, int height, String query) {
        Objects.requireNonNull(snapshot, "snapshot");
        int cardCount = cards(snapshot, Objects.requireNonNull(query, "query")).size();
        GalleryLayout layout = layout(width, height);
        return maximumPixels(
                contentWidth(cardCount, layout.cardWidth()), layout.endpointWidth());
    }

    double initialScrollPosition(
            Optional<AccountState> account,
            Optional<UUID> activePresetId,
            String query,
            int width,
            int height) {
        List<GalleryCard> cards = cards(
                Objects.requireNonNull(account, "account"),
                Objects.requireNonNull(activePresetId, "activePresetId"),
                Objects.requireNonNull(query, "query"));
        GalleryLayout layout = layout(width, height);
        int cardStep = layout.cardWidth() + CARD_GAP;
        int maximum = maximumPixels(
                contentWidth(cards.size(), layout.cardWidth()), layout.endpointWidth());
        for (int index = 0; index < cards.size(); index++) {
            if (cards.get(index).preset()
                    .map(AppearancePreset::id)
                    .filter(id -> activePresetId.filter(id::equals).isPresent())
                    .isPresent()) {
                int trailing = index * cardStep + layout.cardWidth();
                return Math.max(0, Math.min(maximum, trailing - layout.viewport().width()));
            }
        }
        return 0.0;
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
        GalleryLayout layout = layout(width, height);
        int contentWidth = contentWidth(cardCount, layout.cardWidth());
        int maximum = maximumPixels(contentWidth, layout.endpointWidth());
        if (maximum == 0) {
            return 0.0;
        }
        int trackLeft = 40;
        int trackRight = Math.max(trackLeft + 1, width - 40);
        int thumbWidth = thumbWidth(
                trackRight - trackLeft, layout.endpointWidth(), contentWidth);
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
            boolean interactionLocked,
            Bounds actionViewport,
            List<ViewSpec.Text> texts,
            List<ViewSpec.Widget> widgets,
            List<ViewSpec.Preview> previews,
            List<ViewSpec.IconDecoration> iconDecorations) {
        String prefix = "gallery.preset." + preset.id();
        boolean active = snapshot.activePresetId().filter(preset.id()::equals).isPresent();
        SkinCompatibility compatibility = snapshot.compatibilityFor(preset);
        boolean hasCompatibility = compatibility.status() != SkinCompatibilityStatus.ORDINARY;
        texts.add(new ViewSpec.Text(
                prefix + ".name",
                new Bounds(x + 8, top + 8, Math.max(1, cardWidth - 16), 10),
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

        if (hasCompatibility) {
            String indicatorId = prefix + ".compatibility";
            int indicatorBottom = (confirmingDelete ? applyRow : secondaryRow) - 2;
            Bounds indicatorBounds = new Bounds(
                    x + 2,
                    indicatorBottom - 20,
                    20,
                    20);
            widgets.add(ViewSpec.Widget.compatibilityIndicator(
                    indicatorId,
                    indicatorBounds,
                    CompatibilityMessages.accessibleLabel(compatibility),
                    CompatibilityMessages.icon(compatibility)));
        }

        if (confirmingDelete) {
            int confirmationX = x + CARD_ACTION_SIDE_INSET;
            int confirmationWidth = innerWidth - CARD_ACTION_SIDE_INSET * 2;
            int leftWidth = Math.max(1, (confirmationWidth - CARD_ACTION_GAP) / 2);
            addIntersectingAction(widgets, ViewSpec.Widget.button(
                    prefix + ".delete_confirm",
                    new Bounds(confirmationX, applyRow, leftWidth, ACTION_HEIGHT),
                    UiMessage.info("nclskins.gallery.delete"),
                    !snapshot.busy()), actionViewport);
            addIntersectingAction(widgets, ViewSpec.Widget.button(
                    prefix + ".delete_cancel",
                    new Bounds(
                            confirmationX + leftWidth + CARD_ACTION_GAP,
                            applyRow,
                            confirmationWidth - leftWidth - CARD_ACTION_GAP,
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
                    interactionLocked,
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
                rateLimitHint(snapshot, active),
                !snapshot.busy()
                        && !interactionLocked
                        && (!active || rateLimitedPending(snapshot))), actionViewport);
        int editX = actionX + applyWidth + CARD_ACTION_GAP;
        addIntersectingAction(widgets, compactIconAction(
                prefix + ".edit",
                new Bounds(editX, applyRow, ACTION_HEIGHT, ACTION_HEIGHT),
                GuiIcon.ACTION_EDIT,
                UiMessage.info("nclskins.gallery.edit"),
                !snapshot.busy() && !interactionLocked), actionViewport);
        addIntersectingAction(widgets, compactIconAction(
                prefix + ".duplicate",
                new Bounds(
                        editX + ACTION_HEIGHT + CARD_ACTION_GAP,
                        applyRow,
                        ACTION_HEIGHT,
                        ACTION_HEIGHT),
                GuiIcon.ACTION_DUPLICATE,
                UiMessage.info("nclskins.gallery.duplicate"),
                !snapshot.busy() && !interactionLocked), actionViewport);
        addIntersectingAction(widgets, compactIconAction(
                prefix + ".delete",
                new Bounds(
                        editX + (ACTION_HEIGHT + CARD_ACTION_GAP) * 2,
                        applyRow,
                        ACTION_HEIGHT,
                        ACTION_HEIGHT),
                GuiIcon.ACTION_DELETE,
                UiMessage.info("nclskins.gallery.delete"),
                !snapshot.busy() && !interactionLocked), actionViewport);
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
            boolean interactionLocked,
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
                rateLimitHint(snapshot, active),
                !snapshot.busy()
                        && !interactionLocked
                        && (!active || rateLimitedPending(snapshot))), actionViewport);
        addIntersectingAction(widgets, compactIconAction(
                prefix + ".edit",
                new Bounds(editX, secondaryRow, outerWidth, ACTION_HEIGHT),
                GuiIcon.ACTION_EDIT,
                UiMessage.info("nclskins.gallery.edit"),
                !snapshot.busy() && !interactionLocked), actionViewport);
        addIntersectingAction(widgets, compactIconAction(
                prefix + ".duplicate",
                new Bounds(duplicateX, secondaryRow, middleWidth, ACTION_HEIGHT),
                GuiIcon.ACTION_DUPLICATE,
                UiMessage.info("nclskins.gallery.duplicate"),
                !snapshot.busy() && !interactionLocked), actionViewport);
        addIntersectingAction(widgets, compactIconAction(
                prefix + ".delete",
                new Bounds(deleteX, secondaryRow, outerWidth, ACTION_HEIGHT),
                GuiIcon.ACTION_DELETE,
                UiMessage.info("nclskins.gallery.delete"),
                !snapshot.busy() && !interactionLocked), actionViewport);
    }

    private static void addIntersectingAction(
            List<ViewSpec.Widget> widgets, ViewSpec.Widget widget, Bounds viewport) {
        if (intersects(widget.bounds(), viewport)) {
            widgets.add(widget);
        }
    }

    private static ViewSpec.Widget compactIconAction(
            String id, Bounds bounds, GuiIcon icon, UiMessage accessibleLabel, boolean enabled) {
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
        ClientSnapshot.GallerySessionPresentation sessionPresentation =
                snapshot.gallerySessionPresentation();
        boolean retryVisible = sessionPresentation
                == ClientSnapshot.GallerySessionPresentation.OFFLINE_RETRY
                || (sessionPresentation == ClientSnapshot.GallerySessionPresentation.CONNECTING
                        && snapshot.sessionActivity()
                                == ClientSnapshot.SessionActivity.RECONNECTING);
        if (retryVisible) {
            Bounds bounds = recoveryBounds(width);
            widgets.add(ViewSpec.Widget.button(
                    "gallery.retry_session",
                    bounds,
                    UiMessage.info("nclskins.session.retry"),
                    rateLimitHint(snapshot, true),
                    !snapshot.busy()
                            && snapshot.sessionActivity()
                                    != ClientSnapshot.SessionActivity.RECONNECTING
                            && !snapshot.syncInProgress()
                            && snapshot.account().isPresent()));
            return Optional.of(new RecoveryWidget("gallery.retry_session", bounds));
        }
        if (snapshot.recoveryActions().contains(RecoveryAction.RETRY_CAPE)) {
            Bounds bounds = recoveryBounds(width);
            widgets.add(ViewSpec.Widget.button(
                    "gallery.retry_cape",
                    bounds,
                    UiMessage.info("nclskins.recovery.retry_cape"),
                    rateLimitHint(snapshot, true),
                    !snapshot.busy()
                            && (!snapshot.remoteControlsBlocked() || snapshot.rateLimited())));
            return Optional.of(new RecoveryWidget("gallery.retry_cape", bounds));
        }
        return Optional.empty();
    }

    private static Optional<UiMessage> rateLimitHint(ClientSnapshot snapshot, boolean owner) {
        return owner && snapshot.rateLimitProgress().isPresent()
                ? Optional.of(UiMessage.info("nclskins.rate_limit.delayed"))
                : Optional.empty();
    }

    private static boolean rateLimitWaiting(ClientSnapshot snapshot) {
        return snapshot.rateLimited() || snapshot.rateLimitProgress().isPresent();
    }

    private static boolean rateLimitedPending(ClientSnapshot snapshot) {
        if (snapshot.rateLimitProgress().isEmpty()) {
            return false;
        }
        return snapshot.syncStatus() == AppearanceSyncStatus.PENDING
                || snapshot.syncStatus() == AppearanceSyncStatus.ATTEMPTING
                || snapshot.syncStatus() == AppearanceSyncStatus.PARTIAL;
    }

    private static List<ViewSpec.ProgressDecoration> rateLimitProgressDecorations(
            ClientSnapshot snapshot, List<ViewSpec.Widget> widgets) {
        if (snapshot.rateLimitProgress().isEmpty()) {
            return List.of();
        }
        double fraction = snapshot.rateLimitProgress().orElseThrow().fraction();
        String activeApply = rateLimitedPending(snapshot)
                ? snapshot.activePresetId()
                        .map(id -> "gallery.preset." + id + ".apply")
                        .orElse(null)
                : null;
        List<ViewSpec.ProgressDecoration> decorations = new ArrayList<>();
        for (ViewSpec.Widget widget : widgets) {
            boolean retry = widget.id().equals("gallery.retry_session")
                    || widget.id().equals("gallery.retry_cape");
            if (!retry && !widget.id().equals(activeApply)) {
                continue;
            }
            decorations.add(new ViewSpec.ProgressDecoration(
                    widget.id() + ".rate_limit",
                    widget.id(),
                    fraction,
                    RATE_LIMIT_PROGRESS_COLOR,
                    RATE_LIMIT_PROGRESS_HEIGHT));
        }
        return List.copyOf(decorations);
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
        ClientSnapshot.GallerySessionPresentation sessionPresentation =
                snapshot.gallerySessionPresentation();
        boolean connecting = sessionPresentation
                == ClientSnapshot.GallerySessionPresentation.CONNECTING;
        boolean showSessionState = sessionPresentation
                != ClientSnapshot.GallerySessionPresentation.HIDDEN;
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

    private static ViewSpec coldLoadingView(int width, int height) {
        int contentTop = 33;
        int contentBottom = Math.max(contentTop + 10, height - FOOTER_HEIGHT);
        int loadingY = contentTop + Math.max(0, (contentBottom - contentTop - 10) / 2);
        return new ViewSpec(
                "gallery",
                UiMessage.info("nclskins.gallery.title"),
                width,
                height,
                List.of(
                        new ViewSpec.Panel(
                                "header",
                                new Bounds(0, 0, width, 33),
                                ViewSpec.Panel.Style.VANILLA_HEADER),
                        new ViewSpec.Panel(
                                "footer",
                                new Bounds(
                                        0,
                                        Math.max(0, height - FOOTER_HEIGHT),
                                        width,
                                        FOOTER_HEIGHT),
                                ViewSpec.Panel.Style.VANILLA_FOOTER)),
                List.of(
                        new ViewSpec.Text(
                                "gallery.title",
                                new Bounds(0, 12, width, 10),
                                UiMessage.info("nclskins.gallery.title"),
                                ViewSpec.Text.Alignment.CENTER),
                        new ViewSpec.Text(
                                "gallery.loading",
                                new Bounds(8, loadingY, Math.max(1, width - 16), 10),
                                UiMessage.info("nclskins.status.loading"),
                                ViewSpec.Text.Alignment.CENTER)),
                List.of(),
                List.of(),
                Optional.empty(),
                List.of(),
                Optional.empty(),
                List.of(),
                List.of(),
                List.of(),
                List.of());
    }

    private static ViewSpec.Scrollbar scrollbar(
            int width,
            int y,
            int viewportWidth,
            int contentWidth,
            double offset,
            int maximum) {
        int trackLeft = 40;
        int trackRight = Math.max(trackLeft + 1, width - 40);
        int trackWidth = trackRight - trackLeft;
        int thumbWidth = maximum == 0
                ? trackWidth
                : thumbWidth(trackWidth, viewportWidth, contentWidth);
        int travel = Math.max(0, trackWidth - thumbWidth);
        int thumbLeft = maximum == 0
                ? trackLeft
                : trackLeft + (int) Math.round(travel * offset / maximum);
        return new ViewSpec.Scrollbar(
                new Bounds(trackLeft, y, trackWidth, SCROLLBAR_HEIGHT),
                new Bounds(thumbLeft, y, thumbWidth, SCROLLBAR_HEIGHT),
                (int) Math.round(offset),
                maximum);
    }

    private static int thumbWidth(int trackWidth, int viewportWidth, int contentWidth) {
        int proportional = Math.round(
                trackWidth * Math.min(viewportWidth, contentWidth)
                        / (float) Math.max(1, contentWidth));
        return Math.min(trackWidth, Math.max(32, proportional));
    }

    private static int contentWidth(int cardCount, int cardWidth) {
        long width = (long) cardCount * cardWidth
                + (long) Math.max(0, cardCount - 1) * CARD_GAP;
        return (int) Math.min(Integer.MAX_VALUE, width);
    }

    private static int maximumPixels(int contentWidth, int endpointWidth) {
        return Math.max(0, contentWidth - endpointWidth);
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
        snapshot.account().ifPresent(value -> PresetGalleryOrder.arrange(
                        value.presets().stream()
                                .filter(preset -> normalized.isEmpty()
                                        || preset.name().toLowerCase(java.util.Locale.ROOT)
                                                .contains(normalized))
                                .filter(preset -> !snapshot.hideIncompatibleGalleryLooks()
                                        || snapshot.activePresetId().filter(preset.id()::equals).isPresent()
                                        || snapshot.compatibilityFor(preset).status()
                                                != SkinCompatibilityStatus.INCOMPATIBLE)
                                .toList(),
                        snapshot.activePresetId().orElse(null))
                .forEach(preset -> cards.add(new GalleryCard(Optional.of(preset)))));
        return List.copyOf(cards);
    }

    private static List<GalleryCard> cards(
            Optional<AccountState> account,
            Optional<UUID> activePresetId,
            String query) {
        List<GalleryCard> cards = new ArrayList<>();
        cards.add(new GalleryCard(Optional.empty()));
        String normalized = query.trim().toLowerCase(java.util.Locale.ROOT);
        account.ifPresent(value -> PresetGalleryOrder.arrange(
                        value.presets().stream()
                                .filter(preset -> normalized.isEmpty()
                                        || preset.name().toLowerCase(java.util.Locale.ROOT).contains(normalized))
                                .toList(),
                        activePresetId.orElse(null))
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
        int cardWidth = Math.max(1, (int) ((long) viewportHeight * 3L / 4L));
        int cardHeight = viewportHeight;
        int cardTop = VIEWPORT_TOP;
        int endpointInset = Math.min(CARD_GAP, Math.max(0, (width - 1) / 2));
        return new GalleryLayout(
                new Bounds(
                        0,
                        VIEWPORT_TOP,
                        width,
                        viewportHeight),
                endpointInset,
                Math.max(1, width - endpointInset * 2),
                cardWidth,
                cardHeight,
                cardTop,
                scrollbarY);
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

        private String anchorId() {
            return preset.map(value -> "gallery.card." + value.id()).orElse("gallery.add");
        }

        private UiMessage accessibleLabel() {
            return preset
                    .map(value -> UiMessage.literal(value.name(), UiMessage.Severity.INFO))
                    .orElseGet(() -> UiMessage.info("nclskins.gallery.add_hint"));
        }
    }

    private record GalleryLayout(
            Bounds viewport,
            int endpointInset,
            int endpointWidth,
            int cardWidth,
            int cardHeight,
            int cardTop,
            int scrollbarY) {
        private GalleryLayout {
            Objects.requireNonNull(viewport, "viewport");
            if (endpointInset < 0
                    || endpointWidth <= 0
                    || cardWidth <= 0
                    || cardHeight <= 0
                    || cardTop < viewport.y()
                    || cardTop + cardHeight > viewport.bottom()) {
                throw new IllegalArgumentException("invalid gallery layout");
            }
        }
    }

    private record RecoveryWidget(String widgetId, Bounds bounds) {
        private RecoveryWidget {
            Objects.requireNonNull(widgetId, "widgetId");
            Objects.requireNonNull(bounds, "bounds");
        }
    }
}

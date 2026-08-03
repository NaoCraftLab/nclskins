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
    private static final int SCROLLBAR_HEIGHT = 6;

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
        List<GalleryCard> cards = cards(snapshot, query);
        int visibleCount = visibleCount(width);
        int maximum = Math.max(0, cards.size() - visibleCount);
        double visualOffset = Math.max(0.0, Math.min(scrollPosition, maximum));
        int offset = Math.min((int) Math.floor(visualOffset), maximum);
        double fraction = visualOffset - offset;
        int cardWidth = cardWidth(width, visibleCount);
        int viewportCardCount = Math.min(visibleCount, cards.size());
        int viewportWidth = cardWidth * viewportCardCount
                + CARD_GAP * Math.max(0, viewportCardCount - 1);
        int anchorTo = Math.min(cards.size(), offset + visibleCount);
        List<GalleryCard> anchorCards = cards.subList(Math.min(offset, cards.size()), anchorTo);
        int to = Math.min(cards.size(), anchorTo + (fraction > 0.001 ? 1 : 0));
        List<GalleryCard> visibleCards = cards.subList(Math.min(offset, cards.size()), to);
        int startX = startX(snapshot.activePresetId(), width, cardWidth, offset, anchorCards)
                - (int) Math.round(fraction * (cardWidth + CARD_GAP));
        int top = 62;
        int bottom = Math.max(top + 112, height - 64);
        Bounds cardViewport = new Bounds(
                (width - viewportWidth) / 2,
                top,
                Math.max(1, viewportWidth),
                bottom - top);

        List<ViewSpec.Panel> panels = new ArrayList<>();
        panels.add(new ViewSpec.Panel(
                "header", new Bounds(0, 0, width, 33), ViewSpec.Panel.Style.VANILLA_HEADER));
        panels.add(new ViewSpec.Panel(
                "footer", new Bounds(0, Math.max(0, height - 33), width, 33), ViewSpec.Panel.Style.VANILLA_FOOTER));
        List<ViewSpec.Text> texts = new ArrayList<>();
        List<ViewSpec.Widget> widgets = new ArrayList<>();
        List<ViewSpec.Preview> previews = new ArrayList<>();
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
            Bounds panelBounds = new Bounds(x, top, cardWidth, bottom - top);
            if (!intersects(panelBounds, cardViewport)) {
                x += cardWidth + CARD_GAP;
                continue;
            }
            panels.add(new ViewSpec.Panel(card.id(), panelBounds, ViewSpec.Panel.Style.VANILLA_LIST));
            if (card.preset().isEmpty()) {
                texts.add(new ViewSpec.Text(
                        "gallery.add.plus",
                        new Bounds(x, top + Math.max(26, (bottom - top) / 2 - 10), cardWidth, 10),
                        UiMessage.literal("+", UiMessage.Severity.INFO),
                        ViewSpec.Text.Alignment.CENTER));
                boolean nameSeed = matchingPresetCount(snapshot, query) == 0 && !query.isBlank();
                texts.add(new ViewSpec.Text(
                        "gallery.add.hint",
                        new Bounds(x, top + Math.max(44, (bottom - top) / 2 + 10), cardWidth, 10),
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
                        top,
                        bottom,
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

        addGlobalWidgets(snapshot, width, height, widgets);
        addHeaderAndStatus(snapshot, width, height, texts);

        Optional<ViewSpec.Scrollbar> scrollbar = maximum <= 0
                ? Optional.empty()
                : Optional.of(scrollbar(width, bottom, cards.size(), visibleCount, visualOffset, maximum));
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
                List.of());
    }

    public int maximumScroll(ClientSnapshot snapshot, int width, String query) {
        Objects.requireNonNull(snapshot, "snapshot");
        int cardCount = cards(snapshot, Objects.requireNonNull(query, "query")).size();
        return Math.max(0, cardCount - visibleCount(width));
    }

    public double scrollPositionDelta(int width, double pixelDelta) {
        if (!Double.isFinite(pixelDelta)) {
            throw new IllegalArgumentException("gallery scroll delta must be finite");
        }
        int visible = visibleCount(width);
        return pixelDelta / (cardWidth(width, visible) + CARD_GAP);
    }

    public int offsetFromScrollbar(
            ClientSnapshot snapshot, int width, double desiredThumbLeft) {
        return offsetFromScrollbar(snapshot, width, "", desiredThumbLeft);
    }

    public int offsetFromScrollbar(
            ClientSnapshot snapshot, int width, String query, double desiredThumbLeft) {
        return (int) Math.round(positionFromScrollbar(snapshot, width, query, desiredThumbLeft));
    }

    public double positionFromScrollbar(
            ClientSnapshot snapshot, int width, String query, double desiredThumbLeft) {
        Objects.requireNonNull(snapshot, "snapshot");
        int cardCount = cards(snapshot, query).size();
        int visibleCount = visibleCount(width);
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
                ViewSpec.Text.Alignment.CENTER));
        int previewTop = top + 20;
        int previewBottom = bottom - 29;
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

        int innerWidth = cardWidth - 16;
        int row = bottom - 24;
        if (confirmingDelete) {
            int half = Math.max(1, (innerWidth - 4) / 2);
            addIntersectingAction(widgets, ViewSpec.Widget.button(
                    prefix + ".delete_confirm",
                    new Bounds(x + 8, row, half, 20),
                    UiMessage.info("nclskins.gallery.delete"),
                    !snapshot.busy()), actionViewport);
            addIntersectingAction(widgets, ViewSpec.Widget.button(
                    prefix + ".delete_cancel",
                    new Bounds(x + 12 + half, row, innerWidth - half - 4, 20),
                    UiMessage.info("gui.cancel"),
                    !snapshot.busy()), actionViewport);
            return;
        }
        int square = 20;
        int gap = 2;
        int applyWidth = Math.max(42, innerWidth - square * 3 - gap * 3);
        addIntersectingAction(widgets, ViewSpec.Widget.button(
                prefix + ".apply",
                new Bounds(x + 8, row, applyWidth, 20),
                active
                        ? activeAppearanceLabel(snapshot)
                        : UiMessage.info("nclskins.gallery.apply"),
                !snapshot.busy() && !active), actionViewport);
        addIntersectingAction(widgets, compactAction(
                prefix + ".edit",
                new Bounds(x + 8 + applyWidth + gap, row, square, 20),
                "E",
                UiMessage.info("nclskins.gallery.edit"),
                !snapshot.busy()), actionViewport);
        addIntersectingAction(widgets, compactIconAction(
                prefix + ".duplicate",
                new Bounds(x + 8 + applyWidth + (square + gap), row, square, 20),
                "duplicate",
                UiMessage.info("nclskins.gallery.duplicate"),
                !snapshot.busy()), actionViewport);
        addIntersectingAction(widgets, compactIconAction(
                prefix + ".delete",
                new Bounds(x + 8 + applyWidth + (square + gap) * 2, row, square, 20),
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

    private static UiMessage activeAppearanceLabel(ClientSnapshot snapshot) {
        if (snapshot.syncInProgress()) {
            return UiMessage.info("nclskins.gallery.active_syncing");
        }
        return UiMessage.info(switch (snapshot.syncStatus()) {
            case OFFICIAL -> "nclskins.gallery.active_official";
            case PARTIAL -> "nclskins.gallery.active_partial";
            case UNKNOWN -> "nclskins.gallery.active_unknown";
            case LOCAL_ONLY, PENDING, ATTEMPTING -> "nclskins.gallery.active_local";
        });
    }

    private static ViewSpec.Widget compactAction(
            String id, Bounds bounds, String letter, UiMessage hint, boolean enabled) {
        return new ViewSpec.Widget(
                id,
                ViewSpec.WidgetKind.BUTTON,
                bounds,
                UiMessage.literal(letter, UiMessage.Severity.INFO),
                Optional.empty(),
                Optional.of(hint),
                enabled,
                true,
                0);
    }

    private static ViewSpec.Widget compactIconAction(
            String id, Bounds bounds, String icon, UiMessage accessibleLabel, boolean enabled) {
        return ViewSpec.Widget.iconButton(id, bounds, accessibleLabel, icon, enabled);
    }

    private static void addGlobalWidgets(
            ClientSnapshot snapshot, int width, int height, List<ViewSpec.Widget> widgets) {
        widgets.add(ViewSpec.Widget.button(
                "gallery.done",
                new Bounds(width / 2 - 50, height - 28, 100, 20),
                UiMessage.info("gui.done"),
                !snapshot.busy()));
        boolean retryVisible = snapshot.session().isEmpty()
                || !snapshot.session().orElseThrow().valid()
                || snapshot.rateLimited()
                || snapshot.recoveryActions().contains(RecoveryAction.REFRESH_REMOTE_PROFILE);
        if (retryVisible) {
            widgets.add(ViewSpec.Widget.button(
                    "gallery.retry_session",
                    new Bounds(Math.max(8, width - 106), 6, 98, 20),
                    UiMessage.info("nclskins.session.retry"),
                    !snapshot.busy() && snapshot.account().isPresent() && !snapshot.rateLimited()));
        }
        if (snapshot.recoveryActions().contains(RecoveryAction.RETRY_CAPE)) {
            widgets.add(ViewSpec.Widget.button(
                    "gallery.retry_cape",
                    new Bounds(8, height - 28, 96, 20),
                    UiMessage.info("nclskins.recovery.retry_cape"),
                    !snapshot.busy() && !snapshot.remoteControlsBlocked()));
        }
    }

    private static void addHeaderAndStatus(
            ClientSnapshot snapshot, int width, int height, List<ViewSpec.Text> texts) {
        texts.add(new ViewSpec.Text(
                "gallery.title",
                new Bounds(0, 10, width, 10),
                UiMessage.info("nclskins.gallery.title"),
                ViewSpec.Text.Alignment.CENTER));
        boolean valid = snapshot.session().isPresent()
                && snapshot.session().orElseThrow().valid()
                && !snapshot.rateLimited();
        texts.add(new ViewSpec.Text(
                "gallery.session",
                new Bounds(12, 12, Math.max(1, width - 24), 10),
                UiMessage.info(valid ? "nclskins.session.valid" : "nclskins.session.unavailable"),
                ViewSpec.Text.Alignment.LEFT));
        texts.add(new ViewSpec.Text(
                "gallery.status",
                new Bounds(12, Math.max(0, height - 47), Math.max(1, width - 24), 10),
                snapshot.status(),
                ViewSpec.Text.Alignment.CENTER));
    }

    private static ViewSpec.Scrollbar scrollbar(
            int width, int bottom, int cardCount, int visibleCount, double offset, int maximum) {
        int trackLeft = 40;
        int trackRight = Math.max(trackLeft + 1, width - 40);
        int trackWidth = trackRight - trackLeft;
        int thumbWidth = thumbWidth(trackWidth, cardCount, visibleCount);
        int travel = Math.max(0, trackWidth - thumbWidth);
        int thumbLeft = trackLeft + (int) Math.round(travel * offset / maximum);
        int y = bottom + 6;
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

    private static int visibleCount(int width) {
        int available = Math.max(140, width - 80);
        return Math.max(1, Math.min(3, (available + CARD_GAP) / (152 + CARD_GAP)));
    }

    private static int cardWidth(int width, int visible) {
        int available = Math.max(140, width - 80);
        return Math.max(136, Math.min(190, (available - CARD_GAP * (visible - 1)) / visible));
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
}

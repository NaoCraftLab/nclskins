package com.naocraftlab.skins.runtime;

import com.naocraftlab.skins.client.PreviewRenderer;
import com.naocraftlab.skins.core.api.ApiFailureKind;
import com.naocraftlab.skins.core.compatibility.SkinConflictReason;
import com.naocraftlab.skins.core.compatibility.SkinConsumer;
import com.naocraftlab.skins.core.compatibility.SkinConsumerState;
import com.naocraftlab.skins.core.compatibility.SkinExtensionEnvironment;
import com.naocraftlab.skins.core.compatibility.SkinFeatureEvidence;
import com.naocraftlab.skins.core.model.AccountState;
import com.naocraftlab.skins.core.model.AppearanceSyncStatus;
import com.naocraftlab.skins.core.model.SkinVariant;
import com.naocraftlab.skins.core.service.SessionCheckPhase;
import com.naocraftlab.skins.core.service.SessionFailureContext;
import com.naocraftlab.skins.core.service.SessionStatus;
import com.naocraftlab.skins.core.service.SessionValidation;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class GalleryPresenterTest {
    private final GalleryPresenter presenter = new GalleryPresenter();

    @Test
    void galleryHideFlagKeepsOnlyMatchingActiveIncompatibleLookAfterAdd() {
        AccountState account = TestFixtures.account(2);
        UUID active = account.presets().get(1).id();
        ClientSnapshot base = TestFixtures.ready(account, active, 0);
        SkinFeatureEvidence conflict = new SkinFeatureEvidence(
                List.of(), List.of(SkinConflictReason.MALFORMED_EXPRESSIVE_DATA));
        ClientSnapshot hidden = withCompatibility(
                base,
                new SkinExtensionEnvironment(
                        7, Map.of(SkinConsumer.FRESH_MOVES, SkinConsumerState.ACTIVE)),
                Map.of(TestFixtures.CLASSIC_ID, conflict, TestFixtures.SLIM_ID, conflict),
                true);

        assertEquals(
                List.of("gallery.add", "gallery.card." + active),
                presenter.cardIds(hidden, "Preset"));
        ViewSpec view = presenter.present(
                hidden, 854, 480, 0, 0, PreviewRenderer.CapeMode.CAPE,
                SkinVariant.CLASSIC, "Preset", Optional.empty());
        String indicatorId = "gallery.preset." + active + ".compatibility";
        ViewSpec.Widget indicator = view.widget(indicatorId).orElseThrow();
        assertEquals(ViewSpec.WidgetKind.COMPATIBILITY_INDICATOR, indicator.kind());
        assertEquals(Optional.of(indicator.label()), indicator.hint());
        assertTrue(view.navigationNode(indicatorId).isPresent());
        assertEquals(Optional.of(GuiIcon.STATUS_COMPATIBILITY_INCOMPATIBLE), indicator.icon());
        assertEquals(20, indicator.bounds().width());
        assertEquals(20, indicator.bounds().height());
        ViewSpec.Widget apply = view.widget("gallery.preset." + active + ".apply").orElseThrow();
        Bounds card = view.widget("gallery.card." + active).orElseThrow().bounds();
        assertEquals(card.x() + 2, indicator.bounds().x());
        assertEquals(apply.bounds().y() - 2, indicator.bounds().bottom());
        assertTrue(view.iconDecorations().stream()
                .noneMatch(icon -> icon.ownerWidgetId().equals(indicatorId)));

        ClientSnapshot visible = withCompatibility(
                base, hidden.skinExtensionEnvironment(), hidden.assetEvidence(), false);
        assertEquals(3, presenter.cardIds(visible, "Preset").size());
    }

    @Test
    void coldInitializationIsNeutralUntilAccountDataExists() {
        ClientSnapshot cold = initializingSnapshot(Optional.empty(), Optional.empty());

        ViewSpec view = presenter.present(
                cold, 320, 240, 0, 0, PreviewRenderer.CapeMode.CAPE);

        assertTrue(view.texts().stream().anyMatch(text -> text.id().equals("gallery.loading")));
        assertTrue(view.widgets().isEmpty());
        assertTrue(view.previews().isEmpty());
        assertTrue(view.panels().stream().noneMatch(panel ->
                panel.id().startsWith("gallery.card.")));
        assertTrue(view.texts().stream().noneMatch(text -> text.id().equals("gallery.offline")));
    }

    @Test
    void warmedInitializationShowsVisibleDisabledCardsWithoutStaleSessionChrome() {
        AccountState account = TestFixtures.account(5);
        UUID active = account.presets().get(4).id();
        ClientSnapshot seeded = initializingSnapshot(Optional.of(account), Optional.of(active));

        ViewSpec view = presenter.present(
                seeded,
                320,
                240,
                0,
                0,
                PreviewRenderer.CapeMode.CAPE,
                SkinVariant.CLASSIC,
                "",
                Optional.empty(),
                presenter.initialScrollPosition(
                        seeded.account(), seeded.activePresetId(), "", 320, 240));

        Bounds activeBounds = view.panels().stream()
                .filter(panel -> panel.id().equals("gallery.card." + active))
                .findFirst()
                .orElseThrow()
                .bounds();
        assertTrue(activeBounds.x() >= 0 && activeBounds.right() <= 320);
        assertFalse(view.widget("gallery.search").orElseThrow().enabled());
        assertFalse(view.widget("gallery.done").orElseThrow().enabled());
        assertTrue(view.widget("gallery.retry_session").isEmpty());
        assertTrue(view.texts().stream().noneMatch(text -> text.id().equals("gallery.offline")));
    }

    @Test
    void fittingStripCentersAsAGroupAndPublishesFullTrackScrollbar() {
        AccountState account = TestFixtures.account(2);
        UUID active = account.presets().get(0).id();
        ClientSnapshot snapshot = TestFixtures.ready(account, active, 0);

        ViewSpec view = presenter.present(
                snapshot, 854, 240, 0, 0, PreviewRenderer.CapeMode.CAPE);

        List<ViewSpec.Panel> cards = view.panels().stream()
                .filter(panel -> panel.id().startsWith("gallery.card."))
                .toList();
        assertEquals(3, cards.size());
        int contentLeft = cards.get(0).bounds().x();
        int contentRight = cards.get(cards.size() - 1).bounds().right();
        assertTrue(Math.abs((854 - contentRight) - contentLeft) <= 1);
        ViewSpec.Scrollbar scrollbar = view.scrollbar().orElseThrow();
        assertEquals(scrollbar.track(), scrollbar.thumb());
        assertEquals(0, scrollbar.maximum());
        ViewSpec.ScrollSurface surface = view.scrollSurface("gallery.cards").orElseThrow();
        assertEquals(0.0, surface.offsetPixels());
        assertEquals(0.0, surface.maximumPixels());
        assertTrue(cards.stream().allMatch(panel -> panel.style() == ViewSpec.Panel.Style.VANILLA_LIST));
    }

    @Test
    void overflowStripUsesExactLeftAndRightEdgeEndpoints() {
        AccountState account = TestFixtures.account(5);
        ClientSnapshot snapshot = TestFixtures.ready(account, account.presets().get(0).id(), 0);

        ViewSpec start = presenter.present(
                snapshot, 320, 240, 0, 0, PreviewRenderer.CapeMode.CAPE,
                SkinVariant.CLASSIC, "", Optional.empty(), 0.0);
        double maximum = start.scrollSurface("gallery.cards").orElseThrow().maximumPixels();
        ViewSpec end = presenter.present(
                snapshot, 320, 240, 0, 0, PreviewRenderer.CapeMode.CAPE,
                SkinVariant.CLASSIC, "", Optional.empty(), maximum);

        assertEquals(12, panelX(start, "gallery.card.add"));
        Bounds lastBounds = end.panels().stream()
                .filter(panel -> panel.id().startsWith("gallery.card."))
                .max(java.util.Comparator.comparingInt(panel -> panel.bounds().x()))
                .orElseThrow()
                .bounds();
        assertEquals(308, lastBounds.right());
        assertTrue(maximum > 0.0);
        assertEquals((int) maximum, end.scrollbar().orElseThrow().maximum());
    }

    @Test
    void overflowUsesScreenClipWhileTwelvePixelGuttersBelongOnlyToEndpoints() {
        AccountState account = TestFixtures.account(5);
        ClientSnapshot snapshot = TestFixtures.ready(account, account.presets().get(0).id(), 0);

        ViewSpec start = presenter.present(
                snapshot, 320, 480, 0, 0, PreviewRenderer.CapeMode.CAPE,
                SkinVariant.CLASSIC, "", Optional.empty(), 0.0);
        ViewSpec moving = presenter.present(
                snapshot, 320, 480, 0, 0, PreviewRenderer.CapeMode.CAPE,
                SkinVariant.CLASSIC, "", Optional.empty(), 20.0);

        assertEquals(new Bounds(0, 58, 320, 375), galleryViewport(start));
        assertEquals(12, panelX(start, "gallery.card.add"));
        assertEquals(-8, panelX(moving, "gallery.card.add"));
        assertEquals(Optional.of(galleryViewport(moving)),
                moving.clipFor("gallery.card.add"));
    }

    @Test
    void highNarrowWindowKeepsFullHeightThreeByFourCardsBehindTheScreenClip() {
        AccountState account = TestFixtures.account(2);
        ClientSnapshot snapshot = TestFixtures.ready(account, null, 0);

        ViewSpec view = presenter.present(
                snapshot, 120, 480, 0, 0, PreviewRenderer.CapeMode.CAPE,
                SkinVariant.CLASSIC, "", Optional.empty(), 0.0);
        Bounds add = view.panels().stream()
                .filter(panel -> panel.id().equals("gallery.card.add"))
                .findFirst()
                .orElseThrow()
                .bounds();

        assertEquals(new Bounds(0, 58, 120, 375), galleryViewport(view));
        assertEquals(12, add.x());
        assertEquals(58, add.y());
        assertEquals(281, add.width());
        assertEquals(375, add.height());
        assertTrue(add.right() > view.width());
        assertEquals(add.height() * 3 / 4, add.width());
    }

    @Test
    void initialPositionKeepsActiveOrAddVisibleAndEndpointsEdgeBounded() {
        AccountState account = TestFixtures.account(5);
        UUID active = account.presets().get(4).id();
        ClientSnapshot activeSnapshot = TestFixtures.ready(account, active, 0);
        List<int[]> viewports = List.of(
                new int[]{240, 240},
                new int[]{320, 240},
                new int[]{427, 240},
                new int[]{854, 480});

        for (int[] viewport : viewports) {
            double activePosition = presenter.initialScrollPosition(
                    activeSnapshot.account(), activeSnapshot.activePresetId(), "",
                    viewport[0], viewport[1]);
            ViewSpec view = presentAt(
                    activeSnapshot, viewport[0], viewport[1], activePosition);
            Bounds activeBounds = view.panels().stream()
                    .filter(panel -> panel.id().equals("gallery.card." + active))
                    .findFirst()
                    .orElseThrow()
                    .bounds();
            assertTrue(activeBounds.x() >= 0 && activeBounds.right() <= viewport[0]);
        }

        ClientSnapshot emptyActive = TestFixtures.ready(account, null, 0);
        UUID staleActive = UUID.randomUUID();
        for (int[] viewport : viewports) {
            for (ClientSnapshot snapshot : List.of(
                    emptyActive, TestFixtures.ready(account, staleActive, 0))) {
                assertEquals(0.0, presenter.initialScrollPosition(
                        snapshot.account(), snapshot.activePresetId(), "",
                        viewport[0], viewport[1]));
                ViewSpec addAtStart = presentAt(snapshot, viewport[0], viewport[1], 0.0);
                Bounds add = addAtStart.panels().stream()
                        .filter(panel -> panel.id().equals("gallery.card.add"))
                        .findFirst()
                        .orElseThrow()
                        .bounds();
                assertTrue(add.x() >= 0 && add.right() <= viewport[0]);
            }

            double lastPosition = presenter.maximumScroll(
                    emptyActive, viewport[0], viewport[1], "");
            ViewSpec atEnd = presentAt(
                    emptyActive, viewport[0], viewport[1], lastPosition);
            Bounds lastCard = atEnd.panels().stream()
                    .filter(panel -> panel.id().startsWith("gallery.card."))
                    .filter(panel -> !panel.id().equals("gallery.card.add"))
                    .max(java.util.Comparator.comparingInt(panel -> panel.bounds().x()))
                    .orElseThrow()
                    .bounds();
            assertEquals(viewport[0] - 12, lastCard.right());
        }

        ViewSpec addCentered = presentAt(emptyActive, 320, 240, 0.0);
        ViewSpec.Widget search = addCentered.widget("gallery.search").orElseThrow();
        assertTrue(search.selectAllOnFocusAcquire());
        assertEquals(Optional.empty(), search.submitActionId());

        String filteredQuery = "does-not-match";
        assertEquals(0.0, presenter.initialScrollPosition(
                activeSnapshot.account(), activeSnapshot.activePresetId(), filteredQuery,
                320, 240));
        ViewSpec filtered = presenter.present(
                activeSnapshot,
                320,
                240,
                0,
                0,
                PreviewRenderer.CapeMode.CAPE,
                SkinVariant.CLASSIC,
                filteredQuery,
                Optional.empty(),
                0.0);
        assertHorizontallyCentered(
                filtered.panels().stream()
                        .filter(panel -> panel.id().equals("gallery.card.add"))
                        .findFirst()
                        .orElseThrow()
                        .bounds(),
                320);
    }

    @Test
    void portraitLayoutsAreHeightDrivenAcrossRequestedViewportSizes() {
        AccountState account = TestFixtures.account(4);
        ViewSpec wide = presenter.present(
                TestFixtures.ready(account, null, 0),
                427,
                240,
                213,
                100,
                PreviewRenderer.CapeMode.CAPE);
        assertEquals(new Bounds(12, 58, 101, 135), wide.panels().get(2).bounds());
        assertEquals(new Bounds(125, 58, 101, 135), wide.panels().get(3).bounds());
        assertEquals(new Bounds(238, 58, 101, 135), wide.panels().get(4).bounds());
        assertEquals(new Bounds(0, 58, 427, 135), galleryViewport(wide));
        assertEquals(new Bounds(40, 197, 347, 6), wide.scrollbar().orElseThrow().track());
        assertEquals(new Bounds(40, 197, 253, 6), wide.scrollbar().orElseThrow().thumb());

        ViewSpec medium = presenter.present(
                TestFixtures.ready(account, null, 0),
                320,
                240,
                160,
                100,
                PreviewRenderer.CapeMode.CAPE);
        assertEquals(new Bounds(12, 58, 101, 135), medium.panels().get(2).bounds());
        assertEquals(new Bounds(125, 58, 101, 135), medium.panels().get(3).bounds());
        assertEquals(new Bounds(0, 58, 320, 135), galleryViewport(medium));
        assertEquals(new Bounds(60, 212, 200, 20), medium.widget("gallery.done").orElseThrow().bounds());
        assertEquals(new Bounds(40, 197, 240, 6), medium.scrollbar().orElseThrow().track());
        assertEquals(new Bounds(40, 197, 128, 6), medium.scrollbar().orElseThrow().thumb());
        assertEquals(4, medium.scrollbar().orElseThrow().track().y() - galleryViewport(medium).bottom());
        assertEquals(
                4,
                medium.panels().get(1).bounds().y()
                        - medium.scrollbar().orElseThrow().track().bottom());

        ViewSpec narrow = presenter.present(
                TestFixtures.ready(account, null, 0),
                240,
                240,
                120,
                100,
                PreviewRenderer.CapeMode.CAPE);
        assertEquals(new Bounds(12, 58, 101, 135), narrow.panels().get(2).bounds());
        assertEquals(new Bounds(125, 58, 101, 135), narrow.panels().get(3).bounds());
        assertEquals(new Bounds(0, 58, 240, 135), galleryViewport(narrow));

        ViewSpec tall = presenter.present(
                TestFixtures.ready(account, null, 0),
                320,
                360,
                160,
                120,
                PreviewRenderer.CapeMode.CAPE);
        assertEquals(new Bounds(12, 58, 191, 255), tall.panels().get(2).bounds());
        assertEquals(new Bounds(215, 58, 191, 255), tall.panels().get(3).bounds());
        assertEquals(new Bounds(0, 58, 320, 255), galleryViewport(tall));

        for (ViewSpec view : List.of(wide, medium, narrow, tall)) {
            Bounds card = view.panels().get(2).bounds();
            assertEquals((card.width() * 4 + 2) / 3, card.height());
            Bounds viewport = galleryViewport(view);
            assertEquals(
                    viewport.y() + (viewport.height() - card.height()) / 2,
                    card.y());
        }
        assertEquals(wide.panels().get(2).bounds().width(), medium.panels().get(2).bounds().width());
        assertEquals(medium.panels().get(2).bounds().width(), narrow.panels().get(2).bounds().width());
    }

    @Test
    void narrowCardsUseFullWidthTwoRowActionsWithTwoPixelGaps() {
        AccountState account = TestFixtures.account(4);
        ViewSpec view = presenter.present(
                TestFixtures.ready(account, null, 0),
                427,
                240,
                213,
                100,
                PreviewRenderer.CapeMode.CAPE,
                SkinVariant.CLASSIC,
                "",
                Optional.empty(),
                1.0);
        ViewSpec.Panel card = view.panels().get(3);
        UUID presetId = UUID.fromString(card.id().substring("gallery.card.".length()));
        String prefix = "gallery.preset." + presetId;
        ViewSpec.Widget apply = view.widget(prefix + ".apply").orElseThrow();
        ViewSpec.Widget edit = view.widget(prefix + ".edit").orElseThrow();
        ViewSpec.Widget duplicate = view.widget(prefix + ".duplicate").orElseThrow();
        ViewSpec.Widget delete = view.widget(prefix + ".delete").orElseThrow();

        assertEquals(new Bounds(card.bounds().x() + 2, 171, 97, 20), apply.bounds());
        assertEquals(UiMessage.info("nclskins.gallery.apply"), apply.label());
        assertTrue(apply.bounds().width() >= vanillaLabelBudget("Apply"));
        assertTrue(apply.bounds().width() >= vanillaLabelBudget("Применить"));
        assertEquals(new Bounds(card.bounds().x() + 2, 149, 31, 20), edit.bounds());
        assertEquals(new Bounds(card.bounds().x() + 35, 149, 31, 20), duplicate.bounds());
        assertEquals(new Bounds(card.bounds().x() + 68, 149, 31, 20), delete.bounds());
        assertEquals(edit.bounds().width(), delete.bounds().width());
        assertEquals(2, edit.bounds().x() - card.bounds().x());
        assertEquals(2, duplicate.bounds().x() - edit.bounds().right());
        assertEquals(2, delete.bounds().x() - duplicate.bounds().right());
        assertEquals(2, card.bounds().right() - delete.bounds().right());
        assertEquals(2, apply.bounds().x() - card.bounds().x());
        assertEquals(2, card.bounds().right() - apply.bounds().right());
        assertEquals(2, apply.bounds().y() - edit.bounds().bottom());
        assertEquals(2, card.bounds().bottom() - apply.bounds().bottom());
        ViewSpec.Preview preview = view.previews().stream()
                .filter(candidate -> candidate.id().equals(prefix + ".preview"))
                .findFirst()
                .orElseThrow();
        assertEquals(new Bounds(card.bounds().x() + 8, 78, 85, 67), preview.bounds());
        assertEquals(4, edit.bounds().y() - preview.bounds().bottom());

        ViewSpec confirming = presenter.present(
                TestFixtures.ready(account, null, 0),
                427,
                240,
                213,
                100,
                PreviewRenderer.CapeMode.CAPE,
                SkinVariant.CLASSIC,
                "",
                Optional.of(presetId),
                1.0);
        ViewSpec.Widget confirm = confirming.widget(prefix + ".delete_confirm").orElseThrow();
        ViewSpec.Widget cancel = confirming.widget(prefix + ".delete_cancel").orElseThrow();
        assertEquals(new Bounds(card.bounds().x() + 2, 171, 47, 20), confirm.bounds());
        assertEquals(new Bounds(card.bounds().x() + 51, 171, 48, 20), cancel.bounds());
        assertEquals(2, confirm.bounds().x() - card.bounds().x());
        assertEquals(2, cancel.bounds().x() - confirm.bounds().right());
        assertEquals(2, card.bounds().right() - cancel.bounds().right());
        assertEquals(2, card.bounds().bottom() - cancel.bounds().bottom());
        assertTrue(confirm.enabled());
        assertTrue(cancel.enabled());
        assertTrue(confirming.widgets().stream()
                .filter(widget -> widget.id().startsWith("gallery.preset."))
                .filter(widget -> widget.id().endsWith(".apply")
                        || widget.id().endsWith(".edit")
                        || widget.id().endsWith(".duplicate")
                        || widget.id().endsWith(".delete"))
                .allMatch(widget -> !widget.enabled()));
        assertTrue(confirming.widgets().stream()
                .filter(widget -> widget.kind() == ViewSpec.WidgetKind.CATALOG_CARD)
                .allMatch(widget -> !widget.enabled()));
    }

    @Test
    void canonicalDefaultLayoutShowsThreeCenteredCards() {
        AccountState account = TestFixtures.account(4);
        UUID active = account.presets().get(3).id();
        ClientSnapshot snapshot = TestFixtures.ready(account, active, 0);
        ViewSpec view = presenter.present(
                snapshot,
                854,
                480,
                427,
                180,
                PreviewRenderer.CapeMode.ELYTRA,
                SkinVariant.CLASSIC,
                "",
                Optional.empty(),
                presenter.initialScrollPosition(
                        snapshot.account(), snapshot.activePresetId(), "", 854, 480));

        assertEquals(new Bounds(12, 58, 281, 375), view.panels().get(2).bounds());
        assertEquals(new Bounds(305, 58, 281, 375), view.panels().get(3).bounds());
        assertEquals(new Bounds(598, 58, 281, 375), view.panels().get(4).bounds());
        assertEquals(623, view.scrollbar().orElseThrow().maximum());
        assertEquals(new Bounds(40, 437, 774, 6), view.scrollbar().orElseThrow().track());
        ViewSpec.ScrollSurface surface = view.scrollSurface("gallery.cards").orElseThrow();
        assertEquals(galleryViewport(view), surface.viewport());
        assertEquals(ViewSpec.Scrollbar.Orientation.HORIZONTAL, surface.orientation());
        assertEquals(0.0, surface.offsetPixels());
        assertEquals(623.0, surface.maximumPixels());
        assertTrue(view.widget("gallery.preset." + active + ".apply").isPresent());
        assertFalse(view.widget("gallery.preset." + active + ".apply").orElseThrow().enabled());
        assertTrue(view.previews().stream().allMatch(preview -> preview.scale() == 0.88F));

        ViewSpec.Text activeName = view.texts().stream()
                .filter(text -> text.id().equals("gallery.preset." + active + ".name"))
                .findFirst()
                .orElseThrow();
        ViewSpec.MarqueeActivation marquee = activeName.marqueeActivation().orElseThrow();
        assertTrue(marquee.focusWidgetIds().contains("gallery.preset." + active + ".apply"));
        assertTrue(marquee.focusWidgetIds().contains("gallery.preset." + active + ".delete"));
    }

    @Test
    void restingIntegerOffsetsKeepIntersectingNeighborCardsInsideTheClip() {
        AccountState account = TestFixtures.account(5);
        ClientSnapshot snapshot = TestFixtures.ready(account, null, 0);

        ViewSpec start = presenter.present(
                snapshot,
                854,
                480,
                0,
                0,
                PreviewRenderer.CapeMode.CAPE,
                SkinVariant.CLASSIC,
                "",
                Optional.empty(),
                0.0);
        Bounds startViewport = galleryViewport(start);
        assertTrue(start.panels().stream()
                .filter(panel -> panel.style() == ViewSpec.Panel.Style.VANILLA_LIST)
                .map(ViewSpec.Panel::bounds)
                .anyMatch(bounds -> bounds.x() < startViewport.right()
                        && bounds.right() > startViewport.right()));

        ViewSpec settled = presenter.present(
                snapshot,
                854,
                480,
                0,
                0,
                PreviewRenderer.CapeMode.CAPE,
                SkinVariant.CLASSIC,
                "",
                Optional.empty(),
                20.0);
        Bounds settledViewport = galleryViewport(settled);
        assertTrue(settled.panels().stream()
                .filter(panel -> panel.style() == ViewSpec.Panel.Style.VANILLA_LIST)
                .map(ViewSpec.Panel::bounds)
                .anyMatch(bounds -> bounds.x() < settledViewport.x()
                        && bounds.right() > settledViewport.x()));
        assertTrue(settled.panels().stream()
                .filter(panel -> panel.style() == ViewSpec.Panel.Style.VANILLA_LIST)
                .map(ViewSpec.Panel::bounds)
                .anyMatch(bounds -> bounds.x() < settledViewport.right()
                        && bounds.right() > settledViewport.right()));
    }

    @Test
    void activePresetLabelIsGenericForEverySyncStatusAndProgressState() {
        AccountState account = TestFixtures.account(1);
        UUID active = account.presets().get(0).id();
        ClientSnapshot base = TestFixtures.ready(account, active, 0);

        for (AppearanceSyncStatus status : AppearanceSyncStatus.values()) {
            assertActiveLabel(base, active, status, false);
            assertActiveLabel(base, active, status, true);
        }
    }

    @Test
    void headerShowsOnlyOfflineAndTheApplicableRecoveryAction() {
        ClientSnapshot valid = TestFixtures.ready(TestFixtures.account(1), null, 0);
        ViewSpec healthy = presenter.present(valid, 854, 480, 427, 180, PreviewRenderer.CapeMode.CAPE);
        assertTrue(healthy.texts().stream().noneMatch(text -> text.id().equals("gallery.offline")));
        assertTrue(healthy.texts().stream().noneMatch(text -> text.id().equals("gallery.session")));
        assertTrue(healthy.texts().stream().noneMatch(text -> text.id().equals("gallery.status")));
        assertTrue(healthy.widget("gallery.retry_session").isEmpty());
        assertTrue(healthy.widget("gallery.retry_cape").isEmpty());
        assertEquals(new Bounds(0, 12, 854, 10), text(healthy, "gallery.title").bounds());
        assertEquals(
                List.of("gallery.search", "gallery.add", "gallery.done"),
                tabIds(healthy));

        ViewSpec missing = presenter.present(
                withState(valid, Optional.empty(), false, false, AppearanceSyncStatus.LOCAL_ONLY),
                854, 480, 427, 180, PreviewRenderer.CapeMode.CAPE);
        assertTrue(missing.texts().stream().noneMatch(text -> text.id().equals("gallery.offline")));
        assertTrue(missing.widget("gallery.retry_session").isEmpty());
        assertEquals(new Bounds(0, 12, 854, 10), text(missing, "gallery.title").bounds());
        assertEquals(
                List.of("gallery.search", "gallery.add", "gallery.done"),
                tabIds(missing));

        SessionValidation invalidSession = new SessionValidation(
                SessionStatus.OFFLINE_OR_INVALID,
                TestFixtures.validSession().sessionIdentity(),
                null,
                new SessionFailureContext(
                        SessionCheckPhase.PROFILE,
                        ApiFailureKind.NETWORK,
                        null),
                "offline");
        ViewSpec invalid = presenter.present(
                withState(valid, Optional.of(invalidSession), false, true, AppearanceSyncStatus.LOCAL_ONLY),
                854, 480, 427, 180, PreviewRenderer.CapeMode.CAPE);
        assertTrue(invalid.texts().stream().anyMatch(text -> text.id().equals("gallery.offline")));
        assertFalse(invalid.widget("gallery.retry_session").orElseThrow().enabled());

        ViewSpec connecting = presenter.present(
                withSessionActivity(
                        withState(
                                valid,
                                Optional.of(invalidSession),
                                false,
                                false,
                                AppearanceSyncStatus.LOCAL_ONLY),
                        ClientSnapshot.SessionActivity.RECONNECTING),
                854, 480, 427, 180, PreviewRenderer.CapeMode.CAPE);
        assertEquals(
                UiMessage.info("nclskins.session.connecting"),
                text(connecting, "gallery.offline").message());
        assertFalse(connecting.widget("gallery.retry_session").orElseThrow().enabled());

        ViewSpec classifying = presenter.present(
                withSessionActivity(valid, ClientSnapshot.SessionActivity.CLASSIFYING),
                854, 480, 427, 180, PreviewRenderer.CapeMode.CAPE);
        assertEquals(
                UiMessage.info("nclskins.session.connecting"),
                text(classifying, "gallery.offline").message());
        assertTrue(classifying.widget("gallery.retry_session").isEmpty());

        ViewSpec rateLimited = presenter.present(
                withState(valid, valid.session(), true, false, AppearanceSyncStatus.LOCAL_ONLY),
                854, 480, 427, 180, PreviewRenderer.CapeMode.CAPE);
        assertTrue(rateLimited.texts().stream().noneMatch(text -> text.id().equals("gallery.offline")));
        assertTrue(rateLimited.widget("gallery.retry_session").isEmpty());

        ViewSpec unverifiedRateLimited = presenter.present(
                withState(valid, valid.session(), true, false, AppearanceSyncStatus.UNKNOWN),
                854, 480, 427, 180, PreviewRenderer.CapeMode.CAPE);
        assertTrue(unverifiedRateLimited.texts().stream()
                .noneMatch(text -> text.id().equals("gallery.offline")));
        assertTrue(unverifiedRateLimited.widget("gallery.retry_session").isEmpty());

        SessionValidation tokenUnavailable = new SessionValidation(
                SessionStatus.OFFLINE_OR_INVALID,
                TestFixtures.validSession().sessionIdentity(),
                null,
                new SessionFailureContext(
                        SessionCheckPhase.TOKEN_SOURCE,
                        ApiFailureKind.TOKEN_UNAVAILABLE,
                        null),
                "no token");
        ViewSpec noToken = presenter.present(
                withState(
                        valid,
                        Optional.of(tokenUnavailable),
                        false,
                        false,
                        AppearanceSyncStatus.LOCAL_ONLY),
                854, 480, 427, 180, PreviewRenderer.CapeMode.CAPE);
        assertEquals(
                UiMessage.info("nclskins.session.offline"),
                text(noToken, "gallery.offline").message());
        assertTrue(noToken.widget("gallery.retry_session").isEmpty());
        assertTrue(noToken.widget("gallery.retry_cape").isEmpty());

        ViewSpec classifyingNoToken = presenter.present(
                withSessionActivity(
                        withState(
                                valid,
                                Optional.of(tokenUnavailable),
                                false,
                                false,
                                AppearanceSyncStatus.LOCAL_ONLY),
                        ClientSnapshot.SessionActivity.CLASSIFYING),
                854, 480, 427, 180, PreviewRenderer.CapeMode.CAPE);
        assertEquals(
                UiMessage.info("nclskins.session.offline"),
                text(classifyingNoToken, "gallery.offline").message());
        assertTrue(classifyingNoToken.widget("gallery.retry_session").isEmpty());

        SessionValidation expired = new SessionValidation(
                SessionStatus.EXPIRED,
                TestFixtures.validSession().sessionIdentity(),
                null,
                new SessionFailureContext(
                        SessionCheckPhase.PROFILE,
                        ApiFailureKind.SESSION_EXPIRED,
                        401),
                "restart required");
        ViewSpec expiredSession = presenter.present(
                withState(
                        valid,
                        Optional.of(expired),
                        false,
                        false,
                        AppearanceSyncStatus.LOCAL_ONLY),
                854, 480, 427, 180, PreviewRenderer.CapeMode.CAPE);
        assertEquals(
                UiMessage.info("nclskins.session.offline"),
                text(expiredSession, "gallery.offline").message());
        assertTrue(expiredSession.widget("gallery.retry_session").isEmpty());

        ViewSpec validUnknownConnecting = presenter.present(
                withSessionActivity(
                        withState(
                                valid,
                                valid.session(),
                                false,
                                false,
                                AppearanceSyncStatus.UNKNOWN),
                        ClientSnapshot.SessionActivity.RECONNECTING),
                854, 480, 427, 180, PreviewRenderer.CapeMode.CAPE);
        assertEquals(
                UiMessage.info("nclskins.session.connecting"),
                text(validUnknownConnecting, "gallery.offline").message());
        assertFalse(validUnknownConnecting.widget("gallery.retry_session").orElseThrow().enabled());

        ViewSpec validUnknownReconciling = presenter.present(
                withSyncInProgress(
                        withState(
                                valid,
                                valid.session(),
                                false,
                                false,
                                AppearanceSyncStatus.UNKNOWN)),
                854, 480, 427, 180, PreviewRenderer.CapeMode.CAPE);
        assertTrue(validUnknownReconciling.texts().stream()
                .noneMatch(text -> text.id().equals("gallery.offline")));
        assertTrue(validUnknownReconciling.widget("gallery.retry_session").isEmpty());
    }

    @Test
    void rateLimitProgressDecoratesOnlyPendingApplyAndVisibleRetry() {
        AccountState account = TestFixtures.account(2);
        UUID active = account.presets().get(0).id();
        UUID other = account.presets().get(1).id();
        ClientSnapshot base = TestFixtures.ready(account, active, 0);
        SessionValidation invalidSession = new SessionValidation(
                SessionStatus.OFFLINE_OR_INVALID,
                TestFixtures.validSession().sessionIdentity(),
                null,
                null,
                "offline");
        ClientSnapshot snapshot = new ClientSnapshot(
                base.lifecycle(),
                base.account(),
                Optional.of(invalidSession),
                base.remoteProfile(),
                base.lastMutation(),
                base.selectedSkinId(),
                Optional.of(active),
                base.selectedCapeId(),
                base.currentOfficialSkinId(),
                Optional.of(active),
                base.editor(),
                base.addSource(),
                base.status(),
                false,
                true,
                Optional.of(new ClientSnapshot.RateLimitProgress(
                        Duration.ofSeconds(30), Duration.ofSeconds(60), 0.5)),
                base.galleryOffset(),
                base.generation(),
                7,
                AppearanceSyncStatus.PENDING,
                false);

        ViewSpec view = presenter.present(
                snapshot, 854, 480, 427, 180, PreviewRenderer.CapeMode.CAPE);
        String activeApplyId = "gallery.preset." + active + ".apply";
        String otherApplyId = "gallery.preset." + other + ".apply";
        ViewSpec.Widget activeApply = view.widget(activeApplyId).orElseThrow();

        assertTrue(activeApply.enabled());
        assertEquals(
                Optional.of(UiMessage.info("nclskins.rate_limit.delayed")),
                activeApply.hint());
        assertTrue(view.texts().stream().noneMatch(text -> text.id().equals("gallery.offline")));
        assertTrue(view.widget("gallery.retry_session").isEmpty());
        assertTrue(view.widget(otherApplyId).orElseThrow().hint().isEmpty());
        assertEquals(
                List.of(activeApplyId),
                view.progressDecorations().stream()
                        .map(ViewSpec.ProgressDecoration::ownerWidgetId)
                        .toList());
        assertTrue(view.progressDecorations().stream()
                .allMatch(decoration -> decoration.fraction() == 0.5
                        && decoration.color() == 0xFF5A8FCB
                        && decoration.height() == 2));
    }

    @Test
    void durablePartialExposesCapeRecoveryButValidUnknownDoesNotExposeSessionRecovery() {
        AccountState account = TestFixtures.account(1);
        UUID active = account.presets().get(0).id();
        ClientSnapshot base = TestFixtures.ready(account, active, 0);
        ClientSnapshot partial = withDurableStatus(base, AppearanceSyncStatus.PARTIAL);

        ViewSpec partialView = presenter.present(
                partial,
                854,
                480,
                427,
                180,
                PreviewRenderer.CapeMode.CAPE);

        assertTrue(partial.selectedPreset().isEmpty());
        assertTrue(partial.recoveryActions().contains(
                com.naocraftlab.skins.core.service.RecoveryAction.RETRY_CAPE));
        assertTrue(partialView.widget("gallery.retry_cape").orElseThrow().enabled());
        assertEquals(
                new Bounds(734, 6, 112, 20),
                partialView.widget("gallery.retry_cape").orElseThrow().bounds());
        assertTrue(partialView.widget("gallery.retry_session").isEmpty());

        ClientSnapshot unknown = withDurableStatus(base, AppearanceSyncStatus.UNKNOWN);
        ViewSpec unknownView = presenter.present(
                unknown,
                854,
                480,
                427,
                180,
                PreviewRenderer.CapeMode.CAPE);
        assertTrue(unknown.recoveryActions().contains(
                com.naocraftlab.skins.core.service.RecoveryAction.REFRESH_REMOTE_PROFILE));
        assertTrue(unknownView.widget("gallery.retry_session").isEmpty());
        assertTrue(unknownView.texts().stream()
                .noneMatch(text -> text.id().equals("gallery.offline")));
    }

    @Test
    void canonicalWideLayoutUsesFullHeightCardsAndVisibleCounts() {
        AccountState account = TestFixtures.account(6);
        UUID active = account.presets().get(5).id();
        ClientSnapshot snapshot = TestFixtures.ready(account, active, 0);
        ViewSpec view = presenter.present(
                snapshot,
                1600,
                720,
                800,
                200,
                PreviewRenderer.CapeMode.CAPE,
                SkinVariant.CLASSIC,
                "",
                Optional.empty(),
                presenter.initialScrollPosition(
                        snapshot.account(), snapshot.activePresetId(), "", 1600, 720));

        assertEquals(new Bounds(12, 58, 461, 615), view.panels().get(2).bounds());
        assertEquals(new Bounds(485, 58, 461, 615), view.panels().get(3).bounds());
        assertEquals(new Bounds(958, 58, 461, 615), view.panels().get(4).bounds());
        assertEquals(3, view.previews().size());
        assertTrue(view.widget("gallery.previous").isEmpty());
        assertTrue(view.widget("gallery.next").isEmpty());
    }

    @Test
    void fractionalScrollKeepsAnchorRowAndMovesCardsMonotonically() {
        AccountState account = TestFixtures.account(6);
        ClientSnapshot snapshot = TestFixtures.ready(account, null, 0);
        ViewSpec start = presentAt(snapshot, 0.0);
        String trackedCard = start.panels().get(3).id();

        int startX = panelX(start, trackedCard);
        int halfX = panelX(presentAt(snapshot, 20.5), trackedCard);
        int nearEndX = panelX(presentAt(snapshot, 40.25), trackedCard);
        int endX = panelX(presentAt(snapshot, 41.0), trackedCard);

        assertEquals(startX - 21, halfX);
        assertEquals(startX - 40, nearEndX);
        assertEquals(startX - 41, endX);
        assertTrue(startX > halfX);
        assertTrue(halfX > nearEndX);
        assertTrue(nearEndX >= endX);
    }

    @Test
    void scrollingUsesTheSameHeightAwareCardGeometryAsPresentation() {
        ClientSnapshot snapshot = TestFixtures.ready(TestFixtures.account(5), null, 0);

        assertEquals(370, presenter.maximumScroll(snapshot, 320, 240, ""));
        assertEquals(910, presenter.maximumScroll(snapshot, 320, 360, ""));
        assertEquals(263, presenter.maximumScroll(snapshot, 427, 240, ""));
        assertEquals(
                0.0,
                presenter.positionFromScrollbar(snapshot, 320, 240, "", 40.0));
        assertEquals(
                910.0,
                presenter.positionFromScrollbar(snapshot, 320, 360, "", 10_000.0));
    }

    @Test
    void fractionalGalleryDoesNotExposeDiscreteArrowWidgets() {
        ViewSpec view = presentAt(TestFixtures.ready(TestFixtures.account(6), null, 0), 1.5);

        assertTrue(view.widget("gallery.previous").isEmpty());
        assertTrue(view.widget("gallery.next").isEmpty());
    }

    @Test
    void fractionalCardsRetainClippedActionsWhilePartiallyVisible() {
        ViewSpec view = presentAt(TestFixtures.ready(TestFixtures.account(4), null, 0), 320, 240, 82.0);
        Bounds viewport = galleryViewport(view);

        assertEquals(new Bounds(0, 58, 320, 135), viewport);
        assertEquals(Optional.of(viewport), view.clipFor("gallery.card.add"));
        assertEquals(Optional.of(viewport), view.clipFor("gallery.add.icon"));
        ViewSpec.Widget outgoingAdd = view.widget("gallery.add").orElseThrow();
        assertTrue(outgoingAdd.bounds().x() < viewport.x());
        assertEquals(Optional.of(viewport), view.clipFor(outgoingAdd.id()));

        ViewSpec.Panel incoming = view.panels().stream()
                .filter(panel -> panel.id().startsWith("gallery.card."))
                .filter(panel -> panel.bounds().right() > viewport.right())
                .findFirst()
                .orElseThrow();
        String presetId = incoming.id().substring("gallery.card.".length());
        String prefix = "gallery.preset." + presetId;
        assertTrue(view.previews().stream().anyMatch(preview -> preview.id().equals(prefix + ".preview")));
        assertEquals(Optional.of(viewport), view.clipFor(prefix + ".preview"));
        assertTrue(incoming.bounds().right() > viewport.right());
        ViewSpec.Widget intersectingApply = view.widget(prefix + ".apply").orElseThrow();
        assertTrue(intersects(intersectingApply.bounds(), viewport));
        assertEquals(Optional.of(viewport), view.clipFor(intersectingApply.id()));
        for (String action : List.of("edit", "duplicate")) {
            assertTrue(view.widget(prefix + "." + action).isPresent());
        }
        assertTrue(view.widget(prefix + ".delete").isEmpty());

        ViewSpec outgoing = presentAt(
                TestFixtures.ready(TestFixtures.account(4), null, 0), 320, 240, 140.0);
        ViewSpec.Panel outgoingPreset = outgoing.panels().stream()
                .filter(panel -> panel.id().startsWith("gallery.card."))
                .filter(panel -> panel.bounds().x() < viewport.x())
                .findFirst()
                .orElseThrow();
        String outgoingPrefix = "gallery.preset."
                + outgoingPreset.id().substring("gallery.card.".length());
        for (String action : List.of("apply", "edit", "duplicate", "delete")) {
            ViewSpec.Widget widget = outgoing.widget(outgoingPrefix + "." + action).orElseThrow();
            assertTrue(intersects(widget.bounds(), viewport));
            assertEquals(Optional.of(viewport), outgoing.clipFor(widget.id()));
        }

        ViewSpec resting = presentAt(
                TestFixtures.ready(TestFixtures.account(4), null, 0), 320, 240, 113.0);
        ViewSpec.Panel fullyVisible = resting.panels().stream()
                .filter(panel -> panel.id().startsWith("gallery.card."))
                .filter(panel -> panel.bounds().x() >= viewport.x())
                .filter(panel -> panel.bounds().right() <= viewport.right())
                .filter(panel -> !panel.id().equals("gallery.card.add"))
                .findFirst()
                .orElseThrow();
        String fullPresetId = fullyVisible.id().substring("gallery.card.".length());
        assertTrue(resting.widget("gallery.preset." + fullPresetId + ".edit").isPresent());
    }

    @Test
    void fractionalDeleteConfirmationOmitsOnlyTheFullyClippedButton() {
        ClientSnapshot snapshot = TestFixtures.ready(TestFixtures.account(4), null, 0);
        ViewSpec ordinary = presentAt(snapshot, 320, 240, 70.0);
        Bounds viewport = galleryViewport(ordinary);
        ViewSpec.Panel incoming = ordinary.panels().stream()
                .filter(panel -> panel.id().startsWith("gallery.card."))
                .filter(panel -> panel.bounds().right() > viewport.right())
                .findFirst()
                .orElseThrow();
        UUID presetId = UUID.fromString(incoming.id().substring("gallery.card.".length()));
        String prefix = "gallery.preset." + presetId;

        ViewSpec confirming = presenter.present(
                snapshot,
                320,
                240,
                160,
                100,
                PreviewRenderer.CapeMode.CAPE,
                SkinVariant.CLASSIC,
                "",
                Optional.of(presetId),
                70.0);

        ViewSpec.Widget confirm = confirming.widget(prefix + ".delete_confirm").orElseThrow();
        assertTrue(intersects(confirm.bounds(), viewport));
        assertEquals(Optional.of(viewport), confirming.clipFor(confirm.id()));
        assertTrue(confirming.widget(prefix + ".delete_cancel").isEmpty());
    }

    @Test
    void compactActionsUseApprovedIconsWhileDeleteConfirmationStaysTextual() {
        AccountState account = TestFixtures.account(4);
        UUID presetId = account.presets().get(3).id();
        String prefix = "gallery.preset." + presetId;
        ViewSpec view = presenter.present(
                TestFixtures.ready(account, presetId, 0),
                854,
                480,
                427,
                180,
                PreviewRenderer.CapeMode.ELYTRA,
                SkinVariant.CLASSIC,
                "",
                Optional.empty(),
                1.0);

        ViewSpec.Widget edit = view.widget(prefix + ".edit").orElseThrow();
        assertEquals(ViewSpec.WidgetKind.ICON_BUTTON, edit.kind());
        assertEquals(UiMessage.info("nclskins.gallery.edit"), edit.label());
        assertEquals(Optional.of(UiMessage.info("nclskins.gallery.edit")), edit.hint());
        assertEquals(Optional.of(GuiIcon.ACTION_EDIT), edit.icon());

        ViewSpec.Widget duplicate = view.widget(prefix + ".duplicate").orElseThrow();
        assertEquals(ViewSpec.WidgetKind.ICON_BUTTON, duplicate.kind());
        assertEquals(UiMessage.info("nclskins.gallery.duplicate"), duplicate.label());
        assertEquals(Optional.of(UiMessage.info("nclskins.gallery.duplicate")), duplicate.hint());
        assertEquals(Optional.of(GuiIcon.ACTION_DUPLICATE), duplicate.icon());

        ViewSpec.Widget delete = view.widget(prefix + ".delete").orElseThrow();
        assertEquals(ViewSpec.WidgetKind.ICON_BUTTON, delete.kind());
        assertEquals(UiMessage.info("nclskins.gallery.delete"), delete.label());
        assertEquals(Optional.of(UiMessage.info("nclskins.gallery.delete")), delete.hint());
        assertEquals(Optional.of(GuiIcon.ACTION_DELETE), delete.icon());

        ViewSpec.Widget apply = view.widget(prefix + ".apply").orElseThrow();
        Bounds card = view.panels().stream()
                .filter(panel -> panel.id().equals("gallery.card." + presetId))
                .findFirst()
                .orElseThrow()
                .bounds();
        int applyWidth = card.width() - 70;
        assertEquals(new Bounds(card.x() + 2, card.bottom() - 22, applyWidth, 20), apply.bounds());
        assertEquals(new Bounds(card.x() + applyWidth + 4, card.bottom() - 22, 20, 20), edit.bounds());
        assertEquals(new Bounds(card.x() + applyWidth + 26, card.bottom() - 22, 20, 20), duplicate.bounds());
        assertEquals(new Bounds(card.x() + applyWidth + 48, card.bottom() - 22, 20, 20), delete.bounds());
        assertEquals(2, apply.bounds().x() - card.x());
        assertEquals(2, edit.bounds().x() - apply.bounds().right());
        assertEquals(2, duplicate.bounds().x() - edit.bounds().right());
        assertEquals(2, delete.bounds().x() - duplicate.bounds().right());
        assertEquals(2, card.right() - delete.bounds().right());
        assertEquals(2, card.bottom() - delete.bounds().bottom());
        ViewSpec.Preview preview = view.previews().stream()
                .filter(candidate -> candidate.id().equals(prefix + ".preview"))
                .findFirst()
                .orElseThrow();
        assertEquals(4, apply.bounds().y() - preview.bounds().bottom());

        ViewSpec confirming = presenter.present(
                TestFixtures.ready(account, presetId, 0),
                854,
                480,
                427,
                180,
                PreviewRenderer.CapeMode.ELYTRA,
                SkinVariant.CLASSIC,
                "",
                Optional.of(presetId),
                1.0);
        assertEquals(
                ViewSpec.WidgetKind.BUTTON,
                confirming.widget(prefix + ".delete_confirm").orElseThrow().kind());
        assertEquals(
                ViewSpec.WidgetKind.BUTTON,
                confirming.widget(prefix + ".delete_cancel").orElseThrow().kind());
        ViewSpec.Widget confirm = confirming.widget(prefix + ".delete_confirm").orElseThrow();
        ViewSpec.Widget cancel = confirming.widget(prefix + ".delete_cancel").orElseThrow();
        int confirmWidth = (card.width() - 6) / 2;
        assertEquals(
                new Bounds(card.x() + 2, card.bottom() - 22, confirmWidth, 20),
                confirm.bounds());
        assertEquals(
                new Bounds(
                        card.x() + confirmWidth + 4,
                        card.bottom() - 22,
                        card.width() - confirmWidth - 6,
                        20),
                cancel.bounds());
        assertEquals(2, confirm.bounds().x() - card.x());
        assertEquals(2, cancel.bounds().x() - confirm.bounds().right());
        assertEquals(2, card.right() - cancel.bounds().right());
        assertEquals(2, card.bottom() - cancel.bounds().bottom());
    }

    @Test
    void addCardOwnsOneNonInteractivePlusDecoration() {
        ViewSpec view = presenter.present(
                TestFixtures.ready(TestFixtures.account(2), null, 0),
                320,
                240,
                160,
                100,
                PreviewRenderer.CapeMode.CAPE);

        ViewSpec.Widget add = view.widget("gallery.add").orElseThrow();
        assertTrue(add.visible());
        assertEquals(ViewSpec.WidgetKind.CATALOG_CARD, add.kind());
        assertEquals(1, view.iconDecorations().size());
        ViewSpec.IconDecoration addLook = view.iconDecorations().get(0);
        assertEquals("gallery.add.icon", addLook.id());
        assertEquals("gallery.add", addLook.ownerWidgetId());
        assertEquals(GuiIcon.ACTION_ADD_LOOK, addLook.icon());
        assertEquals(32, addLook.bounds().width());
        assertEquals(32, addLook.bounds().height());
        assertEquals(add.bounds().x() + (add.bounds().width() - addLook.bounds().width()) / 2,
                addLook.bounds().x());
        ViewSpec.Text hint = view.texts().stream()
                .filter(text -> text.id().equals("gallery.add.hint"))
                .findFirst()
                .orElseThrow();
        assertEquals(8, hint.bounds().y() - addLook.bounds().bottom());
        assertEquals(0.65F, addLook.idleOpacity());
        assertEquals(1.0F, addLook.activeOpacity());
        assertEquals(Optional.of(galleryViewport(view)), view.clipFor(addLook.id()));
    }

    @Test
    void activeSelectedCardOwnsOnlyItsEnabledActionsInTabGraph() {
        AccountState account = TestFixtures.account(3);
        UUID active = account.presets().get(2).id();
        ClientSnapshot snapshot = TestFixtures.ready(account, active, 0);
        String selected = "gallery.card." + active;

        ViewSpec view = presenter.present(
                snapshot,
                854,
                480,
                427,
                180,
                PreviewRenderer.CapeMode.CAPE,
                SkinVariant.CLASSIC,
                "",
                Optional.empty(),
                presenter.initialScrollPosition(
                        snapshot.account(), snapshot.activePresetId(), "", 854, 480),
                selected);

        assertEquals(
                List.of(
                        "gallery.search",
                        selected,
                        "gallery.preset." + active + ".edit",
                        "gallery.preset." + active + ".duplicate",
                        "gallery.preset." + active + ".delete",
                        "gallery.done"),
                tabIds(view));
        assertTrue(view.navigationNodes().stream().anyMatch(node ->
                node.id().equals("gallery.add") && node.tabOrder() < 0));
        ViewSpec.Widget edit = view.widget("gallery.preset." + active + ".edit").orElseThrow();
        assertEquals(edit.id(), ViewHostPolicy.pointerOwnerAt(
                view,
                edit.bounds().x() + edit.bounds().width() / 2.0,
                edit.bounds().y() + edit.bounds().height() / 2.0).orElseThrow().id());
    }

    @Test
    void selectedCardNormalizationPreservesMatchesAndFallsBackDeterministically() {
        AccountState account = TestFixtures.account(3);
        UUID first = account.presets().get(0).id();
        UUID active = account.presets().get(2).id();
        ClientSnapshot snapshot = TestFixtures.ready(account, active, 0);

        assertEquals("gallery.card." + active,
                presenter.normalizeSelectedCardId(snapshot, "", null));
        assertEquals("gallery.card." + first,
                presenter.normalizeSelectedCardId(
                        snapshot, "Preset 1", "gallery.card." + first));
        assertEquals("gallery.card." + first,
                presenter.normalizeSelectedCardId(
                        snapshot, "Preset 1", "gallery.card." + active));
        assertEquals("gallery.add",
                presenter.normalizeSelectedCardId(
                        snapshot, "does-not-match", "gallery.card." + active));
        assertEquals("gallery.add",
                presenter.normalizeSelectedCardId(
                        TestFixtures.ready(TestFixtures.account(0), null, 0), "", null));
    }

    @Test
    void disabledGalleryDoesNotPublishEligibleCardAnchors() {
        AccountState account = TestFixtures.account(2);
        ClientSnapshot seeded = initializingSnapshot(Optional.of(account), Optional.empty());
        ViewSpec view = presenter.present(
                seeded, 320, 240, 0, 0, PreviewRenderer.CapeMode.CAPE,
                SkinVariant.CLASSIC, "", Optional.empty(), 0.0, "gallery.add");

        assertTrue(view.navigationNodes().stream()
                .filter(node -> node.pattern() == ViewSpec.NavigationPattern.HORIZONTAL_LIST)
                .noneMatch(ViewSpec.NavigationNode::enabled));
    }

    private ViewSpec presentAt(ClientSnapshot snapshot, double scrollPosition) {
        return presentAt(snapshot, 854, 480, scrollPosition);
    }

    private ViewSpec presentAt(
            ClientSnapshot snapshot, int width, int height, double scrollPosition) {
        return presenter.present(
                snapshot,
                width,
                height,
                width / 2,
                Math.min(height - 1, 180),
                PreviewRenderer.CapeMode.CAPE,
                SkinVariant.CLASSIC,
                "",
                Optional.empty(),
                scrollPosition);
    }

    private static List<String> tabIds(ViewSpec view) {
        return view.navigationNodes().stream()
                .filter(ViewSpec.NavigationNode::enabled)
                .filter(node -> node.tabOrder() >= 0)
                .sorted(java.util.Comparator.comparingInt(ViewSpec.NavigationNode::tabOrder))
                .map(ViewSpec.NavigationNode::id)
                .toList();
    }

    private static int vanillaLabelBudget(String label) {
        return label.codePointCount(0, label.length()) * 6 + 8;
    }

    private void assertActiveLabel(
            ClientSnapshot base,
            UUID active,
            AppearanceSyncStatus status,
            boolean syncInProgress) {
        ClientSnapshot snapshot = new ClientSnapshot(
                base.lifecycle(),
                base.account(),
                base.session(),
                base.remoteProfile(),
                base.lastMutation(),
                base.selectedSkinId(),
                base.selectedPresetId(),
                base.selectedCapeId(),
                base.currentOfficialSkinId(),
                base.activePresetId(),
                base.editor(),
                base.addSource(),
                base.status(),
                base.busy(),
                base.rateLimited(),
                base.galleryOffset(),
                base.generation(),
                7,
                status,
                syncInProgress);
        ViewSpec view = presenter.present(
                snapshot,
                854,
                480,
                427,
                180,
                PreviewRenderer.CapeMode.CAPE);

        ViewSpec.Widget apply = view.widget("gallery.preset." + active + ".apply").orElseThrow();
        assertEquals(UiMessage.info("nclskins.gallery.active"), apply.label());
        assertFalse(apply.enabled());
    }

    private static ClientSnapshot withState(
            ClientSnapshot base,
            Optional<SessionValidation> session,
            boolean rateLimited,
            boolean busy,
            AppearanceSyncStatus syncStatus) {
        return new ClientSnapshot(
                base.lifecycle(),
                base.account(),
                session,
                base.remoteProfile(),
                base.lastMutation(),
                base.selectedSkinId(),
                base.selectedPresetId(),
                base.selectedCapeId(),
                base.currentOfficialSkinId(),
                base.activePresetId(),
                base.editor(),
                base.addSource(),
                base.status(),
                busy,
                rateLimited,
                base.galleryOffset(),
                base.generation(),
                base.intentRevision(),
                syncStatus,
                false);
    }

    private static ClientSnapshot withDurableStatus(
            ClientSnapshot base, AppearanceSyncStatus status) {
        return new ClientSnapshot(
                base.lifecycle(),
                base.account(),
                base.session(),
                base.remoteProfile(),
                Optional.empty(),
                base.selectedSkinId(),
                Optional.empty(),
                base.selectedCapeId(),
                base.currentOfficialSkinId(),
                base.activePresetId(),
                base.editor(),
                base.addSource(),
                base.status(),
                base.busy(),
                false,
                base.galleryOffset(),
                base.generation(),
                7,
                status,
                false);
    }

    private static ClientSnapshot withStatus(ClientSnapshot base, UiMessage status) {
        return new ClientSnapshot(
                base.lifecycle(),
                base.account(),
                base.session(),
                base.remoteProfile(),
                base.lastMutation(),
                base.selectedSkinId(),
                base.selectedPresetId(),
                base.selectedCapeId(),
                base.currentOfficialSkinId(),
                base.activePresetId(),
                base.editor(),
                base.addSource(),
                status,
                base.busy(),
                base.rateLimited(),
                base.galleryOffset(),
                base.generation(),
                base.intentRevision(),
                base.syncStatus(),
                base.syncInProgress());
    }

    private static ClientSnapshot withSessionActivity(
            ClientSnapshot base, ClientSnapshot.SessionActivity sessionActivity) {
        return new ClientSnapshot(
                base.lifecycle(),
                base.account(),
                base.session(),
                base.remoteProfile(),
                base.lastMutation(),
                base.selectedSkinId(),
                base.selectedPresetId(),
                base.selectedCapeId(),
                base.currentOfficialSkinId(),
                base.activePresetId(),
                base.editor(),
                base.addSource(),
                base.status(),
                base.busy(),
                base.rateLimited(),
                base.rateLimitProgress(),
                base.galleryOffset(),
                base.generation(),
                base.intentRevision(),
                base.syncStatus(),
                base.syncInProgress(),
                sessionActivity);
    }

    private static ClientSnapshot withSyncInProgress(ClientSnapshot base) {
        return new ClientSnapshot(
                base.lifecycle(),
                base.account(),
                base.session(),
                base.remoteProfile(),
                base.lastMutation(),
                base.selectedSkinId(),
                base.selectedPresetId(),
                base.selectedCapeId(),
                base.currentOfficialSkinId(),
                base.activePresetId(),
                base.editor(),
                base.addSource(),
                base.status(),
                base.busy(),
                base.rateLimited(),
                base.galleryOffset(),
                base.generation(),
                base.intentRevision(),
                base.syncStatus(),
                true);
    }

    private static int panelX(ViewSpec view, String id) {
        return view.panels().stream()
                .filter(panel -> panel.id().equals(id))
                .findFirst()
                .orElseThrow()
                .bounds()
                .x();
    }

    private static Bounds galleryViewport(ViewSpec view) {
        return view.clipRegions().stream()
                .filter(region -> region.id().equals("gallery.cards"))
                .findFirst()
                .orElseThrow()
                .bounds();
    }

    private static ViewSpec.Text text(ViewSpec view, String id) {
        return view.texts().stream()
                .filter(text -> text.id().equals(id))
                .findFirst()
                .orElseThrow();
    }

    private static boolean intersects(Bounds candidate, Bounds viewport) {
        return candidate.right() > viewport.x()
                && candidate.x() < viewport.right()
                && candidate.bottom() > viewport.y()
                && candidate.y() < viewport.bottom();
    }

    private static void assertHorizontallyCentered(Bounds bounds, int viewportWidth) {
        assertTrue(
                Math.abs(bounds.x() + bounds.width() / 2.0 - viewportWidth / 2.0) <= 0.5,
                () -> bounds + " is not horizontally centered in " + viewportWidth);
    }

    private static ClientSnapshot initializingSnapshot(
            Optional<AccountState> account, Optional<UUID> activePresetId) {
        return new ClientSnapshot(
                ClientSnapshot.Lifecycle.INITIALIZING,
                account,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                activePresetId,
                Optional.empty(),
                Optional.empty(),
                UiMessage.info("nclskins.status.loading"),
                true,
                false,
                0,
                1,
                0,
                AppearanceSyncStatus.LOCAL_ONLY,
                false);
    }

    private static ClientSnapshot withCompatibility(
            ClientSnapshot base,
            SkinExtensionEnvironment environment,
            Map<UUID, SkinFeatureEvidence> evidence,
            boolean hideGallery) {
        return new ClientSnapshot(
                base.lifecycle(), base.account(), base.session(), base.remoteProfile(),
                base.lastMutation(), base.selectedSkinId(), base.selectedPresetId(),
                base.selectedCapeId(), base.currentOfficialSkinId(), base.activePresetId(),
                base.editor(), base.addSource(), base.status(), base.busy(), base.rateLimited(),
                base.rateLimitProgress(), base.galleryOffset(), base.generation(),
                base.intentRevision(), base.syncStatus(), base.syncInProgress(),
                base.sessionActivity(), environment, evidence, Map.of(), false, hideGallery);
    }
}

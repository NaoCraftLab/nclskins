package com.naocraftlab.skins.runtime;

import com.naocraftlab.skins.client.PreviewRenderer;
import com.naocraftlab.skins.core.model.AccountState;
import com.naocraftlab.skins.core.model.AppearanceSyncStatus;
import com.naocraftlab.skins.core.model.SkinVariant;
import com.naocraftlab.skins.core.service.SessionStatus;
import com.naocraftlab.skins.core.service.SessionValidation;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class GalleryPresenterTest {
    private final GalleryPresenter presenter = new GalleryPresenter();

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
        assertEquals(new Bounds(50, 58, 101, 135), wide.panels().get(2).bounds());
        assertEquals(new Bounds(163, 58, 101, 135), wide.panels().get(3).bounds());
        assertEquals(new Bounds(276, 58, 101, 135), wide.panels().get(4).bounds());
        assertEquals(new Bounds(0, 58, 427, 135), galleryViewport(wide));
        assertEquals(new Bounds(40, 197, 347, 6), wide.scrollbar().orElseThrow().track());
        assertEquals(new Bounds(40, 197, 208, 6), wide.scrollbar().orElseThrow().thumb());

        ViewSpec medium = presenter.present(
                TestFixtures.ready(account, null, 0),
                320,
                240,
                160,
                100,
                PreviewRenderer.CapeMode.CAPE);
        assertEquals(new Bounds(53, 58, 101, 135), medium.panels().get(2).bounds());
        assertEquals(new Bounds(166, 58, 101, 135), medium.panels().get(3).bounds());
        assertEquals(new Bounds(279, 58, 101, 135), medium.panels().get(4).bounds());
        assertEquals(new Bounds(0, 58, 320, 135), galleryViewport(medium));
        assertEquals(new Bounds(60, 212, 200, 20), medium.widget("gallery.done").orElseThrow().bounds());
        assertEquals(new Bounds(40, 197, 240, 6), medium.scrollbar().orElseThrow().track());
        assertEquals(new Bounds(40, 197, 96, 6), medium.scrollbar().orElseThrow().thumb());
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
        assertEquals(new Bounds(13, 58, 101, 135), narrow.panels().get(2).bounds());
        assertEquals(new Bounds(126, 58, 101, 135), narrow.panels().get(3).bounds());
        assertEquals(new Bounds(239, 58, 101, 135), narrow.panels().get(4).bounds());
        assertEquals(new Bounds(0, 58, 240, 135), galleryViewport(narrow));

        ViewSpec tall = presenter.present(
                TestFixtures.ready(account, null, 0),
                320,
                360,
                160,
                120,
                PreviewRenderer.CapeMode.CAPE);
        assertEquals(new Bounds(65, 58, 190, 254), tall.panels().get(2).bounds());
        assertEquals(new Bounds(267, 58, 190, 254), tall.panels().get(3).bounds());
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
                PreviewRenderer.CapeMode.CAPE);
        ViewSpec.Panel card = view.panels().get(3);
        UUID presetId = UUID.fromString(card.id().substring("gallery.card.".length()));
        String prefix = "gallery.preset." + presetId;
        ViewSpec.Widget apply = view.widget(prefix + ".apply").orElseThrow();
        ViewSpec.Widget edit = view.widget(prefix + ".edit").orElseThrow();
        ViewSpec.Widget duplicate = view.widget(prefix + ".duplicate").orElseThrow();
        ViewSpec.Widget delete = view.widget(prefix + ".delete").orElseThrow();

        assertEquals(new Bounds(165, 171, 97, 20), apply.bounds());
        assertEquals(new Bounds(165, 149, 31, 20), edit.bounds());
        assertEquals(new Bounds(198, 149, 31, 20), duplicate.bounds());
        assertEquals(new Bounds(231, 149, 31, 20), delete.bounds());
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
        assertEquals(new Bounds(171, 78, 85, 67), preview.bounds());
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
                Optional.of(presetId));
        ViewSpec.Widget confirm = confirming.widget(prefix + ".delete_confirm").orElseThrow();
        ViewSpec.Widget cancel = confirming.widget(prefix + ".delete_cancel").orElseThrow();
        assertEquals(new Bounds(163, 171, 49, 20), confirm.bounds());
        assertEquals(new Bounds(214, 171, 50, 20), cancel.bounds());
        assertEquals(0, confirm.bounds().x() - card.bounds().x());
        assertEquals(2, cancel.bounds().x() - confirm.bounds().right());
        assertEquals(0, card.bounds().right() - cancel.bounds().right());
        assertEquals(2, card.bounds().bottom() - cancel.bounds().bottom());
    }

    @Test
    void canonicalDefaultLayoutShowsThreeCenteredCards() {
        AccountState account = TestFixtures.account(4);
        UUID active = account.presets().get(3).id();
        ViewSpec view = presenter.present(
                TestFixtures.ready(account, active, 0),
                854,
                480,
                427,
                180,
                PreviewRenderer.CapeMode.ELYTRA);

        assertEquals(new Bounds(130, 118, 190, 254), view.panels().get(2).bounds());
        assertEquals(new Bounds(332, 118, 190, 254), view.panels().get(3).bounds());
        assertEquals(new Bounds(534, 118, 190, 254), view.panels().get(4).bounds());
        assertEquals(2, view.scrollbar().orElseThrow().maximum());
        assertEquals(new Bounds(40, 437, 774, 6), view.scrollbar().orElseThrow().track());
        assertTrue(view.widget("gallery.preset." + active + ".apply").isPresent());
        assertFalse(view.widget("gallery.preset." + active + ".apply").orElseThrow().enabled());
        assertTrue(view.previews().stream().allMatch(preview -> preview.scale() == 0.88F));
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

        ViewSpec missing = presenter.present(
                withState(valid, Optional.empty(), false, false, AppearanceSyncStatus.LOCAL_ONLY),
                854, 480, 427, 180, PreviewRenderer.CapeMode.CAPE);
        assertEquals(UiMessage.info("nclskins.session.offline"), text(missing, "gallery.offline").message());
        ViewSpec.Widget missingRetry = missing.widget("gallery.retry_session").orElseThrow();
        assertTrue(missingRetry.enabled());
        assertEquals(new Bounds(734, 6, 112, 20), missingRetry.bounds());
        assertTrue(text(missing, "gallery.offline").bounds().right()
                <= text(missing, "gallery.title").bounds().x());
        assertTrue(text(missing, "gallery.title").bounds().right() <= missingRetry.bounds().x());

        SessionValidation invalidSession = new SessionValidation(
                SessionStatus.OFFLINE_OR_INVALID,
                TestFixtures.validSession().sessionIdentity(),
                null,
                null,
                "offline");
        ViewSpec invalid = presenter.present(
                withState(valid, Optional.of(invalidSession), false, true, AppearanceSyncStatus.LOCAL_ONLY),
                854, 480, 427, 180, PreviewRenderer.CapeMode.CAPE);
        assertTrue(invalid.texts().stream().anyMatch(text -> text.id().equals("gallery.offline")));
        assertFalse(invalid.widget("gallery.retry_session").orElseThrow().enabled());

        ViewSpec connecting = presenter.present(
                withStatus(
                        withState(
                                valid,
                                Optional.of(invalidSession),
                                false,
                                true,
                                AppearanceSyncStatus.LOCAL_ONLY),
                        UiMessage.info("nclskins.status.checking_session")),
                854, 480, 427, 180, PreviewRenderer.CapeMode.CAPE);
        assertEquals(
                UiMessage.info("nclskins.session.connecting"),
                text(connecting, "gallery.offline").message());
        assertFalse(connecting.widget("gallery.retry_session").orElseThrow().enabled());

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
        assertFalse(unverifiedRateLimited.widget("gallery.retry_session").orElseThrow().enabled());

        ViewSpec validUnknownConnecting = presenter.present(
                withStatus(
                        withState(
                                valid,
                                valid.session(),
                                false,
                                true,
                                AppearanceSyncStatus.UNKNOWN),
                        UiMessage.info("nclskins.status.checking_session")),
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
        assertFalse(validUnknownReconciling.widget("gallery.retry_session").orElseThrow().enabled());
    }

    @Test
    void durablePartialAndUnknownExposeRecoveryAfterOutcomeAndSelectionAreGone() {
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
        assertTrue(unknownView.widget("gallery.retry_session").orElseThrow().enabled());
    }

    @Test
    void canonicalWideLayoutCapsCardAndVisibleCounts() {
        AccountState account = TestFixtures.account(6);
        UUID active = account.presets().get(5).id();
        ViewSpec view = presenter.present(
                TestFixtures.ready(account, active, 0),
                1600,
                720,
                800,
                200,
                PreviewRenderer.CapeMode.CAPE);

        assertEquals(new Bounds(503, 238, 190, 254), view.panels().get(2).bounds());
        assertEquals(new Bounds(705, 238, 190, 254), view.panels().get(3).bounds());
        assertEquals(new Bounds(907, 238, 190, 254), view.panels().get(4).bounds());
        assertEquals(2, view.previews().size());
        assertTrue(view.widget("gallery.previous").isEmpty());
        assertTrue(view.widget("gallery.next").isEmpty());
    }

    @Test
    void fractionalScrollKeepsAnchorRowAndMovesCardsMonotonically() {
        AccountState account = TestFixtures.account(4);
        ClientSnapshot snapshot = TestFixtures.ready(account, null, 0);
        ViewSpec start = presentAt(snapshot, 0.0);
        String trackedCard = start.panels().get(3).id();

        int startX = panelX(start, trackedCard);
        int halfX = panelX(presentAt(snapshot, 0.5), trackedCard);
        int nearEndX = panelX(presentAt(snapshot, 0.99), trackedCard);
        int endX = panelX(presentAt(snapshot, 1.0), trackedCard);

        assertEquals(332, startX);
        assertEquals(231, halfX);
        assertEquals(132, nearEndX);
        assertEquals(130, endX);
        assertTrue(startX > halfX);
        assertTrue(halfX > nearEndX);
        assertTrue(nearEndX >= endX);
    }

    @Test
    void scrollingUsesTheSameHeightAwareCardGeometryAsPresentation() {
        ClientSnapshot snapshot = TestFixtures.ready(TestFixtures.account(5), null, 0);

        assertEquals(4, presenter.maximumScroll(snapshot, 320, 240, ""));
        assertEquals(5, presenter.maximumScroll(snapshot, 320, 360, ""));
        assertEquals(3, presenter.maximumScroll(snapshot, 427, 240, ""));
        assertEquals(1.0, presenter.scrollPositionDelta(320, 240, 113.0));
        assertEquals(1.0, presenter.scrollPositionDelta(320, 360, 202.0));
        assertEquals(
                4.0,
                presenter.positionFromScrollbar(snapshot, 320, 240, "", 200.0));
        assertEquals(
                5.0,
                presenter.positionFromScrollbar(snapshot, 320, 360, "", 240.0));
    }

    @Test
    void fractionalGalleryDoesNotExposeDiscreteArrowWidgets() {
        ViewSpec view = presentAt(TestFixtures.ready(TestFixtures.account(6), null, 0), 1.5);

        assertTrue(view.widget("gallery.previous").isEmpty());
        assertTrue(view.widget("gallery.next").isEmpty());
    }

    @Test
    void fractionalCardsRetainClippedActionsWhilePartiallyVisible() {
        ViewSpec view = presentAt(TestFixtures.ready(TestFixtures.account(4), null, 0), 320, 240, 0.5);
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
        for (String action : List.of("edit", "duplicate", "delete")) {
            assertTrue(view.widget(prefix + "." + action).isPresent());
        }

        ViewSpec outgoing = presentAt(
                TestFixtures.ready(TestFixtures.account(4), null, 0), 320, 240, 1.74);
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
                TestFixtures.ready(TestFixtures.account(4), null, 0), 320, 240, 1.0);
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
        ViewSpec ordinary = presentAt(snapshot, 320, 240, 0.09);
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
                0.09);

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
                PreviewRenderer.CapeMode.ELYTRA);

        ViewSpec.Widget edit = view.widget(prefix + ".edit").orElseThrow();
        assertEquals(ViewSpec.WidgetKind.ICON_BUTTON, edit.kind());
        assertEquals(UiMessage.info("nclskins.gallery.edit"), edit.label());
        assertEquals(Optional.of(UiMessage.info("nclskins.gallery.edit")), edit.hint());
        assertEquals(Optional.of("edit"), edit.icon());

        ViewSpec.Widget duplicate = view.widget(prefix + ".duplicate").orElseThrow();
        assertEquals(ViewSpec.WidgetKind.ICON_BUTTON, duplicate.kind());
        assertEquals(UiMessage.info("nclskins.gallery.duplicate"), duplicate.label());
        assertEquals(Optional.of(UiMessage.info("nclskins.gallery.duplicate")), duplicate.hint());
        assertEquals(Optional.of("duplicate"), duplicate.icon());

        ViewSpec.Widget delete = view.widget(prefix + ".delete").orElseThrow();
        assertEquals(ViewSpec.WidgetKind.ICON_BUTTON, delete.kind());
        assertEquals(UiMessage.info("nclskins.gallery.delete"), delete.label());
        assertEquals(Optional.of(UiMessage.info("nclskins.gallery.delete")), delete.hint());
        assertEquals(Optional.of("delete"), delete.icon());

        ViewSpec.Widget apply = view.widget(prefix + ".apply").orElseThrow();
        Bounds card = view.panels().stream()
                .filter(panel -> panel.id().equals("gallery.card." + presetId))
                .findFirst()
                .orElseThrow()
                .bounds();
        assertEquals(new Bounds(334, 350, 120, 20), apply.bounds());
        assertEquals(new Bounds(456, 350, 20, 20), edit.bounds());
        assertEquals(new Bounds(478, 350, 20, 20), duplicate.bounds());
        assertEquals(new Bounds(500, 350, 20, 20), delete.bounds());
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
                Optional.of(presetId));
        assertEquals(
                ViewSpec.WidgetKind.BUTTON,
                confirming.widget(prefix + ".delete_confirm").orElseThrow().kind());
        assertEquals(
                ViewSpec.WidgetKind.BUTTON,
                confirming.widget(prefix + ".delete_cancel").orElseThrow().kind());
        ViewSpec.Widget confirm = confirming.widget(prefix + ".delete_confirm").orElseThrow();
        ViewSpec.Widget cancel = confirming.widget(prefix + ".delete_cancel").orElseThrow();
        assertEquals(new Bounds(332, 350, 94, 20), confirm.bounds());
        assertEquals(new Bounds(428, 350, 94, 20), cancel.bounds());
        assertEquals(0, confirm.bounds().x() - card.x());
        assertEquals(2, cancel.bounds().x() - confirm.bounds().right());
        assertEquals(0, card.right() - cancel.bounds().right());
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
        assertFalse(add.visible());
        assertEquals(1, view.iconDecorations().size());
        ViewSpec.IconDecoration plus = view.iconDecorations().get(0);
        assertEquals("gallery.add.icon", plus.id());
        assertEquals("gallery.add", plus.ownerWidgetId());
        assertEquals("plus", plus.icon());
        assertEquals(0.65F, plus.idleOpacity());
        assertEquals(1.0F, plus.activeOpacity());
        assertEquals(Optional.of(galleryViewport(view)), view.clipFor(plus.id()));
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
}

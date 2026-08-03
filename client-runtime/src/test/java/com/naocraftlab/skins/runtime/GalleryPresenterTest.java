package com.naocraftlab.skins.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.naocraftlab.skins.client.PreviewRenderer;
import com.naocraftlab.skins.core.model.AccountState;
import com.naocraftlab.skins.core.model.AppearanceSyncStatus;
import com.naocraftlab.skins.core.model.SkinVariant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

final class GalleryPresenterTest {
    private final GalleryPresenter presenter = new GalleryPresenter();

    @Test
    void canonicalSmallLayoutMatches262() {
        AccountState account = TestFixtures.account(4);
        ViewSpec view = presenter.present(
                TestFixtures.ready(account, null, 0),
                320,
                240,
                160,
                100,
                PreviewRenderer.CapeMode.CAPE);

        assertEquals(new Bounds(65, 62, 190, 114), view.panels().get(2).bounds());
        assertEquals(new Bounds(110, 212, 100, 20), view.widget("gallery.done").orElseThrow().bounds());
        assertEquals(new Bounds(40, 182, 240, 6), view.scrollbar().orElseThrow().track());
        assertEquals(new Bounds(40, 182, 48, 6), view.scrollbar().orElseThrow().thumb());
        assertTrue(view.widget("gallery.add").isPresent());
        assertFalse(view.widget("gallery.add").orElseThrow().visible());
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

        assertEquals(new Bounds(130, 62, 190, 354), view.panels().get(2).bounds());
        assertEquals(new Bounds(332, 62, 190, 354), view.panels().get(3).bounds());
        assertEquals(new Bounds(534, 62, 190, 354), view.panels().get(4).bounds());
        assertEquals(2, view.scrollbar().orElseThrow().maximum());
        assertEquals(new Bounds(40, 422, 774, 6), view.scrollbar().orElseThrow().track());
        assertTrue(view.widget("gallery.preset." + active + ".apply").isPresent());
        assertFalse(view.widget("gallery.preset." + active + ".apply").orElseThrow().enabled());
        assertTrue(view.previews().stream().allMatch(preview -> preview.scale() == 0.88F));
    }

    @Test
    void activePresetLabelMapsEverySyncStatusAndProgressOverridesThemAll() {
        AccountState account = TestFixtures.account(1);
        UUID active = account.presets().get(0).id();
        ClientSnapshot base = TestFixtures.ready(account, active, 0);

        assertActiveLabel(base, active, AppearanceSyncStatus.LOCAL_ONLY, false,
                "nclskins.gallery.active_local");
        assertActiveLabel(base, active, AppearanceSyncStatus.PENDING, false,
                "nclskins.gallery.active_local");
        assertActiveLabel(base, active, AppearanceSyncStatus.ATTEMPTING, false,
                "nclskins.gallery.active_local");
        assertActiveLabel(base, active, AppearanceSyncStatus.OFFICIAL, false,
                "nclskins.gallery.active_official");
        assertActiveLabel(base, active, AppearanceSyncStatus.PARTIAL, false,
                "nclskins.gallery.active_partial");
        assertActiveLabel(base, active, AppearanceSyncStatus.UNKNOWN, false,
                "nclskins.gallery.active_unknown");
        for (AppearanceSyncStatus status : AppearanceSyncStatus.values()) {
            assertActiveLabel(
                    base, active, status, true, "nclskins.gallery.active_syncing");
        }
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

        assertEquals(new Bounds(503, 62, 190, 594), view.panels().get(2).bounds());
        assertEquals(new Bounds(705, 62, 190, 594), view.panels().get(3).bounds());
        assertEquals(new Bounds(907, 62, 190, 594), view.panels().get(4).bounds());
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
    void fractionalGalleryDoesNotExposeDiscreteArrowWidgets() {
        ViewSpec view = presentAt(TestFixtures.ready(TestFixtures.account(6), null, 0), 1.5);

        assertTrue(view.widget("gallery.previous").isEmpty());
        assertTrue(view.widget("gallery.next").isEmpty());
    }

    @Test
    void fractionalCardsRetainClippedActionsWhilePartiallyVisible() {
        ViewSpec view = presentAt(TestFixtures.ready(TestFixtures.account(4), null, 0), 0.5);
        Bounds viewport = view.clipRegions().stream()
                .filter(region -> region.id().equals("gallery.cards"))
                .findFirst()
                .orElseThrow()
                .bounds();

        assertEquals(new Bounds(130, 62, 594, 354), viewport);
        assertEquals(Optional.of(viewport), view.clipFor("gallery.card.add"));
        assertEquals(Optional.of(viewport), view.clipFor("gallery.add.plus"));
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
            assertTrue(view.widget(prefix + "." + action).isEmpty());
        }

        ViewSpec outgoing = presentAt(TestFixtures.ready(TestFixtures.account(4), null, 0), 1.5);
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

        ViewSpec.Panel fullyVisible = view.panels().stream()
                .filter(panel -> panel.id().startsWith("gallery.card."))
                .filter(panel -> panel.bounds().x() >= viewport.x())
                .filter(panel -> panel.bounds().right() <= viewport.right())
                .findFirst()
                .orElseThrow();
        String fullPresetId = fullyVisible.id().substring("gallery.card.".length());
        assertTrue(view.widget("gallery.preset." + fullPresetId + ".edit").isPresent());
    }

    @Test
    void fractionalDeleteConfirmationOmitsOnlyTheFullyClippedButton() {
        ClientSnapshot snapshot = TestFixtures.ready(TestFixtures.account(4), null, 0);
        ViewSpec ordinary = presentAt(snapshot, 0.5);
        Bounds viewport = ordinary.clipRegions().stream()
                .filter(region -> region.id().equals("gallery.cards"))
                .findFirst()
                .orElseThrow()
                .bounds();
        ViewSpec.Panel incoming = ordinary.panels().stream()
                .filter(panel -> panel.id().startsWith("gallery.card."))
                .filter(panel -> panel.bounds().right() > viewport.right())
                .findFirst()
                .orElseThrow();
        UUID presetId = UUID.fromString(incoming.id().substring("gallery.card.".length()));
        String prefix = "gallery.preset." + presetId;

        ViewSpec confirming = presenter.present(
                snapshot,
                854,
                480,
                427,
                180,
                PreviewRenderer.CapeMode.CAPE,
                SkinVariant.CLASSIC,
                "",
                Optional.of(presetId),
                0.5);

        ViewSpec.Widget confirm = confirming.widget(prefix + ".delete_confirm").orElseThrow();
        assertTrue(intersects(confirm.bounds(), viewport));
        assertEquals(Optional.of(viewport), confirming.clipFor(confirm.id()));
        assertTrue(confirming.widget(prefix + ".delete_cancel").isEmpty());
    }

    @Test
    void compactActionsUseApprovedIconsWhileEditAndDeleteConfirmationStayTextButtons() {
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
        assertEquals(ViewSpec.WidgetKind.BUTTON, edit.kind());
        assertEquals(UiMessage.literal("E", UiMessage.Severity.INFO), edit.label());
        assertEquals(Optional.of(UiMessage.info("nclskins.gallery.edit")), edit.hint());
        assertTrue(edit.icon().isEmpty());

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
    }

    private ViewSpec presentAt(ClientSnapshot snapshot, double scrollPosition) {
        return presenter.present(
                snapshot,
                854,
                480,
                427,
                180,
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
            boolean syncInProgress,
            String expectedKey) {
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

        assertEquals(
                UiMessage.info(expectedKey),
                view.widget("gallery.preset." + active + ".apply").orElseThrow().label());
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

    private static int panelX(ViewSpec view, String id) {
        return view.panels().stream()
                .filter(panel -> panel.id().equals(id))
                .findFirst()
                .orElseThrow()
                .bounds()
                .x();
    }

    private static boolean intersects(Bounds candidate, Bounds viewport) {
        return candidate.right() > viewport.x()
                && candidate.x() < viewport.right()
                && candidate.bottom() > viewport.y()
                && candidate.y() < viewport.bottom();
    }
}

package com.naocraftlab.skins.runtime;

import com.naocraftlab.skins.client.OuterLayerVisibility;
import com.naocraftlab.skins.client.PreviewRenderer;
import com.naocraftlab.skins.core.model.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class GalleryContractTest {
    @Test
    void presenterInputIsAnImmutableProjectionNotAnotherDraftOwner() {
        var sections = new HashSet<String>();
        sections.add("gallery.favorites");
        var input = new GalleryPresenter.DraftInput(Optional.empty(), OuterLayerVisibility.allVisible(),
                OuterLayerVisibility.allVisible(), 0, Optional.empty(), sections, Optional.empty(),
                PreviewRenderer.DisplayKind.UNCONFIRMED);
        sections.clear();
        assertEquals(Set.of("gallery.favorites"), input.collapsedSections());
        assertFalse(input.dirty(Optional.empty()));
        assertTrue(input.dirty(Optional.of(UUID.randomUUID())));
        assertThrows(IllegalArgumentException.class, () -> new GalleryPresenter.DraftInput(Optional.empty(),
                OuterLayerVisibility.allVisible(), OuterLayerVisibility.allVisible(), -1, Optional.empty(),
                Set.of(), Optional.empty(), PreviewRenderer.DisplayKind.UNCONFIRMED));
    }

    @Test
    void newOperationContractsRequireImplementationsRatherThanPretendSuccess() throws Exception {
        assertTrue(java.lang.reflect.Modifier.isAbstract(LibraryEditorPort.class
                .getMethod("applyGalleryDraft", LibraryEditorPort.GalleryApplyRequest.class).getModifiers()));
        assertTrue(java.lang.reflect.Modifier.isAbstract(LibraryEditorPort.class
                .getMethod("setPresetFavorite", LibraryEditorPort.PresetFavoriteUpdate.class).getModifiers()));
        assertTrue(java.lang.reflect.Modifier.isAbstract(UiPreferencesPort.class
                .getMethod("updateGallerySections", UiPreferencesPort.GallerySectionsUpdate.class).getModifiers()));
    }

    @Test
    void scopeRejectsAccountRoundTripEpochAndRetiredDraft() {
        UUID id = UUID.randomUUID();
        var current = new AtomicBoolean(true);
        var scope = new LibraryEditorPort.GalleryScope(id, 7, current::get);
        assertTrue(scope.admits(id, 7));
        assertFalse(scope.admits(UUID.randomUUID(), 7));
        assertFalse(scope.admits(id, 8));
        current.set(false);
        assertFalse(scope.admits(id, 7));
        assertThrows(IllegalStateException.class, () -> scope.requireCurrent(id, 7));
        assertThrows(IllegalArgumentException.class,
                () -> new LibraryEditorPort.GalleryScope(id, -1, () -> true));
    }

    @Test
    void maskPatchComparesOnlyMaskAndPreservesLatestMetadata() {
        var visible = OuterLayerVisibility.allVisible();
        var hidden = OuterLayerVisibility.noneVisible();
        var latest = new AppearancePreset(UUID.randomUUID(), "Latest", SkinReference.accountDefault(),
                null, visible, Instant.EPOCH, Instant.EPOCH, null, true);
        var patch = new LibraryEditorPort.GalleryMaskPatch(visible, hidden);
        var changed = patch.applyTo(latest, Instant.EPOCH.plusSeconds(1));
        assertEquals(hidden, changed.outerLayerVisibility());
        assertTrue(changed.favorite());
        assertEquals(latest.name(), changed.name());
        assertEquals(latest.createdAt(), changed.createdAt());
        assertThrows(IllegalStateException.class, () -> patch.applyTo(changed, Instant.EPOCH.plusSeconds(2)));
    }

    @Test
    void requestsCannotSmuggleUnscopedOrAmbiguousWrites() {
        UUID id = UUID.randomUUID();
        var scope = new LibraryEditorPort.GalleryScope(id, 1, () -> true);
        var request = new LibraryEditorPort.GalleryApplyRequest(scope, id, Optional.empty(), 3);
        assertTrue(request.maskPatch().isEmpty());
        assertThrows(NullPointerException.class,
                () -> new LibraryEditorPort.GalleryApplyRequest(null, id, Optional.empty(), 3));
        assertThrows(IllegalArgumentException.class,
                () -> new LibraryEditorPort.GalleryApplyRequest(scope, id, Optional.empty(), -1));
        var values = new HashMap<String, Boolean>();
        values.put("gallery.favorites", true);
        var update = new UiPreferencesPort.GallerySectionsUpdate(scope, values);
        values.clear();
        assertEquals(Map.of("gallery.favorites", true), update.collapsed());
        assertThrows(IllegalArgumentException.class,
                () -> new UiPreferencesPort.GallerySectionsUpdate(scope, Map.of("catalog", true)));
        assertThrows(IllegalArgumentException.class,
                () -> new UiPreferencesPort.GallerySectionsUpdate(scope, Map.of()));
    }

    @Test
    void galleryMainSourceIsExplicitAndPreservedByPreviewCopies() {
        UUID account = UUID.randomUUID();
        UUID preset = UUID.randomUUID();
        var source = new ViewSpec.BodySource(ViewSpec.BodyRole.GALLERY_MAIN, account,
                1, 2, 3, 4, Optional.of(preset), "asset:revision", SkinVariant.CLASSIC, 5, Optional.empty());
        var preview = new ViewSpec.Preview("main", new Bounds(0, 0, 80, 160),
                SkinReference.accountDefault(), "asset:revision", SkinVariant.CLASSIC,
                Optional.empty(), PreviewRenderer.CapeMode.OFF, OuterLayerVisibility.allVisible(),
                0, 0, 1, Optional.of(preset), Optional.empty(), PreviewRenderer.PreviewIntent.EDITOR_DRAFT)
                .withBodySource(Optional.of(source));
        assertEquals(Optional.of(source), preview.withOwnedPng(true).withAnchorBounds(preview.bounds())
                .withNativeAccessories(false, PreviewRenderer.CapeMode.OFF).withCapeElytra(false).bodySource());
        assertNotEquals(source, new ViewSpec.BodySource(ViewSpec.BodyRole.GALLERY_MAIN, account,
                1, 2, 3, 4, Optional.of(preset), "asset:revision", SkinVariant.CLASSIC, 6, Optional.empty()));
        assertThrows(IllegalArgumentException.class, () -> new ViewSpec.Preview("card", preview.bounds(),
                preview.skin(), preview.imageRevision(), preview.variant(), Optional.empty(),
                PreviewRenderer.CapeMode.OFF, preview.outerLayerVisibility(), 0, 0, 1, Optional.of(preset))
                .withBodySource(Optional.of(source)));
    }
}

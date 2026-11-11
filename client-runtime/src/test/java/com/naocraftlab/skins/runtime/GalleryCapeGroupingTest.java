package com.naocraftlab.skins.runtime;

import com.naocraftlab.skins.client.OuterLayerVisibility;
import com.naocraftlab.skins.client.PreviewRenderer;
import com.naocraftlab.skins.core.model.SkinVariant;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

final class GalleryCapeGroupingTest {
    private static GalleryPresenter.CapeChoice texture(String id, String hash, boolean elytra) {
        return new GalleryPresenter.CapeChoice(id, UiMessage.info("nclskins.providers." + id),
                Optional.of("fixture-" + id), elytra, GalleryPresenter.CapeChoice.Content.TEXTURE,
                Optional.ofNullable(hash));
    }

    private static ViewSpec view(List<GalleryPresenter.CapeChoice> capes, String inspected) {
        var account = TestFixtures.account(1);
        var snapshot = TestFixtures.ready(account, account.presets().get(0).id(), 0);
        var mask = OuterLayerVisibility.allVisible();
        var draft = new GalleryPresenter.DraftInput(snapshot.activePresetId(), mask, mask, 0,
                Optional.ofNullable(inspected), java.util.Set.of(), Optional.empty(), PreviewRenderer.DisplayKind.ORDINARY);
        var controls = new GalleryPresenter.ControlsInput(true, mask, PreviewInteractionModel.gallery(),
                Optional.empty(), capes, 0, Optional.empty());
        return new GalleryPresenter().present(snapshot, 854, 480, 0, 0, PreviewRenderer.CapeMode.CAPE,
                SkinVariant.CLASSIC, "", Optional.empty(), 0, null, draft, controls);
    }

    @Test void orderedTooltipAndAccessibleLabelKeepNestedLocalizedMembers() {
        var a = texture("offline", "a".repeat(64), true);
        var b = texture("sneaky", "a".repeat(64), false);
        var c = texture("third", "a".repeat(64), true);
        var card = view(List.of(a, b, c), null).widget("gallery.inspect.offline").orElseThrow();
        var expected = UiMessage.info("nclskins.gallery.cape_sources", a.label(),
                UiMessage.info("nclskins.gallery.cape_sources", b.label(), c.label()));
        assertEquals(expected, card.label());
        assertEquals(Optional.of(expected), card.hint());
        assertEquals(a.label(), view(List.of(a), null).widget("gallery.inspect.offline").orElseThrow().label());
    }

    @Test void unknownIdentitiesAndNativeOrNoneNeverMergeByTextureKey() {
        var a = texture("offline", null, true);
        var b = new GalleryPresenter.CapeChoice("sneaky", UiMessage.info("nclskins.providers.sneaky"),
                a.texture(), false);
        var nativeChoice = new GalleryPresenter.CapeChoice("CPM", UiMessage.info("nclskins.compatibility.feature.customizable_player_models"),
                Optional.empty(), true, GalleryPresenter.CapeChoice.Content.NATIVE, Optional.of("a".repeat(64)));
        var none = new GalleryPresenter.CapeChoice("none", UiMessage.info("nclskins.editor.no_cape"), Optional.empty(), false);
        var input = List.of(a, b, nativeChoice, none);
        assertEquals(input, GalleryPresenter.capeGroups(input).stream().map(GalleryPresenter.CapeGroup::representative).toList());
        var confirmed = List.of(texture("offline", "a".repeat(64), true), nativeChoice,
                texture("sneaky", "a".repeat(64), false), none);
        var groups = GalleryPresenter.capeGroups(confirmed);
        assertEquals(List.of("offline", "CPM", "none"), groups.stream().map(g -> g.representative().id()).toList());
        assertEquals(List.of("offline", "sneaky"), groups.get(0).members().stream().map(GalleryPresenter.CapeChoice::id).toList());
        assertFalse(groups.get(1).contains("offline"));
        assertEquals(List.of(), GalleryPresenter.capeGroups(List.of()));
    }

    @Test void confirmedNonAdjacentDuplicatesKeepFirstPositionAndInspectedRawMember() {
        var a = texture("offline", "a".repeat(64), true);
        var x = texture("other", "b".repeat(64), true);
        var b = texture("sneaky", "a".repeat(64), false);
        var raw = List.of(a, x, b);
        var result = view(raw, "sneaky");
        assertEquals(List.of("gallery.inspect.offline", "gallery.inspect.other"), result.widgets().stream()
                .filter(w -> w.id().startsWith("gallery.inspect.")).map(ViewSpec.Widget::id).toList());
        assertEquals(Optional.of("active"), result.widget("gallery.inspect.offline").orElseThrow().value());
        assertTrue(result.widget("gallery.inspect.sneaky").isEmpty());
        assertTrue(result.navigationNode("gallery.inspect.sneaky").isEmpty());
        assertTrue(result.backEquipmentPreviews().stream().noneMatch(p -> p.id().startsWith("gallery.inspect.sneaky")));
        var body = result.previews().stream().filter(p -> p.id().equals("gallery.preview")).findFirst().orElseThrow();
        assertEquals(b.texture(), body.capeId());
        assertFalse(body.capeHasElytra());
        assertEquals(List.of(a, x, b), raw);
    }
}

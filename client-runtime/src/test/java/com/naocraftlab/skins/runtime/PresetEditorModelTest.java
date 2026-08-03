package com.naocraftlab.skins.runtime;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.naocraftlab.skins.client.PreviewRenderer;
import com.naocraftlab.skins.client.OuterLayerPart;
import com.naocraftlab.skins.core.model.AccountState;
import com.naocraftlab.skins.core.model.AppearancePreset;
import com.naocraftlab.skins.core.model.CatalogOrigin;
import com.naocraftlab.skins.core.model.OwnedCapeEntry;
import com.naocraftlab.skins.core.model.RemoteAssetState;
import com.naocraftlab.skins.core.model.SkinReference;
import com.naocraftlab.skins.core.model.SkinVariant;
import java.util.Map;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

final class PresetEditorModelTest {
    private static final TextResolver ENGLISH = message -> switch (message.key()) {
        case "nclskins.editor.default_name" -> "Preset " + message.arguments().get(0);
        case "nclskins.gallery.copy_name" -> "Copy of " + message.arguments().get(0);
        default -> message.key();
    };

    @Test
    void draftChangesDoNotMutateOriginalAndSaveCarriesOwnedPng() {
        AccountState account = TestFixtures.account(1);
        AppearancePreset original = account.presets().get(0);
        PresetEditorModel model = PresetEditorModel.open(
                account,
                Optional.of(original),
                Optional.of(TestFixtures.validSession().profile()),
                Optional.of(original.id()),
                ENGLISH,
                480,
                PreviewRenderer.CapeMode.CAPE);
        byte[] png = {1, 2, 3};

        PresetEditorModel changed = model
                .withName("Changed")
                .toggleVariant()
                .withPng("skin.png", png)
                .toggleOuterLayerPart(OuterLayerPart.LEFT_ARM);
        png[0] = 9;

        assertEquals(original.name(), model.name());
        assertEquals("Changed", changed.name());
        assertEquals(SkinVariant.SLIM, changed.variant());
        assertFalse(changed.preview().outerLayerVisibility().visible(OuterLayerPart.LEFT_ARM));
        assertTrue(changed.preview().outerLayerVisibility().visible(OuterLayerPart.RIGHT_ARM));
        assertEquals(changed.preview().outerLayerVisibility(), changed.saveRequest().outerLayerVisibility());
        assertArrayEquals(new byte[] {1, 2, 3}, changed.saveRequest().pngBytes().orElseThrow());
        assertEquals(original.name(), account.presets().get(0).name());
    }

    @Test
    void fileDraftPublishesAStableCatalogNameDerivedFromTheFileName() {
        PresetEditorModel model = PresetEditorModel.open(
                        TestFixtures.account(0),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        ENGLISH,
                        480,
                        PreviewRenderer.CapeMode.OFF)
                .withPng("  My skin.PNG  ", new byte[] {1});

        assertEquals(Optional.of("My skin"), model.saveRequest().personalSkinName());
        assertEquals(
                Optional.of("Imported skin"),
                model.withPng(".png", new byte[] {2}).saveRequest().personalSkinName());
        assertEquals(
                128,
                model.withPng("x".repeat(140) + ".png", new byte[] {3})
                        .saveRequest()
                        .personalSkinName()
                        .orElseThrow()
                        .length());
    }

    @Test
    void personalCatalogDraftSwitchesReusableAssetsAndNeverCopiesItsPngOnSave() {
        PresetEditorModel model = PresetEditorModel.openPersonalCatalog(
                "Saved skin",
                Map.of(
                        SkinVariant.CLASSIC,
                        new PresetEditorModel.ReusableCatalogVariant(
                                SkinReference.asset(TestFixtures.CLASSIC_ID), new byte[] {1}),
                        SkinVariant.SLIM,
                        new PresetEditorModel.ReusableCatalogVariant(
                                SkinReference.asset(TestFixtures.SLIM_ID), new byte[] {2})),
                SkinVariant.CLASSIC,
                Optional.empty(),
                480,
                PreviewRenderer.CapeMode.OFF);

        assertEquals(TestFixtures.CLASSIC_ID, model.skin().assetId());
        assertEquals(TestFixtures.SLIM_ID, model.toggleVariant().skin().assetId());
        assertTrue(model.saveRequest().pngBytes().isEmpty());
        assertTrue(model.saveRequest().catalogOrigin().isEmpty());
        assertTrue(model.saveRequest().personalSkinName().isEmpty());
    }

    @Test
    void newDraftStartsWithTheRememberedModelAndMatchingBundledPlaceholder() {
        PresetEditorModel model = PresetEditorModel.open(
                TestFixtures.account(0),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                ENGLISH,
                480,
                PreviewRenderer.CapeMode.OFF,
                SkinVariant.SLIM);

        assertEquals(SkinVariant.SLIM, model.variant());
        assertEquals(TestFixtures.SLIM_ID, model.skin().assetId());
    }

    @Test
    void canonicalSmallEditorLayoutMatches262() {
        PresetEditorModel model = PresetEditorModel.open(
                TestFixtures.account(0),
                Optional.empty(),
                Optional.of(TestFixtures.validSession().profile()),
                Optional.empty(),
                ENGLISH,
                240,
                PreviewRenderer.CapeMode.CAPE);
        ViewSpec view = model.present(320, 240);

        assertEquals(new Bounds(150, 55, 154, 20), view.widget("editor.name").orElseThrow().bounds());
        assertEquals(new Bounds(150, 212, 75, 20), view.widget("editor.save").orElseThrow().bounds());
        assertEquals(new Bounds(229, 212, 75, 20), view.widget("editor.cancel").orElseThrow().bounds());
        assertEquals(new Bounds(0, 0, 150, 240), view.previews().get(0).bounds());
        assertEquals(0.6958763F, view.previews().get(0).scale(), 0.000001F);
    }

    @Test
    void canonicalDefaultAndWideEditorLayoutsMatch262() {
        PresetEditorModel medium = PresetEditorModel.open(
                TestFixtures.account(0),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                ENGLISH,
                480,
                PreviewRenderer.CapeMode.ELYTRA);
        ViewSpec defaultView = medium.present(854, 480);
        assertEquals(new Bounds(578, 55, 260, 20), defaultView.widget("editor.name").orElseThrow().bounds());
        assertEquals(new Bounds(0, 0, 578, 480), defaultView.previews().get(0).bounds());
        assertEquals(new Bounds(578, 452, 128, 20), defaultView.widget("editor.save").orElseThrow().bounds());

        PresetEditorModel wide = PresetEditorModel.open(
                TestFixtures.account(0),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                ENGLISH,
                720,
                PreviewRenderer.CapeMode.CAPE);
        ViewSpec wideView = wide.present(1600, 720);
        assertEquals(new Bounds(1324, 55, 260, 20), wideView.widget("editor.name").orElseThrow().bounds());
        assertEquals(new Bounds(0, 0, 1324, 720), wideView.previews().get(0).bounds());
    }

    @Test
    void previewCycleButtonsExposeCurrentStateAndExactGeometry() {
        PresetEditorModel model = PresetEditorModel.open(
                TestFixtures.account(0),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                ENGLISH,
                480,
                PreviewRenderer.CapeMode.OFF);

        ViewSpec enabled = model.present(854, 480, 0.0);
        assertCycleButton(enabled, "head", new Bounds(2, 208, 20, 20), "head_on",
                "nclskins.editor.outer_head", "nclskins.editor.outer_state_on");
        assertCycleButton(enabled, "body", new Bounds(2, 230, 20, 20), "body_all_on",
                "nclskins.editor.outer_body_arms", "nclskins.editor.outer_state_on");
        assertCycleButton(enabled, "legs", new Bounds(2, 252, 20, 20), "legs_all_on",
                "nclskins.editor.outer_legs", "nclskins.editor.outer_state_on");
        assertEquals(3, enabled.widgets().stream()
                .filter(widget -> widget.id().startsWith("editor.outer_layer."))
                .count());
        assertTrue(enabled.widget("editor.outer_layer.left_arm").isEmpty());
        assertTrue(enabled.widget("editor.outer_layer.all").isEmpty());
        assertTrue(enabled.widget("editor.preview_mode").isEmpty());

        PresetEditorModel changed = model
                .cycleOuterLayer("head", 1)
                .cycleOuterLayer("body", -1)
                .cycleOuterLayer("legs", -1);
        ViewSpec changedView = changed.present(854, 480, 0.0);
        assertCycleButton(changedView, "head", new Bounds(2, 208, 20, 20), "head_off",
                "nclskins.editor.outer_head", "nclskins.editor.outer_state_off");
        assertCycleButton(changedView, "body", new Bounds(2, 230, 20, 20), "body_only_arms_on",
                "nclskins.editor.outer_body_arms",
                "nclskins.editor.outer_state_arms_without_body");
        assertCycleButton(changedView, "legs", new Bounds(2, 252, 20, 20), "legs_right_off",
                "nclskins.editor.outer_legs", "nclskins.editor.outer_state_no_right_leg");
    }

    @Test
    void previewCycleButtonsStayInsideTheVisiblePreviewAtMinimumSize() {
        List<OwnedCapeEntry> capes = capes(5);
        PresetEditorModel model = PresetEditorModel.open(
                TestFixtures.account(0),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                ENGLISH,
                240,
                PreviewRenderer.CapeMode.CAPE,
                SkinVariant.CLASSIC,
                capes);

        ViewSpec view = model.selectCape(1).present(320, 240, 0.0);
        assertEquals(new Bounds(2, 35, 20, 20),
                view.widget("editor.preview_mode").orElseThrow().bounds());
        assertEquals(Optional.of("cape"), view.widget("editor.preview_mode").orElseThrow().icon());
        assertEquals(new Bounds(2, 88, 20, 20),
                view.widget("editor.outer_layer.head").orElseThrow().bounds());
        assertEquals(new Bounds(2, 110, 20, 20),
                view.widget("editor.outer_layer.body").orElseThrow().bounds());
        assertEquals(new Bounds(2, 132, 20, 20),
                view.widget("editor.outer_layer.legs").orElseThrow().bounds());

        Bounds capeViewport = view.clipRegions().stream()
                .filter(region -> region.id().equals("editor.capes"))
                .findFirst()
                .orElseThrow()
                .bounds();
        Bounds scrollbar = view.scrollbar().orElseThrow().track();
        assertEquals(ViewSpec.Scrollbar.Orientation.VERTICAL,
                view.scrollbar().orElseThrow().orientation());
        assertEquals(capeViewport.y(), scrollbar.y());
        assertEquals(capeViewport.height(), scrollbar.height());
    }

    @Test
    void ownedCapesUseScrollableTextureCardsAndSelectionUpdatesPreviewMode() {
        List<OwnedCapeEntry> capes = capes(5);
        PresetEditorModel model = PresetEditorModel.open(
                TestFixtures.account(0),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                ENGLISH,
                240,
                PreviewRenderer.CapeMode.CAPE,
                SkinVariant.CLASSIC,
                capes);

        ViewSpec start = model.present(320, 240, 0.0);
        assertTrue(start.widgets().stream().noneMatch(widget ->
                widget.id().equals("editor.cape_previous") || widget.id().equals("editor.cape_next")));
        assertEquals(ViewSpec.WidgetKind.CAPE_CARD,
                start.widget("editor.cape_choice.0").orElseThrow().kind());
        assertFalse(start.capeTextures().isEmpty());
        assertEquals(2, distinctCapeCardColumns(start));
        assertEquals(ViewSpec.Scrollbar.Orientation.VERTICAL,
                start.scrollbar().orElseThrow().orientation());
        assertTrue(model.maximumCapeScroll(320, 240) > 0);

        PresetEditorModel roomy = PresetEditorModel.open(
                TestFixtures.account(0),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                ENGLISH,
                480,
                PreviewRenderer.CapeMode.CAPE,
                SkinVariant.CLASSIC,
                capes);
        assertEquals(3, distinctCapeCardColumns(roomy.present(854, 480, 0.0)));

        ViewSpec end = model.present(320, 240, model.maximumCapeScroll(320, 240));
        assertTrue(end.capeTextures().stream().anyMatch(texture -> texture.capeId().equals("cape-4")));

        ViewSpec partial = model.present(320, 240, 1.0);
        Bounds capeViewport = partial.clipRegions().stream()
                .filter(region -> region.id().equals("editor.capes"))
                .findFirst()
                .orElseThrow()
                .bounds();
        ViewSpec.Widget partialChoice = partial.widget("editor.cape_choice.0").orElseThrow();
        assertTrue(partialChoice.bounds().y() < capeViewport.y());
        assertEquals(Optional.of(capeViewport), partial.clipFor(partialChoice.id()));

        PresetEditorModel selected = model.selectCape(2).cyclePreviewMode();
        ViewSpec.Preview preview = selected.present(320, 240).previews().get(0);
        assertEquals(Optional.of("cape-1"), preview.capeId());
        assertEquals(PreviewRenderer.CapeMode.ELYTRA, preview.capeMode());
    }

    @Test
    void catalogDraftMetadataUsesAFramelessInfoButton() {
        PresetEditorModel model = PresetEditorModel.openCatalog(
                "Catalog skin",
                new CatalogOrigin(
                        "pack",
                        "heroes",
                        "hero",
                        Optional.of("Description"),
                        Optional.of("Authors")),
                Map.of(SkinVariant.CLASSIC, new byte[] {1}),
                SkinVariant.CLASSIC,
                Optional.empty(),
                240,
                PreviewRenderer.CapeMode.OFF);

        ViewSpec.Widget info = model.present(320, 240).widget("editor.catalog_info").orElseThrow();
        assertEquals(ViewSpec.WidgetKind.INFO_BUTTON, info.kind());
        assertEquals(14, info.bounds().height());
        assertTrue(info.icon().isEmpty());
        assertTrue(info.hint().isPresent());
    }

    private static void assertCycleButton(
            ViewSpec view,
            String id,
            Bounds bounds,
            String icon,
            String labelKey,
            String stateKey) {
        ViewSpec.Widget widget = view.widget("editor.outer_layer." + id).orElseThrow();
        assertEquals(ViewSpec.WidgetKind.ICON_BUTTON, widget.kind());
        assertEquals(bounds, widget.bounds());
        assertEquals(Optional.of(icon), widget.icon());
        UiMessage accessibleLabel = UiMessage.info(
                "nclskins.editor.outer_toggle", UiMessage.info(labelKey), UiMessage.info(stateKey));
        assertEquals(accessibleLabel, widget.label());
        assertEquals(Optional.of(accessibleLabel), widget.hint());
    }

    private static List<OwnedCapeEntry> capes(int count) {
        return java.util.stream.IntStream.range(0, count)
                .mapToObj(index -> new OwnedCapeEntry(
                        "cape-" + index,
                        "Cape " + index,
                        RemoteAssetState.ACTIVE,
                        null))
                .toList();
    }

    private static long distinctCapeCardColumns(ViewSpec view) {
        return view.panels().stream()
                .filter(panel -> panel.id().startsWith("editor.cape_card."))
                .map(panel -> panel.bounds().x())
                .distinct()
                .count();
    }
}

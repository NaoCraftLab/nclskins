package com.naocraftlab.skins.runtime;

import com.naocraftlab.skins.client.BackEquipmentPreviewRenderer;
import com.naocraftlab.skins.client.OuterLayerPart;
import com.naocraftlab.skins.client.OuterLayerVisibility;
import com.naocraftlab.skins.client.PreviewRenderer;
import com.naocraftlab.skins.core.model.AccountState;
import com.naocraftlab.skins.core.model.AppearancePreset;
import com.naocraftlab.skins.core.model.CatalogOrigin;
import com.naocraftlab.skins.core.model.EditorTab;
import com.naocraftlab.skins.core.model.OwnedCapeEntry;
import com.naocraftlab.skins.core.model.RemoteAssetState;
import com.naocraftlab.skins.core.model.SkinReference;
import com.naocraftlab.skins.core.model.SkinVariant;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
    void duplicateDraftCopiesTheSourceSnapshotButHasNoOriginalPresetIdentity() {
        AccountState originalAccount = TestFixtures.account(2);
        AppearancePreset original = originalAccount.presets().get(1);
        OuterLayerVisibility visibility = OuterLayerVisibility.allVisible()
                .with(OuterLayerPart.HEAD, false)
                .with(OuterLayerPart.LEFT_LEG, false);
        AppearancePreset source = new AppearancePreset(
                original.id(),
                original.name(),
                original.skin(),
                original.capeId(),
                visibility,
                original.createdAt(),
                original.updatedAt());
        AccountState account = new AccountState(
                AccountState.CURRENT_SCHEMA_VERSION,
                originalAccount.accountId(),
                originalAccount.skinAssets(),
                originalAccount.personalSkins(),
                List.of(originalAccount.presets().get(0), source),
                originalAccount.updatedAt());

        PresetEditorModel duplicate = PresetEditorModel.openDuplicate(
                account,
                source,
                "Copy of " + source.name(),
                Optional.of(TestFixtures.validSession().profile()),
                Optional.of(source.id()),
                ENGLISH,
                480,
                PreviewRenderer.CapeMode.CAPE,
                SkinVariant.CLASSIC,
                List.of());

        assertTrue(duplicate.originalPresetId().isEmpty());
        assertEquals("Copy of " + source.name(), duplicate.name());
        assertEquals(source.skin(), duplicate.skin());
        assertEquals(SkinVariant.SLIM, duplicate.variant());
        assertEquals(source.optionalCapeId(), duplicate.capeId());
        assertEquals(visibility, duplicate.preview().outerLayerVisibility());
        assertTrue(duplicate.saveRequest().originalPresetId().isEmpty());
        assertEquals(visibility, duplicate.saveRequest().outerLayerVisibility());
        assertEquals(2, account.presets().size());
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

        assertEquals(Optional.of("My Skin"), model.saveRequest().personalSkinName());
        assertEquals(
                Optional.of("Imported Skin"),
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
    void successfulPngSelectionClearsStatusWhileActionableFeedbackRemainsAvailable() {
        PresetEditorModel model = PresetEditorModel.open(
                TestFixtures.account(0),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                ENGLISH,
                480,
                PreviewRenderer.CapeMode.OFF);

        PresetEditorModel selected = model
                .withStatus(UiMessage.info("nclskins.status.cancelled"))
                .withImportedPng("skin.png", new byte[] {1}, SkinVariant.SLIM);

        assertTrue(selected.status().isEmpty());
        assertTrue(selected.present(320, 240).texts().stream()
                .noneMatch(text -> text.id().equals("editor.status")));
        assertEquals(
                Optional.of(UiMessage.info("nclskins.status.choose_png")),
                model.withBusy(UiMessage.info("nclskins.status.choose_png")).status());
        assertEquals(
                Optional.of(UiMessage.error("nclskins.error.png")),
                model.withStatus(UiMessage.error("nclskins.error.png")).status());
    }

    @Test
    void editorHeaderContainsOnlyTheStandardTitleAndActionableStatusUsesTheFooter() {
        PresetEditorModel model = PresetEditorModel.open(
                TestFixtures.account(0),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                ENGLISH,
                240,
                PreviewRenderer.CapeMode.OFF);

        ViewSpec normal = model.present(320, 240);
        ViewSpec.Text title = normal.texts().stream()
                .filter(text -> text.id().equals("editor.title"))
                .findFirst()
                .orElseThrow();
        assertEquals(new Bounds(0, 12, 320, 10), title.bounds());
        assertEquals(
                List.of("editor.title"),
                normal.texts().stream()
                        .filter(text -> text.bounds().y() < 33 && text.bounds().bottom() > 0)
                        .map(ViewSpec.Text::id)
                        .toList());
        assertTrue(normal.widgets().stream()
                .noneMatch(widget -> widget.bounds().y() < 33 && widget.bounds().bottom() > 0));

        ViewSpec busy = model.withBusy(UiMessage.info("nclskins.status.saving")).present(320, 240);
        ViewSpec.Text status = busy.texts().stream()
                .filter(text -> text.id().equals("editor.status"))
                .findFirst()
                .orElseThrow();
        assertEquals(new Bounds(24, 192, 112, 10), status.bounds());
        assertEquals(
                List.of("editor.title"),
                busy.texts().stream()
                        .filter(text -> text.bounds().y() < 33 && text.bounds().bottom() > 0)
                        .map(ViewSpec.Text::id)
                        .toList());
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
    void canonicalSmallEditorLayoutMatchesReference() {
        PresetEditorModel model = PresetEditorModel.open(
                TestFixtures.account(0),
                Optional.empty(),
                Optional.of(TestFixtures.validSession().profile()),
                Optional.empty(),
                ENGLISH,
                240,
                PreviewRenderer.CapeMode.CAPE);
        ViewSpec view = model.present(320, 240);

        ViewSpec.Widget name = view.widget("editor.name").orElseThrow();
        assertEquals(new Bounds(176, 52, 128, 20), name.bounds());
        assertTrue(name.selectAllOnFocusAcquire());
        assertEquals(Optional.empty(), name.submitActionId());
        assertEquals(new Bounds(83, 212, 75, 20), view.widget("editor.save").orElseThrow().bounds());
        assertEquals(new Bounds(162, 212, 75, 20), view.widget("editor.cancel").orElseThrow().bounds());
        assertEquals(new Bounds(0, 0, 320, 240), view.previews().get(0).bounds());
        assertEquals(new Bounds(0, 0, 160, 240), view.previews().get(0).anchorBounds());
        assertEquals(0.6958763F, view.previews().get(0).scale(), 0.000001F);
    }

    @Test
    void canonicalDefaultAndWideEditorLayoutsMatchReference() {
        PresetEditorModel medium = PresetEditorModel.open(
                TestFixtures.account(0),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                ENGLISH,
                480,
                PreviewRenderer.CapeMode.ELYTRA);
        ViewSpec defaultView = medium.present(854, 480);
        assertEquals(new Bounds(443, 52, 395, 20), defaultView.widget("editor.name").orElseThrow().bounds());
        assertEquals(new Bounds(0, 0, 854, 480), defaultView.previews().get(0).bounds());
        assertEquals(new Bounds(0, 0, 427, 480), defaultView.previews().get(0).anchorBounds());
        assertEquals(new Bounds(297, 452, 128, 20), defaultView.widget("editor.save").orElseThrow().bounds());

        PresetEditorModel wide = PresetEditorModel.open(
                TestFixtures.account(0),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                ENGLISH,
                720,
                PreviewRenderer.CapeMode.CAPE);
        ViewSpec wideView = wide.present(1600, 720);
        assertEquals(new Bounds(816, 52, 768, 20), wideView.widget("editor.name").orElseThrow().bounds());
        assertEquals(new Bounds(0, 0, 1600, 720), wideView.previews().get(0).bounds());
        assertEquals(new Bounds(0, 0, 800, 720), wideView.previews().get(0).anchorBounds());
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
        assertCycleButton(enabled, "head", new Bounds(2, 208, 20, 20), GuiIcon.APPEARANCE_OUTER_LAYER_HEAD_ON,
                "nclskins.editor.outer_head_on");
        assertCycleButton(enabled, "body", new Bounds(2, 230, 20, 20), GuiIcon.APPEARANCE_OUTER_LAYER_BODY_ALL_ON,
                "nclskins.editor.outer_body_all_on");
        assertCycleButton(enabled, "legs", new Bounds(2, 252, 20, 20), GuiIcon.APPEARANCE_OUTER_LAYER_LEGS_ALL_ON,
                "nclskins.editor.outer_legs_all_on");
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
        assertCycleButton(changedView, "head", new Bounds(2, 208, 20, 20), GuiIcon.APPEARANCE_OUTER_LAYER_HEAD_OFF,
                "nclskins.editor.outer_head_off");
        assertCycleButton(changedView, "body", new Bounds(2, 230, 20, 20), GuiIcon.APPEARANCE_OUTER_LAYER_BODY_LEFT_ARM_OFF,
                "nclskins.editor.outer_body_and_right_arm");
        assertCycleButton(changedView, "legs", new Bounds(2, 252, 20, 20), GuiIcon.APPEARANCE_OUTER_LAYER_LEGS_RIGHT_OFF,
                "nclskins.editor.outer_legs_no_right_leg");
    }

    @Test
    void selectedCapeKeepsPreviewModeWhenOwnershipCannotBeRefreshed() {
        AccountState originalAccount = TestFixtures.account(1);
        AppearancePreset original = originalAccount.presets().get(0);
        AppearancePreset staleCape = new AppearancePreset(
                original.id(),
                original.name(),
                original.skin(),
                "stale-cape",
                original.outerLayerVisibility(),
                original.createdAt(),
                original.updatedAt());
        AccountState account = new AccountState(
                AccountState.CURRENT_SCHEMA_VERSION,
                originalAccount.accountId(),
                originalAccount.skinAssets(),
                originalAccount.personalSkins(),
                List.of(staleCape),
                originalAccount.updatedAt());

        PresetEditorModel model = PresetEditorModel.open(
                account,
                Optional.of(staleCape),
                Optional.empty(),
                Optional.of(staleCape.id()),
                ENGLISH,
                480,
                PreviewRenderer.CapeMode.CAPE,
                SkinVariant.CLASSIC,
                List.of());

        assertEquals(Optional.of("stale-cape"), model.capeId());
        assertTrue(model.capeChoices().isEmpty());
        assertEquals(model, model.cycleCape(1));
        ViewSpec empty = model.withSelectedEditorTab(EditorTab.CAPE).present(854, 480);
        assertTrue(empty.widgets().stream().noneMatch(widget -> widget.id().startsWith("editor.cape_choice.")));
        assertTrue(empty.texts().stream().anyMatch(text -> text.id().equals("editor.no_capes")
                && text.message().equals(UiMessage.info("nclskins.editor.no_capes"))));
        assertTrue(model.present(854, 480).widget("editor.preview_mode").isPresent());
        assertEquals(PreviewRenderer.CapeMode.ELYTRA, model.cyclePreviewMode().preview().capeMode());
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
                capes).withSelectedEditorTab(EditorTab.CAPE);

        ViewSpec view = model.selectCape(1).present(320, 240, 0.0);
        assertEquals(new Bounds(2, 35, 20, 20),
                view.widget("editor.preview_mode").orElseThrow().bounds());
        assertEquals(Optional.of(GuiIcon.APPEARANCE_BACK_CAPE), view.widget("editor.preview_mode").orElseThrow().icon());
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
        assertEquals(40, capeViewport.y());
        assertEquals(view.panels().stream()
                .filter(panel -> panel.id().equals("footer"))
                .findFirst()
                .orElseThrow()
                .bounds()
                .y(), capeViewport.bottom());
        assertEquals(capeViewport.y(), scrollbar.y());
        assertEquals(capeViewport.height(), scrollbar.height());
    }

    @Test
    void ownedCapesUseScrollableModelCardsAndSelectionUpdatesEveryPreviewMode() {
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
                capes).withSelectedEditorTab(EditorTab.CAPE);

        ViewSpec start = model.present(320, 240, 0.0);
        List<ViewSpec.NavigationNode> capeNodes = start.navigationNodes().stream()
                .filter(node -> node.id().startsWith("editor.cape_choice."))
                .toList();
        assertEquals(6, capeNodes.size());
        assertTrue(start.widgets().stream()
                .filter(widget -> widget.id().startsWith("editor.cape_choice."))
                .count() < capeNodes.size());
        assertTrue(capeNodes.stream().allMatch(node ->
                node.pattern() == ViewSpec.NavigationPattern.GRID
                        && node.surfaceId().equals(Optional.of("editor.capes"))));
        ViewSpec.NavigationNode thirdRow = ViewNavigationPolicy.target(
                        start,
                        "editor.cape_choice.3",
                        ViewSpec.NavigationCommand.DOWN)
                .orElseThrow();
        assertEquals("editor.cape_choice.5", thirdRow.id());
        assertTrue(ViewNavigationPolicy.ensureVisibleOffset(start, thirdRow).isPresent());
        assertTrue(start.widgets().stream().noneMatch(widget ->
                widget.id().equals("editor.cape_previous") || widget.id().equals("editor.cape_next")));
        assertEquals(ViewSpec.WidgetKind.CAPE_CARD,
                start.widget("editor.cape_choice.0").orElseThrow().kind());
        assertEquals(UiMessage.info("nclskins.editor.no_cape"),
                start.widget("editor.cape_choice.0").orElseThrow().label());
        assertEquals(Optional.empty(),
                start.widget("editor.cape_choice.0").orElseThrow().hint());
        assertEquals(UiMessage.info(
                        "nclskins.editor.cape",
                        UiMessage.info("options.modelPart.cape"),
                        UiMessage.literal("Cape 0", UiMessage.Severity.INFO)),
                start.widget("editor.cape_choice.1").orElseThrow().label());
        assertEquals(Optional.empty(), start.widget("editor.cape_choice.1").orElseThrow().hint());
        assertTrue(start.panels().stream().anyMatch(panel ->
                panel.id().startsWith("editor.cape_card.")
                        && panel.style() == ViewSpec.Panel.Style.VANILLA_LIST));
        assertFalse(start.backEquipmentPreviews().isEmpty());
        assertTrue(start.backEquipmentPreviews().stream()
                .allMatch(preview -> preview.mode() == BackEquipmentPreviewRenderer.Mode.CAPE));
        ViewSpec.Widget noCapePreviewMode = start.widget("editor.preview_mode").orElseThrow();
        assertEquals(ViewSpec.WidgetKind.ICON_ONLY_BUTTON, noCapePreviewMode.kind());
        assertEquals(Optional.of(GuiIcon.APPEARANCE_BACK_CAPE), noCapePreviewMode.icon());
        assertEquals(UiMessage.info("options.modelPart.cape"), noCapePreviewMode.label());

        PresetEditorModel noCapeElytra = model.cyclePreviewMode();
        ViewSpec noCapeElytraView = noCapeElytra.present(320, 240, 0.0);
        assertTrue(noCapeElytraView.previews().get(0).capeId().isEmpty());
        assertEquals(PreviewRenderer.CapeMode.OFF,
                noCapeElytraView.previews().get(0).capeMode());
        assertEquals(Optional.of(GuiIcon.APPEARANCE_BACK_ELYTRA),
                noCapeElytraView.widget("editor.preview_mode").orElseThrow().icon());
        assertTrue(noCapeElytraView.backEquipmentPreviews().stream()
                .allMatch(preview -> preview.mode() == BackEquipmentPreviewRenderer.Mode.ELYTRA));
        ViewSpec.IconDecoration noCape = start.iconDecorations().stream()
                .filter(decoration -> decoration.icon() == GuiIcon.APPEARANCE_CAPE_NONE)
                .findFirst()
                .orElseThrow();
        assertEquals("editor.cape_choice.0", noCape.ownerWidgetId());
        Bounds noCapeCard = start.widget("editor.cape_choice.0").orElseThrow().bounds();
         Bounds noCapePreview = new Bounds(
                 noCapeCard.x() + 5,
                 noCapeCard.y() + 20,
                 noCapeCard.width() - 10,
                 noCapeCard.height() - 25);
         int noCapeIconSize = Math.min(32, Math.min(noCapePreview.width(), noCapePreview.height()));
         assertEquals(noCapeIconSize, noCape.bounds().width());
         assertEquals(noCapeIconSize, noCape.bounds().height());
         assertEquals(noCapePreview.x() + (noCapePreview.width() - noCape.bounds().width()) / 2,
                 noCape.bounds().x());
         assertEquals(noCapePreview.y() + (noCapePreview.height() - noCape.bounds().height()) / 2,
                 noCape.bounds().y());
        assertEquals(0.8F, noCape.idleOpacity());
        assertEquals(1.0F, noCape.activeOpacity());
        assertEquals(2, distinctCapeCardColumns(start));
        assertEquals(ViewSpec.Scrollbar.Orientation.VERTICAL,
                start.scrollbar().orElseThrow().orientation());
        assertTrue(model.maximumCapeScroll(320, 240) > 0);
        ViewSpec.ScrollSurface surface = start.scrollSurface("editor.capes").orElseThrow();
        assertEquals(ViewSpec.Scrollbar.Orientation.VERTICAL, surface.orientation());
        assertEquals(0.0, surface.offsetPixels());
        assertEquals(model.maximumCapeScroll(320, 240), surface.maximumPixels());

        PresetEditorModel roomy = PresetEditorModel.open(
                TestFixtures.account(0),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                ENGLISH,
                480,
                PreviewRenderer.CapeMode.CAPE,
                SkinVariant.CLASSIC,
                capes).withSelectedEditorTab(EditorTab.CAPE);
        assertEquals(5, distinctCapeCardColumns(roomy.present(854, 480, 0.0)));

        ViewSpec end = model.present(320, 240, model.maximumCapeScroll(320, 240));
        assertEquals(
                model.maximumCapeScroll(320, 240),
                end.scrollSurface("editor.capes").orElseThrow().offsetPixels());
        assertTrue(end.backEquipmentPreviews().stream()
                .anyMatch(preview -> preview.capeId().equals("cape-4")));

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
        assertTrue(selected.present(320, 240).backEquipmentPreviews().stream()
                .allMatch(equipment -> equipment.mode() == BackEquipmentPreviewRenderer.Mode.ELYTRA));
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

        ViewSpec view = model.present(320, 240);
        ViewSpec.Widget info = view.widget("editor.catalog_info").orElseThrow();
        assertEquals(ViewSpec.WidgetKind.INFO_BUTTON, info.kind());
        assertEquals(14, info.bounds().height());
        assertTrue(info.icon().isEmpty());
        assertTrue(info.hint().isPresent());
        assertFalse(view.widget("editor.model_choice.slim").orElseThrow().enabled());
        assertTrue(view.widget("editor.model_choice.classic").orElseThrow().enabled());
        assertTrue(view.texts().stream().noneMatch(text -> text.id().equals("editor.model.fixed")));
        assertTrue(view.texts().stream().noneMatch(text -> text.id().equals("editor.preview_hint")));
    }

    @Test
    void catalogDraftRetainsDedicatedPreviewIdentityBeforeSave() {
        PresetEditorModel model = PresetEditorModel.openCatalog(
                "Catalog skin",
                new CatalogOrigin("pack", "heroes", "hero"),
                Map.of(SkinVariant.CLASSIC, new byte[]{1, 2, 3}),
                SkinVariant.CLASSIC,
                Optional.empty(),
                240,
                PreviewRenderer.CapeMode.OFF);

        ViewSpec.Preview preview = model.present(320, 240).previews().get(0);

        assertTrue(preview.skin().optionalAssetId().isEmpty());
        assertTrue(preview.imageRevision().startsWith("draft:"));
        assertEquals(
                Optional.of(new ViewSpec.CatalogImage("heroes", "hero")),
                preview.catalogImage());
    }

    @Test
    void capeCardsUsePaneMetricsAcrossHeightAndGuiWidthChanges() {
        PresetEditorModel editor = PresetEditorModel.open(
                TestFixtures.account(0), Optional.empty(), Optional.empty(), Optional.empty(),
                ENGLISH, 480, PreviewRenderer.CapeMode.CAPE, SkinVariant.CLASSIC, capes(20))
                .withSelectedEditorTab(EditorTab.CAPE);
        for (int width : new int[] {320, 427, 854, 1280, 1600}) {
            for (int height : new int[] {900, 240, 186, 120}) {
                ViewSpec view = editor.present(width, height, 0.0);
                Bounds card = view.navigationNodes().stream()
                        .filter(node -> node.id().equals("editor.cape_choice.0")).findFirst().orElseThrow().bounds();
                Bounds viewport = view.scrollSurface("editor.capes").orElseThrow().viewport();
                int expectedCardWidth = switch (width) {
                    case 320 -> 62;
                    case 427 -> 89;
                    case 854 -> 74;
                    case 1280, 1600 -> 71;
                    default -> throw new AssertionError("unexpected test width");
                };
                int expectedCardHeight = switch (height) {
                    case 900 -> 109;
                    case 240 -> 98;
                    case 186, 120 -> 49;
                    default -> throw new AssertionError("unexpected test height");
                };
                assertEquals(expectedCardWidth, card.width());
                assertEquals(expectedCardHeight, card.height());
                assertEquals(viewport.x(), card.x());
                double maximum = editor.maximumCapeScroll(width, height);
                assertEquals(maximum > 0, view.scrollbar().isPresent());
                ViewSpec end = editor.present(width, height, maximum);
                Bounds last = end.navigationNodes().stream()
                        .filter(node -> node.id().equals("editor.cape_choice.20")).findFirst().orElseThrow().bounds();
                Bounds endViewport = end.scrollSurface("editor.capes").orElseThrow().viewport();
                assertTrue(last.bottom() <= endViewport.bottom());
                assertTrue(last.bottom() > endViewport.y());
            }
        }
    }

    @Test
    void verticalTabsPreserveDraftAndUseCatalogCardMetricsAcrossViewports() {
        for (int[] size : List.of(new int[] {320, 240}, new int[] {427, 240},
                new int[] {854, 480}, new int[] {1280, 240})) {
            PresetEditorModel editor = PresetEditorModel.open(
                    TestFixtures.account(0), Optional.empty(), Optional.empty(), Optional.empty(),
                    ENGLISH, size[1], PreviewRenderer.CapeMode.CAPE, SkinVariant.CLASSIC, capes(20));
            assertEquals(EditorTab.APPEARANCE, editor.selectedEditorTab());
            ViewSpec appearance = editor.present(size[0], size[1]);
            assertTrue(appearance.scrollSurface("editor.models").isPresent());
            assertTrue(appearance.widget("editor.name").isPresent());
            PresetEditorModel cape = editor.withSelectedEditorTab(EditorTab.CAPE)
                    .withName("Changed").withPng("new.png", new byte[] {1, 2, 3}).selectCape(2);
            assertEquals(EditorTab.CAPE, cape.selectedEditorTab());
            ViewSpec view = cape.present(size[0], size[1], 12.0);
            assertTrue(view.widget("editor.name").isEmpty());
            assertEquals(ViewSpec.TabOrientation.VERTICAL, view.tabGroups().get(0).orientation());
            Bounds viewport = view.scrollSurface("editor.capes").orElseThrow().viewport();
            Bounds card = view.navigationNodes().stream()
                    .filter(node -> node.id().equals("editor.cape_choice.0"))
                    .findFirst()
                    .orElseThrow()
                    .bounds();
            assertEquals(size[0] == 320 ? 62
                    : size[0] == 427 ? 89
                    : size[0] == 854 ? 74 : 71, card.width());
            assertEquals(size[1] == 480 ? 109 : 98, card.height());
            assertEquals(viewport.x(), card.x());
            assertEquals(size[0] / 2 + 16, viewport.x());
            assertEquals(size[0] - 14, viewport.right());
            assertEquals(40, viewport.y());
            assertEquals(size[1] - 33, viewport.bottom());
            PresetEditorModel restored = cape.withSelectedEditorTab(EditorTab.APPEARANCE)
                    .withSelectedEditorTab(EditorTab.CAPE);
            assertEquals(cape.name(), restored.name());
            assertEquals(cape.skin(), restored.skin());
            assertEquals(cape.capeId(), restored.capeId());
            assertArrayEquals(cape.png().orElseThrow().bytes(), restored.png().orElseThrow().bytes());
            assertEquals(cape.preview(), restored.preview());
            assertEquals(view, restored.present(size[0], size[1], 12.0));
            assertTrue(view.widgets().stream().filter(widget -> widget.id().startsWith("editor.tab."))
                    .allMatch(widget -> !cape.withBusy(UiMessage.info("nclskins.status.saving"))
                            .present(size[0], size[1]).widget(widget.id()).orElseThrow().enabled()));
        }
    }

    private static void assertCycleButton(
            ViewSpec view,
            String id,
            Bounds bounds,
            GuiIcon icon,
            String stateKey) {
        ViewSpec.Widget widget = view.widget("editor.outer_layer." + id).orElseThrow();
        assertEquals(ViewSpec.WidgetKind.ICON_ONLY_BUTTON, widget.kind());
        assertEquals(bounds, widget.bounds());
        assertEquals(Optional.of(icon), widget.icon());
        assertEquals(stateKey, widget.label().key());
        assertFalse(widget.label().arguments().isEmpty());
        assertEquals(Optional.of(widget.label()), widget.hint());
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

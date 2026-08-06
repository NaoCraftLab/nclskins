package com.naocraftlab.skins.runtime;

import com.naocraftlab.skins.core.importing.ExternalImportSource;
import com.naocraftlab.skins.core.model.PersonalSkinSource;
import com.naocraftlab.skins.core.model.SkinVariant;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ExternalImportPresenterTest {
    private final ExternalImportPresenter presenter = new ExternalImportPresenter();

    @Test
    void categoriesExposeOnlyTheirSourcesAndFolderActionsAtMinimumViewport() {
        ExternalImportModel launcher = ExternalImportModel.open(ExternalImportModel.Category.LAUNCHER)
                .withAutomaticProbes(Map.of(
                        ExternalImportSource.MINECRAFT_LAUNCHER, true,
                        ExternalImportSource.PRISM_LAUNCHER, false));
        ViewSpec chooser = presenter.present(
                launcher,
                false,
                Optional.of(UiMessage.info("nclskins.external_import.choose_source")),
                240,
                240);

        assertEquals("external_chooser", chooser.screenId());
        assertTrue(chooser.widget("external.source.minecraft_launcher").orElseThrow().enabled());
        assertFalse(chooser.widget("external.source.prism_launcher").orElseThrow().enabled());
        assertTrue(chooser.widget("external.folder.prism_launcher").orElseThrow().enabled());
        assertEquals(
                new Bounds(204, 66, 20, 20),
                chooser.widget("external.folder.prism_launcher").orElseThrow().bounds());
        assertEquals(
                ViewSpec.WidgetKind.ICON_BUTTON,
                chooser.widget("external.folder.prism_launcher").orElseThrow().kind());
        assertEquals(
                Optional.of("folder"),
                chooser.widget("external.folder.prism_launcher").orElseThrow().icon());
        assertEquals(
                2,
                chooser.widget("external.folder.prism_launcher").orElseThrow().bounds().x()
                        - chooser.widget("external.source.prism_launcher").orElseThrow().bounds().right());
        assertEquals(
                4,
                chooser.widget("external.source.prism_launcher").orElseThrow().bounds().y()
                        - chooser.widget("external.source.minecraft_launcher").orElseThrow().bounds().bottom());
        assertTrue(chooser.panels().stream().anyMatch(panel ->
                panel.style() == ViewSpec.Panel.Style.VANILLA_HEADER));
        assertTrue(chooser.panels().stream().anyMatch(panel ->
                panel.style() == ViewSpec.Panel.Style.VANILLA_FOOTER));
        assertEquals(
                new Bounds(0, 207, 240, 33),
                chooser.panels().stream()
                        .filter(panel -> panel.style() == ViewSpec.Panel.Style.VANILLA_FOOTER)
                        .findFirst()
                        .orElseThrow()
                        .bounds());
        assertEquals(213, chooser.widget("external.back").orElseThrow().bounds().y());
        assertTrue(chooser.texts().stream().noneMatch(text ->
                text.id().equals("external.source.minecraft_launcher.state")));
        assertTrue(chooser.texts().stream().noneMatch(text ->
                text.id().equals("external.mod.explanation")));
        assertTrue(chooser.widget("external.source.skin_shuffle").isEmpty());
        assertTrue(chooser.widgets().stream().allMatch(widget -> widget.bounds().right() <= 240));
    }

    @Test
    void reviewDefaultsToNewCandidatesAndCanSelectDuplicates() {
        ExternalImportModel model = ExternalImportModel.open(ExternalImportModel.Category.MOD)
                .withAutomaticProbes(Map.of(ExternalImportSource.SKIN_SHUFFLE, true))
                .withReview(new ClientOperations.ExternalImportReview(
                        ExternalImportSource.SKIN_SHUFFLE,
                        List.of(candidate("candidate-0", false), candidate("candidate-1", true)),
                        1,
                        0));
        ViewSpec review = presenter.present(model, false, Optional.empty(), 854, 480);

        assertEquals("external_review", review.screenId());
        assertTrue(review.widget("external.review.card:candidate-0")
                .orElseThrow().selectableCardSelected());
        assertFalse(review.widget("external.review.card:candidate-1")
                .orElseThrow().selectableCardSelected());
        assertEquals(2, review.previews().size());
        assertTrue(review.previews().stream().allMatch(preview -> preview.externalImage().isPresent()));
        assertTrue(review.panels().stream().anyMatch(panel ->
                panel.style() == ViewSpec.Panel.Style.VANILLA_HEADER));
        assertTrue(review.panels().stream().anyMatch(panel ->
                panel.style() == ViewSpec.Panel.Style.VANILLA_FOOTER));
        assertEquals(453, review.widget("external.review.commit").orElseThrow().bounds().y());
        assertEquals(453, review.widget("external.review.cancel").orElseThrow().bounds().y());
        assertEquals(
                UiMessage.info("nclskins.external_import.new_expanded", 1),
                review.widget("external.review.collection.new").orElseThrow().label());
        assertEquals(
                UiMessage.info("nclskins.external_import.duplicates_expanded", 1),
                review.widget("external.review.collection.duplicates").orElseThrow().label());
        assertTrue(review.texts().stream().noneMatch(text -> text.id().endsWith(".info")));
        assertTrue(review.texts().stream()
                .filter(text -> text.id().endsWith(".name"))
                .allMatch(text -> text.marqueeActivation().isPresent()));
        assertTrue(review.texts().stream().noneMatch(text -> text.id().endsWith(".selected")));
        assertTrue(review.panels().stream().noneMatch(panel ->
                panel.id().endsWith(".selection_icon")));
        assertEquals(33, review.clipRegions().get(0).bounds().y());
        assertEquals(447, review.clipRegions().get(0).bounds().bottom());
        assertEquals(37, review.widget("external.review.collection.new").orElseThrow().bounds().y());
    }

    @Test
    void emptyDuplicateCollectionIsNotRendered() {
        ExternalImportModel model = ExternalImportModel.open(ExternalImportModel.Category.MOD)
                .withAutomaticProbes(Map.of(ExternalImportSource.SKIN_SHUFFLE, true))
                .withReview(new ClientOperations.ExternalImportReview(
                        ExternalImportSource.SKIN_SHUFFLE,
                        List.of(candidate("candidate-0", false)),
                        0,
                        0));

        ViewSpec review = presenter.present(model, false, Optional.empty(), 320, 240);
        assertTrue(review.widget("external.review.collection.new").isPresent());
        assertTrue(review.widget("external.review.collection.duplicates").isEmpty());
    }

    @Test
    void emptyNewCollectionIsNotRendered() {
        ExternalImportModel model = ExternalImportModel.open(ExternalImportModel.Category.MOD)
                .withAutomaticProbes(Map.of(ExternalImportSource.SKIN_SHUFFLE, true))
                .withReview(new ClientOperations.ExternalImportReview(
                        ExternalImportSource.SKIN_SHUFFLE,
                        List.of(candidate("candidate-1", true)),
                        0,
                        0));

        ViewSpec review = presenter.present(model, false, Optional.empty(), 320, 240);
        assertTrue(review.widget("external.review.collection.new").isEmpty());
        assertTrue(review.widget("external.review.collection.duplicates").isPresent());
    }

    @Test
    void reviewErrorGetsItsOwnRowWithoutChangingChromeClipBounds() {
        ExternalImportModel model = ExternalImportModel.open(ExternalImportModel.Category.MOD)
                .withAutomaticProbes(Map.of(ExternalImportSource.SKIN_SHUFFLE, true))
                .withReview(new ClientOperations.ExternalImportReview(
                        ExternalImportSource.SKIN_SHUFFLE,
                        List.of(candidate("candidate-0", false)),
                        0,
                        0));

        ViewSpec review = presenter.present(
                model,
                false,
                Optional.of(UiMessage.error("nclskins.external_import.commit_failed")),
                320,
                240);

        assertEquals(39, review.texts().stream()
                .filter(text -> text.id().equals("external.review.status"))
                .findFirst()
                .orElseThrow()
                .bounds()
                .y());
        assertEquals(51, review.widget("external.review.collection.new").orElseThrow().bounds().y());
        assertEquals(new Bounds(0, 33, 320, 174), review.clipRegions().get(0).bounds());
    }

    @Test
    void invalidReplacementFolderKeepsThePreviousValidOverride() {
        Path working = Path.of("working-instance");
        ExternalImportModel model = ExternalImportModel.open(ExternalImportModel.Category.MOD)
                .withAutomaticProbes(Map.of(ExternalImportSource.SKIN_SHUFFLE, false))
                .withManualProbe(ExternalImportSource.SKIN_SHUFFLE, working, true)
                .withManualProbe(ExternalImportSource.SKIN_SHUFFLE, Path.of("wrong-instance"), false);

        assertTrue(model.available(ExternalImportSource.SKIN_SHUFFLE));
        assertEquals(
                working.toAbsolutePath().normalize(),
                model.selectedRoot(ExternalImportSource.SKIN_SHUFFLE).orElseThrow());
    }

    @Test
    void clearAllDisablesCommitAndSelectingOneBuildsAnExactSubset() {
        ExternalImportModel model = ExternalImportModel.open(ExternalImportModel.Category.MOD)
                .withAutomaticProbes(Map.of(ExternalImportSource.SKIN_SHUFFLE, true))
                .withReview(new ClientOperations.ExternalImportReview(
                        ExternalImportSource.SKIN_SHUFFLE,
                        List.of(candidate("candidate-0", false), candidate("candidate-1", true)),
                        0,
                        0))
                .toggleAll()
                .toggleAll()
                .toggleCandidate("candidate-1");
        assertEquals(
                List.of("candidate-1"),
                model.review().orElseThrow().selectedCandidates().stream()
                        .map(ClientOperations.ExternalImportCandidate::id)
                        .toList());

        ViewSpec cleared = presenter.present(
                model.toggleCandidate("candidate-1"), false, Optional.empty(), 854, 480);
        assertFalse(cleared.widget("external.review.commit").orElseThrow().enabled());
    }

    private static ClientOperations.ExternalImportCandidate candidate(String id, boolean duplicate) {
        return new ClientOperations.ExternalImportCandidate(
                id,
                duplicate ? "Existing" : "New",
                duplicate ? SkinVariant.SLIM : SkinVariant.CLASSIC,
                PersonalSkinSource.FILE,
                new byte[]{1, 2, 3},
                (duplicate ? "1" : "0").repeat(64),
                null,
                duplicate ? 1 : 0,
                duplicate);
    }
}

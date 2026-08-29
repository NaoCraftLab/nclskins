package com.naocraftlab.skins.runtime;

import com.naocraftlab.skins.core.compatibility.SkinExtensionEnvironment;
import com.naocraftlab.skins.core.compatibility.SkinFeature;
import com.naocraftlab.skins.core.compatibility.SkinFeatureEvidence;
import com.naocraftlab.skins.core.importing.ExternalImportProbe;
import com.naocraftlab.skins.core.importing.ExternalImportSource;
import com.naocraftlab.skins.core.model.PersonalSkinSource;
import com.naocraftlab.skins.core.model.SkinVariant;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ExternalImportPresenterTest {
    private final ExternalImportPresenter presenter = new ExternalImportPresenter();

    @Test
    void categoriesExposeOnlyTheirSourcesAndFolderActionsAtMinimumViewport() {
        ExternalImportModel launcher = ExternalImportModel.open(ExternalImportModel.Category.LAUNCHER)
                .withAutomaticProbes(Map.of(
                        ExternalImportSource.MINECRAFT_LAUNCHER, ExternalImportProbe.AVAILABLE,
                        ExternalImportSource.CURSEFORGE_APP, ExternalImportProbe.DEPENDENCY_MISSING,
                        ExternalImportSource.MODRINTH_APP, ExternalImportProbe.DEPENDENCY_MISSING,
                        ExternalImportSource.PRISM_LAUNCHER, ExternalImportProbe.UNAVAILABLE));
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
                new Bounds(204, 114, 20, 20),
                chooser.widget("external.folder.prism_launcher").orElseThrow().bounds());
        for (String source : List.of("curseforge_app", "modrinth_app")) {
            ViewSpec.Widget sourceButton = chooser.widget("external.source." + source).orElseThrow();
            ViewSpec.Widget folderButton = chooser.widget("external.folder." + source).orElseThrow();
            assertFalse(sourceButton.enabled());
            assertFalse(folderButton.enabled());
            assertEquals(
                    Optional.of(UiMessage.info(
                            "nclskins.external_import.sqlite_dependency_required")),
                    sourceButton.hint());
            assertEquals(sourceButton.hint(), folderButton.hint());
        }
        assertEquals(
                ViewSpec.WidgetKind.ICON_BUTTON,
                chooser.widget("external.folder.prism_launcher").orElseThrow().kind());
        assertEquals(
                Optional.of(GuiIcon.ACTION_SELECT_FOLDER),
                chooser.widget("external.folder.prism_launcher").orElseThrow().icon());
        assertEquals(
                2,
                chooser.widget("external.folder.prism_launcher").orElseThrow().bounds().x()
                        - chooser.widget("external.source.prism_launcher").orElseThrow().bounds().right());
        assertEquals(
                List.of(42, 66, 90, 114),
                List.of("minecraft_launcher", "curseforge_app", "modrinth_app", "prism_launcher")
                        .stream()
                        .map(source -> chooser.widget("external.source." + source)
                                .orElseThrow().bounds().y())
                        .toList());
        assertEquals(
                Optional.of(UiMessage.info(
                        "nclskins.external_import.unavailable.prism_launcher")),
                chooser.widget("external.source.prism_launcher").orElseThrow().hint());
        ViewSpec firstFrame = presenter.present(
                ExternalImportModel.open(ExternalImportModel.Category.LAUNCHER),
                true,
                Optional.of(UiMessage.info("nclskins.external_import.searching")),
                240,
                240);
        for (String source : List.of(
                "minecraft_launcher", "curseforge_app", "modrinth_app", "prism_launcher")) {
            assertEquals(
                    chooser.widget("external.source." + source).orElseThrow().bounds(),
                    firstFrame.widget("external.source." + source).orElseThrow().bounds());
        }
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
                text.id().equals("external.source.curseforge_app.state")
                        || text.id().equals("external.source.modrinth_app.state")
                        || text.id().equals("external.source.prism_launcher.state")));
        assertTrue(firstFrame.texts().stream().noneMatch(text ->
                text.id().startsWith("external.source.") && text.id().endsWith(".state")));
        assertTrue(chooser.texts().stream().noneMatch(text ->
                text.id().equals("external.mod.explanation")));
        assertTrue(chooser.widget("external.source.skin_shuffle").isEmpty());
        assertTrue(chooser.widgets().stream().allMatch(widget -> widget.bounds().right() <= 240));
    }

    @Test
    void modChooserExposesSkinShuffleSkinSwappersAndQuickSkinInStableOrder() {
        ExternalImportModel model = ExternalImportModel.open(ExternalImportModel.Category.MOD)
                .withAutomaticProbes(Map.of(
                        ExternalImportSource.SKIN_SHUFFLE, ExternalImportProbe.AVAILABLE,
                        ExternalImportSource.SKIN_SWAPPER_FAMILY, ExternalImportProbe.AVAILABLE,
                        ExternalImportSource.QUICK_SKIN, ExternalImportProbe.UNAVAILABLE));

        ViewSpec chooser = presenter.present(
                model,
                false,
                Optional.of(UiMessage.info("nclskins.external_import.choose_source")),
                320,
                240);

        assertEquals(
                List.of("skin_shuffle", "skin_swapper_family", "quick_skin"),
                model.category().sources().stream()
                        .map(ExternalImportPresenter::sourceId)
                        .map(id -> id.substring("external.source.".length()))
                        .toList());
        assertEquals(
                List.of(42, 66, 90),
                List.of("skin_shuffle", "skin_swapper_family", "quick_skin").stream()
                        .map(source -> chooser.widget("external.source." + source)
                                .orElseThrow().bounds().y())
                        .toList());
        assertTrue(chooser.texts().stream().noneMatch(text ->
                text.id().startsWith("external.source.") && text.id().endsWith(".state")));
        assertTrue(chooser.widget("external.source.skin_swapper_family").orElseThrow().enabled());
        assertFalse(chooser.widget("external.source.quick_skin").orElseThrow().enabled());
        assertEquals(
                Optional.of(UiMessage.info("nclskins.external_import.unavailable.quick_skin")),
                chooser.widget("external.source.quick_skin").orElseThrow().hint());
        assertEquals(
                Optional.of(UiMessage.info("nclskins.external_import.choose_folder")),
                chooser.widget("external.folder.quick_skin").orElseThrow().hint());
        assertEquals(
                ExternalImportSource.SKIN_SWAPPER_FAMILY,
                ExternalImportPresenter.source("external.folder.skin_swapper_family"));
        assertEquals(
                ExternalImportSource.QUICK_SKIN,
                ExternalImportPresenter.source("external.source.quick_skin"));
    }

    @Test
    void reviewDefaultsToNewCandidatesAndCanSelectDuplicates() {
        ExternalImportModel model = ExternalImportModel.open(ExternalImportModel.Category.MOD)
                .withAutomaticProbes(Map.of(
                        ExternalImportSource.SKIN_SHUFFLE, ExternalImportProbe.AVAILABLE))
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
        assertEquals(new Bounds(0, 0, 854, 33), review.panels().stream()
                .filter(panel -> panel.id().equals("header"))
                .findFirst()
                .orElseThrow()
                .bounds());
        assertTrue(review.panels().stream().anyMatch(panel ->
                panel.id().startsWith("external.review.card:")
                        && panel.style() == ViewSpec.Panel.Style.VANILLA_LIST));
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
        ViewSpec.Widget disclosure = review.widget("external.review.disclosure").orElseThrow();
        assertEquals(ViewSpec.WidgetKind.ICON_BUTTON, disclosure.kind());
        assertEquals(new Bounds(826, 6, 20, 20), disclosure.bounds());
        assertEquals(Optional.of(GuiIcon.ACTION_COLLAPSE_ALL), disclosure.icon());
        assertEquals(UiMessage.info("nclskins.collection.collapse_all"), disclosure.label());
        assertEquals(Optional.of(disclosure.label()), disclosure.hint());
        assertEquals(
                List.of("external.review.toggle_all", "external.review.disclosure"),
                review.widgets().stream().limit(2).map(ViewSpec.Widget::id).toList());
    }

    @Test
    void reviewPublishesAllExpandedLogicalCardsButMaterializesOnlyViewportCards() {
        List<ClientOperations.ExternalImportCandidate> candidates = IntStream.range(0, 30)
                .mapToObj(index -> candidate("candidate-" + index, false))
                .toList();
        ExternalImportModel model = ExternalImportModel.open(ExternalImportModel.Category.MOD)
                .withAutomaticProbes(Map.of(
                        ExternalImportSource.SKIN_SHUFFLE, ExternalImportProbe.AVAILABLE))
                .withReview(new ClientOperations.ExternalImportReview(
                        ExternalImportSource.SKIN_SHUFFLE, candidates, 30, 0));

        ViewSpec review = presenter.present(model, false, Optional.empty(), 320, 240);
        List<ViewSpec.NavigationNode> nodes = review.navigationNodes().stream()
                .filter(node -> node.id().startsWith("external.review.card:"))
                .toList();
        long materialized = review.widgets().stream()
                .filter(widget -> widget.id().startsWith("external.review.card:"))
                .count();

        assertEquals(30, nodes.size());
        assertTrue(materialized < nodes.size());
        assertTrue(review.previews().size() < nodes.size());
        assertTrue(nodes.stream().allMatch(node ->
                node.pattern() == ViewSpec.NavigationPattern.GRID
                        && node.surfaceId().equals(Optional.of("external.review"))));

        ViewSpec collapsed = presenter.present(
                model.toggleCollection(false), false, Optional.empty(), 320, 240);
        assertTrue(collapsed.navigationNodes().stream().noneMatch(node ->
                node.id().startsWith("external.review.card:")));
    }

    @Test
    void reviewContentKeepsACompactHeaderGapAndTheExistingFooterGap() {
        List<ClientOperations.ExternalImportCandidate> candidates = IntStream.range(0, 10)
                .mapToObj(index -> candidate("candidate-" + index, false))
                .toList();
        ExternalImportModel model = ExternalImportModel.open(ExternalImportModel.Category.MOD)
                .withAutomaticProbes(Map.of(
                        ExternalImportSource.SKIN_SHUFFLE, ExternalImportProbe.AVAILABLE))
                .withReview(new ClientOperations.ExternalImportReview(
                        ExternalImportSource.SKIN_SHUFFLE, candidates, 10, 0));

        ViewSpec initial = presenter.present(model, false, Optional.empty(), 320, 240);
        Bounds viewport = new Bounds(0, 33, 320, 174);
        assertEquals(viewport, initial.clipRegions().get(0).bounds());
        assertEquals(viewport.y() + 4, initial.widget(
                "external.review.collection.new").orElseThrow().bounds().y());

        ViewSpec scrolled = presenter.present(
                model.withReviewScroll(12), false, Optional.empty(), 320, 240);
        assertEquals(viewport, scrolled.clipRegions().get(0).bounds());
        assertEquals(viewport.y() + 4 - 12, scrolled.widget(
                "external.review.collection.new").orElseThrow().bounds().y());

        ViewSpec maximumScroll = presenter.present(
                model.withReviewScroll(Integer.MAX_VALUE), false, Optional.empty(), 320, 240);
        Bounds lastCard = maximumScroll.widget(
                "external.review.card:candidate-9").orElseThrow().bounds();
        assertEquals(14, viewport.bottom() - lastCard.bottom());
    }

    @Test
    void shallowWideReviewKeepsTheCollectionGridLeftAligned() {
        ExternalImportModel model = ExternalImportModel.open(ExternalImportModel.Category.MOD)
                .withAutomaticProbes(Map.of(
                        ExternalImportSource.SKIN_SHUFFLE, ExternalImportProbe.AVAILABLE))
                .withReview(new ClientOperations.ExternalImportReview(
                        ExternalImportSource.SKIN_SHUFFLE,
                        List.of(candidate("candidate-0", false)),
                        1,
                        0));

        ViewSpec review = presenter.present(model, false, Optional.empty(), 1600, 120);

        assertEquals(16, review.widget(
                "external.review.card:candidate-0").orElseThrow().bounds().x());
    }

    @Test
    void emptyDuplicateCollectionIsNotRendered() {
        ExternalImportModel model = ExternalImportModel.open(ExternalImportModel.Category.MOD)
                .withAutomaticProbes(Map.of(
                        ExternalImportSource.SKIN_SHUFFLE, ExternalImportProbe.AVAILABLE))
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
                .withAutomaticProbes(Map.of(
                        ExternalImportSource.SKIN_SHUFFLE, ExternalImportProbe.AVAILABLE))
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
                .withAutomaticProbes(Map.of(
                        ExternalImportSource.SKIN_SHUFFLE, ExternalImportProbe.AVAILABLE))
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

        assertEquals(35, review.texts().stream()
                .filter(text -> text.id().equals("external.review.status"))
                .findFirst()
                .orElseThrow()
                .bounds()
                .y());
        assertEquals(37, review.widget("external.review.collection.new").orElseThrow().bounds().y());
        assertEquals(new Bounds(0, 33, 320, 174), review.clipRegions().get(0).bounds());
    }

    @Test
    void reviewBulkDisclosurePreservesSelectionAndClampsScrollForTheChangedLayout() {
        List<ClientOperations.ExternalImportCandidate> candidates = IntStream.range(0, 30)
                .mapToObj(index -> candidate("candidate-" + index, index >= 20))
                .toList();
        ExternalImportModel model = ExternalImportModel.open(ExternalImportModel.Category.MOD)
                .withAutomaticProbes(Map.of(
                        ExternalImportSource.SKIN_SHUFFLE, ExternalImportProbe.AVAILABLE))
                .withReview(new ClientOperations.ExternalImportReview(
                        ExternalImportSource.SKIN_SHUFFLE, candidates, 20, 10))
                .toggleCandidate("candidate-0")
                .toggleCandidate("candidate-20")
                .withReviewScroll(10_000);
        Set<String> selected = model.review().orElseThrow().selectedIds();

        ExternalImportModel collapsed = model.withAllCollectionsCollapsed(true);
        assertEquals(Set.of(false, true), collapsed.review().orElseThrow().collapsedCollections());
        assertEquals(selected, collapsed.review().orElseThrow().selectedIds());
        int normalized = presenter.normalizedReviewScrollOffset(collapsed, 320, 240, 10_000);
        assertEquals(0, normalized);

        ExternalImportModel expanded = collapsed.withAllCollectionsCollapsed(false);
        assertTrue(expanded.review().orElseThrow().collapsedCollections().isEmpty());
        assertEquals(selected, expanded.review().orElseThrow().selectedIds());
    }

    @Test
    void invalidReplacementFolderKeepsThePreviousValidOverride() {
        Path working = Path.of("working-instance");
        ExternalImportModel model = ExternalImportModel.open(ExternalImportModel.Category.MOD)
                .withAutomaticProbes(Map.of(
                        ExternalImportSource.SKIN_SHUFFLE, ExternalImportProbe.UNAVAILABLE))
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
                .withAutomaticProbes(Map.of(
                        ExternalImportSource.SKIN_SHUFFLE, ExternalImportProbe.AVAILABLE))
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

    @Test
    void reviewCardShowsIntrinsicCompatibilityMarkerOverPreview() {
        ClientOperations.ExternalImportCandidate expressive = new ClientOperations.ExternalImportCandidate(
                "candidate-0",
                "Expressive",
                SkinVariant.CLASSIC,
                PersonalSkinSource.FILE,
                new byte[]{1, 2, 3},
                "0".repeat(64),
                null,
                0,
                false,
                new SkinFeatureEvidence(List.of(SkinFeature.FRESH_MOVES), List.of()));
        ExternalImportModel model = ExternalImportModel.open(ExternalImportModel.Category.MOD)
                .withReview(new ClientOperations.ExternalImportReview(
                        ExternalImportSource.SKIN_SHUFFLE, List.of(expressive), 0, 0));

        ViewSpec view = presenter.present(
                model,
                false,
                Optional.empty(),
                854,
                480,
                SkinExtensionEnvironment.unknown(7));

        String indicatorId = "external.review.card:candidate-0.compatibility";
        ViewSpec.Widget indicator = view.widget(indicatorId).orElseThrow();
        assertEquals(Optional.of(indicator.label()), indicator.hint());
        ViewSpec.Widget card = view.widget("external.review.card:candidate-0").orElseThrow();
        assertEquals(card.bounds().x() + 2, indicator.bounds().x());
        assertEquals(card.bounds().bottom() - 2, indicator.bounds().bottom());
        assertEquals(Optional.of(GuiIcon.STATUS_COMPATIBILITY_EXTENDED), indicator.icon());
        assertEquals(20, indicator.bounds().width());
        assertEquals(20, indicator.bounds().height());
        assertTrue(view.iconDecorations().stream().noneMatch(icon ->
                icon.ownerWidgetId().equals(indicatorId)));
    }

    @Test
    void reviewNavigationReachesCollapsedDuplicateHeaderBelowFreshCards() {
        List<ClientOperations.ExternalImportCandidate> candidates = new java.util.ArrayList<>(
                IntStream.range(0, 30)
                        .mapToObj(index -> candidate("fresh-" + index, false))
                        .toList());
        candidates.add(candidate("duplicate", true));
        ExternalImportModel model = ExternalImportModel.open(ExternalImportModel.Category.MOD)
                .withReview(new ClientOperations.ExternalImportReview(
                        ExternalImportSource.SKIN_SHUFFLE, candidates, 30, 1))
                .toggleCollection(true);

        ViewSpec view = presenter.present(model, false, Optional.empty(), 320, 240);
        ViewSpec.NavigationNode lastFresh = view.navigationNodes().stream()
                .filter(node -> node.id().startsWith("external.review.card:fresh-"))
                .max(java.util.Comparator.comparingInt(node -> node.bounds().y()))
                .orElseThrow();
        ViewSpec.NavigationNode duplicateHeader = view.navigationNode(
                "external.review.collection.duplicates").orElseThrow();

        assertTrue(view.widget(duplicateHeader.id()).isEmpty());
        assertEquals(
                duplicateHeader.id(),
                ViewNavigationPolicy.target(view, lastFresh.id(), ViewSpec.NavigationCommand.DOWN)
                        .orElseThrow().id());
        assertTrue(ViewNavigationPolicy.ensureVisibleOffset(view, duplicateHeader).isPresent());
        assertEquals(Optional.of(duplicateHeader.id()), duplicateHeader.activationActionId());
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

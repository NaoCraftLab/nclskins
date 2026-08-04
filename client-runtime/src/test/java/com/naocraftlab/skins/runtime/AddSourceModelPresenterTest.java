package com.naocraftlab.skins.runtime;

import com.naocraftlab.skins.client.CatalogCollectionOrder;
import com.naocraftlab.skins.client.CatalogText;
import com.naocraftlab.skins.client.MinecraftSkinCatalog;
import com.naocraftlab.skins.client.PersonalSkinCatalog;
import com.naocraftlab.skins.client.PreviewRenderer;
import com.naocraftlab.skins.client.SkinCatalogSource;
import com.naocraftlab.skins.client.SkinModel;
import com.naocraftlab.skins.core.model.AccountUiPreferences;
import com.naocraftlab.skins.core.model.AddSourceTab;
import com.naocraftlab.skins.core.model.SkinVariant;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class AddSourceModelPresenterTest {
    private final AddSourcePresenter presenter = new AddSourcePresenter();

    @Test
    void importTabShowsFilePlayerAndUrlWithoutStartingNetworkFromTextInput() {
        AddSourceModel model = AddSourceModel.open(
                        AccountUiPreferences.defaults(TestFixtures.ACCOUNT_ID), List.of())
                .withSelectedTab(AddSourceTab.FILE)
                .withPlayerInput("jeb_")
                .withUrlInput("https://textures.minecraft.net/texture/example");

        ViewSpec view = presenter.present(model, false, 320, 240);

        assertEquals(
                new Bounds(0, 0, 320, 24),
                view.panels().stream()
                        .filter(panel -> panel.id().equals("add.header"))
                        .findFirst()
                        .orElseThrow()
                        .bounds());
        assertEquals(new Bounds(0, 0, 320, 24), view.tabGroups().get(0).bounds());
        assertEquals(List.of("add.tab.catalog", "add.tab.file"),
                view.tabGroups().get(0).tabs().stream().map(ViewSpec.Tab::id).toList());
        assertTrue(view.widget("add.file.choose").orElseThrow().enabled());
        ViewSpec.Widget playerInput = view.widget("add.player.input").orElseThrow();
        assertEquals("jeb_", playerInput.value().orElseThrow());
        assertTrue(playerInput.selectAllOnPrimaryClick());
        assertEquals(Optional.of("add.player.load"), playerInput.submitActionId());
        ViewSpec.Widget urlInput = view.widget("add.url.input").orElseThrow();
        assertTrue(urlInput.selectAllOnPrimaryClick());
        assertEquals(Optional.of("add.url.load"), urlInput.submitActionId());
        assertTrue(view.widget("add.player.load").orElseThrow().enabled());
        assertTrue(view.widget("add.url.load").orElseThrow().enabled());
        assertTrue(view.texts().stream().anyMatch(text -> text.id().equals("add.url.privacy")));
    }

    @Test
    void searchIsCaseInsensitiveSubstringAndFiltersOnlyAvailableModels() {
        SkinCatalogSource.CollectionDescriptor collection = collection(
                "minecraft",
                skin("steve", "Steve", SkinModel.CLASSIC, SkinModel.SLIM),
                skin("sunny", "Sunny Day", SkinModel.SLIM),
                skin("alex", "Alex", SkinModel.CLASSIC));
        AddSourceModel opened = openCatalog(collection);

        assertEquals(List.of("Steve", "Sunny Day", "Alex"), names(opened.visibleSkins(collection)));
        AddSourceModel searched = opened.withQuery("UnNy");
        assertEquals(List.of("Sunny Day"), names(searched.visibleSkins(collection)));
        assertEquals(0, searched.scrollOffset());

        AddSourceModel classic = searched.cycleFilter();
        assertTrue(classic.visibleSkins(collection).isEmpty());
        AddSourceModel slim = classic.cycleFilter();
        assertEquals(List.of("Sunny Day"), names(slim.visibleSkins(collection)));
        assertEquals(SkinVariant.SLIM, slim.selectedVariant(slim.visibleSkins(collection).get(0)));

        ViewSpec noResults = presenter.present(opened.withQuery("missing"), false, 320, 240);
        ViewSpec.Widget catalogSearch = noResults.widget("add.catalog.search").orElseThrow();
        assertTrue(catalogSearch.selectAllOnPrimaryClick());
        assertEquals(Optional.empty(), catalogSearch.submitActionId());
        assertTrue(noResults.texts().stream().anyMatch(text -> text.id().equals("add.catalog.empty")));
    }

    @Test
    void queryHidesCollectionsWithoutMatchingSkinsAndRestoresThemWhenCleared() {
        SkinCatalogSource.CollectionDescriptor forest = collection(
                "forest", skin("red_fox", "Red Fox", SkinModel.CLASSIC));
        SkinCatalogSource.CollectionDescriptor ocean = collection(
                "ocean", skin("blue_whale", "Blue Whale", SkinModel.CLASSIC));
        AddSourceModel opened = openCatalog(List.of(forest, ocean));

        AddSourceModel searched = opened.withQuery("red fox");
        ViewSpec searchedView = presenter.present(searched, false, 320, 240);
        assertEquals(
                List.of("forest"),
                searched.visibleCollections().stream()
                        .map(SkinCatalogSource.CollectionDescriptor::id)
                        .toList());
        assertTrue(searchedView.widget("add.catalog.collection:forest").isPresent());
        assertTrue(searchedView.widget("add.catalog.skin:forest:red_fox").isPresent());
        assertTrue(searchedView.widget("add.catalog.collection:ocean").isEmpty());
        assertTrue(searchedView.widget("add.catalog.skin:ocean:blue_whale").isEmpty());

        ViewSpec collapsedMatch = presenter.present(
                searched.withCollectionCollapsed("forest", true), false, 320, 240);
        assertTrue(collapsedMatch.widget("add.catalog.collection:forest").isPresent(),
                "a matching collapsed group keeps its disclosure header");
        assertTrue(collapsedMatch.widget("add.catalog.collection:ocean").isEmpty());

        AddSourceModel restoredModel = searched.withQuery("");
        ViewSpec restored = presenter.present(restoredModel, false, 854, 480);
        assertEquals(
                List.of("forest", "ocean"),
                restoredModel.visibleCollections().stream()
                        .map(SkinCatalogSource.CollectionDescriptor::id)
                        .toList());
        assertTrue(restored.widget("add.catalog.collection:forest").isPresent());
        assertTrue(restored.widget("add.catalog.collection:ocean").isPresent());
    }

    @Test
    void explicitVariantFilterHidesEmptyCollectionsAndReflowsThe262Contract() {
        SkinCatalogSource.CollectionDescriptor classic = new SkinCatalogSource.CollectionDescriptor(
                "classic_only",
                "Classic only",
                Optional.of("Classic collection description"),
                Optional.of("Classic collection authors"),
                List.of(new SkinCatalogSource.SkinDescriptor(
                        "classic_skin",
                        "Classic skin",
                        Optional.of("Classic skin description"),
                        Optional.of("Classic skin authors"),
                        List.of(SkinModel.CLASSIC))));
        SkinCatalogSource.CollectionDescriptor slim = new SkinCatalogSource.CollectionDescriptor(
                "slim_only",
                "Slim only",
                Optional.of("Slim collection description"),
                Optional.of("Slim collection authors"),
                List.of(new SkinCatalogSource.SkinDescriptor(
                        "slim_skin",
                        "Slim skin",
                        Optional.of("Slim skin description"),
                        Optional.of("Slim skin authors"),
                        List.of(SkinModel.SLIM))));

        AddSourceModel classicFilter = openCatalog(List.of(classic, slim)).cycleFilter();
        ViewSpec classicView = presenter.present(classicFilter, false, 320, 240);

        assertEquals(
                List.of("classic_only"),
                classicFilter.visibleCollections().stream()
                        .map(SkinCatalogSource.CollectionDescriptor::id)
                        .toList());
        assertTrue(classicView.widget("add.catalog.collection:classic_only").isPresent());
        assertTrue(classicView.widget("add.catalog.collection_info:classic_only").isPresent());
        assertTrue(classicView.widget("add.catalog.skin:classic_only:classic_skin").isPresent());
        assertTrue(classicView.widget("add.catalog.collection:slim_only").isEmpty());
        assertTrue(classicView.widget("add.catalog.collection_info:slim_only").isEmpty());
        assertTrue(classicView.widget("add.catalog.skin:slim_only:slim_skin").isEmpty());
        assertTrue(classicView.widget("add.catalog.skin_info:slim_only:slim_skin").isEmpty());

        AddSourceModel slimFilter = classicFilter.cycleFilter();
        ViewSpec slimView = presenter.present(slimFilter, false, 320, 240);
        assertEquals(
                List.of("slim_only"),
                slimFilter.visibleCollections().stream()
                        .map(SkinCatalogSource.CollectionDescriptor::id)
                        .toList());
        assertTrue(slimView.widget("add.catalog.collection:classic_only").isEmpty());
        assertTrue(slimView.widget("add.catalog.collection_info:classic_only").isEmpty());
        assertTrue(slimView.widget("add.catalog.collection:slim_only").isPresent());
        assertTrue(slimView.widget("add.catalog.collection_info:slim_only").isPresent());

        AddSourceModel noClassic = openCatalog(slim).cycleFilter();
        ViewSpec emptyView = presenter.present(noClassic, false, 320, 240);
        assertTrue(noClassic.visibleCollections().isEmpty());
        assertTrue(emptyView.widget("add.catalog.collection:slim_only").isEmpty());
        assertTrue(emptyView.widget("add.catalog.collection_info:slim_only").isEmpty());
        assertTrue(emptyView.texts().stream().anyMatch(text -> text.id().equals("add.catalog.empty")));
        assertEquals(0, presenter.maximumScroll(noClassic, 320, 240));
        assertTrue(emptyView.scrollbar().isEmpty());

        SkinCatalogSource.CollectionDescriptor manyClassic = collection(
                "many_classic",
                IntStream.range(0, 16)
                        .mapToObj(index -> skin(
                                "classic-" + index,
                                "Classic " + index,
                                SkinModel.CLASSIC))
                        .toArray(SkinCatalogSource.SkinDescriptor[]::new));
        SkinCatalogSource.CollectionDescriptor manySlim = collection(
                "many_slim",
                IntStream.range(0, 16)
                        .mapToObj(index -> skin(
                                "slim-" + index,
                                "Slim " + index,
                                SkinModel.SLIM))
                        .toArray(SkinCatalogSource.SkinDescriptor[]::new));
        AddSourceModel mixedFilter = openCatalog(List.of(manyClassic, manySlim)).cycleFilter();
        AddSourceModel matchingOnly = openCatalog(manyClassic).cycleFilter();

        assertEquals(
                presenter.maximumScroll(matchingOnly, 320, 240),
                presenter.maximumScroll(mixedFilter, 320, 240),
                "hidden collections must not reserve header, metadata or scroll space");
        assertEquals(
                presenter.present(matchingOnly, false, 320, 240).scrollbar(),
                presenter.present(mixedFilter, false, 320, 240).scrollbar());
    }

    @Test
    void allPrefersClassicAndFallsBackToSlimWhenThatIsTheOnlyAvailableModel() {
        SkinCatalogSource.SkinDescriptor both = skin("both", "Both", SkinModel.CLASSIC, SkinModel.SLIM);
        SkinCatalogSource.SkinDescriptor slimOnly = skin("slim", "Slim only", SkinModel.SLIM);
        SkinCatalogSource.CollectionDescriptor collection = collection("minecraft", both, slimOnly);
        AddSourceModel model = openCatalog(collection);

        assertEquals(SkinVariant.CLASSIC, model.selectedVariant(both));
        assertEquals(SkinVariant.SLIM, model.selectedVariant(slimOnly));
        assertEquals(SkinVariant.CLASSIC, model.cycleFilter().selectedVariant(both));
        assertEquals(SkinVariant.SLIM, model.cycleFilter().cycleFilter().selectedVariant(slimOnly));
    }

    @Test
    void nativeTabsFocusCatalogSearchAndRememberCollapsedCollectionsInModel() {
        SkinCatalogSource.CollectionDescriptor collection = collection(
                "minecraft", skin("steve", "Steve", SkinModel.CLASSIC, SkinModel.SLIM));
        AddSourceModel model = AddSourceModel.open(
                new AccountUiPreferences(
                        AccountUiPreferences.CURRENT_SCHEMA_VERSION,
                        TestFixtures.ACCOUNT_ID,
                        AddSourceTab.FILE,
                        Set.of()),
                List.of(collection));

        AddSourceModel catalog = model.withSelectedTab(AddSourceTab.CATALOG);
        ViewSpec view = presenter.present(catalog, false, 320, 240);

        assertEquals("add_source", view.screenId());
        assertEquals(1, view.tabGroups().size());
        assertEquals(
                List.of("add.tab.catalog", "add.tab.file"),
                view.tabGroups().get(0).tabs().stream().map(ViewSpec.Tab::id).toList());
        assertTrue(view.tabGroups().get(0).tabs().get(0).selected());
        assertEquals("add.catalog.search", view.focusRequest().orElseThrow().widgetId());
        assertEquals(1L, view.focusRequest().orElseThrow().token());

        AddSourceModel collapsed = catalog.withCollectionCollapsed("minecraft", true);
        assertTrue(collapsed.collectionCollapsed("minecraft"));
        assertTrue(collapsed.collapsedCollectionIds().contains("minecraft"));
        ViewSpec collapsedView = presenter.present(collapsed, false, 320, 240);
        assertTrue(collapsedView.previews().isEmpty());
        assertTrue(
                collapsedView.texts().stream().noneMatch(text -> text.id().equals("add.catalog.empty")),
                "collapsing a non-empty collection must not claim that search has no matches");
        assertEquals(
                ViewSpec.WidgetKind.COLLECTION_HEADER,
                collapsedView.widget("add.catalog.collection:minecraft").orElseThrow().kind());
    }

    @Test
    void catalogCardsMatchCompactGalleryStructureAcrossSupportedWidths() {
        SkinCatalogSource.CollectionDescriptor collection = collection(
                "minecraft",
                IntStream.range(0, 9)
                        .mapToObj(index -> skin(
                                "skin-" + index,
                                "Skin " + index,
                                SkinModel.CLASSIC,
                                SkinModel.SLIM))
                        .toArray(SkinCatalogSource.SkinDescriptor[]::new));
        AddSourceModel model = openCatalog(collection);

        ViewSpec narrow = presenter.present(model, false, 320, 240);
        assertCompactCatalogLayout(
                narrow,
                4,
                new Bounds(16, 58, 290, 16),
                new Bounds(16, 78, 68, 117),
                new Bounds(20, 85, 60, 10),
                new Bounds(21, 98, 58, 92));


        ViewSpec scaledDefault = presenter.present(model, false, 427, 240);
        assertCompactCatalogLayout(
                scaledDefault,
                5,
                new Bounds(16, 58, 397, 16),
                new Bounds(17, 78, 74, 117),
                new Bounds(21, 85, 66, 10),
                new Bounds(22, 98, 64, 92));

        ViewSpec canonical = presenter.present(model, false, 854, 480);
        assertCompactCatalogLayout(
                canonical,
                9,
                new Bounds(16, 58, 824, 16),
                new Bounds(17, 78, 86, 132),
                new Bounds(21, 85, 78, 10),
                new Bounds(22, 98, 76, 107));
        assertEquals(
                9,
                canonical.previews().size(),
                "the complete Mojang collection fits one row at 854 px");

        ViewSpec wide = presenter.present(model, false, 1600, 720);
        assertCompactCatalogLayout(
                wide,
                9,
                new Bounds(16, 58, 1570, 16),
                new Bounds(345, 78, 96, 132),
                new Bounds(349, 85, 88, 10),
                new Bounds(350, 98, 86, 107));
    }

    @Test
    void collectionHeaderIsThinTransparentIntentWithoutAButtonLikeFakeLine() {
        SkinCatalogSource.CollectionDescriptor collection = collection(
                "minecraft", skin("steve", "Steve", SkinModel.CLASSIC, SkinModel.SLIM));
        AddSourceModel expanded = openCatalog(collection);

        ViewSpec.Widget expandedHeader = presenter.present(expanded, false, 854, 480)
                .widget("add.catalog.collection:minecraft")
                .orElseThrow();
        assertEquals(ViewSpec.WidgetKind.COLLECTION_HEADER, expandedHeader.kind());
        assertEquals(new Bounds(16, 58, 824, 16), expandedHeader.bounds());
        assertEquals("▼ Minecraft", expandedHeader.label().key());
        assertTrue(expandedHeader.label().literal());
        assertFalse(expandedHeader.label().key().contains("─"));

        ViewSpec.Widget collapsedHeader = presenter
                .present(expanded.withCollectionCollapsed("minecraft", true), false, 854, 480)
                .widget("add.catalog.collection:minecraft")
                .orElseThrow();
        assertEquals("▶ Minecraft", collapsedHeader.label().key());
        assertEquals(new Bounds(16, 58, 824, 16), collapsedHeader.bounds());
    }

    @Test
    void catalogMetadataUsesFramelessInfoButtonsReservedByCollectionHeaders() {
        SkinCatalogSource.SkinDescriptor skin = new SkinCatalogSource.SkinDescriptor(
                "steve",
                "Steve",
                Optional.of("Skin description"),
                Optional.of("Skin authors"),
                List.of(SkinModel.CLASSIC));
        SkinCatalogSource.CollectionDescriptor collection = new SkinCatalogSource.CollectionDescriptor(
                "minecraft",
                "Minecraft",
                Optional.of("Collection description"),
                Optional.of("Collection authors"),
                List.of(skin));
        ViewSpec view = presenter.present(openCatalog(collection), false, 854, 480);
        Bounds viewport = view.clipRegions().stream()
                .filter(region -> region.id().equals("add.catalog.viewport"))
                .findFirst()
                .orElseThrow()
                .bounds();

        ViewSpec.Widget header = view.widget("add.catalog.collection:minecraft").orElseThrow();
        assertTrue(header.collectionHeaderHasTrailingInfo());
        assertInfoButton(view, "add.catalog.collection_info:minecraft", viewport);
        assertInfoButton(view, "add.catalog.skin_info:minecraft:steve", viewport);
        Bounds skinInfo = view.widget("add.catalog.skin_info:minecraft:steve")
                .orElseThrow()
                .bounds();
        Bounds skinName = view.texts().stream()
                .filter(text -> text.id().equals("add.catalog.skin:minecraft:steve.name"))
                .findFirst()
                .orElseThrow()
                .bounds();
        assertEquals(3, skinName.x() - skinInfo.right());
    }

    @Test
    void catalogCardIsADedicatedVisibleNativeActionNamedForNarration() {
        SkinCatalogSource.CollectionDescriptor collection = collection(
                "minecraft", skin("steve", "Steve", SkinModel.CLASSIC, SkinModel.SLIM));
        ViewSpec view = presenter.present(openCatalog(collection), false, 320, 240);

        ViewSpec.Widget card = view.widget("add.catalog.skin:minecraft:steve").orElseThrow();
        assertEquals(ViewSpec.WidgetKind.CATALOG_CARD, card.kind());
        assertTrue(card.visible(), "native hosts need the card in focus traversal");
        assertTrue(card.enabled());
        assertTrue(card.label().literal());
        assertEquals("Steve", card.label().key());
        assertEquals(
                view.panels().stream()
                        .filter(panel -> panel.id().equals(card.id()))
                        .findFirst()
                        .orElseThrow()
                        .bounds(),
                card.bounds());
    }

    @Test
    void catalogUsesCompactSevenPixelGapsBelowTabsAndSearchControls() {
        SkinCatalogSource.CollectionDescriptor collection = collection(
                "minecraft", skin("steve", "Steve", SkinModel.CLASSIC, SkinModel.SLIM));
        ViewSpec view = presenter.present(openCatalog(collection), false, 854, 480);

        Bounds tabs = view.tabGroups().get(0).bounds();
        Bounds search = view.widget("add.catalog.search").orElseThrow().bounds();
        Bounds filter = view.widget("add.catalog.filter").orElseThrow().bounds();
        Bounds header = view.widget("add.catalog.collection:minecraft").orElseThrow().bounds();

        assertEquals(7, search.y() - tabs.bottom());
        assertEquals(search.y(), filter.y());
        assertEquals(7, header.y() - search.bottom());
    }

    @Test
    void catalogKeepsStaticVisibleOnlyPreviewsAndVerticalScroll() {
        SkinCatalogSource.CollectionDescriptor collection = collection(
                "minecraft",
                IntStream.range(0, 20)
                        .mapToObj(index -> skin(
                                "skin-" + index,
                                "Skin " + index,
                                SkinModel.CLASSIC,
                                SkinModel.SLIM))
                        .toArray(SkinCatalogSource.SkinDescriptor[]::new));
        AddSourceModel model = openCatalog(collection);

        ViewSpec narrow = presenter.present(model, false, 320, 240);
        assertEquals(4, distinctPreviewColumns(narrow));
        assertFalse(narrow.previews().isEmpty());

        ViewSpec canonical = presenter.present(model, false, 854, 480);
        assertEquals(9, distinctPreviewColumns(canonical));
        assertTrue(canonical.previews().size() <= distinctPreviewColumns(canonical) * 3);
        assertTrue(canonical.previews().stream().allMatch(preview -> preview.catalogImage().isPresent()));
        assertTrue(canonical.previews().stream().allMatch(preview -> preview.yawDegrees() == -20.0F));
        assertTrue(canonical.previews().stream().allMatch(preview -> preview.pitchDegrees() == 0.0F));
        assertTrue(canonical.previews().stream().allMatch(preview -> preview.capeMode() == PreviewRenderer.CapeMode.OFF));
        Bounds clip = canonical.clipRegions().stream()
                .filter(region -> region.id().equals("add.catalog.viewport"))
                .findFirst()
                .orElseThrow()
                .bounds();
        assertTrue(canonical.previews().stream().allMatch(preview -> intersects(preview.bounds(), clip)));

        ViewSpec.Scrollbar scrollbar = canonical.scrollbar().orElseThrow();
        assertEquals(ViewSpec.Scrollbar.Orientation.VERTICAL, scrollbar.orientation());
        assertTrue(scrollbar.maximum() > 0);
        ViewSpec.ScrollSurface scrollSurface = canonical.scrollSurface("add.catalog").orElseThrow();
        assertEquals(clip, scrollSurface.viewport());
        assertEquals(ViewSpec.Scrollbar.Orientation.VERTICAL, scrollSurface.orientation());
        assertEquals(0.0, scrollSurface.offsetPixels());
        assertEquals(scrollbar.maximum(), scrollSurface.maximumPixels());
        ViewSpec.Text firstName = canonical.texts().stream()
                .filter(text -> text.id().endsWith(".name"))
                .findFirst()
                .orElseThrow();
        assertTrue(firstName.marqueeActivation().orElseThrow().focusWidgetIds().stream()
                .anyMatch(id -> id.startsWith("add.catalog.skin:")));
        int nextOffset = presenter.nextScrollOffset(model, 320, 240, 1);
        assertTrue(nextOffset > 0);
        ViewSpec afterNext = presenter.present(model.withScrollOffset(nextOffset), false, 320, 240);
        assertFalse(afterNext.previews().isEmpty());
        assertTrue(afterNext.previews().get(0).bounds().y() < narrow.previews().get(0).bounds().y());

        AddSourceModel scrolled = model.withScrollOffset(scrollbar.maximum());
        ViewSpec atEnd = presenter.present(scrolled, false, 854, 480);
        assertEquals(
                scrollbar.maximum(),
                atEnd.scrollSurface("add.catalog").orElseThrow().offsetPixels());
        assertFalse(atEnd.previews().isEmpty());
        assertTrue(
                atEnd.previews().get(0).bounds().y() < canonical.previews().get(0).bounds().y(),
                "pixel scrolling must move a still-visible boundary card instead of paging it away");
    }

    @Test
    void partiallyVisibleCatalogHeadersCardsAndInfoActionsRemainClippedAndInteractive() {
        List<SkinCatalogSource.SkinDescriptor> skins = IntStream.range(0, 20)
                .mapToObj(index -> new SkinCatalogSource.SkinDescriptor(
                        "skin-" + index,
                        "Skin " + index,
                        index == 0 ? Optional.of("Skin description") : Optional.empty(),
                        Optional.empty(),
                        List.of(SkinModel.CLASSIC)))
                .toList();
        SkinCatalogSource.CollectionDescriptor collection = new SkinCatalogSource.CollectionDescriptor(
                "minecraft",
                "Minecraft",
                Optional.of("Collection description"),
                Optional.empty(),
                skins);
        AddSourceModel model = openCatalog(collection);

        ViewSpec partialHeader = presenter.present(model.withScrollOffset(2), false, 320, 240);
        Bounds viewport = partialHeader.clipRegions().stream()
                .filter(region -> region.id().equals("add.catalog.viewport"))
                .findFirst()
                .orElseThrow()
                .bounds();
        for (String id : List.of(
                "add.catalog.collection:minecraft",
                "add.catalog.collection_info:minecraft")) {
            ViewSpec.Widget widget = partialHeader.widget(id).orElseThrow();
            assertTrue(widget.bounds().y() < viewport.y());
            assertTrue(intersects(widget.bounds(), viewport));
            assertEquals(Optional.of(viewport), partialHeader.clipFor(id));
        }

        ViewSpec headerSliver = presenter.present(model.withScrollOffset(15), false, 320, 240);
        assertTrue(headerSliver.widget("add.catalog.collection:minecraft").isPresent());
        assertTrue(headerSliver.widget("add.catalog.collection_info:minecraft").isEmpty());

        ViewSpec partialCard = presenter.present(model.withScrollOffset(24), false, 320, 240);
        for (String id : List.of(
                "add.catalog.skin:minecraft:skin-0",
                "add.catalog.skin_info:minecraft:skin-0")) {
            ViewSpec.Widget widget = partialCard.widget(id).orElseThrow();
            assertTrue(widget.bounds().y() < viewport.y());
            assertTrue(intersects(widget.bounds(), viewport));
            assertEquals(Optional.of(viewport), partialCard.clipFor(id));
        }

        ViewSpec cardSliver = presenter.present(model.withScrollOffset(37), false, 320, 240);
        assertTrue(cardSliver.widget("add.catalog.skin:minecraft:skin-0").isPresent());
        assertTrue(cardSliver.widget("add.catalog.skin_info:minecraft:skin-0").isEmpty());
    }

    @Test
    void catalogReflowsWithoutOverflowAcrossVanillaGuiScaleViewports() {
        SkinCatalogSource.CollectionDescriptor collection = collection(
                "minecraft",
                IntStream.range(0, 20)
                        .mapToObj(index -> skin(
                                "skin-" + index,
                                "Skin " + index,
                                SkinModel.CLASSIC,
                                SkinModel.SLIM))
                        .toArray(SkinCatalogSource.SkinDescriptor[]::new));
        AddSourceModel model = openCatalog(collection);


        List<Bounds> scaledViewports = List.of(
                new Bounds(0, 0, 320, 240),
                new Bounds(0, 0, 427, 240),
                new Bounds(0, 0, 640, 360),
                new Bounds(0, 0, 1280, 720));

        long previousColumns = 0;
        for (Bounds viewport : scaledViewports) {
            ViewSpec initial = presenter.present(model, false, viewport.width(), viewport.height());
            assertViewFitsViewport(initial);
            assertFalse(initial.previews().isEmpty());

            long columns = distinctPreviewColumns(initial);
            assertTrue(columns >= previousColumns, "wider scaled viewports must not lose columns");
            previousColumns = columns;

            int offset = 0;
            while (true) {
                ViewSpec scrolled = presenter.present(
                        model.withScrollOffset(offset), false, viewport.width(), viewport.height());
                assertViewFitsViewport(scrolled);
                assertFalse(scrolled.previews().isEmpty(), "every pixel offset must expose clipped card content");

                int next = presenter.nextScrollOffset(
                        model.withScrollOffset(offset), viewport.width(), viewport.height(), 1);
                if (next == offset) {
                    assertEquals(
                            presenter.maximumScroll(model, viewport.width(), viewport.height()),
                            offset);
                    break;
                }
                assertTrue(next > offset, "pixel scrolling must advance monotonically");
                offset = next;
            }
        }
    }

    @Test
    void resourceCollectionsFollowMenuOrderResolveVanillaTextLazilyAndKeepMinecraftLast() {
        AtomicReference<Map<String, String>> language = new AtomicReference<>(Map.of(
                "nclskins.alpha.name", "Zulu",
                "nclskins.beta.name", "Beta translated",
                "nclskins.unknown_a.name", "Omega",
                "nclskins.unknown_b.name", "Alpha",
                "nclskins.alpha.skin.one.name", "Second",
                "nclskins.alpha.skin.two.name", "First"));
        TextResolver resolver = message -> language.get().getOrDefault(message.key(), message.key());
        var alpha = localizedCollection("alpha", "pack-a", 0, "one", "two");
        var beta = localizedCollection("beta", "pack-b", 1, "skin");
        var unknownA = localizedCollection("unknown_a", "folder-a", -1, "skin");
        var unknownB = localizedCollection("unknown_b", "folder-b", -1, "skin");
        AddSourceModel model = AddSourceModel.open(
                new AccountUiPreferences(
                        AccountUiPreferences.CURRENT_SCHEMA_VERSION,
                        TestFixtures.ACCOUNT_ID,
                        AddSourceTab.CATALOG,
                        Set.of()),
                List.of(MinecraftSkinCatalog.collections().get(0), unknownA, beta, unknownB, alpha),
                SkinVariant.CLASSIC,
                resolver);

        assertEquals(
                List.of("alpha", "beta", "unknown_b", "unknown_a", "minecraft"),
                model.collections().stream()
                        .map(SkinCatalogSource.CollectionDescriptor::id)
                        .toList());
        assertEquals(List.of("two", "one"), model.visibleSkins(alpha).stream()
                .map(SkinCatalogSource.SkinDescriptor::id)
                .toList());
        assertEquals(
                List.of("two"),
                model.withQuery("fir").visibleSkins(alpha).stream()
                        .map(SkinCatalogSource.SkinDescriptor::id)
                        .toList());

        language.set(Map.of(
                "nclskins.alpha.name", "Alpha translated",
                "nclskins.beta.name", "Beta translated",
                "nclskins.unknown_a.name", "Alpha",
                "nclskins.unknown_b.name", "Omega",
                "nclskins.alpha.skin.one.name", "First",
                "nclskins.alpha.skin.two.name", "Second"));

        assertEquals(
                List.of("alpha", "beta", "unknown_a", "unknown_b", "minecraft"),
                model.collections().stream()
                        .map(SkinCatalogSource.CollectionDescriptor::id)
                        .toList());
        assertEquals(List.of("one", "two"), model.visibleSkins(alpha).stream()
                .map(SkinCatalogSource.SkinDescriptor::id)
                .toList());
        assertEquals("Alpha translated", model.collectionName(alpha));
        assertEquals("Standard", model.collectionName(MinecraftSkinCatalog.collections().get(0)));
    }

    @Test
    void personalCollectionIsAlwaysFirstAndOnlyItsCardsExposeDeleteActions() {
        String personalHash = hash('a');
        SkinCatalogSource.CollectionDescriptor personal = personalCollection(
                skin(personalHash, "Local hero", SkinModel.CLASSIC, SkinModel.SLIM));
        SkinCatalogSource.CollectionDescriptor resource = localizedCollection(
                "alpha", "pack-a", 0, "external");
        AddSourceModel model = AddSourceModel.open(
                new AccountUiPreferences(
                        AccountUiPreferences.CURRENT_SCHEMA_VERSION,
                        TestFixtures.ACCOUNT_ID,
                        AddSourceTab.CATALOG,
                        Set.of()),
                List.of(MinecraftSkinCatalog.collections().get(0), resource, personal));

        assertEquals(
                List.of(PersonalSkinCatalog.COLLECTION_ID, "alpha", "minecraft"),
                model.collections().stream()
                        .map(SkinCatalogSource.CollectionDescriptor::id)
                        .toList());

        ViewSpec view = presenter.present(model, false, 1600, 720);
        ViewSpec.Widget delete = view.widget("add.catalog.delete:" + personalHash).orElseThrow();
        assertEquals(ViewSpec.WidgetKind.CATALOG_DELETE, delete.kind());
        assertEquals("nclskins.your_skins.delete", delete.label().key());
        assertEquals(List.of("Local hero"), delete.label().arguments());
        assertTrue(delete.visible());
        assertTrue(delete.enabled());
        assertEquals(
                List.of("add.catalog.delete:" + personalHash),
                view.widgets().stream()
                        .filter(widget -> widget.kind() == ViewSpec.WidgetKind.CATALOG_DELETE)
                        .map(ViewSpec.Widget::id)
                        .toList());
        assertTrue(view.widget("add.catalog.skin:alpha:external").isPresent());
        assertTrue(view.widget("add.catalog.delete:external").isEmpty());

        Bounds card = view.widget("add.catalog.skin:"
                        + PersonalSkinCatalog.COLLECTION_ID
                        + ":"
                        + personalHash)
                .orElseThrow()
                .bounds();
        assertTrue(card.contains(delete.bounds().x(), delete.bounds().y()));
        assertTrue(card.contains(delete.bounds().right() - 1, delete.bounds().bottom() - 1));
    }

    @Test
    void partiallyVisiblePersonalRenameOverlayUsesTheCatalogViewportClip() {
        String personalHash = hash('c');
        SkinCatalogSource.CollectionDescriptor personal = personalCollection(
                skin(personalHash, "Local hero", SkinModel.CLASSIC));
        AddSourceModel model = openCatalog(List.of(
                personal, MinecraftSkinCatalog.collections().get(0)));

        ViewSpec view = presenter.present(
                model.withScrollOffset(24),
                false,
                Optional.empty(),
                320,
                240,
                Optional.of(new AddSourcePresenter.PersonalSkinRename(
                        personalHash, "Renamed hero")));
        Bounds viewport = view.clipRegions().stream()
                .filter(region -> region.id().equals("add.catalog.viewport"))
                .findFirst()
                .orElseThrow()
                .bounds();

        for (String id : List.of(
                "add.catalog.rename.name",
                "add.catalog.rename.save",
                "add.catalog.rename.cancel")) {
            ViewSpec.Widget overlay = view.widget(id).orElseThrow();
            assertEquals(Optional.of(viewport), view.clipFor(id));
        }
        assertTrue(view.widget("add.catalog.rename.name").orElseThrow().bounds().y() < viewport.y());

        ViewSpec topActionsClipped = presenter.present(
                model.withScrollOffset(43),
                false,
                Optional.empty(),
                320,
                240,
                Optional.of(new AddSourcePresenter.PersonalSkinRename(
                        personalHash, "Renamed hero")));
        assertTrue(topActionsClipped.widget("add.catalog.skin:"
                        + PersonalSkinCatalog.COLLECTION_ID
                        + ":"
                        + personalHash)
                .isPresent());
        assertTrue(topActionsClipped.widget("add.catalog.rename:" + personalHash).isEmpty());
        assertTrue(topActionsClipped.widget("add.catalog.delete:" + personalHash).isEmpty());
        assertTrue(topActionsClipped.widget("add.catalog.rename.name").isEmpty());
        for (String id : List.of("add.catalog.rename.save", "add.catalog.rename.cancel")) {
            ViewSpec.Widget overlay = topActionsClipped.widget(id).orElseThrow();
            assertTrue(intersects(overlay.bounds(), viewport));
            assertEquals(Optional.of(viewport), topActionsClipped.clipFor(id));
        }
    }

    @Test
    void personalDeleteConfirmationPreservesTransientCatalogStateAndRestoresDeleteFocus() {
        String personalHash = hash('b');
        SkinCatalogSource.SkinDescriptor personalSkin =
                skin(personalHash, "Your hero", SkinModel.CLASSIC, SkinModel.SLIM);
        SkinCatalogSource.CollectionDescriptor personal = personalCollection(personalSkin);
        AddSourceModel unscrolled = AddSourceModel.open(
                        new AccountUiPreferences(
                                AccountUiPreferences.CURRENT_SCHEMA_VERSION,
                                TestFixtures.ACCOUNT_ID,
                                AddSourceTab.CATALOG,
                                Set.of(MinecraftSkinCatalog.COLLECTION_ID)),
                        List.of(personal, MinecraftSkinCatalog.collections().get(0)))
                .withQuery("hero")
                .cycleFilter();
        int preservedScroll = presenter.normalizedScrollOffset(unscrolled, 320, 240, 5);
        assertEquals(5, preservedScroll, "arbitrary in-range pixel offsets must not snap to rows");
        AddSourceModel catalog = unscrolled.withScrollOffset(preservedScroll);
        assertTrue(presenter.present(catalog, false, 320, 240)
                .widget("add.catalog.delete:" + personalHash)
                .isPresent());

        AddSourceModel confirmation = catalog.requestPersonalSkinDeletion(personal, personalSkin);
        ViewSpec confirmationView = presenter.present(confirmation, false, 320, 240);

        assertEquals("personal_skin_delete", confirmationView.screenId());
        assertTrue(confirmationView.tabGroups().isEmpty());
        assertEquals(
                new Bounds(0, 0, 320, 33),
                confirmationView.panels().stream()
                        .filter(panel -> panel.id().equals("personal_delete.header"))
                        .findFirst()
                        .orElseThrow()
                        .bounds());
        assertTrue(confirmationView.previews().isEmpty());
        assertTrue(confirmationView.scrollbar().isEmpty());
        assertTrue(confirmationView.widget("add.catalog.delete.confirm").orElseThrow().enabled());
        assertTrue(confirmationView.widget("add.catalog.delete.cancel").orElseThrow().enabled());
        assertEquals(
                "add.catalog.delete.cancel",
                confirmationView.focusRequest().orElseThrow().widgetId());
        assertTrue(confirmation.focusToken().orElseThrow() > catalog.focusToken().orElseThrow());
        assertEquals(
                List.of("Your hero"),
                confirmationView.texts().stream()
                        .filter(text -> text.id().equals("personal_delete.question"))
                        .findFirst()
                        .orElseThrow()
                        .message()
                        .arguments());

        ViewSpec busyView = presenter.present(confirmation, true, 320, 240);
        assertFalse(busyView.widget("add.catalog.delete.confirm").orElseThrow().enabled());
        assertFalse(busyView.widget("add.catalog.delete.cancel").orElseThrow().enabled());

        AddSourceModel cancelled = confirmation.cancelPersonalSkinDeletion();
        ViewSpec restored = presenter.present(cancelled, false, 320, 240);
        assertTrue(cancelled.personalSkinDeletion().isEmpty());
        assertEquals(catalog.query(), cancelled.query());
        assertEquals(catalog.filter(), cancelled.filter());
        assertEquals(catalog.collapsedCollectionIds(), cancelled.collapsedCollectionIds());
        assertEquals(catalog.scrollOffset(), cancelled.scrollOffset());
        assertEquals("add_source", restored.screenId());
        assertEquals(
                "add.catalog.delete:" + personalHash,
                restored.focusRequest().orElseThrow().widgetId());
        assertTrue(cancelled.focusToken().orElseThrow() > confirmation.focusToken().orElseThrow());
        assertTrue(restored.widget("add.catalog.delete:" + personalHash).isPresent());
    }

    @Test
    void confirmedPersonalDeleteFocusesNextThenPreviousAndFallsBackToAHeader() {
        String firstHash = hash('1');
        String middleHash = hash('2');
        String lastHash = hash('3');
        SkinCatalogSource.SkinDescriptor first =
                skin(firstHash, "First", SkinModel.CLASSIC);
        SkinCatalogSource.SkinDescriptor middle =
                skin(middleHash, "Middle", SkinModel.CLASSIC);
        SkinCatalogSource.SkinDescriptor last =
                skin(lastHash, "Last", SkinModel.CLASSIC);
        SkinCatalogSource.CollectionDescriptor personal = personalCollection(first, middle, last);
        SkinCatalogSource.CollectionDescriptor resource = localizedCollection(
                "alpha", "pack-a", 0, "external");
        AddSourceModel model = AddSourceModel.open(
                new AccountUiPreferences(
                        AccountUiPreferences.CURRENT_SCHEMA_VERSION,
                        TestFixtures.ACCOUNT_ID,
                        AddSourceTab.CATALOG,
                        Set.of()),
                List.of(resource, personal));

        AddSourceModel removedMiddle = model.requestPersonalSkinDeletion(personal, middle)
                .removeConfirmedPersonalSkin();
        assertEquals(
                "add.catalog.skin:" + PersonalSkinCatalog.COLLECTION_ID + ":" + lastHash,
                removedMiddle.focusWidgetId().orElseThrow(),
                "the item that shifts into the removed slot is the next focus target");

        AddSourceModel removedLast = model.requestPersonalSkinDeletion(personal, last)
                .removeConfirmedPersonalSkin();
        assertEquals(
                "add.catalog.skin:" + PersonalSkinCatalog.COLLECTION_ID + ":" + middleHash,
                removedLast.focusWidgetId().orElseThrow(),
                "the previous card is used when there is no next card");

        SkinCatalogSource.CollectionDescriptor singlePersonal = personalCollection(first);
        AddSourceModel removedOnly = AddSourceModel.open(
                        new AccountUiPreferences(
                                AccountUiPreferences.CURRENT_SCHEMA_VERSION,
                                TestFixtures.ACCOUNT_ID,
                                AddSourceTab.CATALOG,
                                Set.of()),
                        List.of(resource, singlePersonal))
                .requestPersonalSkinDeletion(singlePersonal, first)
                .removeConfirmedPersonalSkin();
        assertEquals(
                "add.catalog.collection:alpha",
                removedOnly.focusWidgetId().orElseThrow(),
                "removing the collection's final card falls back to the next collection header");
        assertEquals(0, removedOnly.scrollOffset());
        assertTrue(removedOnly.collections().stream()
                .noneMatch(collection -> collection.order().kind()
                        == CatalogCollectionOrder.Kind.PERSONAL));
    }

    @Test
    void missingCatalogTranslationUsesHumanizedFallbackOnlyAfterLanguageResolution() {
        TextResolver missing = message -> message.key();
        TextResolver translated = message -> "nclskins.event.skin.red_fox.name".equals(message.key())
                ? "Red fox localized"
                : message.key();
        CatalogText text = CatalogText.skinName("event", "red_fox");

        assertEquals("Red Fox", missing.resolve(text));
        assertEquals("Red fox localized", translated.resolve(text));
    }

    private static void assertCompactCatalogLayout(
            ViewSpec view,
            int expectedColumns,
            Bounds expectedHeader,
            Bounds expectedCard,
            Bounds expectedTitle,
            Bounds expectedPreview) {
        String prefix = "add.catalog.skin:minecraft:skin-0";
        ViewSpec.Panel card = view.panels().stream()
                .filter(panel -> panel.id().equals(prefix))
                .findFirst()
                .orElseThrow();
        ViewSpec.Text title = view.texts().stream()
                .filter(text -> text.id().equals(prefix + ".name"))
                .findFirst()
                .orElseThrow();
        ViewSpec.Preview preview = view.previews().stream()
                .filter(candidate -> candidate.id().equals(prefix + ".preview"))
                .findFirst()
                .orElseThrow();
        ViewSpec.Widget hitTarget = view.widget(prefix).orElseThrow();
        ViewSpec.Widget header = view.widget("add.catalog.collection:minecraft").orElseThrow();

        assertEquals(expectedColumns, distinctPreviewColumns(view));
        assertEquals(expectedHeader, header.bounds());
        assertEquals(expectedCard, card.bounds());
        assertEquals(expectedCard, hitTarget.bounds());
        assertEquals(ViewSpec.WidgetKind.CATALOG_CARD, hitTarget.kind());
        assertTrue(
                hitTarget.visible(),
                "the transparent native card action must participate in focus traversal");
        assertEquals(expectedTitle, title.bounds());
        assertEquals(expectedPreview, preview.bounds());
        assertTrue(title.bounds().bottom() <= preview.bounds().y(), "the title belongs above the model");
        assertEquals(1, view.texts().stream().filter(text -> text.id().startsWith(prefix)).count());
        assertEquals(1, view.widgets().stream().filter(widget -> widget.id().startsWith(prefix)).count());
    }

    private static void assertViewFitsViewport(ViewSpec view) {
        view.panels().forEach(panel -> assertFitsOrClipped(view, panel.bounds(), panel.id()));
        view.texts().forEach(text -> assertFitsOrClipped(view, text.bounds(), text.id()));
        view.widgets().forEach(widget -> assertFitsOrClipped(view, widget.bounds(), widget.id()));
        view.previews().forEach(preview -> assertFitsOrClipped(view, preview.bounds(), preview.id()));
        view.tabGroups().forEach(group -> assertFits(view, group.bounds(), group.id()));
        view.scrollbar().ifPresent(scrollbar -> {
            assertFits(view, scrollbar.track(), "scrollbar.track");
            assertFits(view, scrollbar.thumb(), "scrollbar.thumb");
        });
    }

    private static void assertFits(ViewSpec view, Bounds bounds, String id) {
        assertTrue(bounds.x() >= 0, id + " starts left of the scaled viewport");
        assertTrue(bounds.y() >= 0, id + " starts above the scaled viewport");
        assertTrue(bounds.right() <= view.width(), id + " overflows the scaled viewport width");
        assertTrue(bounds.bottom() <= view.height(), id + " overflows the scaled viewport height");
    }

    private static void assertFitsOrClipped(ViewSpec view, Bounds bounds, String id) {
        Optional<Bounds> clip = view.clipFor(id);
        if (clip.isEmpty()) {
            assertFits(view, bounds, id);
            return;
        }
        assertTrue(intersects(bounds, clip.orElseThrow()), id + " must intersect its clip viewport");
        assertFits(view, clip.orElseThrow(), id + ".clip");
    }

    private static boolean intersects(Bounds left, Bounds right) {
        return left.right() > right.x()
                && left.x() < right.right()
                && left.bottom() > right.y()
                && left.y() < right.bottom();
    }

    private static AddSourceModel openCatalog(SkinCatalogSource.CollectionDescriptor collection) {
        return openCatalog(List.of(collection));
    }

    private static AddSourceModel openCatalog(
            List<SkinCatalogSource.CollectionDescriptor> collections) {
        return AddSourceModel.open(
                new AccountUiPreferences(
                        AccountUiPreferences.CURRENT_SCHEMA_VERSION,
                        TestFixtures.ACCOUNT_ID,
                        AddSourceTab.CATALOG,
                        Set.of()),
                collections);
    }

    private static void assertInfoButton(ViewSpec view, String id, Bounds viewport) {
        ViewSpec.Widget info = view.widget(id).orElseThrow();
        assertEquals(ViewSpec.WidgetKind.INFO_BUTTON, info.kind());
        assertEquals(14, info.bounds().width());
        assertEquals(14, info.bounds().height());
        assertTrue(info.icon().isEmpty());
        assertTrue(info.hint().isPresent());
        assertEquals(Optional.of(viewport), view.clipFor(id));
    }

    private static SkinCatalogSource.CollectionDescriptor collection(
            String id, SkinCatalogSource.SkinDescriptor... skins) {
        return new SkinCatalogSource.CollectionDescriptor(
                id,
                "Minecraft",
                Optional.empty(),
                Optional.empty(),
                List.of(skins));
    }

    private static SkinCatalogSource.SkinDescriptor skin(String id, String name, SkinModel... models) {
        return new SkinCatalogSource.SkinDescriptor(
                id,
                name,
                Optional.empty(),
                Optional.empty(),
                List.of(models));
    }

    private static SkinCatalogSource.CollectionDescriptor localizedCollection(
            String id, String packId, int menuRank, String... skinIds) {
        List<SkinCatalogSource.SkinDescriptor> skins = java.util.Arrays.stream(skinIds)
                .map(skinId -> new SkinCatalogSource.SkinDescriptor(
                        skinId,
                        CatalogText.skinName(id, skinId),
                        Optional.empty(),
                        Optional.empty(),
                        List.of(SkinModel.CLASSIC)))
                .toList();
        CatalogCollectionOrder order = menuRank < 0
                ? CatalogCollectionOrder.unknownResourcePack(packId)
                : CatalogCollectionOrder.resourcePack(packId, menuRank);
        return new SkinCatalogSource.CollectionDescriptor(
                id,
                CatalogText.collectionName(id),
                Optional.empty(),
                Optional.empty(),
                skins,
                order);
    }

    private static SkinCatalogSource.CollectionDescriptor personalCollection(
            SkinCatalogSource.SkinDescriptor... skins) {
        return new SkinCatalogSource.CollectionDescriptor(
                PersonalSkinCatalog.COLLECTION_ID,
                CatalogText.translated("nclskins.your_skins.name", "Your skins"),
                Optional.empty(),
                Optional.empty(),
                List.of(skins),
                CatalogCollectionOrder.personal(PersonalSkinCatalog.SOURCE_ID));
    }

    private static String hash(char value) {
        return String.valueOf(value).repeat(64);
    }

    private static List<String> names(List<SkinCatalogSource.SkinDescriptor> skins) {
        return skins.stream().map(SkinCatalogSource.SkinDescriptor::name).toList();
    }

    private static long distinctPreviewColumns(ViewSpec view) {
        return view.previews().stream().map(preview -> preview.bounds().x()).distinct().count();
    }
}

package com.naocraftlab.skins.runtime;

import com.naocraftlab.skins.client.CapeCatalogSource;
import com.naocraftlab.skins.client.CatalogCollectionOrder;
import com.naocraftlab.skins.client.CatalogText;
import com.naocraftlab.skins.client.PreviewRenderer;
import com.naocraftlab.skins.client.ResourcePackCapeCatalog;
import com.naocraftlab.skins.client.SkinCatalogSource;
import com.naocraftlab.skins.client.SkinModel;
import com.naocraftlab.skins.client.VanillaBackEquipmentTransform;
import com.naocraftlab.skins.core.model.AccountUiPreferences;
import com.naocraftlab.skins.core.model.AddSourceTab;
import com.naocraftlab.skins.core.model.EditorTab;
import com.naocraftlab.skins.core.model.LocalCapeReference;
import com.naocraftlab.skins.core.model.PersonalCapeEntry;
import com.naocraftlab.skins.core.provider.AppearanceProviders;
import com.naocraftlab.skins.core.provider.BuiltinProvider;
import com.naocraftlab.skins.core.provider.ProviderCape;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTimeout;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CapeCatalogTest {
    private static final double PROJECTED_MIN_X = -0.7051820703;
    private static final double PROJECTED_MAX_X = 0.7051820703;
    private static final double PROJECTED_MIN_Y = -0.7463644842;
    private static final double PROJECTED_MAX_Y = 0.7931422647;
    private final LocalCapeReference local = new LocalCapeReference(new UUID(3, 1), "a".repeat(64), false);
    private static final Map<String, String> RESOURCE_TRANSLATIONS = Map.of(
            "nclskins.event.name", "Event capes",
            "nclskins.event.description", "Event description",
            "nclskins.event.authors", "Pack author",
            "nclskins.event.cape.hero.name", "Hero cape",
            "nclskins.event.cape.hero.description", "Hero description");

    private CapeCatalogModel model() {
        var account = TestFixtures.account(1).withPersonalCapes(List.of(
                new PersonalCapeEntry(local, "b".repeat(64), "Local blue", Instant.EPOCH)));
        return CapeCatalogModel.open(account, AppearanceProviders.initial(), local, Optional.of("owned"),
                List.of(new PresetEditorModel.CapeChoice(Optional.of("owned"), UiMessage.literal("Owned red", UiMessage.Severity.INFO), true)),
                message -> message.arguments().isEmpty() ? message.key() : message.arguments().get(0).toString());
    }

    private CapeCatalogModel resourceModel() {
        return resourceModel(TextResolver.withCatalogTranslations(
                UiMessage::key, (key, fallback) -> RESOURCE_TRANSLATIONS.getOrDefault(key, fallback)));
    }

    private CapeCatalogModel deterministicResourceModel() {
        return resourceModel(TextResolver.withCatalogTranslations(
                TextResolver.withLayout(UiMessage::key, (message, width) -> 18),
                (key, fallback) -> RESOURCE_TRANSLATIONS.getOrDefault(key, fallback)));
    }

    private CapeCatalogModel resourceModel(TextResolver resolver) {
        var account = TestFixtures.account(1).withPersonalCapes(List.of(
                new PersonalCapeEntry(local, "b".repeat(64), "Saved copy", Instant.EPOCH)));
        var collection = new CapeCatalogSource.CollectionDescriptor(
                "event",
                CatalogText.collectionName("event"),
                Optional.of(CatalogText.collectionDescription("event")),
                Optional.of(CatalogText.collectionAuthors("event")),
                List.of(new CapeCatalogSource.CapeDescriptor(
                        "hero",
                        CatalogText.capeName("event", "hero"),
                        Optional.of(CatalogText.capeDescription("event", "hero")),
                        Optional.empty(),
                        "b".repeat(64),
                        CapeCatalogSource.RenderSupport.CAPE_ONLY)),
                CatalogCollectionOrder.resourcePack("file/event.zip", 0));
        return CapeCatalogModel.open(
                account,
                AppearanceProviders.initial(),
                local,
                Optional.empty(),
                List.of(),
                List.of(collection),
                Map.of(new ClientOperations.ResourceCapeKey("event", "hero"), "c".repeat(64)),
                7,
                resolver);
    }

    private CapeCatalogModel widePersonalModel() {
        return widePersonalModel(9);
    }

    private CapeCatalogModel widePersonalModel(int count) {
        List<PersonalCapeEntry> entries = IntStream.range(0, count)
                .mapToObj(index -> {
                    LocalCapeReference reference = new LocalCapeReference(
                            new UUID(4, index + 10L), String.format("%064x", index + 1), false);
                    return new PersonalCapeEntry(
                            reference, String.format("%064x", index + 100), "Personal " + index, Instant.EPOCH);
                })
                .toList();
        var account = TestFixtures.account(1).withPersonalCapes(entries);
        return CapeCatalogModel.open(
                account,
                AppearanceProviders.initial(),
                entries.get(0).texture(),
                Optional.empty(),
                List.of(),
                UiMessage::key);
    }

    private CapeCatalogModel mixedCatalogModel() {
        List<PersonalCapeEntry> personal = IntStream.range(0, 5)
                .mapToObj(index -> {
                    LocalCapeReference reference = new LocalCapeReference(
                            new UUID(5, index + 10L), "e".repeat(63) + index, false);
                    return new PersonalCapeEntry(
                            reference, "d".repeat(63) + index, "Personal " + index, Instant.EPOCH);
                })
                .toList();
        var account = TestFixtures.account(1).withPersonalCapes(personal);
        List<CapeCatalogSource.CapeDescriptor> resourceCapes = new ArrayList<>();
        Map<ClientOperations.ResourceCapeKey, String> sourceHashes = new java.util.HashMap<>();
        for (int index = 0; index < 5; index++) {
            String id = "resource-" + index;
            resourceCapes.add(new CapeCatalogSource.CapeDescriptor(
                    id,
                    CatalogText.capeName("event", id),
                    Optional.empty(),
                    Optional.empty(),
                    "c".repeat(63) + index,
                    CapeCatalogSource.RenderSupport.CAPE_ONLY));
            sourceHashes.put(new ClientOperations.ResourceCapeKey("event", id),
                    "f".repeat(63) + index);
        }
        CapeCatalogSource.CollectionDescriptor resourceCollection =
                new CapeCatalogSource.CollectionDescriptor(
                        "event",
                        CatalogText.collectionName("event"),
                        Optional.empty(),
                        Optional.empty(),
                        resourceCapes,
                        CatalogCollectionOrder.resourcePack("file/event.zip", 0));
        List<PresetEditorModel.CapeChoice> owned = IntStream.range(0, 5)
                .mapToObj(index -> new PresetEditorModel.CapeChoice(
                        Optional.of("minecraft-" + index),
                        UiMessage.literal("Minecraft " + index, UiMessage.Severity.INFO),
                        false))
                .toList();
        CapeCatalogModel catalog = CapeCatalogModel.open(
                account,
                AppearanceProviders.initial(),
                personal.get(0).texture(),
                Optional.of("minecraft-4"),
                owned,
                List.of(resourceCollection),
                sourceHashes,
                9,
                UiMessage::key);
        return catalog.choose(catalog.cards().stream()
                .filter(card -> card.provider() == BuiltinProvider.MINECRAFT
                        && card.key().equals("minecraft-4"))
                .findFirst()
                .orElseThrow());
    }

    @Test
    void readOnlyCapeRowsUseCompactHeightsForPreviewScrollAndSelection() {
        CapeCatalogModel original = mixedCatalogModel();
        CapeCatalogModel catalog = original.choose(original.cards().stream()
                .filter(card -> card.provider() == BuiltinProvider.MINECRAFT && card.key().equals("minecraft-2"))
                .findFirst().orElseThrow());
        PresetEditorModel editor = capeEditor(catalog);
        for (int height : new int[] {191, 240, 480}) {
            for (int footer : new int[] {33, 38}) {
                ViewChromeMetrics chrome = new ViewChromeMetrics(footer);
                int baseHeight = Math.max(72, Math.min(132, height - 86 - footer));
                int readOnlyHeight = baseHeight - 23;
                ViewSpec top = editor.present(320, height, 0.0, 0.0, chrome);
                Bounds viewport = top.scrollSurface("editor.capes").orElseThrow().viewport();
                assertEquals(baseHeight, top.navigationNode("editor.cape_item.OFFLINE.import")
                        .orElseThrow().bounds().height());
                int totalHeight = 10 * baseHeight + 6;
                assertEquals(totalHeight - viewport.height(), editor.maximumCapeScroll(320, height, chrome));
                for (String id : List.of("editor.cape_item.RESOURCE.event.resource-0",
                        "editor.cape_item.MINECRAFT.none", "editor.cape_item.MINECRAFT.minecraft-4")) {
                    Bounds card = top.navigationNode(id).orElseThrow().bounds();
                    assertEquals(readOnlyHeight, card.height());
                    ViewSpec focused = editor.present(320, height, card.y() - viewport.y(), 0.0, chrome);
                    if (!id.endsWith(".none")) {
                        Bounds rendered = focused.widget(id).orElseThrow().bounds();
                        assertTrue(focused.backEquipmentPreviews().stream().anyMatch(preview ->
                                preview.bounds().equals(new Bounds(rendered.x() + 5, rendered.y() + 20,
                                        rendered.width() - 10, baseHeight - 48))));
                        focused.backEquipmentPreviews().forEach(preview -> assertProjectedEnvelopeFits(preview.bounds()));
                    }
                }
                Bounds selected = top.navigationNode("editor.cape_item.MINECRAFT.minecraft-2")
                        .orElseThrow().bounds();
                double expected = Math.max(0, Math.min(totalHeight - viewport.height(),
                        selected.y() - viewport.y() - (viewport.height() - readOnlyHeight) / 2.0));
                assertEquals(expected, editor.initialCapeScrollPosition(320, height, chrome), 0.001);
                CapeCatalogModel.Card personalCard = catalog.cards().stream()
                        .filter(card -> card.local() != null).findFirst().orElseThrow();
                Bounds personalBounds = top.navigationNode(personalCard.widgetId()).orElseThrow().bounds();
                double personalOffset = Math.max(0, Math.min(totalHeight - viewport.height(),
                        personalBounds.y() - viewport.y() - (viewport.height() - baseHeight) / 2.0));
                assertEquals(personalOffset, capeEditor(catalog.choose(personalCard))
                        .initialCapeScrollPosition(320, height, chrome), 0.001);
                ViewSpec end = editor.present(320, height, totalHeight, 0.0, chrome);
                assertEquals(viewport.bottom() - 14, end.navigationNode("editor.cape_item.MINECRAFT.minecraft-4")
                        .orElseThrow().bounds().bottom());
            }
        }
    }

    @Test
    void shortCapeCollectionKeepsOrdinaryCardWidthOnAnUltrawideScreen() {
        ViewSpec view = capeEditor(widePersonalModel()).present(3840, 480, 0.0);
        List<Bounds> cards = view.navigationNodes().stream()
                .filter(node -> node.id().startsWith("editor.cape_item.OFFLINE."))
                .map(ViewSpec.NavigationNode::bounds).toList();
        assertEquals(11, cards.size());
        assertEquals(1, cards.stream().map(Bounds::y).distinct().count());
        assertTrue(cards.stream().allMatch(card -> card.width() == 69 && card.height() == 132));
        assertEquals(69 + 6, cards.get(1).x() - cards.get(0).x());
    }

    @Test
    void capeWorkspaceFillsItsPaneAndSharesSkinCollectionSpacing() {
        CapeCatalogModel catalog = widePersonalModel(60);
        PresetEditorModel editor = capeEditor(catalog);
        for (int[] dimensions : new int[][] {{320, 2, 62}, {427, 2, 89}, {480, 2, 102}, {490, 2, 104}, {492, 3, 68}, {854, 5, 74}, {3840, 25, 69}}) {
            for (int footer : new int[] {33, 38}) {
                ViewChromeMetrics chrome = new ViewChromeMetrics(footer);
                ViewSpec view = editor.present(dimensions[0], 240, 0.0, 0.0, chrome);
                Bounds viewport = view.scrollSurface("editor.capes").orElseThrow().viewport();
                Bounds header = view.navigationNode("editor.cape_header.OFFLINE").orElseThrow().bounds();
                assertEquals(new Bounds(viewport.x(), viewport.y(), viewport.width(), 16), header);
                List<Bounds> cards = view.navigationNodes().stream()
                        .filter(node -> node.id().startsWith("editor.cape_item.OFFLINE."))
                        .map(ViewSpec.NavigationNode::bounds).toList();
                assertEquals(62, cards.size());
                int columns = dimensions[1];
                int cardHeight = 154 - footer;
                assertEquals(columns, distinctX(cards));
                for (int index = 0; index < cards.size(); index++) {
                    assertEquals(new Bounds(viewport.x() + index % columns * (dimensions[2] + 6),
                            viewport.y() + 20 + index / columns * (cardHeight + 6),
                            dimensions[2], cardHeight), cards.get(index));
                    assertTrue(cards.get(index).right() <= viewport.right());
                }
                int remainder = viewport.right() - cards.get(columns - 1).right();
                assertTrue(remainder >= 0 && remainder < columns);
                Bounds nextHeader = view.navigationNode("editor.cape_header.MINECRAFT").orElseThrow().bounds();
                assertEquals(cards.get(cards.size() - 1).bottom() + 14, nextHeader.y());
                ViewSpec collapsed = capeEditor(catalog.toggle("OFFLINE"))
                        .present(dimensions[0], 240, 0.0, 0.0, chrome);
                assertEquals(viewport.y() + 20,
                        collapsed.navigationNode("editor.cape_header.MINECRAFT").orElseThrow().bounds().y());
            }
        }
    }

    @Test
    void resourceCollectionsSitBetweenPersonalAndMinecraftAndRemapSavedCopy() {
        CapeCatalogModel model = resourceModel();

        assertEquals(List.of("OFFLINE", "resource:event", "MINECRAFT"),
                model.visibleCollections());
        assertTrue(model.cards().stream().noneMatch(card -> local.equals(card.local())));
        assertEquals("event", model.selectedResource().orElseThrow().collectionId());
        assertEquals(model.previewCape(), model.resetInspection().previewCape());
        assertFalse(model.previewHasElytra());

        var withoutPack = CapeCatalogModel.open(
                TestFixtures.account(1).withPersonalCapes(List.of(
                        new PersonalCapeEntry(local, "b".repeat(64), "Saved copy", Instant.EPOCH))),
                AppearanceProviders.initial(), local, Optional.empty(), List.of(), UiMessage::key);
        assertTrue(withoutPack.cards().stream().anyMatch(card -> local.equals(card.local())));
        assertTrue(withoutPack.selectedResource().isEmpty());
    }

    @Test
    void resourceMetadataSearchCollapseAndActionsFollowCatalogRules() {
        CapeCatalogModel model = resourceModel().withQuery("hero");
        assertEquals(List.of("resource:event"), model.visibleCollections());
        assertEquals("Event description\nPack author",
                model.collectionInfo("resource:event").orElseThrow());
        assertEquals("Hero description", model.matches("resource:event").get(0).info().orElseThrow());

        var widgets = new ArrayList<ViewSpec.Widget>();
        var tooltips = new ArrayList<ViewSpec.TooltipRegion>();
        CapeCatalogPresenter.present(model, new Bounds(160, 68, 300, 500), 68, 86, 3, 0,
                false, com.naocraftlab.skins.client.BackEquipmentPreviewRenderer.Mode.CAPE,
                widgets, new ArrayList<>(), new ArrayList<>(), new ArrayList<>(),
                new ArrayList<>(), new ArrayList<>(), tooltips, new ArrayList<>());
        assertTrue(widgets.stream().noneMatch(widget -> widget.id().startsWith("editor.cape_action.")));
        assertEquals(2, tooltips.size());
        assertTrue(model.toggle("resource:event").collapsed().contains("resource:event"));
    }

    @Test
    void missingOptionalResourceMetadataStaysHiddenAndNamesUseHumanizedFallback() {
        var collection = ResourcePackCapeCatalog.build(List.of(
                new ResourcePackCapeCatalog.Variant(
                        "winter_event", "hero_cape", "fixture", 0, "b".repeat(64))))
                .get(0);
        CapeCatalogModel model = CapeCatalogModel.open(
                TestFixtures.account(1), AppearanceProviders.initial(), null, Optional.empty(),
                List.of(), List.of(collection),
                Map.of(new ClientOperations.ResourceCapeKey(
                        "winter_event", "hero_cape"), "c".repeat(64)),
                1, TextResolver.withCatalogTranslations(
                        UiMessage::key, (key, fallback) -> fallback));

        CapeCatalogModel.Card card = model.matches("resource:winter_event").get(0);
        assertEquals("Winter Event", model.collectionLabel("resource:winter_event").key());
        assertEquals("Hero Cape", card.name());
        assertTrue(model.collectionInfo("resource:winter_event").isEmpty());
        assertTrue(card.info().isEmpty());
    }

    @Test void personalServiceCardsShareSurfaceHeightAndActionsUseIcons() {
        var widgets = new ArrayList<ViewSpec.Widget>();
        var panels = new ArrayList<ViewSpec.Panel>();
        var nodes = new ArrayList<ViewSpec.NavigationNode>();
        CapeCatalogPresenter.present(model(), new Bounds(160, 68, 300, 500), 68, 86, 3, 0, false,
                com.naocraftlab.skins.client.BackEquipmentPreviewRenderer.Mode.CAPE,
                widgets, panels, new ArrayList<>(), new ArrayList<>(), new ArrayList<>(), new ArrayList<>(), nodes);
        var personal = widgets.stream().filter(w -> w.id().startsWith("editor.cape_item.OFFLINE.")).toList();
        assertEquals(3, personal.size());
        assertTrue(personal.stream().allMatch(w -> w.bounds().height() == 86));
        List<ViewSpec.Widget> idleActions = widgets.stream()
                .filter(w -> w.id().startsWith("editor.cape_action.rename.")
                        || w.id().startsWith("editor.cape_action.delete."))
                .toList();
        assertTrue(idleActions.stream().allMatch(w -> w.icon().isPresent()));
        assertTrue(widgets.stream().anyMatch(w -> w.id().startsWith("editor.cape_action.rename.")));
        for (var action : idleActions) {
            assertEquals(action.id().startsWith("editor.cape_action.rename.")
                    ? GuiIcon.ACTION_RENAME : GuiIcon.ACTION_DELETE, action.icon().orElseThrow());
        }
        assertEquals("editor.cape_header.OFFLINE", nodes.stream().filter(n -> n.tabOrder() == 4).findFirst().orElseThrow().id());
        assertEquals(1, nodes.stream().filter(n -> n.tabOrder() == 4).count());
    }

    @Test void serviceIconsAlignWithCapePreviewsAndImportHasNoVisibleCaption() {
        var widgets = new ArrayList<ViewSpec.Widget>();
        var texts = new ArrayList<ViewSpec.Text>();
        var previews = new ArrayList<ViewSpec.BackEquipmentPreview>();
        var icons = new ArrayList<ViewSpec.IconDecoration>();
        var m = model().error(true);
        int top = 68 + CapeCatalogPresenter.errorHeight(m, 300);
        CapeCatalogPresenter.present(m, new Bounds(160, top, 300, 500), 68, 86, 3, 0, false,
                com.naocraftlab.skins.client.BackEquipmentPreviewRenderer.Mode.CAPE,
                widgets, new ArrayList<>(), texts, previews, icons, new ArrayList<>(), new ArrayList<>());
        var noneIcon = icons.stream().filter(i -> i.ownerWidgetId().equals("editor.cape_item.MINECRAFT.none")).findFirst().orElseThrow();
        assertEquals(GuiIcon.APPEARANCE_CAPE_NONE, noneIcon.icon());
        assertEquals(32, noneIcon.icon().baseCanvas());
        assertEquals(32, noneIcon.icon().canvasHeight());
        var importIcon = icons.stream().filter(i -> i.ownerWidgetId().equals("editor.cape_item.OFFLINE.import")).findFirst().orElseThrow();
        assertEquals(GuiIcon.ACTION_ADD_CAPE, importIcon.icon());
        assertEquals(32, importIcon.icon().baseCanvas());
        assertEquals(32, importIcon.icon().canvasHeight());
        var importWidget = widgets.stream().filter(w -> w.id().equals(importIcon.ownerWidgetId())).findFirst().orElseThrow();
        assertFalse(importWidget.label().key().isBlank());
        assertTrue(importWidget.bounds().x() <= importIcon.bounds().x());
        assertTrue(importWidget.bounds().right() >= importIcon.bounds().right());
        Bounds preview = previews.get(0).bounds();
        for (var icon : icons.stream().filter(i -> i.id().contains("OFFLINE")).toList()) {
            assertTrue(Math.abs(2 * icon.bounds().y() + icon.bounds().height()
                    - 2 * preview.y() - preview.height()) <= 1);
        }
        assertFalse(texts.stream().anyMatch(t -> t.id().equals("editor.cape_surface.OFFLINE.import.name")));
        assertTrue(widgets.stream().anyMatch(w -> w.id().equals("editor.cape_item.OFFLINE.import")));
        Bounds error = texts.stream().filter(t -> t.id().equals("editor.cape_error")).findFirst().orElseThrow().bounds();
        Bounds search = widgets.stream().filter(w -> w.id().equals("editor.cape_search")).findFirst().orElseThrow().bounds();
        assertEquals(6, error.y() - search.bottom());
        assertEquals(6, top - error.bottom());
    }

    @Test
    void sameScreenCatalogsDistributeTheirOwnWidthAndApplyCollectionHeight() {
        SkinCatalogSource.CollectionDescriptor skins = new SkinCatalogSource.CollectionDescriptor(
                "skins",
                "Skins",
                Optional.empty(),
                Optional.empty(),
                List.of(new SkinCatalogSource.SkinDescriptor(
                        "one", "One", Optional.empty(), Optional.empty(), List.of(SkinModel.CLASSIC))));
        AddSourceModel skinModel = AddSourceModel.open(
                new AccountUiPreferences(
                        AccountUiPreferences.CURRENT_SCHEMA_VERSION,
                        TestFixtures.ACCOUNT_ID,
                        AddSourceTab.CATALOG,
                        Set.of()),
                List.of(skins));
        ViewSpec skinView = new AddSourcePresenter().present(skinModel, false, 427, 240);
        ViewSpec capeView = capeEditor(resourceModel()).present(427, 240, 0.0);
        Bounds skinCard = skinView.widget("add.catalog.skin:skins:one").orElseThrow().bounds();
        Bounds capeCard = capeView.navigationNodes().stream()
                .filter(node -> node.id().equals("editor.cape_item.OFFLINE.import"))
                .findFirst().orElseThrow().bounds();

        assertEquals(74, skinCard.width());
        assertEquals(98, skinCard.height());
        assertEquals(89, capeCard.width());
        assertEquals(121, capeCard.height());
    }

    @Test
    void paneCardSizingUsesSuppliedChromeWithoutMovingEditorFooter() {
        SkinCatalogSource.CollectionDescriptor skins = new SkinCatalogSource.CollectionDescriptor(
                "skins",
                "Skins",
                Optional.empty(),
                Optional.empty(),
                List.of(new SkinCatalogSource.SkinDescriptor(
                        "one", "One", Optional.empty(), Optional.empty(), List.of(SkinModel.CLASSIC))));
        AddSourceModel skinModel = AddSourceModel.open(
                new AccountUiPreferences(
                        AccountUiPreferences.CURRENT_SCHEMA_VERSION,
                        TestFixtures.ACCOUNT_ID,
                        AddSourceTab.CATALOG,
                        Set.of()),
                List.of(skins));
        ViewChromeMetrics chrome = new ViewChromeMetrics(38);
        ViewSpec skinView = new AddSourcePresenter().present(
                skinModel, false, Optional.empty(), 427, 240, Optional.empty(), chrome);
        ViewSpec capeView = capeEditor(resourceModel()).present(427, 240, 0.0, 0.0, chrome);
        Bounds skinCard = skinView.widget("add.catalog.skin:skins:one").orElseThrow().bounds();
        Bounds capeCard = capeView.navigationNodes().stream()
                .filter(node -> node.id().equals("editor.cape_item.OFFLINE.import"))
                .findFirst().orElseThrow().bounds();

        assertEquals(74, skinCard.width());
        assertEquals(93, skinCard.height());
        assertEquals(89, capeCard.width());
        assertEquals(skinCard.height() + 23, capeCard.height());
        assertEquals(207, capeView.panels().stream()
                .filter(panel -> panel.id().equals("footer"))
                .findFirst().orElseThrow().bounds().y());
    }

    @Test
    void capeCardsDistributePaneWidthAcrossAdaptiveColumns() {
        SkinCatalogSource.CollectionDescriptor skins = new SkinCatalogSource.CollectionDescriptor(
                "skins",
                "Skins",
                Optional.empty(),
                Optional.empty(),
                IntStream.range(0, 18)
                        .mapToObj(index -> new SkinCatalogSource.SkinDescriptor(
                                "skin-" + index,
                                "Skin " + index,
                                Optional.empty(),
                                Optional.empty(),
                                List.of(index % 2 == 0 ? SkinModel.CLASSIC : SkinModel.SLIM)))
                        .toList());
        AddSourceModel skinModel = AddSourceModel.open(
                new AccountUiPreferences(
                        AccountUiPreferences.CURRENT_SCHEMA_VERSION,
                        TestFixtures.ACCOUNT_ID,
                 AddSourceTab.CATALOG,
                         Set.of()),
                 List.of(skins));
        PresetEditorModel editor = capeEditor(widePersonalModel());
        AddSourcePresenter skinPresenter = new AddSourcePresenter();

        for (int[] dimensions : new int[][] {
                {240, 240, 2, 42, 121, 102},
                {320, 240, 2, 62, 121, 68},
                {427, 240, 2, 89, 121, 74},
                {854, 480, 5, 74, 132, 69},
                {1280, 720, 8, 71, 132, 72}}) {
            int screenWidth = dimensions[0];
            int screenHeight = dimensions[1];
            int expectedCapeColumns = dimensions[2];
            int expectedCardWidth = dimensions[3];
            int expectedCardHeight = dimensions[4];
            ViewSpec skinView = skinPresenter.present(
                    skinModel, false, screenWidth, screenHeight);
            ViewSpec capeView = editor.present(
                    screenWidth, screenHeight, 0.0);
            Bounds capeViewport = capeView.scrollSurface("editor.capes").orElseThrow().viewport();
            List<Bounds> skinCards = skinView.navigationNodes().stream()
                    .filter(node -> node.id().startsWith("add.catalog.skin:skins:"))
                    .map(ViewSpec.NavigationNode::bounds)
                    .toList();
            List<Bounds> capeCards = capeView.navigationNodes().stream()
                    .filter(node -> node.id().startsWith("editor.cape_item.OFFLINE."))
                    .map(ViewSpec.NavigationNode::bounds)
                    .toList();
            assertEquals(18, skinCards.size());
            assertEquals(11, capeCards.size());
            assertEquals(dimensions[5], skinCards.get(0).width());
            assertEquals(expectedCardWidth, capeCards.get(0).width());
            assertEquals(expectedCardHeight - 23, skinCards.get(0).height());
            assertEquals(expectedCardHeight, capeCards.get(0).height());
            assertEquals(expectedCapeColumns, distinctX(capeCards));
            assertEquals(CollectionGridLayout.CONTENT_LEFT_INSET, skinCards.get(0).x());
            assertEquals(capeViewport.x(), capeCards.get(0).x());
            if (expectedCapeColumns > 1) {
                assertEquals(CatalogCardSizing.GAP,
                        cardStep(capeCards) - expectedCardWidth);
            }
            assertTrue(capeView.navigationNodes().stream()
                    .anyMatch(node -> node.id().equals("editor.cape_item.MINECRAFT.none")));
            capeView.iconDecorations().stream()
                    .filter(icon -> icon.ownerWidgetId().startsWith("editor.cape_item.OFFLINE."))
                    .forEach(icon -> {
                        Bounds serviceCard = capeView.widget(icon.ownerWidgetId()).orElseThrow().bounds();
                        Bounds slot = new Bounds(
                                serviceCard.x() + 5,
                                serviceCard.y() + 20,
                                serviceCard.width() - 10,
                                serviceCard.height() - 48);
                        assertTrue(slot.contains(icon.bounds().x(), icon.bounds().y()));
                        assertTrue(slot.contains(icon.bounds().right() - 1, icon.bounds().bottom() - 1));
                        assertEquals(slot.x() + (slot.width() - icon.bounds().width()) / 2,
                                icon.bounds().x());
                        assertEquals(slot.y() + (slot.height() - icon.bounds().height()) / 2,
                                icon.bounds().y());
                    });
        }
    }

    @Test
    void personalCapeGeometryFitsActionsAndServiceIconsAtAdaptiveHeights() {
        CapeCatalogModel localAndService = model();
        for (int width : new int[] {320, 427, 490, 854}) {
            for (int[] dimensions : new int[][] {{191, 72}, {221, 102}, {251, 132}}) {
                int height = dimensions[0];
                int expectedCardHeight = dimensions[1];
                PresetEditorModel editor = capeEditor(localAndService);
                ViewSpec top = editor.present(width, height, 0.0);
                assertServiceCapeBounds(top, expectedCardHeight);
                Bounds personal = top.navigationNode("editor.cape_item.OFFLINE." + local.entryId())
                        .orElseThrow().bounds();
                double scroll = personal.y() - top.scrollSurface("editor.capes").orElseThrow().viewport().y();
                assertPersonalCapeBounds(editor.present(width, height, scroll),
                        local.entryId().toString(), expectedCardHeight);
                ViewSpec renamed = capeEditor(localAndService.edit(local.entryId(), false).rename("Renamed"))
                        .present(width, height, scroll);
                assertPersonalCapeBounds(renamed, local.entryId().toString(), expectedCardHeight);
                ViewSpec deleting = capeEditor(localAndService.edit(local.entryId(), true))
                        .present(width, height, scroll);
                assertPersonalCapeBounds(deleting, local.entryId().toString(), expectedCardHeight);
                ViewSpec idle = editor.present(width, height, scroll);
                Bounds idleRenameBounds = idle.widget("editor.cape_action.rename." + local.entryId())
                        .orElseThrow().bounds();
                Bounds idleDeleteBounds = idle.widget("editor.cape_action.delete." + local.entryId())
                        .orElseThrow().bounds();
                CatalogCardGeometry.ActionPair legacyRenameActions = CatalogCardGeometry.renameActions(
                        idle.widget("editor.cape_item.OFFLINE." + local.entryId()).orElseThrow().bounds());
                assertNotEquals(idleRenameBounds, legacyRenameActions.left());
                assertNotEquals(idleDeleteBounds, legacyRenameActions.right());
                ViewSpec.Widget deleteConfirm = deleting.widget(
                        "editor.cape_action.confirm." + local.entryId()).orElseThrow();
                ViewSpec.Widget deleteCancel = deleting.widget(
                        "editor.cape_action.cancel." + local.entryId()).orElseThrow();
                assertEquals(idle.widget("editor.cape_action.rename." + local.entryId()).orElseThrow().bounds(),
                        deleteConfirm.bounds());
                assertEquals(idle.widget("editor.cape_action.delete." + local.entryId()).orElseThrow().bounds(),
                        deleteCancel.bounds());
                assertEquals(ViewSpec.WidgetKind.ICON_BUTTON, deleteConfirm.kind());
                assertEquals(ViewSpec.WidgetKind.ICON_BUTTON, deleteCancel.kind());
                assertEquals("nclskins.capes.delete", deleteConfirm.label().key());
                assertEquals("gui.cancel", deleteCancel.label().key());
                assertEquals(Optional.of(NativeGuiIcon.ACCEPT), deleteConfirm.icon());
                assertEquals(Optional.of(NativeGuiIcon.REJECT), deleteCancel.icon());
                assertTrue(deleteConfirm.enabled());
                assertTrue(deleteCancel.enabled());
                ViewSpec renameView = capeEditor(localAndService.edit(local.entryId(), false).rename("Renamed"))
                        .present(width, height, scroll);
                ViewSpec.Widget renameSave = renameView.widget(
                        "editor.cape_action.save." + local.entryId()).orElseThrow();
                ViewSpec.Widget renameCancel = renameView.widget(
                        "editor.cape_action.cancel." + local.entryId()).orElseThrow();
                assertEquals(idle.widget("editor.cape_action.rename." + local.entryId()).orElseThrow().bounds(),
                        renameSave.bounds());
                assertEquals(idle.widget("editor.cape_action.delete." + local.entryId()).orElseThrow().bounds(),
                        renameCancel.bounds());
                assertEquals(ViewSpec.WidgetKind.ICON_BUTTON, renameSave.kind());
                assertEquals(ViewSpec.WidgetKind.ICON_BUTTON, renameCancel.kind());
                assertEquals(Optional.of(NativeGuiIcon.ACCEPT), renameSave.icon());
                assertEquals(Optional.of(NativeGuiIcon.REJECT), renameCancel.icon());
                assertEquals("nclskins.editor.save", renameSave.label().key());
                assertEquals("gui.cancel", renameCancel.label().key());
                assertTrue(renameSave.enabled());
                assertTrue(renameCancel.enabled());
                ViewSpec blankRename = capeEditor(localAndService.edit(local.entryId(), false).rename("   "))
                        .present(width, height, scroll);
                assertFalse(blankRename.widget("editor.cape_action.save." + local.entryId())
                        .orElseThrow().enabled());
                assertTrue(blankRename.widget("editor.cape_action.cancel." + local.entryId())
                        .orElseThrow().enabled());
            }
        }
    }

    @Test
    void backEquipmentFitUsesActualPresenterSlotsForBothModesAndChrome() {
        PresetEditorModel capeModel = capeEditor(model());
        PresetEditorModel elytraModel = capeModel.cyclePreviewMode();
        for (int[] dimensions : new int[][] {
                {320, 240, 33},
                {320, 191, 33},
                {490, 191, 38},
                {3840, 480, 33},
                {854, 480, 33},
                {854, 191, 33},
                {427, 240, 38}}) {
            ViewChromeMetrics chrome = new ViewChromeMetrics(dimensions[2]);
            double scroll = capeModel.maximumCapeScroll(dimensions[0], dimensions[1], chrome);
            ViewSpec capeView = capeModel.present(
                    dimensions[0], dimensions[1], scroll, 0.0, chrome);
            ViewSpec elytraView = elytraModel.present(
                    dimensions[0], dimensions[1], scroll, 0.0, chrome);
            List<Bounds> capeSlots = capeView.backEquipmentPreviews().stream()
                    .map(ViewSpec.BackEquipmentPreview::bounds)
                    .toList();
            List<Bounds> elytraSlots = elytraView.backEquipmentPreviews().stream()
                    .map(ViewSpec.BackEquipmentPreview::bounds)
                    .toList();

            assertTrue(capeView.backEquipmentPreviews().stream()
                    .allMatch(preview -> preview.mode()
                            == com.naocraftlab.skins.client.BackEquipmentPreviewRenderer.Mode.CAPE));
            assertTrue(elytraView.backEquipmentPreviews().stream()
                    .allMatch(preview -> preview.mode()
                            == com.naocraftlab.skins.client.BackEquipmentPreviewRenderer.Mode.ELYTRA));
            assertEquals(capeSlots, elytraSlots);
            assertFalse(capeSlots.isEmpty());
            for (Bounds slot : capeSlots) {
                assertProjectedEnvelopeFits(slot);
            }
            for (Bounds slot : elytraSlots) {
                assertProjectedEnvelopeFits(slot);
            }
        }
    }

    @Test
    void extremeNarrowCapePaneShrinksCardsAndRejectsPointerOutsideViewport() {
        ViewSpec view = capeEditor(model()).present(180, 240, 0.0);
        Bounds viewport = view.scrollSurface("editor.capes").orElseThrow().viewport();
        String cardId = "editor.cape_item.OFFLINE.import";
        Bounds card = view.navigationNode(cardId).orElseThrow().bounds();

        assertEquals(27, card.width());
        assertEquals(121, card.height());
        assertTrue(viewport.width() > card.width());
        assertTrue(card.right() <= viewport.right());
        assertEquals(2, distinctX(view.navigationNodes().stream()
                .filter(node -> node.id().startsWith("editor.cape_item.OFFLINE."))
                .map(ViewSpec.NavigationNode::bounds)
                .toList()));

        double clippedX = viewport.right() + 1.0;
        double pointerY = card.y() + 1.0;
        assertFalse(card.contains(clippedX, pointerY));
        assertFalse(ViewHostPolicy.pointerInsideClip(view, cardId, clippedX, pointerY));
        assertTrue(ViewHostPolicy.pointerOwnerAt(view, clippedX, pointerY).isEmpty());
        assertEquals(Optional.of(cardId), ViewHostPolicy.pointerOwnerAt(
                view, viewport.x() + 1.0, pointerY).map(ViewSpec.Widget::id));
    }

    @Test
    void importErrorReducesCapeViewportWithoutChangingCardSizing() {
        CapeCatalogModel base = deterministicResourceModel();
        CapeCatalogModel errored = base.withImportError(
                UiMessage.error("nclskins.capes.format_error"));
        for (int[] dimensions : new int[][] {
                {223, 100, 104},
                {253, 130, 132},
                {283, 160, 132}}) {
            ViewSpec view = capeEditor(errored).present(320, dimensions[0], 0.0);
            ViewSpec baseView = capeEditor(base).present(320, dimensions[0], 0.0);
            Bounds viewport = view.scrollSurface("editor.capes").orElseThrow().viewport();
            assertEquals(new Bounds(176, 90, 130, dimensions[1]), viewport);
            Bounds card = view.navigationNodes().stream()
                    .filter(node -> node.id().equals("editor.cape_item.OFFLINE.import"))
                    .findFirst().orElseThrow().bounds();
            assertEquals(dimensions[2], card.height());
            Bounds baseCard = baseView.navigationNode("editor.cape_item.OFFLINE.import")
                    .orElseThrow().bounds();
            assertEquals(baseCard.width(), card.width());
            assertEquals(baseCard.height(), card.height());
            assertEquals(40, view.widget("editor.cape_search").orElseThrow().bounds().y());
            assertEquals(40, view.widget("editor.cape_filter").orElseThrow().bounds().y());
            assertEquals(40, view.widget("editor.cape_disclosure").orElseThrow().bounds().y());
            assertEquals(new Bounds(176, 66, 130, 18), view.texts().stream()
                    .filter(text -> text.id().equals("editor.cape_error"))
                    .findFirst().orElseThrow().bounds());
            assertEquals(dimensions[0] - 33, view.panels().stream()
                    .filter(panel -> panel.id().equals("footer"))
                    .findFirst().orElseThrow().bounds().y());
        }

        CapeCatalogModel cleared = errored.error(false);
        ViewSpec baseView = capeEditor(base).present(320, 253, 0.0);
        ViewSpec erroredView = capeEditor(errored).present(320, 253, 0.0);
        ViewSpec clearedView = capeEditor(cleared).present(320, 253, 0.0);
        assertEquals(baseView.widget("editor.cape_search").orElseThrow().bounds(),
                clearedView.widget("editor.cape_search").orElseThrow().bounds());
        assertEquals(baseView.widget("editor.cape_filter").orElseThrow().bounds(),
                clearedView.widget("editor.cape_filter").orElseThrow().bounds());
        assertEquals(baseView.widget("editor.cape_disclosure").orElseThrow().bounds(),
                clearedView.widget("editor.cape_disclosure").orElseThrow().bounds());
        assertEquals(baseView.panels().stream().filter(panel -> panel.id().equals("footer")).findFirst().orElseThrow(),
                erroredView.panels().stream().filter(panel -> panel.id().equals("footer")).findFirst().orElseThrow());
        assertEquals(baseView.panels().stream().filter(panel -> panel.id().equals("footer")).findFirst().orElseThrow(),
                clearedView.panels().stream().filter(panel -> panel.id().equals("footer")).findFirst().orElseThrow());
        assertEquals(baseView.scrollSurface("editor.capes").orElseThrow(),
                clearedView.scrollSurface("editor.capes").orElseThrow());
        assertCatalogStateUnchanged(base, cleared);
    }

    @Test
    void longFourFormatErrorWrapsInsideNarrowPaneWithoutCoveringSearchOrFooter() {
        String copy = "Поддерживаются PNG 46×22, 64×32, 92×44 и 128×64 пикселя";
        TextResolver resolver = TextResolver.withLayout(
                message -> message.key().equals("nclskins.capes.format_error")
                        ? copy : message.key(),
                (message, width) -> 9 * Math.max(1, (copy.length() * 6 + width - 1) / width));
        CapeCatalogModel base = resourceModel(resolver);
        CapeCatalogModel errored = base.withImportError(UiMessage.error("nclskins.capes.format_error"));
        ViewSpec view = capeEditor(errored).present(320, 240, 0.0);
        ViewSpec.Text error = view.texts().stream()
                .filter(text -> text.id().equals("editor.cape_error")).findFirst().orElseThrow();
        Bounds search = view.widget("editor.cape_search").orElseThrow().bounds();
        Bounds viewport = view.scrollSurface("editor.capes").orElseThrow().viewport();
        Bounds footer = view.panels().stream().filter(panel -> panel.id().equals("footer"))
                .findFirst().orElseThrow().bounds();
        assertEquals(ViewSpec.Text.Layout.WRAP, error.layout());
        assertTrue(error.bounds().height() > 9);
        assertEquals(130, error.bounds().width());
        assertEquals(search.y(), 40);
        assertTrue(search.bottom() < error.bounds().y());
        assertTrue(error.bounds().bottom() < viewport.y());
        assertTrue(viewport.bottom() <= footer.y());
        assertTrue(capeEditor(base).present(320, 240, 0.0).scrollSurface("editor.capes")
                .orElseThrow().viewport().height() > viewport.height());
    }

    @Test
    void mixedCollectionsKeepRowsNavigationAndSelectionLinkedAcrossCollapseFractionalScrollAndResize() {
        CapeCatalogModel expanded = mixedCatalogModel();
        CapeCatalogModel collapsed = expanded.toggle("resource:event");
        assertEquals(List.of("OFFLINE", "resource:event", "MINECRAFT"), collapsed.visibleCollections());
        assertTrue(collapsed.collapsed().contains("resource:event"));
        PresetEditorModel editor = capeEditor(collapsed);
        String selectedId = "editor.cape_item.MINECRAFT.minecraft-4";

        for (int[] dimensions : new int[][] {{320, 157, 2, 72, 3}, {640, 240, 4, 121, 2}}) {
            int width = dimensions[0];
            int height = dimensions[1];
            int expectedColumns = dimensions[2];
            int expectedCardHeight = dimensions[3];
            int expectedMinecraftRows = dimensions[4];
            ViewSpec initial = editor.present(width, height);
            ViewSpec.ScrollSurface surface = initial.scrollSurface("editor.capes").orElseThrow();
            Bounds viewport = surface.viewport();
            int selectedTop = CapeCatalogPresenter.selectedPosition(
                    collapsed, expectedColumns, expectedCardHeight).top();
            int contentHeight = CapeCatalogPresenter.contentHeight(
                    collapsed, expectedColumns, expectedCardHeight);
            int expectedMaximum = Math.max(0, contentHeight - viewport.height());
            double expectedPosition = Math.max(0.0, Math.min(expectedMaximum,
                    selectedTop - (viewport.height() - (expectedCardHeight - 23)) / 2.0));
            ViewSpec.NavigationNode selectedNode = initial.navigationNode(selectedId).orElseThrow();
            assertEquals(expectedCardHeight - 23, selectedNode.bounds().height());
            assertEquals(contentHeight, (int) surface.maximumPixels() + viewport.height());
            assertEquals(expectedMaximum, surface.maximumPixels(), 0.0);
            assertEquals(expectedPosition, surface.offsetPixels(), 0.001);
            assertEquals(viewport.y() + selectedTop - (int) Math.round(expectedPosition),
                    selectedNode.bounds().y());
            if (width == 320) {
                assertEquals(viewport.y() + viewport.height() / 2.0,
                        selectedNode.bounds().y() + selectedNode.bounds().height() / 2.0, 1.0);
            }
            assertEquals(expectedMinecraftRows, initial.navigationNodes().stream()
                    .filter(node -> node.id().startsWith("editor.cape_item.MINECRAFT."))
                    .map(node -> node.bounds().y()).distinct().count());
            assertTrue(initial.navigationNodes().stream()
                    .noneMatch(node -> node.id().startsWith("editor.cape_item.RESOURCE.event.")));
            assertTrue(CapeCatalogPresenter.contentHeight(
                    expanded, expectedColumns, expectedCardHeight) > contentHeight);

            ViewSpec end = editor.present(width, height, expectedMaximum);
            ViewSpec.NavigationNode last = end.navigationNode(selectedId).orElseThrow();
            Bounds endViewport = end.scrollSurface("editor.capes").orElseThrow().viewport();
            assertEquals(endViewport.bottom() - CatalogCardSizing.GAP - 8, last.bounds().bottom());
            assertTrue(last.bounds().y() < endViewport.bottom());
            assertEquals(expectedMaximum,
                    end.scrollSurface("editor.capes").orElseThrow().offsetPixels(), 0.001);

            ViewSpec fractional49 = editor.present(width, height, 0.49);
            ViewSpec fractional51 = editor.present(width, height, 0.51);
            Bounds first49 = fractional49.navigationNode("editor.cape_item.OFFLINE.import")
                    .orElseThrow().bounds();
            Bounds first51 = fractional51.navigationNode("editor.cape_item.OFFLINE.import")
                    .orElseThrow().bounds();
            assertEquals(first49.y() - 1, first51.y());
            assertEquals(0.49, fractional49.scrollSurface("editor.capes").orElseThrow().offsetPixels(), 0.001);
            assertEquals(0.51, fractional51.scrollSurface("editor.capes").orElseThrow().offsetPixels(), 0.001);
        }
    }

    @Test void inlineModeDisablesOtherCapeActionsAndKeepsItsOwnControls() {
        var m = model();
        var other = new LocalCapeReference(new UUID(3, 2), "c".repeat(64), true);
        var cards = new ArrayList<>(m.cards());
        cards.add(new CapeCatalogModel.Card(other.entryId().toString(), BuiltinProvider.OFFLINE, UiMessage.literal("Other", UiMessage.Severity.INFO), "Other", other, true, false));
        var editing = new CapeCatalogModel(cards, m.providers(), m.offline(), m.minecraft(), "", 0, Set.of(), null, local.entryId(), false, "Name", false);
        var widgets = new ArrayList<ViewSpec.Widget>();
        CapeCatalogPresenter.present(editing, new Bounds(160, 68, 400, 500), 68, 86, 4, 0, false,
                com.naocraftlab.skins.client.BackEquipmentPreviewRenderer.Mode.CAPE,
                widgets, new ArrayList<>(), new ArrayList<>(), new ArrayList<>(), new ArrayList<>(), new ArrayList<>(), new ArrayList<>());
        ViewSpec.Widget ownSave = widgets.stream()
                .filter(w -> w.id().equals("editor.cape_action.save." + local.entryId()))
                .findFirst().orElseThrow();
        assertTrue(ownSave.enabled());
        assertEquals(ViewSpec.WidgetKind.ICON_BUTTON, ownSave.kind());
        assertEquals(Optional.of(NativeGuiIcon.ACCEPT), ownSave.icon());
        assertEquals("nclskins.editor.save", ownSave.label().key());
        assertFalse(widgets.stream().filter(w -> w.id().equals("editor.cape_action.rename." + other.entryId())).findFirst().orElseThrow().enabled());
        assertFalse(widgets.stream().filter(w -> w.id().equals("editor.cape_action.delete." + other.entryId())).findFirst().orElseThrow().enabled());
    }

    @Test void errorUsesNativeTextMeasurementWithoutExtraLines() {
        var m = model();
        var measured = new CapeCatalogModel(m.cards(), m.providers(), m.offline(), m.minecraft(), "", 0, Set.of(), null, null, false, "", true,
                TextResolver.withLayout(UiMessage::key, (message, width) -> width >= 120 ? 9 : 18));
        assertEquals(13, CapeCatalogPresenter.errorHeight(measured, 120));
        assertEquals(22, CapeCatalogPresenter.errorHeight(measured, 119));
        assertEquals(0, CapeCatalogPresenter.errorHeight(measured.error(false), 119));
    }

    @Test void importedNamesNormalizeSeparatorsAndUnicodeWords() {
        assertEquals("My Custom Cape", UntrustedDisplayName.fromFileName("my_custom-cape.png", "Cape"));
        assertEquals("Мой Новый Плащ", UntrustedDisplayName.fromFileName("мой.новый_плащ.PNG", "Cape"));
        assertEquals("Skin", UntrustedDisplayName.fromFileName("__--.png", "Skin"));
        assertEquals("My Custom Skin", new PresetEditorModel.DraftPng("my_custom-skin.png", new byte[]{1}).sourceName());
    }

    @Test void searchHidesServiceCardsAndEmptyCollectionsAndKeepsDisclosure() {
        var model = model().toggle(BuiltinProvider.OFFLINE);
        assertEquals(3, model.matches(BuiltinProvider.OFFLINE).size());
        var filtered = model.withQuery("blue");
        assertEquals(1, filtered.matches(BuiltinProvider.OFFLINE).size());
        assertTrue(filtered.matches(BuiltinProvider.MINECRAFT).isEmpty());
        assertTrue(filtered.withQuery("").collapsed().contains(BuiltinProvider.OFFLINE.name()));
        assertEquals(20, CapeCatalogPresenter.contentHeight(filtered, 2, 86));
        assertTrue(model.withQuery("missing").matches(BuiltinProvider.OFFLINE).isEmpty());
    }

    @Test void filterCyclesInBothDirectionsAndKeepsServicesWhenQueryEmpty() {
        var model = model();
        assertEquals(1, model.cycleFilter(false).filter());
        assertEquals(2, model.cycleFilter(true).filter());
        assertEquals(2, model.cycleFilter(false).matches(BuiltinProvider.OFFLINE).size());
        assertEquals(3, model.cycleFilter(true).matches(BuiltinProvider.OFFLINE).size());
        assertEquals(0, model.cycleFilter(true).cycleFilter(false).filter());
    }

    @Test void importedOptifineCapesKeepElytraFiltersAndPreviewFallbackAfterRestart(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path root) throws Exception {
        var validator = new com.naocraftlab.skins.core.png.PngValidator();
        var storage = new com.naocraftlab.skins.core.storage.NclSkinsStorage(
                root, validator, java.time.Clock.systemUTC());
        storage.initialize();
        UUID accountId = new UUID(0, 91);
        storage.loadOrCreateAccount(accountId);
        for (int width : new int[] {46, 64, 92, 128}) {
            for (boolean elytra : new boolean[] {false, true}) {
                var image = new java.awt.image.BufferedImage(width,
                        width == 46 ? 22 : width == 92 ? 44 : width == 128 ? 64 : 32,
                        java.awt.image.BufferedImage.TYPE_INT_ARGB);
                int scale = width >= 92 ? 2 : 1;
                image.setRGB(22 * scale, 0, 0xff000000 | width);
                if (elytra) image.setRGB(24 * scale, 0, 0x01000000);
                if (scale == 2) {
                    image.setRGB(3, 7, 0x80112233);
                    image.setRGB(4, 7, 0x44224466);
                }
                var output = new java.io.ByteArrayOutputStream();
                javax.imageio.ImageIO.write(image, "png", output);
                storage.importCape(accountId, width + "-" + elytra, output.toByteArray());
            }
        }
        var reopened = new com.naocraftlab.skins.core.storage.NclSkinsStorage(
                root, validator, java.time.Clock.systemUTC());
        var account = reopened.loadOrCreateAccount(accountId);
        assertEquals(8, account.personalCapes().size());
        var catalog = CapeCatalogModel.open(account, AppearanceProviders.initial(),
                account.personalCapes().get(0).texture(), Optional.empty(), List.of(), UiMessage::key);
        for (boolean elytra : new boolean[] {false, true}) {
            var filtered = catalog.cycleFilter(!elytra).matches(BuiltinProvider.OFFLINE).stream()
                    .filter(card -> card.local() != null).toList();
            assertEquals(4, filtered.size());
            for (var card : filtered) {
                assertEquals(elytra, card.hasElytra());
                byte[] stored = reopened.readCapeAsset(accountId, card.local().sha256());
                var image = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(stored));
                int expectedWidth = card.name().startsWith("92-") || card.name().startsWith("128-")
                        ? 128 : 64;
                assertEquals(expectedWidth, image.getWidth());
                assertEquals(expectedWidth / 2, image.getHeight());
                if (expectedWidth == 128) {
                    assertEquals(0x80112233, image.getRGB(3, 7));
                    assertEquals(0x44224466, image.getRGB(4, 7));
                }
                var selected = catalog.choose(card);
                assertEquals(elytra, selected.previewHasElytra());
                assertTrue(selected.previewCape().orElseThrow().contains(card.local().sha256()));
                var view = capeEditor(selected).cyclePreviewMode().present(854, 480, 0, 0,
                        new ViewChromeMetrics(33));
                var preview = view.backEquipmentPreviews().stream()
                        .filter(value -> value.capeId().equals(card.texture().orElseThrow()))
                        .findFirst().orElseThrow();
                assertEquals(elytra, preview.capeHasElytra());
            }
        }
    }

    @Test void inspectionDoesNotReplaceOtherProviderAndTabsReturnToPriority() {
        var model = model();
        var owned = model.cards().stream().filter(card -> card.key().equals("owned")).findFirst().orElseThrow();
        assertEquals(Optional.of("provider:cape:" + local.sha256()), model.previewCape());
        assertFalse(model.previewHasElytra());
        var clicked = model.choose(owned);
        assertEquals(local, clicked.offline());
        assertEquals(Optional.of("owned"), clicked.previewCape());
        assertTrue(clicked.previewHasElytra());
        assertEquals(model.previewCape(), clicked.resetInspection().previewCape());
        var none = model.cards().stream().filter(card -> card.provider() == BuiltinProvider.MINECRAFT && card.key().equals("none")).findFirst().orElseThrow();
        var empty = model.choose(none);
        assertTrue(empty.minecraft().isEmpty());
        assertTrue(empty.previewCape().isEmpty());
        assertEquals(model.previewCape(), empty.resetInspection().previewCape());
    }

    @Test void providerEditInspectsObservedInsteadOfPendingWithoutChangingDraft() {
        var model = model();
        var providers = AppearanceProviders.initial().select(1, null,
                new ProviderCape(local.entryId().toString(), local.sha256(), false), new ProviderCape("pending", null));
        providers = new AppearanceProviders(providers.skin(), providers.cape().observeMinecraft(new ProviderCape("owned", null, true)));
        var pending = new CapeCatalogModel(model.cards(), providers, local, Optional.of("pending"), "hidden", 2,
                Set.of(BuiltinProvider.MINECRAFT.name()), null, null, false, "", false);
        var inspected = pending.inspect(BuiltinProvider.MINECRAFT);
        assertEquals(Optional.of("pending"), inspected.minecraft());
        assertEquals(Optional.of("owned"), inspected.previewCape());
        assertEquals("", inspected.query());
        assertEquals(0, inspected.filter());
        assertFalse(inspected.collapsed().contains(BuiltinProvider.MINECRAFT.name()));
        assertEquals(local, inspected.offline());
    }

    @Test
    void providerEditRevealsObservedResourceCapeBySourceHash() {
        CapeCatalogModel model = resourceModel();
        var providers = AppearanceProviders.initial().select(1, null,
                new ProviderCape("observed", "c".repeat(64), false),
                new ProviderCape("pending", null));
        var pending = new CapeCatalogModel(model.cards(), providers, null, Optional.of("pending"),
                "hidden", 2, Set.of("resource:event"), null, null, false, "", false);

        var inspected = pending.inspect(BuiltinProvider.OFFLINE);

        CapeCatalogModel.Card resource = model.resourceOwner("b".repeat(64)).orElseThrow();
        assertEquals(resource, inspected.inspected());
        assertFalse(inspected.collapsed().contains("resource:event"));
        assertEquals(Optional.of("pending"), inspected.minecraft());
        assertEquals(Optional.empty(), inspected.offline() == null
                ? Optional.empty() : Optional.of(inspected.offline()));
    }

    @Test void disabledCollectionIsHiddenWithoutLosingItsAssignment() {
        var model = model();
        var hidden = new CapeCatalogModel(model.cards(), model.providers().disable(AppearanceProviders.Component.CAPE, BuiltinProvider.OFFLINE),
                local, model.minecraft(), "", 0, Set.of(), null, null, false, "", false);
        assertTrue(hidden.matches(BuiltinProvider.OFFLINE).isEmpty());
        assertEquals(local, hidden.offline());
        assertEquals(Optional.of("owned"), hidden.previewCape());
    }

    @Test void deletingActiveCapeClearsInlineStateAndSelectsNoCapeImmediately() {
        CapeCatalogModel deleted = model().edit(local.entryId(), true).refreshed(
                TestFixtures.account(1), AppearanceProviders.initial(), null,
                Optional.of("owned"), List.of());

        assertEquals(null, deleted.editing());
        assertFalse(deleted.deleting());
        assertEquals("", deleted.renameValue());
        assertEquals(null, deleted.offline());
        assertTrue(deleted.selected(deleted.matches(BuiltinProvider.OFFLINE).stream()
                .filter(card -> card.key().equals("none")).findFirst().orElseThrow()));
    }

    @Test void bulkDisclosureScalesWithSectionsInsteadOfRescanningEveryCard() {
        List<CapeCatalogModel.Card> cards = new ArrayList<>();
        for (int index = 0; index < 10_000; index++) {
            cards.add(new CapeCatalogModel.Card(
                    "cape-" + index, BuiltinProvider.OFFLINE, "resource:collection-" + index,
                    UiMessage.literal("Collection", UiMessage.Severity.INFO), Optional.empty(),
                    UiMessage.literal("Cape", UiMessage.Severity.INFO), "Cape",
                    Optional.empty(), null, null, false, false));
        }
        CapeCatalogModel large = new CapeCatalogModel(
                cards, AppearanceProviders.initial(), null, Optional.empty(), "", 0,
                Set.of(), null, null, false, "", false);

        assertTimeout(Duration.ofSeconds(2), () -> {
            CapeCatalogModel collapsed = large.toggleAll();
            assertEquals(10_000, collapsed.collapsed().size());
        });
    }

    private static PresetEditorModel capeEditor(CapeCatalogModel catalog) {
        return PresetEditorModel.open(
                        TestFixtures.account(1),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        UiMessage::key,
                        240,
                        PreviewRenderer.CapeMode.CAPE)
                .withSelectedEditorTab(EditorTab.CAPE)
                .withCapeCatalog(catalog);
    }

    @Test
    void tabGraphIncludesOffscreenActionsAndKeepsOrderThroughMaterialization() {
        PresetEditorModel editor = capeEditor(widePersonalModel());
        ViewSpec top = editor.present(320, 240, 0.0);
        List<ViewSpec.NavigationNode> content = capeTabNodes(top);
        assertTrue(content.stream().anyMatch(node -> node.id().startsWith("editor.cape_action.")
                && top.widget(node.id()).isEmpty()));
        assertTrue(content.stream().allMatch(node -> node.tabOrder() >= 4));
        assertEquals(content.stream().map(ViewSpec.NavigationNode::id).toList(),
                capeTabNodes(editor.present(320, 240, 10_000.0)).stream()
                        .map(ViewSpec.NavigationNode::id).toList());
        assertCapeTraversal(top);
        assertCapeTraversal(editor.present(320, 240, 10_000.0));
    }

    @Test
    void tabGraphOmitsCollapsedFilteredAndDisabledActionsAndIncludesInlineModes() {
        CapeCatalogModel base = model();
        for (CapeCatalogModel catalog : List.of(base, base.toggle("OFFLINE"),
                base.withQuery("Local blue"), base.withQuery("no matching cape"),
                base.edit(local.entryId(), false), base.edit(local.entryId(), false).rename(""),
                base.edit(local.entryId(), true), resourceModel())) {
            ViewSpec view = capeEditor(catalog).present(854, 480, 0.0);
            List<String> actual = capeTabNodes(view).stream()
                    .map(ViewSpec.NavigationNode::id).toList();
            List<String> expected = view.widgets().stream()
                    .filter(ViewSpec.Widget::enabled)
                    .filter(widget -> widget.id().startsWith("editor.cape_header.")
                            || widget.id().startsWith("editor.cape_item.")
                            || widget.id().startsWith("editor.cape_action."))
                    .map(ViewSpec.Widget::id).toList();
            assertEquals(expected, actual);
            assertCapeTraversal(view);
        }
        ViewSpec collapsed = capeEditor(base.toggle("OFFLINE")).present(854, 480, 0.0);
        assertTrue(capeTabNodes(collapsed).stream().noneMatch(node ->
                node.id().startsWith("editor.cape_action.") || node.id().startsWith("editor.cape_item.OFFLINE.")));
        ViewSpec blankRename = capeEditor(base.edit(local.entryId(), false).rename(""))
                .present(854, 480, 0.0);
        assertFalse(capeTabNodes(blankRename).stream().anyMatch(node -> node.id().startsWith("editor.cape_action.save.")));
    }

    private static List<ViewSpec.NavigationNode> capeTabNodes(ViewSpec view) {
        return view.navigationNodes().stream().filter(ViewSpec.NavigationNode::enabled)
                .filter(node -> node.surfaceId().filter("editor.capes"::equals).isPresent())
                .sorted(java.util.Comparator.comparingInt(ViewSpec.NavigationNode::tabOrder)).toList();
    }

    private static void assertCapeTraversal(ViewSpec view) {
        List<ViewSpec.NavigationNode> content = capeTabNodes(view);
        for (int index = 1; index < content.size(); index++) {
            assertEquals(content.get(index).id(), ViewNavigationPolicy.target(
                    view, content.get(index - 1).id(), ViewSpec.NavigationCommand.TAB_FORWARD).orElseThrow().id());
            assertEquals(content.get(index - 1).id(), ViewNavigationPolicy.target(
                    view, content.get(index).id(), ViewSpec.NavigationCommand.TAB_BACKWARD).orElseThrow().id());
        }
    }

    private static void assertPersonalCapeBounds(ViewSpec view, String key, int expectedHeight) {
        String cardId = "editor.cape_item.OFFLINE." + key;
        Bounds card = view.widget(cardId).orElseThrow().bounds();
        assertEquals(expectedHeight, card.height());
        Bounds preview = new Bounds(
                card.x() + 5,
                card.y() + 20,
                card.width() - 10,
                card.height() - 48);
        ViewSpec.BackEquipmentPreview equipment = view.backEquipmentPreviews().stream()
                .filter(value -> value.id().equals(
                        "editor.cape_surface.OFFLINE." + key + ".preview"))
                .findFirst()
                .orElseThrow();
        assertEquals(preview, equipment.bounds());
        view.widgets().stream()
                .filter(widget -> widget.id().startsWith("editor.cape_action."))
                .forEach(widget -> {
                    assertTrue(card.contains(widget.bounds().x(), widget.bounds().y()));
                    assertTrue(card.contains(widget.bounds().right() - 1, widget.bounds().bottom() - 1));
                    if (!widget.id().equals("editor.cape_action.name")) {
                        assertFalse(intersects(widget.bounds(), preview));
                    }
                });
    }

    private static void assertServiceCapeBounds(ViewSpec view, int expectedHeight) {
        for (String key : List.of("none", "import")) {
            String cardId = "editor.cape_item.OFFLINE." + key;
            Bounds card = view.widget(cardId).orElseThrow().bounds();
            assertEquals(expectedHeight, card.height());
            Bounds preview = new Bounds(
                    card.x() + 5,
                    card.y() + 20,
                    card.width() - 10,
                    card.height() - 48);
            ViewSpec.IconDecoration icon = view.iconDecorations().stream()
                    .filter(value -> value.ownerWidgetId().equals(cardId))
                    .findFirst()
                    .orElseThrow();
            assertTrue(preview.contains(icon.bounds().x(), icon.bounds().y()));
            assertTrue(preview.contains(icon.bounds().right() - 1, icon.bounds().bottom() - 1));
            assertEquals(preview.x() + (preview.width() - icon.bounds().width()) / 2,
                    icon.bounds().x());
            assertEquals(preview.y() + (preview.height() - icon.bounds().height()) / 2,
                    icon.bounds().y());
        }
    }

    private static long distinctX(List<Bounds> bounds) {
        return bounds.stream().map(Bounds::x).distinct().count();
    }

    private static int cardStep(List<Bounds> bounds) {
        List<Integer> x = bounds.stream().map(Bounds::x).distinct().sorted().toList();
        return x.get(1) - x.get(0);
    }

    private static void assertProjectedEnvelopeFits(Bounds slot) {
        double scale = VanillaBackEquipmentTransform.fitScale(slot.width(), slot.height());
        double halfWidth = slot.width() / 2.0;
        double halfHeight = slot.height() / 2.0;
        assertTrue(scale > 0.0, "fit scale must be positive for " + slot);
        assertTrue(halfWidth + PROJECTED_MIN_X * scale > 0.0,
                "left projected-envelope inset must be positive for " + slot);
        assertTrue(halfWidth - PROJECTED_MAX_X * scale > 0.0,
                "right projected-envelope inset must be positive for " + slot);
        assertTrue(halfHeight + PROJECTED_MIN_Y * scale > 0.0,
                "top projected-envelope inset must be positive for " + slot);
        assertTrue(halfHeight - PROJECTED_MAX_Y * scale > 0.0,
                "bottom projected-envelope inset must be positive for " + slot);
    }

    private static void assertCatalogStateUnchanged(
            CapeCatalogModel expected, CapeCatalogModel actual) {
        assertEquals(expected.cards(), actual.cards());
        assertEquals(expected.providers(), actual.providers());
        assertEquals(expected.offline(), actual.offline());
        assertEquals(expected.minecraft(), actual.minecraft());
        assertEquals(expected.query(), actual.query());
        assertEquals(expected.filter(), actual.filter());
        assertEquals(expected.collapsed(), actual.collapsed());
        assertEquals(expected.inspected(), actual.inspected());
        assertEquals(expected.editing(), actual.editing());
        assertEquals(expected.deleting(), actual.deleting());
        assertEquals(expected.renameValue(), actual.renameValue());
        assertEquals(expected.importError(), actual.importError());
        assertEquals(expected.textResolver().resolve(UiMessage.info("nclskins.capes.format_error")),
                actual.textResolver().resolve(UiMessage.info("nclskins.capes.format_error")));
        assertEquals(expected.textResolver().wrappedHeight(
                        UiMessage.error("nclskins.capes.format_error"), 130),
                actual.textResolver().wrappedHeight(
                        UiMessage.error("nclskins.capes.format_error"), 130));
    }

    private static boolean intersects(Bounds left, Bounds right) {
        return left.right() > right.x()
                && left.x() < right.right()
                && left.bottom() > right.y()
                && left.y() < right.bottom();
    }
}

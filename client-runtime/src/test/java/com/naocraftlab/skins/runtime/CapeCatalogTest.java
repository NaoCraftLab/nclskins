package com.naocraftlab.skins.runtime;

import com.naocraftlab.skins.client.CapeCatalogSource;
import com.naocraftlab.skins.client.BackEquipmentPreviewRenderer;
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

    @Test
    void capeImportIsToolbarOnlyAndBothNoCapeDomainsSurviveFilters() {
        var catalog = model();
        assertTrue(catalog.cards().stream().noneMatch(CapeCatalogModel.Card::importCard));
        for (int filter = 0; filter < 3; filter++) {
            var view = capeEditor(catalog).present(854, 480, 0);
            assertTrue(view.widget("editor.cape_item.OFFLINE.import").isEmpty());
            assertTrue(view.navigationNode("editor.cape_item.OFFLINE.import").isEmpty());
            var add = view.widget("editor.cape_import").orElseThrow();
            assertEquals(Optional.of(GuiIcon.ACTION_ADD_LOOK), add.icon());
            assertEquals(UiMessage.info("nclskins.capes.import"), add.label());
            assertEquals(Optional.of(add.label()), add.hint());
            assertTrue(add.enabled());
            assertTrue(add.bounds().right() < view.widget("editor.cape_search").orElseThrow().bounds().x());
            for (var provider : List.of(BuiltinProvider.OFFLINE, BuiltinProvider.MINECRAFT)) {
                assertTrue(catalog.matches(provider).stream().anyMatch(card -> card.key().equals("none")));
            }
            var searched = catalog.withQuery("absent");
            assertTrue(capeEditor(searched).present(854, 480, 0).widget("editor.cape_import").orElseThrow().enabled());
            assertTrue(searched.matches(BuiltinProvider.OFFLINE).stream().noneMatch(CapeCatalogModel.Card::service));
            catalog = catalog.cycleFilter(false);
        }
    }

    @Test
    void readOnlyCapeMarqueeReachesBothCardEdges() {
        var view = capeEditor(model()).present(854, 480, 0);
        var card = view.widget("editor.cape_item.MINECRAFT.owned").orElseThrow().bounds();
        var title = view.texts().stream().filter(text -> text.id().equals("editor.cape_surface.MINECRAFT.owned.name"))
                .findFirst().orElseThrow();
        Bounds movingClip = title.marqueeActivation().orElseThrow().movingClip().orElseThrow();
        assertEquals(card.x(), movingClip.x());
        assertEquals(card.right(), movingClip.right());
        assertEquals(card.x() + 4, title.bounds().x());
        assertEquals(card.right() - 4, title.bounds().right());
    }

    @Test
    void noCapeCardsShowOnlyCenteredIconAndRetainTheirAccessibleLabel() {
        var view = capeEditor(model()).present(854, 480, 0);
        for (String provider : List.of("OFFLINE", "MINECRAFT")) {
            String id = "editor.cape_item." + provider + ".none";
            String surface = "editor.cape_surface." + provider + ".none";
            var card = view.widget(id).orElseThrow();
            assertEquals(UiMessage.info("nclskins.editor.no_cape"), card.label());
            assertEquals(Optional.of(id), view.navigationNode(id).orElseThrow().activationActionId());
            assertFalse(view.texts().stream().anyMatch(text -> text.id().equals(surface + ".name")));
            assertFalse(view.panels().stream().anyMatch(panel -> panel.id().equals(surface + ".namebar")));
            var icon = view.iconDecorations().stream().filter(value -> value.ownerWidgetId().equals(id))
                    .findFirst().orElseThrow();
            assertEquals(GuiIcon.APPEARANCE_CAPE_NONE, icon.icon());
            assertEquals(card.bounds().y() + (card.bounds().height() - icon.bounds().height()) / 2, icon.bounds().y());
        }
    }

    @Test
    void capeCompactCardKeepsBoundsAndHidesPreviewDuringInlineRename() {
        var catalog = model();
        var normal = capeEditor(catalog).present(854, 480, 0);
        String id = "editor.cape_item.OFFLINE." + local.entryId();
        var card = normal.widget(id).orElseThrow();
        assertEquals(66, card.bounds().height());
        assertTrue(normal.widget("editor.cape_action.rename." + local.entryId()).isEmpty());
        assertTrue(normal.widget("editor.cape_action.more." + local.entryId()).orElseThrow().enabled());
        var rename = capeEditor(catalog.edit(local.entryId(), false)).present(854, 480, 0);
        assertEquals(card.bounds(), rename.widget(id).orElseThrow().bounds());
        assertTrue(rename.widget("editor.cape_action.more." + local.entryId()).isEmpty());
        assertTrue(rename.backEquipmentPreviews().stream().noneMatch(preview -> preview.id().contains(local.entryId().toString())));
        var save = rename.widget("editor.cape_action.save." + local.entryId()).orElseThrow();
        var field = rename.widget("editor.cape_action.name").orElseThrow();
        var cancel = rename.widget("editor.cape_action.cancel." + local.entryId()).orElseThrow();
        assertEquals(card.bounds().x() + 2, save.bounds().x());
        assertEquals(card.bounds().right() - 2, cancel.bounds().right());
        assertEquals(2, cancel.bounds().x() - save.bounds().right());
        assertEquals(20, save.bounds().height());
        assertTrue(field.selectAllOnFocusAcquire());
        assertTrue(rename.texts().stream().noneMatch(t -> t.id().equals(
                "editor.cape_surface.OFFLINE." + local.entryId() + ".rename_prompt")));
        var none = catalog.matches(BuiltinProvider.OFFLINE).stream().filter(value -> value.key().equals("none")).findFirst().orElseThrow();
        assertEquals(card.bounds().height(), normal.widget(none.widgetId()).orElseThrow().bounds().height());
    }

    @Test
    void compactCapeProducerRequestsCatalogFitOnlyForItsEquipmentSlots() {
        for (var mode : com.naocraftlab.skins.client.PreviewRenderer.CapeMode.values()) {
            if (mode == com.naocraftlab.skins.client.PreviewRenderer.CapeMode.OFF) continue;
            var base = capeEditor(model());
            var editor = base.withPreview(base.preview().withCapeMode(mode));
            var view = editor.present(854, 480, 0);
            assertFalse(view.backEquipmentPreviews().isEmpty());
            for (var preview : view.backEquipmentPreviews()) {
                assertEquals("CATALOG_CARD", preview.projection().name());
                var widget = view.widget(preview.id().replace("editor.cape_surface.", "editor.cape_item.").replace(".preview", ""))
                        .orElseThrow();
                var card = widget.bounds();
                assertEquals(66, card.height());
                assertEquals(new Bounds(card.x() + 2, card.y() + 8, card.width() - 4, 50), preview.bounds());
                assertEquals(preview.bounds().y() - card.y(), card.bottom() - preview.bounds().bottom());
                assertEquals(mode.name(), preview.mode().name());
                assertTrue(ViewHostPolicy.capeCardHovered(view, preview, card.x() + 1, card.y() + 1));
                assertFalse(ViewHostPolicy.capeCardHovered(view, preview, card.x() - 1, card.y() + 1));
                assertTrue(ViewHostPolicy.capeCardPlaying(view, preview, -1, -1, widget.id()));
                assertFalse(ViewHostPolicy.capeCardPlaying(view, preview, -1, -1, "editor.cape_search"));
            }
        }
        var legacy = new ViewSpec.BackEquipmentPreview("legacy", new Bounds(0, 0, 48, 96), "cape",
                com.naocraftlab.skins.client.BackEquipmentPreviewRenderer.Mode.CAPE);
        assertEquals(ViewSpec.BackEquipmentPreview.Projection.EQUIPMENT, legacy.projection());
        assertEquals(ViewSpec.BackEquipmentPreview.Projection.PROVIDER_THUMBNAIL, legacy.asProviderThumbnail().projection());
    }

    @Test
    void createCapeAssignmentsAreGreenAndRepeatedEmbeddedInspectionDoesNotToggleOff() {
        var capability = new com.naocraftlab.skins.client.PreviewAccessories(new UUID(0, 91), 1,
                com.naocraftlab.skins.client.PreviewAccessories.Availability.CONFIRMED, true, true,
                PreviewRenderer.LiveReason.AVAILABLE);
        var catalog = model().withAccessories(capability);
        var embedded = catalog.cards().stream().filter(CapeCatalogModel.Card::embedded).findFirst().orElseThrow();
        var inspected = catalog.choose(embedded).choose(embedded);
        assertEquals(embedded, inspected.inspected());
        assertEquals(catalog.offline(), inspected.offline());
        assertEquals(catalog.minecraft(), inspected.minecraft());
        var view = capeEditor(inspected).present(854, 480, 0);
        for (String id : List.of("editor.cape_item.OFFLINE." + local.entryId(), "editor.cape_item.MINECRAFT.owned", embedded.widgetId())) {
            var card = view.widget(id).orElseThrow();
            assertEquals(List.of(0xFF80C880, 0xFFFFFFFF), ViewHostPolicy.cardFrameColors(card, true));
            assertEquals(0, CatalogCardStyle.backgroundBehindContentColor(card, false));
        }
        assertTrue(view.widgets().stream().filter(widget -> widget.id().startsWith("editor.cape_item."))
                .noneMatch(widget -> ViewHostPolicy.cardFrameColor(widget, false) == 0xFF80C8FF));
        var none = catalog.matches(BuiltinProvider.OFFLINE).stream().filter(card -> card.key().equals("none")).findFirst().orElseThrow();
        var changed = capeEditor(inspected.choose(none)).present(854, 480, 0);
        assertEquals(0xFF80C880, ViewHostPolicy.cardFrameColor(changed.widget(none.widgetId()).orElseThrow(), false));
        assertEquals(0, ViewHostPolicy.cardFrameColor(changed.widget("editor.cape_item.OFFLINE." + local.entryId()).orElseThrow(), false));
        assertEquals(0xFF80C880, ViewHostPolicy.cardFrameColor(changed.widget("editor.cape_item.MINECRAFT.owned").orElseThrow(), false));
    }

    @Test
    void embeddedCpmIsFirstReadOnlyAndInspectionNeverAssigns() {
        var capability = new com.naocraftlab.skins.client.PreviewAccessories(new UUID(0, 91), 1,
                com.naocraftlab.skins.client.PreviewAccessories.Availability.CONFIRMED, false, true,
                PreviewRenderer.LiveReason.AVAILABLE);
        var original = model();
        var catalog = original.withAccessories(capability);
        assertEquals("nclskins.capes.embedded.description", catalog.collectionInfo("EMBEDDED").orElseThrow());
        assertEquals("EMBEDDED", catalog.visibleCollections().get(0));
        var card = catalog.cards().get(0);
        assertEquals(BuiltinProvider.CPM, card.provider());
        assertTrue(card.texture().isEmpty());
        assertTrue(catalog.selected(card));
        var presented = AccessoryInspection.present(capeEditor(catalog).present(320, 240, 0),
                capability, true, true, PreviewRenderer.CapeMode.CAPE, false);
        assertFalse(presented.iconDecorations().stream().anyMatch(icon ->
                icon.ownerWidgetId().equals("editor.tab.cape") && icon.icon() == GuiIcon.SOURCE_CPM));
        var inspection = catalog.choose(card);
        assertEquals(original.offline(), inspection.offline());
        assertEquals(original.minecraft(), inspection.minecraft());
        assertEquals(original.providers(), inspection.providers());
        assertEquals(card, inspection.inspected());
        var unavailable = catalog.withAccessories(new com.naocraftlab.skins.client.PreviewAccessories(
                capability.scope(), 1, capability.availability(), false, true,
                PreviewRenderer.LiveReason.WORLD_REQUIRED));
        assertEquals("EMBEDDED", unavailable.visibleCollections().get(0));
        org.junit.jupiter.api.Assertions.assertNull(unavailable.choose(unavailable.cards().get(0)).inspected());
        var lost = inspection.withAccessories(com.naocraftlab.skins.client.PreviewAccessories.unavailable(
                PreviewRenderer.LiveReason.WORLD_REQUIRED));
        assertFalse(lost.visibleCollections().contains("EMBEDDED"));
        org.junit.jupiter.api.Assertions.assertNull(lost.inspected());
        assertEquals(original.offline(), lost.offline());
    }

    @Test
    void sneakyUsesSharedSearchFilterAndDisclosureWithoutLosingInspection() {
        var base = model();
        var providers = new AppearanceProviders(base.providers().skin(), base.providers().cape().enable(BuiltinProvider.SNEAKY));
        var catalog = new CapeCatalogModel(base.cards(), providers, base.offline(), base.minecraft(), "", 0,
                Set.of(), null, null, false, "", false)
                .withSneaky(new PreviewAssets.SneakyResult(PreviewAssets.SneakyStatus.VALUE, new byte[]{1}, "f".repeat(64), false), 1);
        var card = catalog.cards().stream().filter(value -> value.provider() == BuiltinProvider.SNEAKY).findFirst().orElseThrow();
        var inspected = catalog.choose(card);
        var searched = inspected.withQuery("no matching card");
        assertFalse(searched.visibleCollections().contains("EMBEDDED"));
        assertEquals(card, searched.inspected());
        assertEquals(card.texture(), searched.previewCape());
        var filtered = inspected.cycleFilter(false);
        assertTrue(filtered.matches(BuiltinProvider.SNEAKY).isEmpty());
        assertEquals(card, filtered.inspected());
        var collapsed = inspected.toggle("EMBEDDED");
        assertTrue(collapsed.collapsed().contains("EMBEDDED"));
        assertEquals(card, collapsed.inspected());
        for (int width : new int[]{200, 320, 854}) {
            var view = capeEditor(inspected).present(width, 240, 0);
            assertTrue(view.widget(card.widgetId()).isPresent());
            assertFalse(view.widgets().stream().anyMatch(widget -> widget.id().startsWith("editor.cape_action.")
                    && widget.id().contains("SNEAKY")));
        }
        assertEquals(providers, inspected.providers());
    }

    @Test
    void allDraftProviderOrdersResolveOneAccessoryBundle() {
        var base = model();
        var sources = List.of(BuiltinProvider.OFFLINE, BuiltinProvider.MINECRAFT, BuiltinProvider.CPM, BuiltinProvider.SNEAKY);
        var capability = new com.naocraftlab.skins.client.PreviewAccessories(new UUID(0, 98), 1,
                com.naocraftlab.skins.client.PreviewAccessories.Availability.CONFIRMED, true, true, PreviewRenderer.LiveReason.AVAILABLE);
        for (var first : sources) for (var second : sources) for (var third : sources) for (var fourth : sources) {
            var order = List.of(first, second, third, fourth);
            if (order.stream().distinct().count() != sources.size()) continue;
            var channel = base.providers().cape().enable(BuiltinProvider.CPM).enable(BuiltinProvider.SNEAKY);
            for (int position = 0; position < order.size(); position++) {
                var provider = order.get(position);
                while (channel.order().indexOf(provider) > position) channel = channel.move(provider, -1);
            }
            var catalog = new CapeCatalogModel(base.cards(), new AppearanceProviders(base.providers().skin(), channel),
                    new LocalCapeReference(base.offline().entryId(), base.offline().sha256(), false), Optional.of("owned"),
                    "", 0, Set.of(), null, null, false, "", false).withAccessories(capability)
                    .withSneaky(new PreviewAssets.SneakyResult(PreviewAssets.SneakyStatus.VALUE, new byte[]{1}, "e".repeat(64), false), 1);
            assertEquals(first, catalog.defaultBundle().source());
            assertEquals(first == BuiltinProvider.CPM || first == BuiltinProvider.MINECRAFT, catalog.previewHasElytra());
            assertEquals(first == BuiltinProvider.CPM, catalog.nativePreview());
            assertEquals(catalog.defaultBundle().texture(), catalog.previewCape());
            assertTrue(catalog.selected(catalog.cards().stream().filter(card -> card.provider() == BuiltinProvider.SNEAKY).findFirst().orElseThrow()));
        }
    }

    @Test
    void embeddedPresenceSelectionIsSourceSpecificAfterPartialDisable() {
        var base = model();
        var capability = new com.naocraftlab.skins.client.PreviewAccessories(new UUID(0, 97), 1,
                com.naocraftlab.skins.client.PreviewAccessories.Availability.CONFIRMED, true, true, PreviewRenderer.LiveReason.AVAILABLE);
        var providers = new AppearanceProviders(base.providers().skin(), base.providers().cape().enable(BuiltinProvider.SNEAKY));
        var both = new CapeCatalogModel(base.cards(), providers, base.offline(), base.minecraft(), "", 0,
                Set.of(), null, null, false, "", false).withAccessories(capability)
                .withSneaky(new PreviewAssets.SneakyResult(PreviewAssets.SneakyStatus.VALUE, new byte[]{1}, "d".repeat(64), false), 1);
        var cpm = both.cards().get(0);
        var sneaky = both.cards().get(1);
        var disabled = both.refreshed(TestFixtures.account(2), providers.disable(AppearanceProviders.Component.CAPE, BuiltinProvider.CPM),
                both.offline(), both.minecraft(), List.of());
        assertFalse(disabled.selected(cpm));
        assertTrue(disabled.selected(sneaky));
    }

    @Test
    void defaultPreviewUsesProviderOrderAndClicksOnlyTemporarilyOverrideIt() {
        var base = model();
        var capability = new com.naocraftlab.skins.client.PreviewAccessories(new UUID(0, 92), 1,
                com.naocraftlab.skins.client.PreviewAccessories.Availability.CONFIRMED, true, true,
                PreviewRenderer.LiveReason.AVAILABLE);
        for (boolean cpmFirst : new boolean[]{true, false}) {
            var cape = cpmFirst ? AppearanceProviders.initial().cape().initializeCpm()
                    : base.providers().cape().enable(BuiltinProvider.CPM);
            var catalog = new CapeCatalogModel(base.cards(), new AppearanceProviders(base.providers().skin(), cape),
                    base.offline(), base.minecraft(), "", 0, Set.of(), null, null, false, "", false)
                    .withAccessories(capability);
            var embedded = catalog.cards().get(0);
            var ordinary = catalog.cards().stream().filter(card -> card.local() != null).findFirst().orElseThrow();
            assertEquals(cpmFirst, catalog.nativePreview());
            assertTrue(catalog.selected(embedded));
            var inspected = cpmFirst ? catalog.choose(ordinary) : catalog.choose(embedded);
            assertEquals(!cpmFirst, inspected.nativePreview());
            assertEquals(catalog.offline(), inspected.offline());
            assertEquals(catalog.minecraft(), inspected.minecraft());
            assertTrue(inspected.selected(embedded));
            var reset = inspected.resetInspection();
            assertEquals(cpmFirst, reset.nativePreview());
            assertEquals(catalog.providers(), reset.providers());
            assertEquals(catalog.offline(), reset.offline());
        }
    }

    @Test
    void disabledCpmRemovesEmbeddedCollectionAndRejectsStaleInspection() {
        var original = model();
        var capability = new com.naocraftlab.skins.client.PreviewAccessories(new UUID(0, 93), 1,
                com.naocraftlab.skins.client.PreviewAccessories.Availability.CONFIRMED, true, true,
                PreviewRenderer.LiveReason.AVAILABLE);
        var enabled = original.withAccessories(capability);
        var stale = enabled.cards().get(0);
        var disabledProviders = new AppearanceProviders(original.providers().skin(),
                original.providers().cape().disable(BuiltinProvider.CPM));
        var disabled = enabled.choose(stale).refreshed(TestFixtures.account(1), disabledProviders,
                original.offline(), original.minecraft(), List.of()).withAccessories(capability);
        assertFalse(disabled.visibleCollections().contains("EMBEDDED"));
        assertFalse(disabled.cards().stream().anyMatch(CapeCatalogModel.Card::embedded));
        org.junit.jupiter.api.Assertions.assertNull(disabled.inspected());
        assertFalse(disabled.nativePreview());
        assertFalse(disabled.selected(stale));
        org.junit.jupiter.api.Assertions.assertNull(disabled.choose(stale).inspected());
        var view = capeEditor(disabled).present(320, 240, 0);
        assertFalse(view.widgets().stream().anyMatch(w -> w.id().contains("EMBEDDED") || w.id().equals(stale.widgetId())));
        assertFalse(view.navigationNodes().stream().anyMatch(n -> n.id().contains("EMBEDDED") || n.id().equals(stale.widgetId())));
        assertEquals(disabledProviders, disabled.providers());
        assertEquals(original.offline(), disabled.offline());
    }

    @Test
    void sneakyWinnerUsesOneBundleEvenWhenCpmAndWritableCapesHaveElytra() {
        var base = model();
        var providers = new AppearanceProviders(base.providers().skin(), base.providers().cape()
                .enable(BuiltinProvider.SNEAKY).move(BuiltinProvider.SNEAKY, -1)
                .move(BuiltinProvider.SNEAKY, -1).move(BuiltinProvider.SNEAKY, -1));
        var capability = new com.naocraftlab.skins.client.PreviewAccessories(new UUID(0, 95), 1,
                com.naocraftlab.skins.client.PreviewAccessories.Availability.CONFIRMED, true, true,
                PreviewRenderer.LiveReason.AVAILABLE);
        var catalog = new CapeCatalogModel(base.cards(), providers,
                new LocalCapeReference(base.offline().entryId(), base.offline().sha256(), true), base.minecraft(),
                "", 0, Set.of(), null, null, false, "", false).withAccessories(capability)
                .withSneaky(new PreviewAssets.SneakyResult(PreviewAssets.SneakyStatus.VALUE,
                        new byte[]{1}, "c".repeat(64), false), 1);
        assertEquals(List.of(BuiltinProvider.CPM, BuiltinProvider.SNEAKY), catalog.cards().stream()
                .filter(CapeCatalogModel.Card::embedded).map(CapeCatalogModel.Card::provider).toList());
        assertFalse(catalog.nativeChain());
        assertFalse(catalog.nativePreview());
        assertEquals(Optional.of("sneaky:preview:1:" + "c".repeat(64)), catalog.previewCape());
        assertFalse(catalog.previewHasElytra());
        var inspected = catalog.choose(catalog.cards().get(0));
        assertTrue(inspected.nativePreview());
        assertEquals(catalog.offline(), inspected.offline());
        assertEquals(catalog.minecraft(), inspected.minecraft());
        assertEquals(catalog.previewCape(), inspected.resetInspection().previewCape());
    }

    @Test
    void partialRefreshPreservesSneakyInspectionAndDisclosureButRejectsNewTicket() {
        var base = model();
        var providers = new AppearanceProviders(base.providers().skin(), base.providers().cape().enable(BuiltinProvider.SNEAKY));
        var value = new PreviewAssets.SneakyResult(PreviewAssets.SneakyStatus.VALUE, new byte[]{1}, "c".repeat(64), false);
        var catalog = new CapeCatalogModel(base.cards(), providers, base.offline(), base.minecraft(),
                "", 0, Set.of(), null, null, false, "", false).withSneaky(value, 1);
        var card = catalog.cards().get(0);
        var inspected = catalog.choose(card).withCollapsed(Set.of("EMBEDDED"));
        var refreshed = inspected.refreshed(TestFixtures.account(1), providers,
                inspected.offline(), inspected.minecraft(), List.of());
        assertEquals(card, refreshed.inspected());
        assertEquals(Set.of("EMBEDDED"), refreshed.collapsed());
        assertEquals(card.texture(), refreshed.previewCape());
        var lostCpm = refreshed.withAccessories(com.naocraftlab.skins.client.PreviewAccessories.unavailable(
                PreviewRenderer.LiveReason.WORLD_REQUIRED));
        assertEquals(card, lostCpm.inspected());
        assertEquals(card, lostCpm.withQuery("no matches").inspected());
        org.junit.jupiter.api.Assertions.assertNull(lostCpm.withSneaky(value, 2).inspected());
    }

    @Test
    void sneakyCardFromAnEarlierTicketCannotReactivateSamePixels() {
        var base = model();
        var providers = new AppearanceProviders(base.providers().skin(), base.providers().cape().enable(BuiltinProvider.SNEAKY));
        var value = new PreviewAssets.SneakyResult(PreviewAssets.SneakyStatus.VALUE, new byte[]{1}, "c".repeat(64), false);
        var catalog = new CapeCatalogModel(base.cards(), providers, base.offline(), base.minecraft(),
                "", 0, Set.of(), null, null, false, "", false).withSneaky(value, 1);
        var stale = catalog.cards().get(0);
        org.junit.jupiter.api.Assertions.assertNull(catalog.withSneaky(value, 2).choose(stale).inspected());
    }

    private CapeCatalogModel model() {
        var account = TestFixtures.account(1).withPersonalCapes(List.of(
                new PersonalCapeEntry(local, "b".repeat(64), "Local blue", Instant.EPOCH)));
        return CapeCatalogModel.open(account, new AppearanceProviders(AppearanceProviders.initial().skin(),
                AppearanceProviders.initial().cape().enable(BuiltinProvider.CPM)), local, Optional.of("owned"),
                List.of(new PresetEditorModel.CapeChoice(Optional.of("owned"), UiMessage.literal("Owned red", UiMessage.Severity.INFO), true)),
                message -> message.arguments().isEmpty() ? message.key() : message.arguments().get(0).toString());
    }

    @Test
    void embeddedComponentsSearchDisclosureClippingAndOrdinaryAssignments() {
        for (boolean elytra : new boolean[] {false, true}) {
            var catalog = model().withAccessories(new com.naocraftlab.skins.client.PreviewAccessories(
                    new UUID(0, 91), 1, com.naocraftlab.skins.client.PreviewAccessories.Availability.CONFIRMED,
                    !elytra, elytra, PreviewRenderer.LiveReason.AVAILABLE));
            var embedded = catalog.cards().get(0);
            assertEquals(elytra, embedded.hasElytra());
            assertEquals(List.of("EMBEDDED"), catalog.withQuery("customizable_player_models").visibleCollections());
            assertTrue(catalog.withQuery("absent search").visibleCollections().isEmpty());
            for (int width : new int[] {240, 320, 854}) {
                var view = capeEditor(catalog).present(width, 240, 0);
                var card = view.widget(embedded.widgetId()).orElseThrow();
                var glyph = view.iconDecorations().stream().filter(icon -> icon.ownerWidgetId().equals(card.id())
                        && icon.icon() == GuiIcon.SOURCE_CPM).findFirst().orElseThrow();
                Bounds previewSlot = expectedEquipmentSlot(card.bounds());
                assertEquals(previewSlot.y() + (previewSlot.height() - glyph.bounds().height()) / 2,
                        glyph.bounds().y());
                assertTrue(card.activationEnabled());
                assertTrue(view.clipFor(card.id()).isPresent());
                assertTrue(view.iconDecorations().stream().anyMatch(icon -> icon.ownerWidgetId().equals(card.id())
                        && icon.icon() == GuiIcon.SOURCE_CPM && icon.idleOpacity() == 0.8F && icon.activeOpacity() == 1.0F));
                assertFalse(view.widgets().stream().anyMatch(widget -> widget.id().startsWith("editor.cape_action.")
                        && widget.id().endsWith(embedded.key())));
                assertFalse(capeEditor(catalog.toggle("EMBEDDED")).present(width, 240, 0)
                        .widget(embedded.widgetId()).isPresent());
            }
            var inspected = catalog.choose(embedded);
            var ordinary = catalog.cards().stream().filter(card -> card.provider() == BuiltinProvider.OFFLINE
                    && !card.service()).findFirst().orElseThrow();
            var assigned = inspected.choose(ordinary);
            assertEquals(ordinary.local(), assigned.offline());
            assertFalse(assigned.nativePreview());
            var none = catalog.cards().stream().filter(card -> card.provider() == BuiltinProvider.OFFLINE
                    && card.key().equals("none")).findFirst().orElseThrow();
            var cleared = inspected.choose(none);
            assertEquals(null, cleared.offline());
            assertTrue(cleared.previewCape().isEmpty());
            assertFalse(cleared.nativePreview());
            assertEquals(catalog.providers(), cleared.providers());
            for (var state : com.naocraftlab.skins.client.PreviewAccessories.Availability.values()) {
                if (state == com.naocraftlab.skins.client.PreviewAccessories.Availability.CONFIRMED) continue;
                var unknown = catalog.withAccessories(new com.naocraftlab.skins.client.PreviewAccessories(
                        new UUID(0, 91), 2, state, false, false,
                        PreviewRenderer.LiveReason.AVAILABLE));
                assertFalse(unknown.visibleCollections().contains("EMBEDDED"));
            }
        }
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
                int baseHeight = 66;
                int readOnlyHeight = 66;
                ViewSpec top = editor.present(320, height, 0.0, 0.0, chrome);
                Bounds viewport = top.scrollSurface("editor.capes").orElseThrow().viewport();
                assertEquals(baseHeight, top.navigationNode("editor.cape_item.OFFLINE.none")
                        .orElseThrow().bounds().height());
                int columns = Math.max(1, (viewport.width() + 2) / 50);
                int totalHeight = 66 + ((6 + columns - 1) / columns * 2 + (5 + columns - 1) / columns) * 68;
                assertEquals(Math.max(0, totalHeight - viewport.height()), editor.maximumCapeScroll(320, height, chrome));
                for (String id : List.of("editor.cape_item.RESOURCE.event.resource-0",
                        "editor.cape_item.MINECRAFT.none", "editor.cape_item.MINECRAFT.minecraft-4")) {
                    Bounds card = top.navigationNode(id).orElseThrow().bounds();
                    assertEquals(readOnlyHeight, card.height());
                    ViewSpec focused = editor.present(320, height, card.y() - viewport.y(), 0.0, chrome);
                    if (!id.endsWith(".none")) {
                        Bounds rendered = focused.widget(id).orElseThrow().bounds();
                        assertTrue(focused.backEquipmentPreviews().stream().anyMatch(preview ->
                                preview.bounds().equals(expectedEquipmentSlot(rendered))));
                        focused.backEquipmentPreviews().forEach(preview -> assertProjectedEnvelopeFits(preview.bounds(), preview.mode()));
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
                assertEquals(Math.min(viewport.bottom(), viewport.y() + totalHeight) - 4, end.navigationNode("editor.cape_item.MINECRAFT.minecraft-4")
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
        assertEquals(10, cards.size());
        assertEquals(1, cards.stream().map(Bounds::y).distinct().count());
        assertTrue(cards.stream().allMatch(card -> card.width() == 48 && card.height() == 66));
        assertEquals(48 + 2, cards.get(1).x() - cards.get(0).x());
    }

    @Test
    void capeWorkspaceFillsItsPaneAndSharesSkinCollectionSpacing() {
        CapeCatalogModel catalog = widePersonalModel(60);
        PresetEditorModel editor = capeEditor(catalog);
        for (int width : new int[] {320, 427, 480, 490, 492, 854, 3840}) {
            for (int footer : new int[] {33, 38}) {
                ViewChromeMetrics chrome = new ViewChromeMetrics(footer);
                ViewSpec view = editor.present(width, 240, 0.0, 0.0, chrome);
                Bounds viewport = view.scrollSurface("editor.capes").orElseThrow().viewport();
                Bounds header = view.navigationNode("editor.cape_header.OFFLINE").orElseThrow().bounds();
                assertEquals(new Bounds(viewport.x(), viewport.y(), viewport.width(), 16), header);
                List<Bounds> cards = view.navigationNodes().stream()
                        .filter(node -> node.id().startsWith("editor.cape_item.OFFLINE."))
                        .map(ViewSpec.NavigationNode::bounds).toList();
                assertEquals(61, cards.size());
                int columns = (viewport.width() + 2) / 50;
                int cardWidth = Math.min(64, (viewport.width() - (columns - 1) * 2) / columns);
                int cardHeight = 66;
                assertEquals(Math.min(cards.size(), columns), distinctX(cards));
                for (int index = 0; index < cards.size(); index++) {
                    assertEquals(new Bounds(viewport.x() + index % columns * (cardWidth + 2),
                            viewport.y() + 20 + index / columns * (cardHeight + 2),
                            cardWidth, cardHeight), cards.get(index));
                    assertTrue(cards.get(index).right() <= viewport.right());
                }
                int remainder = viewport.width() - columns * cardWidth - (columns - 1) * 2;
                assertTrue(remainder >= 0 && (cardWidth == 64 || remainder < columns));
                Bounds nextHeader = view.navigationNode("editor.cape_header.MINECRAFT").orElseThrow().bounds();
                assertEquals(cards.get(cards.size() - 1).bottom() + 4, nextHeader.y());
                ViewSpec collapsed = capeEditor(catalog.toggle("OFFLINE"))
                        .present(width, 240, 0.0, 0.0, chrome);
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
    void refreshCanonicalizesSavedCapeWithoutReplacingIndependentInspection() {
        var account = TestFixtures.account(1).withPersonalCapes(List.of(
                new PersonalCapeEntry(local, "b".repeat(64), "Saved copy", Instant.EPOCH)));
        var original = resourceModel();
        var inspected = original.cards().stream().filter(card -> card.provider() == BuiltinProvider.MINECRAFT)
                .findFirst().orElseThrow();
        var before = original.choose(inspected);
        var after = before.refreshed(account, before.providers(), local, before.minecraft(), List.of());
        assertEquals(original.offline(), after.offline());
        assertEquals(inspected.widgetId(), after.inspected().widgetId());
        assertEquals(before.minecraft(), after.minecraft());
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
        CapeCatalogPresenter.present(model(), new Bounds(160, 68, 300, 500), 68, 96, 3, 0, false,
                com.naocraftlab.skins.client.BackEquipmentPreviewRenderer.Mode.CAPE,
                widgets, panels, new ArrayList<>(), new ArrayList<>(), new ArrayList<>(), new ArrayList<>(), nodes);
        var personal = widgets.stream().filter(w -> w.id().startsWith("editor.cape_item.OFFLINE.")).toList();
        assertEquals(2, personal.size());
        assertTrue(personal.stream().allMatch(w -> w.bounds().height() == 96));
        assertTrue(widgets.stream().noneMatch(w -> w.id().startsWith("editor.cape_action.rename.") || w.id().startsWith("editor.cape_action.delete.")));
        widgets.clear();nodes.clear();
        CapeCatalogPresenter.present(model().menu(local.entryId()), new Bounds(160, 68, 300, 500), 68, 96, 3, 0, false,
                com.naocraftlab.skins.client.BackEquipmentPreviewRenderer.Mode.CAPE,
                widgets, new ArrayList<>(), new ArrayList<>(), new ArrayList<>(), new ArrayList<>(), new ArrayList<>(), nodes);
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
        assertEquals("editor.cape_header.OFFLINE", nodes.stream().filter(n -> n.tabOrder() == 5).findFirst().orElseThrow().id());
        assertEquals(1, nodes.stream().filter(n -> n.tabOrder() == 5).count());
    }

    @Test void noCapeIconsAreCenteredAndImportHasNoVisibleCaption() {
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
        var importWidget = widgets.stream().filter(w -> w.id().equals("editor.cape_import")).findFirst().orElseThrow();
        assertEquals(Optional.of(GuiIcon.ACTION_ADD_LOOK), importWidget.icon());
        assertFalse(importWidget.label().key().isBlank());

        for (var icon : icons.stream().filter(i -> i.icon() == GuiIcon.APPEARANCE_CAPE_NONE).toList()) {
            var card = widgets.stream().filter(widget -> widget.id().equals(icon.ownerWidgetId())).findFirst().orElseThrow();
            assertEquals(card.bounds().y() + (card.bounds().height() - icon.bounds().height()) / 2, icon.bounds().y());
        }
        assertFalse(texts.stream().anyMatch(t -> t.id().equals("editor.cape_surface.OFFLINE.import.name")));
        assertTrue(widgets.stream().noneMatch(w -> w.id().equals("editor.cape_item.OFFLINE.import")));
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
                .filter(node -> node.id().equals("editor.cape_item.OFFLINE.none"))
                .findFirst().orElseThrow().bounds();

        assertEquals(49, skinCard.width());
        assertEquals(96, skinCard.height());
        assertEquals(53, capeCard.width());
        assertEquals(66, capeCard.height());
    }

    @Test
    void paneCardSizingUsesSuppliedChromeAndKeepsViewportAboveItsFooter() {
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
                .filter(node -> node.id().equals("editor.cape_item.OFFLINE.none"))
                .findFirst().orElseThrow().bounds();

        assertEquals(49, skinCard.width());
        assertEquals(96, skinCard.height());
        assertEquals(55, capeCard.width());
        assertEquals(66, capeCard.height());
        assertEquals(202, capeView.panels().stream()
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
                {240, 240, 2, 61, 54},
                {320, 240, 2, 61, 49},
                {427, 240, 4, 53, 49},
                {854, 480, 11, 49, 50},
                {1280, 720, 16, 50, 48}}) {
            int screenWidth = dimensions[0];
            int screenHeight = dimensions[1];
            int expectedCapeColumns = dimensions[2];
            int expectedCardWidth = dimensions[3];
            ViewSpec skinView = skinPresenter.present(
                    skinModel, false, screenWidth, screenHeight);
            ViewSpec capeView = editor.present(
                    screenWidth, screenHeight, 0.0);
            Bounds capeViewport = capeView.scrollSurface("editor.capes").orElseThrow().viewport();
            List<Bounds> skinCards = skinView.navigationNodes().stream()
                    .filter(node -> node.id().startsWith("add.catalog.skin:skins:") && node.pattern() == ViewSpec.NavigationPattern.GRID)
                    .map(ViewSpec.NavigationNode::bounds)
                    .toList();
            List<Bounds> capeCards = capeView.navigationNodes().stream()
                    .filter(node -> node.id().startsWith("editor.cape_item.OFFLINE."))
                    .map(ViewSpec.NavigationNode::bounds)
                    .toList();
            assertEquals(18, skinCards.size());
            assertEquals(10, capeCards.size());
            assertEquals(dimensions[4], skinCards.get(0).width());
            assertEquals(expectedCardWidth, capeCards.get(0).width());
            assertEquals(96, skinCards.get(0).height());
            assertEquals(66, capeCards.get(0).height());
            assertEquals(Math.min(expectedCapeColumns, capeCards.size()), distinctX(capeCards));
            assertEquals(8, skinCards.get(0).x());
            assertEquals(capeViewport.x(), capeCards.get(0).x());
            if (expectedCapeColumns > 1) {
                assertEquals(2,
                        cardStep(capeCards) - expectedCardWidth);
            }
            assertTrue(capeView.navigationNodes().stream()
                    .anyMatch(node -> node.id().equals("editor.cape_item.MINECRAFT.none")));
            capeView.iconDecorations().stream()
                    .filter(icon -> icon.ownerWidgetId().startsWith("editor.cape_item.OFFLINE."))
                    .forEach(icon -> {
                        Bounds serviceCard = capeView.widget(icon.ownerWidgetId()).orElseThrow().bounds();
                        Bounds slot = serviceCard;
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
            for (int[] dimensions : new int[][] {{191, 66}, {221, 66}, {251, 66}}) {
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
                ViewSpec idle = capeEditor(localAndService.menu(local.entryId())).present(width, height, scroll);
                Bounds idleRenameBounds = idle.widget("editor.cape_action.rename." + local.entryId())
                        .orElseThrow().bounds();
                Bounds idleDeleteBounds = idle.widget("editor.cape_action.delete." + local.entryId())
                        .orElseThrow().bounds();
                Bounds idleCard = idle.widget("editor.cape_item.OFFLINE." + local.entryId()).orElseThrow().bounds();
                assertEquals(new Bounds(idleCard.x() + 2, idleCard.bottom() - 22, (idleCard.width() - 6) / 2, 20), idleRenameBounds);
                assertEquals(2, idleDeleteBounds.x() - idleRenameBounds.right());
                assertEquals(2, idleCard.right() - idleDeleteBounds.right());
                assertEquals(idleRenameBounds.y(), idleDeleteBounds.y());
                assertEquals(20, idleDeleteBounds.height());
                ViewSpec.Widget deleteConfirm = deleting.widget(
                        "editor.cape_action.confirm." + local.entryId()).orElseThrow();
                ViewSpec.Widget deleteCancel = deleting.widget(
                        "editor.cape_action.cancel." + local.entryId()).orElseThrow();
                assertEquals(idleRenameBounds, deleteConfirm.bounds());
                assertEquals(idleDeleteBounds, deleteCancel.bounds());
                assertEquals(ViewSpec.WidgetKind.ICON_BUTTON, deleteConfirm.kind());
                assertEquals(ViewSpec.WidgetKind.ICON_BUTTON, deleteCancel.kind());
                assertEquals("nclskins.capes.delete", deleteConfirm.label().key());
                assertEquals("gui.cancel", deleteCancel.label().key());
                assertEquals(Optional.of(NativeGuiIcon.ACCEPT), deleteConfirm.icon());
                assertEquals(Optional.of(NativeGuiIcon.REJECT), deleteCancel.icon());
                assertEquals(Optional.of(deleteConfirm.label()), deleteConfirm.hint());
                assertEquals(Optional.of(deleteCancel.label()), deleteCancel.hint());
                var question = deleting.texts().stream().filter(text -> text.id().equals(
                        "editor.cape_surface.OFFLINE." + local.entryId() + ".delete_prompt")).findFirst().orElseThrow();
                assertEquals("nclskins.capes.delete_confirmation", question.message().key());
                assertEquals(ViewSpec.Text.Alignment.CENTER, question.alignment());
                assertTrue(question.bounds().y() >= idleCard.y() + 16);
                assertTrue(question.bounds().bottom() <= deleteConfirm.bounds().y());
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
                assertProjectedEnvelopeFits(slot, BackEquipmentPreviewRenderer.Mode.CAPE);
            }
            for (Bounds slot : elytraSlots) {
                assertProjectedEnvelopeFits(slot, BackEquipmentPreviewRenderer.Mode.ELYTRA);
            }
        }
    }

    @Test
    void extremeNarrowCapePaneShrinksCardsAndRejectsPointerOutsideViewport() {
        ViewSpec view = capeEditor(model()).present(180, 240, 0.0);
        Bounds viewport = view.scrollSurface("editor.capes").orElseThrow().viewport();
        String cardId = "editor.cape_item.OFFLINE.none";
        Bounds card = view.navigationNode(cardId).orElseThrow().bounds();

        assertEquals(61, card.width());
        assertEquals(66, card.height());
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
                {223, 100, 188, 124},
                {253, 130, 188, 124},
                {283, 160, 188, 124}}) {
            ViewSpec view = capeEditor(errored).present(320, dimensions[0], 0.0);
            ViewSpec baseView = capeEditor(base).present(320, dimensions[0], 0.0);
            Bounds viewport = view.scrollSurface("editor.capes").orElseThrow().viewport();
            assertEquals(new Bounds(dimensions[2], 90, dimensions[3], dimensions[1]), viewport);
            Bounds card = view.navigationNodes().stream()
                    .filter(node -> node.id().equals("editor.cape_item.OFFLINE.none"))
                    .findFirst().orElseThrow().bounds();
            assertEquals(66, card.height());
            Bounds baseCard = baseView.navigationNode("editor.cape_item.OFFLINE.none")
                    .orElseThrow().bounds();
            assertEquals(baseCard.width(), card.width());
            assertEquals(baseCard.height(), card.height());
            assertEquals(40, view.widget("editor.cape_search").orElseThrow().bounds().y());
            assertEquals(40, view.widget("editor.cape_filter").orElseThrow().bounds().y());
            assertEquals(40, view.widget("editor.cape_disclosure").orElseThrow().bounds().y());
            assertEquals(new Bounds(dimensions[2], 66, dimensions[3], 18), view.texts().stream()
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
    void acceptedFormatErrorWrapsAtMaximumScaleWithoutCoveringSearchOrFooter() {
        String copy = "Поддерживаются PNG и GIF плащей до 2048×1024 на кадр и до 64 кадров, PNG 46×22 и 92×44, JPEG 64×32";
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
        assertEquals(viewport.width(), error.bounds().width());
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

        for (int[] dimensions : new int[][] {{320, 157, 2, 66, 3}, {640, 210, 8, 66, 1}}) {
            int width = dimensions[0];
            int height = dimensions[1];
            int expectedColumns = dimensions[2];
            int expectedCardHeight = dimensions[3];
            int expectedMinecraftRows = dimensions[4];
            ViewSpec initial = editor.present(width, height);
            ViewSpec.ScrollSurface surface = initial.scrollSurface("editor.capes").orElseThrow();
            Bounds viewport = surface.viewport();
            int rows = (6 + expectedColumns - 1) / expectedColumns;
            int selectedTop = 62 + (rows + 5 / expectedColumns) * 68;
            int contentHeight = 64 + 2 * rows * 68;
            int expectedMaximum = Math.max(0, contentHeight - viewport.height());
            double expectedPosition = Math.max(0.0, Math.min(expectedMaximum,
                    selectedTop - (viewport.height() - expectedCardHeight) / 2.0));
            ViewSpec.NavigationNode selectedNode = initial.navigationNode(selectedId).orElseThrow();
            assertEquals(expectedCardHeight, selectedNode.bounds().height());
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
            assertEquals(Math.min(endViewport.bottom(), endViewport.y() + contentHeight) - 4, last.bounds().bottom());
            assertTrue(last.bounds().y() < endViewport.bottom());
            assertEquals(expectedMaximum,
                    end.scrollSurface("editor.capes").orElseThrow().offsetPixels(), 0.001);

            ViewSpec fractional49 = editor.present(width, height, 0.49);
            ViewSpec fractional51 = editor.present(width, height, 0.51);
            Bounds first49 = fractional49.navigationNode("editor.cape_item.OFFLINE.none")
                    .orElseThrow().bounds();
            Bounds first51 = fractional51.navigationNode("editor.cape_item.OFFLINE.none")
                    .orElseThrow().bounds();
            assertEquals(first49.y() - 1, first51.y());
            assertEquals(0.49, fractional49.scrollSurface("editor.capes").orElseThrow().offsetPixels(), 0.001);
            assertEquals(0.51, fractional51.scrollSurface("editor.capes").orElseThrow().offsetPixels(), 0.001);
        }
    }

    @Test void inlineModeKeepsOtherEligibleCardsAndItsOwnControlsEnabled() {
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
        assertTrue(widgets.stream().filter(w -> w.id().equals("editor.cape_action.more." + other.entryId())).findFirst().orElseThrow().enabled());
        assertTrue(widgets.stream().filter(w -> w.id().equals("editor.cape_item.OFFLINE." + other.entryId())).findFirst().orElseThrow().enabled());
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
        assertEquals(2, model.matches(BuiltinProvider.OFFLINE).size());
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
        assertEquals(1, model.cycleFilter(false).matches(BuiltinProvider.OFFLINE).size());
        assertEquals(2, model.cycleFilter(true).matches(BuiltinProvider.OFFLINE).size());
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

    @Test void importedJpegAppearsAsOfflineCapeWithElytraAfterRestart(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path root) throws Exception {
        var storage = new com.naocraftlab.skins.core.storage.NclSkinsStorage(
                root, new com.naocraftlab.skins.core.png.PngValidator(), java.time.Clock.systemUTC());
        storage.initialize();
        UUID accountId = new UUID(0, 92);
        storage.loadOrCreateAccount(accountId);
        var image = new java.awt.image.BufferedImage(64, 32,
                java.awt.image.BufferedImage.TYPE_INT_RGB);
        image.setRGB(22, 0, 0xff557799);
        var output = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(image, "jpeg", output);
        var entry = storage.importCape(accountId, "JPEG", output.toByteArray());
        var reopened = new com.naocraftlab.skins.core.storage.NclSkinsStorage(
                root, new com.naocraftlab.skins.core.png.PngValidator(), java.time.Clock.systemUTC());
        var account = reopened.loadOrCreateAccount(accountId);
        var catalog = CapeCatalogModel.open(account, AppearanceProviders.initial(),
                entry.texture(), Optional.empty(), List.of(), UiMessage::key);
        var card = catalog.cards().stream()
                .filter(value -> entry.texture().equals(value.local()))
                .findFirst().orElseThrow();
        assertTrue(card.hasElytra());
        assertTrue(catalog.previewHasElytra());
        assertEquals(entry, account.personalCapes().get(0));
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
        var equipment = view.backEquipmentPreviews().stream()
                .filter(value -> value.id().equals(
                        "editor.cape_surface.OFFLINE." + key + ".preview"))
                .findFirst();
        if (!view.widget(cardId).orElseThrow().enabled()) assertTrue(equipment.isEmpty());
        else assertEquals(expectedEquipmentSlot(card), equipment.orElseThrow().bounds());
        view.widgets().stream()
                .filter(widget -> widget.id().startsWith("editor.cape_action."))
                .forEach(widget -> {
                    assertTrue(card.contains(widget.bounds().x(), widget.bounds().y()));
                    assertTrue(card.contains(widget.bounds().right() - 1, widget.bounds().bottom() - 1));

                });
    }

    private static void assertServiceCapeBounds(ViewSpec view, int expectedHeight) {
        for (String provider : List.of("OFFLINE", "MINECRAFT")) {
            String cardId = "editor.cape_item." + provider + ".none";
            Bounds card = view.navigationNode(cardId).orElseThrow().bounds();
            assertEquals(expectedHeight, card.height());
            if (!intersects(card, view.scrollSurface("editor.capes").orElseThrow().viewport())) {
                assertTrue(view.widget(cardId).isEmpty());
                continue;
            }
            Bounds preview = card;
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

    private static Bounds expectedEquipmentSlot(Bounds card) {
        return new Bounds(card.x() + 2, card.y() + 8, card.width() - 4, 50);
    }

    private static void assertProjectedEnvelopeFits(Bounds slot, BackEquipmentPreviewRenderer.Mode mode) {
        double scale = VanillaBackEquipmentTransform.fitScale(slot.width(), slot.height(), mode, BackEquipmentPreviewRenderer.FitPolicy.CATALOG_CARD);
        boolean cape = mode == BackEquipmentPreviewRenderer.Mode.CAPE;
        double halfWidth = slot.width() / 2.0;
        double halfHeight = slot.height() / 2.0;
        double offset = VanillaBackEquipmentTransform.modelOffsetY(mode, BackEquipmentPreviewRenderer.FitPolicy.CATALOG_CARD);
        assertTrue(scale > 0.0, "fit scale must be positive for " + slot);
        assertTrue(halfWidth + (cape ? -0.3125 : PROJECTED_MIN_X) * scale > 0.0,
                "left projected-envelope inset must be positive for " + slot);
        assertTrue(halfWidth - (cape ? 0.3125 : PROJECTED_MAX_X) * scale > 0.0,
                "right projected-envelope inset must be positive for " + slot);
        assertTrue(halfHeight + ((cape ? -0.632 : PROJECTED_MIN_Y) + offset) * scale > 0.0,
                "top projected-envelope inset must be positive for " + slot);
        assertTrue(halfHeight - ((cape ? 0.390 : PROJECTED_MAX_Y) + offset) * scale > 0.0,
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

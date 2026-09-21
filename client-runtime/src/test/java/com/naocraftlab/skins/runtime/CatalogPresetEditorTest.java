package com.naocraftlab.skins.runtime;

import com.naocraftlab.skins.client.PreviewRenderer;
import com.naocraftlab.skins.core.model.CatalogOrigin;
import com.naocraftlab.skins.core.model.SkinVariant;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class CatalogPresetEditorTest {
    @Test
    void modelCardsFillTwoColumnsPreserveAspectAndClipAtTheFooter() {
        PresetEditorModel model = PresetEditorModel.openCatalog(
                "Model", new CatalogOrigin("minecraft", "minecraft", "model"),
                Map.of(SkinVariant.CLASSIC, new byte[] {1}, SkinVariant.SLIM, new byte[] {2}),
                SkinVariant.CLASSIC, Optional.empty(), 480, PreviewRenderer.CapeMode.CAPE);
        for (int[] size : new int[][] {{320, 240}, {427, 240}, {854, 480}, {1600, 240}}) {
            ViewSpec view = model.present(size[0], size[1], 0.0, 0.0);
            ViewSpec.ScrollSurface surface = view.scrollSurface("editor.models").orElseThrow();
            ViewSpec.Widget classic = view.widget("editor.model_choice.classic").orElseThrow();
            ViewSpec.Widget slim = view.widget("editor.model_choice.slim").orElseThrow();
            assertEquals(classic.bounds().y(), slim.bounds().y());
            assertEquals(surface.viewport().x() + CollectionGridLayout.CONTENT_LEFT_INSET, classic.bounds().x());
            assertEquals(surface.viewport().right() - CollectionGridLayout.CONTENT_RIGHT_INSET, slim.bounds().right());
            assertEquals(6, slim.bounds().x() - classic.bounds().right());
            for (ViewSpec.Widget card : java.util.List.of(classic, slim)) {
                assertEquals(Math.round(card.bounds().width() * 86.0 / 68.0), card.bounds().height());
                assertEquals(Optional.of(surface.viewport()), view.clipFor(card.id()));
            }
            assertEquals(33, surface.viewport().y());
            Bounds name = view.widget("editor.name").orElseThrow().bounds();
            assertTrue(surface.viewport().contains(name.x() - 1, name.y()));
            assertTrue(surface.viewport().contains(name.right(), name.y()));
            ViewSpec throughPadding = model.present(size[0], size[1], 0.0, 8.0);
            if (surface.maximumPixels() >= 8) {
                assertEquals(32, throughPadding.texts().stream()
                        .filter(text -> text.id().equals("editor.name_label")).findFirst().orElseThrow().bounds().y());
                assertTrue(ViewHostPolicy.pointerInsideClip(throughPadding, "editor.name_label", name.x(), 35));
                assertFalse(ViewHostPolicy.pointerInsideClip(throughPadding, "editor.name_label", name.x(), 32));
            }
            assertEquals(Optional.of("selected"), classic.value());
            assertEquals(Optional.of("unselected"), slim.value());
            assertEquals(2, view.iconDecorations().size());
            for (ViewSpec.IconDecoration icon : view.iconDecorations()) {
                assertEquals(icon.bounds().width() * 2, icon.bounds().height());
                assertEquals(icon.ownerWidgetId().equals(classic.id()) ? 1.0F : 0.8F, icon.idleOpacity());
                assertEquals(1.0F, icon.activeOpacity());
            }
            assertEquals(surface.maximumPixels() > 0, view.scrollbar().isPresent());
            for (String id : java.util.List.of("editor.name", "editor.name_label", "editor.model_label")) {
                assertEquals(Optional.of(surface.viewport()), view.clipFor(id));
            }
            ViewSpec end = model.present(size[0], size[1], 0.0, surface.maximumPixels());
            assertTrue(end.widget(slim.id()).orElseThrow().bounds().bottom() <= surface.viewport().bottom());
            assertEquals(52 - (int) surface.maximumPixels(), end.widget("editor.name").orElseThrow().bounds().y());
            assertEquals(Optional.of("editor.models"), end.navigationNodes().stream()
                    .filter(node -> node.id().equals("editor.name")).findFirst().orElseThrow().surfaceId());
            if (size[0] >= 854) {
                assertTrue(view.iconDecorations().get(0).bounds().height() > 128);
            }
            if (surface.maximumPixels() > 0) {
                assertFalse(ViewHostPolicy.pointerInsideClip(end, classic.id(), classic.bounds().x() + 2, 32));
            }
        }
    }

    @Test
    void explicitModelSelectionIsIdempotentAndEnforcesCatalogAvailability() {
        for (SkinVariant variant : SkinVariant.values()) {
            PresetEditorModel model = PresetEditorModel.openCatalog(
                    "Model", new CatalogOrigin("minecraft", "minecraft", "model"),
                    Map.of(variant, new byte[] {1, 2}), variant,
                    Optional.empty(), 480, PreviewRenderer.CapeMode.CAPE);
            ViewSpec.IconDecoration selectedIcon = model.present(854, 480).iconDecorations().stream()
                    .filter(icon -> icon.ownerWidgetId().endsWith(variant == SkinVariant.CLASSIC ? "classic" : "slim"))
                    .findFirst().orElseThrow();
            assertEquals(1.0F, selectedIcon.idleOpacity());
            assertEquals(1.0F, selectedIcon.activeOpacity());
            assertEquals(model, model.selectVariant(SkinVariant.CLASSIC));
            assertEquals(model, model.selectVariant(SkinVariant.SLIM));
            ViewSpec view = model.present(854, 480);
            String selected = variant == SkinVariant.CLASSIC ? "classic" : "slim";
            String disabled = variant == SkinVariant.CLASSIC ? "slim" : "classic";
            assertTrue(view.widget("editor.model_choice." + selected).orElseThrow().enabled());
            assertFalse(view.widget("editor.model_choice." + disabled).orElseThrow().enabled());
            assertEquals(Optional.of("selected"), view.widget("editor.model_choice." + selected).orElseThrow().value());
            assertTrue(ViewNavigationPolicy.activationAction(view, "editor.model_choice." + disabled).isEmpty());
        }
    }

    @Test
    void singleModelCatalogKeepsItsOnlyVariantAndBusyDraftCannotToggle() {
        for (SkinVariant variant : SkinVariant.values()) {
            PresetEditorModel model = PresetEditorModel.openCatalog(
                    "Model", new CatalogOrigin("minecraft", "minecraft", "model"),
                    Map.of(variant, new byte[] {1, 2, 3}), variant,
                    Optional.empty(), 480, PreviewRenderer.CapeMode.CAPE);
            assertEquals(variant, model.variant());
            assertFalse(model.modelVariantSelectable());
            assertEquals(model, model.toggleVariant());
            assertEquals(variant, model.saveRequest().variant());
            assertArrayEquals(new byte[] {1, 2, 3}, model.saveRequest().pngBytes().orElseThrow());
            PresetEditorModel busy = model.withBusy(UiMessage.info("nclskins.status.saving"));
            assertEquals(busy, busy.toggleVariant());
        }
    }

    @Test
    void catalogPairToggleSwapsTheCopiedPngRevisionAndKeepsOriginForSave() {
        CatalogOrigin origin = new CatalogOrigin("minecraft", "minecraft", "steve");
        PresetEditorModel model = PresetEditorModel.openCatalog(
                "Steve",
                origin,
                Map.of(
                        SkinVariant.CLASSIC, new byte[] {1, 2, 3},
                        SkinVariant.SLIM, new byte[] {4, 5, 6}),
                SkinVariant.CLASSIC,
                Optional.empty(),
                480,
                PreviewRenderer.CapeMode.CAPE);

        assertEquals(model, model.selectVariant(SkinVariant.CLASSIC));
        PresetEditorModel busy = model.withBusy(UiMessage.info("nclskins.status.saving"));
        assertEquals(busy, busy.selectVariant(SkinVariant.SLIM));
        PresetEditorModel slim = model.selectVariant(SkinVariant.SLIM);

        assertEquals(SkinVariant.CLASSIC, model.variant());
        assertEquals(SkinVariant.SLIM, slim.variant());
        assertArrayEquals(new byte[] {1, 2, 3}, model.png().orElseThrow().bytes());
        assertArrayEquals(new byte[] {4, 5, 6}, slim.png().orElseThrow().bytes());
        assertNotEquals(model.png().orElseThrow().revision(), slim.png().orElseThrow().revision());
        assertEquals(origin, slim.saveRequest().catalogOrigin().orElseThrow());
        assertArrayEquals(new byte[] {4, 5, 6}, slim.saveRequest().pngBytes().orElseThrow());
        assertEquals(Set.of(SkinVariant.CLASSIC, SkinVariant.SLIM), slim.availableCatalogVariants());
    }

    @Test
    void replacingCatalogPngClearsOriginAndStopsPairSpecificToggleSwaps() {
        PresetEditorModel catalog = PresetEditorModel.openCatalog(
                "Alex",
                new CatalogOrigin("minecraft", "minecraft", "alex"),
                Map.of(
                        SkinVariant.CLASSIC, new byte[] {1},
                        SkinVariant.SLIM, new byte[] {2}),
                SkinVariant.CLASSIC,
                Optional.empty(),
                480,
                PreviewRenderer.CapeMode.CAPE);

        PresetEditorModel replacement = catalog.withPng("replacement.png", new byte[] {9, 8, 7});
        PresetEditorModel toggled = replacement.toggleVariant();

        assertTrue(replacement.catalogOrigin().isEmpty());
        assertTrue(replacement.availableCatalogVariants().isEmpty());
        assertTrue(replacement.saveRequest().catalogOrigin().isEmpty());
        assertArrayEquals(new byte[] {9, 8, 7}, replacement.saveRequest().pngBytes().orElseThrow());
        assertEquals(SkinVariant.SLIM, toggled.variant());
        assertArrayEquals(new byte[] {9, 8, 7}, toggled.png().orElseThrow().bytes());
        assertFalse(toggled.saveRequest().catalogOrigin().isPresent());
    }
}

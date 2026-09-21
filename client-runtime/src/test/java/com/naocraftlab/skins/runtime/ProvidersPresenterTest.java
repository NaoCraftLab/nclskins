package com.naocraftlab.skins.runtime;

import com.naocraftlab.skins.client.OuterLayerVisibility;
import com.naocraftlab.skins.client.PreviewRenderer;
import com.naocraftlab.skins.core.model.SkinVariant;
import com.naocraftlab.skins.core.provider.AppearanceProviders;
import com.naocraftlab.skins.core.provider.BuiltinProvider;
import com.naocraftlab.skins.core.provider.ProviderCape;
import com.naocraftlab.skins.core.provider.ProviderSkin;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ProvidersPresenterTest {
    private ViewSpec view(AppearanceProviders providers, String query, boolean adding) {
        return new ProvidersPresenter().present(providers, AppearanceProviders.Component.SKIN, adding, false,
                PreviewInteractionModel.editor(480, PreviewRenderer.CapeMode.CAPE), SkinVariant.CLASSIC, 854, 480);
    }

    @Test void emptyProviderSlotsUseTheirOwnTypeAndKeepCanvasAndTabGeometry() {
        for (var component : AppearanceProviders.Component.values()) {
            var view = new ProvidersPresenter().present(AppearanceProviders.initial(), component, false, false,
                    PreviewInteractionModel.editor(480, PreviewRenderer.CapeMode.CAPE), SkinVariant.CLASSIC, 854, 480);
            assertEquals(4, view.iconDecorations().size());
            for (var icon : view.iconDecorations()) {
                assertTrue(icon.providerTexture().isEmpty());
                assertEquals(32, icon.icon().baseCanvas());
                assertEquals(32, icon.icon().canvasHeight());
                boolean tab = icon.ownerWidgetId().startsWith("providers.tab.");
                assertEquals(tab ? 16 : 32, icon.bounds().width());
                assertEquals(icon.bounds().width(), icon.bounds().height());
                boolean skin = tab ? icon.ownerWidgetId().endsWith("SKIN")
                        : component == AppearanceProviders.Component.SKIN;
                assertEquals(skin ? GuiIcon.PROVIDER_SKIN_NO_VALUE : GuiIcon.PROVIDER_CAPE_NO_VALUE, icon.icon());
                if (tab) assertEquals(icon.icon(), view.widget(icon.ownerWidgetId()).orElseThrow().icon().orElseThrow());
            }
        }
    }

    @Test void editIsBeforeDeleteAndHiddenWithoutWritersInEitherComponent() {
        var presenter = new ProvidersPresenter();
        for (var component : AppearanceProviders.Component.values()) {
            var providers = AppearanceProviders.initial();
            var view = presenter.present(providers, component, false, false,
                    PreviewInteractionModel.editor(480, PreviewRenderer.CapeMode.CAPE), SkinVariant.CLASSIC, 854, 480);
            for (var provider : BuiltinProvider.values()) {
                String id = "providers.edit." + provider;
                var edit = view.widget(id).orElseThrow();
                assertEquals(GuiIcon.ACTION_EDIT, edit.icon().orElseThrow());
                assertEquals(20, edit.bounds().width());
                assertEquals(20, edit.bounds().height());
                assertEquals(view.widget("providers.remove." + provider).orElseThrow().bounds().x() - 4, edit.bounds().right());
                assertEquals("providers.remove." + provider, ViewNavigationPolicy.target(view, id, ViewSpec.NavigationCommand.RIGHT).orElseThrow().id());
                assertEquals("providers.remove." + provider, ViewNavigationPolicy.target(view, id, ViewSpec.NavigationCommand.TAB_FORWARD).orElseThrow().id());
            }
            for (var missing : AppearanceProviders.Component.values()) {
                var disabled = providers.disable(missing, BuiltinProvider.OFFLINE).disable(missing, BuiltinProvider.MINECRAFT);
                var hidden = presenter.present(disabled, component, false, false,
                        PreviewInteractionModel.editor(480, PreviewRenderer.CapeMode.CAPE), SkinVariant.CLASSIC, 854, 480);
                assertTrue(hidden.widgets().stream().noneMatch(w -> w.id().startsWith("providers.edit.")));
                assertTrue(hidden.navigationNodes().stream().noneMatch(n -> n.id().startsWith("providers.edit.")));
            }
        }
    }

    @Test void toolbarAndMissingProviderChooserRemainAvailableWhenBothListsAreEmpty() {
        var providers = AppearanceProviders.initial();
        for (var component : AppearanceProviders.Component.values()) for (var provider : BuiltinProvider.values()) providers = providers.disable(component, provider);
        assertFalse(providers.galleryAvailable());
        var view = view(providers, "", true);
        assertEquals("provider_chooser", view.screenId());
        assertEquals(320, view.widget("providers.row.OFFLINE").orElseThrow().bounds().width());
        assertTrue(view.widget("providers.row.OFFLINE").orElseThrow().enabled());
        assertTrue(view.widget("providers.row.MINECRAFT").orElseThrow().enabled());
        assertTrue(view.previews().isEmpty());
        assertTrue(view.iconDecorations().isEmpty());
        assertEquals(200, view.widget("providers.back").orElseThrow().bounds().width());
    }

    @Test void toolbarHasNoSearchAndInitialRowsAreUnselected() {
        var providers = AppearanceProviders.initial();
        var view = view(providers, "mine", false);
        assertTrue(view.widget("providers.row.OFFLINE").isPresent());
        assertTrue(view.widget("providers.search").isEmpty());
        assertFalse(view.widget("providers.row.OFFLINE").orElseThrow().value().isPresent());
        assertTrue(view.widget("providers.add").orElseThrow().enabled());
        assertEquals(GuiIcon.ACTION_ADD_PROVIDER, view.widget("providers.add").orElseThrow().icon().orElseThrow());
        assertTrue(view.widget("providers.up.MINECRAFT").orElseThrow().enabled());
        assertFalse(view.widget("providers.down.MINECRAFT").orElseThrow().enabled());
        assertEquals(BuiltinProvider.OFFLINE, providers.skin().order().get(0));
    }

    @Test void chooserDisablesExistingAndClipsScrollableButtons() {
        var presenter = new ProvidersPresenter();
        var providers = AppearanceProviders.initial().disable(AppearanceProviders.Component.CAPE, BuiltinProvider.OFFLINE);
        var view = presenter.presentChooser(providers, AppearanceProviders.Component.CAPE, false, 320, 100, 999);
        assertEquals("nclskins.providers.add_cape", view.title().key());
        assertTrue(view.widget("providers.row.OFFLINE").orElseThrow().enabled());
        assertFalse(view.widget("providers.row.MINECRAFT").orElseThrow().enabled());
        assertEquals(288, view.widget("providers.row.OFFLINE").orElseThrow().bounds().width());
        var surface = view.scrollSurface("providers.chooser").orElseThrow();
        assertTrue(surface.maximumPixels() > 0);
        assertEquals(surface.maximumPixels(), surface.offsetPixels());
        assertTrue(view.clipRegions().get(0).matches("providers.row.MINECRAFT"));
        assertTrue(view.scrollbar().isPresent());
        assertTrue(view.widget("providers.row.OFFLINE").orElseThrow().bounds().y()
                < view.widget("providers.row.MINECRAFT").orElseThrow().bounds().y());
    }

    @Test void cooldownUsesTheMinecraftRowAreaAfterItsIconInBothTabs() {
        var presenter = new ProvidersPresenter();
        for (var component : AppearanceProviders.Component.values()) {
            var providers = AppearanceProviders.initial();
            var progress = java.util.Optional.of(new ClientSnapshot.RateLimitProgress(
                    java.time.Duration.ofSeconds(30), java.time.Duration.ofSeconds(60), 0.5));
            var view = presenter.present(providers, component, false, false,
                    PreviewInteractionModel.editor(480, PreviewRenderer.CapeMode.CAPE), SkinVariant.CLASSIC,
                    854, 480, null, null, progress);
            assertEquals(1, view.progressDecorations().size());
            var bar = view.progressDecorations().get(0);
            var row = view.widget(bar.ownerWidgetId()).orElseThrow();
            assertEquals("providers.row.MINECRAFT", bar.ownerWidgetId());
            assertEquals(0.5, bar.fraction());
            assertEquals(row.bounds().x() + 34, bar.startX(row.bounds()));
            assertEquals(row.bounds().right() - 1, bar.startX(row.bounds()) + bar.availableWidth(row.bounds()));
            assertEquals(2, bar.height());
            var icon = view.iconDecorations().stream().filter(value -> value.ownerWidgetId().equals(row.id())).findFirst().orElseThrow();
            assertEquals(icon.bounds().bottom(), bar.bottomY(row.bounds()));
            var galleryBar = new ViewSpec.ProgressDecoration("gallery", row.id(), 0.5, 0xFF5A8FCB, 2);
            assertEquals(row.bounds().bottom() - 1, galleryBar.bottomY(row.bounds()));
            assertTrue(presenter.present(providers.disable(component, BuiltinProvider.MINECRAFT), component, false, false,
                    PreviewInteractionModel.editor(480, PreviewRenderer.CapeMode.CAPE), SkinVariant.CLASSIC,
                    854, 480, null, null, progress).progressDecorations().isEmpty());
        }
    }

    @Test void keyboardTraversalStaysWithinRowsAndSkipsDisabledActions() {
        var view = view(AppearanceProviders.initial(), "", false);
        assertEquals("providers.row.MINECRAFT", ViewNavigationPolicy.target(view, "providers.row.OFFLINE", ViewSpec.NavigationCommand.DOWN).orElseThrow().id());
        assertEquals("providers.down.OFFLINE", ViewNavigationPolicy.target(view, "providers.row.OFFLINE", ViewSpec.NavigationCommand.RIGHT).orElseThrow().id());
        assertEquals("providers.edit.OFFLINE", ViewNavigationPolicy.target(view, "providers.down.OFFLINE", ViewSpec.NavigationCommand.RIGHT).orElseThrow().id());
        assertEquals("providers.edit.OFFLINE", ViewNavigationPolicy.target(view, "providers.remove.OFFLINE", ViewSpec.NavigationCommand.LEFT).orElseThrow().id());
        assertEquals("providers.row.MINECRAFT", ViewNavigationPolicy.target(view, "providers.remove.OFFLINE", ViewSpec.NavigationCommand.DOWN).orElseThrow().id());
        assertEquals("providers.up.MINECRAFT", ViewNavigationPolicy.target(view, "providers.row.MINECRAFT", ViewSpec.NavigationCommand.TAB_FORWARD).orElseThrow().id());
        assertEquals("providers.edit.MINECRAFT", ViewNavigationPolicy.target(view, "providers.up.MINECRAFT", ViewSpec.NavigationCommand.TAB_FORWARD).orElseThrow().id());
        var delete = view.widget("providers.remove.OFFLINE").orElseThrow();
        var row = view.widget("providers.row.OFFLINE").orElseThrow();
        assertEquals(ViewSpec.WidgetKind.ICON_BUTTON, delete.kind());
        assertEquals(20, delete.bounds().width());
        assertEquals(20, delete.bounds().height());
        assertEquals(row.bounds().right() - 4, delete.bounds().right());
        assertEquals(GuiIcon.ACTION_REMOVE, delete.icon().orElseThrow());
        assertEquals("providers.remove.OFFLINE", ViewNavigationPolicy.activationAction(view, delete.id()).orElseThrow());
    }

    @Test void rowsAndTabsUseTheSameEffectiveTextureAndOptionsMask() {
        var providers = AppearanceProviders.initial().select(1, new ProviderSkin("a".repeat(64), SkinVariant.SLIM), new ProviderCape("cape", "b".repeat(64)));
        var view = new ProvidersPresenter().present(providers, AppearanceProviders.Component.SKIN, false, false,
                PreviewInteractionModel.editor(480, PreviewRenderer.CapeMode.ELYTRA).withOuterLayerVisibility(OuterLayerVisibility.noneVisible()), SkinVariant.CLASSIC, 854, 480);
        var tab = view.iconDecorations().stream().filter(i -> i.ownerWidgetId().equals("providers.tab.SKIN")).findFirst().orElseThrow();
        var row = view.iconDecorations().stream().filter(i -> i.ownerWidgetId().equals("providers.row.OFFLINE")).findFirst().orElseThrow();
        assertEquals(tab.providerTexture(), row.providerTexture());
        assertFalse(tab.providerTexture().orElseThrow().overlay());
        assertEquals(SkinVariant.SLIM, view.previews().get(0).variant());
        assertEquals(PreviewRenderer.CapeMode.ELYTRA, view.previews().get(0).capeMode());
        assertEquals(GuiIcon.APPEARANCE_BACK_ELYTRA, view.widget("providers.preview_mode").orElseThrow().icon().orElseThrow());
        assertEquals(OuterLayerVisibility.noneVisible(), view.previews().get(0).outerLayerVisibility());
    }

    @Test void inspectingEmptyMinecraftSkinKeepsTheOfflineTabResult() {
        var providers = AppearanceProviders.initial().select(1, new ProviderSkin("a".repeat(64), SkinVariant.SLIM), new ProviderCape("cape", "b".repeat(64)));
        var inspected = new ProvidersPresenter().present(providers, AppearanceProviders.Component.SKIN, false, false,
                PreviewInteractionModel.editor(480, PreviewRenderer.CapeMode.CAPE), SkinVariant.CLASSIC, 854, 480, BuiltinProvider.MINECRAFT, null);
        assertEquals("provider:default", inspected.previews().get(0).imageRevision());
        assertTrue(inspected.previews().get(0).capeId().isPresent());
        assertEquals(view(providers, "", false).iconDecorations().get(0), inspected.iconDecorations().get(0));
        assertEquals("selected", inspected.widget("providers.row.MINECRAFT").orElseThrow().value().orElseThrow());
    }

    @Test void capeProjectionKeepsCentreOfFrontFaceAtEveryResolution() {
        for (int scale : new int[]{1, 2, 4, 16}) {
            var crop = ProviderIconProjection.of(false, false, 64 * scale, 32 * scale);
            assertEquals(1 * scale, crop.u());
            assertEquals(4 * scale, crop.v());
            assertEquals(10 * scale, crop.width());
            assertEquals(crop.width(), crop.height());
        }
        assertEquals(ProviderIconProjection.of(true, false, 64, 64), ProviderIconProjection.of(true, false, 64, 32));
    }
}

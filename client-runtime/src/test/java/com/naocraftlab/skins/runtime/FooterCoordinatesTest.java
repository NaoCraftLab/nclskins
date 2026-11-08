package com.naocraftlab.skins.runtime;

import com.naocraftlab.skins.client.OuterLayerVisibility;
import com.naocraftlab.skins.client.PreviewRenderer;
import com.naocraftlab.skins.core.model.AccountUiPreferences;
import com.naocraftlab.skins.core.model.AddSourceTab;
import com.naocraftlab.skins.core.model.SkinVariant;
import com.naocraftlab.skins.core.provider.AppearanceProviders;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

final class FooterCoordinatesTest {
    @Test void everyCommonFooterLeafUsesItsOwnFooterAndVanillaRounding() {
        var account = TestFixtures.account(2);
        var snapshot = TestFixtures.ready(account, account.presets().get(0).id(), 0);
        for (int width : List.of(240, 241, 320, 854)) for (int height : List.of(112, 113, 239, 240, 479, 480)) {
            var gallery = new GalleryPresenter();
            check(gallery.present(snapshot, width, height, 0, 0, PreviewRenderer.CapeMode.CAPE), "gallery.done");
            var draft = new GalleryPresenter.DraftInput(snapshot.activePresetId(), OuterLayerVisibility.allVisible(),
                    OuterLayerVisibility.noneVisible(), 1, Optional.empty(), Set.of(), Optional.empty(), PreviewRenderer.DisplayKind.ORDINARY);
            check(gallery.present(snapshot, width, height, 0, 0, PreviewRenderer.CapeMode.CAPE,
                    SkinVariant.CLASSIC, "", Optional.empty(), 0, null, draft), "gallery.apply", "gallery.back", "gallery.reset");
            for (boolean adding : List.of(false, true)) for (var component : AppearanceProviders.Component.values()) {
                check(new ProvidersPresenter().present(AppearanceProviders.initial(), component, adding, false,
                        PreviewInteractionModel.editor(height, PreviewRenderer.CapeMode.CAPE), SkinVariant.CLASSIC,
                        width, height), "providers.back");
            }
            for (var tab : AddSourceTab.values()) {
                var model = AddSourceModel.open(AccountUiPreferences.defaults(TestFixtures.ACCOUNT_ID), List.of(),
                        SkinVariant.CLASSIC, UiMessage::key).withSelectedTab(tab);
                check(new AddSourcePresenter().present(model, false, width, height), "add.cancel");
            }
            for (var category : ExternalImportModel.Category.values()) {
                check(new ExternalImportPresenter().present(ExternalImportModel.open(category), false, Optional.empty(),
                        width, height), "external.back");
            }
            var candidate = new ClientOperations.ExternalImportCandidate("footer", "Footer", SkinVariant.CLASSIC,
                    com.naocraftlab.skins.core.model.PersonalSkinSource.FILE, new byte[]{1}, "0".repeat(64), null, 0, false);
            var review = ExternalImportModel.open(ExternalImportModel.Category.MOD).withReview(
                    new ClientOperations.ExternalImportReview(com.naocraftlab.skins.core.importing.ExternalImportSource.SKIN_SHUFFLE,
                            List.of(candidate), 0, 0));
            check(new ExternalImportPresenter().present(review, false, Optional.empty(), width, height),
                    "external.review.commit", "external.review.cancel");
            var editor = PresetEditorModel.open(account, Optional.of(account.presets().get(0)), Optional.empty(),
                    Optional.empty(), UiMessage::key, height, PreviewRenderer.CapeMode.CAPE);
            check(editor.present(width, height), "editor.save", "editor.cancel");
            check(editor.withName("Changed").present(width, height), "editor.save", "editor.cancel");
        }
    }

    @Test void footerMetricMatchesPinnedVanillaHalfAlignmentForOddAndEvenFrames() {
        for (int footer : List.of(32, 33, 34, 35)) for (int height : List.of(112, 113, 240, 241)) {
            int expected = Math.max(0, height - footer) + (footer - 20 + 1) / 2;
            assertEquals(expected, new ViewChromeMetrics(footer).footerRowTop(height, 20));
        }
    }

    @Test void cpmDependencyPromptCentersItsRealCancelForEveryFooterSize() {
        for (int width : List.of(240, 241, 320, 854)) for (int height : List.of(112, 113, 239, 240, 479, 480)) {
            var model = AddSourceModel.open(AccountUiPreferences.defaults(TestFixtures.ACCOUNT_ID), List.of(),
                    SkinVariant.CLASSIC, UiMessage::key).withImportContext(new AddSourceModel.ImportContext(
                    Optional.empty(), Optional.empty(), AddSourceModel.Purpose.CREATE_PRESET,
                    Optional.of(new AddSourceModel.DependencyPrompt(7))));
            for (boolean busy : List.of(false, true)) {
                var view = new AddSourcePresenter().present(model, busy, width, height);
                var cancel = view.widget("add.cpm.dependency.cancel.7").orElseThrow();
                assertEquals(height - 33 + 7, cancel.bounds().y());
                assertEquals(20, cancel.bounds().height());
                assertTrue(cancel.enabled());
                assertEquals(!busy, view.widget("add.cpm.dependency.confirm.7").orElseThrow().enabled());
                assertTrue(view.widget("add.cancel").isEmpty());
            }
        }
    }

    private static void check(ViewSpec view, String... ids) {
        var footer = view.panels().stream().filter(p -> p.style() == ViewSpec.Panel.Style.VANILLA_FOOTER)
                .map(ViewSpec.Panel::bounds).findFirst().orElseGet(() -> {
                    assertEquals("add_source", view.screenId());
                    assertFalse(view.tabGroups().isEmpty());
                    return new Bounds(0, Math.max(0, view.height() - 33), view.width(), 33);
                });
        for (String id : ids) {
            var widget = view.widget(id).orElseThrow(() -> new AssertionError(id));
            int expected = footer.y() + (footer.height() - widget.bounds().height() + 1) / 2;
            assertEquals(expected, widget.bounds().y(), view.screenId() + ":" + id);
            assertTrue(widget.bounds().y() >= footer.y());
            assertTrue(widget.bounds().bottom() <= footer.bottom());
        }
    }
}

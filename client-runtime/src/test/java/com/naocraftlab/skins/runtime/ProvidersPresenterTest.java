package com.naocraftlab.skins.runtime;

import com.naocraftlab.skins.client.OuterLayerVisibility;
import com.naocraftlab.skins.client.PreviewRenderer;
import com.naocraftlab.skins.core.model.SkinVariant;
import com.naocraftlab.skins.core.provider.AppearanceProviders;
import com.naocraftlab.skins.core.provider.BuiltinProvider;
import com.naocraftlab.skins.core.provider.ProviderCape;
import com.naocraftlab.skins.core.provider.ProviderSkin;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ProvidersPresenterTest {
    @Test void refreshLabelAndHintFollowSelectionAndCooldownForBothComponents() {
        for (var component : AppearanceProviders.Component.values()) {
            var providers = AppearanceProviders.initial();
            var all = refreshWidget(providers, component, null, false, java.util.Optional.empty(), Map.of());
            assertEquals("nclskins.providers.refresh_all", all.label().key());
            assertTrue(all.enabled());
            var selected = refreshWidget(providers, component, BuiltinProvider.MINECRAFT, false,
                    java.util.Optional.of(new ClientSnapshot.RateLimitProgress(Duration.ofSeconds(2), Duration.ofSeconds(2), 1)), Map.of());
            assertEquals("nclskins.providers.refresh", selected.label().key());
            assertEquals("nclskins.providers.refresh_rate_limited", selected.hint().orElseThrow().key());
            assertFalse(selected.enabled());
            var busy = refreshWidget(providers, component, BuiltinProvider.MINECRAFT, true, java.util.Optional.empty(), Map.of());
            assertFalse(busy.enabled());
            assertEquals("nclskins.providers.refresh", busy.hint().orElseThrow().key());
            var mixed = refreshWidget(providers, component, null, false,
                    java.util.Optional.of(new ClientSnapshot.RateLimitProgress(Duration.ofSeconds(2), Duration.ofSeconds(2), 1)), Map.of());
            assertTrue(mixed.enabled());
            var onlyMinecraft = providers.disable(component, BuiltinProvider.OFFLINE);
            var limitedAll = refreshWidget(onlyMinecraft, component, null, false,
                    java.util.Optional.of(new ClientSnapshot.RateLimitProgress(Duration.ofSeconds(2), Duration.ofSeconds(2), 1)), Map.of());
            assertFalse(limitedAll.enabled());
            assertEquals("nclskins.providers.refresh_all", limitedAll.label().key());
            assertEquals("nclskins.providers.refresh_rate_limited", limitedAll.hint().orElseThrow().key());
            assertTrue(refreshWidget(onlyMinecraft.disable(component, BuiltinProvider.MINECRAFT), component,
                    null, false, java.util.Optional.empty(), Map.of()).enabled());
        }
    }

    private ViewSpec.Widget refreshWidget(AppearanceProviders providers, AppearanceProviders.Component component,
            BuiltinProvider selected, boolean busy, java.util.Optional<ClientSnapshot.RateLimitProgress> cooldown,
            Map<BuiltinProvider, Duration> capeCooldowns) {
        return new ProvidersPresenter().present(providers, component, false, busy,
                PreviewInteractionModel.editor(240, PreviewRenderer.CapeMode.CAPE), SkinVariant.CLASSIC,
                320, 240, component == AppearanceProviders.Component.SKIN ? selected : null,
                component == AppearanceProviders.Component.CAPE ? selected : null, cooldown,
                false, null, 0, UiMessage::key, capeCooldowns).widget("providers.refresh").orElseThrow();
    }

    @Test void confirmedOrdinaryLayerControlsRespectWritableAvailabilityOnBothTabs() {
        var empty = AppearanceProviders.initial();
        for (var component : AppearanceProviders.Component.values()) for (var provider : BuiltinProvider.values()) {
            empty = empty.disable(component, provider);
        }
        var configurations = java.util.List.of(empty,
                empty.enable(AppearanceProviders.Component.CAPE, BuiltinProvider.OPTIFINE),
                empty.enable(AppearanceProviders.Component.SKIN, BuiltinProvider.OFFLINE),
                empty.enable(AppearanceProviders.Component.CAPE, BuiltinProvider.OFFLINE),
                empty.enable(AppearanceProviders.Component.SKIN, BuiltinProvider.MINECRAFT)
                        .enable(AppearanceProviders.Component.CAPE, BuiltinProvider.MINECRAFT),
                AppearanceProviders.initial());
        for (var providers : configurations) for (var component : AppearanceProviders.Component.values()) {
            for (boolean busy : java.util.List.of(false, true)) {
                var view = new ProvidersPresenter().present(providers, component, false, busy,
                        PreviewInteractionModel.editor(240, PreviewRenderer.CapeMode.CAPE), SkinVariant.CLASSIC,
                        320, 240, null, null,
                        java.util.Optional.of(new ClientSnapshot.RateLimitProgress(Duration.ofSeconds(5), Duration.ofSeconds(10), .5)),
                        false, null, 0, UiMessage::key, Map.of(BuiltinProvider.OPTIFINE, Duration.ofSeconds(10)), true, false, false, true);
                for (String group : java.util.List.of("head", "body", "legs")) {
                    var widget = view.widget("providers.outer_layer." + group);
                    assertEquals(!providers.galleryAvailable(), widget.isPresent());
                    if (widget.isPresent()) assertEquals(!busy, widget.orElseThrow().enabled());
                    assertEquals(!providers.galleryAvailable(), view.navigationNodes().stream()
                            .anyMatch(node -> node.id().equals("providers.outer_layer." + group)));
                }
            }
            var chooser = new ProvidersPresenter().present(providers, component, true, false,
                    PreviewInteractionModel.editor(240, PreviewRenderer.CapeMode.CAPE), SkinVariant.CLASSIC, 320, 240);
            assertTrue(chooser.widgets().stream().noneMatch(widget -> widget.id().startsWith("providers.outer_layer.")));
        }
    }

    @Test void restrictedProviderLayersMatchEditorForEveryMaskAndSize() {
        var restricted = AppearanceProviders.initial().disable(AppearanceProviders.Component.SKIN, BuiltinProvider.OFFLINE)
                .disable(AppearanceProviders.Component.SKIN, BuiltinProvider.MINECRAFT);
        for (int bits = 0; bits < 64; bits++) {
            var mask = OuterLayerVisibility.noneVisible();
            for (var part : com.naocraftlab.skins.client.OuterLayerPart.values()) {
                mask = mask.with(part, (bits & (1 << part.ordinal())) != 0);
            }
            for (int[] size : java.util.List.of(new int[]{200, 100}, new int[]{320, 240}, new int[]{854, 480})) {
                var transform = PreviewInteractionModel.editor(size[1], PreviewRenderer.CapeMode.ELYTRA).withOuterLayerVisibility(mask);
                var editor = PresetEditorModel.open(TestFixtures.account(0), java.util.Optional.empty(), java.util.Optional.empty(),
                        java.util.Optional.empty(), UiMessage::key, size[1], PreviewRenderer.CapeMode.ELYTRA).withPreview(transform);
                var expected = editor.present(size[0], size[1], 0, 0, ViewChromeMetrics.STANDARD, true);
                for (var component : AppearanceProviders.Component.values()) {
                    var view = new ProvidersPresenter().present(restricted, component, false, false, transform,
                            SkinVariant.CLASSIC, size[0], size[1], null, null, java.util.Optional.empty(), false, null, 0,
                            UiMessage::key, Map.of(), true, false, false, true);
                    for (String group : java.util.List.of("head", "body", "legs")) {
                        var control = view.widget("providers.outer_layer." + group).orElseThrow();
                        var reference = expected.widget("editor.outer_layer." + group).orElseThrow();
                        assertEquals(reference.bounds(), control.bounds());
                        assertEquals(reference.label(), control.label());
                        assertEquals(reference.icon(), control.icon());
                        assertEquals(reference.kind(), control.kind());
                        assertEquals(reference.hint(), control.hint());
                    }
                    assertEquals(mask, view.previews().get(0).outerLayerVisibility());
                }
            }
        }
    }

    @Test void equalCapePixelsSuppressThumbnailGlintWithoutMutatingProviderMaterial() {
        String key = "c".repeat(64);
        var local = new ProviderCape("local:cape", key, true, false);
        var remote = new ProviderCape("minecraftcapes:cape", key, true, true);
        var selected = AppearanceProviders.initial().select(1L, null, local, null);
        var providers = new AppearanceProviders(selected.skin(), selected.cape()
                .enable(BuiltinProvider.MINECRAFTCAPES).observeMinecraftcapes(remote));
        var view = new ProvidersPresenter().present(providers, AppearanceProviders.Component.CAPE,
                false, false, PreviewInteractionModel.editor(480, PreviewRenderer.CapeMode.CAPE),
                SkinVariant.CLASSIC, 854, 480, null, BuiltinProvider.MINECRAFTCAPES);
        var scrolled = new ProvidersPresenter().present(providers, AppearanceProviders.Component.CAPE,
                false, false, PreviewInteractionModel.editor(480, PreviewRenderer.CapeMode.CAPE),
                SkinVariant.CLASSIC, 854, 480, null, BuiltinProvider.MINECRAFTCAPES,
                java.util.Optional.empty(), false, null, 999, UiMessage::key, Map.of());
        var offline = view.iconDecorations().stream().filter(icon ->
                icon.id().contains("OFFLINE") && icon.providerTexture().isPresent())
                .findFirst().orElseThrow().providerTexture().orElseThrow();
        var minecraftCapes = scrolled.iconDecorations().stream().filter(icon ->
                icon.id().contains("MINECRAFTCAPES") && icon.providerTexture().isPresent())
                .findFirst().orElseThrow().providerTexture().orElseThrow();
        assertEquals(offline.cacheKey(), minecraftCapes.cacheKey());
        assertFalse(offline.capeGlint());
        assertFalse(minecraftCapes.capeGlint());
        assertTrue(view.iconDecorations().stream().filter(icon -> icon.providerTexture().isPresent())
                .allMatch(icon -> !icon.providerTexture().orElseThrow().capeGlint()));
        assertTrue(scrolled.iconDecorations().stream().filter(icon -> icon.providerTexture().isPresent())
                .allMatch(icon -> !icon.providerTexture().orElseThrow().capeGlint()));
        assertEquals("provider:cape:" + key, view.previews().get(0).capeId().orElseThrow());
        assertTrue(providers.cape().minecraftcapes().value().capeGlint());
    }

    @Test void feedbackAndResizeRetainLiteralProviderViewportAndPointerBoundaries() {
        var presenter = new ProvidersPresenter();
        for (var component : AppearanceProviders.Component.values()) {
            for (int width : new int[]{200, 854}) {
                for (int height : new int[]{150, 191}) {
                    for (boolean feedback : new boolean[]{false, true}) {
                        var view = presenter.present(AppearanceProviders.initial(), component, false, false,
                                PreviewInteractionModel.editor(height, PreviewRenderer.CapeMode.CAPE),
                                SkinVariant.CLASSIC, width, height, BuiltinProvider.OFFLINE, BuiltinProvider.OFFLINE,
                                java.util.Optional.empty(), false,
                                feedback ? UiMessage.literal("x", UiMessage.Severity.INFO) : null,
                                10, UiMessage::key);
                        int left = width == 200 ? 108 : 435;
                        int rowWidth = width == 200 ? 84 : 411;
                        int bottom = height - (feedback && component == AppearanceProviders.Component.CAPE ? 53 : 33);
                        assertEquals(new Bounds(left, 65, rowWidth, bottom - 65),
                                view.scrollSurface("providers.rows").orElseThrow().viewport());
                        assertEquals(new Bounds(0, height - 33, width, 33), view.panels().stream()
                                .filter(panel -> panel.id().equals("footer")).findFirst().orElseThrow().bounds());
                        for (var widget : view.widgets()) {
                            if (!ViewHostPolicy.belongsToProviderRow(widget.id(), "OFFLINE")) continue;
                            assertEquals(new Bounds(left, 65, rowWidth, bottom - 65), view.clipFor(widget.id()).orElseThrow());
                            assertFalse(ViewHostPolicy.pointerInsideClip(view, widget.id(), left + 2, 64));
                            assertFalse(ViewHostPolicy.pointerInsideClip(view, widget.id(), left + 2, bottom));
                            assertTrue(ViewHostPolicy.pointerInsideClip(view, widget.id(), left + 2, 65));
                        }
                        assertTrue(CatalogCardStyle.selectionSelected(view.widget("providers.row.OFFLINE").orElseThrow()));
                        assertFalse(view.widget("providers.up.OFFLINE").orElseThrow().enabled());
                        assertTrue(view.widget("providers.down.OFFLINE").orElseThrow().enabled());
                    }
                }
            }
        }
    }

    @Test void providerRowsKeepExistingInputAndFooterBehaviorAtShortHeight() {
        var providers = AppearanceProviders.initial();
        var view = new ProvidersPresenter().present(providers, AppearanceProviders.Component.SKIN,
                false, false, PreviewInteractionModel.editor(100, PreviewRenderer.CapeMode.CAPE),
                SkinVariant.CLASSIC, 200, 100, null, null, java.util.Optional.empty(),
                false, null, 999, UiMessage::key);
        var rows = view.scrollSurface("providers.rows").orElseThrow();
        assertEquals(rows.maximumPixels(), rows.offsetPixels());
        assertEquals(rows.viewport(), view.clipFor("providers.row.MINECRAFT.icon").orElseThrow());
        assertEquals("providers.row.MINECRAFT", ViewNavigationPolicy.target(view,
                "providers.row.OFFLINE", ViewSpec.NavigationCommand.DOWN).orElseThrow().id());
        assertFalse(view.widget("providers.up.OFFLINE").orElseThrow().enabled());
        assertFalse(view.widget("providers.down.MINECRAFT").orElseThrow().enabled());
        assertTrue(view.widget("providers.back").orElseThrow().bounds().y()
                >= view.panels().stream().filter(panel -> panel.id().equals("footer"))
                .findFirst().orElseThrow().bounds().y());
    }

    @Test void ordinaryProviderViewportAndTexturedRowsMeetFooterInBothTabs() {
        var providers = AppearanceProviders.initial().select(1,
                new ProviderSkin("a".repeat(64), SkinVariant.CLASSIC),
                new ProviderCape("cape", "b".repeat(64)));
        var presenter = new ProvidersPresenter();
        for (var component : AppearanceProviders.Component.values()) {
            for (int height : new int[]{150, 191}) {
                var view = presenter.present(providers, component, false, false,
                        PreviewInteractionModel.editor(height, PreviewRenderer.CapeMode.CAPE),
                        SkinVariant.CLASSIC, 200, height, null, null, java.util.Optional.empty(),
                        false, null, 999, UiMessage::key);
                var viewport = view.scrollSurface("providers.rows").orElseThrow().viewport();
                var footer = view.panels().stream().filter(panel -> panel.id().equals("footer"))
                        .findFirst().orElseThrow().bounds();
                assertEquals(footer.y(), viewport.bottom());
                assertEquals(viewport, view.clipFor("providers.row.OFFLINE.icon").orElseThrow());
                assertEquals(viewport, view.clipFor("providers.up.OFFLINE").orElseThrow());
                assertEquals(viewport, view.clipFor("providers.down.OFFLINE").orElseThrow());
                assertTrue(view.iconDecorations().stream().filter(icon ->
                        icon.ownerWidgetId().equals("providers.row.OFFLINE"))
                        .findFirst().orElseThrow().providerTexture().isPresent());
                var rows = view.scrollSurface("providers.rows").orElseThrow();
                assertEquals(rows.maximumPixels(), rows.offsetPixels());
                if (rows.maximumPixels() > 0) {
                    assertEquals(viewport.bottom(), view.widget("providers.row.MINECRAFT")
                            .orElseThrow().bounds().bottom());
                }
            }
        }
    }

    @Test void sneakyGlobeAndCapeInspectionStayAvailableWithoutWritableProviders() {
        var providers = AppearanceProviders.initial()
                .disable(AppearanceProviders.Component.CAPE, BuiltinProvider.OFFLINE)
                .disable(AppearanceProviders.Component.CAPE, BuiltinProvider.MINECRAFT)
                .enable(AppearanceProviders.Component.CAPE, BuiltinProvider.SNEAKY);
        providers = new AppearanceProviders(providers.skin(), providers.cape().observeSneaky(
                new ProviderCape("sneaky:asset", "a".repeat(64), true)));
        var presenter = new ProvidersPresenter();
        var cape = presenter.present(providers, AppearanceProviders.Component.CAPE, false, false,
                PreviewInteractionModel.editor(480, PreviewRenderer.CapeMode.CAPE),
                SkinVariant.CLASSIC, 854, 480, null, BuiltinProvider.SNEAKY);
        var globe = cape.widget("providers.account.SNEAKY").orElseThrow();
        assertEquals(GuiIcon.ACTION_OPEN_ACCOUNT, globe.icon().orElseThrow());
        assertEquals("nclskins.providers.open_sneaky_editor", globe.label().key());
        assertTrue(globe.enabled());
        assertTrue(cape.widget("providers.edit.SNEAKY").isEmpty());
        assertEquals("provider:cape:" + "a".repeat(64), cape.previews().get(0).capeId().orElseThrow());
        assertTrue(presenter.presentChooser(providers, AppearanceProviders.Component.SKIN,
                false, 854, 480, 0).widget("providers.row.SNEAKY").isEmpty());
    }

    @Test void losingSneakyRowKeepsItsOwnTextureWhileResultUsesMinecraft() {
        String sneakyKey = "a".repeat(64);
        String minecraftKey = "b".repeat(64);
        var providers = AppearanceProviders.initial()
                .enable(AppearanceProviders.Component.CAPE, BuiltinProvider.SNEAKY);
        providers = new AppearanceProviders(providers.skin(), providers.cape()
                .observeMinecraft(new ProviderCape("official", minecraftKey, false))
                .observeSneaky(new ProviderCape("sneaky:asset", sneakyKey, true)));
        var presenter = new ProvidersPresenter();
        var transform = PreviewInteractionModel.editor(480, PreviewRenderer.CapeMode.CAPE);
        var result = presenter.present(providers, AppearanceProviders.Component.CAPE, false, false,
                transform, SkinVariant.CLASSIC, 854, 480, null, null);
        var inspected = presenter.present(providers, AppearanceProviders.Component.CAPE, false, false,
                transform, SkinVariant.CLASSIC, 854, 480, null, BuiltinProvider.SNEAKY);
        assertEquals("provider:cape:" + minecraftKey, result.previews().get(0).capeId().orElseThrow());
        assertEquals("provider:cape:" + sneakyKey, inspected.previews().get(0).capeId().orElseThrow());
    }
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
                if (component == AppearanceProviders.Component.SKIN && !provider.supportsSkin()
                        || component == AppearanceProviders.Component.CAPE && !provider.supportsCape()) continue;
                if (!provider.canWrite(component)) continue;
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

    @Test void readOnlyOptifineCapeHasIndependentGlobeAndNoSkinOrGalleryEdit() {
        var providers = AppearanceProviders.initial()
                .disable(AppearanceProviders.Component.CAPE, BuiltinProvider.OFFLINE)
                .disable(AppearanceProviders.Component.CAPE, BuiltinProvider.MINECRAFT)
                .enable(AppearanceProviders.Component.CAPE, BuiltinProvider.OPTIFINE);
        assertFalse(providers.galleryAvailable());
        var presenter = new ProvidersPresenter();
        var cape = presenter.present(providers, AppearanceProviders.Component.CAPE, false, false,
                PreviewInteractionModel.editor(480, PreviewRenderer.CapeMode.CAPE), SkinVariant.CLASSIC,
                854, 480, null, null, java.util.Optional.empty(), false,
                UiMessage.info("nclskins.providers.link_preparing"));
        var globe = cape.widget("providers.account.OPTIFINE").orElseThrow();
        assertEquals(GuiIcon.ACTION_OPEN_ACCOUNT, globe.icon().orElseThrow());
        assertEquals(20, globe.bounds().width());
        assertEquals("nclskins.providers.open_account", globe.label().key());
        assertTrue(globe.enabled());
        assertTrue(cape.widget("providers.edit.OPTIFINE").isEmpty());
        assertEquals("nclskins.providers.link_preparing", cape.texts().stream()
                .filter(text -> text.id().equals("providers.account.feedback"))
                .findFirst().orElseThrow().message().key());
        var skinChooser = presenter.presentChooser(providers, AppearanceProviders.Component.SKIN, false, 320, 100, 0);
        assertTrue(skinChooser.widget("providers.row.OPTIFINE").isEmpty());
        var capeChooser = presenter.presentChooser(providers, AppearanceProviders.Component.CAPE, false, 320, 100, 0);
        assertFalse(capeChooser.widget("providers.row.OPTIFINE").orElseThrow().enabled());
    }

    @Test void skinMcCapeRowKeepsAccountActionPreviewAndKeyboardAtNarrowWidth() {
        var initial = AppearanceProviders.initial().enable(
                AppearanceProviders.Component.CAPE, BuiltinProvider.SKINMC);
        var absent = new AppearanceProviders(initial.skin(), initial.cape().observeSkinmc(null));
        var presenter = new ProvidersPresenter();
        var empty = presenter.present(absent, AppearanceProviders.Component.CAPE, false, false,
                PreviewInteractionModel.editor(191, PreviewRenderer.CapeMode.CAPE),
                SkinVariant.CLASSIC, 200, 191, null, BuiltinProvider.SKINMC);
        var row = empty.widget("providers.row.SKINMC").orElseThrow();
        var account = empty.widget("providers.account.SKINMC").orElseThrow();
        assertEquals("nclskins.providers.skinmc", row.label().key());
        assertEquals(GuiIcon.ACTION_OPEN_ACCOUNT, account.icon().orElseThrow());
        assertEquals("nclskins.providers.open_account", account.label().key());
        assertTrue(empty.widget("providers.edit.SKINMC").isEmpty());
        assertEquals(row.bounds().right() - 28, account.bounds().right());
        assertTrue(empty.clipRegions().stream().anyMatch(region ->
                region.id().equals("providers.rows") && region.matches(account.id())));
        assertEquals("providers.capability.SKINMC.distribution", ViewNavigationPolicy.target(empty,
                "providers.up.SKINMC", ViewSpec.NavigationCommand.RIGHT).orElseThrow().id());
        assertEquals("providers.account.SKINMC", ViewNavigationPolicy.target(empty,
                "providers.capability.SKINMC.distribution", ViewSpec.NavigationCommand.RIGHT).orElseThrow().id());
        assertTrue(empty.iconDecorations().stream().filter(icon ->
                icon.ownerWidgetId().equals(row.id())).findFirst().orElseThrow()
                .providerTexture().isEmpty());

        var observed = new AppearanceProviders(initial.skin(), initial.cape().observeSkinmc(
                new ProviderCape("cape", "b".repeat(64), true)));
        var populated = presenter.present(observed, AppearanceProviders.Component.CAPE, false, false,
                PreviewInteractionModel.editor(480, PreviewRenderer.CapeMode.ELYTRA),
                SkinVariant.CLASSIC, 854, 480, null, BuiltinProvider.SKINMC);
        assertTrue(populated.previews().get(0).capeId().orElseThrow().contains("b".repeat(64)));
        assertTrue(populated.widget("providers.preview_mode").isPresent());
        assertTrue(presenter.present(observed, AppearanceProviders.Component.SKIN, false, false,
                PreviewInteractionModel.editor(480, PreviewRenderer.CapeMode.CAPE),
                SkinVariant.CLASSIC, 854, 480).widget("providers.row.SKINMC").isEmpty());
    }

    @Test void minecraftCapesUsesCapeChooserAccountCooldownAndOwnInspection() {
        var initial = AppearanceProviders.initial().enable(
                AppearanceProviders.Component.CAPE, BuiltinProvider.MINECRAFTCAPES);
        String key = "c".repeat(64);
        var providers = new AppearanceProviders(initial.skin(), initial.cape().observeMinecraftcapes(
                new ProviderCape("minecraftcapes:fixture", key, false)));
        var presenter = new ProvidersPresenter();
        var skinChooser = presenter.presentChooser(providers, AppearanceProviders.Component.SKIN,
                false, 200, 100, 0);
        assertTrue(skinChooser.widget("providers.row.MINECRAFTCAPES").isEmpty());
        var capeChooser = presenter.presentChooser(providers, AppearanceProviders.Component.CAPE,
                false, 200, 100, 0);
        assertFalse(capeChooser.widget("providers.row.MINECRAFTCAPES").orElseThrow().enabled());

        var view = presenter.present(providers, AppearanceProviders.Component.CAPE, false, false,
                PreviewInteractionModel.editor(191, PreviewRenderer.CapeMode.CAPE),
                SkinVariant.CLASSIC, 200, 191, null, BuiltinProvider.MINECRAFTCAPES,
                java.util.Optional.empty(), false, null, 0, UiMessage::key,
                Map.of(BuiltinProvider.MINECRAFTCAPES, Duration.ofSeconds(61)));
        var row = view.widget("providers.row.MINECRAFTCAPES").orElseThrow();
        assertEquals("nclskins.providers.minecraftcapes", row.label().key());
        assertEquals("provider:cape:" + key, view.previews().get(0).capeId().orElseThrow());
        assertEquals("nclskins.providers.open_account", view.widget("providers.account.MINECRAFTCAPES")
                .orElseThrow().label().key());
        assertTrue(view.widget("providers.edit.MINECRAFTCAPES").isEmpty());
        assertEquals("nclskins.providers.capability.distribution.cape.description",
                view.widget("providers.capability.MINECRAFTCAPES.distribution")
                        .orElseThrow().hint().orElseThrow().key());
        assertEquals("nclskins.providers.cooldown", view.texts().stream()
                .filter(text -> text.id().equals("providers.row.MINECRAFTCAPES.cooldown"))
                .findFirst().orElseThrow().message().key());
        assertEquals("providers.capability.MINECRAFTCAPES.hd", ViewNavigationPolicy.target(view,
                "providers.capability.MINECRAFTCAPES.distribution",
                ViewSpec.NavigationCommand.RIGHT).orElseThrow().id());
        assertEquals(GuiIcon.STATUS_PROVIDER_CAPABILITY_HD,
                view.widget("providers.capability.MINECRAFTCAPES.hd").orElseThrow().icon().orElseThrow());
        assertEquals(GuiIcon.STATUS_PROVIDER_CAPABILITY_ANIMATION,
                view.widget("providers.capability.MINECRAFTCAPES.animation").orElseThrow().icon().orElseThrow());
        assertTrue(view.widget("providers.capability.MINECRAFTCAPES.animation").orElseThrow()
                .bounds().right() <= row.bounds().right());
    }

    @Test void narrowWrappedFeedbackReservesRowsViewportAndRowsScroll() {
        var providers = AppearanceProviders.initial()
                .enable(AppearanceProviders.Component.CAPE, BuiltinProvider.OPTIFINE);
        UiMessage feedback = UiMessage.error("nclskins.providers.link_failed");
        TextResolver measured = TextResolver.withLayout(UiMessage::key, (message, width) -> 36);
        var presenter = new ProvidersPresenter();
        var first = presenter.present(providers, AppearanceProviders.Component.CAPE, false, false,
                PreviewInteractionModel.editor(191, PreviewRenderer.CapeMode.CAPE), SkinVariant.CLASSIC,
                200, 191, null, null, java.util.Optional.empty(), false, feedback, 0, measured);
        var text = first.texts().stream().filter(value -> value.id().equals("providers.account.feedback"))
                .findFirst().orElseThrow();
        var rows = first.scrollSurface("providers.rows").orElseThrow();
        assertEquals(ViewSpec.Text.Layout.WRAP, text.layout());
        assertEquals(36, text.bounds().height());
        assertEquals(text.bounds().y() - 4, rows.viewport().bottom());
        assertEquals(rows.viewport(), first.clipFor("providers.row.OPTIFINE.icon").orElseThrow());
        assertTrue(text.bounds().bottom() < first.widget("providers.back").orElseThrow().bounds().y());
        assertTrue(rows.maximumPixels() > 0);
        assertTrue(first.clipRegions().stream().anyMatch(region -> region.id().equals("providers.rows")
                && region.matches("providers.row.OPTIFINE")));
        var last = presenter.present(providers, AppearanceProviders.Component.CAPE, false, false,
                PreviewInteractionModel.editor(191, PreviewRenderer.CapeMode.CAPE), SkinVariant.CLASSIC,
                200, 191, null, null, java.util.Optional.empty(), false, feedback, 999, measured);
        assertEquals(last.scrollSurface("providers.rows").orElseThrow().maximumPixels(),
                last.scrollSurface("providers.rows").orElseThrow().offsetPixels());
        assertTrue(last.widget("providers.row.OPTIFINE").orElseThrow().bounds().bottom()
                <= last.scrollSurface("providers.rows").orElseThrow().viewport().bottom());
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

    @Test void providerIndicatorsFollowTheDomainMatrixAndChooserHasNone() {
        var providers = AppearanceProviders.initial()
                .enable(AppearanceProviders.Component.CAPE, BuiltinProvider.OPTIFINE)
                .enable(AppearanceProviders.Component.CAPE, BuiltinProvider.SKINMC)
                .enable(AppearanceProviders.Component.CAPE, BuiltinProvider.SNEAKY);
        var presenter = new ProvidersPresenter();
        var transform = PreviewInteractionModel.editor(480, PreviewRenderer.CapeMode.CAPE);
        var skin = presenter.present(providers, AppearanceProviders.Component.SKIN, false, false,
                transform, SkinVariant.CLASSIC, 854, 480);
        assertEquals(java.util.List.of("providers.capability.OFFLINE.write",
                        "providers.capability.MINECRAFT.write", "providers.capability.MINECRAFT.distribution"),
                capabilityIds(skin));
        var cape = presenter.present(providers, AppearanceProviders.Component.CAPE, false, false,
                transform, SkinVariant.CLASSIC, 854, 480);
        assertEquals(java.util.List.of("providers.capability.OFFLINE.write",
                        "providers.capability.OFFLINE.hd", "providers.capability.OFFLINE.animation",
                        "providers.capability.MINECRAFT.limited_write", "providers.capability.MINECRAFT.distribution",
                        "providers.capability.OPTIFINE.distribution", "providers.capability.SKINMC.distribution",
                        "providers.capability.SNEAKY.distribution"), capabilityIds(cape));
        var first = cape.widget("providers.capability.MINECRAFT.limited_write").orElseThrow();
        var second = cape.widget("providers.capability.MINECRAFT.distribution").orElseThrow();
        var offlineHd = cape.widget("providers.capability.OFFLINE.hd").orElseThrow();
        var offlineAnimation = cape.widget("providers.capability.OFFLINE.animation").orElseThrow();
        assertEquals(GuiIcon.STATUS_PROVIDER_CAPABILITY_HD, offlineHd.icon().orElseThrow());
        assertEquals(GuiIcon.STATUS_PROVIDER_CAPABILITY_ANIMATION,
                offlineAnimation.icon().orElseThrow());
        assertEquals("nclskins.providers.capability.hd.title",
                offlineHd.hint().orElseThrow().key());
        assertEquals("nclskins.providers.capability.animation.title",
                offlineAnimation.hint().orElseThrow().key());
        assertEquals("providers.capability.OFFLINE.hd", ViewNavigationPolicy.target(cape,
                "providers.capability.OFFLINE.write", ViewSpec.NavigationCommand.RIGHT)
                .orElseThrow().id());
        assertTrue(offlineAnimation.bounds().right()
                <= cape.widget("providers.row.OFFLINE").orElseThrow().bounds().right());
        var row = cape.widget("providers.row.MINECRAFT").orElseThrow();
        assertEquals(row.bounds().x() + 38, first.bounds().x());
        assertEquals(row.bounds().y() + 14, first.bounds().y());
        assertEquals("nclskins.providers.capability.limited_write.cape.description",
                first.hint().orElseThrow().key());
        assertEquals(first.bounds().right(), second.bounds().x());
        assertEquals(20, first.bounds().width());
        assertEquals(20, first.bounds().height());
        assertEquals(cape.scrollSurface("providers.rows").orElseThrow().viewport(),
                cape.clipFor(first.id()).orElseThrow());
        assertTrue(ViewNavigationPolicy.activationAction(cape, first.id()).isEmpty());
        assertEquals(java.util.List.of(), capabilityIds(presenter.presentChooser(providers,
                AppearanceProviders.Component.CAPE, false, 854, 480, 0)));
    }

    @Test void providerNameMarqueeUsesOnlyItsClippedNameBounds() {
        var view = new ProvidersPresenter().present(AppearanceProviders.initial(),
                AppearanceProviders.Component.SKIN, false, false,
                PreviewInteractionModel.editor(240, PreviewRenderer.CapeMode.CAPE),
                SkinVariant.CLASSIC, 320, 240);
        var name = view.texts().stream().filter(text -> text.id().equals("providers.row.OFFLINE.name"))
                .findFirst().orElseThrow();
        assertEquals(name.bounds(), name.marqueeActivation().orElseThrow().hoverBounds());
        assertEquals(view.scrollSurface("providers.rows").orElseThrow().viewport(),
                view.clipFor(name.id()).orElseThrow());
        assertTrue(MarqueeRouting.active(view, name,
                name.bounds().x(), name.bounds().y(), ignored -> false));
        assertFalse(MarqueeRouting.active(view, name,
                name.bounds().right(), name.bounds().y(), ignored -> false));
    }

    @Test void narrowRowsMoveActionsBelowTheFixedCapabilityHeader() {
        var providers = AppearanceProviders.initial();
        var presenter = new ProvidersPresenter();
        var progress = java.util.Optional.of(new ClientSnapshot.RateLimitProgress(
                java.time.Duration.ofSeconds(30), java.time.Duration.ofSeconds(60), 0.5));
        var view = presenter.present(providers, AppearanceProviders.Component.CAPE, false, false,
                PreviewInteractionModel.editor(191, PreviewRenderer.CapeMode.CAPE), SkinVariant.CLASSIC,
                200, 191, null, null, progress, false, null, 0, UiMessage::key,
                java.util.Map.of(BuiltinProvider.MINECRAFT, java.time.Duration.ofSeconds(8)));
        var row = view.widget("providers.row.MINECRAFT").orElseThrow().bounds();
        var indicator = view.widget("providers.capability.MINECRAFT.distribution").orElseThrow().bounds();
        var edit = view.widget("providers.edit.MINECRAFT").orElseThrow().bounds();
        var explanation = view.texts().stream().filter(text -> text.id().equals("providers.row.MINECRAFT.cooldown"))
                .findFirst().orElseThrow().bounds();
        assertEquals(row.y() + 34, indicator.bottom());
        assertTrue(edit.y() >= explanation.bottom());
        assertTrue(edit.y() >= indicator.bottom());
        assertEquals(row.y() + 34, view.progressDecorations().get(0).bottomY(row));
    }

    private static java.util.List<String> capabilityIds(ViewSpec view) {
        return view.widgets().stream().filter(widget -> widget.kind() == ViewSpec.WidgetKind.PASSIVE_INDICATOR)
                .map(ViewSpec.Widget::id).toList();
    }

    @Test void publicCapeCooldownsUseSeparateWrappedRowsAndDoNotAlterMinecraftProgress() {
        var providers = AppearanceProviders.initial()
                .enable(AppearanceProviders.Component.CAPE, BuiltinProvider.OPTIFINE)
                .enable(AppearanceProviders.Component.CAPE, BuiltinProvider.SKINMC);
        var presenter = new ProvidersPresenter();
        var remaining = java.util.Map.of(BuiltinProvider.OPTIFINE, java.time.Duration.ofSeconds(61),
                BuiltinProvider.SKINMC, java.time.Duration.ofMillis(1500));
        var view = presenter.present(providers, AppearanceProviders.Component.CAPE, false, false,
                PreviewInteractionModel.editor(191, PreviewRenderer.CapeMode.CAPE), SkinVariant.CLASSIC,
                200, 191, null, null, java.util.Optional.empty(), false, null, 0,
                UiMessage::key, remaining);
        var optifine = view.texts().stream().filter(text -> text.id().equals("providers.row.OPTIFINE.cooldown"))
                .findFirst().orElseThrow();
        var skinmc = view.texts().stream().filter(text -> text.id().equals("providers.row.SKINMC.cooldown"))
                .findFirst().orElseThrow();
        assertEquals("nclskins.providers.cooldown", optifine.message().key());
        assertEquals("1:01", optifine.message().arguments().get(0));
        assertEquals("0:02", skinmc.message().arguments().get(0));
        assertEquals(ViewSpec.Text.Layout.WRAP, skinmc.layout());
        assertTrue(view.widget("providers.row.OPTIFINE").orElseThrow().bounds().height() > 36);
        assertTrue(view.widget("providers.row.SKINMC").orElseThrow().bounds().height() > 36);
        assertTrue(view.clipRegions().stream().anyMatch(region ->
                region.id().equals("providers.rows") && region.matches(skinmc.id())));
        assertTrue(view.progressDecorations().isEmpty());
        var skins = presenter.present(providers, AppearanceProviders.Component.SKIN, false, false,
                PreviewInteractionModel.editor(191, PreviewRenderer.CapeMode.CAPE), SkinVariant.CLASSIC,
                200, 191, null, null, java.util.Optional.empty(), false, null, 0,
                UiMessage::key, remaining);
        assertTrue(skins.texts().stream().noneMatch(text -> text.id().endsWith(".cooldown")));
    }

    @Test void keyboardTraversalStaysWithinRowsAndSkipsDisabledActions() {
        var view = view(AppearanceProviders.initial(), "", false);
        assertEquals("providers.row.MINECRAFT", ViewNavigationPolicy.target(view, "providers.row.OFFLINE", ViewSpec.NavigationCommand.DOWN).orElseThrow().id());
        assertEquals("providers.down.OFFLINE", ViewNavigationPolicy.target(view, "providers.row.OFFLINE", ViewSpec.NavigationCommand.RIGHT).orElseThrow().id());
        assertEquals("providers.capability.OFFLINE.write", ViewNavigationPolicy.target(view, "providers.down.OFFLINE", ViewSpec.NavigationCommand.RIGHT).orElseThrow().id());
        assertEquals("providers.edit.OFFLINE", ViewNavigationPolicy.target(view, "providers.capability.OFFLINE.write", ViewSpec.NavigationCommand.RIGHT).orElseThrow().id());
        assertEquals("providers.edit.OFFLINE", ViewNavigationPolicy.target(view, "providers.remove.OFFLINE", ViewSpec.NavigationCommand.LEFT).orElseThrow().id());
        assertEquals("providers.row.MINECRAFT", ViewNavigationPolicy.target(view, "providers.remove.OFFLINE", ViewSpec.NavigationCommand.DOWN).orElseThrow().id());
        assertEquals("providers.up.MINECRAFT", ViewNavigationPolicy.target(view, "providers.row.MINECRAFT", ViewSpec.NavigationCommand.TAB_FORWARD).orElseThrow().id());
        assertEquals("providers.capability.MINECRAFT.write", ViewNavigationPolicy.target(view, "providers.up.MINECRAFT", ViewSpec.NavigationCommand.TAB_FORWARD).orElseThrow().id());
        assertEquals("providers.capability.MINECRAFT.distribution", ViewNavigationPolicy.target(view, "providers.capability.MINECRAFT.write", ViewSpec.NavigationCommand.TAB_FORWARD).orElseThrow().id());
        assertEquals("providers.edit.MINECRAFT", ViewNavigationPolicy.target(view, "providers.capability.MINECRAFT.distribution", ViewSpec.NavigationCommand.TAB_FORWARD).orElseThrow().id());
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

package com.naocraftlab.skins.runtime;

import com.naocraftlab.skins.client.PreviewRenderer;
import com.naocraftlab.skins.core.api.ApiFailureKind;
import com.naocraftlab.skins.core.compatibility.SkinConflictReason;
import com.naocraftlab.skins.core.compatibility.SkinConsumer;
import com.naocraftlab.skins.core.compatibility.SkinConsumerState;
import com.naocraftlab.skins.core.compatibility.SkinExtensionEnvironment;
import com.naocraftlab.skins.core.compatibility.SkinFeatureEvidence;
import com.naocraftlab.skins.core.model.AccountState;
import com.naocraftlab.skins.core.model.AppearanceSyncStatus;
import com.naocraftlab.skins.core.model.SkinVariant;
import com.naocraftlab.skins.core.service.SessionCheckPhase;
import com.naocraftlab.skins.core.service.SessionFailureContext;
import com.naocraftlab.skins.core.service.SessionStatus;
import com.naocraftlab.skins.core.service.SessionValidation;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class GalleryPresenterTest {
    private final GalleryPresenter presenter = new GalleryPresenter();

    @Test void capeGridKeepsCenteredStandardCellsAndMarksSourceNotPixels() {
        var account = TestFixtures.account(1);
        var snapshot = TestFixtures.ready(account,account.presets().get(0).id(),0);
        var draft = draft(snapshot.activePresetId(),PreviewRenderer.DisplayKind.ORDINARY);
        for (int height : List.of(240,480,720)) for (int count : List.of(0,1,2,3,4,5,9)) {
            var choices = java.util.stream.IntStream.range(0,count).mapToObj(i -> new GalleryPresenter.CapeChoice(
                    "source"+i,UiMessage.literal("Cape",UiMessage.Severity.INFO),Optional.of("same-pixels"),true)).toList();
            var controls = new GalleryPresenter.ControlsInput(true,com.naocraftlab.skins.client.OuterLayerVisibility.allVisible(),
                    PreviewInteractionModel.gallery(),Optional.empty(),choices,0,Optional.empty());
            var view = presenter.present(snapshot,854,height,0,0,PreviewRenderer.CapeMode.CAPE,SkinVariant.CLASSIC,
                    "",Optional.empty(),0,null,draft,controls);
            var cells = view.widgets().stream().filter(w -> w.id().startsWith("gallery.inspect.")).toList();
            int width = view.scrollSurface("gallery.stage").orElseThrow().viewport().width()-16;
            int columns = Math.max(1,(width+2)/34);
            int expected = 32;
            for (int i=0;i<cells.size();i++) {
                var cell = cells.get(i);
                assertEquals(expected,cell.bounds().width()); assertEquals(expected,cell.bounds().height());
                int rowCount=Math.min(columns,count-(i/columns)*columns);
                assertEquals(12+(width-(rowCount*34-2))/2+(i%columns)*34,cell.bounds().x());
                assertEquals(i==0?Optional.of("active"):Optional.empty(),cell.value(),"provenance, not shared PNG");
                assertTrue(ViewHostPolicy.cardFrameSegments(cell,false).stream().allMatch(s -> s.color()==0xFF80C880));
            }
            if (count>1) {
                var inspect = new GalleryPresenter.DraftInput(draft.selectedPresetId(),draft.baselineMask(),draft.draftMask(),draft.generation(),
                        Optional.of("source1"),draft.collapsedSections(),draft.temporarilyRevealedSection(),draft.displayedBodyKind());
                var inspected = presenter.present(snapshot,854,height,0,0,PreviewRenderer.CapeMode.CAPE,SkinVariant.CLASSIC,
                        "",Optional.empty(),0,null,inspect,controls);
                assertEquals(Optional.empty(),inspected.widget("gallery.inspect.source0").orElseThrow().value());
                assertEquals(Optional.of("active"),inspected.widget("gallery.inspect.source1").orElseThrow().value());
            }
        }
    }

    @Test
    void leftColumnKeepsThreeLayersAndCenteredHeightDerivedPortraitFit() {
        var account = TestFixtures.account(3);
        var s = TestFixtures.ready(account, account.presets().get(0).id(), 0);
        Bounds previous = null;
        for (int[] size : List.of(new int[]{854,480},new int[]{1200,480},new int[]{320,240},new int[]{240,150},new int[]{120,480})) {
            var d = draft(s.activePresetId(),PreviewRenderer.DisplayKind.ORDINARY);
            var c = new GalleryPresenter.ControlsInput(true,com.naocraftlab.skins.client.OuterLayerVisibility.allVisible(),
                    PreviewInteractionModel.gallery(),Optional.empty(),List.of(),0,Optional.empty());
            var v = presenter.present(s,size[0],size[1],0,0,PreviewRenderer.CapeMode.CAPE,SkinVariant.CLASSIC,
                    "",Optional.empty(),0,null,d,c);
            var viewport = v.scrollSurface("gallery.stage").orElseThrow().viewport();
            var anchor = v.previews().get(0).anchorBounds();
            int oldLeft = Math.min(Math.max(64,(size[1]-73)/4)+6,Math.max(70,size[0]-60));
            int expandedLeft = Math.min((int)Math.round(oldLeft*4.0/3),Math.max(70,size[0]-60));
            assertEquals(expandedLeft, v.panels().stream().filter(p -> p.id().equals("gallery.workspace")).findFirst().orElseThrow().bounds().x());
            assertEquals(anchor.width()*2,anchor.height());
            assertEquals(Math.max(1,Math.min((size[1]-73)/4,oldLeft-6)),anchor.width(),"larger area retains the old fit");
            assertTrue(Math.abs((viewport.x()*2+viewport.width())-(anchor.x()*2+anchor.width())) <= 1);
            assertTrue(anchor.x()>=viewport.x() && anchor.right()<=viewport.right());
            var buttons = v.widgets().stream().filter(w -> w.id().startsWith("gallery.outer_layer.")).toList();
            assertEquals(3,buttons.size());
            assertEquals(buttons.get(0).bounds().y(),buttons.get(2).bounds().y(),"layers must not wrap");
            assertEquals(2,buttons.get(1).bounds().x()-buttons.get(0).bounds().right());
            assertTrue(Math.abs(buttons.get(0).bounds().x()+buttons.get(2).bounds().right()-viewport.x()-viewport.right())<=1);
            assertEquals(viewport.y()+18,buttons.get(0).bounds().y());
            var header = v.texts().stream().filter(t -> t.id().equals("gallery.layers")).findFirst().orElseThrow();
            var headerGeometry = CollectionHeaderStyle.geometry(header.bounds(),9,16);
            assertEquals(viewport.x()+8,headerGeometry.textX());
            assertEquals(viewport.right()-8,headerGeometry.lineEndExclusive());
            var workspace = v.panels().stream().filter(p -> p.id().equals("gallery.workspace")).findFirst().orElseThrow().bounds();
            assertEquals(8,v.widget("gallery.add").orElseThrow().bounds().x()-workspace.x());
            assertEquals(4,v.widget("gallery.add").orElseThrow().bounds().y()-workspace.y());
            assertEquals(8,size[0]-v.scrollSurface("gallery.cards").orElseThrow().viewport().right());
            assertTrue(buttons.get(2).bounds().right()<=viewport.right());
            if (size[0]==1200) assertEquals(previous,anchor,"width-only change must not grow height-derived fit");
            previous=anchor;
            var pending = presenter.present(s,size[0],size[1],0,0,PreviewRenderer.CapeMode.CAPE,SkinVariant.CLASSIC,
                    "",Optional.empty(),0,null,draft(s.activePresetId(),PreviewRenderer.DisplayKind.UNCONFIRMED),c);
            assertEquals(anchor,pending.previews().get(0).anchorBounds());
            assertEquals(viewport,pending.scrollSurface("gallery.stage").orElseThrow().viewport());
        }
    }

    @Test
    void galleryCardNamebarIsTranslucentAndPermanentMoreActionIsTransparent() {
        var account = TestFixtures.account(3);
        var s = TestFixtures.ready(account, account.presets().get(0).id(), 0);
        var view = presenter.present(s, 854, 480, 0, 0, PreviewRenderer.CapeMode.CAPE);
        var more = view.widgets().stream().filter(w -> w.id().endsWith(".more")).findFirst().orElseThrow();
        assertEquals("TRANSPARENT_BUTTON", more.kind().name());
        assertTrue(view.panels().stream().filter(p -> p.id().endsWith(".namebar"))
                .allMatch(p -> p.style().name().equals("TRANSLUCENT_NAMEBAR")));
        var workspace = view.panels().stream().filter(p -> p.id().equals("gallery.workspace")).findFirst().orElseThrow();
        assertEquals(ViewSpec.Panel.Style.VANILLA_TAB_CONTENT, workspace.style());
        assertEquals(33,workspace.bounds().y());assertEquals(447,workspace.bounds().bottom());
        assertTrue(view.tabGroups().isEmpty());
        assertEquals(GuiIcon.ACTION_ADD_LOOK,view.widget("gallery.add").orElseThrow().icon().orElseThrow());
    }


    @Test
    void confirmedGeometryOnlyAndElytraOnlyAccessoriesEnableMainToggleWithoutLeakingToCards() {
        var account = TestFixtures.account(3);
        var s = TestFixtures.ready(account, account.presets().get(0).id(), 0);
        s = new ClientSnapshot(s.lifecycle(),s.account(),s.session(),s.remoteProfile(),s.lastMutation(),s.selectedSkinId(),
                s.selectedPresetId(),s.selectedCapeId(),s.currentOfficialSkinId(),s.activePresetId(),s.editor(),s.addSource(),
                s.status(),s.busy(),s.rateLimited(),s.rateLimitProgress(),s.capeProviderCooldowns(),s.galleryOffset(),s.generation(),
                s.intentRevision(),s.syncStatus(),s.syncInProgress(),s.sessionActivity(),s.skinExtensionEnvironment(),s.assetEvidence(),
                s.catalogEvidence(),s.hideIncompatibleCatalogSkins(),s.hideIncompatibleGalleryLooks(),s.providers().enable(
                        com.naocraftlab.skins.core.provider.AppearanceProviders.Component.CAPE,
                        com.naocraftlab.skins.core.provider.BuiltinProvider.CPM));
        var d = draft(s.activePresetId(), PreviewRenderer.DisplayKind.ORDINARY);
        for (boolean cape : List.of(true, false)) {
            var accessories = new com.naocraftlab.skins.client.PreviewAccessories(UUID.randomUUID(), 1,
                    com.naocraftlab.skins.client.PreviewAccessories.Availability.CONFIRMED,
                    cape, !cape, PreviewRenderer.LiveReason.AVAILABLE);
            var c = new GalleryPresenter.ControlsInput(true,
                    com.naocraftlab.skins.client.OuterLayerVisibility.allVisible(), PreviewInteractionModel.gallery(),
                    Optional.empty(), List.of(), 0, Optional.empty(), accessories);
            var view = presenter.present(s,854,480,0,0,PreviewRenderer.CapeMode.CAPE,SkinVariant.CLASSIC,
                    "",Optional.empty(),0,null,d,c);
            assertTrue(view.widget("gallery.preview_mode").isPresent());
            assertTrue(view.previews().get(0).nativeAccessories());
            assertTrue(view.previews().stream().skip(1).noneMatch(ViewSpec.Preview::nativeAccessories));
            var header = view.texts().stream().filter(t -> t.id().equals("gallery.layers")).findFirst().orElseThrow();
            assertEquals("COLLECTION_HEADER", header.layout().name());
            assertTrue(header.marqueeActivation().isEmpty());
            assertTrue(view.panels().stream().noneMatch(p -> p.id().equals("gallery.layers.divider")));
            assertTrue(view.widget(header.id()).isEmpty());
            assertTrue(view.navigationNodes().stream().noneMatch(n -> n.id().equals(header.id())));
            assertTrue(view.tooltipRegions().stream().noneMatch(t -> t.id().equals(header.id())));
        }
    }

    @Test
    void observedNativeAvailabilityNeverReplacesSelectedPresetCapeTexture() {
        var account = TestFixtures.account(2);
        var selected = account.presets().get(1);
        var s = TestFixtures.ready(account, selected.id(), 0);
        var d = draft(s.activePresetId(), PreviewRenderer.DisplayKind.ORDINARY);
        var baseline = presenter.present(s,854,480,0,0,PreviewRenderer.CapeMode.CAPE).previews().get(0);
        assertEquals(selected.optionalCapeId(), baseline.capeId());
        var accessories = new com.naocraftlab.skins.client.PreviewAccessories(UUID.randomUUID(), 1,
                com.naocraftlab.skins.client.PreviewAccessories.Availability.CONFIRMED,
                true, true, PreviewRenderer.LiveReason.AVAILABLE);
        var c = new GalleryPresenter.ControlsInput(true,
                com.naocraftlab.skins.client.OuterLayerVisibility.allVisible(), PreviewInteractionModel.gallery(),
                Optional.empty(), List.of(), 0, Optional.empty(), accessories);
        var preview = presenter.present(s,854,480,0,0,PreviewRenderer.CapeMode.CAPE,SkinVariant.CLASSIC,
                "",Optional.empty(),0,null,d,c).previews().get(0);
        assertEquals(selected.optionalCapeId(), preview.capeId());
        assertFalse(preview.nativeAccessories());
    }

    @Test
    void inspectedCapeCarriesItsOwnElytraAdmission() {
        var account = TestFixtures.account(1);
        var s = TestFixtures.ready(account, account.presets().get(0).id(), 0);
        var base = draft(s.activePresetId(), PreviewRenderer.DisplayKind.ORDINARY);
        var d = new GalleryPresenter.DraftInput(base.selectedPresetId(), base.baselineMask(), base.draftMask(),
                base.generation(), Optional.of("choice"), base.collapsedSections(), base.temporarilyRevealedSection(), base.displayedBodyKind());
        var c = new GalleryPresenter.ControlsInput(true,
                com.naocraftlab.skins.client.OuterLayerVisibility.allVisible(), PreviewInteractionModel.gallery(),
                Optional.empty(), List.of(new GalleryPresenter.CapeChoice("choice", UiMessage.info("provider"),
                        Optional.of("provider:cape:" + "c".repeat(64)), false)), 0, Optional.empty());
        var view = presenter.present(s,854,480,0,0,PreviewRenderer.CapeMode.CAPE,SkinVariant.CLASSIC,
                "",Optional.empty(),0,null,d,c);
        var header = view.texts().stream().filter(t -> t.id().equals("gallery.capes")).findFirst().orElseThrow();
        assertEquals(ViewSpec.Text.Layout.COLLECTION_HEADER, header.layout());
        assertTrue(header.marqueeActivation().isEmpty());
        assertTrue(view.widget(header.id()).isEmpty());
        assertTrue(view.navigationNodes().stream().noneMatch(n -> n.id().equals(header.id())));
        assertTrue(view.tooltipRegions().stream().noneMatch(t -> t.id().equals(header.id())));
        assertTrue(view.panels().stream().noneMatch(p -> p.id().equals("gallery.capes.divider")));
        var main = view.previews().get(0);
        assertEquals(Optional.of("provider:cape:" + "c".repeat(64)), main.capeId());
        assertFalse(main.capeHasElytra());
    }

    @Test
    void unavailableNativeAccessoriesDoNotCreateGhostToggle() {
        var account = TestFixtures.account(1);
        var s = TestFixtures.ready(account, account.presets().get(0).id(), 0);
        var d = draft(s.activePresetId(), PreviewRenderer.DisplayKind.ORDINARY);
        for (var availability : com.naocraftlab.skins.client.PreviewAccessories.Availability.values()) {
            var c = new GalleryPresenter.ControlsInput(true,
                    com.naocraftlab.skins.client.OuterLayerVisibility.allVisible(), PreviewInteractionModel.gallery(),
                    Optional.empty(), List.of(), 0, Optional.empty(),
                    new com.naocraftlab.skins.client.PreviewAccessories(UUID.randomUUID(), 1,
                            availability, false, false, PreviewRenderer.LiveReason.AVAILABLE));
            var view = presenter.present(s,854,480,0,0,PreviewRenderer.CapeMode.CAPE,SkinVariant.CLASSIC,
                    "",Optional.empty(),0,null,d,c);
            assertTrue(view.widget("gallery.preview_mode").isEmpty());
            assertFalse(view.previews().get(0).nativeAccessories());
        }
    }

    @Test
    void leftColumnAndPortraitStayHeightDerivedWhenWindowWidens() {
        var account = TestFixtures.account(80);
        var s = TestFixtures.ready(account, account.presets().get(0).id(), 0);
        var d = draft(s.activePresetId(), PreviewRenderer.DisplayKind.ORDINARY);
        var reference = project(s, 854, 480, d, Optional.empty(), Optional.empty(), 0);
        var wide = project(s, 1600, 480, d, Optional.empty(), Optional.empty(), 0);
        assertEquals(reference.scrollSurface("gallery.stage").orElseThrow().viewport().width(),
                wide.scrollSurface("gallery.stage").orElseThrow().viewport().width());
        var portrait = reference.previews().get(0).anchorBounds();
        assertEquals(portrait.width() * 2, portrait.height());
        assertEquals(portrait, wide.previews().get(0).anchorBounds());
        assertTrue(wide.scrollSurface("gallery.cards").orElseThrow().viewport().width()
                > reference.scrollSurface("gallery.cards").orElseThrow().viewport().width());
    }

    @Test
    void narrowAndLowPortraitPreservesAspectWithIndependentSafeScroll() {
        var account = TestFixtures.account(80);
        var s = TestFixtures.ready(account, account.presets().get(0).id(), 0);
        var d = draft(s.activePresetId(), PreviewRenderer.DisplayKind.ORDINARY);
        for (int[] size : List.of(new int[]{120, 480}, new int[]{320, 180})) {
            var capes = List.of(new GalleryPresenter.CapeChoice("OFFLINE",UiMessage.literal("Offline",UiMessage.Severity.INFO),Optional.of("fixture-offline"),true),
                    new GalleryPresenter.CapeChoice("MINECRAFT",UiMessage.literal("Minecraft",UiMessage.Severity.INFO),Optional.of("fixture-official"),true));
            var view = presenter.present(s,size[0],size[1],0,0,PreviewRenderer.CapeMode.CAPE,SkinVariant.CLASSIC,"",Optional.empty(),0,null,d,
                    new GalleryPresenter.ControlsInput(true,com.naocraftlab.skins.client.OuterLayerVisibility.allVisible(),PreviewInteractionModel.gallery(),Optional.empty(),capes,0,Optional.empty()));
            assertEquals(2,view.backEquipmentPreviews().size());
            assertTrue(view.backEquipmentPreviews().stream().allMatch(p -> p.projection()==ViewSpec.BackEquipmentPreview.Projection.PROVIDER_THUMBNAIL
                    && p.bounds().width()==p.bounds().height()));
            var stage = view.scrollSurface("gallery.stage").orElseThrow();
            var portrait = view.previews().get(0).anchorBounds();
            assertEquals(portrait.width() * 2, portrait.height());
            assertTrue(portrait.width() > 0);
            assertTrue(portrait.right() <= stage.viewport().right());
            assertTrue(stage.viewport().x() >= 0);
            assertTrue(stage.viewport().bottom() <= view.widget("gallery.done").orElseThrow().bounds().y());
            assertTrue(view.clipFor("gallery.preview").isEmpty());
            var cards = view.scrollSurface("gallery.cards").orElseThrow();
            assertTrue(cards.viewport().x() >= stage.viewport().right());
            assertTrue(cards.viewport().right() <= size[0]);
            assertTrue(cards.maximumPixels() > 0);
            if (size[1] == 180) assertTrue(stage.maximumPixels() > 0);
        }
    }

    @Test
    void cardModesKeepBoundsAndExactTwentyPixelTwoByTwoActions() {
        var account=TestFixtures.account(3);var p=account.presets().get(2);var s=TestFixtures.ready(account,p.id(),0);
        var d=draft(Optional.of(p.id()),PreviewRenderer.DisplayKind.ORDINARY);
        var normal=project(s,854,480,d,Optional.empty(),Optional.empty(),0);
        var actions=project(s,854,480,d,Optional.of(p.id()),Optional.empty(),0);
        String prefix="gallery.preset."+p.id();
        var star=actions.widget(prefix+".favorite").orElseThrow().bounds();
        var edit=actions.widget(prefix+".edit").orElseThrow().bounds();
        var copy=actions.widget(prefix+".duplicate").orElseThrow().bounds();
        var delete=actions.widget(prefix+".delete").orElseThrow().bounds();
        var bounds=actions.widget("gallery.card."+p.id()).orElseThrow().bounds();
        assertTrue(Math.abs(star.x()+edit.right()-bounds.x()-bounds.right())<=1);
        for(var b:List.of(star,edit,copy,delete)) {assertTrue(b.width()>=20);assertEquals(20,b.height());}
        assertEquals(bounds.x()+2,star.x());assertEquals(bounds.right()-2,edit.right());
        assertEquals(2,edit.x()-star.right());assertEquals(2,copy.y()-star.bottom());assertEquals(copy.y(),delete.y());
        var confirm=project(s,854,480,d,Optional.of(p.id()),Optional.of(p.id()),0);
        assertEquals(normal.widget("gallery.card."+p.id()).orElseThrow().bounds(),confirm.widget("gallery.card."+p.id()).orElseThrow().bounds());
        var cancel=confirm.widget(prefix+".delete_cancel").orElseThrow().bounds();
        var accept=confirm.widget(prefix+".delete_confirm").orElseThrow().bounds();
        assertEquals(20,cancel.height());assertEquals(20,accept.height());assertEquals(2,cancel.y()-accept.bottom());
        assertTrue(confirm.widget(prefix+".favorite").isEmpty());assertTrue(confirm.widget(prefix+".more").isPresent());
    }

    @Test
    void cardCaptionAndMovingClipRiseWithoutMovingArrowOrHitBounds() {
        var account = TestFixtures.account(3);
        var p = account.presets().get(2);
        var s = TestFixtures.ready(account, p.id(), 0);
        var d = draft(s.activePresetId(), PreviewRenderer.DisplayKind.ORDINARY);
        String prefix = "gallery.preset." + p.id();
        for (int mode = 0; mode < 3; mode++) {
            var view = project(s,854,480,d,mode == 0 ? Optional.empty() : Optional.of(p.id()),
                    mode == 2 ? Optional.of(p.id()) : Optional.empty(),0);
            var card = view.widget("gallery.card."+p.id()).orElseThrow().bounds();
            var arrow = view.widget(prefix+".more").orElseThrow();
            int barTop = mode == 0 ? card.bottom()-16 : card.y();
            assertEquals(new Bounds(card.x(),barTop,card.width(),16),arrow.bounds());
            var name = view.texts().stream().filter(v -> v.id().equals(prefix+".name")).findFirst().orElseThrow();
            int nameTop = barTop+(mode == 0 ? 4 : 5);
            assertEquals(new Bounds(card.x()+4,nameTop,card.width()-18,10),name.bounds());
            var marquee = name.marqueeActivation().orElseThrow();
            assertEquals(card,marquee.hoverBounds());
            assertEquals(List.of("gallery.card."+p.id(),prefix+".more"),marquee.focusWidgetIds());
            assertEquals(Optional.of(new Bounds(card.x(),nameTop,card.width()-14,10)),marquee.movingClip());
        }
    }

    @Test
    void overlayBarOwnsWholeNameAndMenuRemovesBodyAndBadgeWithoutMovingCards() {
        var account = TestFixtures.account(3);
        var p = account.presets().get(2);
        var s = TestFixtures.ready(account, p.id(), 0);
        var d = draft(s.activePresetId(), PreviewRenderer.DisplayKind.ORDINARY);
        String prefix = "gallery.preset." + p.id();
        var normal = project(s,854,480,d,Optional.empty(),Optional.empty(),0);
        var b = normal.widget("gallery.card." + p.id()).orElseThrow().bounds();
        var preview = normal.previews().stream().filter(v -> v.id().equals(prefix + ".preview")).findFirst().orElseThrow();
        assertEquals(b,preview.bounds());
        assertEquals(b,ViewHostPolicy.paintClip(normal,preview.id()).orElseThrow());
        var strip=normal.panels().stream().filter(v -> v.id().equals(prefix+".namebar")).findFirst().orElseThrow().bounds();
        assertEquals(new Bounds(b.x(),b.bottom()-16,b.width(),16),strip);
        assertEquals(0x6D000000,ViewHostPolicy.panelOverlayColor("gallery",ViewSpec.Panel.Style.TRANSLUCENT_NAMEBAR));
        assertEquals(0x88000000,ViewHostPolicy.panelOverlayColor("catalog",ViewSpec.Panel.Style.TRANSLUCENT_NAMEBAR));
        var bar = normal.widget(prefix + ".more").orElseThrow();
        assertEquals(new Bounds(b.x(),b.bottom()-16,b.width(),16),bar.bounds());
        assertEquals(Optional.of("up"),bar.value());
        assertEquals(UiMessage.info("nclskins.gallery.actions"),bar.label());
        assertEquals(bar.id(),ViewHostPolicy.pointerOwnerAt(normal,b.x()+3,b.bottom()-8).orElseThrow().id());
        for (boolean deleting : List.of(false,true)) {
            var menu = project(s,854,480,d,Optional.of(p.id()),deleting ? Optional.of(p.id()) : Optional.empty(),0);
            assertEquals(b,menu.widget("gallery.card."+p.id()).orElseThrow().bounds());
            assertTrue(menu.previews().stream().noneMatch(v -> v.id().startsWith(prefix)));
            assertTrue(menu.widgets().stream().noneMatch(v -> v.id().equals(prefix+".compatibility")));
            assertTrue(menu.navigationNodes().stream().noneMatch(v -> v.id().equals(prefix+".compatibility")));
            assertFalse(menu.widget("gallery.card."+p.id()).orElseThrow().activationEnabled());
            assertEquals(Optional.of("down"),menu.widget(prefix+".more").orElseThrow().value());
            assertEquals(new Bounds(b.x(),b.y(),b.width(),16),menu.widget(prefix+".more").orElseThrow().bounds());
            assertEquals(new Bounds(b.x()+2,b.y()+2,b.width()-4,b.height()-4),menu.panels().stream().filter(v -> v.id().equals(prefix+".namebar")).findFirst().orElseThrow().bounds());
            var name = menu.texts().stream().filter(v -> v.id().equals(prefix+".name")).findFirst().orElseThrow();
            assertEquals(4,name.bounds().x()-b.x());
            assertEquals(5,name.bounds().y()-b.y());
            assertEquals(ViewSpec.Text.Alignment.LEFT,name.alignment());
            if (deleting) {
                var accept = menu.widget(prefix+".delete_confirm").orElseThrow().bounds();
                var cancel = menu.widget(prefix+".delete_cancel").orElseThrow().bounds();
                assertEquals(2,cancel.y()-accept.bottom());
                assertEquals(b.bottom()-2,cancel.bottom());
                var prompt = menu.texts().stream().filter(v -> v.id().equals(prefix+".confirmation")).findFirst().orElseThrow().bounds();
                assertEquals((b.y()+16+accept.y())/2,prompt.y()+prompt.height()/2);
            } else assertEquals(b.bottom()-2,menu.widget(prefix+".delete").orElseThrow().bounds().bottom());
        }
    }

    @Test
    void normalNamebarsReachCardBottomAcrossColumnsAndPartialScrollClips() {
        for (int count : List.of(1, 12)) {
            var account = TestFixtures.account(count);
            var snapshot = TestFixtures.ready(account, account.presets().get(0).id(), 0);
            for (int width : List.of(120, 320, 854)) for (double scroll : List.of(0.0, 37.5)) {
                var view = project(snapshot,width,180,draft(snapshot.activePresetId(),PreviewRenderer.DisplayKind.ORDINARY),
                        Optional.empty(),Optional.empty(),scroll);
                var cards = view.widgets().stream().filter(w -> w.id().startsWith("gallery.card.")).toList();
                assertFalse(cards.isEmpty());
                if (count > 1 && width == 854) assertTrue(cards.stream().filter(w -> w.bounds().y()==cards.get(0).bounds().y()).count()>1);
                for (var card : cards) {
                    String prefix = card.id().replace("gallery.card.","gallery.preset.");
                    var bounds = card.bounds();
                    var strip = view.panels().stream().filter(p -> p.id().equals(prefix+".namebar")).findFirst().orElseThrow();
                    assertEquals(new Bounds(bounds.x(),bounds.bottom()-16,bounds.width(),16),strip.bounds());
                    assertEquals(strip.bounds(),view.widget(prefix+".more").orElseThrow().bounds());
                    assertEquals(bounds.bottom()-12,view.texts().stream().filter(t -> t.id().equals(prefix+".name")).findFirst().orElseThrow().bounds().y());
                    assertEquals(view.scrollSurface("gallery.cards").orElseThrow().viewport(),ViewHostPolicy.paintClip(view,strip.id()).orElseThrow());
                }
            }
        }
    }

    @Test
    void spatialFocusRevealAndActivationUseLogicalGridWithoutApplying() {
        var account=TestFixtures.account(80);var s=TestFixtures.ready(account,account.presets().get(0).id(),0);
        var d=draft(s.activePresetId(),PreviewRenderer.DisplayKind.UNCONFIRMED);
        var view=project(s,320,180,d,Optional.empty(),Optional.empty(),0);
        String first="gallery.card."+account.presets().get(79).id();
        var down=ViewNavigationPolicy.target(view,first,ViewSpec.NavigationCommand.DOWN).orElseThrow();
        assertTrue(down.bounds().y()>view.navigationNode(first).orElseThrow().bounds().y());
        double offset=ViewNavigationPolicy.ensureVisibleOffset(view,down).orElseThrow();
        var revealed=project(s,320,180,d,Optional.empty(),Optional.empty(),offset);
        assertTrue(revealed.widget(down.id()).isPresent());
        assertEquals(Optional.of(down.id()),ViewNavigationPolicy.activationAction(revealed,down.id()));
        assertEquals(s.activePresetId(),d.selectedPresetId());
        assertTrue(ViewNavigationPolicy.target(view,first,ViewSpec.NavigationCommand.UP).isEmpty());
    }

    @Test
    void portraitGridStaysFixedTopLeftAndFitsThreeRowsWithSectionsAtStandardScale() {
        var base=TestFixtures.account(80);var ps=new java.util.ArrayList<>(base.presets());ps.set(79,ps.get(79).withFavorite(true));
        var a=new AccountState(base.schemaVersion(),base.accountId(),base.skinAssets(),ps,base.updatedAt());
        var s=TestFixtures.ready(a,ps.get(0).id(),0);
        for(int[] size:List.of(new int[]{854,480},new int[]{427,240},new int[]{320,180},new int[]{160,120},new int[]{120,480},new int[]{1600,900})) {
            var view=project(s,size[0],size[1],draft(s.activePresetId(),PreviewRenderer.DisplayKind.UNCONFIRMED),Optional.empty(),Optional.empty(),0);
            var cards=view.navigationNodes().stream().filter(n->n.id().startsWith("gallery.card.")).toList();
            assertTrue(cards.stream().allMatch(n->n.bounds().width()>=48 && n.bounds().width()<=64 && n.bounds().height()==96));
            var viewport = view.scrollSurface("gallery.cards").orElseThrow().viewport();
            assertEquals(size[1] - 33, viewport.bottom());
            int columns = Math.max(1, (viewport.width() - 2) / 50);
            int expectedWidth = Math.min(64, (viewport.width() - 4 - (columns - 1) * 2) / columns);
            assertEquals(Math.max(48, expectedWidth), cards.get(0).bounds().width());
            assertEquals(80,cards.size());
            assertTrue(view.scrollSurface("gallery.cards").orElseThrow().maximumPixels()>=0);
            if(size[0]==854) assertTrue(cards.stream().filter(n->n.bounds().bottom()<=view.scrollSurface("gallery.cards").orElseThrow().viewport().bottom()).map(n->n.bounds().y()).distinct().count()>=3);
        }
    }

    @Test
    void noSelectedUsesLiveOptionsWithCleanFooterAndControllerActualDrawGate() {
        var s=TestFixtures.ready(TestFixtures.account(0),null,0);
        for(var kind:PreviewRenderer.DisplayKind.values()) for(boolean controller:List.of(false,true)) {
            var mask=com.naocraftlab.skins.client.OuterLayerVisibility.noneVisible();
            var d=draft(Optional.empty(),kind);
            var c=new GalleryPresenter.ControlsInput(controller,mask,PreviewInteractionModel.gallery(),Optional.empty(),List.of(),0,Optional.empty());
            var view=presenter.present(s,320,180,0,0,PreviewRenderer.CapeMode.CAPE,SkinVariant.CLASSIC,"",Optional.empty(),0,null,d,c);
            assertEquals(mask,view.previews().get(0).outerLayerVisibility());
            assertEquals(controller && kind==PreviewRenderer.DisplayKind.ORDINARY,view.widget("gallery.outer_layer.head").isPresent());
            assertEquals(controller && kind==PreviewRenderer.DisplayKind.ORDINARY,view.texts().stream().anyMatch(t->t.id().equals("gallery.layers")));
            assertTrue(view.widget("gallery.done").isPresent());assertTrue(view.widget("gallery.apply").isEmpty());
        }
    }

    private static GalleryPresenter.DraftInput draft(Optional<UUID> selected,PreviewRenderer.DisplayKind kind) {
        var mask=com.naocraftlab.skins.client.OuterLayerVisibility.allVisible();
        return new GalleryPresenter.DraftInput(selected,mask,mask,1,Optional.empty(),java.util.Set.of(),Optional.empty(),kind);
    }

    private ViewSpec project(ClientSnapshot s,int w,int h,GalleryPresenter.DraftInput d,Optional<UUID> action,Optional<UUID> deleting,double scroll) {
        return presenter.present(s,w,h,0,0,PreviewRenderer.CapeMode.CAPE,SkinVariant.CLASSIC,"",deleting,scroll,
                d.selectedPresetId().map(id->"gallery.card."+id).orElse(null),d,
                new GalleryPresenter.ControlsInput(true,com.naocraftlab.skins.client.OuterLayerVisibility.allVisible(),PreviewInteractionModel.gallery(),action,List.of(),0,Optional.empty()));
    }

    @Test
    void selectedDraftOwnsMainPreviewAndDirtyFooterNotCardApply() {
        var account = TestFixtures.account(2);
        var a = account.presets().get(0);
        var b = account.presets().get(1);
        var input = new GalleryPresenter.DraftInput(Optional.of(b.id()), b.outerLayerVisibility(),
                com.naocraftlab.skins.client.OuterLayerVisibility.noneVisible(), 7, Optional.empty(),
                java.util.Set.of(), Optional.empty(), PreviewRenderer.DisplayKind.UNCONFIRMED);
        var view = presenter.present(TestFixtures.ready(account, a.id(), 0), 854, 480, 0, 0,
                PreviewRenderer.CapeMode.CAPE, SkinVariant.CLASSIC, "", Optional.empty(), 0,
                "gallery.card."+b.id(), input);
        var main = view.previews().stream().filter(p -> p.id().equals("gallery.preview")).findFirst().orElseThrow();
        assertEquals(Optional.of(b.id()), main.presetId());
        assertEquals(b.skin(), main.skin());
        assertEquals(input.draftMask(), main.outerLayerVisibility());
        assertEquals(PreviewRenderer.PreviewIntent.EDITOR_DRAFT, main.intent());
        assertTrue(view.widget("gallery.done").isEmpty());
        assertEquals(4,view.widget("gallery.back").orElseThrow().bounds().x()-view.widget("gallery.apply").orElseThrow().bounds().right());
        assertEquals(4,view.widget("gallery.reset").orElseThrow().bounds().x()-view.widget("gallery.back").orElseThrow().bounds().right());
        assertEquals(List.of("gallery.apply", "gallery.back", "gallery.reset"),
                view.widgets().stream().filter(w -> List.of("gallery.apply","gallery.back","gallery.reset").contains(w.id())).map(ViewSpec.Widget::id).toList());
        assertTrue(view.widgets().stream().noneMatch(w -> w.id().startsWith("gallery.outer_layer.")));
    }

    @Test
    void favoritesTopologyPrecedesQueryAndExplicitCollapseIsPureProjection() {
        var base = TestFixtures.account(4);
        var presets = new java.util.ArrayList<>(base.presets());
        presets.set(0, presets.get(0).withFavorite(true));
        var account = new AccountState(base.schemaVersion(), base.accountId(), base.skinAssets(), presets, base.updatedAt());
        var s = TestFixtures.ready(account, presets.get(0).id(), 0);
        var d = new GalleryPresenter.DraftInput(s.activePresetId(), presets.get(0).outerLayerVisibility(), presets.get(0).outerLayerVisibility(),
                1, Optional.empty(), java.util.Set.of("gallery.favorites"), Optional.empty(), PreviewRenderer.DisplayKind.UNCONFIRMED);
        var collapsed = presenter.present(s,854,480,0,0,PreviewRenderer.CapeMode.CAPE,SkinVariant.CLASSIC,"",Optional.empty(),0,"gallery.card."+presets.get(0).id(),d);
        assertTrue(collapsed.navigationNode("gallery.card."+presets.get(0).id()).isEmpty());
        assertTrue(collapsed.navigationNode("gallery.favorites").isPresent());
        var filtered = presenter.present(s,854,480,0,0,PreviewRenderer.CapeMode.CAPE,SkinVariant.CLASSIC,"PRESET 2",Optional.empty(),0,null,d);
        assertTrue(filtered.widget("gallery.sections").isPresent());
        assertTrue(filtered.navigationNode("gallery.favorites").isEmpty());
        assertTrue(filtered.navigationNode("gallery.your_appearances").isPresent());
        assertEquals(java.util.Set.of("gallery.favorites"),d.collapsedSections());
        var reveal=new GalleryPresenter.DraftInput(d.selectedPresetId(),d.baselineMask(),d.draftMask(),d.generation(),d.inspectionIdentity(),d.collapsedSections(),Optional.of("gallery.favorites"),d.displayedBodyKind());
        var revealed=presenter.present(s,854,480,0,0,PreviewRenderer.CapeMode.CAPE,SkinVariant.CLASSIC,"",Optional.empty(),0,null,reveal);
        assertTrue(revealed.navigationNode("gallery.card."+presets.get(0).id()).isPresent());
        assertEquals(java.util.Set.of("gallery.favorites"),reveal.collapsedSections());
        assertEquals("gallery.favorites",presenter.normalizeSelectedCardId(s,"","gallery.card."+presets.get(0).id(),d));
    }

    @Test
    void compactGridReplacesStripAndAddCard() {
        var account = TestFixtures.account(40);
        var view = presenter.present(TestFixtures.ready(account, account.presets().get(0).id(), 0),
                854, 480, 0, 0, PreviewRenderer.CapeMode.CAPE);
        assertEquals(ViewSpec.Scrollbar.Orientation.VERTICAL,
                view.scrollSurface("gallery.cards").orElseThrow().orientation());
        assertTrue(view.widget("gallery.add").isPresent());
        assertTrue(view.panels().stream().noneMatch(p -> p.id().equals("gallery.card.add")));
        var cards = view.navigationNodes().stream().filter(n -> n.id().startsWith("gallery.card.")).toList();
        assertEquals(40, cards.size());
        assertTrue(cards.stream().allMatch(n -> n.bounds().height() == 96 && n.bounds().width() >= 48 && n.bounds().width() <= 64));
        assertTrue(cards.stream().map(n -> n.bounds().y()).distinct().count() >= 3);
        assertTrue(view.widgets().stream().noneMatch(w -> w.id().endsWith(".apply")));
    }

    @Test
    void galleryHideFlagKeepsOnlyMatchingActiveIncompatibleLookAfterAdd() {
        AccountState account = TestFixtures.account(2);
        UUID active = account.presets().get(1).id();
        ClientSnapshot base = TestFixtures.ready(account, active, 0);
        SkinFeatureEvidence conflict = new SkinFeatureEvidence(
                List.of(), List.of(SkinConflictReason.MALFORMED_EXPRESSIVE_DATA));
        ClientSnapshot hidden = withCompatibility(
                base,
                new SkinExtensionEnvironment(
                        7, Map.of(SkinConsumer.FRESH_MOVES, SkinConsumerState.ACTIVE)),
                Map.of(TestFixtures.CLASSIC_ID, conflict, TestFixtures.SLIM_ID, conflict),
                true);

        assertEquals(
                List.of("gallery.card." + active),
                presenter.cardIds(hidden, "Preset"));
        ViewSpec view = presenter.present(
                hidden, 854, 480, 0, 0, PreviewRenderer.CapeMode.CAPE,
                SkinVariant.CLASSIC, "Preset", Optional.empty());
        String indicatorId = "gallery.preset." + active + ".compatibility";
        ViewSpec.Widget indicator = view.widget(indicatorId).orElseThrow();
        assertEquals(ViewSpec.WidgetKind.PASSIVE_INDICATOR, indicator.kind());
        assertEquals(Optional.of(indicator.label()), indicator.hint());
        assertTrue(view.navigationNode(indicatorId).isPresent());
        assertEquals(Optional.of(GuiIcon.STATUS_COMPATIBILITY_INCOMPATIBLE), indicator.icon());
        assertEquals(20, indicator.bounds().width());
        assertEquals(20, indicator.bounds().height());
        Bounds card = view.widget("gallery.card." + active).orElseThrow().bounds();
        assertEquals(card.x(), indicator.bounds().x());
        assertEquals(card.y() + 2, indicator.bounds().y());
        assertTrue(view.iconDecorations().stream()
                .noneMatch(icon -> icon.ownerWidgetId().equals(indicatorId)));

        ClientSnapshot visible = withCompatibility(
                base, hidden.skinExtensionEnvironment(), hidden.assetEvidence(), false);
        assertEquals(2, presenter.cardIds(visible, "Preset").size());
    }

    @Test
    void galleryHidesInactiveSneakyPresetButKeepsActiveOne() {
        AccountState account = TestFixtures.account(2);
        UUID active = account.presets().get(0).id();
        SkinFeatureEvidence sneaky = new SkinFeatureEvidence(
                List.of(com.naocraftlab.skins.core.compatibility.SkinFeature.SNEAKY_CAPES),
                List.of(SkinConflictReason.SNEAKY_FRESH_MOVES_OVERLAP,
                        SkinConflictReason.SNEAKY_JUST_EXPRESSIONS_OVERLAP));
        ClientSnapshot hidden = withCompatibility(TestFixtures.ready(account, active, 0),
                new SkinExtensionEnvironment(5,
                        Map.of(SkinConsumer.FRESH_MOVES, SkinConsumerState.ACTIVE)),
                Map.of(TestFixtures.CLASSIC_ID, sneaky, TestFixtures.SLIM_ID, sneaky), true);
        assertEquals(List.of("gallery.card." + active), presenter.cardIds(hidden, "Preset"));
        assertEquals(Optional.of(GuiIcon.STATUS_COMPATIBILITY_INCOMPATIBLE),
                presenter.present(hidden, 854, 480, 0, 0, PreviewRenderer.CapeMode.CAPE,
                        SkinVariant.CLASSIC, "Preset", Optional.empty())
                        .widget("gallery.preset." + active + ".compatibility").orElseThrow().icon());
    }

    @Test
    void coldInitializationIsNeutralUntilAccountDataExists() {
        ClientSnapshot cold = initializingSnapshot(Optional.empty(), Optional.empty());

        ViewSpec view = presenter.present(
                cold, 320, 240, 0, 0, PreviewRenderer.CapeMode.CAPE);

        assertTrue(view.texts().stream().anyMatch(text -> text.id().equals("gallery.loading")));
        assertTrue(view.widgets().isEmpty());
        assertTrue(view.previews().isEmpty());
        assertTrue(view.panels().stream().noneMatch(panel ->
                panel.id().startsWith("gallery.card.")));
        assertTrue(view.texts().stream().noneMatch(text -> text.id().equals("gallery.offline")));
    }

    @Test
    void warmedInitializationShowsVisibleDisabledCardsWithoutStaleSessionChrome() {
        AccountState account = TestFixtures.account(5);
        UUID active = account.presets().get(4).id();
        ClientSnapshot seeded = initializingSnapshot(Optional.of(account), Optional.of(active));

        ViewSpec view = presenter.present(
                seeded,
                320,
                240,
                0,
                0,
                PreviewRenderer.CapeMode.CAPE,
                SkinVariant.CLASSIC,
                "",
                Optional.empty(),
                presenter.initialScrollPosition(
                        seeded.account(), seeded.activePresetId(), "", 320, 240));

        Bounds activeBounds = view.panels().stream()
                .filter(panel -> panel.id().equals("gallery.card." + active))
                .findFirst()
                .orElseThrow()
                .bounds();
        assertTrue(activeBounds.x() >= 0 && activeBounds.right() <= 320);
        assertFalse(view.widget("gallery.search").orElseThrow().enabled());
        assertFalse(view.widget("gallery.done").orElseThrow().enabled());
        assertTrue(view.widget("gallery.retry_session").isEmpty());
        assertTrue(view.texts().stream().noneMatch(text -> text.id().equals("gallery.offline")));
    }





















    @Test
    void headerShowsOnlyOfflineAndTheApplicableRecoveryAction() {
        ClientSnapshot valid = TestFixtures.ready(TestFixtures.account(1), null, 0);
        ViewSpec healthy = presenter.present(valid, 854, 480, 427, 180, PreviewRenderer.CapeMode.CAPE);
        assertTrue(healthy.texts().stream().noneMatch(text -> text.id().equals("gallery.offline")));
        assertTrue(healthy.texts().stream().noneMatch(text -> text.id().equals("gallery.session")));
        assertTrue(healthy.texts().stream().noneMatch(text -> text.id().equals("gallery.status")));
        assertTrue(healthy.widget("gallery.retry_session").isEmpty());
        assertTrue(healthy.widget("gallery.retry_cape").isEmpty());
        assertEquals(new Bounds(32, 12, 790, 10), text(healthy, "gallery.title").bounds());
        assertEquals(
                List.of("gallery.add", "gallery.search", "gallery.card." + valid.account().orElseThrow().presets().get(0).id(), "gallery.preset." + valid.account().orElseThrow().presets().get(0).id() + ".more", "gallery.providers", "gallery.done"),
                tabIds(healthy));

        ViewSpec missing = presenter.present(
                withState(valid, Optional.empty(), false, false, AppearanceSyncStatus.LOCAL_ONLY),
                854, 480, 427, 180, PreviewRenderer.CapeMode.CAPE);
        assertTrue(missing.texts().stream().noneMatch(text -> text.id().equals("gallery.offline")));
        assertTrue(missing.widget("gallery.retry_session").isEmpty());
        assertEquals(new Bounds(32, 12, 790, 10), text(missing, "gallery.title").bounds());
        assertEquals(
                List.of("gallery.add", "gallery.search", "gallery.card." + valid.account().orElseThrow().presets().get(0).id(), "gallery.preset." + valid.account().orElseThrow().presets().get(0).id() + ".more", "gallery.providers", "gallery.done"),
                tabIds(missing));

        SessionValidation invalidSession = new SessionValidation(
                SessionStatus.OFFLINE_OR_INVALID,
                TestFixtures.validSession().sessionIdentity(),
                null,
                new SessionFailureContext(
                        SessionCheckPhase.PROFILE,
                        ApiFailureKind.NETWORK,
                        null),
                "offline");
        ViewSpec invalid = presenter.present(
                withState(valid, Optional.of(invalidSession), false, true, AppearanceSyncStatus.LOCAL_ONLY),
                854, 480, 427, 180, PreviewRenderer.CapeMode.CAPE);
        assertTrue(invalid.texts().stream().anyMatch(text -> text.id().equals("gallery.offline")));
        assertFalse(invalid.widget("gallery.retry_session").orElseThrow().enabled());

        ViewSpec connecting = presenter.present(
                withSessionActivity(
                        withState(
                                valid,
                                Optional.of(invalidSession),
                                false,
                                false,
                                AppearanceSyncStatus.LOCAL_ONLY),
                        ClientSnapshot.SessionActivity.RECONNECTING),
                854, 480, 427, 180, PreviewRenderer.CapeMode.CAPE);
        assertEquals(
                UiMessage.info("nclskins.session.connecting"),
                text(connecting, "gallery.offline").message());
        assertFalse(connecting.widget("gallery.retry_session").orElseThrow().enabled());

        SessionValidation unchecked = new SessionValidation(SessionStatus.UNCHECKED,
                invalidSession.sessionIdentity(), null, null, "Unchecked");
        ViewSpec uncheckedView = presenter.present(
                withState(valid, Optional.of(unchecked), false, false, AppearanceSyncStatus.LOCAL_ONLY),
                854, 480, 427, 180, PreviewRenderer.CapeMode.CAPE);
        assertTrue(uncheckedView.texts().stream().noneMatch(text -> text.id().equals("gallery.offline")));
        assertTrue(uncheckedView.widget("gallery.retry_session").isEmpty());

        ViewSpec rateLimited = presenter.present(
                withState(valid, valid.session(), true, false, AppearanceSyncStatus.LOCAL_ONLY),
                854, 480, 427, 180, PreviewRenderer.CapeMode.CAPE);
        assertTrue(rateLimited.texts().stream().noneMatch(text -> text.id().equals("gallery.offline")));
        assertTrue(rateLimited.widget("gallery.retry_session").isEmpty());

        ViewSpec unverifiedRateLimited = presenter.present(
                withState(valid, valid.session(), true, false, AppearanceSyncStatus.UNKNOWN),
                854, 480, 427, 180, PreviewRenderer.CapeMode.CAPE);
        assertTrue(unverifiedRateLimited.texts().stream()
                .noneMatch(text -> text.id().equals("gallery.offline")));
        assertTrue(unverifiedRateLimited.widget("gallery.retry_session").isEmpty());

        SessionValidation tokenUnavailable = new SessionValidation(
                SessionStatus.OFFLINE_OR_INVALID,
                TestFixtures.validSession().sessionIdentity(),
                null,
                new SessionFailureContext(
                        SessionCheckPhase.TOKEN_SOURCE,
                        ApiFailureKind.TOKEN_UNAVAILABLE,
                        null),
                "no token");
        ViewSpec noToken = presenter.present(
                withState(
                        valid,
                        Optional.of(tokenUnavailable),
                        false,
                        false,
                        AppearanceSyncStatus.LOCAL_ONLY),
                854, 480, 427, 180, PreviewRenderer.CapeMode.CAPE);
        assertEquals(
                UiMessage.info("nclskins.session.offline"),
                text(noToken, "gallery.offline").message());
        assertTrue(noToken.widget("gallery.retry_session").isEmpty());
        assertTrue(noToken.widget("gallery.retry_cape").isEmpty());

        SessionValidation expired = new SessionValidation(
                SessionStatus.EXPIRED,
                TestFixtures.validSession().sessionIdentity(),
                null,
                new SessionFailureContext(
                        SessionCheckPhase.PROFILE,
                        ApiFailureKind.SESSION_EXPIRED,
                        401),
                "restart required");
        ViewSpec expiredSession = presenter.present(
                withState(
                        valid,
                        Optional.of(expired),
                        false,
                        false,
                        AppearanceSyncStatus.LOCAL_ONLY),
                854, 480, 427, 180, PreviewRenderer.CapeMode.CAPE);
        assertEquals(
                UiMessage.info("nclskins.session.offline"),
                text(expiredSession, "gallery.offline").message());
        assertTrue(expiredSession.widget("gallery.retry_session").isEmpty());

        ViewSpec validUnknownConnecting = presenter.present(
                withSessionActivity(
                        withState(
                                valid,
                                valid.session(),
                                false,
                                false,
                                AppearanceSyncStatus.UNKNOWN),
                        ClientSnapshot.SessionActivity.RECONNECTING),
                854, 480, 427, 180, PreviewRenderer.CapeMode.CAPE);
        assertEquals(
                UiMessage.info("nclskins.session.connecting"),
                text(validUnknownConnecting, "gallery.offline").message());
        assertFalse(validUnknownConnecting.widget("gallery.retry_session").orElseThrow().enabled());

        ViewSpec validUnknownReconciling = presenter.present(
                withSyncInProgress(
                        withState(
                                valid,
                                valid.session(),
                                false,
                                false,
                                AppearanceSyncStatus.UNKNOWN)),
                854, 480, 427, 180, PreviewRenderer.CapeMode.CAPE);
        assertTrue(validUnknownReconciling.texts().stream()
                .noneMatch(text -> text.id().equals("gallery.offline")));
        assertTrue(validUnknownReconciling.widget("gallery.retry_session").isEmpty());
    }

























    @Test
    void disabledGalleryDoesNotPublishEligibleCardAnchors() {
        AccountState account = TestFixtures.account(2);
        ClientSnapshot seeded = initializingSnapshot(Optional.of(account), Optional.empty());
        ViewSpec view = presenter.present(
                seeded, 320, 240, 0, 0, PreviewRenderer.CapeMode.CAPE,
                SkinVariant.CLASSIC, "", Optional.empty(), 0.0, "gallery.add");

        assertTrue(view.navigationNodes().stream()
                .filter(node -> node.pattern() == ViewSpec.NavigationPattern.HORIZONTAL_LIST)
                .noneMatch(ViewSpec.NavigationNode::enabled));
    }

    private ViewSpec presentAt(ClientSnapshot snapshot, double scrollPosition) {
        return presentAt(snapshot, 854, 480, scrollPosition);
    }

    private ViewSpec presentAt(
            ClientSnapshot snapshot, int width, int height, double scrollPosition) {
        return presenter.present(
                snapshot,
                width,
                height,
                width / 2,
                Math.min(height - 1, 180),
                PreviewRenderer.CapeMode.CAPE,
                SkinVariant.CLASSIC,
                "",
                Optional.empty(),
                scrollPosition);
    }

    private static List<String> tabIds(ViewSpec view) {
        return view.navigationNodes().stream()
                .filter(ViewSpec.NavigationNode::enabled)
                .filter(node -> node.tabOrder() >= 0)
                .sorted(java.util.Comparator.comparingInt(ViewSpec.NavigationNode::tabOrder))
                .map(ViewSpec.NavigationNode::id)
                .toList();
    }

    private static int vanillaLabelBudget(String label) {
        return label.codePointCount(0, label.length()) * 6 + 8;
    }

    private static ClientSnapshot withState(
            ClientSnapshot base,
            Optional<SessionValidation> session,
            boolean rateLimited,
            boolean busy,
            AppearanceSyncStatus syncStatus) {
        return new ClientSnapshot(
                base.lifecycle(),
                base.account(),
                session,
                base.remoteProfile(),
                base.lastMutation(),
                base.selectedSkinId(),
                base.selectedPresetId(),
                base.selectedCapeId(),
                base.currentOfficialSkinId(),
                base.activePresetId(),
                base.editor(),
                base.addSource(),
                base.status(),
                busy,
                rateLimited,
                base.galleryOffset(),
                base.generation(),
                base.intentRevision(),
                syncStatus,
                false);
    }

    private static ClientSnapshot withDurableStatus(
            ClientSnapshot base, AppearanceSyncStatus status) {
        return new ClientSnapshot(
                base.lifecycle(),
                base.account(),
                base.session(),
                base.remoteProfile(),
                Optional.empty(),
                base.selectedSkinId(),
                Optional.empty(),
                base.selectedCapeId(),
                base.currentOfficialSkinId(),
                base.activePresetId(),
                base.editor(),
                base.addSource(),
                base.status(),
                base.busy(),
                false,
                base.galleryOffset(),
                base.generation(),
                7,
                status,
                false);
    }

    private static ClientSnapshot withStatus(ClientSnapshot base, UiMessage status) {
        return new ClientSnapshot(
                base.lifecycle(),
                base.account(),
                base.session(),
                base.remoteProfile(),
                base.lastMutation(),
                base.selectedSkinId(),
                base.selectedPresetId(),
                base.selectedCapeId(),
                base.currentOfficialSkinId(),
                base.activePresetId(),
                base.editor(),
                base.addSource(),
                status,
                base.busy(),
                base.rateLimited(),
                base.galleryOffset(),
                base.generation(),
                base.intentRevision(),
                base.syncStatus(),
                base.syncInProgress());
    }

    private static ClientSnapshot withSessionActivity(
            ClientSnapshot base, ClientSnapshot.SessionActivity sessionActivity) {
        return new ClientSnapshot(
                base.lifecycle(),
                base.account(),
                base.session(),
                base.remoteProfile(),
                base.lastMutation(),
                base.selectedSkinId(),
                base.selectedPresetId(),
                base.selectedCapeId(),
                base.currentOfficialSkinId(),
                base.activePresetId(),
                base.editor(),
                base.addSource(),
                base.status(),
                base.busy(),
                base.rateLimited(),
                base.rateLimitProgress(),
                base.galleryOffset(),
                base.generation(),
                base.intentRevision(),
                base.syncStatus(),
                base.syncInProgress(),
                sessionActivity);
    }

    private static ClientSnapshot withSyncInProgress(ClientSnapshot base) {
        return new ClientSnapshot(
                base.lifecycle(),
                base.account(),
                base.session(),
                base.remoteProfile(),
                base.lastMutation(),
                base.selectedSkinId(),
                base.selectedPresetId(),
                base.selectedCapeId(),
                base.currentOfficialSkinId(),
                base.activePresetId(),
                base.editor(),
                base.addSource(),
                base.status(),
                base.busy(),
                base.rateLimited(),
                base.galleryOffset(),
                base.generation(),
                base.intentRevision(),
                base.syncStatus(),
                true);
    }

    private static int panelX(ViewSpec view, String id) {
        return view.panels().stream()
                .filter(panel -> panel.id().equals(id))
                .findFirst()
                .orElseThrow()
                .bounds()
                .x();
    }

    private static Bounds galleryViewport(ViewSpec view) {
        return view.clipRegions().stream()
                .filter(region -> region.id().equals("gallery.cards"))
                .findFirst()
                .orElseThrow()
                .bounds();
    }

    private static ViewSpec.Text text(ViewSpec view, String id) {
        return view.texts().stream()
                .filter(text -> text.id().equals(id))
                .findFirst()
                .orElseThrow();
    }

    private static boolean intersects(Bounds candidate, Bounds viewport) {
        return candidate.right() > viewport.x()
                && candidate.x() < viewport.right()
                && candidate.bottom() > viewport.y()
                && candidate.y() < viewport.bottom();
    }

    private static void assertHorizontallyCentered(Bounds bounds, int viewportWidth) {
        assertTrue(
                Math.abs(bounds.x() + bounds.width() / 2.0 - viewportWidth / 2.0) <= 0.5,
                () -> bounds + " is not horizontally centered in " + viewportWidth);
    }

    private static ClientSnapshot initializingSnapshot(
            Optional<AccountState> account, Optional<UUID> activePresetId) {
        return new ClientSnapshot(
                ClientSnapshot.Lifecycle.INITIALIZING,
                account,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                activePresetId,
                Optional.empty(),
                Optional.empty(),
                UiMessage.info("nclskins.status.loading"),
                true,
                false,
                0,
                1,
                0,
                AppearanceSyncStatus.LOCAL_ONLY,
                false);
    }

    private static ClientSnapshot withCompatibility(
            ClientSnapshot base,
            SkinExtensionEnvironment environment,
            Map<UUID, SkinFeatureEvidence> evidence,
            boolean hideGallery) {
        return new ClientSnapshot(
                base.lifecycle(), base.account(), base.session(), base.remoteProfile(),
                base.lastMutation(), base.selectedSkinId(), base.selectedPresetId(),
                base.selectedCapeId(), base.currentOfficialSkinId(), base.activePresetId(),
                base.editor(), base.addSource(), base.status(), base.busy(), base.rateLimited(),
                base.rateLimitProgress(), base.galleryOffset(), base.generation(),
                base.intentRevision(), base.syncStatus(), base.syncInProgress(),
                base.sessionActivity(), environment, evidence, Map.of(), false, hideGallery);
    }
}

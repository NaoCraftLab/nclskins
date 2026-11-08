package com.naocraftlab.skins.runtime;

import com.naocraftlab.skins.client.OuterLayerVisibility;
import com.naocraftlab.skins.client.PreviewRenderer;
import com.naocraftlab.skins.core.model.SkinVariant;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;

final class GalleryRefinementTest {
    private final GalleryPresenter presenter = new GalleryPresenter();

    private ViewSpec view(int width, int height, int count, double offset) {
        var account = TestFixtures.account(3);
        var snapshot = TestFixtures.ready(account, account.presets().get(0).id(), 0);
        var mask = OuterLayerVisibility.allVisible();
        var draft = new GalleryPresenter.DraftInput(snapshot.activePresetId(), mask, mask, 0,
                Optional.empty(), java.util.Set.of(), Optional.empty(), PreviewRenderer.DisplayKind.ORDINARY);
        var capes = java.util.stream.IntStream.range(0, count).mapToObj(i -> new GalleryPresenter.CapeChoice(
                "source" + i, UiMessage.info("cape"), Optional.of("cape" + i), true)).toList();
        var controls = new GalleryPresenter.ControlsInput(true, mask, PreviewInteractionModel.gallery(),
                Optional.empty(), capes, offset, Optional.empty());
        return presenter.present(snapshot, width, height, 0, 0, PreviewRenderer.CapeMode.CAPE,
                SkinVariant.CLASSIC, "", Optional.empty(), 0, null, draft, controls);
    }

    @Test void cardChromeUsesFullStripGreenActiveAndFluidActionsWithInterleavedTab() {
        var account = TestFixtures.account(3);
        var snapshot = TestFixtures.ready(account,account.presets().get(0).id(),0);
        for (int width : List.of(240,320,427,854,1001)) {
            var normal = presenter.present(snapshot,width,480,0,0,PreviewRenderer.CapeMode.CAPE);
            var card = normal.widget("gallery.card."+account.presets().get(0).id()).orElseThrow();
            assertEquals(Optional.of("active"),card.value());
            var bar = normal.panels().stream().filter(p -> p.id().equals("gallery.preset."+account.presets().get(0).id()+".namebar")).findFirst().orElseThrow();
            assertEquals(card.bounds().x(),bar.bounds().x());
            assertEquals(card.bounds().width(),bar.bounds().width());
            var title = normal.texts().stream().filter(t -> t.id().equals("gallery.preset."+account.presets().get(0).id()+".name")).findFirst().orElseThrow();
            assertEquals(bar.bounds().y()+4,title.bounds().y());
            var order = normal.navigationNodes().stream().filter(n -> n.enabled() && n.tabOrder()>=0)
                    .sorted(java.util.Comparator.comparingInt(ViewSpec.NavigationNode::tabOrder)).map(ViewSpec.NavigationNode::id).toList();
            for (var preset : account.presets()) {
                int at = order.indexOf("gallery.card."+preset.id());
                assertTrue(at>=0);
                assertEquals("gallery.preset."+preset.id()+".more",order.get(at+1));
            }
            var mask=OuterLayerVisibility.allVisible();
            var draft = new GalleryPresenter.DraftInput(snapshot.activePresetId(),mask,mask,0,Optional.empty(),java.util.Set.of(),Optional.empty(),PreviewRenderer.DisplayKind.ORDINARY);
            var controls = new GalleryPresenter.ControlsInput(true,mask,PreviewInteractionModel.gallery(),Optional.of(account.presets().get(0).id()),List.of(),0,Optional.empty());
            var menu=presenter.present(snapshot,width,480,0,0,PreviewRenderer.CapeMode.CAPE,SkinVariant.CLASSIC,"",Optional.empty(),0,null,draft,controls);
            String prefix="gallery.preset."+account.presets().get(0).id()+".";
            var expandedTitle=menu.texts().stream().filter(t -> t.id().equals(prefix+"name")).findFirst().orElseThrow();
            assertEquals(card.bounds().y()+5,expandedTitle.bounds().y());
            Bounds left=menu.widget(prefix+"favorite").orElseThrow().bounds();
            Bounds right=menu.widget(prefix+"edit").orElseThrow().bounds();
            assertEquals(card.bounds().x()+2,left.x());
            assertEquals(card.bounds().right()-2,right.right());
            assertEquals(2,right.x()-left.right());
            assertTrue(Math.abs(left.width()-right.width())<=1);
            assertEquals(20,left.height());
        }
    }

    @Test void nativeCapeTooltipUsesVisibleOwnerAndLowerClip() {
        var b=new Bounds(10,20,32,32);
        var card=new ViewSpec.Widget("gallery.inspect.CPM",ViewSpec.WidgetKind.CAPE_CARD,b,UiMessage.info("CPM"),Optional.empty(),Optional.of(UiMessage.info("CPM cape")),true,true,0);
        var view=new ViewSpec("gallery",UiMessage.info("gallery"),200,200,List.of(),List.of(),List.of(card),List.of(),Optional.empty(),List.of(),Optional.empty(),List.of(new ViewSpec.ClipRegion("gallery.stage",new Bounds(0,30,60,40),List.of(card.id()))),List.of());
        assertTrue(ViewHostPolicy.passiveIndicatorTooltip(view,20,35,null).isPresent());
        assertTrue(ViewHostPolicy.passiveIndicatorTooltip(view,20,25,null).isEmpty());
    }

    @Test void lowerScrollbarParticipatesInPhysicalPointerCapture() {
        var view=view(240,150,31,0);
        var lower=view.scrollSurface("gallery.stage").orElseThrow().viewport();
        assertTrue(PointerRouting.hit(view,lower.right()-3,lower.y()+3).scrollbar());
        assertFalse(PointerRouting.hit(view,lower.right()-3,lower.y()-1).scrollbar());
        var divider = view.panels().stream().filter(p -> p.id().equals("gallery.workspace")).findFirst().orElseThrow().bounds().x();
        var track = ViewHostPolicy.scrollbar(view,"gallery.stage").orElseThrow().track();
        assertEquals(divider,track.right());
        for (int x=track.x();x<track.right();x++) {
            assertTrue(PointerRouting.hit(view,x,track.y()+3).scrollbar());
            assertEquals("gallery.stage",PointerRouting.scrollSurface(view,x,track.y()+3).orElseThrow().id());
        }
        assertEquals("gallery.cards",PointerRouting.scrollSurface(view,divider+8,lower.y()+3).orElseThrow().id());
    }

    @Test void footerUsesVanillaRoundedVerticalCenter() {
        for (int height : List.of(150,239,240,479,480,481)) {
            var view=view(854,height,1,0);
            var footer=view.panels().stream().filter(p -> p.id().equals("footer")).findFirst().orElseThrow().bounds();
            assertEquals(footer.y()+Math.round((footer.height()-20)*0.5F),view.widget("gallery.done").orElseThrow().bounds().y());
        }
    }

    @Test void soleNoneIsNeutral() {
        var account=TestFixtures.account(1);
        var snapshot=TestFixtures.ready(account,account.presets().get(0).id(),0);
        var mask=OuterLayerVisibility.allVisible();
        var draft=new GalleryPresenter.DraftInput(snapshot.activePresetId(),mask,mask,0,Optional.empty(),java.util.Set.of(),Optional.empty(),PreviewRenderer.DisplayKind.ORDINARY);
        var controls=new GalleryPresenter.ControlsInput(true,mask,PreviewInteractionModel.gallery(),Optional.empty(),List.of(new GalleryPresenter.CapeChoice("none",UiMessage.info("none"),Optional.empty(),false)),0,Optional.empty());
        var view=presenter.present(snapshot,854,480,0,0,PreviewRenderer.CapeMode.CAPE,SkinVariant.CLASSIC,"",Optional.empty(),0,null,draft,controls);
        assertEquals(Optional.empty(),view.widget("gallery.inspect.none").orElseThrow().value());
    }

    @Test void fixedPreviewAndCenteredStandardCellsHaveExactLowerClipAndStableGutters() {
        for (int[] size : List.of(new int[]{854,480}, new int[]{1200,480}, new int[]{320,240}, new int[]{240,150}, new int[]{120,480})) {
            var initial = view(size[0], size[1], 31, 0);
            var scrolled = view(size[0], size[1], 31, 17.5);
            var fit = view(size[0], size[1], 1, 0);
            var lower = initial.scrollSurface("gallery.stage").orElseThrow().viewport();
            var preview = initial.previews().get(0).anchorBounds();
            assertEquals(preview, scrolled.previews().get(0).anchorBounds(), "lower scroll must not move model fit");
            assertEquals(lower, fit.scrollSurface("gallery.stage").orElseThrow().viewport());
            assertEquals(size[1]-33, lower.bottom(), "lower clip must reach exact footer top");
            assertTrue(lower.y() >= preview.bottom());
            assertEquals(lower, initial.clipFor("gallery.inspect.source0").orElseThrow());
            assertTrue(initial.clipFor("gallery.preview").isEmpty(), "fullscreen draw is not lower content");
            var cells = initial.widgets().stream().filter(w -> w.id().startsWith("gallery.inspect.")).toList();
            int columns = Math.max(1, (lower.width()-16+2)/34);
            for (int start=0; start<cells.size(); start+=columns) {
                int length = Math.min(columns, cells.size()-start);
                var first = cells.get(start).bounds();
                var last = cells.get(start+length-1).bounds();
                assertTrue(Math.abs(first.x()+last.right()-lower.x()-lower.right())<=1, "center every row");
                for (int i=start;i<start+length;i++) {
                    assertEquals(32,cells.get(i).bounds().width());
                    assertEquals(32,cells.get(i).bounds().height());
                    assertTrue(cells.get(i).bounds().x()>=lower.x()+8);
                    assertTrue(cells.get(i).bounds().right()<=lower.right()-8);
                }
            }
            var bar = initial.scrollbar().orElseThrow();
            assertEquals(size[0],bar.track().right());
            assertEquals(6,bar.track().width());
        }
        assertEquals(view(854,480,31,0).previews().get(0).anchorBounds(),view(1200,480,31,0).previews().get(0).anchorBounds());
    }
}

package com.naocraftlab.skins.client;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class EditorPreviewFitGateTest {
    @Test void suppressionPreservesOwnImageConsumptionGate() {
        var session=new EditorPreviewSession();
        session.beginBody("A","demand",false,true);
        session.beginImage("image");
        session.suppressFit("A");
        assertTrue(session.imageTracked());
        assertTrue(session.imagePreparing());
        var hidden=session.ordinaryDisplay(()->true);
        assertTrue(hidden.requiresConsumption());
        hidden.accept(new PreviewModelFrames.Body(PreviewRenderer.BodyStatus.ORDINARY,()->true));
        assertTrue(session.imageVisible());
        assertFalse(session.body().current().getAsBoolean());
        assertTrue(session.ordinaryDisplay(()->true).requiresConsumption());
    }

    @Test void lateFitAdmissionPreservesPendingImageGate() {
        var session=new EditorPreviewSession();
        session.beginBody("A","demand",true,true);
        session.body(new PreviewModelFrames.Body(PreviewRenderer.BodyStatus.READY,()->true));
        session.beginImage("image");
        session.prepareFit();
        assertTrue(session.imageTracked());
        assertTrue(session.imagePreparing());
        assertTrue(((PreviewModelFrames.Completion)session.displayBody(()->true)).requiresConsumption());
    }

    @Test void imageOnlyRolloverPreservesNumericDemandAndOriginalCpmDeadline() {
        long[] now = {0};
        try (var gallery = new EditorPreviewSession.Gallery(() -> now[0])) {
            var a = gallery.prepare("source", "numeric-demand", true, true, () -> true);
            a.body(new PreviewModelFrames.Body(PreviewRenderer.BodyStatus.READY, () -> true));
            gallery.prepareImage("image-A", 0, true, false);
            a.imagePreparation(() -> true).accept(new PreviewModelFrames.Body(PreviewRenderer.BodyStatus.READY, () -> true));
            a.displayBody(() -> true).accept(new PreviewModelFrames.Body(PreviewRenderer.BodyStatus.READY, () -> true));
            gallery.confirm("source");
            gallery.image("source", new PreviewRenderer.PreviewRequest(
                    new PreviewRenderer.PreviewAppearance(new TextureRegistry.TextureHandle("A", 64, 64), SkinModel.CLASSIC,
                            java.util.Optional.empty(), PreviewRenderer.CapeMode.OFF, OuterLayerVisibility.allVisible()),
                    0, 0, 100, 100, 0, 0, 1, PreviewRenderer.PreviewIntent.EDITOR_DRAFT), () -> true);
            now[0] = Duration.ofSeconds(59).toNanos();
            var b = gallery.prepareImage("image-B", now[0], false, false);
            assertNotSame(a, b);
            assertSame(b, gallery.prepare("source", "numeric-demand", true, true, () -> true));
            var fit = b.prepareFit();
            assertTrue(fit.current());
            assertTrue(gallery.displays(a));
            assertFalse(b.body().current().getAsBoolean());
            now[0] = Duration.ofSeconds(60).toNanos();
            assertFalse(fit.current());
            assertEquals(PreviewRenderer.BodyStatus.TIMED_OUT, b.bodyStatus());
            assertTrue(b.imageTracked());
            fit.complete();
            assertEquals(PreviewRenderer.BodyStatus.TIMED_OUT, b.bodyStatus());
            var next = gallery.prepare("new-source", "new-demand", true, true, () -> true);
            assertTrue(next.prepareFit().current());
        }
    }

    @Test void displayedSessionReuseRequiresBothLifetimeAndMatchingNormalizationDemand() {
        boolean[] lifetime = {true};
        try (var gallery = new EditorPreviewSession.Gallery()) {
            var a = gallery.prepare("A", "demand", false, true, () -> true);
            gallery.lifetime("A", () -> lifetime[0]);
            a.ordinaryDisplay(() -> true).accept(new PreviewModelFrames.Body(PreviewRenderer.BodyStatus.ORDINARY, () -> true));
            gallery.confirm("A");
            gallery.prepare("B", "other", false, true, () -> true);
            assertSame(a, gallery.prepare("A", "demand", false, true, () -> true));
            gallery.prepare("B", "other", false, true, () -> true);
            assertNotSame(a, gallery.prepare("A", "new-demand", false, true, () -> true));
            gallery.prepare("B", "other", false, true, () -> true);
            lifetime[0] = false;
            assertNotSame(a, gallery.prepare("A", "demand", false, true, () -> true));
        }
    }

    @Test void terminalFitAndRetiredOwnerHaveDistinctLifetimes() {
        long[] now={0};var session=new EditorPreviewSession(() -> now[0]);
        session.beginBody("S0","demand",true,true);var original=session.prepareFit();
        now[0]=Duration.ofSeconds(60).toNanos();
        assertFalse(original.current());assertTrue(original.ownerCurrent());
        session.declineFit();assertTrue(original.ownerCurrent());
        session.beginBody("S1","demand",true,true);assertFalse(original.ownerCurrent());
        var successor=session.prepareFit();assertTrue(successor.ownerCurrent());
        session.retireBody();assertFalse(successor.ownerCurrent());
    }

    @Test void galleryReusesDisplayedSessionBeforeSuccessorAckButNotAfterItsRetirement() {
        try(var gallery=new EditorPreviewSession.Gallery()) {
            var s0=gallery.prepare("S0","demand",true,true,()->true);
            var ready=new PreviewModelFrames.Body(PreviewRenderer.BodyStatus.READY,()->true);
            s0.body(ready);s0.displayBody(()->true).accept(ready);gallery.confirm("S0");
            var s1=gallery.prepare("S1","demand",true,true,()->true);
            var late=s1.displayBody(()->true);
            assertTrue(gallery.displays(s0));assertTrue(gallery.retains(s0));
            assertSame(s0,gallery.prepare("S0","demand",true,true,()->true));
            assertTrue(gallery.body().current().getAsBoolean());
            late.accept(ready);assertTrue(gallery.displays(s0));assertFalse(gallery.retains(s1));
            var accepted=gallery.prepare("S1","demand",true,true,()->true);
            accepted.body(ready);accepted.displayBody(()->true).accept(ready);gallery.confirm("S1");
            assertFalse(gallery.retains(s0));
            assertNotSame(s0,gallery.prepare("S0","demand",true,true,()->true));
        }
    }

    @Test void suppressionIsSourceBoundAndLateFitCannotRestoreFullAdmission() {
        var session = new EditorPreviewSession();
        session.beginBody("A", "demand", true, true);
        var old = session.prepareFit();
        session.suppressFit("foreign");
        assertFalse(session.fitSuppressed());
        session.body(new PreviewModelFrames.Body(PreviewRenderer.BodyStatus.READY, () -> true));
        session.suppressFit("A");
        assertTrue(session.fitSuppressed());
        assertFalse(old.current());
        old.complete();
        assertTrue(session.fitSuppressed());
        assertEquals(PreviewRenderer.BodyStatus.READY,session.bodyStatus());
        session.ordinaryDisplay(() -> true).accept(new PreviewModelFrames.Body(PreviewRenderer.BodyStatus.ORDINARY, () -> true));
        assertEquals(PreviewRenderer.BodyStatus.ORDINARY,session.body().status());
        session.beginBody("A:refined", "demand", true, true);
        session.suppressFit("A:refined");
        assertTrue(session.fitSuppressed());
        session.beginBody("B", "new-demand", true, true);
        assertFalse(session.fitSuppressed());
        assertTrue(session.prepareFit().current());
    }

    @Test void legacyNoNormalizationSessionKeepsItsOwnFullBodyAdmission() {
        var session = new EditorPreviewSession();
        session.beginBody("legacy", true, true);
        var ready = new PreviewModelFrames.Body(PreviewRenderer.BodyStatus.READY, () -> true);
        session.body(ready);
        session.displayBody(() -> true).accept(ready);
        assertFalse(session.fitSuppressed());
        assertEquals(PreviewRenderer.BodyStatus.READY,session.body().status());
    }

    @Test void declinedFitReleasesOnlyNormalizationGateAndCannotReviveTimeout() {
        long[] now = {0};
        var session = new EditorPreviewSession(() -> now[0]);
        session.beginBody("A", true, true);
        var fit = session.prepareFit();
        session.body(new PreviewModelFrames.Body(PreviewRenderer.BodyStatus.READY, () -> true));
        assertEquals(PreviewRenderer.BodyStatus.PENDING, session.bodyStatus());
        session.declineFit();
        assertFalse(fit.current());
        assertEquals(PreviewRenderer.BodyStatus.READY, session.bodyStatus());
        assertTrue(session.frame().getAsBoolean());
        assertFalse(session.body().current().getAsBoolean());
        session.ordinaryDisplay(() -> true).accept(new PreviewModelFrames.Body(PreviewRenderer.BodyStatus.ORDINARY, () -> true));
        assertEquals(PreviewRenderer.BodyStatus.ORDINARY, session.body().status());
        session.beginBody("B", true, true);
        session.prepareFit();
        now[0] = Duration.ofSeconds(60).toNanos();
        assertEquals(PreviewRenderer.BodyStatus.TIMED_OUT, session.bodyStatus());
        session.declineFit();
        assertEquals(PreviewRenderer.BodyStatus.TIMED_OUT, session.bodyStatus());
        assertFalse(session.frame().getAsBoolean());
    }
    @Test void galleryReadinessKeyRefinementPreservesOriginalDeadlineButNewDemandRenewsIt() {
        long[] now = {0};
        try (var gallery = new EditorPreviewSession.Gallery(() -> now[0])) {
            var initial = gallery.prepare("A:no-accessories", "A", true, true, () -> true);
            var first = initial.prepareFit();
            now[0] = Duration.ofSeconds(59).toNanos();
            var refined = gallery.prepare("A:native-cape", "A", true, true, () -> true);
            assertNotSame(initial, refined);
            assertFalse(first.current());
            var fit = refined.prepareFit();
            refined.body(new PreviewModelFrames.Body(PreviewRenderer.BodyStatus.READY, () -> true));
            assertEquals(PreviewRenderer.BodyStatus.PENDING, refined.bodyStatus());
            now[0] = Duration.ofSeconds(60).toNanos();
            assertFalse(fit.current());
            assertEquals(PreviewRenderer.BodyStatus.TIMED_OUT, refined.bodyStatus());
            var next = gallery.prepare("B", "B", true, true, () -> true);
            assertTrue(next.prepareFit().current());
            assertEquals(PreviewRenderer.BodyStatus.PENDING, next.bodyStatus());
        }
    }

    @Test void newSemanticDemandRenewsDeadlineEvenWhenNativeSourceStringIsReused() {
        long[] now = {0};
        var session = new EditorPreviewSession(() -> now[0]);
        session.beginBody("same-source", "opening-A", true, true);
        var old=session.prepareFit();
        now[0] = Duration.ofSeconds(60).toNanos();
        assertFalse(old.current());
        session.beginBody("same-source", "opening-B", true, true);
        assertEquals(PreviewRenderer.BodyStatus.PENDING, session.bodyStatus());
        assertTrue(session.prepareFit().current());
    }

    @Test void editorReadinessKeyRefinementCannotResetOriginalSourceDeadline() {
        long[] now = {0};
        var session = new EditorPreviewSession(() -> now[0]);
        session.beginBody("A:no-accessories", "A", true, true);
        now[0] = Duration.ofSeconds(59).toNanos();
        session.beginBody("A:native-cape", "A", true, true);
        var fit = session.prepareFit();
        now[0] = Duration.ofSeconds(60).toNanos();
        assertFalse(fit.current());
        assertEquals(PreviewRenderer.BodyStatus.TIMED_OUT, session.bodyStatus());
    }

    @Test void galleryRetirementRevokesPendingNumericAdmissionWithoutRevokingRetainedPredecessor() {
        var gallery = new EditorPreviewSession.Gallery();
        try {
            var a = gallery.prepare("A", true, true, () -> true);
            assertFalse(gallery.displays(a));
            var fitA = a.prepareFit();
            a.body(new PreviewModelFrames.Body(PreviewRenderer.BodyStatus.READY, () -> true));
            fitA.complete();
            a.displayBody(() -> true).accept(new PreviewModelFrames.Body(PreviewRenderer.BodyStatus.READY, () -> true));
            gallery.confirm("A");
            assertTrue(gallery.displays(a));
            var b = gallery.prepare("B", true, true, () -> true);
            var fitB = b.prepareFit();
            assertFalse(gallery.displays(b));
            gallery.prepare("C", true, true, () -> true);
            assertFalse(fitB.current());
            assertTrue(fitA.current());
            assertTrue(gallery.displays(a));
            assertTrue(a.body().current().getAsBoolean());
            gallery.close();
            assertFalse(fitA.current());
            assertFalse(gallery.displays(a));
        } finally {
            gallery.close();
        }
    }

    @Test void modelReadyBeforeFitStillExpiresAtOriginalDeadline() {
        long[] now = {0};
        var session = new EditorPreviewSession(() -> now[0]);
        session.beginBody("A", true, true);
        now[0] = Duration.ofSeconds(59).toNanos();
        var fit = session.prepareFit();
        session.body(new PreviewModelFrames.Body(PreviewRenderer.BodyStatus.READY, () -> true));
        assertEquals(PreviewRenderer.BodyStatus.PENDING, session.bodyStatus());
        assertFalse(session.frame().getAsBoolean());
        now[0] = Duration.ofSeconds(60).toNanos();
        assertFalse(fit.current(), "First-create admission must expire even before a client status query");
        assertEquals(PreviewRenderer.BodyStatus.TIMED_OUT, session.bodyStatus());
        fit.complete();
        assertEquals(PreviewRenderer.BodyStatus.TIMED_OUT, session.bodyStatus());
        assertFalse(session.frame().getAsBoolean());
    }

    @Test void fitDoesNotGrantDisplayAuthorityAndSuccessfulJoinDoesNotExpireLater() {
        long[] now = {0};
        var session = new EditorPreviewSession(() -> now[0]);
        session.beginBody("A", true, true);
        var fit = session.prepareFit();
        fit.complete();
        assertEquals(PreviewRenderer.BodyStatus.PENDING, session.bodyStatus());
        assertFalse(session.body().current().getAsBoolean());
        session.body(new PreviewModelFrames.Body(PreviewRenderer.BodyStatus.READY, () -> true));
        assertEquals(PreviewRenderer.BodyStatus.READY, session.bodyStatus());
        assertFalse(session.body().current().getAsBoolean());
        session.displayBody(() -> true).accept(new PreviewModelFrames.Body(PreviewRenderer.BodyStatus.READY, () -> true));
        assertTrue(session.body().current().getAsBoolean());
        now[0] = Duration.ofSeconds(61).toNanos();
        assertTrue(session.body().current().getAsBoolean());
    }

    @Test void sourceReplacementRetiresFitBeforeLateCompletionAndCoalescesCurrentPreparation() {
        var session = new EditorPreviewSession();
        session.beginBody("A", true, true);
        var a = session.prepareFit();
        assertSame(a, session.prepareFit());
        session.beginBody("B", true, true);
        var b = session.prepareFit();
        a.complete();
        assertFalse(a.current());
        assertTrue(b.current());
        session.body(new PreviewModelFrames.Body(PreviewRenderer.BodyStatus.READY, () -> true));
        assertEquals(PreviewRenderer.BodyStatus.PENDING, session.bodyStatus());
        b.complete();
        assertEquals(PreviewRenderer.BodyStatus.READY, session.bodyStatus());
    }

    @Test void heldModelReadyAfterSixtySecondsCannotReviveExpiredFit() {
        long[] now = {0};
        var session = new EditorPreviewSession(() -> now[0]);
        session.beginBody("A", true, true);
        var fit = session.prepareFit();
        now[0] = Duration.ofSeconds(60).toNanos();
        session.body(new PreviewModelFrames.Body(PreviewRenderer.BodyStatus.READY, () -> true));
        fit.complete();
        assertFalse(fit.current());
        assertEquals(PreviewRenderer.BodyStatus.TIMED_OUT, session.bodyStatus());
    }
}

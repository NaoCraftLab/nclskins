package com.naocraftlab.skins.client;

import org.junit.jupiter.api.Test;

import static com.naocraftlab.skins.client.EditorPreviewSession.Path.BAKED;
import static com.naocraftlab.skins.client.EditorPreviewSession.Path.LIVE;
import static com.naocraftlab.skins.client.PreviewRenderer.PreviewIntent.ASSET_THUMBNAIL;
import static com.naocraftlab.skins.client.PreviewRenderer.PreviewIntent.CURRENT_APPEARANCE;
import static com.naocraftlab.skins.client.PreviewRenderer.PreviewIntent.EDITOR_DRAFT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EditorPreviewSessionTest {
    @Test void sameSourceImagePrerequisiteCannotRecreateRetiredOwnerDuringMaterialCurrentness() {
        var gallery = new EditorPreviewSession.Gallery();
        boolean[] close = {false};
        var a = gallery.prepare("same",false,false,() -> true);
        gallery.prepareImage("image",0,true,false);
        var material = new PreviewModelFrames.Body(PreviewRenderer.BodyStatus.ORDINARY,() -> {
            if (close[0]) gallery.close();
            return true;
        });
        a.imagePreparation(() -> true).accept(material);
        a.ordinaryDisplay(() -> true).accept(material);
        gallery.image("same",new PreviewRenderer.PreviewRequest(new PreviewRenderer.PreviewAppearance(
                new TextureRegistry.TextureHandle("fixture:retired",64,64),SkinModel.CLASSIC,java.util.Optional.empty(),
                PreviewRenderer.CapeMode.OFF,OuterLayerVisibility.allVisible()),0,0,160,180,0,0,1,CURRENT_APPEARANCE),() -> true);
        gallery.confirm("same");
        close[0]=true;
        assertEquals(null,gallery.prepareImage("image",0,false,false));
        assertFalse(a.imagePreparing());
        assertFalse(gallery.retains(a));
    }

    @Test void sameSourcePreparationRetainsIndependentWholeDisplayedOwnerUntilOwnVisibleSuccessor() {
        long[] now = {0};
        var gallery = new EditorPreviewSession.Gallery(() -> now[0]);
        var a = gallery.prepare("same",false,false,() -> true);
        gallery.prepareImage("image",0,true,false);
        var materialA = new PreviewModelFrames.Body(PreviewRenderer.BodyStatus.ORDINARY,() -> true);
        a.imagePreparation(() -> true).accept(materialA);
        a.ordinaryDisplay(() -> true).accept(materialA);
        var request = new PreviewRenderer.PreviewRequest(new PreviewRenderer.PreviewAppearance(
                new TextureRegistry.TextureHandle("fixture:same",64,64),SkinModel.SLIM,java.util.Optional.empty(),
                PreviewRenderer.CapeMode.OFF,OuterLayerVisibility.noneVisible()),0,0,160,180,0,0,1,CURRENT_APPEARANCE);
        gallery.image("same",request,() -> true);
        gallery.confirm("same");
        var b = gallery.prepareImage("image",0,false,false);
        assertTrue(a != b);
        assertTrue(a.imageVisible());
        assertFalse(b.imageVisible());
        for (long tick : new long[]{0,299_000_000L,300_000_000L,5_000_000_000L}) {
            now[0]=tick;
            assertEquals("same",gallery.retainedSource());
            assertTrue(gallery.retainedSession()==a && gallery.session("same")==b);
            assertTrue(gallery.retainedImage("same")==request);
            assertFalse(gallery.body().current().getAsBoolean());
            assertFalse(b.imageSpinner());
            assertTrue(gallery.prepareImage("image",0,false,false)==b);
        }
        var pending = b.imagePreparation(() -> true);
        pending.accept(materialA);
        assertFalse(b.imageVisible());
        assertTrue(gallery.prepareImage("image",0,true,false)==b);
        pending.accept(materialA);
        assertFalse(b.imageVisible());
        b.imagePreparation(() -> true).accept(new PreviewModelFrames.Body(PreviewRenderer.BodyStatus.ORDINARY,() -> true));
        assertTrue(b.imageVisible());
        assertEquals(null,gallery.retainedSource());
        assertFalse(gallery.body().current().getAsBoolean());
        b.ordinaryDisplay(() -> true).accept(new PreviewModelFrames.Body(PreviewRenderer.BodyStatus.ORDINARY,() -> true));
        gallery.confirm("same");
        assertFalse(gallery.retains(a));
        assertTrue(gallery.retains(b));
        gallery.close();
        assertFalse(b.imageVisible());
    }

    @Test void retainedTransformUsesCurrentSelectionWithoutCandidateRequestOrAuthority() {
        var gallery = new EditorPreviewSession.Gallery();
        boolean[] lifetime = {true};
        var a = gallery.prepare("A",false,false,() -> true);
        gallery.lifetime("A",() -> lifetime[0]);
        gallery.prepareImage("imageA",0,true,false);
        var material = new PreviewModelFrames.Body(PreviewRenderer.BodyStatus.ORDINARY,() -> lifetime[0]);
        a.imagePreparation(() -> true).accept(material);
        a.ordinaryDisplay(() -> true).accept(material);
        var request = new PreviewRenderer.PreviewRequest(new PreviewRenderer.PreviewAppearance(
                new TextureRegistry.TextureHandle("fixture:pose",64,64),SkinModel.SLIM,java.util.Optional.empty(),
                PreviewRenderer.CapeMode.OFF,OuterLayerVisibility.noneVisible()),2,3,160,180,0,0,1,CURRENT_APPEARANCE,12,13,140,150);
        gallery.image("A",request,() -> lifetime[0]);
        gallery.confirm("A");
        var b = gallery.prepare("B",false,false,() -> true);
        gallery.prepareImage("imageB",0,false,false);
        gallery.transformRetained("obsolete",40,15,2);
        assertTrue(gallery.retainedImage("A") == request);
        gallery.transformRetained("B",40,15,2);
        var transformed = gallery.retainedImage("A");
        assertEquals(new PreviewRenderer.PreviewRequest(request.appearance(),2,3,160,180,40,15,2,CURRENT_APPEARANCE,12,13,140,150),transformed);
        assertTrue(transformed.appearance() == request.appearance());
        assertFalse(b.imageVisible());
        assertFalse(gallery.body().current().getAsBoolean());
        gallery.prepare("C",false,false,() -> true);
        gallery.prepareImage("imageC",0,false,false);
        gallery.transformRetained("B",80,30,3);
        assertTrue(gallery.retainedImage("A") == transformed);
        lifetime[0] = false;
        gallery.transformRetained("C",80,30,3);
        assertEquals(null,gallery.retainedImage("A"));
        lifetime[0] = true;
        assertTrue(gallery.retainedImage("A") == transformed);
        gallery.close();
        gallery.transformRetained("C",80,30,3);
        assertEquals(null,gallery.retainedImage("A"));
    }

    @Test void successorImageSessionUsesSameClockWithoutRetiringPredecessorMaterial() {
        long[] now = {0};
        var a = new EditorPreviewSession(() -> now[0]);
        a.beginImage("A");
        a.imagePreparation(() -> true).accept(new PreviewModelFrames.Body(PreviewRenderer.BodyStatus.ORDINARY,() -> true));
        var b = a.nextImageSession();
        b.beginImage("B");
        now[0]=299_000_000L;
        assertFalse(b.imageSpinner());
        assertTrue(a.imageVisible());
        now[0]=300_000_000L;
        assertTrue(b.imageSpinner());
        a.invalidateDisplayedBody();
        assertFalse(a.imageVisible());
        assertTrue(b.imagePreparing());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void retiredMaterialCompletionCannotTerminalizeOrOverwriteSameImageSuccessor(boolean obsoleteLast) {
        var session = new EditorPreviewSession();
        session.beginImage("same-image", 0);
        var a = session.imagePreparation(() -> true);
        var b = session.imagePreparation(() -> true);
        var target = new ScreenOwnedRenderTarget();
        Object ownerA = new Object(), ownerB = new Object();
        AutoCloseable resourceA = target.acquire(ownerA, () -> (AutoCloseable) () -> {}, AutoCloseable.class);
        var bodyA = new PreviewModelFrames.Body(PreviewRenderer.BodyStatus.ORDINARY, () -> target.owns(ownerA, resourceA));
        AutoCloseable resourceB = target.acquire(ownerB, () -> (AutoCloseable) () -> {}, AutoCloseable.class);
        var bodyB = new PreviewModelFrames.Body(PreviewRenderer.BodyStatus.ORDINARY, () -> target.owns(ownerB, resourceB));
        assertFalse(bodyA.current().getAsBoolean());
        assertTrue(bodyB.current().getAsBoolean());
        if (obsoleteLast) {
            b.accept(bodyB);
            assertTrue(session.imageVisible());
            a.accept(bodyA);
            assertTrue(session.imageVisible());
        } else {
            a.accept(bodyA);
            assertTrue(session.imagePreparing());
            assertFalse(session.imageVisible());
            b.accept(bodyB);
            assertTrue(session.imageVisible());
        }
        target.close();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {true, false})
    void retainedWholeRecipeUsesDisplayLifetimeNotRetiredCandidateAuthorityAndReturnsToItsOwnOwner(boolean indicator) {
        long[] now = {0};
        boolean[] candidateA = {true}, lifetime = {true};
        var gallery = new EditorPreviewSession.Gallery(() -> now[0]);
        var a = gallery.prepare("A", false, false, () -> candidateA[0]);
        a.beginImage("A", 0);
        var material = new PreviewModelFrames.Body(PreviewRenderer.BodyStatus.ORDINARY, () -> lifetime[0]);
        a.imagePreparation(() -> true).accept(material);
        a.ordinaryDisplay(() -> true).accept(material);
        var recipe = new PreviewRenderer.PreviewRequest(new PreviewRenderer.PreviewAppearance(
                new TextureRegistry.TextureHandle("fixture:A", 64, 64), SkinModel.CLASSIC, java.util.Optional.empty(),
                PreviewRenderer.CapeMode.OFF, OuterLayerVisibility.allVisible()), 0, 0, 160, 180, 0, 0, 1, EDITOR_DRAFT);
        gallery.lifetime("A", () -> lifetime[0]);
        gallery.image("A", recipe, () -> lifetime[0]);
        gallery.confirm("A");
        candidateA[0] = false;
        var b = gallery.prepare("B", false, false, () -> true);
        b.imageIndicator(indicator);
        b.beginImage("B", 0);
        for (long tick : new long[]{0, 299_000_000L}) {
            now[0] = tick;
            assertEquals("A", gallery.retainedSource());
            assertTrue(gallery.retainedImage("A") == recipe);
            assertFalse(gallery.body().current().getAsBoolean(), "retained A cannot authorize B");
        }
        for (long tick : new long[]{300_000_000L, 5_000_000_000L, 120_000_000_000L}) {
            now[0] = tick;
            assertEquals(indicator ? null : "A", gallery.retainedSource());
            assertEquals(indicator, b.imageSpinner());
            assertFalse(gallery.body().current().getAsBoolean(), "retained A never acknowledges B");
        }
        assertTrue(gallery.retainedTextures().contains(recipe.appearance().skin()), "A remains owned internally after visibility deadline");
        var retiredB = b.imagePreparation(() -> true);
        var c = gallery.prepare("C", false, false, () -> true);
        c.beginImage("C", now[0]);
        retiredB.accept(material);
        assertFalse(c.imageVisible());
        assertEquals("A", gallery.retainedSource());
        candidateA[0] = true;
        assertTrue(a == gallery.prepare("A", false, false, () -> candidateA[0]), "return before successor ACK reuses the exact still-owned A session");
        assertTrue(gallery.body().current().getAsBoolean());
        var d = gallery.prepare("D", false, false, () -> true);
        d.beginImage("D", now[0]);
        lifetime[0] = false;
        assertEquals(null, gallery.retainedSource());
        assertTrue(gallery.retainedTextures().isEmpty(), "lifecycle retirement drops material pins");
        gallery.close();
        assertFalse(gallery.retains(a));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {true, false})
    void earlyOwnSuccessorDoesNotWaitForDelayOrBorrowPredecessorAuthority(boolean indicator) {
        long[] now = {0};
        var gallery = new EditorPreviewSession.Gallery(() -> now[0]);
        var a = gallery.prepare("A", false, false, () -> true);
        a.beginImage("A", 0);
        a.imagePreparation(() -> true).accept(new PreviewModelFrames.Body(PreviewRenderer.BodyStatus.ORDINARY, () -> true));
        a.ordinaryDisplay(() -> true).accept(new PreviewModelFrames.Body(PreviewRenderer.BodyStatus.ORDINARY, () -> true));
        gallery.confirm("A");
        var b = gallery.prepare("B", false, false, () -> true);
        b.imageIndicator(indicator);
        b.beginImage("B", 0);
        now[0] = 25_000_000L;
        b.imagePreparation(() -> true).accept(new PreviewModelFrames.Body(PreviewRenderer.BodyStatus.ORDINARY, () -> true));
        assertTrue(b.imageVisible());
        assertFalse(b.imageSpinner());
        assertEquals(null, gallery.retainedSource());
        assertFalse(gallery.body().current().getAsBoolean());
        b.ordinaryDisplay(() -> true).accept(new PreviewModelFrames.Body(PreviewRenderer.BodyStatus.ORDINARY, () -> true));
        gallery.confirm("B");
        assertTrue(gallery.body().current().getAsBoolean());
        assertFalse(gallery.retains(a));
        gallery.close();
    }

    @Test void noIndicatorFirstOpenStaysEmptyAndRetiredLatestPreparationCannotReviveIt() {
        long[] now = {0};
        var gallery = new EditorPreviewSession.Gallery(() -> now[0]);
        var b = gallery.prepare("B", false, false, () -> true);
        b.imageIndicator(false);
        b.beginImage("B", 0);
        var stale = b.imagePreparation(() -> true);
        now[0] = 120_000_000_000L;
        assertFalse(b.imageSpinner());
        assertFalse(b.imageVisible());
        assertEquals(null, gallery.retainedSource());
        var c = gallery.prepare("C", false, false, () -> true);
        c.imageIndicator(false);
        c.beginImage("C", now[0]);
        stale.accept(new PreviewModelFrames.Body(PreviewRenderer.BodyStatus.ORDINARY, () -> true));
        assertFalse(c.imageVisible());
        var completion = c.imagePreparation(() -> true);
        gallery.close();
        completion.accept(new PreviewModelFrames.Body(PreviewRenderer.BodyStatus.ORDINARY, () -> true));
        assertFalse(c.imageVisible());
        assertFalse(gallery.body().current().getAsBoolean());
    }

    @Test void replacementAuthorityPreservesNewHiddenAttemptUntilItsSeparateVisiblePass() {
        long[] now = {0};
        var session = new EditorPreviewSession(() -> now[0]);
        Object world = new Object(), player = new Object();
        session.beginBody("A", false, true);
        session.beginImage(new Object());
        session.displayAuthority("material-A", world, player);
        session.displayBody(() -> true).accept(new PreviewModelFrames.Body(PreviewRenderer.BodyStatus.ORDINARY, () -> true));
        var oldVisible = session.displayBody(() -> true);
        oldVisible.accept(new PreviewModelFrames.Body(PreviewRenderer.BodyStatus.ORDINARY, () -> true));
        assertTrue(session.body().current().getAsBoolean());
        now[0] = 1_000_000_000L;
        session.beginBody("B", false, true);
        Object attempt = new Object();
        session.beginImage(attempt, now[0]);
        now[0] += 299_000_000L;
        session.displayAuthority("material-B", world, player);
        assertTrue(session.imageTracked(), "new B preparation must survive replacement of A authority");
        assertTrue(session.imagePreparing());
        assertFalse(session.imageSpinner());
        now[0] += 1_000_000L;
        session.beginImage(attempt, now[0]);
        assertTrue(session.imageSpinner(), "authority admission cannot reset the producer's 300ms interval");
        var hidden = session.displayBody(() -> true);
        assertTrue(hidden instanceof PreviewModelFrames.Completion completion && completion.requiresConsumption());
        oldVisible.accept(new PreviewModelFrames.Body(PreviewRenderer.BodyStatus.ORDINARY, () -> true));
        assertFalse(session.body().current().getAsBoolean());
        hidden.accept(new PreviewModelFrames.Body(PreviewRenderer.BodyStatus.ORDINARY, () -> true));
        assertTrue(session.imageVisible());
        assertFalse(session.imageSpinner(), "own material readiness has no minimum spinner time");
        assertFalse(session.body().current().getAsBoolean(), "hidden B is not a visible ordinary witness");
        session.displayBody(() -> true).accept(new PreviewModelFrames.Body(PreviewRenderer.BodyStatus.ORDINARY, () -> true));
        assertTrue(session.body().current().getAsBoolean());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"registration", "world", "player"})
    void sameImageAuthorityReplacementRequiresNewHiddenMaterialBeforeVisiblePass(String boundary) {
        long[] now = {0};
        var session = new EditorPreviewSession(() -> now[0]);
        Object world = new Object(), player = new Object(), material = new Object(), attempt = new Object();
        session.beginBody("same", false, true);
        session.beginImage(attempt);
        session.displayAuthority(material, world, player);
        session.displayBody(() -> true).accept(new PreviewModelFrames.Body(PreviewRenderer.BodyStatus.ORDINARY, () -> true));
        var old = session.displayBody(() -> true);
        old.accept(new PreviewModelFrames.Body(PreviewRenderer.BodyStatus.ORDINARY, () -> true));
        assertTrue(session.imageVisible());
        now[0] = 400_000_000L;
        session.displayAuthority(boundary.equals("registration") ? new Object() : material,
                boundary.equals("world") ? new Object() : world, boundary.equals("player") ? new Object() : player);
        assertTrue(session.imageTracked(), "native replacement must not fall into the untracked visible path");
        assertTrue(session.imagePreparing());
        assertFalse(session.imageVisible());
        assertFalse(session.imageSpinner(), "a ready material's replacement begins a new native preparation interval");
        now[0] = 699_000_000L;
        assertFalse(session.imageSpinner());
        now[0] = 700_000_000L;
        assertTrue(session.imageSpinner());
        old.accept(new PreviewModelFrames.Body(PreviewRenderer.BodyStatus.ORDINARY, () -> true));
        assertFalse(session.body().current().getAsBoolean());
        session.displayBody(() -> true).accept(new PreviewModelFrames.Body(PreviewRenderer.BodyStatus.ORDINARY, () -> true));
        assertTrue(session.imageVisible());
        assertFalse(session.body().current().getAsBoolean());
        session.displayBody(() -> true).accept(new PreviewModelFrames.Body(PreviewRenderer.BodyStatus.ORDINARY, () -> true));
        assertTrue(session.body().current().getAsBoolean());
    }

    @Test void authorityReplacementFencesPendingCallbacksWithoutRetryingTerminalImageOrCpm() {
        long[] now = {0};
        var session = new EditorPreviewSession(() -> now[0]);
        session.beginBody("same", true, true);
        Object attempt = new Object();
        session.beginImage(attempt);
        session.displayAuthority("old", null, null);
        var late = session.imagePreparation(() -> true);
        now[0] = 59_900_000_000L;
        session.displayAuthority("new", null, null);
        late.accept(new PreviewModelFrames.Body(PreviewRenderer.BodyStatus.READY, () -> true));
        assertFalse(session.imageVisible());
        assertTrue(session.imageSpinner());
        now[0] = 60_000_000_000L;
        assertEquals(PreviewRenderer.BodyStatus.TIMED_OUT, session.bodyStatus());
        session.ordinaryDisplay(() -> true).accept(new PreviewModelFrames.Body(PreviewRenderer.BodyStatus.FAILED, () -> true));
        session.displayAuthority("replacement", null, null);
        session.beginImage(attempt);
        assertTrue(session.imageTracked());
        assertFalse(session.imagePreparing(), "authority replacement cannot revive a terminal image attempt");
        assertFalse(session.imageSpinner());
        assertEquals(PreviewRenderer.BodyStatus.TIMED_OUT, session.bodyStatus());
    }

    @Test void partialInputConsumptionCannotReleaseImageAndItsLateFenceIsRejected() {
        long[] now = {0};
        var session = new EditorPreviewSession(() -> now[0]);
        session.beginImage("source");
        session.imagePrerequisite(false, 0L);
        var partial = session.imagePreparation(() -> true);
        now[0] = 400_000_000L;
        partial.accept(new PreviewModelFrames.Body(PreviewRenderer.BodyStatus.ORDINARY, () -> true));
        assertFalse(session.imageVisible());
        assertTrue(session.imageSpinner());
        session.imagePrerequisite(true, now[0]);
        partial.accept(new PreviewModelFrames.Body(PreviewRenderer.BodyStatus.ORDINARY, () -> true));
        assertFalse(session.imageVisible());
        assertTrue(session.imageSpinner());
        session.imagePreparation(() -> true).accept(new PreviewModelFrames.Body(PreviewRenderer.BodyStatus.ORDINARY, () -> true));
        assertTrue(session.imageVisible());
    }

    @Test void terminalCpmStageKeepsImagePreparationForItsExistingFallbackConsumer() {
        for (var terminal : java.util.List.of(PreviewRenderer.BodyStatus.FAILED, PreviewRenderer.BodyStatus.BLOCKED,
                PreviewRenderer.BodyStatus.TIMED_OUT)) {
            long[] now = {0};
            var session = new EditorPreviewSession(() -> now[0]);
            session.beginBody("source", true, true);
            session.beginImage("source");
            now[0] = 400_000_000L;
            if (terminal == PreviewRenderer.BodyStatus.TIMED_OUT) now[0] = 60_000_000_000L;
            else session.body(new PreviewModelFrames.Body(terminal, () -> true));
            assertEquals(terminal, session.bodyStatus());
            assertTrue(session.imageTracked());
            session.ordinaryDisplay(() -> true).accept(new PreviewModelFrames.Body(PreviewRenderer.BodyStatus.ORDINARY, () -> true));
            assertTrue(session.imageVisible());
            assertFalse(session.body().current().getAsBoolean(), "fallback preparation is not a visible fallback display");
            session.ordinaryDisplay(() -> true).accept(new PreviewModelFrames.Body(PreviewRenderer.BodyStatus.ORDINARY, () -> true));
            assertTrue(session.body().current().getAsBoolean());
            assertEquals(terminal, session.bodyStatus());
        }
    }

    @Test void sourcePreparationAcceptsProducerStartWithoutResettingAtNativeStage() {
        long[] now = {400_000_000L};
        var session = new EditorPreviewSession(() -> now[0]);
        session.beginImage("source", 0L);
        assertTrue(session.imageSpinner());
        session.beginImage("source", now[0]);
        assertTrue(session.imageSpinner());
        session.imagePreparation(() -> true).accept(new PreviewModelFrames.Body(PreviewRenderer.BodyStatus.FAILED, () -> true));
        session.beginImage("source", now[0]);
        assertFalse(session.imagePreparing());
        session.beginImage("new-source", now[0]);
        assertTrue(session.imagePreparing());
        assertFalse(session.imageSpinner());
    }

    @Test void pendingConsumerStagePreservesContinuousImageAttempt() {
        long[] now = {0};
        var session = new EditorPreviewSession(() -> now[0]);
        session.beginImage("source");
        var preparation = session.imagePreparation(() -> true);
        now[0] = 299_000_000L;
        preparation.accept(new PreviewModelFrames.Body(PreviewRenderer.BodyStatus.PENDING, () -> true));
        assertTrue(session.imagePreparing());
        assertFalse(session.imageSpinner());
        now[0] = 300_000_000L;
        assertTrue(session.imageSpinner());
        preparation.accept(new PreviewModelFrames.Body(PreviewRenderer.BodyStatus.READY, () -> true));
        assertTrue(session.imageVisible());
        assertFalse(session.imageSpinner());
    }

    @Test void nativeBodyCompletionSeparatesHiddenImageAndVisibleAuthorityAcrossTextureStages() {
        long[] now = {0};
        var session = new EditorPreviewSession(() -> now[0]);
        session.beginBody("fixture:joined",false,true);
        session.beginImage("fixture:joined");
        now[0] = 299_000_000L;
        Object world = new Object(), player = new Object();
        session.displayAuthority("fixture:material",world,player);
        assertFalse(session.imageSpinner());
        now[0] = 300_000_000L;
        assertTrue(session.imageSpinner(), "first material admission preserves source preparation start");
        var hidden = session.displayBody(() -> true);
        assertTrue(hidden instanceof PreviewModelFrames.Completion);
        assertTrue(((PreviewModelFrames.Completion)hidden).requiresConsumption());
        hidden.accept(new PreviewModelFrames.Body(PreviewRenderer.BodyStatus.ORDINARY,() -> true));
        assertTrue(session.imageVisible());
        assertFalse(session.imageSpinner());
        assertFalse(session.body().current().getAsBoolean());
        var visible = session.displayBody(() -> true);
        assertTrue(((PreviewModelFrames.Completion)visible).requiresConsumption());
        visible.accept(new PreviewModelFrames.Body(PreviewRenderer.BodyStatus.ORDINARY,() -> true));
        assertTrue(session.body().current().getAsBoolean());
        session.displayAuthority("fixture:replacement",world,player);
        visible.accept(new PreviewModelFrames.Body(PreviewRenderer.BodyStatus.ORDINARY,() -> true));
        assertFalse(session.body().current().getAsBoolean());
        assertFalse(session.imageVisible());
    }

    @Test void imagePreparationHidesImmediatelyDelaysSpinnerAndNeverGrantsVisibleBody() {
        long[] now = {0};
        var session = new EditorPreviewSession(() -> now[0]);
        session.beginBody("fixture:image", false, true);
        session.beginImage("fixture:image");
        assertFalse(session.imageVisible());
        assertFalse(session.imageSpinner());
        now[0] = 299_000_000L;
        session.beginImage("fixture:image");
        assertFalse(session.imageSpinner());
        now[0] = 300_000_000L;
        assertTrue(session.imageSpinner());
        var prepared = session.imagePreparation(() -> true);
        assertTrue(prepared.requiresConsumption());
        prepared.accept(new PreviewModelFrames.Body(PreviewRenderer.BodyStatus.ORDINARY, () -> true));
        assertTrue(session.imageVisible());
        assertFalse(session.imageSpinner());
        assertFalse(session.body().current().getAsBoolean());
        session.ordinaryDisplay(() -> true).accept(new PreviewModelFrames.Body(PreviewRenderer.BodyStatus.ORDINARY, () -> true));
        assertTrue(session.body().current().getAsBoolean());
    }

    @Test void imageGenerationRejectsLateAndRetiredMaterialWithoutMinimumOrComparisonReset() {
        long[] now = {0};
        var session = new EditorPreviewSession(() -> now[0]);
        session.beginImage("A");
        var old = session.imagePreparation(() -> true);
        now[0] = 400_000_000L;
        session.beginImage("B");
        old.accept(new PreviewModelFrames.Body(PreviewRenderer.BodyStatus.ORDINARY, () -> true));
        assertFalse(session.imageVisible());
        assertFalse(session.imageSpinner());
        now[0] = 700_000_000L;
        assertTrue(session.imageSpinner());
        var material = new java.util.concurrent.atomic.AtomicBoolean(true);
        session.imagePreparation(() -> true).accept(new PreviewModelFrames.Body(PreviewRenderer.BodyStatus.ORDINARY, material::get));
        assertTrue(session.imageVisible());
        session.beginImage("B");
        assertTrue(session.imageVisible());
        assertFalse(session.imageSpinner());
        material.set(false);
        assertFalse(session.imageVisible());
        assertFalse(session.imageSpinner());
        session.beginImage("B");
        assertTrue(session.imagePreparing(), "an explicit demand must reprepare an actually released native material");
        assertFalse(session.imageVisible());
        session.imagePreparation(() -> true).accept(new PreviewModelFrames.Body(PreviewRenderer.BodyStatus.ORDINARY, () -> true));
        assertTrue(session.imageVisible());
    }

    @Test void imageTerminalAndCloseCannotRevivePendingOrVisibleAuthority() {
        long[] now = {1_000_000_000L};
        var session = new EditorPreviewSession(() -> now[0]);
        session.beginImage("A");
        var current = new java.util.concurrent.atomic.AtomicBoolean(true);
        var completion = session.imagePreparation(current::get);
        completion.accept(new PreviewModelFrames.Body(PreviewRenderer.BodyStatus.FAILED, () -> true));
        now[0] += 600_000_000L;
        assertFalse(session.imageSpinner());
        assertFalse(session.imageVisible());
        session.beginImage("B");
        var late = session.imagePreparation(current::get);
        session.invalidateDisplayedBody();
        late.accept(new PreviewModelFrames.Body(PreviewRenderer.BodyStatus.ORDINARY, () -> true));
        assertFalse(session.imageVisible());
        assertFalse(session.imageSpinner());
    }

    @Test void imageMaterialRetainsAdmissionAndRejectsReentrantReplacement() {
        var session = new EditorPreviewSession();
        var current = new java.util.concurrent.atomic.AtomicBoolean(true);
        session.beginImage("A");
        session.imagePreparation(current::get).accept(new PreviewModelFrames.Body(PreviewRenderer.BodyStatus.ORDINARY, () -> true));
        assertTrue(session.imageVisible());
        current.set(false);
        assertFalse(session.imageVisible());
        session.beginImage("B");
        var b = session.imagePreparation(() -> true);
        b.accept(new PreviewModelFrames.Body(PreviewRenderer.BodyStatus.ORDINARY, () -> {
            session.beginImage("C");
            session.imagePreparation(() -> true).accept(new PreviewModelFrames.Body(PreviewRenderer.BodyStatus.ORDINARY, () -> true));
            return true;
        }));
        assertTrue(session.imageVisible());
        assertFalse(session.body().current().getAsBoolean());
    }

    @Test void imageSpinnerPhaseUsesOneClockRatherThanAttemptStart() {
        long[] now = {300_000_000L};
        var a = new EditorPreviewSession(() -> now[0]);
        var b = new EditorPreviewSession(() -> now[0]);
        a.beginImage("A");
        now[0] += 100_000_000L;
        b.beginImage("B");
        for (int frame = 0; frame < 16; frame++) {
            now[0] = frame * 100_000_000L;
            assertEquals(frame % 8, a.imageFrame());
            assertEquals(a.imageFrame(), b.imageFrame());
        }
    }

    @Test void galleryActualPassRemainsProvisionalUntilExactCommonAcknowledgement() {
        try (var gallery = new EditorPreviewSession.Gallery()) {
            var a = gallery.prepare("A",false,true,() -> true);
            a.ordinaryDisplay(() -> true).accept(new PreviewModelFrames.Body(PreviewRenderer.BodyStatus.ORDINARY,() -> true));
            gallery.confirm("A");
            var predecessor = a.body();
            var current = new java.util.concurrent.atomic.AtomicBoolean(true);
            var b = gallery.prepare("B",false,true,current::get);
            b.ordinaryDisplay(() -> true).accept(new PreviewModelFrames.Body(PreviewRenderer.BodyStatus.ORDINARY,() -> true));
            assertTrue(predecessor.current().getAsBoolean(),"Actual candidate pass alone cannot retire common-confirmed A");
            current.set(false);
            gallery.confirm("B");
            assertTrue(predecessor.current().getAsBoolean(),"Superseded B has no acknowledgement authority");
            assertTrue(gallery.retains(a));
            assertFalse(gallery.retains(b));
            var c = gallery.prepare("C",false,true,() -> true);
            gallery.confirm("C");
            assertTrue(predecessor.current().getAsBoolean(),"Preparation without own pass is not confirmation");
            c.ordinaryDisplay(() -> true).accept(new PreviewModelFrames.Body(PreviewRenderer.BodyStatus.ORDINARY,() -> true));
            gallery.confirm("C");
            assertFalse(predecessor.current().getAsBoolean());
            assertTrue(gallery.retains(c));
        }
    }

    @Test void gallerySameContentRegistrationReplacementRetainsExactSessionAndTerminalCloseNeverRecreates() {
        var gallery = new EditorPreviewSession.Gallery();
        var old = new java.util.concurrent.atomic.AtomicBoolean(true);
        var a = gallery.prepare("A",false,true,old::get);
        a.ordinaryDisplay(() -> true).accept(new PreviewModelFrames.Body(PreviewRenderer.BodyStatus.ORDINARY,() -> true));
        gallery.confirm("A");
        old.set(false);
        var replacement = gallery.prepare("A",false,true,() -> true);
        org.junit.jupiter.api.Assertions.assertSame(a,replacement);
        assertTrue(a.body().current().getAsBoolean());
        gallery.close();
        org.junit.jupiter.api.Assertions.assertNull(gallery.prepare("A",false,true,() -> true));
        assertFalse(gallery.retains(a));
        assertFalse(gallery.body().current().getAsBoolean());
    }

    @Test void galleryReentrantAdmissionCloseCannotReturnAReplacementOrDisplayedWitness() {
        var gallery = new EditorPreviewSession.Gallery();
        org.junit.jupiter.api.Assertions.assertNull(gallery.prepare("A",false,true,() -> { gallery.close(); return true; }));
        assertFalse(gallery.body().current().getAsBoolean());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"ORDINARY,current,false", "ORDINARY,result,false", "READY,current,false", "READY,result,false",
            "ORDINARY,current,true", "ORDINARY,result,true", "READY,current,true", "READY,result,true"})
    void displayedQueryRejectsReentrantRetirementWithoutClearingReplacement(
            PreviewRenderer.BodyStatus status, String boundary, boolean replace) {
        var session = new EditorPreviewSession();
        session.beginBody("original", false, true);
        var armed = new java.util.concurrent.atomic.AtomicBoolean();
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        java.util.function.BooleanSupplier retire = () -> {
            if (armed.get() && calls.incrementAndGet() == 2) {
                session.beginBody("replacement", false, true);
                if (replace) session.displayBody(() -> true).accept(
                        new PreviewModelFrames.Body(PreviewRenderer.BodyStatus.READY, () -> true));
            }
            return true;
        };
        session.displayBody(boundary.equals("current") ? retire : () -> true).accept(
                new PreviewModelFrames.Body(status, boundary.equals("result") ? retire : () -> true));
        PreviewRenderer<Object> renderer = new PreviewRenderer<>() {
            public void render(Object graphics, PreviewRequest request) { throw new AssertionError(); }
            public PreviewModelFrames.Body body() { return session.body(); }
        };
        armed.set(true);
        assertEquals(PreviewRenderer.DisplayKind.UNCONFIRMED, renderer.displayKind(), "Retired query must not authorize controls");
        assertEquals(replace ? PreviewRenderer.DisplayKind.CPM : PreviewRenderer.DisplayKind.UNCONFIRMED, renderer.displayKind());
    }

    @Test
    void queuedFrameRechecksPureAuthorityAfterPreparedWitnessPredicate() {
        var session = new EditorPreviewSession();
        session.beginBody("original", true, true);
        var armed = new java.util.concurrent.atomic.AtomicBoolean();
        session.body(new PreviewModelFrames.Body(PreviewRenderer.BodyStatus.READY, () -> {
            if (armed.getAndSet(false)) session.beginBody("replacement", true, true);
            return true;
        }));
        var frame = session.frame();
        armed.set(true);
        assertFalse(frame.getAsBoolean(), "A nested prepared-witness predicate retired this frame");
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(value = PreviewRenderer.BodyStatus.class, names = {"ORDINARY", "READY"})
    void completionCannotOverwriteNewWitnessInstalledByItsLastCurrentPredicate(PreviewRenderer.BodyStatus status) {
        var session = new EditorPreviewSession();
        session.beginBody("original", false, true);
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        session.displayBody(() -> {
            if (calls.incrementAndGet() == 2) {
                session.beginBody("replacement", false, true);
                session.displayBody(() -> true).accept(new PreviewModelFrames.Body(PreviewRenderer.BodyStatus.READY, () -> true));
            }
            return true;
        }).accept(new PreviewModelFrames.Body(status, () -> true));
        assertEquals(PreviewRenderer.BodyStatus.READY, session.body().status());
        assertTrue(session.body().current().getAsBoolean());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(value = PreviewRenderer.BodyStatus.class, names = {"ORDINARY", "READY"})
    void displayedQueryRechecksPreparedAuthorityAfterLastNestedPredicate(PreviewRenderer.BodyStatus status) {
        var session = new EditorPreviewSession();
        session.beginBody("original", true, true);
        session.body(new PreviewModelFrames.Body(PreviewRenderer.BodyStatus.READY, () -> true));
        var armed = new java.util.concurrent.atomic.AtomicBoolean();
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        session.displayBody(() -> true).accept(new PreviewModelFrames.Body(status, () -> {
            if (armed.get() && calls.incrementAndGet() == 2) session.body(
                    new PreviewModelFrames.Body(PreviewRenderer.BodyStatus.PENDING, () -> true));
            return true;
        }));
        PreviewRenderer<Object> query = new PreviewRenderer<>() {
            public void render(Object graphics, PreviewRequest request) { throw new AssertionError(); }
            public PreviewModelFrames.Body body() { return session.body(); }
        };
        armed.set(true);
        assertEquals(PreviewRenderer.DisplayKind.UNCONFIRMED, query.displayKind());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"ORDINARY,current", "ORDINARY,result", "READY,current", "READY,result"})
    void displayedQueryRejectsSameSourceWitnessReplacement(PreviewRenderer.BodyStatus status, String boundary) {
        var session = new EditorPreviewSession();
        session.beginBody("same-source", false, true);
        var replacement = status == PreviewRenderer.BodyStatus.ORDINARY ? PreviewRenderer.BodyStatus.READY : PreviewRenderer.BodyStatus.ORDINARY;
        var armed = new java.util.concurrent.atomic.AtomicBoolean();
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        java.util.function.BooleanSupplier replace = () -> {
            if (armed.get() && calls.incrementAndGet() == 2) session.displayBody(() -> true).accept(
                    new PreviewModelFrames.Body(replacement, () -> true));
            return true;
        };
        session.displayBody(boundary.equals("current") ? replace : () -> true).accept(
                new PreviewModelFrames.Body(status, boundary.equals("result") ? replace : () -> true));
        PreviewRenderer<Object> query = new PreviewRenderer<>() {
            public void render(Object graphics, PreviewRequest request) { throw new AssertionError(); }
            public PreviewModelFrames.Body body() { return session.body(); }
        };
        armed.set(true);
        assertEquals(PreviewRenderer.DisplayKind.UNCONFIRMED, query.displayKind());
        assertEquals(replacement == PreviewRenderer.BodyStatus.READY ? PreviewRenderer.DisplayKind.CPM : PreviewRenderer.DisplayKind.ORDINARY,
                query.displayKind());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(value = PreviewRenderer.BodyStatus.class, names = {"ORDINARY", "READY"})
    void completionPreservesSameSourceWitnessInstalledByItsCurrentPredicate(PreviewRenderer.BodyStatus status) {
        var session = new EditorPreviewSession();
        session.beginBody("same-source", false, true);
        var replacement = status == PreviewRenderer.BodyStatus.ORDINARY ? PreviewRenderer.BodyStatus.READY : PreviewRenderer.BodyStatus.ORDINARY;
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        session.displayBody(() -> {
            if (calls.incrementAndGet() == 2) session.displayBody(() -> true).accept(
                    new PreviewModelFrames.Body(replacement, () -> true));
            return true;
        }).accept(new PreviewModelFrames.Body(status, () -> true));
        assertEquals(replacement, session.body().status());
        assertTrue(session.body().current().getAsBoolean());
    }

    @Test
    void equivalentNativeOwnersMustNotShareOrdinaryCompletionAuthority() {
        record Owner(int id) {}
        var session = new EditorPreviewSession();
        var first = new Owner(1);
        var replacement = new Owner(1);
        session.displayAuthority("skin", first, null);
        session.ordinaryDisplay(() -> true).accept(new PreviewModelFrames.Body(PreviewRenderer.BodyStatus.ORDINARY, () -> true));
        session.displayAuthority("skin", replacement, null);
        assertFalse(session.body().current().getAsBoolean(), "Native owner identity is stricter than semantic equality");
    }

    @Test
    void displayNotificationsDistinguishOrdinaryCpmAndFailureWithoutRepeatedPasses() {
        var session = new EditorPreviewSession();
        PreviewRenderer<Object> query = new PreviewRenderer<>() {
            public void render(Object graphics, PreviewRequest request) { throw new AssertionError(); }
            public PreviewModelFrames.Body body() { return session.body(); }
        };
        var kinds = new java.util.ArrayList<PreviewRenderer.DisplayKind>();
        session.observeBody(ignored -> kinds.add(query.displayKind()));
        var complete = session.displayBody(() -> true);
        for (var status : java.util.List.of(PreviewRenderer.BodyStatus.ORDINARY, PreviewRenderer.BodyStatus.READY,
                PreviewRenderer.BodyStatus.ORDINARY, PreviewRenderer.BodyStatus.FAILED)) {
            complete.accept(new PreviewModelFrames.Body(status, () -> true));
            complete.accept(new PreviewModelFrames.Body(status, () -> true));
        }
        assertEquals(java.util.List.of(PreviewRenderer.DisplayKind.ORDINARY, PreviewRenderer.DisplayKind.CPM,
                PreviewRenderer.DisplayKind.ORDINARY, PreviewRenderer.DisplayKind.UNCONFIRMED), kinds);
    }

    @Test
    void reentrantRetirementWhileTestingACandidateCannotInstallOldDisplay() {
        var session = new EditorPreviewSession();
        session.beginBody("old", false, true);
        var complete = session.displayBody(() -> true);
        complete.accept(new PreviewModelFrames.Body(PreviewRenderer.BodyStatus.ORDINARY, () -> {
            session.beginBody("replacement", false, true);
            return true;
        }));
        assertFalse(session.body().current().getAsBoolean());
    }

    @Test
    void fallbackCompletionIsScopedStableAndIndependentOfLiveAdmission() {
        for (String mode : java.util.List.of("worldless", "failed", "blocked", "timeout", "circuit")) {
            long[] now = {0};
            var session = new EditorPreviewSession(() -> now[0]);
            session.beginBody("source", true, !mode.equals("worldless"));
            switch (mode) {
                case "failed" -> session.body(new PreviewModelFrames.Body(PreviewRenderer.BodyStatus.FAILED, () -> true));
                case "blocked" -> session.body(new PreviewModelFrames.Body(PreviewRenderer.BodyStatus.BLOCKED, () -> true));
                case "timeout" -> { now[0] = java.time.Duration.ofSeconds(60).toNanos(); session.bodyStatus(); }
                case "circuit" -> session.disableLive(new IllegalStateException());
                default -> {}
            }
            session.displayAuthority("resource-A", null, null);
            boolean[] current = {true};
            var completion = session.ordinaryDisplay(() -> current[0]);
            var events = new java.util.ArrayList<PreviewRenderer.DisplayKind>();
            PreviewRenderer<Object> query = new PreviewRenderer<>() {
                public void render(Object graphics, PreviewRequest request) { throw new AssertionError(); }
                public PreviewModelFrames.Body body() { return session.body(); }
            };
            session.observeBody(ignored -> events.add(query.displayKind()));
            assertEquals(PreviewRenderer.DisplayKind.UNCONFIRMED, query.displayKind(), mode);
            completion.accept(new PreviewModelFrames.Body(PreviewRenderer.BodyStatus.ORDINARY, () -> true));
            assertEquals(PreviewRenderer.DisplayKind.ORDINARY, query.displayKind(), mode);
            assertFalse(mode.equals("worldless") ? session.waitsForBody() : session.frame().getAsBoolean());
            session.beginBody("source", true, !mode.equals("worldless"));
            session.displayAuthority("resource-A", null, null);
            org.junit.jupiter.api.Assertions.assertSame(completion, session.ordinaryDisplay(() -> { throw new AssertionError(); }));
            completion.accept(new PreviewModelFrames.Body(PreviewRenderer.BodyStatus.ORDINARY, () -> true));
            assertEquals(java.util.List.of(PreviewRenderer.DisplayKind.ORDINARY), events, mode);
            completion.accept(new PreviewModelFrames.Body(PreviewRenderer.BodyStatus.FAILED, () -> true));
            assertEquals(PreviewRenderer.DisplayKind.UNCONFIRMED, query.displayKind(), mode);
            completion.accept(new PreviewModelFrames.Body(PreviewRenderer.BodyStatus.ORDINARY, () -> true));
            session.displayAuthority("resource-B", null, null);
            completion.accept(new PreviewModelFrames.Body(PreviewRenderer.BodyStatus.ORDINARY, () -> true));
            assertEquals(PreviewRenderer.DisplayKind.UNCONFIRMED, query.displayKind(), mode);
            var replacement = session.ordinaryDisplay(() -> current[0]);
            replacement.accept(new PreviewModelFrames.Body(PreviewRenderer.BodyStatus.READY, () -> true));
            assertEquals(PreviewRenderer.DisplayKind.UNCONFIRMED, query.displayKind(), "Fallback cannot claim CPM");
            replacement.accept(new PreviewModelFrames.Body(PreviewRenderer.BodyStatus.ORDINARY, () -> true));
            current[0] = false;
            assertEquals(PreviewRenderer.DisplayKind.UNCONFIRMED, query.displayKind(), mode);
        }
    }

    @Test
    void missingDisplayIsNotPositiveOrdinaryEvidence() {
        var session = new EditorPreviewSession();
        assertFalse(session.body().current().getAsBoolean(), "Opening has not displayed an ordinary body");
        session.beginBody("ordinary", false, true);
        assertFalse(session.body().current().getAsBoolean(), "Preparation is not display");
    }

    @Test
    void ordinaryDisplayRetainsItsCurrentWitnessAndPublishesOnlyItsTransition() {
        var session = new EditorPreviewSession();
        session.beginBody("ordinary", false, true);
        var events = new java.util.ArrayList<PreviewRenderer.BodyStatus>();
        session.observeBody(events::add);
        boolean[] current = {true};
        var completion = session.displayBody(() -> current[0]);
        completion.accept(new PreviewModelFrames.Body(PreviewRenderer.BodyStatus.ORDINARY, () -> true));
        assertEquals(1, events.size(), "Successful ordinary pass must publish its first display");
        assertTrue(session.body().current().getAsBoolean());
        completion.accept(new PreviewModelFrames.Body(PreviewRenderer.BodyStatus.ORDINARY, () -> true));
        assertEquals(1, events.size());
        current[0] = false;
        assertFalse(session.body().current().getAsBoolean(), "Retired ordinary is not positive evidence");
        assertEquals(2, events.size());
    }

    @Test
    void retirementNotificationsNeverPublishPreviousPreparationReadiness() {
        var observed = new java.util.LinkedHashMap<String, java.util.List<PreviewRenderer.BodyStatus>>();
        for (String retirement : java.util.List.of("source", "world", "circuit")) {
            var session = new EditorPreviewSession();
            session.beginBody("source-A", true, true);
            session.body(new PreviewModelFrames.Body(PreviewRenderer.BodyStatus.READY, () -> true));
            session.displayBody(() -> true).accept(
                    new PreviewModelFrames.Body(PreviewRenderer.BodyStatus.READY, () -> true));
            var events = new java.util.ArrayList<PreviewRenderer.BodyStatus>();
            session.observeBody(status -> {
                events.add(status);
                assertEquals(status, session.bodyStatus());
                assertEquals(PreviewRenderer.BodyStatus.ORDINARY, session.body().status());
            });
            switch (retirement) {
                case "source" -> session.beginBody("source-B", true, true);
                case "world" -> session.beginBody("source-A", true, false);
                default -> session.disableLive(new IllegalStateException("synthetic draw failure"));
            }
            observed.put(retirement, events.stream().distinct().toList());
        }
        assertEquals(java.util.Map.of(
                "source", java.util.List.of(PreviewRenderer.BodyStatus.PENDING),
                "world", java.util.List.of(PreviewRenderer.BodyStatus.ORDINARY),
                "circuit", java.util.List.of(PreviewRenderer.BodyStatus.FAILED)), observed);
    }

    @Test
    void fallbackRetiresQueuedNativeExecutionBeforeItCanEnterItsScope() {
        var session = new EditorPreviewSession();
        var queuedFrame = session.frame();
        assertTrue(queuedFrame.getAsBoolean());
        session.invalidateDisplayedBody();
        assertFalse(queuedFrame.getAsBoolean());
        assertTrue(session.frame().getAsBoolean());
    }


    @Test
    void repeatedEquivalentPreparationDoesNotRetireTheDisplayedPass() {
        var session = new EditorPreviewSession();
        session.beginBody("source", true, true);
        session.body(new PreviewModelFrames.Body(PreviewRenderer.BodyStatus.READY, () -> true));
        session.displayBody(() -> true).accept(new PreviewModelFrames.Body(PreviewRenderer.BodyStatus.READY, () -> true));
        session.body(new PreviewModelFrames.Body(PreviewRenderer.BodyStatus.READY, () -> true));
        assertEquals(PreviewRenderer.BodyStatus.READY, session.body().status());
    }


    @Test
    void preparationIsNotDisplayAndNotificationsTrackOnlyWitnessTransitions() {
        EditorPreviewSession session = new EditorPreviewSession();
        var events = new java.util.ArrayList<PreviewRenderer.BodyStatus>();
        session.observeBody(events::add);
        session.beginBody("source", true, true);
        session.body(new PreviewModelFrames.Body(PreviewRenderer.BodyStatus.READY, () -> true));
        assertEquals(PreviewRenderer.BodyStatus.ORDINARY, session.body().status());
        int prepared = events.size();
        var completion = session.displayBody(() -> true);
        completion.accept(new PreviewModelFrames.Body(PreviewRenderer.BodyStatus.READY, () -> true));
        assertEquals(prepared + 1, events.size());
        completion.accept(new PreviewModelFrames.Body(PreviewRenderer.BodyStatus.READY, () -> true));
        assertEquals(prepared + 1, events.size());
        session.invalidateDisplayedBody();
        assertEquals(prepared + 2, events.size());
        completion.accept(new PreviewModelFrames.Body(PreviewRenderer.BodyStatus.READY, () -> true));
        assertEquals(PreviewRenderer.BodyStatus.ORDINARY, session.body().status());
    }

    @Test
    void sourceWorldTerminalAndCircuitChangesRejectQueuedDisplayCompletion() {
        for (String retirement : java.util.List.of("source", "world", "blocked", "failed", "timeout", "circuit", "fallback")) {
            long[] now = {0};
            EditorPreviewSession session = new EditorPreviewSession(() -> now[0]);
            session.beginBody("source", true, true);
            session.body(new PreviewModelFrames.Body(PreviewRenderer.BodyStatus.READY, () -> true));
            var complete = session.displayBody(() -> true);
            var ready = new PreviewModelFrames.Body(PreviewRenderer.BodyStatus.READY, () -> true);
            complete.accept(ready);
            assertEquals(PreviewRenderer.BodyStatus.READY, session.body().status(), retirement);
            switch (retirement) {
                case "source" -> session.beginBody("other", false, true);
                case "world" -> session.beginBody("source", true, false);
                case "blocked" -> session.body(new PreviewModelFrames.Body(PreviewRenderer.BodyStatus.BLOCKED, () -> true));
                case "failed" -> session.body(new PreviewModelFrames.Body(PreviewRenderer.BodyStatus.FAILED, () -> true));
                case "timeout" -> {
                    session.beginBody("pending", true, true);
                    now[0] = java.time.Duration.ofSeconds(60).toNanos();
                    assertEquals(PreviewRenderer.BodyStatus.TIMED_OUT, session.bodyStatus());
                }
                case "circuit" -> session.disableLive(new IllegalStateException());
                default -> session.invalidateDisplayedBody();
            }
            complete.accept(ready);
            assertEquals(PreviewRenderer.BodyStatus.ORDINARY, session.body().status(), retirement);
        }
    }


    @Test
    void providersDisplayRequiresCompletedPassAndRechecksRetirementWithoutPreparation() {
        EditorPreviewSession session = new EditorPreviewSession();
        boolean[] current = {true};
        var complete = session.displayBody(() -> current[0]);
        assertFalse(session.waitsForBody());
        assertEquals(PreviewRenderer.BodyStatus.ORDINARY, session.body().status());
        complete.accept(new PreviewModelFrames.Body(PreviewRenderer.BodyStatus.READY, () -> current[0]));
        assertEquals(PreviewRenderer.BodyStatus.READY, session.body().status());
        assertFalse(session.waitsForBody());
        current[0] = false;
        assertEquals(PreviewRenderer.BodyStatus.ORDINARY, session.body().status());
    }

    @Test
    void pendingBodyUsesOneMonotonicWaitAndLatchesLateReady() {
        long[] nanos = {0};
        EditorPreviewSession session = new EditorPreviewSession(() -> nanos[0]);
        session.beginBody("source-A", true, true);
        assertEquals(PreviewRenderer.BodyStatus.PENDING, session.bodyStatus());
        assertFalse(session.frame().getAsBoolean());
        nanos[0] = java.time.Duration.ofSeconds(59).toNanos();
        session.beginBody("source-A", true, true);
        session.body(new PreviewModelFrames.Body(PreviewRenderer.BodyStatus.PENDING, () -> true));
        nanos[0] = java.time.Duration.ofSeconds(60).toNanos();
        assertEquals(PreviewRenderer.BodyStatus.TIMED_OUT, session.bodyStatus());
        session.body(new PreviewModelFrames.Body(PreviewRenderer.BodyStatus.READY, () -> true));
        assertEquals(PreviewRenderer.BodyStatus.TIMED_OUT, session.bodyStatus());
        session.beginBody("source-B", true, true);
        assertEquals(PreviewRenderer.BodyStatus.PENDING, session.bodyStatus());
        session.body(new PreviewModelFrames.Body(PreviewRenderer.BodyStatus.READY, () -> true));
        assertTrue(session.frame().getAsBoolean());
    }

    @Test
    void capturedReadyFrameCannotCrossSourceOrNativeDefinitionRetirement() {
        EditorPreviewSession session = new EditorPreviewSession();
        boolean[] current = {true};
        session.beginBody("source-A", true, true);
        session.body(new PreviewModelFrames.Body(PreviewRenderer.BodyStatus.READY, () -> current[0]));
        var frame = session.frame();
        assertTrue(frame.getAsBoolean());
        current[0] = false;
        assertFalse(frame.getAsBoolean());
        current[0] = true;
        session.beginBody("source-B", true, true);
        session.body(new PreviewModelFrames.Body(PreviewRenderer.BodyStatus.READY, () -> true));
        assertFalse(frame.getAsBoolean());
        assertTrue(session.frame().getAsBoolean());
    }

    @Test
    void failedAndBlockedBodyStayLatchedWithoutChangingOrdinaryLiveOrWorldlessBakedPolicy() {
        for (var status : java.util.List.of(PreviewRenderer.BodyStatus.FAILED, PreviewRenderer.BodyStatus.BLOCKED)) {
            EditorPreviewSession session = new EditorPreviewSession();
            session.beginBody("source", true, true);
            session.body(new PreviewModelFrames.Body(status, () -> true));
            session.body(new PreviewModelFrames.Body(PreviewRenderer.BodyStatus.READY, () -> true));
            assertEquals(status, session.bodyStatus());
            assertFalse(session.frame().getAsBoolean());
            session.beginBody("worldless", true, false);
            assertEquals(PreviewRenderer.BodyStatus.ORDINARY, session.bodyStatus());
            assertFalse(session.waitsForBody());
            assertEquals(BAKED, session.path(EDITOR_DRAFT, false));
            session.beginBody("ordinary", false, true);
            assertEquals(PreviewRenderer.BodyStatus.ORDINARY, session.bodyStatus());
            assertFalse(session.waitsForBody());
            assertEquals(LIVE, session.path(EDITOR_DRAFT, true));
        }
    }

    @Test
    void aDisabledLiveSessionReportsFailureImmediatelyForItsNextSource() {
        EditorPreviewSession session = new EditorPreviewSession();
        session.beginBody("source-A", true, true);
        session.disableLive(new IllegalStateException("synthetic draw failure"));
        session.beginBody("source-B", true, true);
        assertEquals(PreviewRenderer.BodyStatus.FAILED, session.bodyStatus());
        assertEquals(BAKED, session.path(EDITOR_DRAFT, true));
    }

    @Test
    void inspectionRequiresWorldReadyTextureAndProxyAndSurvivesNoCircuitFailure() {
        EditorPreviewSession session = new EditorPreviewSession();
        assertEquals(PreviewRenderer.LiveReason.WORLD_REQUIRED, session.liveReason(EDITOR_DRAFT, false, true, true));
        assertEquals(PreviewRenderer.LiveReason.LOADING, session.liveReason(EDITOR_DRAFT, true, false, true));
        assertEquals(PreviewRenderer.LiveReason.LIVE_UNAVAILABLE, session.liveReason(EDITOR_DRAFT, true, true, false));
        assertEquals(PreviewRenderer.LiveReason.AVAILABLE, session.liveReason(EDITOR_DRAFT, true, true, true));
        assertEquals(PreviewRenderer.LiveReason.LIVE_UNAVAILABLE, session.liveReason(ASSET_THUMBNAIL, true, true, true));
        session.disableLive(new RuntimeException());
        assertEquals(PreviewRenderer.LiveReason.LIVE_UNAVAILABLE, session.liveReason(EDITOR_DRAFT, true, true, true));
    }

    @Test
    void worldlessPreviewAlwaysUsesTheBakedRenderer() {
        assertEquals(BAKED, new EditorPreviewSession().path(EDITOR_DRAFT, false));
    }

    @Test
    void onlyInWorldEditorDraftUsesTheCanonicalLiveRenderer() {
        EditorPreviewSession session = new EditorPreviewSession();

        assertEquals(LIVE, session.path(EDITOR_DRAFT, true));
        assertEquals(BAKED, session.path(CURRENT_APPEARANCE, true));
        assertEquals(BAKED, session.path(ASSET_THUMBNAIL, true));
    }

    @Test
    void runtimeFailureDisablesLiveRenderingForTheRestOfTheSession() {
        EditorPreviewSession session = new EditorPreviewSession();

        assertTrue(session.disableLive(new RuntimeException("renderer")));
        assertEquals(BAKED, session.path(EDITOR_DRAFT, true));
        assertFalse(session.disableLive(new RuntimeException("renderer again")));
    }

    @Test
    void reopeningTheEditorRetriesTheLiveRenderer() {
        EditorPreviewSession closedSession = new EditorPreviewSession();
        closedSession.disableLive(new RuntimeException("renderer"));

        assertEquals(LIVE, new EditorPreviewSession().path(EDITOR_DRAFT, true));
    }

    @Test
    void failureMustBeExplicitlyProvidedWithoutBeingRetained() {
        EditorPreviewSession session = new EditorPreviewSession();
        assertThrows(NullPointerException.class, () -> session.disableLive(null));
        assertThrows(NullPointerException.class, () -> session.path(null, true));
        assertEquals(LIVE, session.path(EDITOR_DRAFT, true));
    }

    @Test
    void capeSettlesOnceAcrossSixMonotonicTicksWithoutMovingTheScreenAnchor() {
        EditorPreviewSession session = new EditorPreviewSession();
        assertEquals(EditorPreviewSession.SettlingMotion.NONE,
                session.capeSettling(EDITOR_DRAFT, true,
                        PreviewRenderer.CapeMode.CAPE, false, 30.0F));

        EditorPreviewSession.SettlingMotion first = session.capeSettling(
                EDITOR_DRAFT, true, PreviewRenderer.CapeMode.CAPE, true, 40.0F);
        EditorPreviewSession.SettlingMotion repeated = session.capeSettling(
                EDITOR_DRAFT, true, PreviewRenderer.CapeMode.CAPE, true, 40.0F);
        assertTrue(first.active());
        assertEquals(first, repeated);

        float previous = first.currentYOffset();
        for (int tick = 1; tick < EditorPreviewSession.SETTLING_TICKS; tick++) {
            EditorPreviewSession.SettlingMotion motion = session.capeSettling(
                    EDITOR_DRAFT, true, PreviewRenderer.CapeMode.CAPE, true, 40.0F + tick);
            assertTrue(motion.active());
            assertTrue(motion.currentYOffset() < previous);
            previous = motion.currentYOffset();
        }
        assertEquals(EditorPreviewSession.SettlingMotion.NONE,
                session.capeSettling(EDITOR_DRAFT, true,
                        PreviewRenderer.CapeMode.CAPE, true, 46.0F));
        assertEquals(EditorPreviewSession.SettlingMotion.NONE,
                session.capeSettling(EDITOR_DRAFT, true,
                        PreviewRenderer.CapeMode.CAPE, true, 100.0F));
    }

    @Test
    void worldlessCapeOffAndCircuitBreakerNeverStartSettling() {
        EditorPreviewSession session = new EditorPreviewSession();
        assertEquals(EditorPreviewSession.SettlingMotion.NONE,
                session.capeSettling(EDITOR_DRAFT, false,
                        PreviewRenderer.CapeMode.CAPE, true, 1.0F));
        assertEquals(EditorPreviewSession.SettlingMotion.NONE,
                session.capeSettling(EDITOR_DRAFT, true,
                        PreviewRenderer.CapeMode.OFF, true, 2.0F));
        session.disableLive(new RuntimeException("renderer"));
        assertEquals(EditorPreviewSession.SettlingMotion.NONE,
                session.capeSettling(EDITOR_DRAFT, true,
                        PreviewRenderer.CapeMode.CAPE, true, 3.0F));
    }
}

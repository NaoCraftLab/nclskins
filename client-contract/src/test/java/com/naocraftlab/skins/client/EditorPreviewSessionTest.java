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

package com.naocraftlab.skins.runtime;

import com.naocraftlab.skins.client.*;
import com.naocraftlab.skins.core.model.*;
import com.naocraftlab.skins.core.compatibility.SkinExtensionEnvironment;
import com.naocraftlab.skins.core.service.LibraryService;
import com.naocraftlab.skins.core.storage.LibraryStorageAdapter;
import com.naocraftlab.skins.runtime.infrastructure.PreviewModelFitStorageAdapter;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.CsvSource;
import static org.junit.jupiter.api.Assertions.*;

final class PreviewModelFitRuntimeTest {
    @TempDir Path directory;
    private GalleryRuntimeSelectionTest f;
    private LaneStore store;
    private SkinAsset a;
    private SkinAsset b;
    private final AtomicInteger measurements = new AtomicInteger();
    private static final PreviewModelFitStore.Value FIRST = value(5);
    private static final PreviewModelFitStore.Value SECOND = value(3);

    @ParameterizedTest
    @CsvSource({"gallery,false", "editor,false", "providers,false", "gallery,true", "editor,true", "providers,true"})
    void earlyDemandCannotBeRenewedByLifecycleBeforeTextureAdmission(String surface, boolean initiallyApplicable) {
        openSurface(surface);
        boolean[] applicable={initiallyApplicable};
        var probes=new AtomicInteger();
        var attempts=new AtomicInteger();
        var suppressed=new ArrayList<String>();
        PreviewRenderer<Object> renderer=new PreviewRenderer<>() {
            public void render(Object graphics, PreviewRequest request) { throw new AssertionError(); }
            public boolean modelFitApplicable() { probes.incrementAndGet(); return applicable[0]; }
            public void suppressModelFit(String source) { suppressed.add(source); }
            public Optional<ModelFitPreparation> prepareModelFit(String source, PreviewAppearance appearance) {
                attempts.incrementAndGet(); return Optional.empty();
            }
        };
        f.runtime.observePreviewModelFitDemand(surfacePreview(surface),renderer);
        assertEquals(0,attempts.get());
        applicable[0]=true;
        f.runtime.importWorldChanged(); f.runtime.resourcesReloaded(); f.drain();
        var appearance=new PreviewRenderer.PreviewAppearance(new TextureRegistry.TextureHandle("synthetic:skin",64,64),
                SkinModel.CLASSIC,Optional.empty(),PreviewRenderer.CapeMode.OFF,true);
        for(int i=0;i<100;i++) {
            var current=surfacePreview(surface);
            f.runtime.observePreviewModelFitDemand(current,renderer);
            assertTrue(f.runtime.preparePreviewModelFit(current,renderer,appearance).isDone());
        }
        f.drain();
        assertEquals(1,probes.get()); assertEquals(0,attempts.get());
        assertEquals(100,suppressed.size());
        assertEquals(f.runtime.previewBodySource(surfacePreview(surface)),suppressed.get(99));
        assertEquals(0,store.loads); assertEquals(0,store.creates);
        reopen(); openSurface(surface);
        f.runtime.observePreviewModelFitDemand(surfacePreview(surface),renderer);
        assertEquals(2,probes.get());
        f.runtime.preparePreviewModelFit(surfacePreview(surface),renderer,appearance);
        assertEquals(1,attempts.get());
    }

    @ParameterizedTest @ValueSource(strings={"gallery","editor"})
    void eligibleEarlyDemandContinuesWhenItsTextureArrives(String surface) {
        openSurface(surface);
        var attempts=new AtomicInteger();
        PreviewRenderer<Object> renderer=new PreviewRenderer<>() {
            public void render(Object graphics, PreviewRequest request) { throw new AssertionError(); }
            public Optional<ModelFitPreparation> prepareModelFit(String source, PreviewAppearance appearance) {
                attempts.incrementAndGet();
                var session=new EditorPreviewSession(); session.beginBody(source,false,true);
                return Optional.of(preparation(session.prepareFit(),CompletableFuture.completedFuture(
                        new PreviewRenderer.ModelFitMeasurement(FIRST.referenceHeight(),FIRST.referenceCenterX(),FIRST.referenceCenterY(),FIRST.referenceCenterZ(),FIRST.fit()))));
            }
        };
        f.runtime.observePreviewModelFitDemand(surfacePreview(surface),renderer);
        f.drain();
        assertEquals(0,attempts.get()); assertEquals(0,store.loads);
        var appearance=new PreviewRenderer.PreviewAppearance(new TextureRegistry.TextureHandle("synthetic:skin",64,64),
                SkinModel.CLASSIC,Optional.empty(),PreviewRenderer.CapeMode.OFF,true);
        var result=f.runtime.preparePreviewModelFit(surfacePreview(surface),renderer,appearance);
        f.drain();
        assertEquals(PreviewAssets.NumericFitStatus.STORED,result.join().status());
        assertEquals(1,attempts.get()); assertEquals(1,store.loads); assertEquals(1,store.creates);
    }

    @ParameterizedTest @ValueSource(strings = {"gallery", "editor", "providers"})
    void actualSurfaceDemandCoalescesSkippedPreparationAcrossFrames(String surface) {
        if (surface.equals("editor")) {
            f.runtime.dispatchWidget("gallery.preset." + GalleryRuntimeSelectionTest.A + ".more");
            f.runtime.dispatchWidget("gallery.preset." + GalleryRuntimeSelectionTest.A + ".edit");
        } else if (surface.equals("providers")) f.runtime.dispatchWidget("gallery.providers");
        String id = surface.equals("gallery") ? "gallery.preview" : "editor.preview";
        var attempts = new AtomicInteger();
        PreviewRenderer<Object> renderer = new PreviewRenderer<>() {
            public void render(Object graphics, PreviewRequest request) { throw new AssertionError(); }
            public Optional<ModelFitPreparation> prepareModelFit(String source, PreviewAppearance appearance) {
                attempts.incrementAndGet(); return Optional.empty();
            }
        };
        var appearance = new PreviewRenderer.PreviewAppearance(new TextureRegistry.TextureHandle("synthetic:skin",64,64),
                SkinModel.CLASSIC,Optional.empty(),PreviewRenderer.CapeMode.OFF,true);
        for (int i=0;i<100;i++) {
            var view=f.runtime.view(854+i%3,480,i%11,i%17);
            var selected=view.previews().stream().filter(p->p.id().equals(id)).findFirst().orElseThrow();
            assertTrue(ViewHostPolicy.screenOwnedBody(view.screenId(),selected));
            assertEquals(!surface.equals("providers"), !view.screenId().equals("providers"));
            f.runtime.preparePreviewModelFit(selected,renderer,appearance);
            f.runtime.tick();
        }
        f.drain();
        assertEquals(1,attempts.get());
        assertEquals(0,store.loads);
        assertEquals(0,store.creates);
    }

    @ParameterizedTest @ValueSource(strings = {"gallery", "editor", "providers"})
    void lifecycleRetirementDoesNotRenewForegroundDemand(String surface) {
        openSurface(surface);
        var attempts = new AtomicInteger();
        var suppressed = new ArrayList<String>();
        var measured = new CompletableFuture<PreviewRenderer.ModelFitMeasurement>();
        PreviewRenderer<Object> renderer = new PreviewRenderer<>() {
            public void render(Object graphics, PreviewRequest request) { throw new AssertionError(); }
            public void suppressModelFit(String source) { suppressed.add(source); }
            public Optional<ModelFitPreparation> prepareModelFit(String source, PreviewAppearance appearance) {
                attempts.incrementAndGet();
                var session = new EditorPreviewSession(); session.beginBody(source,false,true);
                return Optional.of(preparation(session.prepareFit(),measured));
            }
        };
        var appearance = new PreviewRenderer.PreviewAppearance(new TextureRegistry.TextureHandle("synthetic:skin",64,64),
                SkinModel.CLASSIC,Optional.empty(),PreviewRenderer.CapeMode.OFF,true);
        var pending=f.runtime.preparePreviewModelFit(surfacePreview(surface),renderer,appearance);
        f.runtime.importWorldChanged(); f.runtime.resourcesReloaded();
        var original=f.identity.get();
        f.identity.set(new GameSessionTokenSource.SessionIdentity(new UUID(0,999),"SyntheticOther")); f.runtime.tick();
        f.identity.set(original); f.runtime.tick();
        assertEquals(surface.equals("providers") ? PreviewAssets.NumericFitStatus.UNAVAILABLE : PreviewAssets.NumericFitStatus.RETIRED,pending.join().status());
        measured.complete(new PreviewRenderer.ModelFitMeasurement(FIRST.referenceHeight(),FIRST.referenceCenterX(),FIRST.referenceCenterY(),FIRST.referenceCenterZ(),FIRST.fit()));
        for(int i=0;i<100;i++) {
            f.runtime.tick();
            assertSame(pending,f.runtime.preparePreviewModelFit(surfacePreview(surface),renderer,appearance));
        }
        f.drain();
        assertEquals(1,attempts.get()); assertEquals(0,store.loads); assertEquals(0,store.creates); assertEquals(0,measurements.get());
        assertEquals(100,suppressed.size());
        assertEquals(f.runtime.previewBodySource(surfacePreview(surface)),suppressed.get(99));
        reopen(); openSurface(surface);
        assertNotSame(pending,f.runtime.preparePreviewModelFit(surfacePreview(surface),renderer,appearance));
        f.drain();
        assertEquals(2,attempts.get());
        assertEquals(surface.equals("providers") ? 0 : 1,store.loads);
        assertEquals(surface.equals("providers") ? 0 : 1,store.creates);
    }

    private void openSurface(String surface) {
        if(surface.equals("editor")) {
            f.runtime.dispatchWidget("gallery.preset."+GalleryRuntimeSelectionTest.A+".more");
            f.runtime.dispatchWidget("gallery.preset."+GalleryRuntimeSelectionTest.A+".edit");
        } else if(surface.equals("providers")) f.runtime.dispatchWidget("gallery.providers");
    }

    private ViewSpec.Preview surfacePreview(String surface) {
        String id=surface.equals("gallery")?"gallery.preview":"editor.preview";
        return f.runtime.view(854,480,0,0).previews().stream().filter(p->p.id().equals(id)).findFirst().orElseThrow();
    }

    @ParameterizedTest @ValueSource(strings={"world", "resources", "accountABA", "close", "shutdown"})
    void coarseRetirementDetachesBodyBeforeActualPreparationFutureCallbacks(String event) {
        var gallery = new EditorPreviewSession.Gallery();
        var displayed = gallery.prepare("A", false, true, () -> true);
        var displayedFit = displayed.prepareFit();
        displayedFit.complete();
        displayed.displayBody(() -> true).accept(new PreviewModelFrames.Body(PreviewRenderer.BodyStatus.ORDINARY, () -> true));
        gallery.confirm("A");
        var displayedBody = displayed.body();
        assertTrue(displayedBody.current().getAsBoolean());
        f.selectB();
        var candidate = gallery.prepare("B", true, true, () -> true);
        var candidateFit = candidate.prepareFit();
        var measured = new CompletableFuture<PreviewRenderer.ModelFitMeasurement>();
        var callbackState = new ArrayList<Boolean>();
        f.runtime.observePreviewBody(preview(), () -> PreviewRenderer.DisplayKind.UNCONFIRMED, () -> {}, gallery::close);
        var pending = f.runtime.preparePreviewModelFit(preview(), new PreviewRenderer.ModelFitPreparation() {
            public boolean current() { return candidateFit.current(); }
            public CompletableFuture<PreviewRenderer.ModelFitMeasurement> measure() { return measured; }
            public void complete(PreviewRenderer.ModelFitMeasurement value) { candidateFit.complete(); }
            public void close() { candidateFit.close(); measured.complete(null); }
        });
        f.drain();
        assertFalse(pending.isDone());
        measured.whenComplete((value, failure) -> {
            callbackState.add(displayedFit.ownerCurrent());
            callbackState.add(displayedBody.current().getAsBoolean());
            callbackState.add(candidateFit.ownerCurrent());
            callbackState.add(bodyRegistrationAttached());
        });
        retire(event);
        assertEquals(List.of(false, false, false, false), callbackState);
        assertEquals(PreviewAssets.NumericFitStatus.RETIRED, pending.join().status());
        f.drain();
        assertEquals(0, store.creates);
        assertEquals(PreviewModelFitStore.Status.ABSENT, durable(b).status());
    }

    @ParameterizedTest @ValueSource(strings={"world", "resources"})
    void coarseBodyCallbackCanInstallNewScopeWithoutOldCleanupRetiringIt(String event) {
        var measured = new CompletableFuture<PreviewModelFitStore.Value>();
        var old = prepare(measured); f.drain();
        var replacement = new java.util.concurrent.atomic.AtomicReference<CompletableFuture<PreviewAssets.NumericFitResult>>();
        var nextRetired = new AtomicInteger();
        f.runtime.observePreviewBody(preview(), () -> PreviewRenderer.DisplayKind.UNCONFIRMED, () -> {}, () -> {
            f.selectB();
            f.runtime.observePreviewBody(preview(), () -> PreviewRenderer.DisplayKind.UNCONFIRMED, () -> {}, nextRetired::incrementAndGet);
            replacement.set(prepare(CompletableFuture.completedFuture(SECOND)));
        });
        retire(event);
        assertNotNull(replacement.get());
        measured.complete(FIRST); f.drain();
        assertEquals(PreviewAssets.NumericFitStatus.RETIRED, old.join().status());
        assertEquals(SECOND, replacement.get().join().value());
        assertSame(replacement.get(), prepare(CompletableFuture.completedFuture(FIRST)));
        assertTrue(bodyRegistrationAttached());
        assertEquals(0, nextRetired.get());
        assertEquals(PreviewModelFitStore.Status.ABSENT, durable(a).status());
        assertEquals(SECOND, durable(b).value());
    }

    @ParameterizedTest @ValueSource(strings={"world", "resources"})
    void coarseBodyCallbackCanCloseRuntimeWithoutLeavingOldNumericPending(String event) {
        var measured = new CompletableFuture<PreviewModelFitStore.Value>();
        var old = prepare(measured); f.drain();
        f.runtime.observePreviewBody(preview(), () -> PreviewRenderer.DisplayKind.UNCONFIRMED, () -> {}, f.runtime::close);
        retire(event);
        measured.complete(FIRST); f.drain();
        assertEquals(ClientSnapshot.Lifecycle.CLOSED, f.runtime.snapshot().lifecycle());
        assertEquals(PreviewAssets.NumericFitStatus.RETIRED, old.join().status());
        assertFalse(bodyRegistrationAttached());
        assertEquals(0, store.creates);
    }

    @ParameterizedTest @ValueSource(strings={"world", "resources", "accountABA", "close", "shutdown"})
    void throwingBodyRetirementStillClosesCapturedPreparationAndPreservesPrimaryFailure(String event) {
        var gateOwner = new EditorPreviewSession(); gateOwner.beginBody("B", true, true);
        var gate = gateOwner.prepareFit();
        var measured = new CompletableFuture<PreviewRenderer.ModelFitMeasurement>();
        var closes = new AtomicInteger();
        var secondary = new IllegalStateException("synthetic preparation close");
        var pending = f.runtime.preparePreviewModelFit(preview(), new PreviewRenderer.ModelFitPreparation() {
            public boolean current() { return gate.current(); }
            public CompletableFuture<PreviewRenderer.ModelFitMeasurement> measure() { return measured; }
            public void complete(PreviewRenderer.ModelFitMeasurement value) { gate.complete(); }
            public void close() { closes.incrementAndGet(); gate.close(); measured.complete(null); throw secondary; }
        });
        f.drain();
        var primary = new IllegalArgumentException("synthetic body retirement");
        f.runtime.observePreviewBody(preview(), () -> PreviewRenderer.DisplayKind.UNCONFIRMED, () -> {}, () -> { throw primary; });
        assertSame(primary, assertThrows(IllegalArgumentException.class, () -> retire(event)));
        assertArrayEquals(new Throwable[]{secondary}, primary.getSuppressed());
        assertEquals(1, closes.get());
        assertEquals(PreviewAssets.NumericFitStatus.RETIRED, pending.join().status());
        assertFalse(bodyRegistrationAttached());
        f.drain();
        assertEquals(0, store.creates);
        if (event.equals("close") || event.equals("shutdown"))
            assertEquals(ClientSnapshot.Lifecycle.CLOSED, f.runtime.snapshot().lifecycle(), "Throwing preview hook must not abandon the remaining close cleanup/publication");
    }

    @ParameterizedTest @ValueSource(strings={"close", "shutdown"})
    void closingScopeRejectsReentrantBodyAndNumericAdmission(String event) {
        var accepted = new AtomicInteger();
        var closedPreview = preview();
        var pending = new java.util.concurrent.atomic.AtomicReference<CompletableFuture<PreviewAssets.NumericFitResult>>();
        f.runtime.observePreviewBody(closedPreview, () -> PreviewRenderer.DisplayKind.UNCONFIRMED, () -> {}, () -> {
            var callback = f.runtime.observePreviewBody(closedPreview, () -> PreviewRenderer.DisplayKind.ORDINARY, accepted::incrementAndGet);
            callback.accept(PreviewRenderer.BodyStatus.ORDINARY);
            pending.set(f.runtime.preparePreviewModelFit(closedPreview, () -> CompletableFuture.completedFuture(FIRST)));
        });
        retire(event);
        assertNotNull(pending.get());
        assertEquals(PreviewAssets.NumericFitStatus.UNAVAILABLE, pending.get().join().status());
        assertEquals(0, accepted.get());
        assertFalse(bodyRegistrationAttached());
        f.drain();
        assertEquals(0, store.creates);
    }

    @Test void closingBodyCallbackCanExplicitlyReopenWithoutOldCleanupClosingNewScope() {
        var old = prepare(new CompletableFuture<>()); f.drain();
        var replacement = new java.util.concurrent.atomic.AtomicReference<CompletableFuture<PreviewAssets.NumericFitResult>>();
        f.runtime.observePreviewBody(preview(), () -> PreviewRenderer.DisplayKind.UNCONFIRMED, () -> {}, () -> {
            f.runtime.reopen(); f.drain(); f.selectB();
            f.runtime.observePreviewBody(preview(), () -> PreviewRenderer.DisplayKind.UNCONFIRMED);
            replacement.set(prepare(CompletableFuture.completedFuture(SECOND)));
        });
        f.runtime.closeScreen(); f.drain();
        assertEquals(ClientSnapshot.Lifecycle.READY, f.runtime.snapshot().lifecycle());
        assertEquals(PreviewAssets.NumericFitStatus.RETIRED, old.join().status());
        assertNotNull(replacement.get());
        assertEquals(SECOND, replacement.get().join().value());
        assertSame(replacement.get(), prepare(CompletableFuture.completedFuture(FIRST)));
        assertTrue(bodyRegistrationAttached());
    }

    @ParameterizedTest
    @CsvSource({"editor,close", "editor,shutdown", "providers,close", "providers,shutdown"})
    void identityRefreshCannotReadmitBodyAfterRetirementCallbackClosesScope(String surface, String event) {
        openSurface(surface);
        var oldPreview = surfacePreview(surface);
        var retired = new AtomicInteger();
        var accepted = new AtomicInteger();
        f.runtime.observePreviewBody(oldPreview, () -> PreviewRenderer.DisplayKind.UNCONFIRMED, () -> {}, () -> {
            retired.incrementAndGet();
            retire(event);
        });
        f.identity.set(new GameSessionTokenSource.SessionIdentity(new UUID(0, 999), "SyntheticOther"));
        var callback = f.runtime.observePreviewBody(oldPreview, () -> PreviewRenderer.DisplayKind.ORDINARY,
                accepted::incrementAndGet, () -> {});
        assertEquals(1, retired.get());
        assertEquals(ClientSnapshot.Lifecycle.CLOSED, f.runtime.snapshot().lifecycle());
        for (String field : List.of("bodyRegistration", "bodyCallback", "bodyCapability", "bodyAccepted", "bodyRetired", "bodyPreview"))
            assertNull(bodyField(field), field);
        callback.accept(PreviewRenderer.BodyStatus.ORDINARY);
        f.drain();
        assertEquals(0, accepted.get());
        assertFalse(bodyRegistrationAttached());
        assertEquals(0, store.loads);
        assertEquals(0, store.creates);
    }

    @ParameterizedTest @ValueSource(strings={"editor", "providers"})
    void identityRefreshCannotReplaceBodyAdmittedByReentrantReopening(String surface) {
        openSurface(surface);
        var oldPreview = surfacePreview(surface);
        var originalIdentity = f.identity.get();
        var successor = new java.util.concurrent.atomic.AtomicReference<java.util.function.Consumer<PreviewRenderer.BodyStatus>>();
        var successorRetired = new AtomicInteger();
        f.runtime.observePreviewBody(oldPreview, () -> PreviewRenderer.DisplayKind.UNCONFIRMED, () -> {}, () -> {
            f.runtime.closeScreen();
            f.identity.set(originalIdentity);
            f.runtime.reopen(); f.drain(); openSurface(surface);
            successor.set(f.runtime.observePreviewBody(surfacePreview(surface), () -> PreviewRenderer.DisplayKind.UNCONFIRMED,
                    () -> {}, successorRetired::incrementAndGet));
        });
        f.identity.set(new GameSessionTokenSource.SessionIdentity(new UUID(0, 999), "SyntheticOther"));
        var stale = f.runtime.observePreviewBody(oldPreview, () -> PreviewRenderer.DisplayKind.ORDINARY);
        assertNotNull(successor.get());
        assertEquals(ClientSnapshot.Lifecycle.READY, f.runtime.snapshot().lifecycle());
        assertSame(successor.get(), bodyField("bodyCallback"));
        assertTrue(bodyRegistrationAttached());
        stale.accept(PreviewRenderer.BodyStatus.ORDINARY);
        assertSame(successor.get(), bodyField("bodyCallback"));
        assertEquals(0, successorRetired.get());
        f.runtime.closeScreen();
        assertEquals(1, successorRetired.get());
    }

    @ParameterizedTest @ValueSource(strings={"editor", "providers"})
    void identityRefreshCannotReplaceAccessoriesAdmittedByReentrantReopening(String surface) {
        openSurface(surface);
        var oldPreview = surfacePreview(surface);
        var originalIdentity = f.identity.get();
        var successor = new java.util.concurrent.atomic.AtomicReference<java.util.function.Consumer<PreviewAccessories>>();
        f.runtime.observePreviewBody(oldPreview, () -> PreviewRenderer.DisplayKind.UNCONFIRMED, () -> {}, () -> {
            f.runtime.closeScreen();
            f.identity.set(originalIdentity);
            f.runtime.reopen(); f.drain(); openSurface(surface);
            successor.set(f.runtime.observePreviewAccessories(surfacePreview(surface),
                    () -> PreviewAccessories.unavailable(PreviewRenderer.LiveReason.LOADING)));
            assertSame(successor.get(), bodyField("accessoryCallback"));
        });
        f.identity.set(new GameSessionTokenSource.SessionIdentity(new UUID(0, 999), "SyntheticOther"));
        var stale = f.runtime.observePreviewAccessories(oldPreview,
                () -> PreviewAccessories.unavailable(PreviewRenderer.LiveReason.LOADING));
        assertNotNull(successor.get());
        assertEquals(ClientSnapshot.Lifecycle.READY, f.runtime.snapshot().lifecycle());
        assertSame(successor.get(), bodyField("accessoryCallback"));
        stale.accept(PreviewAccessories.unavailable(PreviewRenderer.LiveReason.LOADING));
        assertSame(successor.get(), bodyField("accessoryCallback"));
    }

    private Object bodyField(String name) {
        try {
            var field = ClientRuntime.class.getDeclaredField(name);
            field.setAccessible(true);
            return field.get(f.runtime);
        } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }

    private boolean bodyRegistrationAttached() { return bodyField("bodyRegistration") != null; }

    @Test void settledDemandRebindsRetiredSameKeyPreparationWithoutNumericRetry() {
        var attempts=new AtomicInteger();
        var completions=new AtomicInteger();
        var gates=new ArrayList<EditorPreviewSession.FitPreparation>();
        PreviewRenderer<Object> renderer=new PreviewRenderer<>() {
            public void render(Object graphics, PreviewRequest request) { throw new AssertionError(); }
            public Optional<ModelFitPreparation> prepareModelFit(String source, PreviewAppearance appearance) {
                attempts.incrementAndGet();
                var session=new EditorPreviewSession();session.beginBody(source,false,true);
                var gate=session.prepareFit();gates.add(gate);
                var actual=preparation(gate,CompletableFuture.completedFuture(new ModelFitMeasurement(
                        FIRST.referenceHeight(),FIRST.referenceCenterX(),FIRST.referenceCenterY(),FIRST.referenceCenterZ(),FIRST.fit())));
                return Optional.of(new ModelFitPreparation() {
                    public boolean current() { return actual.current(); }
                    public CompletableFuture<ModelFitMeasurement> measure() { return actual.measure(); }
                    public void complete(ModelFitMeasurement value) { completions.incrementAndGet();actual.complete(value); }
                    public void close() { actual.close(); }
                });
            }
        };
        var appearance=new PreviewRenderer.PreviewAppearance(new TextureRegistry.TextureHandle("synthetic:skin",64,64),
                SkinModel.CLASSIC,Optional.empty(),PreviewRenderer.CapeMode.OFF,true);
        var result=f.runtime.preparePreviewModelFit(preview(),renderer,appearance);f.drain();
        assertEquals(FIRST,result.join().value());
        int loads=store.loads,creates=store.creates,measured=measurements.get();
        for(int i=0;i<100;i++) assertSame(result,f.runtime.preparePreviewModelFit(preview(),renderer,appearance));
        assertEquals(1,attempts.get());assertEquals(1,completions.get());
        gates.get(0).close();
        assertSame(result,f.runtime.preparePreviewModelFit(preview(),renderer,appearance));
        assertEquals(2,attempts.get());assertEquals(2,completions.get());assertTrue(gates.get(1).current());
        gates.get(0).complete();assertFalse(gates.get(0).current());
        for(int i=0;i<100;i++) assertSame(result,f.runtime.preparePreviewModelFit(preview(),renderer,appearance));
        assertEquals(2,attempts.get());assertEquals(2,completions.get());
        assertEquals(loads,store.loads);assertEquals(creates,store.creates);assertEquals(measured,measurements.get());
        f.runtime.resourcesReloaded();f.drain();
        for(int i=0;i<100;i++) f.runtime.preparePreviewModelFit(preview(),renderer,appearance);
        assertEquals(2,attempts.get());assertFalse(gates.get(1).current());
    }

    @ParameterizedTest @ValueSource(strings={"timeout","decline"})
    void settledReboundTerminalSameOwnerDoesNotRetry(String terminal) {
        long[] now={0};
        var owner=new EditorPreviewSession[]{new EditorPreviewSession(() -> now[0])};
        var attempts=new AtomicInteger();var completions=new AtomicInteger();var suppressed=new AtomicInteger();
        var gates=new ArrayList<EditorPreviewSession.FitPreparation>();
        var measured=new PreviewRenderer.ModelFitMeasurement(FIRST.referenceHeight(),FIRST.referenceCenterX(),FIRST.referenceCenterY(),FIRST.referenceCenterZ(),FIRST.fit());
        PreviewRenderer<Object> renderer=new PreviewRenderer<>() {
            public void render(Object graphics,PreviewRequest request) { throw new AssertionError(); }
            public void suppressModelFit(String source) { suppressed.incrementAndGet();owner[0].suppressFit(source); }
            public Optional<ModelFitPreparation> prepareModelFit(String source,PreviewAppearance appearance) {
                attempts.incrementAndGet();var captured=owner[0];captured.beginBody(source,true,true);
                var gate=captured.prepareFit();gates.add(gate);
                return Optional.of(new ModelFitPreparation() {
                    public boolean ownerCurrent() { return captured==owner[0] && gate.ownerCurrent(); }
                    public boolean current() { return gate.current(); }
                    public CompletableFuture<ModelFitMeasurement> measure() { return CompletableFuture.completedFuture(measured); }
                    public void complete(ModelFitMeasurement value) { completions.incrementAndGet();gate.complete(); }
                    public void close() { gate.close(); }
                });
            }
        };
        var appearance=new PreviewRenderer.PreviewAppearance(new TextureRegistry.TextureHandle("synthetic:skin",64,64),SkinModel.CLASSIC,Optional.empty(),PreviewRenderer.CapeMode.OFF,true);
        var result=f.runtime.preparePreviewModelFit(preview(),renderer,appearance);f.drain();
        assertEquals(FIRST,result.join().value());
        owner[0]=new EditorPreviewSession(() -> now[0]);
        assertSame(result,f.runtime.preparePreviewModelFit(preview(),renderer,appearance));
        assertEquals(2,attempts.get());assertEquals(2,completions.get());
        var sameOwner=owner[0];var rebound=gates.get(1);
        if(terminal.equals("timeout")) now[0]=java.time.Duration.ofSeconds(60).toNanos();
        else sameOwner.declineFit();
        assertFalse(rebound.current());assertTrue(rebound.ownerCurrent());
        int loads=store.loads,creates=store.creates;
        for(int i=0;i<100;i++) assertSame(result,f.runtime.preparePreviewModelFit(preview(),renderer,appearance));
        assertSame(sameOwner,owner[0]);assertEquals(2,attempts.get());assertEquals(2,completions.get());
        assertEquals(100,suppressed.get());assertTrue(sameOwner.fitSuppressed());
        assertEquals(loads,store.loads);assertEquals(creates,store.creates);
        rebound.complete();assertFalse(rebound.current());
        reopen();owner[0]=new EditorPreviewSession(() -> now[0]);
        var fresh=f.runtime.preparePreviewModelFit(preview(),renderer,appearance);f.drain();
        assertNotSame(result,fresh);assertEquals(FIRST,fresh.join().value());assertEquals(3,attempts.get());
        assertTrue(gates.get(2).current());assertEquals(creates,store.creates);
    }

    @ParameterizedTest @ValueSource(strings={"prepare","complete"})
    void reboundCallbacksCannotRetryOrOverwriteNewerAssociation(String phase) {
        var generation=new AtomicInteger();var attempts=new AtomicInteger();var completions=new AtomicInteger();
        var suppressed=new AtomicInteger();var callbacks=new AtomicInteger();var gates=new ArrayList<EditorPreviewSession.FitPreparation>();
        var appearance=new PreviewRenderer.PreviewAppearance(new TextureRegistry.TextureHandle("synthetic:skin",64,64),SkinModel.CLASSIC,Optional.empty(),PreviewRenderer.CapeMode.OFF,true);
        var measured=new PreviewRenderer.ModelFitMeasurement(FIRST.referenceHeight(),FIRST.referenceCenterX(),FIRST.referenceCenterY(),FIRST.referenceCenterZ(),FIRST.fit());
        PreviewRenderer<Object> renderer=new PreviewRenderer<>() {
            public void render(Object graphics,PreviewRequest request) { throw new AssertionError(); }
            public void suppressModelFit(String source) { suppressed.incrementAndGet(); }
            private void callback(String checkpoint) {
                if(generation.get()==1 && phase.equals(checkpoint) && callbacks.getAndIncrement()==0) {
                    generation.incrementAndGet();
                    f.runtime.preparePreviewModelFit(preview(),this,appearance);
                }
            }
            public Optional<ModelFitPreparation> prepareModelFit(String source,PreviewAppearance appearance) {
                attempts.incrementAndGet();int captured=generation.get();
                var session=new EditorPreviewSession();session.beginBody(source,false,true);var gate=session.prepareFit();gates.add(gate);
                callback("prepare");
                return Optional.of(new ModelFitPreparation() {
                    public boolean current() { return gate.current(); }
                    public boolean ownerCurrent() { return captured==generation.get(); }
                    public CompletableFuture<ModelFitMeasurement> measure() { return CompletableFuture.completedFuture(measured); }
                    public void complete(ModelFitMeasurement value) { completions.incrementAndGet();gate.complete();callback("complete"); }
                    public void close() { gate.close(); }
                });
            }
        };
        var result=f.runtime.preparePreviewModelFit(preview(),renderer,appearance);f.drain();assertEquals(FIRST,result.join().value());
        generation.incrementAndGet();
        assertSame(result,f.runtime.preparePreviewModelFit(preview(),renderer,appearance));
        for(int i=0;i<100;i++) assertSame(result,f.runtime.preparePreviewModelFit(preview(),renderer,appearance));
        assertEquals(3,attempts.get());assertEquals(3,completions.get());assertEquals(0,suppressed.get());
        assertTrue(gates.get(2).current());
        f.runtime.resourcesReloaded();f.drain();assertFalse(gates.get(2).current());
        for(int i=0;i<100;i++) f.runtime.preparePreviewModelFit(preview(),renderer,appearance);
        assertEquals(3,attempts.get());
    }

    @ParameterizedTest @CsvSource({"gallery,false", "editor,false", "gallery,true", "editor,true"})
    void pendingNativeOwnerRolloverContinuesOnlyBeforeOriginalDeadline(String surface, boolean timeout) throws Exception {
        var environment = ClientRuntime.class.getDeclaredField("skinExtensionEnvironment");
        environment.setAccessible(true);
        f.operations.useCpmPresence(() -> true);
        f.operations.enableProvider(com.naocraftlab.skins.core.provider.AppearanceProviders.Component.CAPE,
                com.naocraftlab.skins.core.provider.BuiltinProvider.CPM);
        reopen();
        environment.set(f.runtime, new SkinExtensionEnvironment(0, Map.of(), true));
        openSurface(surface);
        long[] now = {0};
        var gallery = new EditorPreviewSession.Gallery(() -> now[0]);
        var attempts = new AtomicInteger();
        var gates = new ArrayList<EditorPreviewSession.FitPreparation>();
        var measured = new ArrayList<CompletableFuture<PreviewRenderer.ModelFitMeasurement>>();
        var nativePreparations = new ArrayList<PreviewRenderer.ModelFitPreparation>();
        PreviewRenderer<Object> renderer = new PreviewRenderer<>() {
            public void render(Object graphics, PreviewRequest request) { throw new AssertionError(); }
            public Optional<ModelFitPreparation> prepareModelFit(String source, PreviewAppearance appearance) {
                attempts.incrementAndGet();
                var gate = gallery.session(source).prepareFit();
                var result = new CompletableFuture<ModelFitMeasurement>();
                gates.add(gate); measured.add(result);
                var preparation = new ModelFitPreparation() {
                    public boolean ownerCurrent() { return gate.ownerCurrent(); }
                    public boolean current() { return gate.current(); }
                    public CompletableFuture<ModelFitMeasurement> measure() { measurements.incrementAndGet(); return result; }
                    public void complete(ModelFitMeasurement value) { gate.complete(); }
                    public void close() { gate.close(); result.complete(null); }
                };
                nativePreparations.add(preparation);
                return Optional.of(preparation);
            }
        };
        var appearance = new PreviewRenderer.PreviewAppearance(new TextureRegistry.TextureHandle("synthetic:skin",64,64),
                SkinModel.CLASSIC, Optional.empty(), PreviewRenderer.CapeMode.OFF, true);
        var initial = surfacePreview(surface);
        String source = f.runtime.previewBodySource(initial), demand = f.runtime.previewBodyDemand(initial);
        gallery.prepare(source, demand, true, true, () -> true);
        var pending = f.runtime.preparePreviewModelFit(initial, renderer, appearance);
        f.drain();
        assertFalse(pending.isDone()); assertEquals(1, measurements.get());
        now[0] = java.time.Duration.ofSeconds(timeout ? 60 : 59).toNanos();
        if (timeout) {
            measured.get(0).complete(null);
            f.drain();
            assertTrue(pending.isDone());
            assertEquals(PreviewAssets.NumericFitStatus.RETIRED, pending.join().status());
        }
        var live = new PreviewAccessories(new UUID(0, 51), 1, PreviewAccessories.Availability.CONFIRMED,
                false, true, PreviewRenderer.LiveReason.AVAILABLE);
        f.runtime.observePreviewAccessories(initial, () -> live);
        var refined = surfacePreview(surface);
        assertTrue(refined.nativeAccessories());
        assertNotEquals(source, f.runtime.previewBodySource(refined));
        assertEquals(demand, f.runtime.previewBodyDemand(refined));
        var successor = gallery.prepare(f.runtime.previewBodySource(refined), demand, true, true, () -> true);
        assertFalse(gates.get(0).ownerCurrent()); assertFalse(gates.get(0).current());
        nativePreparations.get(0).close();
        f.drain();
        assertEquals(PreviewAssets.NumericFitStatus.RETIRED, pending.join().status());
        assertTrue(f.runtime.preparePreviewModelFit(initial, renderer, appearance).isDone());
        assertEquals(1, attempts.get());
        var next = f.runtime.preparePreviewModelFit(refined, renderer, appearance);
        if (timeout) {
            assertSame(pending, next); assertEquals(1, attempts.get());
            assertEquals(PreviewRenderer.BodyStatus.TIMED_OUT, successor.bodyStatus());
        } else {
            assertNotSame(pending, next); assertEquals(2, attempts.get());
            f.drain(); assertFalse(next.isDone());
            measured.get(1).complete(new PreviewRenderer.ModelFitMeasurement(FIRST.referenceHeight(),
                    FIRST.referenceCenterX(), FIRST.referenceCenterY(), FIRST.referenceCenterZ(), FIRST.fit()));
            f.drain();
            assertEquals(PreviewAssets.NumericFitStatus.STORED, next.join().status());
            assertEquals(FIRST, durable(a).value());
            successor.body(new PreviewModelFrames.Body(PreviewRenderer.BodyStatus.READY, () -> true));
            assertFalse(successor.body().current().getAsBoolean());
            successor.displayBody(() -> true).accept(new PreviewModelFrames.Body(PreviewRenderer.BodyStatus.READY, () -> true));
            assertTrue(successor.body().current().getAsBoolean());
            int loads = store.loads;
            for (int i = 0; i < 100; i++) assertSame(next, f.runtime.preparePreviewModelFit(refined, renderer, appearance));
            assertEquals(2, attempts.get()); assertEquals(loads, store.loads);
        }
        assertEquals(timeout ? 0 : 1, store.creates);
        gallery.close();
    }

    @Test void foregroundTimeoutLatchesRetirementWithoutRenderDrivenRetry() {
        long[] now = {0};
        var attempts = new AtomicInteger();
        var measured = new CompletableFuture<PreviewRenderer.ModelFitMeasurement>();
        PreviewRenderer<Object> renderer = new PreviewRenderer<>() {
            public void render(Object graphics, PreviewRequest request) { throw new AssertionError(); }
            public Optional<ModelFitPreparation> prepareModelFit(String source, PreviewAppearance appearance) {
                attempts.incrementAndGet();
                var session = new EditorPreviewSession(() -> now[0]);
                session.beginBody(source, true, true);
                return Optional.of(preparation(session.prepareFit(), measured));
            }
        };
        var appearance = new PreviewRenderer.PreviewAppearance(new TextureRegistry.TextureHandle("synthetic:skin", 64, 64),
                SkinModel.CLASSIC, Optional.empty(), PreviewRenderer.CapeMode.OFF, true);
        var pending = f.runtime.preparePreviewModelFit(preview(), renderer, appearance);
        f.drain();
        assertEquals(1, measurements.get());
        now[0] = java.time.Duration.ofSeconds(60).toNanos();
        measured.complete(new PreviewRenderer.ModelFitMeasurement(FIRST.referenceHeight(), FIRST.referenceCenterX(), FIRST.referenceCenterY(), FIRST.referenceCenterZ(), FIRST.fit()));
        f.drain();
        assertEquals(PreviewAssets.NumericFitStatus.RETIRED, pending.join().status());
        for (int i = 0; i < 100; i++) {
            f.runtime.tick(); f.view();
            assertSame(pending, f.runtime.preparePreviewModelFit(preview(), renderer, appearance));
        }
        f.drain();
        assertEquals(1, attempts.get());
        assertEquals(0, store.creates);
        reopen();
        var next = f.runtime.preparePreviewModelFit(preview(), renderer, appearance);
        f.drain();
        assertEquals(2, attempts.get());
        assertEquals(PreviewAssets.NumericFitStatus.STORED, next.join().status());
        assertEquals(FIRST, durable(a).value());
    }

    @Test void foregroundDemandCoalescesUnsupportedRendererUntilNewOpening() {
        var attempts = new AtomicInteger();
        PreviewRenderer<Object> renderer = new PreviewRenderer<>() {
            public void render(Object graphics, PreviewRequest request) { throw new AssertionError(); }
            public Optional<ModelFitPreparation> prepareModelFit(String source, PreviewAppearance appearance) {
                attempts.incrementAndGet();
                return Optional.empty();
            }
        };
        var appearance = new PreviewRenderer.PreviewAppearance(new TextureRegistry.TextureHandle("synthetic:skin", 64, 64),
                SkinModel.CLASSIC, Optional.empty(), PreviewRenderer.CapeMode.OFF, true);
        for (int i = 0; i < 100; i++) {
            f.runtime.preparePreviewModelFit(preview(), renderer, appearance);
            f.runtime.tick(); f.view();
        }
        f.drain();
        assertEquals(1, attempts.get());
        assertEquals(0, store.loads);
        assertEquals(0, store.creates);
        f.selectB();
        f.runtime.preparePreviewModelFit(preview(), renderer, appearance);
        assertEquals(2, attempts.get());
        for(int i=0;i<100;i++) f.runtime.preparePreviewModelFit(preview(), renderer, appearance);
        assertEquals(2, attempts.get());
        reopen();
        f.runtime.preparePreviewModelFit(preview(), renderer, appearance);
        assertEquals(3, attempts.get());
    }

    @Test void originalSessionDeadlineRejectsRealQueuedFirstCreateBeforeClientTimeoutQuery() {
        long[] now = {0};
        var session = new EditorPreviewSession(() -> now[0]);
        session.beginBody("A", true, true);
        var gate = session.prepareFit();
        var measured = new CompletableFuture<PreviewRenderer.ModelFitMeasurement>();
        var preparation = preparation(gate, measured);
        var result = f.runtime.preparePreviewModelFit(preview(), preparation);
        f.drain();
        session.body(new PreviewModelFrames.Body(PreviewRenderer.BodyStatus.READY, () -> true));
        assertEquals(PreviewRenderer.BodyStatus.PENDING, session.bodyStatus());
        measured.complete(new PreviewRenderer.ModelFitMeasurement(FIRST.referenceHeight(), FIRST.referenceCenterX(), FIRST.referenceCenterY(), FIRST.referenceCenterZ(), FIRST.fit()));
        assertFalse(f.workers.isEmpty());
        now[0] = java.time.Duration.ofSeconds(60).toNanos();
        f.drain();
        assertEquals(0, store.creates);
        assertEquals(PreviewModelFitStore.Status.ABSENT, durable(a).status());
        assertEquals(PreviewAssets.NumericFitStatus.RETIRED, result.join().status());
        assertEquals(PreviewRenderer.BodyStatus.TIMED_OUT, session.bodyStatus());
    }

    @Test void durableWinnerCompletesNumericGateButNotActualBodyAuthority() {
        var session = new EditorPreviewSession();
        session.beginBody("A", true, true);
        var gate = session.prepareFit();
        var measured = new CompletableFuture<PreviewRenderer.ModelFitMeasurement>();
        var result = f.runtime.preparePreviewModelFit(preview(), preparation(gate, measured));
        f.drain();
        session.body(new PreviewModelFrames.Body(PreviewRenderer.BodyStatus.READY, () -> true));
        new PreviewModelFitStorageAdapter(f.storage).create(key(a), FIRST, () -> true);
        measured.complete(new PreviewRenderer.ModelFitMeasurement(SECOND.referenceHeight(), SECOND.referenceCenterX(), SECOND.referenceCenterY(), SECOND.referenceCenterZ(), SECOND.fit()));
        f.drain();
        assertEquals(FIRST, result.join().value());
        assertEquals(PreviewRenderer.BodyStatus.READY, session.bodyStatus());
        assertFalse(session.body().current().getAsBoolean());
        session.displayBody(() -> true).accept(new PreviewModelFrames.Body(PreviewRenderer.BodyStatus.READY, () -> true));
        assertTrue(session.body().current().getAsBoolean());
    }

    private PreviewRenderer.ModelFitPreparation preparation(EditorPreviewSession.FitPreparation gate,
            CompletableFuture<PreviewRenderer.ModelFitMeasurement> measured) {
        return new PreviewRenderer.ModelFitPreparation() {
            public boolean current() { return gate.current(); }
            public CompletableFuture<PreviewRenderer.ModelFitMeasurement> measure() {
                assertFalse(f.workerLane);
                measurements.incrementAndGet();
                return measured;
            }
            public void complete(PreviewRenderer.ModelFitMeasurement fit) {
                assertFalse(f.workerLane);
                assertEquals(new PreviewRenderer.ModelFitMeasurement(FIRST.referenceHeight(), FIRST.referenceCenterX(), FIRST.referenceCenterY(), FIRST.referenceCenterZ(), FIRST.fit()), fit);
                gate.complete();
            }
            public void close() { gate.close(); }
        };
    }

    @BeforeEach void setup() throws Exception {
        f = new GalleryRuntimeSelectionTest();
        f.directory = directory.toRealPath();
        store = new LaneStore();
        f.previewModelFitStore = store;
        f.setup();
        var adapter = new LibraryStorageAdapter(f.storage);
        var library = new LibraryService(adapter, adapter, GalleryApplyTest.CLOCK);
        a = library.importSkin(GalleryRuntimeSelectionTest.ACCOUNT, "Synthetic A", SkinVariant.CLASSIC,
                SkinSource.IMPORTED, GalleryPreviewSourceJoinTest.png(0xff335577)).asset();
        b = library.importSkin(GalleryRuntimeSelectionTest.ACCOUNT, "Synthetic B", SkinVariant.SLIM,
                SkinSource.IMPORTED, GalleryPreviewSourceJoinTest.png(0xff779933)).asset();
        f.storage.updateAccount(GalleryRuntimeSelectionTest.ACCOUNT, old -> new AccountState(old.schemaVersion(), old.accountId(), old.skinAssets(),
                List.of(new AppearancePreset(GalleryRuntimeSelectionTest.A, "A", SkinReference.asset(a.id()), null, GalleryApplyTest.NOW, GalleryApplyTest.NOW),
                        new AppearancePreset(GalleryRuntimeSelectionTest.B, "B", SkinReference.asset(b.id()), null, GalleryApplyTest.NOW, GalleryApplyTest.NOW)), old.updatedAt()));
        f.runtime.dispatchWidget("gallery.reset"); f.drain();
        f.runtime.dispatchWidget(f.card(GalleryRuntimeSelectionTest.A));
        f.view();
        f.beforeOperation = method -> {
            if (Set.of("loadSkinPreview", "loadProviderTexture", "loadDurableAppearance", "initializeForRuntime").contains(method)) {
                assertTrue(f.workerLane, "I/O on client lane: " + method);
            }
        };
    }

    @AfterEach void close() { if (f != null && f.runtime != null) f.runtime.close(); }

    @ParameterizedTest @ValueSource(strings = {"sourceABA", "close", "resources", "world", "accountABA", "shutdown"})
    void queuedFirstCreateIsRetiredByExistingLifecycleBeforeWorkerIo(String transition) {
        var measurement = new CompletableFuture<PreviewModelFitStore.Value>();
        var pending = prepare(measurement);
        f.drain();
        assertEquals(1, measurements.get());
        assertFalse(pending.isDone());
        assertEquals(0, store.creates);
        measurement.complete(FIRST);
        assertEquals(1, f.workers.size());
        retire(transition);
        assertTrue(pending.isDone(), "Lifecycle must retire pending first-create immediately");
        assertEquals(PreviewAssets.NumericFitStatus.RETIRED, pending.join().status());
        f.drain();
        assertEquals(0, store.creates);
        assertEquals(PreviewModelFitStore.Status.ABSENT, durable(a).status());
    }

    @Test void sameOpeningCoalescesAndFramesDoNotRetrySuccessfulNumericWork() {
        var pending = prepare(CompletableFuture.completedFuture(FIRST));
        assertSame(pending, prepare(CompletableFuture.completedFuture(SECOND)));
        f.drain();
        assertEquals(PreviewAssets.NumericFitStatus.STORED, pending.join().status());
        assertEquals(FIRST, pending.join().value());
        int loads = store.loads, creates = store.creates;
        for (int i = 0; i < 100; i++) {
            f.runtime.tick();
            f.runtime.view(854 + i, 480, i, i);
            assertSame(pending, prepare(CompletableFuture.completedFuture(SECOND)));
        }
        f.drain();
        assertEquals(1, measurements.get());
        assertEquals(loads, store.loads);
        assertEquals(creates, store.creates);
        assertEquals(FIRST, durable(a).value());
    }

    @Test void realWriteFailureRetainsOnlyTransientValueAndRetriesOnlyAfterReopen() throws Exception {
        Path blocked = target(a).getParent();
        store.beforeCreate = () -> {
            try {
                Files.createDirectories(blocked.getParent());
                Files.writeString(blocked, "synthetic write obstruction");
            } catch (java.io.IOException failure) { throw new java.io.UncheckedIOException(failure); }
        };
        var result = prepare(CompletableFuture.completedFuture(FIRST));
        f.drain();
        assertEquals(PreviewAssets.NumericFitStatus.TRANSIENT, result.join().status());
        assertEquals(FIRST, result.join().value());
        assertFalse(Files.exists(target(a)));
        Files.delete(blocked);
        store.beforeCreate = () -> {};
        for (int i = 0; i < 100; i++) {
            f.runtime.tick(); f.view();
            assertSame(result, prepare(CompletableFuture.completedFuture(SECOND)));
        }
        f.drain();
        assertEquals(1, store.creates);
        assertEquals(1, measurements.get());
        assertEquals(PreviewModelFitStore.Status.ABSENT, durable(a).status());
        reopen();
        var retry = prepare(CompletableFuture.completedFuture(SECOND));
        f.drain();
        assertEquals(PreviewAssets.NumericFitStatus.STORED, retry.join().status());
        assertEquals(SECOND, durable(a).value());
        assertEquals(2, store.creates);
    }

    @ParameterizedTest @ValueSource(strings = {"corrupt", "unknown"})
    void unavailableRecordIsNotMeasuredOverwrittenOrRetriedByFrames(String failure) throws Exception {
        new PreviewModelFitStorageAdapter(f.storage).create(key(a), FIRST, () -> true);
        Path target = target(a);
        byte[] damaged = failure.equals("corrupt") ? new byte[] {0, 1, 2}
                : Files.readString(target).replace("\"schemaVersion\": 1", "\"schemaVersion\": 99")
                        .replace("\"schemaVersion\":1", "\"schemaVersion\":99").getBytes(java.nio.charset.StandardCharsets.UTF_8);
        if (failure.equals("unknown")) assertFalse(Arrays.equals(Files.readAllBytes(target), damaged));
        Files.write(target, damaged);
        var result = prepare(CompletableFuture.completedFuture(SECOND));
        f.drain();
        assertEquals(PreviewAssets.NumericFitStatus.UNAVAILABLE, result.join().status());
        assertEquals(0, measurements.get());
        for (int i = 0; i < 100; i++) { f.runtime.tick(); f.view(); assertSame(result, prepare(CompletableFuture.completedFuture(SECOND))); }
        f.drain();
        assertEquals(1, store.loads);
        assertEquals(0, store.creates);
        assertArrayEquals(damaged, Files.readAllBytes(target));
    }

    @Test void concurrentCreateReturnsTheRealDurableWinnerRatherThanMeasuredLoser() {
        var measured = new CompletableFuture<PreviewModelFitStore.Value>();
        var pending = prepare(measured); f.drain();
        assertFalse(pending.isDone());
        assertEquals(FIRST, new PreviewModelFitStorageAdapter(f.storage).create(key(a), FIRST, () -> true).value());
        measured.complete(SECOND); f.drain();
        assertEquals(PreviewAssets.NumericFitStatus.STORED, pending.join().status());
        assertEquals(FIRST, pending.join().value());
        assertEquals(FIRST, durable(a).value());
    }

    @ParameterizedTest @ValueSource(strings = {"sourceABA", "close", "resources", "world", "accountABA", "shutdown"})
    void heldMeasurementCompletionCannotCreateAfterRetirement(String transition) {
        var measured = new CompletableFuture<PreviewModelFitStore.Value>();
        var pending = prepare(measured); f.drain();
        retire(transition);
        assertTrue(pending.isDone());
        assertEquals(PreviewAssets.NumericFitStatus.RETIRED, pending.join().status());
        measured.complete(FIRST); f.drain();
        assertEquals(0, store.creates);
        assertEquals(PreviewModelFitStore.Status.ABSENT, durable(a).status());
    }

    @Test void identityChangeWithoutClientTickRetiresWorkerWithoutCallingRuntimeEffects() {
        var measured = new CompletableFuture<PreviewModelFitStore.Value>();
        var pending = prepare(measured); f.drain();
        measured.complete(FIRST);
        var old = f.identity.get();
        f.identity.set(new GameSessionTokenSource.SessionIdentity(new UUID(0, 999), "SyntheticOther"));
        long generation = f.runtime.snapshot().generation();
        f.work();
        assertEquals(generation, f.runtime.snapshot().generation());
        assertFalse(pending.isDone(), "Worker must only queue client retirement");
        assertEquals(0, store.creates);
        f.identity.set(old);
        f.drain();
        assertEquals(PreviewAssets.NumericFitStatus.RETIRED, pending.join().status());
        assertEquals(PreviewModelFitStore.Status.ABSENT, durable(a).status());
    }

    @Test void heldReadCannotRetargetNewSourceOrRemoveExistingDurableRecord() {
        new PreviewModelFitStorageAdapter(f.storage).create(key(a), FIRST, () -> true);
        var pending = prepare(CompletableFuture.completedFuture(SECOND));
        while (store.loads == 0) {
            assertFalse(f.workers.isEmpty());
            f.work();
            if (store.loads == 0) while (!f.clients.isEmpty()) f.clients.remove().run();
        }
        assertFalse(pending.isDone());
        f.selectB();
        assertEquals(PreviewAssets.NumericFitStatus.RETIRED, pending.join().status());
        var next = prepare(CompletableFuture.completedFuture(SECOND));
        f.drain();
        assertEquals(SECOND, next.join().value());
        assertEquals(FIRST, durable(a).value());
        assertEquals(SECOND, durable(b).value());
    }

    @Test void resourceReloadReopenAndRenamedPresetWithCapeReuseFirstRecord() throws Exception {
        var first = prepare(CompletableFuture.completedFuture(FIRST)); f.drain();
        assertEquals(FIRST, first.join().value());
        f.runtime.resourcesReloaded(); f.drain();
        f.storage.updateAccount(GalleryRuntimeSelectionTest.ACCOUNT, old -> new AccountState(old.schemaVersion(), old.accountId(), old.skinAssets(),
                old.presets().stream().map(p -> p.id().equals(GalleryRuntimeSelectionTest.B)
                        ? new AppearancePreset(p.id(), "Renamed duplicate", SkinReference.asset(a.id()), "synthetic-cape",
                                OuterLayerVisibility.noneVisible(), p.createdAt(), p.updatedAt()) : p).toList(), old.updatedAt()));
        reopen(); f.selectB();
        var second = prepare(CompletableFuture.completedFuture(SECOND)); f.drain();
        assertEquals(FIRST, second.join().value());
        assertEquals(PreviewAssets.NumericFitStatus.STORED, second.join().status());
        assertEquals(1, measurements.get());
        assertEquals(1, store.creates);
        assertEquals(FIRST, durable(a).value());
    }

    @Test void retirementCallbackCanInstallReplacementWithoutOldWorkerClearingIt() {
        var measured = new CompletableFuture<PreviewModelFitStore.Value>();
        var old = prepare(measured); f.drain();
        var replacement = new java.util.concurrent.atomic.AtomicReference<CompletableFuture<PreviewAssets.NumericFitResult>>();
        old.thenAccept(result -> replacement.set(prepare(CompletableFuture.completedFuture(SECOND))));
        f.selectB();
        assertNotNull(replacement.get());
        measured.complete(FIRST); f.drain();
        assertEquals(SECOND, replacement.get().join().value());
        assertSame(replacement.get(), prepare(CompletableFuture.completedFuture(FIRST)));
        assertEquals(PreviewModelFitStore.Status.ABSENT, durable(a).status());
        assertEquals(SECOND, durable(b).value());
    }

    @Test void retirementAfterRealLockContentionRejectsTheRuntimeFirstCreate() throws Exception {
        var measured = new CompletableFuture<PreviewModelFitStore.Value>();
        var pending = prepare(measured); f.drain(); measured.complete(FIRST);
        var lockHeld = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        var writerThread = new java.util.concurrent.atomic.AtomicReference<Thread>();
        try {
            var blocker = executor.submit(() -> new PreviewModelFitStorageAdapter(f.storage).create(key(b), SECOND, () -> {
                if (atImmutableWriter()) { lockHeld.countDown(); await(release); }
                return true;
            }));
            assertTrue(lockHeld.await(5, TimeUnit.SECONDS));
            var writer = executor.submit(() -> {
                writerThread.set(Thread.currentThread());
                f.work();
            });
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            boolean waiting = false;
            while (System.nanoTime() < deadline) {
                Thread thread = writerThread.get();
                if (thread != null && thread.getState() == Thread.State.WAITING
                        && Arrays.stream(thread.getStackTrace()).anyMatch(frame -> frame.getClassName().endsWith("InterProcessLockManager")
                                && frame.getMethodName().equals("acquire"))) { waiting = true; break; }
                Thread.sleep(1);
            }
            assertTrue(waiting, "Runtime writer must contend on the real account lock");
            f.workerLane = false;
            f.runtime.closeScreen();
            assertEquals(PreviewAssets.NumericFitStatus.RETIRED, pending.join().status());
            f.workerLane = true;
            release.countDown();
            assertEquals(SECOND, blocker.get(5, TimeUnit.SECONDS).value());
            writer.get(5, TimeUnit.SECONDS);
            f.drain();
            assertEquals(1, store.creates);
            assertEquals(PreviewModelFitStore.Status.ABSENT, durable(a).status());
            assertEquals(SECOND, durable(b).value());
        } finally {
            release.countDown(); executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
            f.workerLane = false;
        }
    }

    @Test void retirementAtActualPrepublicationGuardLeavesNoDurableFirstValue() throws Exception {
        var measured = new CompletableFuture<PreviewModelFitStore.Value>();
        var pending = prepare(measured); f.drain(); measured.complete(FIRST);
        var atPublication = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var checks = new AtomicInteger();
        store.guardProbe = () -> {
            if (atImmutableWriter() && checks.incrementAndGet() == 2) { atPublication.countDown(); await(release); }
        };
        var executor = Executors.newSingleThreadExecutor();
        try {
            var writer = executor.submit(f::work);
            assertTrue(atPublication.await(5, TimeUnit.SECONDS));
            assertFalse(Files.exists(target(a)));
            f.workerLane = false;
            f.selectB();
            assertEquals(PreviewAssets.NumericFitStatus.RETIRED, pending.join().status());
            f.workerLane = true;
            release.countDown(); writer.get(5, TimeUnit.SECONDS);
            f.drain();
            assertEquals(2, checks.get());
            assertEquals(PreviewModelFitStore.Status.ABSENT, durable(a).status());
            try (var files = Files.list(target(a).getParent())) { assertEquals(0, files.count()); }
        } finally {
            release.countDown(); executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
            f.workerLane = false;
        }
    }

    private static boolean atImmutableWriter() {
        return StackWalker.getInstance().walk(frames -> frames.anyMatch(frame -> frame.getClassName().endsWith("AtomicFileWriter")
                && frame.getMethodName().equals("createImmutable")));
    }

    private static void await(CountDownLatch latch) {
        try { assertTrue(latch.await(5, TimeUnit.SECONDS)); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new AssertionError(interrupted); }
    }

    @Test void numericRecordDoesNotAcknowledgeAnActualBodyOrChangePreviewPolicy() {
        var preview = preview();
        var accepted = new AtomicInteger();
        f.runtime.observePreviewBody(preview, () -> PreviewRenderer.DisplayKind.UNCONFIRMED, accepted::incrementAndGet);
        var result = prepare(CompletableFuture.completedFuture(FIRST)); f.drain();
        assertEquals(PreviewAssets.NumericFitStatus.STORED, result.join().status());
        assertEquals(0, accepted.get());
        assertEquals(preview.intent(), preview().intent());
        assertEquals(preview.skin(), preview().skin());
        assertEquals(preview.requiresLoadedSkin(), preview().requiresLoadedSkin());
        assertFalse(f.runtime.snapshot().busy());
        assertEquals(GalleryRuntimeSelectionTest.A, f.runtime.snapshot().activePresetId().orElseThrow());
    }

    @Test void failureIsStickyForTheOpeningButCanMeasureAgainAfterReopen() {
        var failed = new CompletableFuture<PreviewModelFitStore.Value>();
        var result = prepare(failed); f.drain();
        failed.completeExceptionally(new IllegalStateException("Synthetic numeric failure")); f.drain();
        assertEquals(PreviewAssets.NumericFitStatus.UNAVAILABLE, result.join().status());
        for (int i = 0; i < 100; i++) { f.runtime.tick(); f.view(); assertSame(result, prepare(CompletableFuture.completedFuture(FIRST))); }
        assertEquals(0, store.creates);
        assertEquals(1, measurements.get());
        reopen();
        var retried = prepare(CompletableFuture.completedFuture(FIRST)); f.drain();
        assertEquals(PreviewAssets.NumericFitStatus.STORED, retried.join().status());
        assertEquals(2, measurements.get());
    }

    @Test void sameFullContentWithAnotherVariantGetsADistinctRuntimeRecord() throws Exception {
        var adapter = new LibraryStorageAdapter(f.storage);
        var library = new LibraryService(adapter, adapter, GalleryApplyTest.CLOCK);
        var slim = library.importSkin(GalleryRuntimeSelectionTest.ACCOUNT, "Same content slim", SkinVariant.SLIM,
                SkinSource.IMPORTED, library.resolveSkin(library.load(GalleryRuntimeSelectionTest.ACCOUNT), a.id()).pngBytes()).asset();
        assertEquals(a.sha256(), slim.sha256());
        f.storage.updateAccount(GalleryRuntimeSelectionTest.ACCOUNT, old -> new AccountState(old.schemaVersion(), old.accountId(), old.skinAssets(),
                old.presets().stream().map(p -> p.id().equals(GalleryRuntimeSelectionTest.B)
                        ? new AppearancePreset(p.id(), p.name(), SkinReference.asset(slim.id()), null, p.createdAt(), p.updatedAt()) : p).toList(), old.updatedAt()));
        var classic = prepare(CompletableFuture.completedFuture(FIRST)); f.drain();
        assertEquals(FIRST, classic.join().value());
        reopen(); f.selectB();
        assertEquals(SkinVariant.SLIM, preview().variant());
        var other = prepare(CompletableFuture.completedFuture(SECOND)); f.drain();
        assertEquals(SECOND, other.join().value());
        assertEquals(FIRST, durable(a).value());
        assertEquals(SECOND, durable(slim).value());
        assertEquals(2, store.creates);
    }

    @Test void rejectedThumbnailAndForeignSourceCannotStartNumericIo() {
        var view = f.view();
        var card = view.previews().stream().filter(p -> !p.id().equals("gallery.preview")).findFirst().orElseThrow();
        var result = f.runtime.preparePreviewModelFit(card, () -> fail("No thumbnail measurement"));
        assertEquals(PreviewAssets.NumericFitStatus.UNAVAILABLE, result.join().status());
        var old = preview(); f.selectB();
        assertEquals(PreviewAssets.NumericFitStatus.UNAVAILABLE,
                f.runtime.preparePreviewModelFit(old, () -> fail("No stale-source measurement")).join().status());
        assertEquals(0, store.loads);
        assertEquals(0, store.creates);
        assertTrue(f.workers.isEmpty());
    }

    @Test void backendCommitDoesNotGrantStaleClientPublicationOrDeleteTheWinner() {
        var measured = new CompletableFuture<PreviewModelFitStore.Value>();
        var pending = prepare(measured); f.drain(); measured.complete(FIRST);
        f.work();
        assertEquals(FIRST, durable(a).value());
        assertFalse(pending.isDone(), "Durable commit and client publication are separate");
        f.selectB();
        assertEquals(PreviewAssets.NumericFitStatus.RETIRED, pending.join().status());
        var next = prepare(CompletableFuture.completedFuture(SECOND)); f.drain();
        assertEquals(SECOND, next.join().value());
        assertEquals(FIRST, durable(a).value());
        assertEquals(GalleryRuntimeSelectionTest.B, preview().presetId().orElseThrow());
    }

    @Test void numericPublicationPreservesSourcePngAndLibraryMetadataBytes() throws Exception {
        byte[] account = Files.readAllBytes(f.storage.layout().accountState(GalleryRuntimeSelectionTest.ACCOUNT));
        byte[] png = Files.readAllBytes(f.storage.assetPath(a.sha256()));
        var result = prepare(CompletableFuture.completedFuture(FIRST)); f.drain();
        assertEquals(PreviewAssets.NumericFitStatus.STORED, result.join().status());
        assertArrayEquals(account, Files.readAllBytes(f.storage.layout().accountState(GalleryRuntimeSelectionTest.ACCOUNT)));
        assertArrayEquals(png, Files.readAllBytes(f.storage.assetPath(a.sha256())));
    }

    @Test void fullEncodedBytesRatherThanRasterOrPresetIdentityDefineRuntimeKey() throws Exception {
        var adapter = new LibraryStorageAdapter(f.storage);
        var library = new LibraryService(adapter, adapter, GalleryApplyTest.CLOCK);
        byte[] original = library.resolveSkin(library.load(GalleryRuntimeSelectionTest.ACCOUNT), a.id()).pngBytes();
        byte[] type = "tEXt".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        byte[] payload = "synthetic\0encoded identity".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        var crc = new java.util.zip.CRC32(); crc.update(type); crc.update(payload);
        var output = new java.io.ByteArrayOutputStream();
        var data = new java.io.DataOutputStream(output);
        data.write(original, 0, original.length - 12);
        data.writeInt(payload.length); data.write(type); data.write(payload); data.writeInt((int) crc.getValue());
        data.write(original, original.length - 12, 12);
        var encoded = library.importSkin(GalleryRuntimeSelectionTest.ACCOUNT, "Same raster", SkinVariant.CLASSIC,
                SkinSource.IMPORTED, output.toByteArray()).asset();
        assertNotEquals(a.sha256(), encoded.sha256());
        var imageA = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(original));
        var imageB = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(output.toByteArray()));
        assertArrayEquals(imageA.getRGB(0, 0, 64, 64, null, 0, 64), imageB.getRGB(0, 0, 64, 64, null, 0, 64));
        var first = prepare(CompletableFuture.completedFuture(FIRST)); f.drain();
        assertEquals(FIRST, first.join().value());
        f.storage.updateAccount(GalleryRuntimeSelectionTest.ACCOUNT, old -> new AccountState(old.schemaVersion(), old.accountId(), old.skinAssets(),
                old.presets().stream().map(p -> p.id().equals(GalleryRuntimeSelectionTest.B)
                        ? new AppearancePreset(p.id(), p.name(), SkinReference.asset(encoded.id()), null, p.createdAt(), p.updatedAt()) : p).toList(), old.updatedAt()));
        reopen(); f.selectB();
        var second = prepare(CompletableFuture.completedFuture(SECOND)); f.drain();
        assertEquals(SECOND, second.join().value());
        assertEquals(FIRST, durable(a).value());
        assertEquals(SECOND, durable(encoded).value());
    }

    private Path target(SkinAsset asset) {
        return f.storage.layout().previewModelFit(GalleryRuntimeSelectionTest.ACCOUNT, asset.sha256(), asset.variant());
    }

    private PreviewModelFitStore.Key key(SkinAsset asset) {
        return new PreviewModelFitStore.Key(GalleryRuntimeSelectionTest.ACCOUNT, asset.sha256(), asset.variant());
    }

    private void reopen() {
        f.runtime.closeScreen(); f.runtime.reopen(); f.drain();
        f.runtime.dispatchWidget(f.card(GalleryRuntimeSelectionTest.A)); f.view();
    }

    private CompletableFuture<PreviewAssets.NumericFitResult> prepare(CompletableFuture<PreviewModelFitStore.Value> measurement) {
        return f.runtime.preparePreviewModelFit(preview(), () -> {
            assertFalse(f.workerLane);
            measurements.incrementAndGet();
            return measurement;
        });
    }

    private ViewSpec.Preview preview() { return f.runtime.galleryPreviewCandidate().orElseThrow(); }

    private PreviewModelFitStore.Result durable(SkinAsset asset) {
        return new PreviewModelFitStorageAdapter(f.storage).load(new PreviewModelFitStore.Key(
                GalleryRuntimeSelectionTest.ACCOUNT, asset.sha256(), asset.variant()));
    }

    private void retire(String transition) {
        switch (transition) {
            case "sourceABA" -> { f.selectB(); f.runtime.dispatchWidget(f.card(GalleryRuntimeSelectionTest.A)); }
            case "close" -> f.runtime.closeScreen();
            case "resources" -> f.runtime.resourcesReloaded();
            case "world" -> f.runtime.importWorldChanged();
            case "accountABA" -> {
                var original = f.identity.get();
                f.identity.set(new GameSessionTokenSource.SessionIdentity(new UUID(0, 999), "SyntheticOther"));
                f.runtime.tick();
                f.identity.set(original);
                f.runtime.tick();
            }
            case "shutdown" -> f.runtime.close();
            default -> throw new AssertionError(transition);
        }
    }

    private static PreviewModelFitStore.Value value(double height) {
        return new PreviewModelFitStore.Value(2, 0.25, 0.95, -0.125, PreviewModelNormalization.full(2, height, 1, 3, -1, 4, 2));
    }

    private final class LaneStore implements PreviewModelFitStore {
        int loads;
        int creates;
        Runnable beforeCreate = () -> {};
        Runnable guardProbe = () -> {};
        @Override public Result load(Key key) {
            assertTrue(f.workerLane, "Numeric read on client lane");
            loads++;
            return new PreviewModelFitStorageAdapter(f.storage).load(key);
        }
        @Override public Result create(Key key, Value value, BooleanSupplier current) {
            assertTrue(f.workerLane, "Numeric write on client lane");
            creates++;
            beforeCreate.run();
            return new PreviewModelFitStorageAdapter(f.storage).create(key, value, () -> {
                assertTrue(f.workerLane, "Durable guard on client lane");
                guardProbe.run();
                return current.getAsBoolean();
            });
        }
    }
}

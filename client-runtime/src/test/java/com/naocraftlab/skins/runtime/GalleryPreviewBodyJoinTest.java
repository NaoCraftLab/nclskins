package com.naocraftlab.skins.runtime;

import com.naocraftlab.skins.client.*;
import com.naocraftlab.skins.core.compatibility.SkinExtensionEnvironment;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

final class GalleryPreviewBodyJoinTest {
    @TempDir Path directory;

    @Test void contentOnlyFactsRemoveCardWithoutReplacingBodyAndRetiredCallbacksCannotRestoreIt() throws Exception {
        var f=fixture();
        try {
            var env=ClientRuntime.class.getDeclaredField("skinExtensionEnvironment");env.setAccessible(true);
            env.set(f.runtime,new SkinExtensionEnvironment(0,Map.of(),true));
            GalleryInspectLayersTest.providers(f,f.runtime.snapshot().providers());
            var live=new AtomicReference<>(new PreviewAccessories(new UUID(0,91),1,
                    PreviewAccessories.Availability.CONFIRMED,true,true,PreviewRenderer.LiveReason.AVAILABLE));
            f.runtime.observePreviewAccessories(main(f),live::get);
            var candidate=f.runtime.galleryPreviewCandidate().orElseThrow();
            f.runtime.observePreviewBody(candidate,()->PreviewRenderer.DisplayKind.CPM).accept(PreviewRenderer.BodyStatus.READY);
            assertTrue(f.view().widget("gallery.inspect.CPM").orElseThrow().activationEnabled());
            var reads=new AtomicInteger();
            var callback=f.runtime.observePreviewAccessories(candidate,()->{reads.incrementAndGet();return live.get();});
            String body=f.runtime.previewBodySource(candidate);
            var before=ScopedEditorSaveTest.inventory(directory);var providers=f.runtime.snapshot().providers();
            live.set(new PreviewAccessories(new UUID(0,91),2,PreviewAccessories.Availability.CONFIRMED,true,true,
                    PreviewRenderer.LiveReason.AVAILABLE,true,true));
            callback.accept(live.get());
            assertTrue(f.view().widget("gallery.inspect.CPM").isEmpty());
            assertTrue(f.view().widget("gallery.inspect.none").isPresent(),"Proven unused content contributes only UI absence, not native policy absence");
            var current=f.runtime.galleryPreviewCandidate().orElseThrow();
            assertEquals(body,f.runtime.previewBodySource(current));
            assertEquals(candidate.nativeAccessories(),current.nativeAccessories());
            assertEquals(PreviewRenderer.DisplayKind.CPM,draft(f).displayedBodyKind());
            f.runtime.dispatchWidget("gallery.inspect.CPM",false,InteractionOrigin.POINTER);
            assertEquals(body,f.runtime.previewBodySource(f.runtime.galleryPreviewCandidate().orElseThrow()));
            for(int i=0;i<4;i++) {callback.accept(live.get());assertTrue(f.view().widget("gallery.inspect.CPM").isEmpty());}
            assertEquals(providers,f.runtime.snapshot().providers());assertEquals(before,ScopedEditorSaveTest.inventory(directory));
            assertEquals(0,f.reconciliations.get());
            f.selectB();
            int retiredReads=reads.get();
            callback.accept(new PreviewAccessories(new UUID(0,91),3,PreviewAccessories.Availability.CONFIRMED,true,true,PreviewRenderer.LiveReason.AVAILABLE));
            assertEquals(retiredReads,reads.get());
            assertTrue(f.view().widget("gallery.inspect.CPM").isEmpty());
        } finally {f.runtime.close();}
    }

    @Test void exactNativeCapeIconSurvivesItsOwnInspectionAndColdSuccessorWithoutWrites() throws Exception {
        var f=fixture();
        try {
            var env=ClientRuntime.class.getDeclaredField("skinExtensionEnvironment");env.setAccessible(true);
            env.set(f.runtime,new SkinExtensionEnvironment(0,Map.of(),true));
            GalleryInspectLayersTest.providers(f,f.runtime.snapshot().providers());
            assertTrue(f.view().widget("gallery.inspect.CPM").isEmpty());
            assertTrue(f.view().widget("gallery.inspect.none").isEmpty(),"Applicable unknown CPM is not absence");
            var value=new AtomicReference<>(new PreviewAccessories(new UUID(0,91),1,
                    PreviewAccessories.Availability.CONFIRMED,true,true,PreviewRenderer.LiveReason.AVAILABLE));
            f.runtime.observePreviewAccessories(main(f),value::get);
            var candidate=f.runtime.galleryPreviewCandidate().orElseThrow();
            f.runtime.observePreviewBody(candidate,()->PreviewRenderer.DisplayKind.CPM).accept(PreviewRenderer.BodyStatus.READY);
            var row=f.view().widget("gallery.inspect.CPM").orElseThrow();
            assertTrue(row.activationEnabled());assertEquals(Optional.of("active"),row.value());
            assertTrue(f.view().iconDecorations().stream().anyMatch(i->i.ownerWidgetId().equals(row.id())&&i.icon()==GuiIcon.SOURCE_CPM));
            assertTrue(f.view().backEquipmentPreviews().stream().noneMatch(p->p.id().startsWith(row.id())));
            var before=ScopedEditorSaveTest.inventory(directory);var providers=f.runtime.snapshot().providers();
            f.runtime.dispatchWidget(row.id(),false,InteractionOrigin.POINTER);
            var inspected=f.runtime.galleryPreviewCandidate().orElseThrow();
            assertEquals(Optional.of("CPM"),inspected.bodySource().orElseThrow().inspectionIdentity());
            assertTrue(inspected.nativeAccessories());assertTrue(inspected.capeId().isEmpty());
            value.set(PreviewAccessories.unavailable(PreviewRenderer.LiveReason.LOADING));
            f.runtime.observePreviewAccessories(inspected,value::get);
            for(int i=0;i<5;i++)assertEquals(f.runtime.previewBodySource(inspected),f.runtime.previewBodySource(f.runtime.galleryPreviewCandidate().orElseThrow()));
            assertFalse(f.view().widget(row.id()).orElseThrow().activationEnabled());
            value.set(new PreviewAccessories(new UUID(0,92),2,PreviewAccessories.Availability.CONFIRMED,true,true,PreviewRenderer.LiveReason.AVAILABLE));
            f.runtime.observePreviewAccessories(inspected,value::get);
            f.runtime.observePreviewBody(inspected,()->PreviewRenderer.DisplayKind.CPM).accept(PreviewRenderer.BodyStatus.READY);
            assertEquals(Optional.of("active"),f.view().widget(row.id()).orElseThrow().value());
            assertEquals(providers,f.runtime.snapshot().providers());assertEquals(before,ScopedEditorSaveTest.inventory(directory));
            assertEquals(0,f.reconciliations.get());
            f.selectB();assertTrue(f.runtime.galleryPreviewCandidate().orElseThrow().bodySource().orElseThrow().inspectionIdentity().isEmpty());
        } finally {f.runtime.close();}
    }

    @Test void admissionIsPureAndSemanticSelectionRevokesBeforeAnotherNativePreparation() throws Exception {
        var f = fixture();
        try {
            var a = main(f);
            f.runtime.observePreviewBody(a, () -> PreviewRenderer.DisplayKind.UNCONFIRMED, () -> {});
            var admission = f.runtime.galleryPreviewAdmission(a);
            var cache = ClientRuntime.class.getDeclaredField("galleryAdmissionSource"); cache.setAccessible(true);
            assertTrue(admission.getAsBoolean(), "captured=" + f.runtime.previewBodySource(a) + ", cached=" + cache.get(f.runtime));
            f.beforeOperation = name -> { throw new AssertionError("Admission must not call operations: " + name); };
            for (int i=0;i<10;i++) assertTrue(admission.getAsBoolean());
            f.beforeOperation = ignored -> {};
            f.selectB();
            f.beforeOperation = name -> { throw new AssertionError("Stale admission must stay pure: " + name); };
            assertFalse(admission.getAsBoolean());
            assertTrue(f.runtime.galleryPreviewFrameCurrent(a), "Desired revocation does not revoke retained frame authority");
            f.beforeOperation = ignored -> {};
        } finally { f.runtime.close(); }
    }

    @Test void queuedAcceptanceAcknowledgesBeforePublicationAndReentrantClosePublishesNoSuccess() throws Exception {
        var f = fixture();
        try {
            var events = new ArrayList<String>();
            var preview = main(f);
            var callback = f.runtime.observePreviewBody(preview, () -> PreviewRenderer.DisplayKind.ORDINARY,
                    () -> events.add("ack"));
            var subscription = f.runtime.subscribe(ignored -> events.add("publication"));
            events.clear();
            f.workerLane = true;
            try { callback.accept(PreviewRenderer.BodyStatus.ORDINARY); }
            finally { f.workerLane = false; }
            assertTrue(events.isEmpty(), "Queued callback cannot acknowledge on the submitting worker");
            f.drain();
            assertEquals("ack",events.get(0));
            assertTrue(events.contains("publication"));
            subscription.close();
            f.selectB();
            var desired = f.runtime.galleryPreviewCandidate().orElseThrow();
            var close = f.runtime.observePreviewBody(desired, () -> PreviewRenderer.DisplayKind.ORDINARY, f.runtime::closeScreen);
            close.accept(PreviewRenderer.BodyStatus.ORDINARY);
            assertEquals(ClientSnapshot.Lifecycle.CLOSED,f.runtime.snapshot().lifecycle());
        } finally { f.runtime.close(); }
    }

    @Test void gallerySourceAndSessionRecipientDistinguishPreparationFromDisplay() throws Exception {
        var f = fixture();
        try {
            var preview = main(f);
            var source = preview.bodySource().orElseThrow();
            assertEquals(ViewSpec.BodyRole.GALLERY_MAIN, source.role());
            assertEquals(GalleryRuntimeSelectionTest.ACCOUNT, source.accountId());
            assertEquals(preview.presetId(), source.presetId());
            assertTrue(ViewHostPolicy.screenOwnedBody("gallery", preview));
            assertTrue(f.view().previews().stream().filter(p -> !p.id().equals("gallery.preview"))
                    .allMatch(p -> p.bodySource().isEmpty() && !ViewHostPolicy.screenOwnedBody("gallery", p)));
            var session = new EditorPreviewSession();
            PreviewRenderer<Object> renderer = renderer(session);
            var callback = f.runtime.observePreviewBody(preview, renderer::displayKind);
            session.observeBody(callback);
            session.beginBody(f.runtime.previewBodySource(preview), true, true);
            session.body(new PreviewModelFrames.Body(PreviewRenderer.BodyStatus.READY, () -> true));
            assertEquals(PreviewRenderer.DisplayKind.UNCONFIRMED, draft(f).displayedBodyKind());
            session.displayBody(() -> true).accept(new PreviewModelFrames.Body(PreviewRenderer.BodyStatus.READY, () -> true));
            assertEquals(PreviewRenderer.DisplayKind.CPM, draft(f).displayedBodyKind());
            session.invalidateDisplayedBody();
            assertEquals(PreviewRenderer.DisplayKind.UNCONFIRMED, draft(f).displayedBodyKind());
            session.beginBody(f.runtime.previewBodySource(preview), false, true);
            session.ordinaryDisplay(() -> true).accept(new PreviewModelFrames.Body(PreviewRenderer.BodyStatus.ORDINARY, () -> true));
            assertEquals(PreviewRenderer.DisplayKind.ORDINARY, draft(f).displayedBodyKind());
            f.selectB();
            assertEquals(source, main(f).bodySource().orElseThrow());
            var desired = f.runtime.galleryPreviewCandidate().orElseThrow();
            assertNotEquals(source, desired.bodySource().orElseThrow());
            callback.accept(PreviewRenderer.BodyStatus.READY);
            assertEquals(PreviewRenderer.DisplayKind.UNCONFIRMED, draft(f).displayedBodyKind());
            GalleryInspectLayersTest.ordinary(f);
            assertEquals(desired.bodySource(), main(f).bodySource());
            assertEquals(PreviewRenderer.DisplayKind.ORDINARY, draft(f).displayedBodyKind());
            assertTrue(f.runtime.snapshot().editor().isEmpty());
        } finally { f.runtime.close(); }
    }

    @Test void accessoryLateAvailabilityAndOldCardCallbacksCannotRetireMain() throws Exception {
        var f = fixture();
        try {
            var env = ClientRuntime.class.getDeclaredField("skinExtensionEnvironment"); env.setAccessible(true);
            env.set(f.runtime, new SkinExtensionEnvironment(0, Map.of(), true));
            var value = new AtomicReference<>(PreviewAccessories.unavailable(PreviewRenderer.LiveReason.LOADING));
            var reads = new AtomicInteger();
            var preview = main(f);
            var old = f.runtime.observePreviewAccessories(preview, () -> { reads.incrementAndGet(); return value.get(); });
            assertFalse(main(f).nativeAccessories());
            value.set(new PreviewAccessories(new UUID(0, 51), 1, PreviewAccessories.Availability.CONFIRMED,
                    false, true, PreviewRenderer.LiveReason.AVAILABLE));
            old.accept(value.get());
            assertTrue(main(f).nativeAccessories(), "Elytra-only geometry requires no cape PNG");
            assertTrue(f.view().widget("gallery.preview_mode").isPresent());
            var card = f.view().previews().stream().filter(p -> !p.id().equals("gallery.preview")).findFirst().orElseThrow();
            int before = reads.get();
            var foreign = f.runtime.observePreviewAccessories(card, () -> { fail("Card must stay baked"); return value.get(); });
            f.runtime.invalidatePreviewAccessories(foreign);
            assertTrue(main(f).nativeAccessories());
            assertTrue(reads.get() > before);
            f.selectB();
            old.accept(value.get());
            assertFalse(main(f).nativeAccessories());
            var next = f.runtime.observePreviewAccessories(main(f), value::get);
            assertTrue(main(f).nativeAccessories());
            f.runtime.invalidatePreviewAccessories(old);
            assertTrue(main(f).nativeAccessories());
            f.runtime.invalidatePreviewAccessories(next);
            assertFalse(main(f).nativeAccessories());
            assertTrue(f.runtime.snapshot().editor().isEmpty());
        } finally { f.runtime.close(); }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"confirmed", "absent", "ordinary"})
    void equalFreshAccessoryValuesConvergeThroughSubscriberView(String scenario) throws Exception {
        var f = fixture();
        try {
            var env = ClientRuntime.class.getDeclaredField("skinExtensionEnvironment"); env.setAccessible(true);
            env.set(f.runtime, new SkinExtensionEnvironment(0, Map.of(), !scenario.equals("ordinary")));
            int[] publications = {0}, reads = {0};
            var subscription = f.runtime.subscribe(ignored -> {
                assertTrue(++publications[0] <= 4, "Stable accessory input must converge");
                f.runtime.view(854,480,0,0);
            });
            try {
                f.runtime.observePreviewAccessories(main(f), () -> {
                    reads[0]++;
                    return scenario.equals("confirmed")
                            ? new PreviewAccessories(new UUID(0, 51), 1, PreviewAccessories.Availability.CONFIRMED,
                                    true, true, PreviewRenderer.LiveReason.AVAILABLE)
                            : PreviewAccessories.unavailable(PreviewRenderer.LiveReason.LIVE_UNAVAILABLE);
                });
                for (int i=0;i<4;i++) f.runtime.view(854,480,0,0);
                assertTrue(publications[0] <= 2);
                assertEquals(!scenario.equals("ordinary"), reads[0] > 0);
            } finally { subscription.close(); }
        } finally { f.runtime.close(); }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(value = PreviewAccessories.Availability.class,
            names = {"UNKNOWN", "PENDING", "FAILED", "BLOCKED"})
    void featureKnowledgeSurvivesColdSuccessorWithoutRetainingLiveAuthority(PreviewAccessories.Availability availability) throws Exception {
        var f = fixture();
        try {
            var env = ClientRuntime.class.getDeclaredField("skinExtensionEnvironment"); env.setAccessible(true);
            env.set(f.runtime, new SkinExtensionEnvironment(0, Map.of(), true));
            var value = new AtomicReference<>(new PreviewAccessories(new UUID(0, 71), 1,
                    PreviewAccessories.Availability.CONFIRMED, true, true, PreviewRenderer.LiveReason.AVAILABLE));
            var p0 = main(f);
            f.runtime.observePreviewAccessories(p0, value::get);
            var p1 = f.runtime.galleryPreviewCandidate().orElseThrow();
            assertTrue(p1.nativeAccessories());
            String key = f.runtime.previewBodySource(p1);
            value.set(new PreviewAccessories(null, 0, availability, false, false, PreviewRenderer.LiveReason.LOADING));
            f.runtime.observePreviewAccessories(p1, value::get);
            var next = f.runtime.galleryPreviewCandidate().orElseThrow();
            assertEquals(key, f.runtime.previewBodySource(next), "Cold successor is not confirmed feature absence");
            var flowField = ClientRuntime.class.getDeclaredField("galleryFlow"); flowField.setAccessible(true);
            var liveField = GalleryFlow.class.getDeclaredField("accessories"); liveField.setAccessible(true);
            var live = (PreviewAccessories) liveField.get(flowField.get(f.runtime));
            assertEquals(availability, live.availability());
            assertFalse(live.canInspect());
            assertNull(live.scope());
            assertEquals(PreviewRenderer.DisplayKind.UNCONFIRMED, draft(f).displayedBodyKind());
            value.set(new PreviewAccessories(new UUID(0, 72), 2, PreviewAccessories.Availability.CONFIRMED,
                    false, false, PreviewRenderer.LiveReason.AVAILABLE));
            f.runtime.view(854,480,0,0);
            assertFalse(f.runtime.galleryPreviewCandidate().orElseThrow().nativeAccessories(), "Confirmed absence replaces knowledge");
            value.set(new PreviewAccessories(new UUID(0, 72), 3, PreviewAccessories.Availability.CONFIRMED,
                    true, false, PreviewRenderer.LiveReason.AVAILABLE));
            f.runtime.view(854,480,0,0);
            assertTrue(f.runtime.galleryPreviewCandidate().orElseThrow().nativeAccessories());
            f.selectB();
            assertFalse(f.runtime.galleryPreviewCandidate().orElseThrow().nativeAccessories(), "Knowledge is exact-input scoped");
        } finally { f.runtime.close(); }
    }

    @Test void inputAdmissionIsPureAndSurvivesOnlyItsOwnDerivedBodyProjection() throws Exception {
        var f = fixture();
        try {
            var env = ClientRuntime.class.getDeclaredField("skinExtensionEnvironment"); env.setAccessible(true);
            env.set(f.runtime, new SkinExtensionEnvironment(0, Map.of(), true));
            var p0 = main(f);
            f.runtime.observePreviewBody(p0, () -> PreviewRenderer.DisplayKind.UNCONFIRMED);
            var fine = f.runtime.galleryPreviewAdmission(p0);
            var input = f.runtime.galleryAccessoryAdmission(p0);
            assertTrue(fine.getAsBoolean()); assertTrue(input.getAsBoolean());
            var value = new AtomicReference<>(new PreviewAccessories(new UUID(0, 81), 1,
                    PreviewAccessories.Availability.CONFIRMED, true, true, PreviewRenderer.LiveReason.AVAILABLE));
            var callback = f.runtime.observePreviewAccessories(p0, value::get);
            var p1 = f.runtime.galleryPreviewCandidate().orElseThrow();
            String key = f.runtime.previewBodySource(p1);
            assertFalse(fine.getAsBoolean(), "Metadata must not self-confirm the old full BODY key");
            f.beforeOperation = name -> fail("Input gate must be a pure captured fence: " + name);
            assertTrue(input.getAsBoolean());
            f.beforeOperation = ignored -> {};
            value.set(new PreviewAccessories(new UUID(0, 81), 1, PreviewAccessories.Availability.CONFIRMED,
                    true, true, PreviewRenderer.LiveReason.LOADING));
            callback.accept(value.get());
            assertEquals(key, f.runtime.previewBodySource(f.runtime.galleryPreviewCandidate().orElseThrow()));
            assertFalse(f.view().widget("gallery.preview_mode").orElseThrow().enabled());
            value.set(new PreviewAccessories(new UUID(0, 82), 2, PreviewAccessories.Availability.CONFIRMED,
                    true, false, PreviewRenderer.LiveReason.AVAILABLE));
            callback.accept(value.get());
            assertFalse(f.runtime.galleryPreviewCandidate().orElseThrow().capeHasElytra());
            assertTrue(input.getAsBoolean(), "Native scope/generation is observation, not canonical skin input");
            f.selectB();
            f.beforeOperation = name -> fail("Retired input gate must stay pure: " + name);
            assertFalse(input.getAsBoolean());
            f.beforeOperation = ignored -> {};
        } finally { f.runtime.close(); }
    }

    @Test void accessoryGetterCannotPublishOldFactsAfterReentrantSourceReplacement() throws Exception {
        var f = fixture();
        try {
            var env = ClientRuntime.class.getDeclaredField("skinExtensionEnvironment"); env.setAccessible(true);
            env.set(f.runtime, new SkinExtensionEnvironment(0, Map.of(), true));
            var armed = new java.util.concurrent.atomic.AtomicBoolean();
            var reads = new AtomicInteger();
            var live = new PreviewAccessories(new UUID(0, 83), 1, PreviewAccessories.Availability.CONFIRMED,
                    true, true, PreviewRenderer.LiveReason.AVAILABLE);
            var callback = f.runtime.observePreviewAccessories(main(f), () -> {
                reads.incrementAndGet();
                if (armed.getAndSet(false)) f.selectB();
                return live;
            });
            assertTrue(f.runtime.galleryPreviewCandidate().orElseThrow().nativeAccessories());
            armed.set(true);
            callback.accept(live);
            assertFalse(f.runtime.galleryPreviewCandidate().orElseThrow().nativeAccessories());
            int retired = reads.get();
            callback.accept(live);
            assertEquals(retired, reads.get(), "Old bound callback cannot even enter its supplier after replacement");
        } finally { f.runtime.close(); }
    }

    private GalleryRuntimeSelectionTest fixture() throws Exception {
        var f = new GalleryRuntimeSelectionTest(); f.directory = directory; f.setup();
        f.operations.useCpmPresence(() -> true);
        GallerySneakyPreparationJoinTest.change(f, f.operations.enableProvider(
                com.naocraftlab.skins.core.provider.AppearanceProviders.Component.CAPE,
                com.naocraftlab.skins.core.provider.BuiltinProvider.CPM));
        return f;
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"source", "world", "resource", "session", "screen", "reopen", "replacement", "reentrant"})
    void currentnessRetiresBodyAndAccessoriesWithoutReadingStaleSuppliers(String change) throws Exception {
        var f = fixture();
        try {
            var env = ClientRuntime.class.getDeclaredField("skinExtensionEnvironment"); env.setAccessible(true);
            env.set(f.runtime, new SkinExtensionEnvironment(0, Map.of(), true));
            var reads = new AtomicInteger();
            var reenter = new java.util.concurrent.atomic.AtomicBoolean();
            var initial = main(f);
            var live = new PreviewAccessories(new UUID(0, 52), 1, PreviewAccessories.Availability.CONFIRMED,
                    true, true, PreviewRenderer.LiveReason.AVAILABLE);
            var accessory = f.runtime.observePreviewAccessories(initial, () -> { reads.incrementAndGet(); return live; });
            var preview = f.runtime.galleryPreviewCandidate().orElseThrow();
            assertNotEquals(f.runtime.previewBodySource(initial), f.runtime.previewBodySource(preview),
                    "Native accessory selection is a distinct exact body projection");
            var body = f.runtime.observePreviewBody(preview, () -> {
                reads.incrementAndGet();
                if (reenter.getAndSet(false)) f.selectB();
                return PreviewRenderer.DisplayKind.ORDINARY;
            });
            body.accept(PreviewRenderer.BodyStatus.ORDINARY);
            assertEquals(PreviewRenderer.DisplayKind.ORDINARY, draft(f).displayedBodyKind());
            assertTrue(main(f).nativeAccessories());
            switch (change) {
                case "source" -> f.selectB();
                case "world" -> f.runtime.importWorldChanged();
                case "resource" -> f.runtime.resourcesReloaded();
                case "session" -> f.identity.set(new GameSessionTokenSource.SessionIdentity(new UUID(0, 999), "Other"));
                case "screen" -> f.runtime.dispatchWidget("gallery.providers");
                case "reopen" -> { f.runtime.closeScreen(); f.runtime.reopen(); f.drain(); }
                case "replacement" -> {
                    f.runtime.observePreviewBody(preview, () -> PreviewRenderer.DisplayKind.UNCONFIRMED);
                    f.runtime.observePreviewAccessories(preview, () -> PreviewAccessories.unavailable(PreviewRenderer.LiveReason.LOADING));
                }
                case "reentrant" -> reenter.set(true);
                default -> fail(change);
            }
            int before = reads.get();
            body.accept(PreviewRenderer.BodyStatus.READY);
            accessory.accept(live);
            if (!change.equals("reentrant")) assertEquals(before, reads.get(), "Stale suppliers must not run");
            assertEquals(PreviewRenderer.DisplayKind.UNCONFIRMED, draft(f).displayedBodyKind());
            if (change.equals("source")) assertTrue(main(f).nativeAccessories(), "Retained drawing has no successor action authority");
            else if (change.equals("replacement")) {
                assertTrue(main(f).nativeAccessories(), "Same canonical input replacement preserves feature recipe, not live authority");
                assertFalse(f.view().widget("gallery.preview_mode").orElseThrow().enabled());
            } else if (!change.equals("screen")) assertFalse(main(f).nativeAccessories());
            assertTrue(f.runtime.snapshot().editor().isEmpty());
        } finally { f.runtime.close(); }
    }

    @Test void favoriteMetadataDoesNotReplaceSourceAndCurrentnessDoesNotReadStorage() throws Exception {
        var f = fixture();
        try {
            var preview = main(f);
            String source = f.runtime.previewBodySource(preview);
            var callback = f.runtime.observePreviewBody(preview, () -> PreviewRenderer.DisplayKind.ORDINARY);
            callback.accept(PreviewRenderer.BodyStatus.ORDINARY);
            f.runtime.dispatchWidget("gallery.preset." + GalleryRuntimeSelectionTest.A + ".more");
            assertTrue(f.view().widget("gallery.preset." + GalleryRuntimeSelectionTest.A + ".favorite").orElseThrow().enabled());
            int[] admitted = {0};
            f.beforeOperation = name -> { if (name.equals("setPresetFavorite")) admitted[0]++; };
            f.runtime.dispatchWidget("gallery.preset." + GalleryRuntimeSelectionTest.A + ".favorite");
            assertFalse(f.runtime.snapshot().busy());
            assertEquals(1, f.workers.size());
            f.drain();
            assertEquals(1, admitted[0], "Real favorite writer must be admitted");
            assertTrue(f.storage.loadOrCreateAccount(GalleryRuntimeSelectionTest.ACCOUNT).presets().stream()
                    .filter(p -> p.id().equals(GalleryRuntimeSelectionTest.A)).findFirst().orElseThrow().favorite());
            assertEquals(PreviewRenderer.DisplayKind.ORDINARY, draft(f).displayedBodyKind(), "Metadata write preserves actual source witness");
            assertEquals(source, f.runtime.previewBodySource(main(f)));
            f.beforeOperation = name -> {
                if (!f.workerLane) assertEquals("sessionIdentity", name, "Currentness must use ephemeral state, not storage getters");
            };
            callback.accept(PreviewRenderer.BodyStatus.READY);
            assertEquals(PreviewRenderer.DisplayKind.ORDINARY, draft(f).displayedBodyKind());
            assertEquals(source, f.runtime.previewBodySource(main(f)));
            f.beforeOperation = ignored -> {};
        } finally { f.runtime.close(); }
    }

    @Test void changedCapeElytraCapabilityRejectsOldBodyAndAccessoryCallbacks() throws Exception {
        var f = fixture();
        try {
            var env = ClientRuntime.class.getDeclaredField("skinExtensionEnvironment"); env.setAccessible(true);
            env.set(f.runtime, new SkinExtensionEnvironment(0, Map.of(), true));
            var preview = main(f);
            var seeded = f.storage.updateAccount(GalleryRuntimeSelectionTest.ACCOUNT, old -> new com.naocraftlab.skins.core.model.AccountState(
                    old.schemaVersion(), old.accountId(), old.skinAssets(), old.presets().stream().map(p ->
                    p.id().equals(GalleryRuntimeSelectionTest.A) ? p.withOfflineCape(new com.naocraftlab.skins.core.model.LocalCapeReference(
                            null, "a".repeat(64), true)) : p).toList(), old.updatedAt()));
            var stateField = ClientRuntime.class.getDeclaredField("state"); stateField.setAccessible(true);
            Object state = stateField.get(f.runtime);
            var accountField = state.getClass().getDeclaredField("account"); accountField.setAccessible(true);
            accountField.set(state, seeded);
            var publish = ClientRuntime.class.getDeclaredMethod("publish"); publish.setAccessible(true); publish.invoke(f.runtime);
            preview = main(f);
            var reads = new AtomicInteger();
            var body = f.runtime.observePreviewBody(preview, () -> { reads.incrementAndGet(); return PreviewRenderer.DisplayKind.ORDINARY; });
            var live = new PreviewAccessories(new UUID(0, 53), 1, PreviewAccessories.Availability.CONFIRMED,
                    true, true, PreviewRenderer.LiveReason.AVAILABLE);
            var accessory = f.runtime.observePreviewAccessories(preview, () -> { reads.incrementAndGet(); return live; });
            body.accept(PreviewRenderer.BodyStatus.ORDINARY);
            assertEquals(PreviewRenderer.DisplayKind.ORDINARY, draft(f).displayedBodyKind());
            f.storage.updateAccount(GalleryRuntimeSelectionTest.ACCOUNT, old -> new com.naocraftlab.skins.core.model.AccountState(
                    old.schemaVersion(), old.accountId(), old.skinAssets(), old.presets().stream().map(p ->
                    p.id().equals(GalleryRuntimeSelectionTest.A) ? p.withOfflineCape(new com.naocraftlab.skins.core.model.LocalCapeReference(
                            null, "a".repeat(64), false)) : p).toList(), old.updatedAt()));
            accountField.set(state, f.storage.loadOrCreateAccount(GalleryRuntimeSelectionTest.ACCOUNT));
            publish.invoke(f.runtime);
            assertEquals(preview, main(f), "Changed desired capability must not partially replace displayed composition");
            var current = f.runtime.galleryPreviewCandidate().orElseThrow();
            assertEquals(preview.bodySource(), current.bodySource());
            assertEquals(preview.skin(), current.skin());
            assertEquals(preview.capeId(), current.capeId());
            assertEquals(preview.outerLayerVisibility(), current.outerLayerVisibility());
            assertNotEquals(preview.capeHasElytra(), current.capeHasElytra());
            int before = reads.get();
            body.accept(PreviewRenderer.BodyStatus.READY);
            accessory.accept(live);
            assertEquals(before, reads.get(), "Changed current capability must reject old suppliers");
            assertEquals(PreviewRenderer.DisplayKind.UNCONFIRMED, draft(f).displayedBodyKind());
            assertFalse(main(f).nativeAccessories());
            GalleryInspectLayersTest.ordinary(f);
            assertEquals(current.capeHasElytra(), main(f).capeHasElytra());
        } finally { f.runtime.close(); }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"importAccountEpoch", "importWorldEpoch", "importResourceEpoch"})
    void retainedDisplayChecksCurrentEpochRatherThanOnlyCapturedSource(String epoch) throws Exception {
        var f = fixture();
        try {
            GalleryInspectLayersTest.ordinary(f);
            var shown = main(f);
            f.selectB();
            assertEquals(shown, main(f));
            var field = ClientRuntime.class.getDeclaredField(epoch);
            field.setAccessible(true);
            field.setLong(f.runtime, field.getLong(f.runtime) + 1);
            assertEquals(GalleryRuntimeSelectionTest.B, main(f).presetId().orElseThrow());
            assertEquals(PreviewRenderer.DisplayKind.UNCONFIRMED, draft(f).displayedBodyKind());
        } finally { f.runtime.close(); }
    }

    @Test void freshMetadataCannotOverrideDisabledCapeProviderAndReenableRestoresNativeWinner() throws Exception {
        var f = fixture();
        try {
            f.operations.useCpmPresence(() -> true);
            var env = ClientRuntime.class.getDeclaredField("skinExtensionEnvironment"); env.setAccessible(true);
            env.set(f.runtime, new SkinExtensionEnvironment(1, Map.of(), true));
            GallerySneakyPreparationJoinTest.change(f, f.operations.enableProvider(
                    com.naocraftlab.skins.core.provider.AppearanceProviders.Component.CAPE,
                    com.naocraftlab.skins.core.provider.BuiltinProvider.CPM));
            var live = new PreviewAccessories(new UUID(0, 91), 1, PreviewAccessories.Availability.CONFIRMED,
                    true, true, PreviewRenderer.LiveReason.AVAILABLE);
            var reads = new AtomicInteger();
            var old = f.runtime.observePreviewAccessories(main(f), () -> { reads.incrementAndGet(); return live; });
            assertTrue(main(f).nativeAccessories());
            String enabled = f.runtime.previewBodySource(main(f));
            GallerySneakyPreparationJoinTest.change(f, f.operations.disableProvider(
                    com.naocraftlab.skins.core.provider.AppearanceProviders.Component.CAPE,
                    com.naocraftlab.skins.core.provider.BuiltinProvider.CPM));
            assertFalse(f.runtime.snapshot().providers().cape().enabled(com.naocraftlab.skins.core.provider.BuiltinProvider.CPM));
            int before = reads.get(); old.accept(live);
            assertEquals(before, reads.get(), "Disabled input rejects old callback before its getter");
            var disabled = f.runtime.galleryPreviewCandidate().orElseThrow();
            assertFalse(disabled.nativeAccessories());
            String disabledKey = f.runtime.previewBodySource(disabled);
            assertNotEquals(enabled, disabledKey);
            f.runtime.observePreviewAccessories(disabled, () -> { reads.incrementAndGet(); return live; });
            assertTrue(reads.get() > before, "Fresh raw CPM knowledge remains legal with cape provider disabled");
            assertFalse(main(f).nativeAccessories(), "Fresh current metadata cannot re-enable default CPM cape");
            assertEquals(disabledKey, f.runtime.previewBodySource(f.runtime.galleryPreviewCandidate().orElseThrow()));
            assertTrue(f.view().widget("gallery.preview_mode").isEmpty());
            GallerySneakyPreparationJoinTest.change(f, f.operations.enableProvider(
                    com.naocraftlab.skins.core.provider.AppearanceProviders.Component.CAPE,
                    com.naocraftlab.skins.core.provider.BuiltinProvider.CPM));
            f.runtime.observePreviewAccessories(f.runtime.galleryPreviewCandidate().orElseThrow(), () -> live);
            assertTrue(main(f).nativeAccessories());
            assertTrue(f.view().widget("gallery.preview_mode").orElseThrow().enabled());
        } finally { f.runtime.close(); }
    }

    @Test void nativeWinnerTracksActualOrdinaryCapePriorityAndFallsBackWhenHigherProviderIsAbsent() throws Exception {
        var f = fixture();
        try {
            f.operations.useCpmPresence(() -> true);
            var env = ClientRuntime.class.getDeclaredField("skinExtensionEnvironment"); env.setAccessible(true);
            env.set(f.runtime, new SkinExtensionEnvironment(1, Map.of(), true));
            var component = com.naocraftlab.skins.core.provider.AppearanceProviders.Component.CAPE;
            var cpm = com.naocraftlab.skins.core.provider.BuiltinProvider.CPM;
            GallerySneakyPreparationJoinTest.change(f, f.operations.enableProvider(component, cpm));
            var live = new PreviewAccessories(new UUID(0, 92), 1, PreviewAccessories.Availability.CONFIRMED,
                    true, true, PreviewRenderer.LiveReason.AVAILABLE);
            f.runtime.observePreviewAccessories(main(f), () -> live);
            assertTrue(main(f).nativeAccessories(), "Lower CPM falls back when higher ordinary providers have no candidate");
            f.storage.updateAccount(GalleryRuntimeSelectionTest.ACCOUNT, old -> new com.naocraftlab.skins.core.model.AccountState(
                    old.schemaVersion(), old.accountId(), old.skinAssets(), old.presets().stream().map(p -> p.id().equals(GalleryRuntimeSelectionTest.A)
                    ? p.withOfflineCape(new com.naocraftlab.skins.core.model.LocalCapeReference(null, "b".repeat(64), false)) : p).toList(), old.updatedAt()));
            f.runtime.closeScreen(); f.runtime.reopen(); f.drain();
            f.runtime.observePreviewAccessories(main(f), () -> live);
            var ordinary = main(f);
            assertEquals(Optional.of("provider:cape:" + "b".repeat(64)), ordinary.capeId());
            assertFalse(ordinary.nativeAccessories());
            assertFalse(ordinary.capeHasElytra());
            while (f.runtime.snapshot().providers().cape().order().indexOf(cpm) > 0)
                GallerySneakyPreparationJoinTest.change(f, f.operations.moveProvider(component, cpm, -1));
            f.runtime.observePreviewAccessories(f.runtime.galleryPreviewCandidate().orElseThrow(), () -> live);
            assertTrue(main(f).nativeAccessories());
            assertTrue(main(f).capeId().isEmpty());
            assertNotEquals(f.runtime.previewBodySource(ordinary), f.runtime.previewBodySource(main(f)));
            GallerySneakyPreparationJoinTest.change(f, f.operations.moveProvider(component, cpm, 1));
            f.runtime.observePreviewAccessories(f.runtime.galleryPreviewCandidate().orElseThrow(), () -> live);
            assertEquals(ordinary.capeId(), main(f).capeId());
            assertFalse(main(f).nativeAccessories());
        } finally { f.runtime.close(); }
    }

    private static ViewSpec.Preview main(GalleryRuntimeSelectionTest f) {
        return f.view().previews().stream().filter(p -> p.id().equals("gallery.preview")).findFirst().orElseThrow();
    }
    private static GalleryPresenter.DraftInput draft(GalleryRuntimeSelectionTest f) throws Exception {
        var field = ClientRuntime.class.getDeclaredField("galleryFlow"); field.setAccessible(true);
        var method = GalleryFlow.class.getDeclaredMethod("draftInput"); method.setAccessible(true);
        return (GalleryPresenter.DraftInput) method.invoke(field.get(f.runtime));
    }
    private static PreviewRenderer<Object> renderer(EditorPreviewSession session) {
        return new PreviewRenderer<>() {
            public void render(Object context, PreviewRequest request) { throw new AssertionError("Component control, not native draw"); }
            public PreviewModelFrames.Body body() { return session.body(); }
        };
    }
}

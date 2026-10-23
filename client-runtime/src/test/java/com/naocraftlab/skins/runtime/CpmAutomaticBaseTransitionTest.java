package com.naocraftlab.skins.runtime;

import com.naocraftlab.skins.client.*;
import com.naocraftlab.skins.core.api.PublicSkinImageAdapter;
import com.naocraftlab.skins.core.importing.PublicSkinImageSource;
import com.naocraftlab.skins.core.importing.ExternalImportProbe;
import com.naocraftlab.skins.core.model.*;
import com.naocraftlab.skins.diagnostics.DiagnosticSinks;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

final class CpmAutomaticBaseTransitionTest {
    @Test
    void sourceMetadataSurvivesFileUrlDependencyBaseAndDistinctContinuations() throws Exception {
        Path directory = java.nio.file.Files.createTempDirectory(Path.of(
                System.getenv().getOrDefault("TMPDIR", System.getProperty("java.io.tmpdir"))), "cpm-source-name-");
        try {
            for (String format : List.of("cpmproject", "cpmmodel")) {
                String basename = "Лесная é 😀 + (V2)." + format;
                Path source = directory.resolve(basename);
                java.nio.file.Files.copy(Path.of(format.equals("cpmproject")
                        ? "../gradle/build-logic/src/cpmComposition/fixtures/import-feasibility/canonical.cpmproject"
                        : "../gradle/build-logic/src/cpmComposition/fixtures/model-conversion/classic-embedded.cpmmodel"), source);
                for (boolean url : List.of(false, true)) for (boolean automatic : List.of(false, true))
                    for (boolean dependency : List.of(false, true)) try (Fixture fixture = new Fixture()) {
                        fixture.selected = source;
                        fixture.publication = true;
                        fixture.publicationParts = 2;
                        fixture.automatic = automatic;
                        fixture.dependency = dependency;
                        String expected = url ? "URL+%20 😀." + format : basename;
                        if (url) {
                            fixture.runtime.dispatchText("add.url.input", "https://example.invalid/private/URL+%2520%20%F0%9F%98%80."
                                    + format + "?hidden=secret#fragment");
                            fixture.outer = fixture.runtime.snapshot().addSource().orElseThrow();
                            fixture.runtime.dispatchWidget("add.url.load");
                        } else fixture.startImport();
                        fixture.drain();
                        if (dependency) {
                            assertEquals(0, fixture.publications);
                            fixture.runtime.dispatchWidget(fixture.action("dependency", "confirm"));
                            fixture.drain();
                            assertEquals(1, fixture.dependencyAcquisitions);
                            fixture.dependency = false;
                        }
                        if (!automatic) {
                            assertEquals(0, fixture.publications);
                            fixture.selected = Path.of("../gradle/build-logic/src/cpmComposition/fixtures/model-conversion/classic-base.png");
                            fixture.runtime.dispatchWidget("add.tab.file");
                            fixture.runtime.dispatchWidget("add.file.choose");
                            fixture.drain();
                        }
                        assertEquals(List.of(expected, expected), fixture.publicationNames,
                                "url=" + url + " automatic=" + automatic + " dependency=" + dependency
                                        + " status=" + fixture.runtime.snapshot().status().key() + " preparations=" + fixture.preparations);
                        assertEquals(2, fixture.publications);
                        assertTrue(fixture.runtime.snapshot().editor().isPresent());
                        String display = fixture.runtime.snapshot().editor().orElseThrow().name();
                        assertEquals(url ? "Imported URL skin" : UntrustedDisplayName.fromFileName(basename, "unused"), display);
                    }
                java.nio.file.Files.delete(source);
            }
        } finally {
            try (var paths = java.nio.file.Files.list(directory)) {
                for (Path path : paths.toList()) java.nio.file.Files.delete(path);
            }
            java.nio.file.Files.delete(directory);
        }
    }

    @Test
    void latePreparationCannotUseOrReplaceNextImportsSourceName() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.publication = true;
            fixture.startImport(); fixture.workerStep(); fixture.clientStep(); fixture.workerStep();
            Runnable late = fixture.clients.remove();
            fixture.runtime.dispatchWidget("add.cancel"); fixture.drain();
            fixture.runtime.dispatchText("add.url.input", "https://example.invalid/new+😀.cpmmodel?secret=hidden");
            fixture.outer = fixture.runtime.snapshot().addSource().orElseThrow();
            fixture.runtime.dispatchWidget("add.url.load");
            late.run(); fixture.drain();
            assertEquals(List.of("new+😀.cpmmodel"), fixture.publicationNames);
            assertTrue(fixture.runtime.snapshot().editor().isPresent());
        }
    }

    @Test
    void submittedUrlFreezesDecodedBasenameWithoutQueryFragmentOrPlusConversion() throws Exception {
        String[][] vectors = {
                {"https://example.invalid/dir/%D0%9B%D0%B5%D1%81+%2520%20%F0%9F%98%80.cpmproject?private=secret#hidden", "Лес+%20 😀.cpmproject"},
                {"https://example.invalid/dir/encoded%2Fleaf%5Cforest_witch.cpmmodel?q=private", "forest_witch.cpmmodel"},
                {"https://example.invalid/?private=hidden#secret", "NCL-Skins-model"},
                {"https://example.invalid/%0D%0A%00%7F", "NCL-Skins-model"}
        };
        for (String[] vector : vectors) {
            try (Fixture fixture = new Fixture()) {
                fixture.publication = true;
                fixture.runtime.dispatchText("add.url.input", vector[0]);
                fixture.outer = fixture.runtime.snapshot().addSource().orElseThrow();
                fixture.runtime.dispatchWidget("add.url.load");
                fixture.drain();
                assertEquals(List.of(vector[1]), fixture.publicationNames);
                assertTrue(fixture.runtime.snapshot().editor().isPresent());
            }
        }
    }

    @Test
    void sourceFileBasenameReachesPublicationUpload() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.publication = true;
            var names = new ArrayList<String>();
            fixture.runtime.useCpmModelPublication(new CpmPastePublicationAdapter(new CpmPastePublicationTest.Sessions(),
                    (method, uri, headers, body, timeout, maximum) -> {
                        String response = switch (uri.getPath()) {
                            case "/api/connect" -> "{\"id\":\"synthetic\",\"key\":\"AQID\"}";
                            case "/api/session" -> "{}";
                            case "/api/list" -> "{\"files\":[],\"maxSize\":64,\"maxFiles\":1}";
                            case "/api/upload" -> {
                                names.add(headers.get("File-Name"));
                                yield "{\"id\":\"fixture-id\"}";
                            }
                            default -> throw new AssertionError(uri.getPath());
                        };
                        return new com.naocraftlab.skins.core.api.PublicHttpsImageFetcher.Response(200,
                                response.getBytes(java.nio.charset.StandardCharsets.UTF_8), Map.of());
                    }, (profile, token, serverId, timeout) -> 204, () -> 0L));
            fixture.startImport();
            fixture.drain();
            assertEquals(List.of("canonical.cpmproject"), names);
            assertTrue(fixture.runtime.snapshot().editor().isPresent());
        }
    }

    @Test
    void queuedPublicationExpiresOnPausedViewWithoutRunningWorkerAndLateJobCannotUnlockB() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.publication = true;
            fixture.startImport();
            fixture.readyPublication();
            Runnable old = fixture.workers.remove();
            fixture.nanos = java.time.Duration.ofSeconds(60).toNanos();
            fixture.runtime.view(240, 240, 0, 0);
            fixture.assertOuterRestored();
            assertEquals("nclskins.add.cpm_publication_timeout", fixture.runtime.snapshot().status().key());
            fixture.runtime.dispatchWidget("add.file.choose");
            fixture.clientLane = false;
            try { old.run(); } finally { fixture.clientLane = true; }
            while (!fixture.clients.isEmpty()) fixture.clientStep();
            assertTrue(fixture.runtime.snapshot().busy());
            assertEquals(0, fixture.publications);
            fixture.drain();
            assertTrue(fixture.runtime.snapshot().editor().orElseThrow().convertedCreation());
        }
    }

    @Test
    void confirmedUploadWithQueuedLinkExpiresAndExplicitSamePayloadReusesSettlement() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.publication = true;
            fixture.startImport();
            fixture.readyPublication();
            fixture.workerStep();
            while (!fixture.clients.isEmpty()) fixture.clientStep();
            assertEquals(1, fixture.publications);
            assertTrue(fixture.runtime.snapshot().busy());
            fixture.nanos = java.time.Duration.ofSeconds(60).toNanos();
            fixture.runtime.tick();
            fixture.assertOuterRestored();
            fixture.drain();
            assertEquals(0, fixture.links);
            fixture.startImport();
            fixture.workerStep();
            while (!fixture.clients.isEmpty()) fixture.clientStep();
            fixture.workerStep();
            while (!fixture.clients.isEmpty()) fixture.clientStep();
            assertTrue(fixture.runtime.snapshot().busy());
            assertFalse(fixture.runtime.view(854, 480, 0, 0).widget("add.cancel").orElseThrow().enabled(),
                    "Confirmed settlement recovery is still one bounded disabled local-preparation interval");
            fixture.drain();
            assertTrue(fixture.runtime.snapshot().editor().orElseThrow().convertedCreation());
            assertEquals(1, fixture.publications);
            assertEquals(1, fixture.links);
        }
    }

    @Test
    @org.junit.jupiter.api.Timeout(15)
    void expiryAndActualSendAdmissionChooseOneConsistentOutcome() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.publication = true;
            var paused = new java.util.concurrent.CountDownLatch(1);
            var release = new java.util.concurrent.CountDownLatch(1);
            var expiryRead = new java.util.concurrent.CountDownLatch(1);
            var freeze = new java.util.concurrent.atomic.AtomicBoolean(true);
            var insideSend = new java.util.concurrent.atomic.AtomicBoolean();
            var sent = new java.util.concurrent.atomic.AtomicBoolean();
            var nanos = new java.util.concurrent.atomic.AtomicLong();
            fixture.runtime.importClock(() -> {
                if (Thread.currentThread().getName().equals("cpm-send-checkpoint") && insideSend.get() && freeze.compareAndSet(true, false)) {
                    paused.countDown();
                    try { assertTrue(release.await(5, java.util.concurrent.TimeUnit.SECONDS)); }
                    catch (InterruptedException failure) { throw new AssertionError(failure); }
                    return 0;
                }
                long value = nanos.get();
                if (Thread.currentThread().getName().equals("cpm-expiry-client")) expiryRead.countDown();
                return value;
            });
            fixture.runtime.useCpmModelPublication((request, current) -> {
                insideSend.set(true);
                sent.set(((CpmModelPublication.Control) current).beginSend());
                return new CpmModelPublication.Result(CpmModelPublication.Status.UNCERTAIN,
                        CpmModelPublication.Reason.EXPIRED, Optional.empty());
            });
            fixture.startImport(); fixture.readyPublication();
            Thread worker = new Thread(fixture.workers.remove(), "cpm-send-checkpoint");
            var failure = new java.util.concurrent.atomic.AtomicReference<Throwable>();
            Thread client = new Thread(() -> {
                try { fixture.runtime.view(240, 240, 0, 0); }
                catch (Throwable problem) { failure.set(problem); }
            }, "cpm-expiry-client");
            try {
                worker.start();
                assertTrue(paused.await(5, java.util.concurrent.TimeUnit.SECONDS));
                nanos.set(java.time.Duration.ofSeconds(60).toNanos());
                client.start();
                assertTrue(expiryRead.await(5, java.util.concurrent.TimeUnit.SECONDS));
                long bound = System.nanoTime() + java.time.Duration.ofSeconds(5).toNanos();
                while (client.isAlive() && client.getState() != Thread.State.BLOCKED && System.nanoTime() < bound) Thread.onSpinWait();
            } finally {
                release.countDown();
                worker.join(5000); client.join(5000);
            }
            assertFalse(worker.isAlive()); assertFalse(client.isAlive()); assertNull(failure.get());
            assertTrue(sent.get(), "The in-progress send checkpoint was admitted before expiry acquired its terminal decision");
            assertEquals("nclskins.add.cpm_publication_uncertain", fixture.runtime.snapshot().status().key(),
                    "A checkpoint admitted before retirement cannot be classified as definitely not sent");
            fixture.drain();
        }
    }

    @Test
    void forcedRemovalRetiresPublicationButVoluntaryEscapeCannot() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.publication = true;
            fixture.startImport();
            fixture.readyPublication();
            fixture.runtime.escapePressed();
            assertTrue(fixture.runtime.snapshot().busy());
            fixture.runtime.closeScreen();
            fixture.drain();
            assertEquals(0, fixture.publications);
            assertTrue(fixture.runtime.snapshot().editor().isEmpty());
            assertTrue(fixture.runtime.snapshot().addSource().isEmpty());
            assertEquals(1, fixture.closes);
            assertEquals(1, fixture.publicationCloses);
        }
    }

    @Test
    void confirmedPublicationRestoresInitiatingFormAndBlocksVoluntaryExit() throws Exception {
        for (boolean automatic : List.of(false, true)) {
            try (Fixture fixture = new Fixture()) {
                fixture.automatic = automatic;
                fixture.publication = true;
                fixture.startImport();
                if (automatic) fixture.readyPublication(); else fixture.drain();
                if (!automatic) {
                    fixture.selected = Path.of("../gradle/build-logic/src/cpmComposition/fixtures/model-conversion/classic-base.png");
                    fixture.runtime.dispatchWidget("add.tab.file");
                    fixture.startImport();
                    fixture.readyPublication();
                }
                String confirm = "add.cpm.publication.confirm.1";
                var view = fixture.runtime.view(854, 480, 0, 0);
                assertTrue(view.widget("add.file.choose").isPresent());
                assertFalse(view.widget("add.cancel").orElseThrow().enabled());
                assertTrue(view.texts().stream().anyMatch(text ->
                        text.message().key().equals("nclskins.add.cpm_publication_loading")));
                assertTrue(view.widgets().stream().noneMatch(widget -> widget.id().startsWith("add.cpm.publication.")));
                fixture.runtime.dispatchWidget("add.cancel");
                assertTrue(fixture.runtime.snapshot().busy());
                fixture.runtime.dispatchWidget(confirm);
                fixture.drain();
                assertEquals(1, fixture.publications);
                assertTrue(fixture.runtime.snapshot().editor().orElseThrow().convertedCreation());
                fixture.runtime.dispatchWidget("editor.cancel");
                if (!automatic) fixture.runtime.dispatchWidget("add.cancel");
                fixture.assertOuterRestored();
            }
        }
    }

    @Test
    void confirmedPublicationLinkFailureRestoresTypedOuterImportWithoutResend() throws Exception {
        for (boolean automatic : List.of(true, false)) {
            for (var reason : List.of(CpmModelFileConversion.Reason.CONVERSION_FAILED,
                    CpmModelFileConversion.Reason.MODEL_UNSUPPORTED,
                    CpmModelFileConversion.Reason.INVALID_OUTPUT_PNG)) {
                try (Fixture fixture = new Fixture()) {
                    fixture.automatic = automatic;
                    fixture.publication = true;
                    fixture.linkFailure = reason;
                    fixture.startImport();
                    fixture.drain();
                    if (!automatic) {
                        fixture.selected = Path.of("../gradle/build-logic/src/cpmComposition/fixtures/model-conversion/classic-base.png");
                        fixture.runtime.dispatchWidget("add.tab.file");
                        fixture.startImport();
                        fixture.drain();
                    }
                    fixture.drain();
                    fixture.assertOuterRestored();
                    assertEquals(Optional.of(reason), fixture.runtime.snapshot().addSource().orElseThrow()
                            .importContext().failure());
                    String key = switch (reason) {
                        case CONVERSION_FAILED -> "nclskins.add_source.cpm.conversion_failed";
                        case MODEL_UNSUPPORTED -> "nclskins.add_source.cpm.model_unsupported";
                        case INVALID_OUTPUT_PNG -> "nclskins.add_source.cpm.invalid_output";
                        default -> throw new AssertionError(reason);
                    };
                    assertTrue(fixture.runtime.view(240, 240, 0, 0).texts().stream()
                            .anyMatch(text -> text.message().key().equals(key)));
                    assertEquals(1, fixture.publications);
                    assertEquals(1, fixture.publicationCloses);
                    fixture.automatic = true;
                    fixture.linkFailure = null;
                    fixture.selected = Path.of("../gradle/build-logic/src/cpmComposition/fixtures/import-feasibility/canonical.cpmproject");
                    fixture.startImport();
                    fixture.drain();
                    assertTrue(fixture.runtime.snapshot().editor().orElseThrow().convertedCreation());
                    assertEquals(1, fixture.publications, "Explicit same-wire import reuses confirmed settlement");
                }
            }
        }
    }

    @Test
    void removedFallbackActionDoesNotChangeRetainedCreation() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.startImport();
            fixture.drain();
            var before = fixture.runtime.snapshot().editor().orElseThrow();
            assertTrue(fixture.runtime.view(240, 240, 0, 0).widgets().stream()
                    .noneMatch(widget -> widget.id().equals("editor.cpm.fallback.choose")));
            fixture.runtime.dispatchWidget("editor.cpm.fallback.choose");
            assertEquals(before, fixture.runtime.snapshot().editor().orElseThrow());
            assertTrue(fixture.workers.isEmpty());
            assertEquals(1, fixture.preparations);
            assertEquals(1, fixture.importPickers);
            assertEquals(0, fixture.basePickers);
        }
    }

    @Test
    void automaticBaseKeepsImportViewUntilQueuedPreparationOpensEditor() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.startImport();
            fixture.workerStep();
            fixture.clientStep();
            assertEquals(0, fixture.preparations);
            assertTrue(fixture.runtime.snapshot().busy());
            assertTrue(fixture.runtime.snapshot().editor().isEmpty());
            fixture.assertOnlyOuterImportPublished();
            fixture.workerStep();
            assertEquals(1, fixture.preparations);
            fixture.assertOnlyOuterImportPublished();
            fixture.clientStep();
            assertTrue(fixture.runtime.snapshot().editor().orElseThrow().convertedCreation());
            assertEquals("preset_editor", fixture.views.get(fixture.views.size() - 1).screenId());
            assertTrue(fixture.frames.stream().filter(frame -> !frame.editor())
                    .allMatch(frame -> frame.tab() == AddSourceTab.FILE
                            && frame.purpose() == AddSourceModel.Purpose.CREATE_PRESET), fixture.frames.toString());
            fixture.drain();
            fixture.runtime.dispatchWidget("editor.cancel");
            fixture.assertOuterRestored();
        }
    }

    @Test
    void automaticModelUsesTheSameUninterruptedTransition() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.selected = Path.of("../gradle/build-logic/src/cpmComposition/fixtures/model-conversion/classic-embedded.cpmmodel");
            fixture.startImport();
            fixture.workerStep();
            fixture.clientStep();
            fixture.assertOnlyOuterImportPublished();
            fixture.drain();
            assertTrue(fixture.runtime.snapshot().editor().orElseThrow().convertedCreation());
            assertTrue(fixture.frames.stream().noneMatch(frame -> frame.purpose() == AddSourceModel.Purpose.CPM_BASE));
        }
    }

    @Test
    void missingBasePublishesLockedChooserAndEditorCancelReturnsToIt() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.automatic = false;
            fixture.startImport();
            fixture.drain();
            var chooser = fixture.runtime.snapshot().addSource().orElseThrow();
            assertEquals(AddSourceModel.Purpose.CPM_BASE, chooser.importContext().purpose());
            assertEquals(AddSourceTab.CATALOG, chooser.selectedTab());
            assertEquals(AddSourceModel.CatalogFilter.CLASSIC, chooser.filter());
            assertFalse(fixture.runtime.view(854, 480, 0, 0).widget("add.catalog.filter").orElseThrow().enabled());
            assertEquals(0, fixture.preparations);
            fixture.selected = Path.of("../gradle/build-logic/src/cpmComposition/fixtures/model-conversion/classic-base.png");
            fixture.runtime.dispatchWidget("add.tab.file");
            fixture.startImport();
            fixture.drain();
            assertTrue(fixture.runtime.snapshot().editor().isPresent(), fixture.runtime.snapshot().status().key());
            assertTrue(fixture.runtime.snapshot().editor().orElseThrow().convertedCreation());
            fixture.runtime.dispatchWidget("editor.cancel");
            assertEquals(AddSourceModel.Purpose.CPM_BASE,
                    fixture.runtime.snapshot().addSource().orElseThrow().importContext().purpose());
            fixture.runtime.dispatchWidget("add.cancel");
            fixture.assertOuterRestored();
            fixture.drain();
            assertEquals(1, fixture.closes);
        }
    }

    @Test
    void cancellingAutomaticPreparationRejectsQueuedAndLateCompletion() throws Exception {
        for (boolean prepared : List.of(false, true)) {
            try (Fixture fixture = new Fixture()) {
                fixture.startImport();
                fixture.workerStep();
                fixture.clientStep();
                fixture.assertOnlyOuterImportPublished();
                if (prepared) fixture.workerStep();
                fixture.runtime.dispatchWidget("add.cancel");
                fixture.assertOuterRestored();
                fixture.drain();
                assertEquals(prepared ? 1 : 0, fixture.preparations);
                assertEquals(1, fixture.closes);
                assertTrue(fixture.runtime.snapshot().editor().isEmpty());
                assertTrue(fixture.frames.stream().noneMatch(Frame::editor));
                fixture.assertOuterRestored();
            }
        }
    }

    @Test
    void explicitImportAutomaticallyPublishesOuterBusyBeforeEnqueueUploadAndLocalLink() throws Exception {
        for (boolean direct : List.of(false, true)) {
            try (Fixture fixture = new Fixture()) {
                fixture.direct = direct;
                fixture.publication = true;
                fixture.startImport();
                fixture.drain();
                assertEquals(1, fixture.publications);
                assertEquals(1, fixture.links);
                assertTrue(fixture.publicationEnqueues >= 2);
                assertTrue(fixture.runtime.snapshot().editor().orElseThrow().convertedCreation());
                assertTrue(fixture.frames.stream().filter(frame -> !frame.editor()).allMatch(frame ->
                        frame.purpose() == AddSourceModel.Purpose.CREATE_PRESET && frame.tab() == AddSourceTab.FILE && frame.busy()));
                assertTrue(fixture.views.stream().noneMatch(view -> view.widgets().stream()
                        .anyMatch(widget -> widget.id().startsWith("add.cpm.publication."))));
                fixture.runtime.dispatchWidget("editor.cancel");
                fixture.assertOuterRestored();
                fixture.drain();
                assertEquals(1, fixture.publicationCloses);
            }
        }
    }

    @Test
    void genuineChooserIsIndependentOfOuterPublicationAndReturnsAfterEditorCancel() throws Exception {
        for (boolean direct : List.of(false, true)) for (String source : List.of("CATALOG", "FILE", "URL")) {
            try (Fixture fixture = new Fixture()) {
                fixture.automatic = false;
                fixture.publication = true;
                fixture.customizeOuter();
                double outerPosition = fixture.flow().addSourceScrollPosition();
                double outerTarget = fixture.flow().addSourceScrollTarget();
                fixture.direct = direct;
                fixture.startImport();
                fixture.drain();
                fixture.runtime.dispatchText("add.catalog.search", "Steve");
                fixture.runtime.dispatchWidget("add.tab.file");
                fixture.runtime.dispatchText("add.url.input", "https://example.invalid/base");
                fixture.runtime.dispatchText("add.player.input", "BasePlayer");
                if (source.equals("CATALOG")) fixture.runtime.dispatchWidget("add.tab.catalog");
                fixture.runtime.dispatchNavigation(ViewSpec.NavigationCommand.TAB_FORWARD,
                        source.equals("CATALOG") ? "add.catalog.search" : "add.player.input");
                var chooser = fixture.runtime.snapshot().addSource().orElseThrow();
                double chooserPosition = fixture.flow().addSourceScrollPosition();
                double chooserTarget = fixture.flow().addSourceScrollTarget();
                if (source.equals("CATALOG")) fixture.runtime.dispatchWidget("add.catalog.skin:minecraft:steve");
                if (source.equals("FILE")) {
                    fixture.selected = Path.of("../gradle/build-logic/src/cpmComposition/fixtures/model-conversion/classic-base.png");
                    fixture.startImport();
                }
                if (source.equals("URL")) fixture.runtime.dispatchWidget("add.url.load");
                fixture.drain();
                assertEquals(1, fixture.publications, source);
                assertTrue(fixture.runtime.snapshot().editor().orElseThrow().convertedCreation());
                fixture.runtime.dispatchWidget("editor.cancel");
                fixture.assertSameForm(chooser, fixture.runtime.snapshot().addSource().orElseThrow());
                assertEquals(chooserPosition, fixture.flow().addSourceScrollPosition());
                assertEquals(chooserTarget, fixture.flow().addSourceScrollTarget());
                assertEquals(AddSourceModel.Purpose.CPM_BASE, fixture.runtime.snapshot().addSource().orElseThrow().importContext().purpose());
                fixture.runtime.dispatchWidget("add.cancel");
                fixture.assertOuterRestored();
                assertEquals(outerPosition, fixture.flow().addSourceScrollPosition());
                assertEquals(outerTarget, fixture.flow().addSourceScrollTarget());
                assertTrue(fixture.views.stream().noneMatch(view -> view.widgets().stream()
                        .anyMatch(widget -> widget.id().startsWith("add.cpm.publication."))));
            }
        }
    }

    @Test
    void publicationRestoresOuterFocusAndFractionalScrollAndRejectsAllBusyInput() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.viewWidth = 240; fixture.viewHeight = 240;
            fixture.runtime.dispatchWidget("add.tab.catalog");
            fixture.runtime.dispatchWidget("add.catalog.filter");
            fixture.runtime.dispatchWidget("add.catalog.filter");
            fixture.runtime.dispatchText("add.catalog.search", "e");
            fixture.runtime.pointerScrolled(50, 140, 0, -0.4140625);
            assertTrue(fixture.flow().addSourceScrollPosition() > 0);
            fixture.runtime.dispatchWidget("add.tab.file");
            fixture.seedFocus("add.url.input");
            fixture.runtime.dispatchText("add.player.input", "OuterPlayer");
            fixture.drain();
            var outerFocus = fixture.runtime.view(240, 240, 0, 0).focusRequest().orElseThrow().widgetId();
            fixture.outer = fixture.runtime.snapshot().addSource().orElseThrow();
            double position = fixture.flow().addSourceScrollPosition();
            double target = fixture.flow().addSourceScrollTarget();
            fixture.publication = true;
            fixture.startImport(); fixture.readyPublication();
            assertEquals(outerFocus, fixture.runtime.view(240, 240, 0, 0).focusRequest().orElseThrow().widgetId());
            assertEquals(position, fixture.flow().addSourceScrollPosition());
            assertEquals(target, fixture.flow().addSourceScrollTarget());
            var before = fixture.runtime.snapshot();
            fixture.runtime.nativeScrollPositionChanged("add.import", 120);
            fixture.runtime.pointerScrolled(50, 100, 0, -1);
            fixture.runtime.dispatchNavigation(ViewSpec.NavigationCommand.TAB_FORWARD, "add.url.input");
            fixture.runtime.dispatchText("add.url.input", "changed");
            fixture.runtime.dispatchWidget("add.tab.catalog");
            fixture.runtime.dispatchWidget("add.cancel");
            fixture.runtime.escapePressed();
            fixture.runtime.dispatchWidget("add.cpm.publication.confirm.1");
            fixture.runtime.dispatchWidget("add.cpm.publication.cancel.1");
            assertSame(before.addSource().orElseThrow(), fixture.runtime.snapshot().addSource().orElseThrow());
            assertEquals(before.status(), fixture.runtime.snapshot().status());
            assertEquals(before.generation(), fixture.runtime.snapshot().generation());
            assertTrue(fixture.runtime.snapshot().busy());
            assertEquals(position, fixture.flow().addSourceScrollPosition());
            assertEquals(target, fixture.flow().addSourceScrollTarget());
            fixture.drain();
            fixture.runtime.dispatchWidget("editor.cancel");
            fixture.assertOuterRestored();
        }
    }

    @Test
    void dependencyPermissionWaitIsSeparateFromAutomaticPublicationBudget() throws Exception {
        for (boolean automatic : List.of(false, true)) for (boolean confirm : List.of(false, true)) {
            try (Fixture fixture = new Fixture()) {
                fixture.dependency = true; fixture.publication = true; fixture.automatic = automatic;
                fixture.startImport(); fixture.drain();
                String accept = fixture.action("dependency", "confirm");
                String decline = fixture.action("dependency", "cancel");
                assertEquals(0, fixture.dependencyAcquisitions); assertEquals(0, fixture.publications);
                fixture.nanos = java.time.Duration.ofMinutes(10).toNanos();
                fixture.runtime.dispatchWidget(confirm ? accept : decline);
                fixture.drain();
                if (!confirm) {
                    fixture.runtime.dispatchWidget(accept); fixture.drain();
                    fixture.assertOuterRestored();
                    assertEquals(0, fixture.dependencyAcquisitions); assertEquals(0, fixture.publications);
                    continue;
                }
                if (!automatic) {
                    fixture.nanos += java.time.Duration.ofMinutes(10).toNanos();
                    fixture.runtime.dispatchWidget("add.tab.file");
                    fixture.selected = Path.of("../gradle/build-logic/src/cpmComposition/fixtures/model-conversion/classic-base.png");
                    fixture.dependency = false;
                    fixture.startImport(); fixture.drain();
                }
                assertEquals(1, fixture.dependencyAcquisitions); assertEquals(1, fixture.publications);
                assertTrue(fixture.runtime.snapshot().editor().orElseThrow().convertedCreation());
                fixture.runtime.dispatchWidget(accept); fixture.runtime.dispatchWidget(decline);
                assertTrue(fixture.runtime.snapshot().editor().isPresent());
                fixture.runtime.dispatchWidget("editor.cancel");
                if (!automatic) fixture.runtime.dispatchWidget("add.cancel");
                fixture.assertOuterRestored();
            }
        }
    }

    @Test
    void explicitUrlImportAuthorizesPublicationButTypingPickerOpenCancelAndStaleSelectionDoNot() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.publication = true;
            fixture.runtime.dispatchText("add.url.input", "https://example.invalid/outer");
            fixture.runtime.view(240, 240, 0, 0); fixture.runtime.tick();
            fixture.runtime.dispatchWidget("add.cpm.publication.confirm.1");
            assertEquals(0, fixture.publications); assertTrue(fixture.workers.isEmpty());
            fixture.picker = new CompletableFuture<>();
            fixture.runtime.dispatchWidget("add.file.choose");
            assertTrue(fixture.workers.isEmpty()); assertEquals(0, fixture.publications);
            fixture.picker.complete(Optional.empty());
            fixture.drain(); assertEquals(0, fixture.publications);
            fixture.runtime.dispatchWidget("add.url.load"); fixture.drain();
            assertEquals(1, fixture.publications);
            fixture.runtime.dispatchWidget("editor.cancel"); fixture.drain();
            fixture.picker = new CompletableFuture<>();
            fixture.runtime.dispatchWidget("add.file.choose");
            fixture.runtime.closeScreen();
            fixture.picker.complete(Optional.of(fixture.selected));
            fixture.drain();
            assertEquals(1, fixture.publications); assertTrue(fixture.runtime.snapshot().editor().isEmpty());
        }
    }

    @Test
    void dependencyConsentPrecedesAutomaticPreparationWithoutPublishingBaseChooser() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.dependency = true;
            fixture.startImport();
            fixture.drain();
            assertEquals(0, fixture.dependencyAcquisitions);
            assertEquals(0, fixture.preparations);
            fixture.runtime.dispatchWidget(fixture.action("dependency", "confirm"));
            fixture.workerStep();
            fixture.clientStep();
            assertEquals(1, fixture.dependencyAcquisitions);
            assertEquals(0, fixture.preparations);
            assertTrue(fixture.runtime.snapshot().busy());
            assertTrue(fixture.frames.stream().noneMatch(frame -> frame.purpose() == AddSourceModel.Purpose.CPM_BASE));
            assertTrue(fixture.views.stream().noneMatch(view -> view.widget("add.catalog.filter").isPresent()));
            fixture.drain();
            assertTrue(fixture.runtime.snapshot().editor().orElseThrow().convertedCreation());
            fixture.runtime.dispatchWidget("editor.cancel");
            fixture.assertOuterRestored();
        }
    }

    @Test
    void failedAutomaticPreparationRestoresOrdinaryImportAndReleasesModel() throws Exception {
        for (boolean dependency : List.of(false, true)) {
            try (Fixture fixture = new Fixture()) {
                fixture.dependency = dependency;
                fixture.failPreparation = true;
                fixture.startImport();
                fixture.workerStep();
                fixture.clientStep();
                String staleConfirm = null;
                String staleCancel = null;
                if (dependency) {
                    staleConfirm = fixture.action("dependency", "confirm");
                    staleCancel = fixture.action("dependency", "cancel");
                    fixture.runtime.dispatchWidget(staleConfirm);
                    fixture.workerStep();
                    fixture.clientStep();
                }
                assertTrue(fixture.runtime.snapshot().busy());
                fixture.workerStep();
                assertTrue(fixture.runtime.snapshot().busy());
                fixture.clientStep();
                fixture.assertFailureRestored(CpmModelFileConversion.Reason.CONVERSION_FAILED);
                fixture.drain();
                assertEquals(1, fixture.closes);
                assertEquals(0, fixture.publications);
                if (dependency) {
                    fixture.runtime.dispatchWidget(staleConfirm);
                    fixture.runtime.dispatchWidget(staleCancel);
                    fixture.assertOuterRestored();
                    assertTrue(fixture.runtime.snapshot().editor().isEmpty());
                    assertTrue(fixture.workers.isEmpty());
                    assertTrue(fixture.clients.isEmpty());
                    assertEquals(1, fixture.dependencyAcquisitions);
                }
                fixture.dependency = false;
                fixture.failPreparation = false;
                fixture.selected = Path.of("../gradle/build-logic/src/cpmComposition/fixtures/model-conversion/classic-base.png");
                fixture.startImport();
                assertEquals(2, fixture.importPickers);
                assertEquals(0, fixture.basePickers);
                fixture.drain();
                assertTrue(fixture.runtime.snapshot().editor().isPresent());
                assertFalse(fixture.runtime.snapshot().editor().orElseThrow().convertedCreation());
                assertEquals(1, fixture.preparations);
                fixture.runtime.dispatchWidget("editor.cancel");
                fixture.assertOuterRestored();
            }
        }
    }

    @Test
    void failedAutomaticCarrierEncodingRestoresOrdinaryImport() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.startImport();
            fixture.workerStep();
            fixture.clientStep();
            var registry = javax.imageio.spi.IIORegistry.getDefaultInstance();
            var writers = new ArrayList<javax.imageio.spi.ImageWriterSpi>();
            registry.getServiceProviders(javax.imageio.spi.ImageWriterSpi.class, true).forEachRemaining(provider -> {
                if (Arrays.stream(provider.getFormatNames()).anyMatch("png"::equalsIgnoreCase)) writers.add(provider);
            });
            assertFalse(writers.isEmpty());
            try {
                writers.forEach(registry::deregisterServiceProvider);
                fixture.workerStep();
            } finally {
                writers.forEach(registry::registerServiceProvider);
            }
            assertTrue(fixture.runtime.snapshot().busy());
            fixture.clientStep();
            fixture.assertFailureRestored(CpmModelFileConversion.Reason.INVALID_OUTPUT_PNG);
            fixture.drain();
            assertEquals(1, fixture.closes);
        }
    }

    @Test
    void confirmedPublicationEncodingFailureAndExpiryRetireOuterWithoutResend() throws Exception {
        for (boolean expire : List.of(false, true)) {
            try (Fixture fixture = new Fixture()) {
                fixture.publication = true;
                fixture.startImport(); fixture.readyPublication();
                fixture.workerStep();
                while (!fixture.clients.isEmpty()) fixture.clientStep();
                assertEquals(1, fixture.publications);
                var registry = javax.imageio.spi.IIORegistry.getDefaultInstance();
                var providers = new ArrayList<javax.imageio.spi.ImageWriterSpi>();
                registry.getServiceProviders(javax.imageio.spi.ImageWriterSpi.class, true).forEachRemaining(provider -> {
                    if (Arrays.stream(provider.getFormatNames()).anyMatch("png"::equalsIgnoreCase)) providers.add(provider);
                });
                var writes = new java.util.concurrent.atomic.AtomicInteger();
                var intercepted = new EncodingBoundary(providers.get(0), () -> {
                    writes.incrementAndGet();
                    if (expire) fixture.nanos = java.time.Duration.ofSeconds(60).toNanos();
                    else throw new java.io.IOException("Synthetic encoder failure");
                });
                registry.registerServiceProvider(intercepted);
                providers.forEach(provider -> registry.setOrdering(javax.imageio.spi.ImageWriterSpi.class, intercepted, provider));
                try { fixture.workerStep(); }
                finally { registry.deregisterServiceProvider(intercepted); }
                assertEquals(1, writes.get(), "Actual ImageIO write boundary reached");
                fixture.drain();
                fixture.assertOuterRestored();
                assertTrue(fixture.runtime.snapshot().editor().isEmpty());
                if (expire) assertEquals("nclskins.add.cpm_publication_timeout", fixture.runtime.snapshot().status().key());
                else assertEquals(Optional.of(CpmModelFileConversion.Reason.INVALID_OUTPUT_PNG),
                        fixture.runtime.snapshot().addSource().orElseThrow().importContext().failure());
                assertEquals(1, fixture.publicationCloses); assertEquals(1, fixture.closes);
                fixture.startImport(); fixture.drain();
                assertTrue(fixture.runtime.snapshot().editor().orElseThrow().convertedCreation());
                assertEquals(1, fixture.publications, "Confirmed upload is reused after local encoding recovery");
            }
        }
    }

    @FunctionalInterface
    private interface EncodingAction { void run() throws java.io.IOException; }

    private static final class EncodingBoundary extends javax.imageio.spi.ImageWriterSpi {
        private final javax.imageio.spi.ImageWriterSpi delegate;
        private final EncodingAction action;
        EncodingBoundary(javax.imageio.spi.ImageWriterSpi delegate, EncodingAction action) {
            super("NCL", "1", new String[]{"png"}, new String[]{"png"}, new String[]{"image/png"},
                    "com.naocraftlab.skins.runtime.CpmAutomaticBaseTransitionTest", new Class<?>[]{javax.imageio.stream.ImageOutputStream.class},
                    null, false, null, null, null, null, false, null, null, null, null);
            this.delegate = delegate; this.action = action;
        }
        public boolean canEncodeImage(javax.imageio.ImageTypeSpecifier type) { return delegate.canEncodeImage(type); }
        public String getDescription(Locale locale) { return "Synthetic encoding boundary"; }
        public javax.imageio.ImageWriter createWriterInstance(Object extension) {
            return new javax.imageio.ImageWriter(this) {
                public javax.imageio.metadata.IIOMetadata getDefaultStreamMetadata(javax.imageio.ImageWriteParam param) { return null; }
                public javax.imageio.metadata.IIOMetadata getDefaultImageMetadata(javax.imageio.ImageTypeSpecifier type, javax.imageio.ImageWriteParam param) { return null; }
                public javax.imageio.metadata.IIOMetadata convertStreamMetadata(javax.imageio.metadata.IIOMetadata metadata, javax.imageio.ImageWriteParam param) { return null; }
                public javax.imageio.metadata.IIOMetadata convertImageMetadata(javax.imageio.metadata.IIOMetadata metadata, javax.imageio.ImageTypeSpecifier type, javax.imageio.ImageWriteParam param) { return null; }
                public void write(javax.imageio.metadata.IIOMetadata metadata, javax.imageio.IIOImage image,
                        javax.imageio.ImageWriteParam param) throws java.io.IOException {
                    action.run();
                    var writer = delegate.createWriterInstance();
                    try { writer.setOutput(getOutput()); writer.write(metadata, image, null); }
                    finally { writer.dispose(); }
                }
            };
        }
    }

    @Test
    void recoveredExternalButtonsReachTheirActualHandlers() throws Exception {
        for (String category : List.of("launcher", "mod")) {
            try (Fixture fixture = new Fixture()) {
                fixture.failPreparation = true;
                fixture.startImport();
                fixture.drain();
                fixture.assertFailureRestored(CpmModelFileConversion.Reason.CONVERSION_FAILED);
                fixture.runtime.dispatchWidget("add.external." + category);
                assertTrue(fixture.runtime.snapshot().busy());
                fixture.drain();
                assertTrue(fixture.externalProbes > 0);
                assertEquals(UiMessage.info("nclskins.external_import." + category + "_title"),
                        fixture.runtime.view(854, 480, 0, 0).title());
                fixture.runtime.dispatchWidget("external.back");
                fixture.assertOuterRestored();
                assertEquals(1, fixture.closes);
            }
        }
    }

    @Test
    void missingBaseFailureRetainsScopedChooserForExplicitRetry() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.automatic = false;
            fixture.startImport();
            fixture.drain();
            fixture.selected = Path.of("../gradle/build-logic/src/cpmComposition/fixtures/model-conversion/classic-base.png");
            fixture.failPreparation = true;
            fixture.runtime.dispatchWidget("add.tab.file");
            fixture.startImport();
            fixture.drain();
            assertFalse(fixture.runtime.snapshot().busy());
            assertEquals(0, fixture.closes);
            assertEquals(AddSourceModel.Purpose.CPM_BASE,
                    fixture.runtime.snapshot().addSource().orElseThrow().importContext().purpose());
            assertFalse(fixture.runtime.view(854, 480, 0, 0).widget("add.external.mod").orElseThrow().enabled());
            fixture.failPreparation = false;
            fixture.startImport();
            fixture.drain();
            assertTrue(fixture.runtime.snapshot().editor().orElseThrow().convertedCreation());
            assertEquals(1, fixture.importPickers);
            assertEquals(2, fixture.basePickers);
            fixture.runtime.dispatchWidget("editor.cancel");
            fixture.runtime.dispatchWidget("add.cancel");
            fixture.assertOuterRestored();
            fixture.drain();
            assertEquals(1, fixture.closes);
        }
    }

    @Test
    void cancellingBeforeFailureCompletionCannotRestoreStaleError() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.failPreparation = true;
            fixture.startImport();
            fixture.workerStep();
            fixture.clientStep();
            fixture.workerStep();
            fixture.runtime.dispatchWidget("add.cancel");
            fixture.assertOuterRestored();
            var status = fixture.runtime.snapshot().status();
            fixture.drain();
            fixture.assertOuterRestored();
            assertEquals(status, fixture.runtime.snapshot().status());
            assertTrue(fixture.runtime.snapshot().addSource().orElseThrow().importContext().failure().isEmpty());
            assertEquals(1, fixture.closes);
        }
    }

    @Test
    void serverSizeRefusalIsVisibleWithoutEnablingUncertainPublicationRetry() throws Exception {
        assertPublicationRefusal(413, "{}", CpmModelPublication.Reason.SIZE_QUOTA,
                "nclskins.add.cpm_publication_size_quota");
        assertPublicationRefusal(200, "{\"error\":\"size\",\"errorMessage\":\"error.paste.fileTooBig\"}",
                CpmModelPublication.Reason.SIZE_QUOTA, "nclskins.add.cpm_publication_size_quota");
    }

    @Test
    void countQuotaPreflightReachesRawFeedbackThenCurrentMenuProjectionWithoutUpload() throws Exception {
        var labels = Map.of("button.cpm.open_editor", "Редактор CPM", "button.cpm.edit", "Редактировать",
                "button.cpm.edit.pastes", "Вставки");
        try (Fixture fixture = new Fixture(TextResolver.withCatalogTranslations(UiMessage::key,
                (key, fallback) -> labels.getOrDefault(key, fallback)))) {
            var effects = new ArrayList<String>();
            var results = new ArrayList<CpmModelPublication.Result>();
            GameSessionTokenSource sessions = new GameSessionTokenSource() {
                public SessionIdentity currentSession() { return TestFixtures.validSession().sessionIdentity(); }
                public <T, E extends Exception> T withAccessToken(TokenRequest<T, E> request) throws E {
                    return request.execute("synthetic-test-token");
                }
            };
            var adapter = new CpmPastePublicationAdapter(sessions, (method, uri, headers, bytes, timeout, maximum) -> {
                assertFalse(fixture.clientLane);
                effects.add(uri.getPath());
                String response = switch (uri.getPath()) {
                    case "/api/connect" -> "{\"id\":\"synthetic-session\",\"key\":\"AQID\"}";
                    case "/api/session" -> "{}";
                    case "/api/list" -> "{\"maxSize\":100,\"maxFiles\":1,\"files\":[{\"id\":\"synthetic-existing\",\"name\":\"synthetic\",\"time\":\"0\"}]}";
                    default -> throw new AssertionError("Count quota must stop before upload: " + uri.getPath());
                };
                return new com.naocraftlab.skins.core.api.PublicHttpsImageFetcher.Response(200,
                        response.getBytes(java.nio.charset.StandardCharsets.UTF_8), Map.of());
            }, (profile, token, serverId, timeout) -> 204, () -> 0L);
            fixture.runtime.useCpmModelPublication((request, current) -> {
                var result = adapter.publish(request, current); results.add(result); return result;
            });
            fixture.publication = true;
            fixture.startImport();
            fixture.drain();
            assertEquals(1, results.size());
            assertEquals(CpmModelPublication.Reason.COUNT_QUOTA, results.get(0).reason());
            var raw = fixture.runtime.snapshot().status();
            assertEquals("nclskins.add.cpm_publication_count_quota", raw.key());
            assertTrue(raw.arguments().isEmpty());
            var projected = fixture.runtime.view(180, 120, 0, 0).texts().stream()
                    .filter(text -> text.id().equals("add.import.status")).findFirst().orElseThrow().message();
            assertEquals(raw.key(), projected.key());
            assertEquals(List.of("Редактор CPM", "Редактировать", "Вставки"), projected.arguments());
            assertEquals(List.of("/api/connect", "/api/session", "/api/list"), effects);
            fixture.assertOuterRestored();
        }
    }

    @Test
    void unknownServerRefusalKeepsUncertaintyCopyAndCannotResend() throws Exception {
        assertPublicationRefusal(200, "{}", CpmModelPublication.Reason.PROTOCOL,
                "nclskins.add.cpm_publication_uncertain");
    }

    private void assertPublicationRefusal(int status, String body, CpmModelPublication.Reason reason,
            String message) throws Exception {
        try (Fixture fixture = new Fixture()) {
            var effects = new ArrayList<String>();
            var results = new ArrayList<CpmModelPublication.Result>();
            GameSessionTokenSource sessions = new GameSessionTokenSource() {
                public SessionIdentity currentSession() {
                    return new SessionIdentity(TestFixtures.ACCOUNT_ID, "Synthetic");
                }
                public <T, E extends Exception> T withAccessToken(TokenRequest<T, E> request) throws E {
                    return request.execute("synthetic-test-token");
                }
            };
            var adapter = new CpmPastePublicationAdapter(sessions, (method, uri, headers, bytes, timeout, maximum) -> {
                assertFalse(fixture.clientLane);
                effects.add(uri.getPath());
                String response = switch (uri.getPath()) {
                    case "/api/connect" -> "{\"id\":\"synthetic-session\",\"key\":\"AQID\"}";
                    case "/api/session" -> "{}";
                    case "/api/list" -> "{\"maxSize\":100,\"maxFiles\":10,\"files\":[]}";
                    case "/api/upload" -> body;
                    default -> throw new AssertionError(uri.getPath());
                };
                return new com.naocraftlab.skins.core.api.PublicHttpsImageFetcher.Response(
                        uri.getPath().equals("/api/upload") ? status : 200,
                        response.getBytes(java.nio.charset.StandardCharsets.UTF_8), Map.of());
            }, (profile, token, serverId, timeout) -> 204, () -> 0L);
            fixture.runtime.useCpmModelPublication((request, current) -> {
                var result = adapter.publish(request, current);
                results.add(result);
                return result;
            });
            fixture.publication = true;
            fixture.startImport();
            fixture.readyPublication();
            assertTrue(effects.isEmpty());
            String originalConfirm = "add.cpm.publication.confirm.1";
            fixture.runtime.dispatchWidget(originalConfirm);
            assertTrue(fixture.runtime.snapshot().busy());
            fixture.drain();
            assertEquals(1, results.size());
            assertEquals(CpmModelPublication.Status.UNCERTAIN, results.get(0).status());
            assertEquals(reason, results.get(0).reason());
            assertTrue(results.get(0).pasteId().isEmpty());
            assertFalse(fixture.runtime.snapshot().busy());
            assertTrue(fixture.runtime.snapshot().editor().isEmpty());
            assertEquals(message, fixture.runtime.snapshot().status().key());
            assertTrue(fixture.runtime.view(854, 480, 0, 0).texts().stream()
                    .anyMatch(text -> text.message().key().equals(message)));
            fixture.assertOuterRestored();
            assertTrue(fixture.runtime.view(854, 480, 0, 0).widgets().stream()
                    .noneMatch(widget -> widget.id().startsWith("add.cpm.publication.")));
            fixture.runtime.dispatchWidget(originalConfirm);
            fixture.drain();
            assertEquals(1, results.size());
            assertEquals(List.of("/api/connect", "/api/session", "/api/list", "/api/upload"), effects);
            fixture.assertOuterRestored();
            assertEquals(1, fixture.publicationCloses);
            assertEquals(1, fixture.closes);
        }
    }

    private record Frame(AddSourceTab tab, AddSourceModel.Purpose purpose, boolean busy, boolean editor) {}

    private static final class Fixture implements AutoCloseable {
        final ArrayDeque<Runnable> workers = new ArrayDeque<>();
        final ArrayDeque<Runnable> clients = new ArrayDeque<>();
        final List<ViewSpec> views = new ArrayList<>();
        final List<Frame> frames = new ArrayList<>();
        final List<ClientSnapshot> published = new ArrayList<>();
        final ClientRuntime runtime;
        AddSourceModel outer;
        int viewWidth = 854, viewHeight = 480;
        CompletableFuture<Optional<Path>> picker;
        final int[] pixels;
        boolean clientLane = true;
        boolean direct;
        int publicationEnqueues;
        int preparations;
        int closes;
        boolean failPreparation;
        CpmModelFileConversion.Reason linkFailure;
        int importPickers;
        int basePickers;
        int externalProbes;
        boolean automatic = true;
        boolean publication;
        int publicationParts = 1;
        boolean dependency;
        int publications;
        final List<String> publicationNames = new ArrayList<>();
        int publicationCloses;
        int dependencyAcquisitions;
        int links;
        long nanos;
        Path selected = Path.of("../gradle/build-logic/src/cpmComposition/fixtures/import-feasibility/canonical.cpmproject");
        final AccountState account = TestFixtures.account(0);
        final AccountUiPreferences preferences = AccountUiPreferences.defaults(TestFixtures.ACCOUNT_ID);

        Fixture() throws Exception { this(UiMessage::key); }

        Fixture(TextResolver text) throws Exception {
            var image = javax.imageio.ImageIO.read(Path.of("../core/src/test/resources/cpm/classic-a.png").toFile());
            pixels = image.getRGB(0, 0, 64, 64, null, 0, 64);
            CpmModelFileConversion converter = new CpmModelFileConversion() {
                public Availability availability() { return Availability.READY; }
                public AdmittedModel admitFile(Path path) { throw new AssertionError("Use frozen input"); }
                public AdmittedModel admit(java.io.InputStream input) {
                    assertFalse(clientLane);
                    assertInstanceOf(FrozenImportInput.Reader.class, input);
                    return new AdmittedModel() {
                        public Optional<SkinModel> requiredVariant() { return Optional.of(SkinModel.CLASSIC); }
                        public Optional<Raster> automaticBase(SkinModel preferred) {
                            assertFalse(clientLane);
                            return automatic ? Optional.of(new Raster(SkinModel.CLASSIC, pixels)) : Optional.empty();
                        }
                        public void close() { assertFalse(clientLane); closes++; }
                    };
                }
                public Preparation prepare(AdmittedModel model, Raster base, long revision) throws Failure {
                    assertFalse(clientLane);
                    preparations++;
                    if (failPreparation) throw new Failure(Reason.CONVERSION_FAILED);
                    if (publication) return publication(base, revision, 0);
                    return new Inline(new Raster(base.variant(), pixels));
                }
                private Publication publication(Raster base, long revision, int part) {
                    return new Publication() {
                        private boolean closed;
                        public SkinModel variant() { return base.variant(); }
                        public long revision() { return revision; }
                        public byte[] wire() { return new byte[]{1, 2, (byte) (3 + part)}; }
                        public Preparation link(String id) throws Failure {
                            assertFalse(clientLane);
                            assertPublishedBusy();
                            assertFalse(closed);
                            assertEquals("fixture-id", id);
                            links++;
                            if (linkFailure != null) throw new Failure(linkFailure);
                            if (part + 1 < publicationParts) return publication(base, revision, part + 1);
                            return new Inline(new Raster(base.variant(), pixels));
                        }
                        public void close() {
                            assertFalse(clientLane);
                            assertFalse(closed);
                            closed = true;
                            publicationCloses++;
                        }
                    };
                }
            };
            PublicSkinImageSource images = new PublicSkinImageSource() {
                public Acquired acquireFile(Path path) throws Exception { return PublicSkinImageAdapter.acquireLocal(path); }
                public Acquired acquireUrl(String url) { throw new AssertionError("No network"); }
                public com.naocraftlab.skins.core.png.NormalizedSkin loadUntrusted(String url) { throw new AssertionError("No network"); }
                public com.naocraftlab.skins.core.png.NormalizedSkin loadVerified(
                        com.naocraftlab.skins.core.importing.PublicProfileLookup.VerifiedTexture texture) { throw new AssertionError("No profile"); }
            };
            var imports = new PublicSkinImportService(Optional.empty(), images,
                    (collection, skin, variant) -> { throw new AssertionError("No catalog materialization"); });
            ClientOperations operations = (ClientOperations) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{TestCapeOperations.class}, (proxy, method, args) -> {
                        return switch (method.getName()) {
                            case "initialize" -> new ClientOperations.InitialData(account, TestFixtures.validSession(),
                                    Optional.empty(), Optional.empty(), Optional.empty(), false, List.of(), preferences);
                            case "sessionIdentity" -> TestFixtures.validSession().sessionIdentity();
                            case "setSelectedAddSourceTab", "setPreferredSkinVariant" -> preferences;
                            case "rateLimited" -> false;
                            case "probeExternalSource" -> {
                                externalProbes++;
                                yield ExternalImportProbe.UNAVAILABLE;
                            }
                            case "catalogCollections" -> MinecraftSkinCatalog.collections();
                            case "freezeCatalogSelection" -> Optional.empty();
                            case "loadCatalogSkin" -> java.nio.file.Files.readAllBytes(Path.of(
                                    "../gradle/build-logic/src/cpmComposition/fixtures/model-conversion/classic-base.png"));
                            case "loadUrlSkin", "loadPlayerSkin" -> new ImportOperations.ImportDraft("", SkinVariant.CLASSIC,
                                    java.nio.file.Files.readAllBytes(Path.of(
                                            "../gradle/build-logic/src/cpmComposition/fixtures/model-conversion/classic-base.png")), PersonalSkinSource.FILE);
                            case "catalogFeatureEvidence", "warmedCapePreviews" -> Map.of();
                            case "loadCapeEditorData" -> new CatalogRead.CapeEditorData(account, List.of(), Map.of(), 0);
                            case "capeCatalogGeneration" -> 0L;
                            case "warmedCapeEditorData" -> Optional.empty();
                            case "warmOwnedCapeCache", "warmResourceCapeCatalog", "warmCapeCatalog" -> null;
                            case "acquireFile", "acquireUrl" -> {
                                Path input = method.getName().equals("acquireFile") ? (Path) args[0] : selected;
                                if (dependency) yield new ImportOperations.AcquiredDependency() {
                                    public ImportOperations.AcquiredInput acquire(java.util.function.BooleanSupplier current) throws Exception {
                                        assertFalse(clientLane);
                                        assertTrue(current.getAsBoolean());
                                        dependencyAcquisitions++;
                                        return imports.acquireFile(input, Optional.of(converter),
                                                (CpmModelFileConversion.Reason) args[2]);
                                    }
                                    public void close() { assertFalse(clientLane); }
                                };
                                yield imports.acquireFile(input, Optional.of(converter),
                                        (CpmModelFileConversion.Reason) args[2]);
                            }
                            default -> {
                                if (method.isDefault()) yield InvocationHandler.invokeDefault(proxy, method, args);
                                throw new AssertionError("Unexpected operation: " + method.getName());
                            }
                        };
                    });
            ClientExecutor client = new ClientExecutor() {
                public boolean isClientThread() { return clientLane; }
                public void execute(Runnable action) {
                    Runnable admitted = () -> {
                        boolean previous = clientLane;
                        clientLane = true;
                        try { action.run(); } finally { clientLane = previous; }
                    };
                    if (direct) admitted.run(); else clients.add(admitted);
                }
            };
            runtime = new ClientRuntime(new DefaultRuntimeServices(), operations, client,
                    new FilePicker() {
                        public CompletableFuture<Optional<Path>> chooseSkinPng() {
                            basePickers++;
                            return CompletableFuture.completedFuture(Optional.of(selected));
                        }
                        public CompletableFuture<Optional<Path>> chooseSkinImportFile(boolean allowCpmModel) {
                            assertTrue(allowCpmModel);
                            importPickers++;
                            return picker != null ? picker : CompletableFuture.completedFuture(Optional.of(selected));
                        }
                    }, this::enqueueWorker,
                    text, Optional.empty(), Optional.empty(), DiagnosticSinks.discarding());
            runtime.useCpmModelPublication((request, current) -> {
                assertFalse(clientLane);
                assertPublishedBusy();
                assertTrue(current.getAsBoolean());
                assertTrue(((CpmModelPublication.Control) current).beginSend());
                publications++;
                publicationNames.add(request.name());
                return new CpmModelPublication.Result(CpmModelPublication.Status.CONFIRMED,
                        CpmModelPublication.Reason.NONE, Optional.of("fixture-id"));
            });
            runtime.importClock(() -> nanos);
            runtime.useSkinExtensionEnvironmentSource(new SkinExtensionEnvironmentSource() {
                public Snapshot snapshot() { return new Snapshot(1, Map.of(), true); }
                public Optional<CpmModelFileConversion> modelFileConversion() { return Optional.of(converter); }
            });
            runtime.reopen(ScreenDestination.SKIN_IMPORT);
            drain();
            runtime.dispatchText("add.url.input", "https://example.invalid/outer");
            outer = runtime.snapshot().addSource().orElseThrow();
            runtime.subscribe(snapshot -> {
                assertTrue(clientLane);
                if (snapshot.addSource().isEmpty()) return;
                published.add(snapshot);
                views.add(runtime.view(viewWidth, viewHeight, 0, 0));
                var add = snapshot.addSource().orElseThrow();
                frames.add(new Frame(add.selectedTab(), add.importContext().purpose(), snapshot.busy(), snapshot.editor().isPresent()));
            });
            views.clear();
            frames.clear();
            published.clear();
        }

        String action(String prompt, String action) {
            return runtime.view(854, 480, 0, 0).widgets().stream()
                    .filter(widget -> widget.id().startsWith("add.cpm." + prompt + "." + action + "."))
                    .findFirst().orElseThrow().id();
        }

        void startImport() {
            runtime.dispatchWidget("add.file.choose");
            while (!clients.isEmpty()) clientStep();
            if (!direct) assertEquals(1, workers.size());
        }

        void readyPublication() {
            int steps = 0;
            while (!runtime.snapshot().status().key().equals("nclskins.add.cpm_publication_loading")) {
                assertTrue(++steps < 10);
                workerStep();
                while (!clients.isEmpty()) clientStep();
            }
            assertPublishedBusy();
            assertTrue(runtime.snapshot().busy());
            assertFalse(workers.isEmpty());
        }

        CatalogImportFlow flow() throws Exception {
            var field = ClientRuntime.class.getDeclaredField("catalogImportFlow");
            field.setAccessible(true);
            return (CatalogImportFlow) field.get(runtime);
        }

        void seedFocus(String widget) throws Exception {
            var field = CatalogImportFlow.class.getDeclaredField("state");
            field.setAccessible(true);
            Object state = field.get(flow());
            var model = state.getClass().getDeclaredField("addSource");
            model.setAccessible(true);
            model.set(state, runtime.snapshot().addSource().orElseThrow().withRequestedFocus(widget));
        }

        void customizeOuter() throws Exception {
            viewWidth = 240; viewHeight = 240;
            runtime.dispatchWidget("add.tab.catalog");
            runtime.dispatchWidget("add.catalog.filter");
            runtime.dispatchWidget("add.catalog.filter");
            runtime.dispatchText("add.catalog.search", "e");
            runtime.pointerScrolled(50, 140, 0, -0.4140625);
            assertTrue(flow().addSourceScrollPosition() > 0);
            runtime.dispatchWidget("add.tab.file");
            seedFocus("add.url.input");
            runtime.dispatchText("add.player.input", "OuterPlayer");
            drain();
            outer = runtime.snapshot().addSource().orElseThrow();
        }

        void enqueueWorker(Runnable action) {
            if (runtime != null && runtime.snapshot().busy()
                    && runtime.snapshot().status().key().equals("nclskins.add.cpm_publication_loading")) {
                assertPublishedBusy();
                publicationEnqueues++;
            }
            if (direct) {
                boolean previous = clientLane;
                clientLane = false;
                try { action.run(); } finally { clientLane = previous; }
            } else workers.add(action);
        }

        void assertPublishedBusy() {
            assertFalse(published.isEmpty());
            var snapshot = published.get(published.size() - 1);
            var add = snapshot.addSource().orElseThrow();
            assertEquals(AddSourceModel.Purpose.CREATE_PRESET, add.importContext().purpose());
            assertEquals(AddSourceTab.FILE, add.selectedTab());
            assertTrue(add.importContext().requiredVariant().isEmpty());
            assertSameForm(outer, add);
            assertTrue(snapshot.busy());
            assertEquals("nclskins.add.cpm_publication_loading", snapshot.status().key());
            var view = views.get(views.size() - 1);
            assertEquals("nclskins.add_source.title", view.title().key());
            assertTrue(view.widget("add.catalog.filter").isEmpty());
            assertTrue(view.widgets().stream().noneMatch(ViewSpec.Widget::enabled));
            assertTrue(view.tabGroups().stream().flatMap(group -> group.tabs().stream()).noneMatch(ViewSpec.Tab::enabled));
            assertEquals(1, view.texts().stream().filter(text -> text.message().key()
                    .equals("nclskins.add.cpm_publication_loading")).count());
            assertTrue(view.widgets().stream().noneMatch(widget -> widget.id().startsWith("add.cpm.publication.")));
        }

        void assertOnlyOuterImportPublished() {
            assertFalse(views.isEmpty());
            assertTrue(views.stream().allMatch(view -> view.screenId().equals("add_source")), frames.toString());
            assertTrue(frames.stream().allMatch(frame -> frame.tab() == AddSourceTab.FILE
                    && frame.purpose() == AddSourceModel.Purpose.CREATE_PRESET && frame.busy() && !frame.editor()), frames.toString());
            assertTrue(views.stream().noneMatch(view -> view.widget("add.catalog.filter").isPresent()));
        }

        void assertFailureRestored(CpmModelFileConversion.Reason reason) {
            assertOuterRestored();
            var add = runtime.snapshot().addSource().orElseThrow();
            assertEquals(Optional.of(reason), add.importContext().failure());
            assertTrue(add.importContext().requiredVariant().isEmpty());
            assertTrue(runtime.snapshot().editor().isEmpty());
            assertTrue(frames.stream().noneMatch(frame -> frame.purpose() == AddSourceModel.Purpose.CPM_BASE));
            var view = runtime.view(854, 480, 0, 0);
            assertEquals("add_source", view.screenId());
            assertTrue(view.widget("add.file.choose").orElseThrow().enabled());
            assertTrue(view.widget("add.external.launcher").orElseThrow().enabled());
            assertTrue(view.widget("add.external.mod").orElseThrow().enabled());
            assertTrue(view.widgets().stream().noneMatch(widget -> widget.id().startsWith("add.cpm.publication.")
                    || widget.id().startsWith("add.cpm.dependency.")));
        }

        void assertOuterRestored() {
            var add = runtime.snapshot().addSource().orElseThrow();
            assertSameForm(outer, add);
            assertEquals(AddSourceModel.Purpose.CREATE_PRESET, add.importContext().purpose());
            assertEquals(AddSourceTab.FILE, add.selectedTab());
            assertFalse(runtime.snapshot().busy());
        }

        void assertSameForm(AddSourceModel expected, AddSourceModel actual) {
            assertEquals(expected.selectedTab(), actual.selectedTab());
            assertEquals(expected.query(), actual.query());
            assertEquals(expected.urlInput(), actual.urlInput());
            assertEquals(expected.playerInput(), actual.playerInput());
            assertEquals(expected.filter(), actual.filter());
            assertEquals(expected.preferredVariant(), actual.preferredVariant());
            assertEquals(expected.scrollOffset(), actual.scrollOffset());
            assertEquals(expected.focusWidgetId(), actual.focusWidgetId());
            assertEquals(expected.focusToken(), actual.focusToken());
        }

        void workerStep() {
            clientLane = false;
            try { workers.remove().run(); } finally { clientLane = true; }
        }

        void clientStep() { clients.remove().run(); }

        void drain() {
            int steps = 0;
            while (!workers.isEmpty() || !clients.isEmpty()) {
                assertTrue(++steps < 100);
                if (!workers.isEmpty()) workerStep();
                if (!clients.isEmpty()) clientStep();
            }
        }

        public void close() {
            runtime.close();
            drain();
        }
    }
}

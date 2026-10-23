package com.naocraftlab.skins.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class FilePickerCoordinatorTest {
    @Test
    void actualContentNotSuffixDeterminesImportPurpose(@TempDir Path directory) throws Exception {
        Path png = Files.copy(Path.of("../core/src/test/resources/cpm/classic-a.png"), directory.resolve("skin.cpmproject"));
        Path model = Files.copy(Path.of("../gradle/build-logic/src/cpmComposition/fixtures/model-conversion/classic-embedded.cpmmodel"), directory.resolve("model.png"));
        var picker = new FilePickerCoordinator(Runnable::run);
        assertEquals(Optional.of(png.toAbsolutePath()), picker.choose(() -> Optional.of(png)).join());
        assertEquals(Optional.of(model.toAbsolutePath()), picker.chooseSkinImport(() -> Optional.of(model)).join());
        var failure = org.junit.jupiter.api.Assertions.assertThrows(CompletionException.class,
                () -> picker.choose(() -> Optional.of(model)).join());
        assertInstanceOf(com.naocraftlab.skins.client.FilePicker.InvalidSelection.class, failure.getCause());
    }

    @Test
    void cancelledModelPickerConsumerDoesNotReleaseNativeDialogGuard(@TempDir Path directory) throws Exception {
        var picker=new FilePickerCoordinator(Runnable::run);
        var nativeResult=new CompletableFuture<Optional<Path>>();
        var selected=picker.chooseSkinImportAsync(()->nativeResult,Runnable::run);
        assertTrue(selected.cancel(false));
        assertTrue(picker.choose(Optional::empty).isCompletedExceptionally());
        Path model=Files.copy(Path.of("../gradle/build-logic/src/cpmComposition/fixtures/model-conversion/classic-embedded.cpmmodel"),directory.resolve("fixture.CPMMODEL"));
        nativeResult.complete(Optional.of(model));
        assertEquals(Optional.empty(),picker.choose(Optional::empty).join());
        assertEquals(Optional.of(model.toAbsolutePath()),picker.chooseSkinImport(()->Optional.of(model)).join());
        assertTrue(picker.choose(()->Optional.of(model)).isCompletedExceptionally());
        assertTrue(picker.chooseCape(()->Optional.of(model)).isCompletedExceptionally());
        Path project=Files.write(directory.resolve("fixture.cpmproject"),new byte[]{1});
        assertTrue(picker.chooseSkinImport(()->Optional.of(project)).isCompletedExceptionally());
    }

    @Test
    void asynchronousDialogStartsOnRequestedExecutorAndRetainsSingleDialogGuard(
            @TempDir Path directory) throws Exception {
        QueuedExecutor worker = new QueuedExecutor();
        QueuedExecutor clientExecutor = new QueuedExecutor();
        FilePickerCoordinator picker = new FilePickerCoordinator(worker);
        CompletableFuture<Optional<Path>> nativeResult = new CompletableFuture<>();

        var selected = picker.chooseDirectoryAsync(() -> nativeResult, clientExecutor);
        assertTrue(worker.tasks.isEmpty());
        assertEquals(1, clientExecutor.tasks.size());
        clientExecutor.runFirst();
        CompletionException concurrent = org.junit.jupiter.api.Assertions.assertThrows(
                CompletionException.class,
                () -> picker.chooseAsync(
                        () -> CompletableFuture.completedFuture(Optional.empty()),
                        clientExecutor).join());
        assertInstanceOf(IllegalStateException.class, concurrent.getCause());

        nativeResult.complete(Optional.of(directory));
        assertEquals(Optional.of(directory.toAbsolutePath().normalize()), selected.join());

        var cancelled = picker.chooseAsync(
                () -> CompletableFuture.completedFuture(Optional.empty()), clientExecutor);
        clientExecutor.runFirst();
        assertEquals(Optional.empty(), cancelled.join());
    }

    @Test
    void cancelAndRegularPngArePublishedOnTheWorker(@TempDir Path directory) throws Exception {
        QueuedExecutor worker = new QueuedExecutor();
        FilePickerCoordinator picker = new FilePickerCoordinator(worker);

        var cancelled = picker.choose(Optional::empty);
        assertEquals(1, worker.tasks.size());
        worker.runFirst();
        assertEquals(Optional.empty(), cancelled.join());

        Path png = Files.write(directory.resolve("skin.PNG"), new byte[] {1});
        var selected = picker.choose(() -> Optional.of(png));
        worker.runFirst();
        assertEquals(Optional.of(png.toAbsolutePath().normalize()), selected.join());
    }

    @Test
    void capePickerAcceptsJpegAndKeepsSkinPickerPngOnly(@TempDir Path directory) throws Exception {
        QueuedExecutor worker = new QueuedExecutor();
        FilePickerCoordinator picker = new FilePickerCoordinator(worker);
        Path jpeg = Files.write(directory.resolve("cape.JPEG"), new byte[] {1});

        var selected = picker.chooseCape(() -> Optional.of(jpeg));
        worker.runFirst();
        assertEquals(Optional.of(jpeg.toAbsolutePath().normalize()), selected.join());

        var skin = picker.choose(() -> Optional.of(jpeg));
        worker.runFirst();
        CompletionException failure = org.junit.jupiter.api.Assertions.assertThrows(
                CompletionException.class, skin::join);
        assertInstanceOf(IllegalStateException.class, failure.getCause());
        var cancelled = picker.chooseCape(Optional::empty);
        worker.runFirst();
        assertEquals(Optional.empty(), cancelled.join());
    }

    @Test
    void rejectsInvalidPathAndConcurrentDialog(@TempDir Path directory) throws Exception {
        QueuedExecutor worker = new QueuedExecutor();
        FilePickerCoordinator picker = new FilePickerCoordinator(worker);

        var first = picker.choose(Optional::empty);
        CompletionException concurrent = org.junit.jupiter.api.Assertions.assertThrows(
                CompletionException.class,
                () -> picker.choose(Optional::empty).join());
        assertInstanceOf(IllegalStateException.class, concurrent.getCause());
        worker.runFirst();
        assertEquals(Optional.empty(), first.join());

        Path text = Files.writeString(directory.resolve("not-a-skin.txt"), "x");
        var invalid = picker.choose(() -> Optional.of(text));
        worker.runFirst();
        CompletionException invalidFailure = org.junit.jupiter.api.Assertions.assertThrows(
                CompletionException.class, invalid::join);
        assertInstanceOf(IllegalStateException.class, invalidFailure.getCause());
        assertTrue(invalidFailure.getCause().getCause() instanceof IllegalArgumentException);
    }

    @Test
    void directorySelectionSharesTheSingleDialogGuard(@TempDir Path directory) {
        QueuedExecutor worker = new QueuedExecutor();
        QueuedExecutor clientExecutor = new QueuedExecutor();
        FilePickerCoordinator picker = new FilePickerCoordinator(worker);

        var selected = picker.chooseDirectory(() -> Optional.of(directory), clientExecutor);
        assertTrue(worker.tasks.isEmpty());
        assertEquals(1, clientExecutor.tasks.size());
        CompletionException concurrent = org.junit.jupiter.api.Assertions.assertThrows(
                CompletionException.class,
                () -> picker.choose(Optional::empty).join());
        assertInstanceOf(IllegalStateException.class, concurrent.getCause());
        clientExecutor.runFirst();
        assertEquals(Optional.of(directory.toAbsolutePath().normalize()), selected.join());

        Path missing = directory.resolve("missing");
        var invalid = picker.chooseDirectory(() -> Optional.of(missing));
        worker.runFirst();
        CompletionException invalidFailure = org.junit.jupiter.api.Assertions.assertThrows(
                CompletionException.class, invalid::join);
        assertInstanceOf(IllegalStateException.class, invalidFailure.getCause());
        assertTrue(invalidFailure.getCause().getMessage().contains("directory"));
    }

    @Test
    void sqliteSelectionAcceptsOnlyAppDatabase(@TempDir Path directory) throws Exception {
        QueuedExecutor worker = new QueuedExecutor();
        FilePickerCoordinator picker = new FilePickerCoordinator(worker);
        Path database = Files.write(directory.resolve("app.db"), new byte[]{1});

        var selected = picker.chooseSqliteDatabase(() -> Optional.of(database));
        worker.runFirst();
        assertEquals(Optional.of(database.toAbsolutePath().normalize()), selected.join());

        Path other = Files.write(directory.resolve("other.db"), new byte[]{1});
        var invalid = picker.chooseSqliteDatabase(() -> Optional.of(other));
        worker.runFirst();
        CompletionException failure = org.junit.jupiter.api.Assertions.assertThrows(
                CompletionException.class, invalid::join);
        assertInstanceOf(IllegalStateException.class, failure.getCause());
        assertTrue(failure.getCause().getCause() instanceof IllegalArgumentException);
    }

    @Test
    void rejectedModelSelectionIsSanitizedWithoutBecomingAcceptedPng(@TempDir Path directory) throws Exception {
        Path model = Files.copy(Path.of("../gradle/build-logic/src/cpmComposition/fixtures/model-conversion/classic-embedded.cpmmodel"),directory.resolve("private-name.CPMMODEL"));
        var picker = new FilePickerCoordinator(Runnable::run);
        for (boolean async : List.of(false, true)) {
            var selected = async ? picker.chooseAsync(
                    () -> CompletableFuture.completedFuture(Optional.of(model)), Runnable::run)
                    : picker.choose(() -> Optional.of(model));
            CompletionException failure = org.junit.jupiter.api.Assertions.assertThrows(CompletionException.class, selected::join);
            var rejected = assertInstanceOf(com.naocraftlab.skins.client.FilePicker.InvalidSelection.class, failure.getCause());
            assertEquals(com.naocraftlab.skins.client.FilePicker.InvalidSelection.Reason.MODEL_NOT_ALLOWED, rejected.reason());
            assertEquals("MODEL_NOT_ALLOWED", rejected.getMessage());
            assertEquals(null, rejected.getCause());
            assertEquals(0, rejected.getSuppressed().length);
            assertEquals(Optional.empty(), picker.choose(Optional::empty).join());
            assertEquals(Optional.of(model.toAbsolutePath()), picker.chooseSkinImport(() -> Optional.of(model)).join());
            assertTrue(picker.chooseCape(() -> Optional.of(model)).isCompletedExceptionally());
        }
    }

    @Test
    void nativeAndSchedulingFailuresRemainPickerFailuresAndReleaseGuard() {
        var picker = new FilePickerCoordinator(Runnable::run);
        for (boolean async : List.of(false, true)) {
            var nativeFailure = async ? picker.chooseAsync(() -> { throw new UnsatisfiedLinkError("synthetic"); }, Runnable::run)
                    : picker.choose(() -> { throw new UnsatisfiedLinkError("synthetic"); });
            CompletionException failure = org.junit.jupiter.api.Assertions.assertThrows(CompletionException.class, nativeFailure::join);
            assertInstanceOf(IllegalStateException.class, failure.getCause());
            assertInstanceOf(UnsatisfiedLinkError.class, failure.getCause().getCause());
            assertEquals(Optional.empty(), picker.choose(Optional::empty).join());
            Executor rejecting = command -> { throw new java.util.concurrent.RejectedExecutionException("synthetic"); };
            var scheduled = async ? picker.chooseAsync(() -> CompletableFuture.completedFuture(Optional.empty()), rejecting)
                    : new FilePickerCoordinator(rejecting).choose(Optional::empty);
            CompletionException rejected = org.junit.jupiter.api.Assertions.assertThrows(CompletionException.class, scheduled::join);
            assertInstanceOf(IllegalStateException.class, rejected.getCause());
            assertInstanceOf(java.util.concurrent.RejectedExecutionException.class, rejected.getCause().getCause());
            assertEquals(Optional.empty(), picker.choose(Optional::empty).join());
        }
    }

    private static final class QueuedExecutor implements Executor {
        private final List<Runnable> tasks = new ArrayList<>();

        @Override
        public void execute(Runnable command) {
            tasks.add(command);
        }

        private void runFirst() {
            tasks.remove(0).run();
        }
    }
}

package com.naocraftlab.skins.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class FilePickerCoordinatorTest {
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

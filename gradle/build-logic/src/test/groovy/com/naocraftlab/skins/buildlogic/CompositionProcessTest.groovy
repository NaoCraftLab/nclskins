package com.naocraftlab.skins.buildlogic

import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import static org.junit.jupiter.api.Assertions.*

class CompositionProcessTest {
    static class ControlledProcess extends Process {
        boolean alive = true
        boolean finishNormally
        boolean confirmStop = true
        boolean interruptWait
        boolean interruptStop
        int code
        int stops
        OutputStream getOutputStream() { new ByteArrayOutputStream() }
        InputStream getInputStream() { new ByteArrayInputStream(new byte[0]) }
        InputStream getErrorStream() { getInputStream() }
        int waitFor() { throw new AssertionError('Unbounded wait forbidden') }
        boolean waitFor(long amount, TimeUnit unit) {
            if (interruptWait && stops == 0) { interruptWait = false; throw new InterruptedException('initial') }
            if (interruptStop && stops > 0) { interruptStop = false; throw new InterruptedException('cleanup') }
            if (finishNormally || stops > 0 && confirmStop) alive = false
            !alive
        }
        int exitValue() { if (alive) throw new IllegalThreadStateException(); code }
        void destroy() { stops++ }
        Process destroyForcibly() { stops++; this }
        boolean isAlive() { alive }
    }

    @Test void normalAndNonzeroExitArePreserved() {
        [0, 7].each { code ->
            def process = new ControlledProcess(finishNormally: true, code: code)
            assertEquals(code, CompositionProcess.await(process, 100, 100))
            assertFalse(process.alive)
        }
    }

    @Test void timeoutStopsOnlyTheOwnedChild() {
        def process = new ControlledProcess()
        assertThrows(TimeoutException) { CompositionProcess.await(process, 1, 20) }
        assertFalse(process.alive)
        assertTrue(process.stops > 0)
    }

    @Test void interruptionRestoresFlagAfterConfirmedCleanup() {
        def process = new ControlledProcess(interruptWait: true, interruptStop: true)
        try {
            assertThrows(InterruptedException) { CompositionProcess.await(process, 100, 20) }
            assertFalse(process.alive)
            assertTrue(Thread.currentThread().isInterrupted())
        } finally { Thread.interrupted() }
    }

    @Test void unconfirmedCleanupCannotBecomePassOrReplacePrimaryFailure() {
        def process = new ControlledProcess(confirmStop: false)
        def failure = assertThrows(TimeoutException) { CompositionProcess.await(process, 1, 1) }
        assertEquals(1, failure.suppressed.length)
        assertTrue(failure.suppressed[0].message.contains('termination unconfirmed'))
    }

    @Test void realTinyJavaChildIsTerminatedAfterInterruptedWait() {
        File root = Files.createTempDirectory(new File(System.getProperty('java.io.tmpdir')).toPath(), 'composition-child-').toFile()
        File source = new File(root, 'OwnedChild.java')
        File ready = new File(root, 'ready')
        Process child
        try {
            source.text = 'class OwnedChild { public static void main(String[] args) throws Exception { java.nio.file.Files.writeString(java.nio.file.Path.of(args[0]), "READY"); Thread.sleep(60000); } }'
            child = new ProcessBuilder(new File(System.getProperty('java.home'), 'bin/java').path, source.path, ready.path)
                    .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
            awaitReady(child, ready, 10000)
            assertTrue(child.isAlive())
            Thread.currentThread().interrupt()
            assertThrows(InterruptedException) { CompositionProcess.await(child, 1000, 1000) }
            assertFalse(child.isAlive())
            assertTrue(Thread.currentThread().isInterrupted())
        } finally { cleanupChild(child, source, ready, root) }
    }

    @Test void earlyExitWithoutReadyIsRejectedBeforeInterruption() {
        File root = Files.createTempDirectory(new File(System.getProperty('java.io.tmpdir')).toPath(), 'composition-child-').toFile()
        File source = new File(root, 'OwnedChild.java')
        File ready = new File(root, 'ready')
        Process child
        try {
            source.text = 'class OwnedChild { public static void main(String[] args) {} }'
            child = new ProcessBuilder(new File(System.getProperty('java.home'), 'bin/java').path, source.path, ready.path)
                    .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
            assertTrue(child.waitFor(10000, TimeUnit.MILLISECONDS))
            assertEquals(0, child.exitValue())
            def failure = assertThrows(IllegalStateException) { awaitReady(child, ready, 10000) }
            assertEquals('Owned child exited before READY', failure.message)
            assertFalse(Thread.currentThread().isInterrupted())
        } finally { cleanupChild(child, source, ready, root) }
    }

    private static void awaitReady(Process child, File ready, long timeoutMillis) {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        while (System.nanoTime() < deadline) {
            if (!child.isAlive()) throw new IllegalStateException('Owned child exited before READY')
            if (ready.isFile()) {
                String marker = ready.withInputStream { InputStream input -> new String(input.readNBytes(6), 'US-ASCII') }
                if (marker == 'READY') return
            }
            Thread.sleep(10)
        }
        throw new TimeoutException('Owned child READY exceeded startup budget')
    }

    private static void cleanupChild(Process child, File source, File ready, File root) {
        Thread.interrupted()
        if (child != null) {
            if (child.isAlive()) child.destroyForcibly()
            assertTrue(child.waitFor(5000, TimeUnit.MILLISECONDS))
            assertFalse(child.isAlive())
            child.outputStream.close()
        }
        if (ready.exists()) assertTrue(ready.delete())
        if (source.exists()) assertTrue(source.delete())
        assertTrue(root.delete())
    }
}

package com.naocraftlab.skins.buildlogic

import org.junit.jupiter.api.Test

import java.nio.file.Files
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

import static org.junit.jupiter.api.Assertions.*

final class ReleaseProcessTest {
    @Test
    void streamsOutputBeforeExitAndTimesOutTheProcessTree() {
        def lines = new LinkedBlockingQueue<String>()
        def failure = new AtomicReference<Throwable>()
        Thread worker = new Thread({
            try {
                ReleaseProcess.run(new ProcessBuilder('sh', '-c', 'echo started; sleep 30 & echo $!; wait'),
                        'fixture', 1) { lines.add(it.toString()) }
            } catch (Throwable error) { failure.set(error) }
        })
        worker.start()
        assertEquals('[fixture] started', lines.poll(5, TimeUnit.SECONDS))
        String child = lines.poll(5, TimeUnit.SECONDS).split(' ').last()
        worker.join(6000)
        assertFalse(worker.alive)
        assertTrue(failure.get().message.contains('exceeded'))
        assertFalse(ProcessHandle.of(Long.parseLong(child)).map { it.isAlive() }.orElse(false))
    }

    @Test
    void interruptionCancelsChildProcessAndPreservesInterruptFlag() {
        def lines = new LinkedBlockingQueue<String>()
        def interrupted = new AtomicReference<Boolean>(false)
        Thread worker = new Thread({
            try {
                ReleaseProcess.run(new ProcessBuilder('sh', '-c', 'echo started; sleep 30'), 'fixture', 20) {
                    lines.add(it.toString())
                }
            } catch (IllegalStateException error) {
                interrupted.set(Thread.currentThread().isInterrupted())
            }
        })
        worker.start()
        assertNotNull(lines.poll(5, TimeUnit.SECONDS))
        worker.interrupt()
        worker.join(5000)
        assertFalse(worker.alive)
        assertTrue(interrupted.get())
    }

    @Test
    void planDigestRejectsChangesAndRoundTrips() {
        File directory = Files.createTempDirectory('release-plan-test-').toFile()
        try {
            Map plan = ReleasePlan.seal([schemaVersion: 1, sourceCommit: 'a' * 40,
                    tagCommit: 'b' * 40, components: [], buildTargetIds: ['fabric-26.3']])
            File file = new File(directory, 'plan.json')
            Files.writeString(file.toPath(), CatalogTools.json(plan))
            assertEquals(plan, ReleasePlan.load(file))
            plan.buildTargetIds = ['forge-1.20.1']
            Files.writeString(file.toPath(), CatalogTools.json(plan))
            assertThrows(IllegalStateException) { ReleasePlan.load(file) }
        } finally { directory.deleteDir() }
    }
}

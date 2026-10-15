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
        File directory = Files.createTempDirectory(
                new File(System.getenv('TMPDIR') ?: System.getProperty('java.io.tmpdir')).toPath(),
                'release-plan-test-').toFile()
        try {
            Map plan = ReleasePlan.seal([schemaVersion: 1, version: '1.4.0', sourceCommit: 'a' * 40,
                    tagCommit: 'b' * 40, serverState: [schemaVersion: 1, sealed: true,
                        currentVersion: '1.4.0', sourceCommit: 'b' * 40, activeVersion: '1.2.0'],
                    components: [], buildTargetIds: ['fabric-26.3']])
            File file = new File(directory, 'plan.json')
            Files.writeString(file.toPath(), CatalogTools.json(plan))
            assertEquals(plan, ReleasePlan.load(file))
            plan.buildTargetIds = ['forge-1.20.1']
            Files.writeString(file.toPath(), CatalogTools.json(plan))
            assertThrows(IllegalStateException) { ReleasePlan.load(file) }
        } finally { directory.deleteDir() }
    }

    @Test
    void pinnedPlanRejectsInvalidStateWithoutOrdinaryFallback() {
        File directory = Files.createTempDirectory(
                new File(System.getenv('TMPDIR') ?: System.getProperty('java.io.tmpdir')).toPath(),
                'release-plan-state-test-').toFile()
        try {
            Map valid = [schemaVersion: 1, version: '1.4.0', sourceCommit: 'a' * 40,
                    tagCommit: 'b' * 40, components: [], buildTargetIds: [],
                    serverState: [schemaVersion: 1, sealed: true, currentVersion: '1.4.0',
                                  sourceCommit: 'b' * 40, activeVersion: '1.2.0']]
            File file = new File(directory, 'plan.json')
            file.text = CatalogTools.json(ReleasePlan.seal(valid))
            assertEquals('1.2.0', ReleasePlan.load(file).serverState.activeVersion)
            List<Map> mutations = [[:], [activeVersion: null], [activeVersion: ''],
                    [activeVersion: ' '], [activeVersion: ' 1.2.0 '], [activeVersion: 120],
                    [activeVersion: 'v1.2.0'], [activeVersion: '1.2.0.1'],
                    [activeVersion: '01.2.0'], [activeVersion: '1.2.0-beta.0'],
                    [activeVersion: '1.2.0-rc.1'], [schemaVersion: 2], [sealed: false],
                    [currentVersion: '1.4.1'], [sourceCommit: 'c' * 40]]
            mutations.eachWithIndex { Map mutation, int index ->
                Map changed = CatalogTools.materialize(valid) as Map
                if (index == 0) changed.serverState.remove('activeVersion')
                else changed.serverState.putAll(mutation)
                file.text = CatalogTools.json(ReleasePlan.seal(changed))
                IllegalStateException error = assertThrows(IllegalStateException) { ReleasePlan.load(file) }
                assertTrue(error.message.contains('server plugin state'), error.message)
            }
            [null, [], 'invalid'].each { Object state ->
                Map changed = new LinkedHashMap(valid) + [serverState: state]
                file.text = CatalogTools.json(ReleasePlan.seal(changed))
                assertThrows(IllegalStateException) { ReleasePlan.load(file) }
            }
        } finally { directory.deleteDir() }
    }

    @Test
    void pinnedPlanRequiresAnExplicitReleaseVersionEvenWhenStateMatchesIt() {
        File directory = Files.createTempDirectory(
                new File(System.getenv('TMPDIR') ?: System.getProperty('java.io.tmpdir')).toPath(),
                'release-plan-version-test-').toFile()
        try {
            File file = new File(directory, 'plan.json')
            [null, '', 140, 'v1.4.0', '1.4.0.1', '01.4.0', '1.4.0-beta.0'].each { Object version ->
                Map invalid = [schemaVersion: 1, version: version, sourceCommit: 'a' * 40,
                        tagCommit: 'b' * 40, components: [], buildTargetIds: [],
                        serverState: [schemaVersion: 1, sealed: true, currentVersion: version,
                                      sourceCommit: 'b' * 40, activeVersion: '1.2.0']]
                file.text = CatalogTools.json(ReleasePlan.seal(invalid))
                assertThrows(IllegalStateException) { ReleasePlan.load(file) }
            }
        } finally { directory.deleteDir() }
    }
}

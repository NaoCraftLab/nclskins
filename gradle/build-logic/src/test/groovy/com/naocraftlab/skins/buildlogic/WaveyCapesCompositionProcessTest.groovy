package com.naocraftlab.skins.buildlogic

import groovy.json.JsonSlurper
import org.gradle.api.GradleException
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource

import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

import static org.junit.jupiter.api.Assertions.*

class WaveyCapesCompositionProcessTest {
    @TempDir File root

    static class OwnedProcess extends Process {
        boolean alive = true
        int stops
        int reaps
        int code
        Throwable waitFailure
        Throwable pidFailure
        Throwable cleanupFailure
        InterruptedException cleanupInterrupt
        boolean timeout
        List<Map> waits = []
        OutputStream getOutputStream() { new ByteArrayOutputStream() }
        InputStream getInputStream() { new ByteArrayInputStream(new byte[0]) }
        InputStream getErrorStream() { getInputStream() }
        int waitFor() { throw new AssertionError('Unbounded wait forbidden') }
        boolean waitFor(long amount, TimeUnit unit) {
            waits.add([amount: amount, unit: unit, stops: stops])
            if (stops == 0 && waitFailure != null) throw waitFailure
            if (stops == 0 && (amount == 0 || timeout)) return false
            if (stops > 0 && cleanupInterrupt != null) {
                def failure = cleanupInterrupt
                cleanupInterrupt = null
                throw failure
            }
            if (stops > 0 && cleanupFailure != null) throw cleanupFailure
            alive = false
            reaps++
            true
        }
        int exitValue() { if (alive) throw new IllegalThreadStateException(); code }
        void destroy() { stops++ }
        Process destroyForcibly() { stops++; this }
        boolean isAlive() { alive }
        long pid() { if (pidFailure != null) throw pidFailure; 424242L }
    }

    abstract static class ProcessTask extends VerifyWaveyCapesCompositionTask {
        OwnedProcess child = new OwnedProcess()
        Throwable startFailure
        Throwable hashFailure
        List<List<String>> launches = []
        Map<Integer, Throwable> saveFailures = [:]
        int saveCalls
        boolean consumeInterrupt
        List<String> results = []
        protected Process startCompositionProcess(List<String> argv, File work, File log) {
            launches.add(argv)
            if (startFailure != null) throw startFailure
            log.text = 'OWNED_PROCESS_READY'
            child
        }
        protected String compositionLogHash(File log) {
            if (hashFailure != null) throw hashFailure
            super.compositionLogHash(log)
        }
        protected void saveCompositionReceipt(File file, Map receipt) {
            saveCalls++
            results.add(receipt.result)
            if (consumeInterrupt) Thread.interrupted()
            def failure = saveFailures[saveCalls]
            if (failure != null) throw failure
            super.saveCompositionReceipt(file, receipt)
        }
    }

    static class ReceiptMap extends LinkedHashMap {
        Throwable writeFailure
        Throwable removeFailure
        Object put(Object key, Object value) {
            if (key == 'activeProcess' && writeFailure != null) throw writeFailure
            super.put(key, value)
        }
        Object remove(Object key) {
            if (key == 'activeProcess' && removeFailure != null) throw removeFailure
            super.remove(key)
        }
    }

    static class FailingCommands extends ArrayList {
        Throwable failure
        boolean add(Object value) { throw failure }
    }

    private ProcessTask task() {
        def task = ProjectBuilder.builder().withProjectDir(root).build().tasks.create('composition', ProcessTask)
        task.targetId.set('unit-only')
        task
    }

    @Test void initialReceiptWriteFailureStillStopsAndReapsOwnedChild() {
        def task = task()
        def unrelated = new OwnedProcess()
        def original = new IOException('initial receipt write')
        Map receipt = [commands: []]
        int writes = 0
        Closure save = { if (++writes == 1) throw original }
        def thrown = assertThrows(IOException) {
            task.runCompositionProcess(root, receipt, save, 'owned', ['unit-only', 'argument with spaces'])
        }
        assertSame(original, thrown)
        assertFalse(task.child.alive, 'Successfully started child escaped ownership cleanup')
        assertTrue(task.child.stops > 0)
        assertTrue(task.child.reaps > 0)
        assertTrue(task.child.waits.every { it.amount >= 0 && it.unit.toMillis(it.amount) <= 5000 })
        assertFalse(receipt.containsKey('activeProcess'))
        assertEquals([['unit-only', 'argument with spaces']], task.launches)
        assertTrue(unrelated.alive)
        assertEquals(0, unrelated.stops)
        assertTrue(unrelated.waits.empty)
    }

    @Test void awaitFailureSurvivesHashAndReceiptFailures() {
        def task = task()
        def original = new IOException('owned wait')
        def hash = new IOException('log hash')
        def saveFailure = new IOException('final receipt write')
        task.child.waitFailure = original
        task.hashFailure = hash
        int writes = 0
        Map receipt = [commands: []]
        def thrown = assertThrows(IOException) {
            task.runCompositionProcess(root, receipt, { if (++writes > 1) throw saveFailure }, 'owned', ['unit-only'])
        }
        assertSame(original, thrown, 'Reporting replaced the actual wait failure')
        assertTrue(thrown.suppressed.any { it.is(hash) })
        assertTrue(thrown.suppressed.any { it.is(saveFailure) })
        assertFalse(task.child.alive)
        assertTrue(task.child.reaps > 0)
        assertFalse(receipt.containsKey('activeProcess'))
    }

    @ParameterizedTest
    @CsvSource(['0,0,OWNED_PROCESS_READY,false', '7,7,OWNED_PROCESS_READY,false',
                '7,0,OWNED_PROCESS_READY,true', '0,0,MISSING,true', '7,7,MISSING,true'])
    void exitsAndMarkersRemainFunctionalAssertions(int code, int expected, String marker, boolean rejected) {
        def task = task()
        task.child.code = code
        Map receipt = [commands: []]
        Closure run = { task.runCompositionProcess(root, receipt, {}, 'owned', ['unit-only'], expected, marker) }
        if (rejected) assertThrows(GradleException) { run() }
        else run()
        assertFalse(task.child.alive)
        assertEquals(1, task.child.reaps)
        assertEquals(120000L, task.child.waits[0].amount)
        assertEquals(TimeUnit.MILLISECONDS, task.child.waits[0].unit)
        assertEquals(0, task.child.stops)
        assertEquals(code, receipt.commands[0].exit)
        assertEquals(expected, receipt.commands[0].expectedExit)
        assertEquals(marker, receipt.commands[0].marker)
        assertEquals(424242L, receipt.commands[0].pid)
        assertEquals(ProcessHandle.current().pid(), receipt.commands[0].parentPid)
        assertTrue(receipt.commands[0].reaped)
        assertTrue(receipt.commands[0].sha256 ==~ /[a-f0-9]{64}/)
        assertFalse(receipt.containsKey('activeProcess'))
    }

    @ParameterizedTest
    @ValueSource(strings = ['exit', 'marker'])
    void functionalFailureSurvivesReportingFailures(String stage) {
        def task = task()
        task.child.code = stage == 'exit' ? 7 : 0
        def hash = new IOException('hash')
        def saveFailure = new IOException('save')
        task.hashFailure = hash
        int writes = 0
        Map receipt = [commands: []]
        def thrown = assertThrows(GradleException) {
            task.runCompositionProcess(root, receipt, { if (++writes == 2) throw saveFailure }, 'owned', ['unit-only'], 0, stage == 'marker' ? 'MISSING' : null)
        }
        assertTrue(thrown.message.contains('owned failed'))
        assertTrue(thrown.suppressed.any { it.is(hash) })
        assertTrue(thrown.suppressed.any { it.is(saveFailure) })
        assertFalse(task.child.alive)
        assertFalse(receipt.containsKey('activeProcess'))
    }

    @ParameterizedTest
    @ValueSource(strings = ['pid', 'active-write', 'command-append', 'active-remove'])
    void bookkeepingFailuresCannotEscapeOwnedLifecycle(String stage) {
        def task = task()
        def original = new IOException(stage)
        def receipt = new ReceiptMap()
        receipt.put('commands', stage == 'command-append' ? new FailingCommands(failure: original) : [])
        if (stage == 'pid') task.child.pidFailure = original
        if (stage == 'active-write') receipt.setWriteFailure(original)
        if (stage == 'active-remove') receipt.setRemoveFailure(original)
        assertSame(original, assertThrows(IOException) {
            task.runCompositionProcess(root, receipt, {}, 'owned', ['unit-only'])
        })
        assertFalse(task.child.alive)
        assertEquals(1, task.child.reaps)
        if (stage in ['pid', 'active-write']) {
            assertTrue(task.child.stops > 0)
            assertTrue(task.child.waits.every { it.unit.toMillis(it.amount) <= 5000 })
        }
        if (stage != 'active-remove') assertFalse(receipt.containsKey('activeProcess'))
    }

    @ParameterizedTest
    @ValueSource(strings = ['hash', 'save'])
    void reportingFailureAfterFunctionalSuccessStillFails(String stage) {
        def task = task()
        def original = new IOException(stage)
        if (stage == 'hash') task.hashFailure = original
        int writes = 0
        Map receipt = [commands: []]
        assertSame(original, assertThrows(IOException) {
            task.runCompositionProcess(root, receipt, { if (++writes == 2 && stage == 'save') throw original }, 'owned', ['unit-only'])
        })
        assertFalse(task.child.alive)
        assertEquals(2, writes)
        assertFalse(receipt.containsKey('activeProcess'))
    }

    @Test void timeoutRetainsExecutionAndCleanupBudgets() {
        def task = task()
        task.child.timeout = true
        Map receipt = [commands: []]
        assertThrows(TimeoutException) { task.runCompositionProcess(root, receipt, {}, 'owned', ['unit-only']) }
        assertEquals(120000L, task.child.waits[0].amount)
        assertTrue(task.child.waits.drop(1).every { it.amount > 0 && it.unit.toMillis(it.amount) <= 5000 })
        assertTrue(task.child.stops > 0)
        assertFalse(task.child.alive)
        assertTrue(receipt.commands[0].reaped)
    }

    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    void unconfirmedCleanupRemainsSecondaryToOriginalFailure(boolean beforeAwait) {
        def task = task()
        def original = new IOException('primary')
        def cleanup = new IllegalStateException('owned cleanup unconfirmed')
        task.child.cleanupFailure = cleanup
        if (!beforeAwait) task.child.waitFailure = original
        int writes = 0
        Map receipt = [commands: []]
        def thrown = assertThrows(IOException) {
            task.runCompositionProcess(root, receipt, { if (++writes == 1 && beforeAwait) throw original }, 'owned', ['unit-only'])
        }
        assertSame(original, thrown)
        assertTrue(thrown.suppressed.any { it.is(cleanup) })
        assertFalse(thrown.suppressed.any { it instanceof TimeoutException })
        assertTrue(task.child.alive)
        assertFalse(receipt.commands[0].reaped)
        assertTrue(task.child.waits.drop(beforeAwait ? 0 : 1).every { it.unit.toMillis(it.amount) <= 5000 })
    }

    @Test void cleanupInterruptionSurvivesReceiptFailureAndFlagConsumingReporting() {
        def task = task()
        def original = new IOException('initial receipt write')
        def interrupt = new InterruptedException('owned cleanup')
        task.child.cleanupInterrupt = interrupt
        int writes = 0
        Map receipt = [commands: []]
        try {
            def thrown = assertThrows(IOException) {
                task.runCompositionProcess(root, receipt, { Thread.interrupted(); if (++writes == 1) throw original }, 'owned', ['unit-only'])
            }
            assertSame(original, thrown)
            assertTrue(thrown.suppressed.any { it.is(interrupt) })
            assertFalse(task.child.alive)
            assertTrue(Thread.currentThread().isInterrupted())
        } finally { Thread.interrupted() }
    }

    private ProcessTask actionTask() {
        def task = task()
        File tool = new File(root, 'unit-tool')
        tool.text = 'not executable; Process double only'
        File services = new File(root, 'services'); assertTrue(services.mkdir())
        task.mode.set('mesh')
        task.submissionPreview.set(false)
        task.javaRelease.set(17)
        task.compilerExecutable.set(tool)
        task.javaExecutable.set(tool)
        task.serviceResources.set(services)
        task.workDirectory.set(new File(root, 'work'))
        task
    }

    @ParameterizedTest
    @ValueSource(strings = ['start', 'initial-save', 'await', 'interrupt'])
    void actualTaskActionPreservesPrimaryThroughOuterFailSave(String stage) {
        def task = actionTask()
        Throwable original = stage == 'interrupt' ? new InterruptedException('owned wait') : new IOException(stage)
        def outer = new IOException('outer FAIL save')
        if (stage == 'start') {
            task.startFailure = original
            task.saveFailures[1] = outer
        } else {
            if (stage == 'initial-save') task.saveFailures[1] = original
            else task.child.waitFailure = original
            task.hashFailure = new IOException('hash')
            task.saveFailures[2] = new IOException('final command save')
            task.saveFailures[3] = outer
        }
        task.consumeInterrupt = true
        try {
            def thrown = assertThrows(original.class) { task.verifyComposition() }
            assertSame(original, thrown)
            assertTrue(thrown.suppressed.any { it.is(outer) })
            assertEquals('FAIL', task.results.last())
            assertEquals(1, task.launches.size())
            if (stage == 'start') {
                assertTrue(task.child.waits.empty)
                assertEquals(0, task.child.stops)
            } else {
                assertFalse(task.child.alive)
                assertTrue(task.child.reaps > 0)
                assertEquals(3, task.saveCalls)
            }
            assertEquals(stage == 'interrupt', Thread.currentThread().isInterrupted())
        } finally { Thread.interrupted() }
    }

    @Test void actualTaskActionWritesFailReceiptAfterConfirmedCleanup() {
        def task = actionTask()
        def original = new IOException('owned wait')
        task.child.waitFailure = original
        assertSame(original, assertThrows(IOException) { task.verifyComposition() })
        def receipt = new JsonSlurper().parse(new File(root, 'work/result.json'))
        assertEquals('FAIL', receipt.result)
        assertEquals(1, receipt.commands.size())
        assertTrue(receipt.commands[0].reaped)
        assertEquals(IOException.name, receipt.commands[0].failureClass)
        assertFalse(receipt.containsKey('activeProcess'))
        assertFalse(task.child.alive)
    }
}

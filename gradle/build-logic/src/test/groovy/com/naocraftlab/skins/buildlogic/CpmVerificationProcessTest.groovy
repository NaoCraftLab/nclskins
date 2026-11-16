package com.naocraftlab.skins.buildlogic

import groovy.json.JsonSlurper
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

import java.lang.reflect.InvocationTargetException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

import static org.junit.jupiter.api.Assertions.*

class CpmVerificationProcessTest {
    @TempDir File root

    static class OwnedProcess extends Process {
        boolean alive = true
        boolean timeout
        int code
        int stops
        int reaps
        Throwable waitFailure
        Throwable cleanupFailure
        InterruptedException cleanupInterrupt
        List<Map> waits = []
        OutputStream getOutputStream() { new ByteArrayOutputStream() }
        InputStream getInputStream() { new ByteArrayInputStream(new byte[0]) }
        InputStream getErrorStream() { inputStream }
        int waitFor() { throw new AssertionError('Unbounded wait forbidden') }
        boolean waitFor(long amount, TimeUnit unit) {
            waits.add([amount: amount, unit: unit, stops: stops])
            if (stops == 0 && waitFailure != null) throw waitFailure
            if (stops == 0 && timeout) return false
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
        long pid() { throw new AssertionError('PID bookkeeping must not precede ownership') }
    }

    static class Control {
        Process child = new OwnedProcess()
        Throwable startFailure
        String log = 'EXPECTED_ASSERTION'
        boolean directoryLog
        int launches
        Process start(List<String> command, File output) {
            launches++
            assertFalse(command.empty)
            if (startFailure != null) throw startFailure
            if (directoryLog) assertTrue(output.mkdir())
            else output.text = log
            child
        }
    }

    abstract static class MeasurementTask extends VerifyCpmNativeMeasurementTask {
        Control control = new Control()
        protected Process startCpmProcess(List<String> command, File output) { control.start(command, output) }
    }

    abstract static class IntegrationTask extends VerifyCpmIntegrationTask {
        Control control = new Control()
        protected Process startCpmProcess(List<String> command, File output) { control.start(command, output) }
    }

    private def task(String wrapper) {
        ProjectBuilder.builder().withProjectDir(root).build().tasks.create('owned',
                wrapper == 'measurement' ? MeasurementTask : IntegrationTask)
    }

    private void run(def task, String wrapper, String expected = null) {
        Class owner = wrapper == 'measurement' ? VerifyCpmNativeMeasurementTask : VerifyCpmIntegrationTask
        String name = wrapper == 'measurement' ? 'run' : wrapper == 'probe' ? 'runProbe' : 'runMutation'
        def method = owner.declaredMethods.find { it.name == name && it.parameterCount == (wrapper == 'measurement' ? 7 : wrapper == 'probe' ? 6 : 4) }
        assertNotNull(method)
        method.accessible = true
        Object[] args = wrapper == 'measurement' ? [root, 'owned', [], 'UnitOnly', [], [], expected] as Object[] :
                wrapper == 'probe' ? [root, 'owned', [], 'UnitOnly', [], []] as Object[] :
                [['unit-only'], new File(root, 'owned.log'), 'wings', 'EXPECTED_ASSERTION'] as Object[]
        try { method.invoke(task, args) }
        catch (InvocationTargetException failure) { throw failure.cause }
    }

    @ParameterizedTest(name = '{displayName} [{index}] {arguments}') @ValueSource(strings = ['measurement', 'probe', 'mutation'])
    void waitFailureStopsAndReapsOnlyOwnedChild(String wrapper) {
        def task = task(wrapper)
        def unrelated = new OwnedProcess()
        def original = new IOException('wait failure')
        task.control.child.waitFailure = original
        assertSame(original, assertThrows(IOException) { run(task, wrapper) })
        assertFalse(task.control.child.alive, 'Started child escaped wrapper cleanup')
        assertEquals(1, task.control.child.reaps)
        assertTrue(task.control.child.stops > 0)
        assertTrue(unrelated.alive)
        assertEquals(0, unrelated.stops)
        assertTrue(unrelated.waits.empty)
    }

    @ParameterizedTest(name = '{displayName} [{index}] {arguments}') @ValueSource(strings = ['measurement', 'probe', 'mutation'])
    void timeoutUsesBoundedCleanupAndCannotPass(String wrapper) {
        def task = task(wrapper)
        task.control.child.timeout = true
        assertThrows(TimeoutException) { run(task, wrapper) }
        assertFalse(task.control.child.alive)
        assertEquals(1, task.control.child.reaps)
        assertTrue(task.control.child.stops > 0)
        assertEquals(wrapper == 'measurement' ? 120000L : 60000L, task.control.child.waits[0].unit.toMillis(task.control.child.waits[0].amount))
        assertTrue(task.control.child.waits.size() > 1)
        assertTrue(task.control.child.waits.drop(1).every { it.amount > 0 && it.unit.toMillis(it.amount) <= 5000L })
    }

    @ParameterizedTest(name = '{displayName} [{index}] {arguments}') @ValueSource(strings = ['measurement', 'probe', 'mutation'])
    void interruptionPreservesIdentitySecondaryCleanupAndFlag(String wrapper) {
        def task = task(wrapper)
        def original = new InterruptedException('initial wait')
        def secondary = new InterruptedException('cleanup wait')
        task.control.child.waitFailure = original
        task.control.child.cleanupInterrupt = secondary
        try {
            def thrown = assertThrows(InterruptedException) { run(task, wrapper) }
            assertSame(original, thrown)
            assertTrue(thrown.suppressed.any { it.is(secondary) })
            assertFalse(task.control.child.alive)
            assertEquals(1, task.control.child.reaps)
            assertTrue(Thread.currentThread().isInterrupted())
        } finally { Thread.interrupted() }
    }

    @ParameterizedTest(name = '{displayName} [{index}] {arguments}') @ValueSource(strings = ['measurement', 'probe', 'mutation'])
    void unconfirmedTerminationFailsWithoutReplacingPrimary(String wrapper) {
        def task = task(wrapper)
        def original = new IOException('primary wait')
        def cleanup = new IllegalStateException('termination unconfirmed')
        task.control.child.waitFailure = original
        task.control.child.cleanupFailure = cleanup
        def thrown = assertThrows(IOException) { run(task, wrapper) }
        assertSame(original, thrown)
        assertTrue(thrown.suppressed.any { it.is(cleanup) })
        assertTrue(task.control.child.alive)
        assertEquals(0, task.control.child.reaps)
        assertTrue(task.control.child.stops > 0)
        assertTrue(task.control.child.waits.size() > 1)
        assertTrue(task.control.child.waits.drop(1).every { it.unit.toMillis(it.amount) <= 5000L })
        assertFalse(new File(root, 'owned-exit.json').exists())
    }

    @ParameterizedTest(name = '{displayName} [{index}] {arguments}') @ValueSource(strings = ['measurement', 'probe', 'mutation'])
    void startFailureDoesNotInventAChild(String wrapper) {
        def task = task(wrapper)
        def original = new IOException('start failure')
        task.control.startFailure = original
        assertSame(original, assertThrows(IOException) { run(task, wrapper) })
        assertEquals(1, task.control.launches)
        assertTrue(task.control.child.waits.empty)
        assertEquals(0, task.control.child.stops)
    }

    @ParameterizedTest(name = '{displayName} [{index}] {arguments}') @ValueSource(strings = ['measurement', 'probe', 'mutation'])
    void positiveExitAndNonzeroAssertionsRemainEffective(String wrapper) {
        def task = task(wrapper)
        task.control.child.code = wrapper == 'mutation' ? 7 : 0
        run(task, wrapper)
        assertFalse(task.control.child.alive)
        assertEquals(1, task.control.child.reaps)
        assertEquals(0, task.control.child.stops)
        if (wrapper == 'measurement') {
            def receipt = new JsonSlurper().parse(new File(root, 'owned-exit.json'))
            assertEquals(0, receipt.exit)
            assertTrue(receipt.completed)
        }
        new File(root, 'tmp-owned').delete()
        task.control.child = new OwnedProcess(code: wrapper == 'mutation' ? 0 : 7)
        def failure = assertThrows(IllegalStateException) { run(task, wrapper) }
        assertTrue(failure.message.contains(wrapper == 'measurement' ? 'stage failed' : wrapper == 'probe' ? 'Native CPM probe failed' : 'did not reject'))
        assertFalse(task.control.child.alive)
    }

    @ParameterizedTest(name = '{displayName} [{index}] {arguments}') @ValueSource(strings = ['valid', 'wrong-exit', 'missing', 'suppressed'])
    void measurementSensitivityRequiresExactAssertionAndCleanExit(String scenario) {
        def task = task('measurement')
        task.control.child.code = scenario == 'wrong-exit' ? 7 : 1
        task.control.log = scenario == 'missing' ? 'OTHER_ASSERTION' : scenario == 'suppressed' ? 'EXPECTED_ASSERTION\nSuppressed: teardown' : 'EXPECTED_ASSERTION'
        if (scenario == 'valid') run(task, 'measurement', 'EXPECTED_ASSERTION')
        else assertThrows(IllegalStateException) { run(task, 'measurement', 'EXPECTED_ASSERTION') }
        assertFalse(task.control.child.alive)
        assertEquals(1, task.control.child.reaps)
    }

    @ParameterizedTest(name = '{displayName} [{index}] {arguments}') @ValueSource(strings = ['wings', 'cloak'])
    void decoratedTaskDispatchesMutationThroughClosure(String boundary) {
        def task = task('mutation')
        String marker = boundary == 'wings' ? 'actual transformed wings binding' :
                'redirected cloak fall-through must emit ordinary geometry when CPM loses'
        task.control.child.code = 1
        task.control.log = marker
        File output = new File(root, 'owned.log')
        def dispatch = { runMutation(['unit-only'], output, boundary, marker) }
        dispatch.delegate = task
        dispatch.resolveStrategy = Closure.DELEGATE_ONLY
        dispatch.call()
        assertEquals(1, task.control.launches)
        assertEquals(1, task.control.child.reaps)
        assertFalse(task.control.child.alive)
        task.control.child = new OwnedProcess(code: 1)
        task.control.log = 'wrong component'
        assertThrows(IllegalStateException) { dispatch.call() }
        assertEquals(2, task.control.launches)
        assertEquals(1, task.control.child.reaps)
    }

    @ParameterizedTest(name = '{displayName} [{index}] {arguments}') @ValueSource(strings = ['valid', 'missing'])
    void mutationSensitivityRequiresItsOriginalMarker(String scenario) {
        def task = task('mutation')
        task.control.child.code = 7
        task.control.log = scenario == 'valid' ? 'EXPECTED_ASSERTION' : 'OTHER_ASSERTION'
        if (scenario == 'valid') run(task, 'mutation')
        else assertThrows(IllegalStateException) { run(task, 'mutation') }
        assertFalse(task.control.child.alive)
        assertEquals(1, task.control.child.reaps)
    }

    @ParameterizedTest(name = '{displayName} [{index}] {arguments}') @ValueSource(strings = ['measurement', 'probe', 'mutation'])
    void reportingFailureOccursOnlyAfterChildReaped(String wrapper) {
        def task = task(wrapper)
        if (wrapper == 'measurement') assertTrue(new File(root, 'owned-exit.json').mkdir())
        else task.control.directoryLog = true
        task.control.child.code = wrapper == 'mutation' ? 7 : 0
        assertThrows(IOException) { run(task, wrapper) }
        assertFalse(task.control.child.alive)
        assertEquals(1, task.control.child.reaps)
    }

    @ParameterizedTest(name = '{displayName} [{index}] {arguments}') @ValueSource(strings = ['measurement', 'probe', 'sensitivity'])
    void functionalFailureSurvivesSecondaryReportingFailure(String scenario) {
        String wrapper = scenario == 'probe' ? 'probe' : 'measurement'
        def task = task(wrapper)
        task.control.child.code = 7
        if (wrapper == 'measurement') assertTrue(new File(root, 'owned-exit.json').mkdir())
        else task.control.directoryLog = true
        def failure = assertThrows(IllegalStateException) {
            run(task, wrapper, scenario == 'sensitivity' ? 'EXPECTED_ASSERTION' : null)
        }
        assertTrue(failure.message.contains(scenario == 'sensitivity' ? 'sensitivity failed' : wrapper == 'measurement' ? 'stage failed' : 'Native CPM probe failed'))
        assertEquals(1, failure.suppressed.length)
        assertInstanceOf(IOException, failure.suppressed[0])
        assertFalse(task.control.child.alive)
        assertEquals(1, task.control.child.reaps)
    }

    @ParameterizedTest(name = '{displayName} [{index}] {arguments}') @ValueSource(strings = ['measurement', 'probe', 'mutation'])
    void realReadyChildIsDeadBeforeTestCleanupAfterWrapperInterruption(String wrapper) {
        def task = task(wrapper)
        File source = new File(root, 'OwnedCpmChild.java')
        File ready = new File(root, 'ready')
        source.text = 'class OwnedCpmChild { public static void main(String[] args) throws Exception { java.nio.file.Files.writeString(java.nio.file.Path.of(args[0]), "READY"); Thread.sleep(60000); } }'
        List<String> command = [new File(System.getProperty('java.home'), 'bin/java').path, source.path, ready.path]
        println('CPM_WRAPPER_CHILD_COMMAND ' + groovy.json.JsonOutput.toJson(command))
        Process child = new ProcessBuilder(command)
                .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
        task.control.child = child
        try {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            while (!ready.isFile() && child.isAlive() && System.nanoTime() < deadline) Thread.sleep(10)
            assertTrue(child.isAlive(), 'Owned child exited before READY')
            assertTrue(ready.isFile(), 'Owned child READY exceeded startup budget')
            assertEquals('READY', ready.text)
            println("CPM_WRAPPER_READY wrapper=${wrapper} pid=${child.pid()} alive=${child.isAlive()}")
            Thread.currentThread().interrupt()
            assertThrows(InterruptedException) { run(task, wrapper) }
            println("CPM_WRAPPER_SETTLED wrapper=${wrapper} pid=${child.pid()} alive=${child.isAlive()} interrupted=${Thread.currentThread().isInterrupted()}")
            assertFalse(child.isAlive(), 'Child must be dead before test cleanup')
            assertTrue(Thread.currentThread().isInterrupted())
        } finally {
            Thread.interrupted()
            if (child.isAlive()) child.destroyForcibly()
            assertTrue(child.waitFor(5000, TimeUnit.MILLISECONDS))
            assertFalse(child.isAlive())
            child.outputStream.close()
        }
    }
}

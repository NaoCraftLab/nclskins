package com.naocraftlab.skins.buildlogic

import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.jvm.toolchain.JavaToolchainService
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.opentest4j.AssertionFailedError
import org.objectweb.asm.ClassReader
import org.objectweb.asm.commons.ClassRemapper
import org.objectweb.asm.tree.ClassNode
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

import static org.junit.jupiter.api.Assertions.*

class WaveySubmissionFenceTest {
    @TempDir File temporary

    @Test
    void actualBindingsAndContractRejectFenceAndPreservationMutants() {
        def project = ProjectBuilder.builder().withProjectDir(temporary).build()
        project.pluginManager.apply('java')
        def tools = project.extensions.getByType(JavaToolchainService)
        File javac = tools.compilerFor { languageVersion.set(JavaLanguageVersion.of(21)) }.get().executablePath.asFile
        File java = tools.launcherFor { languageVersion.set(JavaLanguageVersion.of(21)) }.get().executablePath.asFile
        String cp = [ClassReader, ClassNode, ClassRemapper].collect {
            new File(it.protectionDomain.codeSource.location.toURI()).absolutePath
        }.unique().join(File.pathSeparator)
        File output = new File(temporary, 'classes'); output.mkdirs()
        List<String> sources = [
                'src/waveyLoaderComposition/terminal/java/com/naocraftlab/skins/buildlogic/terminal/ExtractionTerminalBindings.java',
                'src/waveyLoaderComposition/terminal/java/com/naocraftlab/skins/buildlogic/terminal/SubmissionPreviewBindings.java',
                'src/waveyLoaderComposition/contract/java/com/naocraftlab/skins/buildlogic/contract/SubmissionPreviewContract.java',
                'src/testFixtures/java/com/naocraftlab/skins/buildlogic/SubmissionFenceFixture.java'].collect { new File(it).absolutePath }
        run([javac.absolutePath, '-proc:none', '--release', '21', '-cp', cp, '-d', output.absolutePath] + sources, 'compile')
        run([java.absolutePath, '-Djava.awt.headless=true', '-cp', output.absolutePath + File.pathSeparator + cp,
             'com.naocraftlab.skins.buildlogic.SubmissionFenceFixture'], 'contract')
    }

    @Test void timeoutReapsOwnedJvmWithoutLosingFailure() { assertOwnedChildReaped('timeout') }
    @Test void interruptionReapsOwnedJvmWithoutLosingFailure() { assertOwnedChildReaped('interrupt') }

    @Test void normalAndNonzeroJvmExitsKeepTheirResults() {
        File source = new File(temporary, 'ExitChild.java')
        source.text = 'class ExitChild { public static void main(String[] args) { System.out.print("exit-marker"); System.exit(Integer.parseInt(args[0])); } }'
        File java = new File(System.getProperty('java.home'), 'bin/java')
        run([java.absolutePath, source.absolutePath, '0'], 'zero', 10000)
        def failure = assertThrows(AssertionFailedError) { run([java.absolutePath, source.absolutePath, '7'], 'nonzero', 10000) }
        assertTrue(failure.message.contains('exit-marker'))
        assertEquals('7', failure.actual.value.toString())
    }

    private void assertOwnedChildReaped(String mode) {
            File source = new File(temporary, 'OwnedChild.java')
            File ready = new File(temporary, mode + '.ready')
            source.text = '''class OwnedChild {
                public static void main(String[] args) throws Exception {
                    java.nio.file.Path ready = java.nio.file.Path.of(args[0]);
                    java.nio.file.Path pending = ready.resolveSibling(ready.getFileName() + ".pending");
                    java.nio.file.Files.writeString(pending, Long.toString(ProcessHandle.current().pid()));
                    java.nio.file.Files.move(pending, ready);
                    Thread.sleep(60000);
                }
            }'''
            File java = new File(System.getProperty('java.home'), 'bin/java')
            Throwable failure = null
            boolean interrupted = false
            Thread runner = new Thread({
                try { run([java.absolutePath, source.absolutePath, ready.absolutePath], mode, 3000) }
                catch (Throwable caught) { failure = caught; interrupted = Thread.currentThread().isInterrupted() }
            } as Runnable)
            ProcessHandle owned = null
            try {
                runner.start()
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
                while (!ready.isFile() && runner.isAlive() && System.nanoTime() < deadline) Thread.sleep(10)
                assertTrue(ready.isFile(), mode + ' child did not start')
                owned = ProcessHandle.of(Long.parseLong(ready.text)).orElseThrow()
                assertTrue(owned.isAlive())
                if (mode == 'interrupt') runner.interrupt()
                runner.join(10000)
                assertFalse(runner.isAlive(), mode + ' wrapper did not return')
                assertNotNull(failure)
                if (mode == 'interrupt') {
                    assertInstanceOf(InterruptedException, failure)
                    assertEquals(0, failure.suppressed.length)
                } else {
                    assertInstanceOf(AssertionFailedError, failure)
                    assertEquals('timeout timed out', failure.message)
                    assertInstanceOf(TimeoutException, failure.cause)
                    assertEquals(0, failure.cause.suppressed.length)
                }
                boolean alive = owned.isAlive()
                println("owned-child mode=${mode} pid=${owned.pid()} alive-after-wrapper=${alive} failure=${failure.class.name} interrupt=${interrupted}")
                assertFalse(alive, mode + ' child still alive after wrapper failed')
                if (mode == 'interrupt') assertTrue(interrupted)
            } finally {
                if (runner.isAlive()) runner.interrupt()
                if (owned != null && owned.isAlive()) owned.destroyForcibly()
                if (owned != null) owned.onExit().get(10, TimeUnit.SECONDS)
                runner.join(10000)
                assertFalse(runner.isAlive())
            }
    }

    private void run(List<String> argv, String name, long timeoutMillis = 60000) {
        File log = new File(temporary, name + '.log')
        Process child = new ProcessBuilder(argv).redirectErrorStream(true).redirectOutput(log).start()
        int exit
        try {
            exit = CompositionProcess.await(child, timeoutMillis, 5000)
        } catch (TimeoutException failure) {
            throw new AssertionFailedError(name + ' timed out', failure)
        }
        assertEquals(0, exit, log.text)
    }
}

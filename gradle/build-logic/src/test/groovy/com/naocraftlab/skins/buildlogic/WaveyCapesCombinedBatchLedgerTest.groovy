package com.naocraftlab.skins.buildlogic

import org.gradle.api.GradleException
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

import java.security.MessageDigest

import static org.junit.jupiter.api.Assertions.*

class WaveyCapesCombinedBatchLedgerTest {
    @TempDir File root

    abstract static class ControlledBatchTask extends VerifyWaveyCapesCompositionTask {
        File emitterClasses
        boolean omitReset
        Process child

        @Override
        protected Process startCompositionProcess(List<String> argv, File work, File log) {
            List<String> lines = new File(work, 'combined-cases.tsv').readLines('UTF-8')
            File manifest = new File(work, 'combined-cases.tsv')
            List<String> records = ['COMBINED_BATCH_MANIFEST\t' + hash(manifest) + '\t' + lines.size()]
            int positives = 0
            lines.each { String line ->
                List<String> row = line.split('\t', -1).toList()
                boolean positive = row[1] == '0'
                String outcome = positive ? 'POSITIVE' : 'EXPECTED_REJECTION'
                records.add((['COMBINED_BATCH_BEGIN', row[0], outcome] + row.drop(3)).join('\t'))
                if (positive) {
                    records.add("COMBINED_COMPOSITION_PASS scope=${row[3]} scenario=${row[4]} order=${row[5]} stage=${row[6]} style=${row[7]} geometry=1 draws=4; raw material snapshot consumer scope, no publication/GPU claim".toString())
                    positives++
                }
                records.add(['COMBINED_BATCH_OUTCOME', row[0], outcome, positive ? 'NONE' : 'COMBINED_INTEGRATION_MISSING'].join('\t'))
                records.add('COMBINED_BATCH_RESET\t' + row[0])
            }
            records.add(['COMBINED_BATCH_COMPLETE', lines.size(), positives, lines.size() - positives].join('\t'))
            if (omitReset) records.remove(records.findIndexOf { it.startsWith('COMBINED_BATCH_RESET\t') })
            File input = new File(work, 'unit-child-protocol.txt')
            input.setText(records.join('\n') + '\n', 'UTF-8')
            child = new ProcessBuilder(new File(System.getProperty('java.home'), 'bin/java').absolutePath,
                    '-cp', emitterClasses.absolutePath, 'com.naocraftlab.skins.buildlogic.fixture.BatchReceiptEmitter', input.absolutePath)
                    .directory(work).redirectErrorStream(true).redirectOutput(log).start()
            child
        }

        @Override
        void runCompositionProcess(File work, Map receipt, Closure save, String name, List command, int expected, String marker) {
            if (name == 'combined-matrix-batch') super.runCompositionProcess(work, receipt, save, name, command, expected, marker)
        }
    }

    private static String hash(File file) {
        MessageDigest.getInstance('SHA-256').digest(file.bytes).encodeHex().toString()
    }

    private Map fixture(boolean framed = false) {
        List<Map> cases = [
                [name: 'combined-world-live-foreign-first-normal-SMOOTH', expectedExit: 0,
                 args: ['', 'world', 'live', 'foreign-first', 'normal', 'SMOOTH']],
                [name: 'combined-sensitivity-no-support-SMOOTH', expectedExit: 1,
                 args: ['', 'world', 'no-support', 'foreign-first', 'normal', 'SMOOTH']]]
        cases.each { it.args[0] = new File(root, it.name).absolutePath }
        File manifest = new File(root, 'combined-cases.tsv')
        manifest.setText(cases.collect { ([it.name, it.expectedExit.toString()] + it.args).join('\t') }.join('\n') + '\n', 'UTF-8')
        List<String> lines = ['COMBINED_BATCH_MANIFEST\t' + hash(manifest) + '\t' + cases.size()]
        cases.each { row ->
            String outcome = row.expectedExit == 0 ? 'POSITIVE' : 'EXPECTED_REJECTION'
            lines.add((['COMBINED_BATCH_BEGIN', row.name, outcome] + row.args.drop(1)).join('\t'))
            if (row.expectedExit == 0) lines.add('COMBINED_COMPOSITION_PASS scope=world scenario=live order=foreign-first stage=normal style=SMOOTH geometry=1 draws=4; raw material snapshot consumer scope, no publication/GPU claim')
            lines.add(['COMBINED_BATCH_OUTCOME', row.name, outcome,
                       row.expectedExit == 0 ? 'NONE' : 'COMBINED_INTEGRATION_MISSING'].join('\t'))
            lines.add('COMBINED_BATCH_RESET\t' + row.name)
        }
        lines.add('COMBINED_BATCH_COMPLETE\t2\t1\t1')
        File log = new File(root, 'combined-matrix-batch.log')
        log.setText(lines.collect { framed ? '[12:34:56] [main/INFO]: [STDOUT]: ' + it : it }.join('\n') + '\n', 'UTF-8')
        List<String> argv = ['unit-java', '-cp', 'unit-classpath',
                             'com.naocraftlab.skins.buildlogic.probe.wavey.CombinedCapeBatchProbe', manifest.absolutePath]
        Map expected = [name: 'combined-matrix-batch', manifest: manifest, manifestSha256: hash(manifest),
                        cases: cases, log: log, argv: argv]
        Map command = [name: expected.name, argv: argv, expectedExit: 0, exit: 0, reaped: true,
                       pid: 424242L, parentPid: ProcessHandle.current().pid(), log: log.absolutePath,
                       sha256: hash(log), failureClass: null]
        [expected: expected, command: command, lines: lines]
    }

    private void admit(Map fixture) {
        def task = ProjectBuilder.builder().withProjectDir(root).build().tasks.create('batch', VerifyWaveyCapesCompositionTask)
        task.targetId.set('batch-unit')
        Map receipt = [result: 'RUNNING', commands: [fixture.command]]
        int saves = 0
        task.completeCombinedBatch(receipt, { saves++ }, fixture.expected)
        assertEquals(1, saves)
        assertEquals('PASS', receipt.combinedBatch.state)
        assertEquals(2, receipt.combinedBatch.cases.size())
        assertEquals(fixture.expected.cases*.name, receipt.combinedBatch.cases*.name)
        assertTrue(receipt.combinedBatch.cases.every { it.resetVerified })
        assertEquals(fixture.command.sha256, receipt.combinedBatch.logSha256)
        assertEquals(fixture.expected.manifestSha256, receipt.combinedBatch.manifestSha256)
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    void realTaskAdmitsOnlyCompleteOwnedBatchAndObservedLoggerFraming(boolean framed) {
        admit(fixture(framed))
    }

    @ParameterizedTest
    @ValueSource(strings = ['missing-begin', 'duplicate-begin', 'extra-row', 'wrong-name', 'wrong-scope',
            'wrong-style', 'wrong-stage', 'wrong-order', 'wrong-scenario', 'wrong-outcome', 'wrong-reason',
            'missing-reset', 'duplicate-reset', 'foreign-reset', 'swapped-lifecycle', 'missing-native',
            'duplicate-native', 'wrong-native', 'missing-summary', 'duplicate-summary', 'forged-summary',
            'truncated', 'substring-frame', 'missing-manifest-header', 'wrong-manifest-header'])
    void realTaskRejectsLedgerMutationEvenWithReboundPhysicalLogHash(String mutation) {
        Map f = fixture()
        List<String> lines = f.lines
        int begin = lines.findIndexOf { it.startsWith('COMBINED_BATCH_BEGIN\t') }
        int outcome = lines.findIndexOf { it.startsWith('COMBINED_BATCH_OUTCOME\t') }
        int reset = lines.findIndexOf { it.startsWith('COMBINED_BATCH_RESET\t') }
        int nativePass = lines.findIndexOf { it.startsWith('COMBINED_COMPOSITION_PASS ') }
        switch (mutation) {
            case 'missing-begin': lines.remove(begin); break
            case 'duplicate-begin': lines.add(begin, lines[begin]); break
            case 'extra-row': lines.add(lines.size() - 1, 'COMBINED_BATCH_BEGIN\tunknown\tPOSITIVE\tworld\tlive\tforeign-first\tnormal\tSMOOTH'); break
            case 'wrong-name': lines[begin] = lines[begin].replace(f.expected.cases[0].name, 'unknown'); break
            case 'wrong-scope': lines[begin] = lines[begin].replace('\tworld\t', '\tworldless\t'); break
            case 'wrong-style': lines[begin] = lines[begin].replace('\tSMOOTH', '\tBLOCKY'); break
            case 'wrong-stage': lines[begin] = lines[begin].replace('\tnormal\t', '\tACCUMULATE\t'); break
            case 'wrong-order': lines[begin] = lines[begin].replace('\tforeign-first\t', '\tforeign-last\t'); break
            case 'wrong-scenario': lines[begin] = lines[begin].replace('\tlive\t', '\tstale-before\t'); break
            case 'wrong-outcome': lines[outcome] = lines[outcome].replace('\tPOSITIVE\t', '\tEXPECTED_REJECTION\t'); break
            case 'wrong-reason': lines[outcome] = lines[outcome].replace('\tNONE', '\tCOMBINED_INTEGRATION_MISSING'); break
            case 'missing-reset': lines.remove(reset); break
            case 'duplicate-reset': lines.add(reset, lines[reset]); break
            case 'foreign-reset': lines[reset] = 'COMBINED_BATCH_RESET\t' + f.expected.cases[1].name; break
            case 'swapped-lifecycle': Collections.swap(lines, outcome, reset); break
            case 'missing-native': lines.remove(nativePass); break
            case 'duplicate-native': lines.add(nativePass, lines[nativePass]); break
            case 'wrong-native': lines[nativePass] = lines[nativePass].replace('scope=world ', 'scope=worldless '); break
            case 'missing-summary': lines.remove(lines.size() - 1); break
            case 'duplicate-summary': lines.add(lines.last()); break
            case 'forged-summary': lines[lines.size() - 1] = 'COMBINED_BATCH_COMPLETE\t999\t998\t1'; break
            case 'truncated': lines = lines.take(reset); break
            case 'substring-frame': lines[begin] = 'untrusted-prefix ' + lines[begin]; break
            case 'missing-manifest-header': lines.remove(0); break
            case 'wrong-manifest-header': lines[0] = 'COMBINED_BATCH_MANIFEST\t' + '0' * 64 + '\t2'; break
            default: fail(mutation)
        }
        f.expected.log.setText(lines.join('\n') + '\n', 'UTF-8')
        f.command.sha256 = hash(f.expected.log)
        def task = ProjectBuilder.builder().withProjectDir(root).build().tasks.create('mutatedBatch', VerifyWaveyCapesCompositionTask)
        Map receipt = [result: 'RUNNING', commands: [f.command]]
        int saves = 0
        assertThrows(GradleException) { task.completeCombinedBatch(receipt, { saves++ }, f.expected) }
        assertEquals(0, saves)
        assertNotEquals('PASS', receipt.combinedBatch?.state)
        assertEquals('RUNNING', receipt.result)
    }

    @ParameterizedTest
    @ValueSource(strings = ['exit', 'unreaped', 'failure', 'hash', 'other-log', 'other-argv', 'other-command',
            'duplicate-command', 'missing-command', 'manifest-changed', 'expected-empty', 'expected-duplicate'])
    void realTaskRejectsInvalidOwnershipOrInputBindings(String mutation) {
        Map f = fixture()
        List<Map> commands = [f.command]
        switch (mutation) {
            case 'exit': f.command.exit = 1; break
            case 'unreaped': f.command.reaped = false; break
            case 'failure': f.command.failureClass = 'unit-failure'; break
            case 'hash': f.command.sha256 = '0' * 64; break
            case 'other-log': f.command.log = new File(root, 'other.log').absolutePath; break
            case 'other-argv': f.command.argv = ['other-command']; break
            case 'other-command': f.command.name = 'other'; break
            case 'duplicate-command': commands.add(f.command); break
            case 'missing-command': commands.clear(); break
            case 'manifest-changed': f.expected.manifest.append('changed\n'); break
            case 'expected-empty': f.expected.cases = []; break
            case 'expected-duplicate': f.expected.cases = [f.expected.cases[0], f.expected.cases[0]]; break
            default: fail(mutation)
        }
        def task = ProjectBuilder.builder().withProjectDir(root).build().tasks.create('invalidBatch', VerifyWaveyCapesCompositionTask)
        Map receipt = [result: 'RUNNING', commands: commands]
        int saves = 0
        assertThrows(GradleException) { task.completeCombinedBatch(receipt, { saves++ }, f.expected) }
        assertEquals(0, saves)
        assertNotEquals('PASS', receipt.combinedBatch?.state)
    }

    @Test
    void receiptWriteFailureCannotBeRelabelledAsTaskSuccess() {
        Map f = fixture()
        def task = ProjectBuilder.builder().withProjectDir(root).build().tasks.create('reportingBatch', VerifyWaveyCapesCompositionTask)
        Map receipt = [result: 'RUNNING', commands: [f.command]]
        def failure = new IOException('unit-report-write')
        assertSame(failure, assertThrows(IOException) {
            task.completeCombinedBatch(receipt, { throw failure }, f.expected)
        })
        assertEquals('RUNNING', receipt.result)
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    void fullTaskReapsOwnedUnitChildBeforeAdmittingOrRejectingItsLedger(boolean omitReset) {
        File source = new File(root, 'BatchReceiptEmitter.java')
        source.setText('''package com.naocraftlab.skins.buildlogic.fixture;
public final class BatchReceiptEmitter {
    public static void main(String[] args) throws Exception {
        System.out.print(java.nio.file.Files.readString(java.nio.file.Path.of(args[0])));
    }
}
''', 'UTF-8')
        File classes = new File(root, 'unit-emitter-classes'); classes.mkdirs()
        ByteArrayOutputStream compilerOutput = new ByteArrayOutputStream()
        assertEquals(0, javax.tools.ToolProvider.systemJavaCompiler.run(null, compilerOutput, compilerOutput,
                '-d', classes.absolutePath, source.absolutePath), compilerOutput.toString('UTF-8'))
        def task = ProjectBuilder.builder().withProjectDir(root).build().tasks.create('ownedBatch', ControlledBatchTask)
        task.emitterClasses = classes; task.omitReset = omitReset
        File resources = new File(root, 'resources'); resources.mkdirs()
        File index = new File(root, 'unit-owner-index.jar')
        new java.util.zip.ZipOutputStream(new FileOutputStream(index)).withCloseable { zip ->
            ['dev/tr7zw/waveycapes/render/CapeNodeCollector.class',
             'net/minecraft/client/renderer/rendertype/RenderType.class'].each { entry ->
                zip.putNextEntry(new java.util.zip.ZipEntry(entry)); zip.closeEntry()
            }
        }
        File home = new File(System.getProperty('java.home'))
        task.mode.set('combined'); task.submissionPreview.set(false); task.targetId.set('owned-batch-unit')
        task.javaRelease.set(17); task.serviceResources.set(resources)
        task.compilerExecutable.set(new File(home, 'bin/javac')); task.javaExecutable.set(new File(home, 'bin/java'))
        task.executableClasspath.from(index); task.workDirectory.set(new File(root, 'owned-work'))
        if (omitReset) assertThrows(GradleException) { task.verifyComposition() }
        else task.verifyComposition()
        Map receipt = new groovy.json.JsonSlurper().parse(new File(root, 'owned-work/result.json')) as Map
        assertEquals(omitReset ? 'FAIL' : 'PASS', receipt.result)
        assertFalse(task.child.isAlive())
        assertFalse(receipt.containsKey('activeProcess'))
        assertEquals(1, receipt.commands.size())
        assertEquals(task.child.pid(), receipt.commands[0].pid)
        assertEquals(0, receipt.commands[0].exit)
        assertEquals(true, receipt.commands[0].reaped)
        assertEquals(hash(new File(root, 'owned-work/combined-matrix-batch.log')), receipt.commands[0].sha256)
        if (omitReset) assertNull(receipt.combinedBatch)
        else assertEquals(216, receipt.combinedBatch.cases.size())
    }
}

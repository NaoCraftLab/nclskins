package com.naocraftlab.skins.buildlogic

import org.gradle.api.GradleException

import java.security.MessageDigest
import java.util.regex.Pattern

final class CombinedBatchResults {
    private static final Pattern FRAME = Pattern.compile('^\\[[0-9]{2}:[0-9]{2}:[0-9]{2}\\] \\[main/INFO\\]: \\[STDOUT\\]: (.*)$')
    private static final Pattern NATIVE_PASS = Pattern.compile('^COMBINED_COMPOSITION_PASS scope=(world|worldless) scenario=([a-z-]+) order=(foreign-first|foreign-last) stage=(normal|ACCUMULATE|DEPTH_BOUNDS|TRANSMITTANCE) style=(SMOOTH|BLOCKY) geometry=[0-9]+ draws=[0-9]+; raw material snapshot consumer scope, no publication/GPU claim$')

    private static String hash(File file) {
        MessageDigest.getInstance('SHA-256').digest(file.bytes).encodeHex().toString()
    }

    private static void require(boolean condition, String reason) {
        if (!condition) throw new GradleException('Combined batch receipt rejected: ' + reason)
    }

    static List<Map> verify(Map expected, Map command) {
        List<Map> cases = expected.cases
        require(cases != null && !cases.empty, 'empty expected roster')
        require(cases*.name.toSet().size() == cases.size(), 'duplicate expected case')
        require(cases.collect { it.args[0] }.toSet().size() == cases.size(), 'duplicate storage root')
        cases.each { row ->
            require(row.name instanceof String && row.name ==~ /[a-zA-Z0-9_-]+/, 'invalid case name')
            require(row.expectedExit in [0, 1] && row.args.size() == 6, 'invalid expected case')
        }
        File manifest = expected.manifest
        File log = expected.log
        require(manifest.isFile() && manifest.length() <= 1_048_576, 'missing or oversized manifest')
        require(hash(manifest) == expected.manifestSha256, 'manifest changed')
        List<String> manifestRows = manifest.readLines('UTF-8')
        require(manifestRows == cases.collect { ([it.name, it.expectedExit.toString()] + it.args).join('\t') }, 'manifest differs from generated roster')
        require(command.name == expected.name && command.argv == expected.argv, 'command identity')
        require(command.expectedExit == 0 && command.exit == 0 && command.reaped == true && !command.failureClass, 'process did not complete successfully')
        require(command.pid instanceof Number && command.pid > 0 && command.parentPid instanceof Number && command.parentPid > 0, 'missing process ownership')
        require(command.log == log.absolutePath && log.isFile() && log.length() <= 16_777_216, 'physical log binding')
        require(command.sha256 == hash(log), 'physical log hash')
        int index = 0
        String step = 'MANIFEST'
        boolean nativeSeen = false
        List<Map> verified = []
        log.eachLine('UTF-8') { String line ->
            def envelope = FRAME.matcher(line)
            String payload = envelope.matches() ? envelope.group(1) : line
            if (payload.startsWith('COMBINED_COMPOSITION_PASS')) {
                require(step == 'OUTCOME' && index < cases.size() && cases[index].expectedExit == 0 && !nativeSeen, 'unexpected native result')
                def nativeResult = NATIVE_PASS.matcher(payload)
                require(nativeResult.matches(), 'native result framing')
                require((1..5).collect { nativeResult.group(it) } == cases[index].args.drop(1), 'native result arguments')
                nativeSeen = true
                return
            }
            if (!payload.startsWith('COMBINED_BATCH_')) return
            List<String> fields = payload.split('\t', -1).toList()
            switch (fields[0]) {
                case 'COMBINED_BATCH_MANIFEST':
                    require(step == 'MANIFEST' && fields == ['COMBINED_BATCH_MANIFEST', expected.manifestSha256, cases.size().toString()], 'manifest header')
                    step = 'BEGIN'
                    break
                case 'COMBINED_BATCH_BEGIN':
                    require(step == 'BEGIN' && index < cases.size(), 'case begin order')
                    Map row = cases[index]
                    String outcome = row.expectedExit == 0 ? 'POSITIVE' : 'EXPECTED_REJECTION'
                    require(fields == ['COMBINED_BATCH_BEGIN', row.name, outcome] + row.args.drop(1), 'case identity or arguments')
                    nativeSeen = false
                    step = 'OUTCOME'
                    break
                case 'COMBINED_BATCH_OUTCOME':
                    require(step == 'OUTCOME' && index < cases.size(), 'case outcome order')
                    Map row = cases[index]
                    boolean positive = row.expectedExit == 0
                    require(nativeSeen == positive, 'missing or unexpected native result')
                    require(fields == ['COMBINED_BATCH_OUTCOME', row.name, positive ? 'POSITIVE' : 'EXPECTED_REJECTION',
                                       positive ? 'NONE' : 'COMBINED_INTEGRATION_MISSING'], 'case outcome or rejection reason')
                    step = 'RESET'
                    break
                case 'COMBINED_BATCH_RESET':
                    require(step == 'RESET' && index < cases.size() && fields == ['COMBINED_BATCH_RESET', cases[index].name], 'reset identity or order')
                    Map row = cases[index]
                    verified.add([name: row.name, arguments: row.args.toList(),
                                  outcome: row.expectedExit == 0 ? 'POSITIVE' : 'EXPECTED_REJECTION',
                                  reason: row.expectedExit == 0 ? 'NONE' : 'COMBINED_INTEGRATION_MISSING',
                                  nativeObserved: nativeSeen, resetVerified: true])
                    index++
                    step = index == cases.size() ? 'COMPLETE' : 'BEGIN'
                    break
                case 'COMBINED_BATCH_COMPLETE':
                    require(step == 'COMPLETE', 'summary order or duplication')
                    int positives = verified.count { it.outcome == 'POSITIVE' }
                    require(fields == ['COMBINED_BATCH_COMPLETE', verified.size().toString(), positives.toString(),
                                       (verified.size() - positives).toString()], 'summary counts')
                    step = 'DONE'
                    break
                default:
                    throw new GradleException('Combined batch receipt rejected: unknown protocol record')
            }
        }
        require(step == 'DONE' && verified.size() == cases.size(), 'incomplete lifecycle ledger')
        verified
    }
}

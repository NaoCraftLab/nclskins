package com.naocraftlab.skins.buildlogic

import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.gradle.testfixtures.ProjectBuilder
import groovy.json.JsonOutput
import org.junit.jupiter.api.io.TempDir
import static org.junit.jupiter.api.Assertions.*

class WaveyCapesGuardContractsTest {
    @TempDir File temporary

    @Test
    void immediateSubjectInputsUseExactTypedFactoriesBeforeNativeQueries() {
        File sources = new File('../..', 'gradle/build-logic/src/waveyImmediateComposition').canonicalFile
        ['rendered-buffer': 'identity.toString()', 'mesh': 'identity'].each { abi, argument ->
            String inputs = new File(sources, "${abi}/java/com/naocraftlab/skins/buildlogic/probe/wavey/ImmediateAppearanceInputs.java").text
            assertTrue(inputs.contains('public static User currentUser(UUID identity)'), abi)
            assertTrue(inputs.contains('new User("Self", ' + argument + ', "", Optional.empty(), Optional.empty(), User.Type.MSA)'), abi)
        }
        ['ImmediateGuardProbe', 'ImmediateCapeProbe', 'ImmediatePublicPreviewProbe'].each { name ->
            String caller = new File(sources, "java/com/naocraftlab/skins/buildlogic/probe/wavey/${name}.java").text
            assertEquals(1, caller.count('ImmediateAppearanceInputs.currentUser(producer.self)'), name)
            assertFalse(caller.contains('getType() == UUID.class') || caller.contains('User.class.getDeclaredField("uuid")'), name)
            assertTrue(caller.contains('"CURRENT_SUBJECT_IDENTITY"'), name)
            assertTrue(caller.contains('"PREVIOUS_SUBJECT_RESTORED"'), name)
            assertTrue(caller.indexOf('ImmediateAppearanceInputs.currentUser(producer.self)') < caller.indexOf(name == 'ImmediatePublicPreviewProbe' ? 'cache.request(' : 'CapeProjection.material('), name)
        }
    }

    @Test
    void everyGuardNeedsItsOwnCompletionAndResetNotMetadata() {
        (WaveyCompositionGuards.ISOLATED + WaveyCompositionGuards.COMPATIBLE).each { String name ->
            File syntheticUnitLog = new File(temporary, name + '.log')
            syntheticUnitLog.text = 'CANONICAL_CASE_PASS guard-' + name + '\nCANONICAL_CASE_RESET_PASS guard-' + name + '\n'
            assertEquals([(name): 'PASS'], WaveyCompositionGuards.readResults(syntheticUnitLog, [name]))
            ['CANONICAL_CASE_PASS guard-', 'CANONICAL_CASE_RESET_PASS guard-'].each { String prefix ->
                syntheticUnitLog.text = prefix + name + '\n'
                assertThrows(IllegalStateException) { WaveyCompositionGuards.readResults(syntheticUnitLog, [name]) }
            }
            syntheticUnitLog.text = ('CANONICAL_CASE_PASS guard-' + name + '\nCANONICAL_CASE_RESET_PASS guard-' + name + '\n') * 2
            assertThrows(IllegalStateException) { WaveyCompositionGuards.readResults(syntheticUnitLog, [name]) }
            syntheticUnitLog.text = 'CANONICAL_CASE_PASS guard-' + name + '-other\nCANONICAL_CASE_RESET_PASS guard-' + name + '-other\n'
            assertThrows(IllegalStateException) { WaveyCompositionGuards.readResults(syntheticUnitLog, [name]) }
            syntheticUnitLog.text = '[unit] CANONICAL_CASE_PASS guard-' + name + '\n[unit] CANONICAL_CASE_RESET_PASS guard-' + name + '\n'
            assertThrows(IllegalStateException) { WaveyCompositionGuards.readResults(syntheticUnitLog, [name]) }
        }
    }

    @Test
    void completeRosterCannotHideUnknownExtraResultsOrResets() {
        [WaveyCompositionGuards.ISOLATED, WaveyCompositionGuards.COMPATIBLE].each { List<String> roster ->
            File log = new File(temporary, 'complete-unit.log')
            List order = roster.contains('bridge-contract') ? ['no-support-sensitivity', 'bridge-contract'] : roster
            String valid = order.collectMany { ['CANONICAL_CASE_PASS guard-' + it, 'CANONICAL_CASE_RESET_PASS guard-' + it] }.join('\n') + '\n'
            assertAll(['CANONICAL_CASE_PASS guard-', 'CANONICAL_CASE_RESET_PASS guard-'].collect { String prefix ->
                { ->
                    log.text = valid
                    assertEquals(roster.collectEntries { [(it): 'PASS'] }, WaveyCompositionGuards.readResults(log, roster))
                    log.append(prefix + 'unexpected\n')
                    def failure = assertThrows(IllegalStateException) { WaveyCompositionGuards.readResults(log, roster) }
                    assertTrue(failure.message.contains('guard result roster'), failure.message)
                } as org.junit.jupiter.api.function.Executable
            })
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ['rendered-buffer', 'mesh'])
    void legacyCompletionAcceptsActualProducerOrder(String mode) {
        File source = new File('../..', 'gradle/build-logic/src/waveyLoaderComposition/legacy-game/java/com/naocraftlab/skins/buildlogic/probe/wavey/LegacyCapeBatch.java')
        List order = (source.text.split(/return;\s*}/, 2).last() =~ /runCase\("guard-([^"\r\n]+)"/).collect { it[1] }
        assertEquals(['no-support-sensitivity', 'bridge-contract'], order)
        def project = ProjectBuilder.builder().withProjectDir(temporary).build()
        def guards = project.tasks.create('verifyWaveyCapesGuards', VerifyWaveyLegacyCompositionTask)
        def composition = project.tasks.create('verifyWaveyCapesComposition', VerifyWaveyLegacyCompositionTask)
        [guards, composition].each { task ->
            task.mode.set(mode); task.targetId.set('unit-legacy-' + mode)
            task.runtimeProfile.set('legacy-client-' + mode)
            task.workDirectory.set(new File(temporary, task.name))
        }
        guards.guardOnly.set(true); guards.guardRoster.set(WaveyCompositionGuards.ISOLATED)
        composition.dependsOn(guards)
        composition.guardReceipt.set(guards.workDirectory.file('result.json'))
        Map inputs = [target: 'unit-legacy-' + mode, mode: mode, runtimeProfile: 'legacy-client-' + mode,
                submissionPreview: false, javaRelease: 17, sources: [:], loaderSources: [:], mixins: [:],
                compiler: [unitOnly: true], java: [unitOnly: true], classpath: [], compilerClasspath: [], resourceClasspath: []]
        Map stage = [:], receipt = [:]
        [[guards, WaveyCompositionGuards.ISOLATED, 'guards', stage], [composition, order, 'positive', receipt]].each { row ->
            File log = new File(temporary, row[2] + '.log')
            log.text = (row[1].collectMany { ['CANONICAL_CASE_PASS guard-' + it, 'CANONICAL_CASE_RESET_PASS guard-' + it] }
                    + 'CANONICAL_CLIENT_CLOSED_PASS').join('\n') + '\n'
            row[3].putAll(inputs + [guardOnly: row[0] == guards, guardRoster: row[0].guardRoster.get(),
                    definitionProfiles: [[name: row[2], state: 'PASS']], executedFmlContexts: 1,
                    guardResults: row[1].collectEntries { [(it): 'PASS'] },
                    commands: [[name: 'connected-client-' + row[2], exit: 0, expectedExit: 0, reaped: true,
                            failureClass: null, marker: 'CANONICAL_CLIENT_CLOSED_PASS', log: log.absolutePath,
                            sha256: WaveyCompositionGuards.fileHash(log)]]])
        }
        assertEquals('PASS', guards.completeDirect(stage))
        stage.result = 'PASS'
        File stageFile = composition.guardReceipt.get().asFile
        stageFile.parentFile.mkdirs(); stageFile.text = JsonOutput.toJson(stage)
        assertEquals('PASS', composition.completeDirect(receipt))
    }

    @Test
    void namesAndPassFieldsCannotReplaceExecutedClosedProcessEvidence() {
        [WaveyCompositionGuards.ISOLATED, WaveyCompositionGuards.COMPATIBLE].each { roster ->
            Map metadataOnly = [guardResults: roster.collectEntries { [(it): 'PASS'] }]
            def failure = assertThrows(IllegalStateException) {
                WaveyCompositionGuards.verifyEvidence(metadataOnly, roster, roster == WaveyCompositionGuards.ISOLATED ? 'guards' : 'positive')
            }
            assertTrue(failure.message.contains('profile not executed'))
        }
    }
}

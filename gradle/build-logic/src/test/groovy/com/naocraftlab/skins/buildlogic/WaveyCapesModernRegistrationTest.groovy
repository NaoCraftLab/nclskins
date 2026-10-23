package com.naocraftlab.skins.buildlogic

import groovy.json.JsonSlurper
import org.gradle.testfixtures.ProjectBuilder
import org.objectweb.asm.*
import org.objectweb.asm.tree.*
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import static org.junit.jupiter.api.Assertions.*

class WaveyCapesModernRegistrationTest {
    @TempDir File temporary

    @ParameterizedTest
    @ValueSource(strings = ['neoforge-1.21.11', 'neoforge-26.1', 'neoforge-26.2', 'neoforge-26.3',
            'neoforge-1.21.11|prepare', 'neoforge-26.1|prepare', 'neoforge-26.2|prepare', 'neoforge-26.3|prepare'])
    void defaultGraphSelectsExactlyOneNativeEnchantmentGameInput(String configuration) {
        String target = configuration.split('\\|')[0]
        def project = registeredProject(target, configuration.endsWith('|prepare')) { fail('Selection must not prepare or execute') }
        def composition = project.tasks.named('verifyWaveyCapesComposition').get()
        def guards = project.tasks.named('verifyWaveyCapesGuards').get()
        boolean submission = composition.submissionPreview.get()
        File game = new File('../..', 'gradle/build-logic/src/waveyLoaderComposition/game').canonicalFile
        String relative = '/java/com/naocraftlab/skins/buildlogic/probe/wavey/NativeEnchantmentInput.java'
        File expected = new File(game, (submission ? 'item-components' : 'holder-components') + relative)
        File opposite = new File(game, (submission ? 'holder-components' : 'item-components') + relative)
        Closure verify = { Set<File> sources ->
            assertEquals([expected] as Set, sources.findAll { it.name == 'NativeEnchantmentInput.java' } as Set,
                    'Exactly one ABI-correct factory must reach the real GAME compilation')
        }
        Set<File> selected = composition.loaderSources.files.collect { it.canonicalFile } as Set
        verify(selected)
        assertEquals(selected, guards.loaderSources.files.collect { it.canonicalFile } as Set)
        assertTrue(selected.contains(new File(game, 'java/com/naocraftlab/skins/buildlogic/probe/wavey/ClientCapeBatch.java')))
        assertTrue(selected.find { it.name == 'NativeEnchantmentInput.java' }.path.contains('/game/'))
        [selected - expected, selected + opposite, (selected - expected) + opposite].each { Set<File> mutant ->
            assertThrows(AssertionError, { verify(mutant) } as org.junit.jupiter.api.function.Executable)
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ['neoforge-1.21.11', 'neoforge-26.1', 'neoforge-26.2', 'neoforge-26.3'])
    void dependencyInspectionDoesNotResolveCanonicalSdk(String target) {
        def project = registeredProject(target, false) { fail('No preparation during dependency inspection') }
        int resolutions = 0
        project.configurations.waveyCapesPublicAbi.incoming.beforeResolve {
            resolutions++
            throw new IllegalStateException('SDK_RESOLVED_DURING_DEPENDENCY_INSPECTION')
        }
        ['check', 'verifyWaveyCapesGuards', 'verifyWaveyCapesComposition'].each { name ->
            def task = project.tasks.named(name).get()
            assertDoesNotThrow({ task.taskDependencies.getDependencies(task) } as org.junit.jupiter.api.function.Executable)
        }
        assertEquals(0, resolutions)
    }

    @ParameterizedTest
    @ValueSource(strings = ['neoforge-1.21.11', 'neoforge-26.1', 'neoforge-26.2', 'neoforge-26.3'])
    void sdkFileResolutionHonorsCanonicalProducerBeforeExecution(String target) {
        def project = registeredProject(target, false) { fail('No preparation in unit validator fixture') }
        def configuration = project.configurations.waveyCapesPublicAbi
        configuration.dependencies.clear()
        File sdk = new File(project.projectDir, 'synthetic-unit-validator-sdk.jar')
        sdk.bytes = new byte[0]
        def producer = project.tasks.register('unitSdkProducer') { outputs.file(sdk) }
        project.dependencies.add(configuration.name, project.files(sdk).builtBy(producer))
        int resolutions = 0
        configuration.incoming.beforeResolve { resolutions++ }
        def guards = project.tasks.named('verifyWaveyCapesGuards').get()
        assertTrue(guards.taskDependencies.getDependencies(guards).contains(producer.get()))
        assertEquals(0, resolutions)
        def failure = assertThrows(RuntimeException, { guards.isolatedSdk.get() } as org.junit.jupiter.api.function.Executable)
        assertTrue(failure.cause instanceof org.gradle.api.InvalidUserCodeException)
        assertTrue(failure.cause.message.contains("before task ':unitSdkProducer' has completed"))
        assertFalse(producer.get().state.executed)
    }

    @ParameterizedTest
    @ValueSource(strings = ['neoforge-1.21.11', 'neoforge-26.1', 'neoforge-26.2', 'neoforge-26.3',
            'neoforge-1.21.11|prepare', 'neoforge-26.1|prepare', 'neoforge-26.2|prepare', 'neoforge-26.3|prepare'])
    void ordinaryCheckSelectsImplementedDirectRouteWithoutRootFlag(String configuration) {
        String target = configuration.split('\\|')[0]
        boolean prepare = configuration.endsWith('|prepare')
        int effects = 0
        def project = registeredProject(target, prepare) { effects++ }
        Map spec = project.ext.targetSpec
        def composition = project.tasks.named('verifyWaveyCapesComposition').get()
        assertEquals(target == 'neoforge-26.3' ? 'direct-client-combined' : target == 'neoforge-26.2' ? 'direct-client-prepared' : 'direct-client-buffered', composition.runtimeProfile.get())
        assertEquals(prepare, composition.prepareOnly.get())
        assertTrue(project.tasks.named('check').get().taskDependencies.getDependencies(project.tasks.named('check').get()).contains(composition))
        assertTrue(composition.taskDependencies.getDependencies(composition).contains(project.tasks.named('prepareWaveyVerificationRun').get()))
        assertEquals(0, effects)
        assertFalse(new File(project.buildDir, 'fixture').exists())
        def guards = project.tasks.findByName('verifyWaveyCapesGuards')
        assertNotNull(guards, 'I1: ordinary direct check must have an executable isolated guard stage')
        assertTrue(composition.taskDependencies.getDependencies(composition).contains(guards),
                'I1: direct completion must depend on the guard stage')
        assertTrue(project.tasks.named('check').get().taskDependencies.getDependencies(project.tasks.named('check').get()).contains(guards))
        assertEquals(['partial-registration', 'callback-linkage', 'closed-legacy'], guards.guardRoster.get())
        assertTrue(guards.guardOnly.get())
        assertEquals(prepare, guards.prepareOnly.get())
        assertEquals(guards.workDirectory.file('result.json').get().asFile, composition.guardReceipt.get().asFile)
        assertEquals(composition.probeSources.files, guards.probeSources.files)
        assertTrue(guards.probeSources.files*.name.containsAll(['CapeRegistrationFailureProbe.java', 'CapeUnsupportedAbiProbe.java',
                'CapeCallbackLinkageProbe.java', 'CapeClosedLegacyProbe.java', 'CapeGuardProbe.java']))
        assertEquals(target == 'neoforge-1.21.11', composition.probeSources.files*.name.contains('BakedPublicPreviewProbe.java'))
        assertEquals(1, composition.probeSources.files.count { it.name == 'NativeElytraConsumptionProbe.java' })
        assertEquals(target != 'neoforge-1.21.11', composition.probeSources.files*.name.contains('NativeElytraDrain.java'))
        ['LivePublicPreviewProbe.java', 'SubmissionRawInputs.java'].each { String source ->
            assertEquals(target == 'neoforge-1.21.11', composition.probeSources.files*.name.contains(source), source)
        }
        assertTrue(composition.loaderSources.files*.name.containsAll(['SubmissionPreviewBindings.java', 'SubmissionPreviewContract.java']))
        assertTrue(composition.loaderSources.files*.name.containsAll(['BakedPreviewBindings.java', 'BakedPreviewContract.java']))
        assertEquals(0, effects)
        guards.prepareOnly.set(false)
        composition.prepareOnly.set(false)
        assertThrows(IllegalStateException) { composition.completeDirect([:]) }
        [guards, composition].each { task ->
            List roster = task == guards ? WaveyCompositionGuards.CONNECTED : WaveyCompositionGuards.COMPATIBLE
            roster.each { String omitted ->
                Map partial = roster.findAll { it != omitted }.collectEntries { [(it): 'PASS'] }
                def failure = assertThrows(IllegalStateException) { task.completeDirect([guardResults: partial]) }
                assertTrue(failure.message.contains('incomplete guard results'), omitted)
            }
        }
        guards.prepareOnly.set(true)
        composition.prepareOnly.set(true)
        WaveyCompositionGuards.CONNECTED.each { String omitted ->
            guards.guardRoster.set(WaveyCompositionGuards.CONNECTED.findAll { it != omitted })
            def failure = assertThrows(IllegalStateException) { composition.completeDirect([executedFmlContexts: 0]) }
            assertTrue(failure.message.contains('missing mandatory stage/edge'), omitted)
        }
        guards.guardRoster.set(WaveyCompositionGuards.CONNECTED)
        def originalSources = guards.probeSources.files
        guards.probeSources.setFrom([])
        def missingSources = assertThrows(IllegalStateException) { composition.completeDirect([executedFmlContexts: 0]) }
        assertTrue(missingSources.message.contains('guard source closure mismatch'))
        guards.probeSources.setFrom(originalSources)
        def noReceipt = assertThrows(IllegalStateException) { composition.completeDirect([executedFmlContexts: 0]) }
        assertTrue(noReceipt.message.contains('missing guard receipt'))
        Map syntheticPreparation = [target: target, mode: composition.mode.get(), submissionPreview: false, javaRelease: spec.java.release,
                runtimeProfile: composition.runtimeProfile.get(), sources: [:], loaderSources: [:], mixins: [:], compiler: [:], java: [:],
                classpath: [], compilerClasspath: [], resourceClasspath: [], executedFmlContexts: 0,
                guardOnly: true, guardRoster: WaveyCompositionGuards.CONNECTED,
                abiSdkPin: guards.abiSdkPin.get(), loaderRuntimeClasspath: [], loaderVmArgs: [:], loaderProgramArgs: [:],
                proofKinds: ['isolated-production-abi', 'connected-game-guards'],
                abiProof: [kind: 'isolated-production-abi', state: 'PREPARED_NOT_STARTED', executedAbiJvms: 0]]
        syntheticPreparation.result = guards.completeDirect(syntheticPreparation)
        assertEquals('PREPARED_NOT_STARTED', syntheticPreparation.result)
        File unitReceipt = composition.guardReceipt.get().asFile
        unitReceipt.parentFile.mkdirs()
        unitReceipt.text = groovy.json.JsonOutput.toJson(syntheticPreparation)
        assertEquals('PREPARED_NOT_STARTED', composition.completeDirect(new LinkedHashMap(syntheticPreparation)))
        ['target', 'mode', 'submissionPreview', 'javaRelease', 'runtimeProfile', 'sources', 'loaderSources', 'mixins',
                'compiler', 'java', 'classpath', 'compilerClasspath', 'resourceClasspath'].each { String key ->
            Map mismatch = new LinkedHashMap(syntheticPreparation)
            mismatch[key] = 'unit-mismatch'
            def rejected = assertThrows(IllegalStateException) { composition.completeDirect(mismatch) }
            assertTrue(rejected.message.contains('stage input mismatch: ' + key), key)
        }
        composition.prepareOnly.set(false)
        Map incompleteRuntime = [guardResults: WaveyCompositionGuards.COMPATIBLE.collectEntries { [(it): 'PASS'] }]
        guards.prepareOnly.set(false)
        def preparedNotPass = assertThrows(IllegalStateException) { composition.completeDirect(incompleteRuntime + syntheticPreparation) }
        assertTrue(preparedNotPass.message.contains('stage not passed'))
        composition.prepareOnly.set(true)
        guards.prepareOnly.set(true)
        composition.guardReceipt.set(guards.workDirectory.file('result.json').get().asFile)
        composition.setDependsOn([])
        def missingEdge = assertThrows(IllegalStateException) { composition.completeDirect([executedFmlContexts: 0]) }
        assertTrue(missingEdge.message.contains('missing stage dependency'))
        assertEquals(0, effects)
    }

    @ParameterizedTest
    @ValueSource(strings = ['neoforge-1.21.11', 'neoforge-26.1', 'neoforge-26.2', 'neoforge-26.3'])
    void connectedContextRosterIsExact(String target) {
        Map fixture = runtimeFixture(target)
        List checks = []
        [false, true].each { boolean isolated ->
            Map receipt = isolated ? fixture.isolated : fixture.receipt
            receipt.definitionProfiles*.name.each { String profile ->
                ['missing', 'duplicate', 'extra'].each { String mutation ->
                    checks.add({ ->
                        rejectsMutation(fixture, isolated, profile + '/' + mutation, 'connected invocation roster') { Map changed ->
                            Map command = changed.commands.find { it.name == 'connected-client-' + profile }
                            if (mutation == 'missing') changed.commands.remove(command)
                            else changed.commands.add(new LinkedHashMap(command) + (mutation == 'extra' ? [name: command.name + '-unexpected'] : [:]))
                        }
                    } as org.junit.jupiter.api.function.Executable)
                }
            }
        }
        assertAll(checks)
    }

    @ParameterizedTest
    @ValueSource(strings = ['neoforge-1.21.11', 'neoforge-26.1', 'neoforge-26.2', 'neoforge-26.3'])
    void everyConnectedContextNeedsItsOwnPhysicalEvidence(String target) {
        Map fixture = runtimeFixture(target)
        List checks = []
        [false, true].each { boolean isolated ->
            Map receipt = isolated ? fixture.isolated : fixture.receipt
            receipt.definitionProfiles*.name.each { String profile ->
                Map mutations = [
                        exit: ['guard process not successful/closed', { Map c -> c.exit = 1 }],
                        expectedExit: ['guard process not successful/closed', { Map c -> c.expectedExit = 1 }],
                        reaped: ['guard process not successful/closed', { Map c -> c.reaped = false }],
                        failure: ['guard process not successful/closed', { Map c -> c.failureClass = 'unit.SetupFailure' }],
                        marker: ['guard process not successful/closed', { Map c -> c.marker = 'CANONICAL_CASE_PASS' }],
                        hash: ['guard log binding', { Map c -> c.sha256 = '0' * 64 }],
                        missingLog: ['guard log binding', { Map c -> new File(c.log).delete() }],
                        changedLog: ['guard log binding', { Map c -> new File(c.log).append('unit-corruption\n') }],
                        close: ['loader close not observed', { Map c -> rewriteLog(c) { it.findAll { it != 'CANONICAL_CLIENT_CLOSED_PASS' } } }]]
                if (profile in ['positive', 'guards']) {
                    List roster = isolated ? WaveyCompositionGuards.CONNECTED : WaveyCompositionGuards.COMPATIBLE
                    roster.each { String guard ->
                        ['CANONICAL_CASE_PASS guard-', 'CANONICAL_CASE_RESET_PASS guard-'].each { String prefix ->
                            mutations['missing-' + prefix + guard] = ['guard result roster', { Map c -> rewriteLog(c) { it.findAll { it != prefix + guard } } }]
                            mutations['duplicate-' + prefix + guard] = ['guard result roster', { Map c -> rewriteLog(c) { it + (prefix + guard) } }]
                        }
                    }
                } else {
                    ['CANONICAL_EXPECTED_BEHAVIOR_RED ', 'CANONICAL_CASE_PASS ', 'CANONICAL_CASE_RESET_PASS '].each { String prefix ->
                        ['missing', 'duplicate', 'wrong', 'prefixed'].each { String mutation ->
                            mutations[mutation + '-' + prefix] = ['definition result/log mismatch', { Map c ->
                                rewriteLog(c) { List lines ->
                                    String marker = prefix + profile
                                    if (mutation == 'duplicate') return lines + marker
                                    lines.collectMany { it != marker ? [it] : mutation == 'missing' ? [] : [mutation == 'wrong' ? prefix + 'positive' : '[unit] ' + marker] }
                                }
                            }]
                        }
                    }
                    mutations.setupFailure = ['definition result/log mismatch', { Map c ->
                        rewriteLog(c) { ['unit.SetupFailure', 'CANONICAL_CLIENT_CLOSED_PASS'] }
                    }]
                }
                mutations.each { String name, List mutation ->
                    checks.add({ -> rejectsMutation(fixture, isolated, profile + '/' + name, mutation[0].toString()) { Map changed ->
                        mutation[1].call(changed.commands.find { it.name == 'connected-client-' + profile })
                    } } as org.junit.jupiter.api.function.Executable)
                }
            }
        }
        assertAll(checks)
    }

    @ParameterizedTest
    @ValueSource(strings = ['neoforge-1.21.11', 'neoforge-26.1', 'neoforge-26.2', 'neoforge-26.3'])
    void completionRejectsExtraGuardResultsAndResets(String target) {
        Map fixture = runtimeFixture(target)
        List checks = []
        [false, true].each { boolean isolated ->
            Map receipt = isolated ? fixture.isolated : fixture.receipt
            receipt.definitionProfiles*.name.each { String profile ->
                ['CANONICAL_CASE_PASS guard-', 'CANONICAL_CASE_RESET_PASS guard-'].each { String prefix ->
                    checks.add({ -> rejectsMutation(fixture, isolated, profile + '/' + prefix, 'guard result roster') { Map changed ->
                        rewriteLog(changed.commands.find { it.name == 'connected-client-' + profile }) { it + (prefix + 'unexpected') }
                    } } as org.junit.jupiter.api.function.Executable)
                }
            }
        }
        assertAll(checks)
    }

    @ParameterizedTest
    @ValueSource(strings = ['neoforge-1.21.11', 'neoforge-26.1', 'neoforge-26.2', 'neoforge-26.3'])
    void connectedGuardsAloneNeverCompleteMandatorySafety(String target) {
        def project = registeredProject(target, false) { fail('No runtime in unit test') }
        def guards = project.tasks.named('verifyWaveyCapesGuards').get()
        Map connectedOnly = runtimeReceipt(guards)
        connectedOnly.remove('proofKinds'); connectedOnly.remove('abiProof')
        connectedOnly.commands.removeIf { it.name == 'isolated-production-abi' }
        def failure = assertThrows(IllegalStateException) { guards.completeDirect(connectedOnly) }
        assertTrue(failure.message.contains('mandatory proof kinds'), failure.message)
    }

    @ParameterizedTest
    @ValueSource(strings = ['neoforge-1.21.11', 'neoforge-26.1', 'neoforge-26.2', 'neoforge-26.3'])
    void isolatedProofNeedsExactPhysicalInputsCellsLinkageAndCleanup(String target) {
        Map fixture = runtimeFixture(target)
        Map mutations = [
                kinds: { Map r -> r.proofKinds = ['connected-game-guards'] },
                duplicateKind: { Map r -> r.proofKinds += 'isolated-production-abi' },
                missing: { Map r -> r.remove('abiProof') },
                prepared: { Map r -> r.abiProof.state = 'PREPARED_NOT_STARTED' },
                target: { Map r -> r.abiProof.inputs.target = 'fabric-26.2' },
                input: { Map r -> r.abiProof.binding = '0' * 64 },
                missingCompanion: { Map r -> rewriteAbiLog(r) { it.findAll { !it.startsWith('ABI_NESTED ') } } },
                missingDefinition: { Map r -> rewriteAbiLog(r) { it.findAll { !it.contains('ABI_DEFINE supported com.naocraftlab.skins.compat.waveycapes.WaveyCapesIntegration ') } } },
                provenance: { Map r -> rewriteAbiLog(r) { it.collect { it.startsWith('ABI_DEFINE ') ? it.replace(' production ', ' parent-resource ') : it } } },
                guardian: { Map r -> rewriteAbiLog(r) { ['IllegalAccessError guardian setup', 'ISOLATED_PRODUCTION_ABI_CLOSED_PASS'] } },
                noLinkage: { Map r -> rewriteAbiLog(r) { it.findAll { !it.startsWith('ABI_PRODUCTION_LINKAGE_PASS ') } } },
                noTerminal: { Map r -> rewriteAbiLog(r) { it.findAll { !it.startsWith('ABI_TERMINAL ') } } },
                reversedCells: { Map r -> rewriteAbiLog(r) { lines ->
                    def pass = lines.find { it == 'ABI_CELL_PASS supported' }
                    lines.remove(pass); lines.add(1, pass); lines
                } },
                earlyClose: { Map r -> rewriteAbiLog(r) { lines ->
                    lines.remove('ISOLATED_PRODUCTION_ABI_CLOSED_PASS'); lines.add(0, 'ISOLATED_PRODUCTION_ABI_CLOSED_PASS'); lines
                } },
                earlyCleanup: { Map r -> rewriteAbiLog(r) { lines ->
                    lines.remove('ABI_CELL_CLEANUP_PASS supported'); lines.add(0, 'ABI_CELL_CLEANUP_PASS supported'); lines
                } },
                extraMutation: { Map r -> rewriteAbiLog(r) { it + 'ABI_MUTATION unsupported extra name ()V 1 bad bad' } },
                setup: { Map r -> r.commands.find { it.name == 'isolated-production-abi' }.failureClass = 'unit.SetupFailure' },
                exit: { Map r -> r.commands.find { it.name == 'isolated-production-abi' }.exit = 1 },
                reaped: { Map r -> r.commands.find { it.name == 'isolated-production-abi' }.reaped = false },
                staleLog: { Map r -> r.commands.find { it.name == 'isolated-production-abi' }.sha256 = '0' * 64 },
                extraCommand: { Map r -> r.commands.add(r.commands.find { it.name == 'isolated-production-abi' }) }]
        ['ABI_CELL_PASS ', 'ABI_CELL_CLEANUP_PASS ', 'ABI_CELL_CLOSED '].each { prefix ->
            WaveyCompositionGuards.ABI_CELLS.each { cell ->
                mutations['missing-' + prefix + cell] = { Map r -> rewriteAbiLog(r) { it.findAll { it != prefix + cell } } }
                mutations['duplicate-' + prefix + cell] = { Map r -> rewriteAbiLog(r) { it + (prefix + cell) } }
            }
        }
        mutations.each { label, mutation ->
            Map stage = new JsonSlurper().parseText(groovy.json.JsonOutput.toJson(fixture.isolated)) as Map
            Map<File, byte[]> logs = stage.commands.findAll { it.log }.collectEntries { [(new File(it.log)): new File(it.log).bytes] }
            try {
                mutation.call(stage)
                assertThrows(IllegalStateException, { fixture.guards.completeDirect(stage) }, label)
            } finally { logs.each { file, bytes -> file.bytes = bytes } }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ['neoforge-1.21.11', 'neoforge-26.1', 'neoforge-26.2', 'neoforge-26.3'])
    void completionAcceptsActualProducerOrderAndRejectsBrokenPhysicalLifecycle(String target) {
        Map fixture = runtimeFixture(target)
        Map mutations = [
                resetBeforePass: ['guard PASS/RESET ordering', { List lines -> Collections.swap(lines, 0, 1); lines }],
                mismatchedReset: ['guard PASS/RESET ordering', { List lines -> Collections.swap(lines, 1, 3); lines }],
                overlappingCases: ['guard PASS/RESET ordering', { List lines -> Collections.swap(lines, 1, 2); lines }],
                earlyBridge: ['bridge must be last', { List lines -> lines[2..3] + lines[0..1] + lines[4..-1] }],
                caseAfterBridge: ['bridge must be last', { List lines -> lines[0..3] +
                        ['CANONICAL_CASE_PASS late-native-case', 'CANONICAL_CASE_RESET_PASS late-native-case'] + lines[4..-1] }]]
        mutations.each { label, mutation ->
            rejectsMutation(fixture, false, label, mutation[0]) { Map changed ->
                rewriteLog(changed.commands.find { it.name == 'connected-client-positive' }, mutation[1])
            }
        }
        rejectsMutation(fixture, true, 'closed-guard-before-remaining-cases', 'guard case ordering') { Map changed ->
            rewriteLog(changed.commands.find { it.name == 'connected-client-guards' }) { List lines ->
                lines[4..5] + lines[0..3] + lines[6..-1]
            }
        }
    }

    private static void rewriteAbiLog(Map receipt, Closure mutation) {
        rewriteLog(receipt.commands.find { it.name == 'isolated-production-abi' }, mutation)
    }

    private def registeredProject(String target, boolean prepare, Closure effect) {
        File repository = new File('../..').canonicalFile
        java.nio.file.Files.createSymbolicLink(new File(temporary, 'gradle').toPath(), new File(repository, 'gradle').toPath())
        java.nio.file.Files.createSymbolicLink(new File(temporary, 'compat').toPath(), new File(repository, 'compat').toPath())
        Map catalog = new JsonSlurper().parse(new File(repository, 'gradle/targets.json')) as Map
        Map spec = catalog.targets.find { it.id == target }
        File directory = new File(temporary, spec.path.toString()); directory.mkdirs()
        def project = ProjectBuilder.builder().withProjectDir(directory).build()
        project.pluginManager.apply('java')
        project.configurations.create('cpmCompositionRuntime')
        project.configurations.create('nclskinsSqliteClientRuntime')
        project.ext.targetSpec = spec
        project.ext.targetCatalog = catalog
        project.ext.nclskinsCatalogTools = CatalogTools
        if (prepare) project.ext.nclskinsWaveyLoaderPrepareOnly = 'true'
        project.tasks.register('prepareWaveyVerificationRun') {
            ext.vmArgsFile = project.layout.buildDirectory.file('fixture/vm.args')
            ext.programArgsFile = project.layout.buildDirectory.file('fixture/program.args')
            doLast { effect.call() }
        }
        project.tasks.register('runWaveyVerification') {
            ext.classpathProvider = project.files()
            doLast { effect.call() }
        }
        project.apply(from: new File(repository, 'gradle/waveycapes-conventions.gradle'))
        project
    }

    private Map runtimeFixture(String target) {
        def project = registeredProject(target, false) { fail('Unit admission must not execute preparation or runtime tasks') }
        def composition = project.tasks.named('verifyWaveyCapesComposition').get()
        def guards = project.tasks.named('verifyWaveyCapesGuards').get()
        Map isolated = runtimeReceipt(guards)
        Map receipt = runtimeReceipt(composition)
        assertEquals(target == 'neoforge-1.21.11' ? 2 : 3, receipt.definitionProfiles.size())
        assertEquals(1, isolated.definitionProfiles.size())
        assertFalse(composition.probeSources.empty)
        assertFalse(composition.loaderSources.empty)
        assertFalse(composition.mixinConfigurations.empty)
        assertEquals('PASS', guards.completeDirect(isolated))
        isolated.result = 'PASS'
        File file = composition.guardReceipt.get().asFile
        file.parentFile.mkdirs()
        file.text = groovy.json.JsonOutput.toJson(isolated)
        assertEquals('PASS', composition.completeDirect(receipt))
        [composition: composition, guards: guards, receipt: receipt, isolated: isolated]
    }

    private Map runtimeReceipt(VerifyWaveyCapesCompositionTask task) {
        List profiles = task.guardOnly.get() ? ['guards'] : task.submissionPreview.get() ? ['positive', 'missing-world'] : ['positive', 'missing-world', 'missing-extraction']
        List roster = task.guardOnly.get() ? WaveyCompositionGuards.CONNECTED : WaveyCompositionGuards.COMPATIBLE
        List producerOrder = task.guardOnly.get() ? WaveyCompositionGuards.CONNECTED :
                (new File('../..', 'gradle/build-logic/src/waveyLoaderComposition/game/java/com/naocraftlab/skins/buildlogic/probe/wavey/ClientCapeBatch.java').text.split(/return;\s*}/, 2).last()
                        =~ /runCase\("guard-([^"\r\n]+)"/).collect { it[1] }
        if (!task.guardOnly.get()) assertEquals(['no-support-sensitivity', 'bridge-contract'], producerOrder)
        Map receipt = [target: task.targetId.get(), mode: task.mode.get(), submissionPreview: task.submissionPreview.get(),
                javaRelease: task.javaRelease.get(), runtimeProfile: task.runtimeProfile.get(), guardOnly: task.guardOnly.get(),
                guardRoster: task.guardRoster.get(), sources: task.probeSources.files.collectEntries { [(it.absolutePath): hash(it)] },
                loaderSources: task.loaderSources.files.collectEntries { [(it.absolutePath): hash(it)] },
                mixins: task.mixinConfigurations.files.collectEntries { [(it.name): hash(it)] },
                compiler: [unitOnly: true], java: [unitOnly: true], classpath: [], compilerClasspath: [], resourceClasspath: [],
                abiSdkPin: task.abiSdkPin.get(), loaderRuntimeClasspath: [], loaderVmArgs: [:], loaderProgramArgs: [:],
                definitionProfiles: profiles.collect { [name: it, state: 'PASS'] }, executedFmlContexts: profiles.size(),
                guardResults: roster.collectEntries { [(it): 'PASS'] }, commands: [[name: 'compile-game']]]
        profiles.each { String profile ->
            List lines = profile in ['positive', 'guards'] ? producerOrder.collectMany {
                ['CANONICAL_CASE_PASS guard-' + it, 'CANONICAL_CASE_RESET_PASS guard-' + it]
            } : ['CANONICAL_EXPECTED_BEHAVIOR_RED ' + profile, 'CANONICAL_CASE_PASS ' + profile, 'CANONICAL_CASE_RESET_PASS ' + profile]
            File log = new File(task.workDirectory.get().asFile, 'connected-client-' + profile + '.log')
            log.parentFile.mkdirs()
            log.setText((lines + 'CANONICAL_CLIENT_CLOSED_PASS').join('\n') + '\n', 'UTF-8')
            receipt.commands.add([name: 'connected-client-' + profile, exit: 0, expectedExit: 0, reaped: true,
                    failureClass: null, marker: 'CANONICAL_CLIENT_CLOSED_PASS', log: log.absolutePath, sha256: hash(log)])
        }
        if (task.guardOnly.get()) addUnitAbiEvidence(task, receipt)
        receipt
    }

    private static byte[] unitClass(String name, String method = null, String descriptor = '()V', boolean terminal = false) {
        ClassNode node = new ClassNode(); node.version = Opcodes.V17; node.access = Opcodes.ACC_PUBLIC
        node.name = name; node.superName = 'java/lang/Object'
        if (method != null) {
            MethodNode member = new MethodNode(Opcodes.ACC_PUBLIC | (terminal ? Opcodes.ACC_STATIC : 0), method, descriptor, null, null)
            if (terminal) { member.instructions.add(new InsnNode(Opcodes.ICONST_0)); member.instructions.add(new InsnNode(Opcodes.IRETURN)); member.maxStack = 1 }
            node.methods.add(member)
        }
        ClassWriter writer = new ClassWriter(0); node.accept(writer); writer.toByteArray()
    }

    private void addUnitAbiEvidence(VerifyWaveyCapesCompositionTask task, Map receipt) {
        String own = 'com/naocraftlab/skins/compat/waveycapes/'
        String thread = own + 'CapeRenderThread'
        Map selectors = ['missing-getCapePasses': ['dev/tr7zw/waveycapes/render/CapeRenderer', 'getCapePasses', '(Ldev/tr7zw/transition/mc/entitywrapper/PlayerWrapper;)Ljava/util/List;'],
                         'missing-wrapper': ['dev/tr7zw/waveycapes/render/CapePass', 'wrapper', '()Ljava/util/function/UnaryOperator;']]
        Map<String, byte[]> production = [(thread + '.class'): unitClass(thread, 'isClientThread', '()Z', true),
                (own + 'WaveyCapesIntegration.class'): unitClass(own + 'WaveyCapesIntegration'),
                (own + 'WaveyCapesSupport.class'): unitClass(own + 'WaveyCapesSupport')]
        Map<String, byte[]> sdk = selectors.values().collectEntries { [(it[0] + '.class'): unitClass(it[0], it[1], it[2])] }
        sdk['dev/tr7zw/waveycapes/support/SupportManager.class'] = unitClass('dev/tr7zw/waveycapes/support/SupportManager')
        File directory = new File(task.workDirectory.get().asFile, 'synthetic-unit-only'); directory.mkdirs()
        File nested = new File(directory, 'TRansition-unit.jar')
        String player = 'dev/tr7zw/transition/mc/entitywrapper/PlayerWrapper'
        byte[] playerBytes = unitClass(player)
        WaveyLoaderComposition.archive(nested, [(player + '.class'): playerBytes])
        File render = new File(directory, 'TRender-unit.jar'); WaveyLoaderComposition.archive(render, ['unit-only': new byte[0]])
        File ownJar = new File(directory, 'production-unit.jar'), sdkJar = new File(directory, 'sdk-unit.jar')
        WaveyLoaderComposition.archive(ownJar, production)
        WaveyLoaderComposition.archive(sdkJar, sdk + [('TRansition-unit.jar'): nested.bytes, ('TRender-unit.jar'): render.bytes])
        Map inputs = WaveyCompositionGuards.DIRECT_BINDINGS.collectEntries { [(it): receipt[it]] }
        inputs.production = [path: ownJar.absolutePath, sha256: hash(ownJar)]
        inputs.sdk = [path: sdkJar.absolutePath, sha256: hash(sdkJar)]
        String binding = WaveyCompositionGuards.bindingHash(inputs)
        Properties properties = new Properties(); properties.setProperty('binding', binding); properties.setProperty('version', receipt.abiSdkPin.version)
        ['production', 'sdk'].each { key -> properties.setProperty(key + '.path', inputs[key].path); properties.setProperty(key + '.sha256', inputs[key].sha256) }
        File bindings = new File(directory, 'bindings.properties'); bindings.withWriter('UTF-8') { properties.store(it, null) }
        List argv = ['unit-java', '--isolated', bindings.absolutePath]
        File init = new File(directory, 'class-init.log'); init.text = 'Synthetic unit-only class-init log; no JVM execution\n'
        receipt.proofKinds = ['isolated-production-abi', 'connected-game-guards']
        receipt.abiProof = [kind: 'isolated-production-abi', state: 'PASS', inputs: inputs, binding: binding,
                invocation: [argv: argv], bindings: [path: bindings.absolutePath, sha256: hash(bindings)],
                executedAbiJvms: 1, classInit: init.absolutePath, classInitSha256: hash(init)]
        List lines = ['ABI_BINDING_PASS ' + binding]
        WaveyCompositionGuards.ABI_CELLS.each { cell ->
            [nested, render].each { lines.add('ABI_NESTED ' + cell + ' ' + it.name + ' ' + hash(it)) }
            Map<String, byte[]> original = production + sdk + [(player + '.class'): playerBytes]
            if (cell == 'missing-getCapePasses') original.remove(thread + '.class')
            if (cell == 'missing-wrapper') original.remove(player + '.class')
            original.each { resource, bytes ->
                byte[] chosen = bytes
                if (resource == thread + '.class' || selectors.containsKey(cell) && resource == selectors[cell][0] + '.class') {
                    boolean terminal = resource == thread + '.class'
                    List selector = terminal ? [thread, 'isClientThread', '()Z'] : selectors[cell]
                    ClassNode node = new ClassNode(); new ClassReader(bytes).accept(node, 0)
                    def method = node.methods.find { it.name == selector[1] && it.desc == selector[2] }
                    if (terminal) { method.instructions.clear(); method.instructions.add(new InsnNode(Opcodes.ICONST_1)); method.instructions.add(new InsnNode(Opcodes.IRETURN)) }
                    else node.methods.remove(method)
                    ClassWriter writer = new ClassWriter(0); node.accept(writer); chosen = writer.toByteArray()
                    lines.add((terminal ? 'ABI_TERMINAL ' : 'ABI_MUTATION ') + cell + ' ' + selector.join(' ') + ' 1 ' + WaveyCompositionGuards.byteHash(bytes) + ' ' + WaveyCompositionGuards.byteHash(chosen))
                }
                String origin = production.containsKey(resource) ? 'production' : sdk.containsKey(resource) ? 'sdk' : 'nested:' + nested.name
                lines.add('ABI_DEFINE ' + cell + ' ' + resource.replace('/', '.').replace('.class', '') + ' ' + origin + ' ' + WaveyCompositionGuards.byteHash(bytes) + ' ' + WaveyCompositionGuards.byteHash(chosen))
            }
            if (cell != 'supported') {
                lines.add('ABI_MISSING_MEMBER_PASS ' + cell)
                lines.add('ABI_PRODUCTION_LINKAGE_PASS ' + cell + ' NoSuchMethodError ' + (cell == 'missing-wrapper' ? 'dev.tr7zw.waveycapes.render.CapePass.wrapper()' : 'dev.tr7zw.waveycapes.render.CapeRenderer.getCapePasses(dev.tr7zw.transition.mc.entitywrapper.PlayerWrapper)'))
            }
            lines.addAll(['ABI_CELL_PASS ', 'ABI_CELL_CLEANUP_PASS ', 'ABI_CELL_CLOSED '].collect { it + cell })
        }
        lines.add('ISOLATED_PRODUCTION_ABI_CLOSED_PASS')
        File log = new File(directory, 'isolated-unit.log'); log.text = lines.join('\n') + '\n'
        receipt.commands.add([name: 'isolated-production-abi', argv: argv, exit: 0, expectedExit: 0, reaped: true, failureClass: null,
                marker: 'ISOLATED_PRODUCTION_ABI_CLOSED_PASS', log: log.absolutePath, sha256: hash(log)])
    }

    private void rejectsMutation(Map fixture, boolean isolated, String label, String reason, Closure mutation) {
        Map receipt = new JsonSlurper().parseText(groovy.json.JsonOutput.toJson(fixture.receipt)) as Map
        Map stage = new JsonSlurper().parseText(groovy.json.JsonOutput.toJson(fixture.isolated)) as Map
        Map<File, byte[]> logs = (receipt.commands + stage.commands).findAll { it.log }.collectEntries { [(new File(it.log)): new File(it.log).bytes] }
        File stageFile = fixture.composition.guardReceipt.get().asFile
        try {
            stageFile.text = groovy.json.JsonOutput.toJson(stage)
            assertEquals('PASS', fixture.guards.completeDirect(stage))
            assertEquals('PASS', fixture.composition.completeDirect(receipt))
            mutation.call(isolated ? stage : receipt)
            stageFile.text = groovy.json.JsonOutput.toJson(stage)
            def failure = assertThrows(IllegalStateException, { fixture.composition.completeDirect(receipt) }, label)
            assertTrue(failure.message.contains(reason), label + ': ' + failure.message)
        } finally {
            logs.each { File file, byte[] bytes -> file.bytes = bytes }
            stageFile.text = groovy.json.JsonOutput.toJson(fixture.isolated)
        }
    }

    private static void rewriteLog(Map command, Closure mutation) {
        File file = new File(command.log)
        file.setText(mutation.call(file.readLines('UTF-8')).join('\n') + '\n', 'UTF-8')
        command.sha256 = hash(file)
    }

    private static String hash(File file) {
        java.security.MessageDigest.getInstance('SHA-256').digest(file.bytes).encodeHex().toString()
    }
}

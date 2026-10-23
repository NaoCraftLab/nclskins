package com.naocraftlab.skins.buildlogic

import groovy.json.JsonSlurper
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.junit.jupiter.api.Test
import static org.junit.jupiter.api.Assertions.*

class WaveyCapesLegacyRegistrationTest {
    @TempDir File temporary

    @ParameterizedTest
    @ValueSource(strings = ['forge-1.20.1', 'neoforge-1.21.1'])
    void realConventionSelectsBothMandatoryStagesWithoutProfileOverride(String target) {
        File repository = new File('../..').canonicalFile
        java.nio.file.Files.createSymbolicLink(new File(temporary, 'gradle').toPath(), new File(repository, 'gradle').toPath())
        java.nio.file.Files.createSymbolicLink(new File(temporary, 'compat').toPath(), new File(repository, 'compat').toPath())
        Map catalog = new JsonSlurper().parse(new File(repository, 'gradle/targets.json'))
        Map spec = catalog.targets.find { it.id == target }
        File directory = new File(temporary, spec.path.toString()); directory.mkdirs()
        def project = ProjectBuilder.builder().withProjectDir(directory).build()
        project.pluginManager.apply('java')
        ['cpmCompositionRuntime', 'nclskinsSqliteClientRuntime'].each { project.configurations.create(it) }
        project.ext.targetSpec = spec; project.ext.targetCatalog = catalog; project.ext.nclskinsCatalogTools = CatalogTools
        project.ext.nclskinsWaveyLoaderPrepareOnly = 'true'
        if (target.startsWith('forge-')) {
            project.ext.fg = [deobf: { it }]
            project.ext.minecraft = [mappings: project.providers.provider { 'synthetic-unit-mappings' }]
        }
        int effects = 0
        ['prepareWaveyVerificationRun', 'prepareWaveyLegacyProvider'].each { name ->
            project.tasks.register(name) {
                ext.vmArgsFile = project.layout.buildDirectory.file('fixture/vm.args')
                ext.programArgsFile = project.layout.buildDirectory.file('fixture/program.args')
                ext.waveyRuntimeFile = project.layout.buildDirectory.file('fixture/wavey-runtime.jar')
                ext.generatedMixinRefmapFile = project.layout.buildDirectory.file('fixture/generated.refmap.json')
                doLast { effects++ }
            }
        }
        project.tasks.register('runWaveyVerification') {
            ext.classpathProvider = project.files(); ext.minecraftArtifacts = project.files(); ext.runtimeClasspathArtifacts = project.files()
            doLast { effects++ }
        }
        project.apply(from: new File(repository, 'gradle/waveycapes-conventions.gradle'))
        def task = project.tasks.named('verifyWaveyCapesComposition').get()
        def guards = project.tasks.named('verifyWaveyCapesGuards').get()
        assertInstanceOf(VerifyWaveyLegacyCompositionTask, task)
        assertEquals('legacy-client-' + task.mode.get(), task.runtimeProfile.get())
        assertTrue(task.prepareOnly.get() && guards.prepareOnly.get())
        assertEquals(WaveyCompositionGuards.ISOLATED, guards.guardRoster.get())
        assertTrue(task.taskDependencies.getDependencies(task).contains(guards))
        assertEquals(guards.workDirectory.file('result.json').get().asFile, task.guardReceipt.get().asFile)
        assertEquals(task.probeSources.files, guards.probeSources.files)
        assertEquals(task.loaderSources.files, guards.loaderSources.files)
        assertTrue(task.probeSources.files*.name.containsAll(['CapeRegistrationFailureProbe.java', 'CapeUnsupportedAbiProbe.java', 'ImmediateGuardProbe.java', 'ImmediatePublicPreviewProbe.java', 'ImmediatePreviewInputs.java', 'ImmediatePreviewBindings.java']))
        assertFalse(task.probeSources.files*.name.any { it in ['CapeGuardProbe.java', 'CapeCallbackLinkageProbe.java', 'CapeClosedLegacyProbe.java'] })
        assertTrue(task.loaderSources.files*.name.containsAll(['LegacyClientLaunchHandler.java', 'LegacyGameService.java', 'LegacyCapeTerminalPlugin.java', 'LegacyCapeBatch.java', 'LegacyTerminalContract.java']))
        def check = project.tasks.named('check').get()
        assertTrue(check.taskDependencies.getDependencies(check).containsAll([task, guards]))
        assertEquals(0, effects)
        assertThrows(IllegalStateException) { task.completeDirect([executedFmlContexts: 0]) }
    }

    @ParameterizedTest
    @ValueSource(strings = ['rendered-buffer', 'mesh'])
    void immediateAdmissionRequiresExactlyOneDeclaredPositiveContext(String mode) {
        File log = new File(temporary, 'connected.log')
        List roster = WaveyCompositionGuards.COMPATIBLE
        File source = new File('../..', 'gradle/build-logic/src/waveyLoaderComposition/legacy-game/java/com/naocraftlab/skins/buildlogic/probe/wavey/LegacyCapeBatch.java')
        List order = (source.text.split(/return;\s*}/, 2).last() =~ /runCase\("guard-([^"\r\n]+)"/).collect { it[1] }
        assertEquals(['no-support-sensitivity', 'bridge-contract'], order)
        log.text = (order.collectMany { ['CANONICAL_CASE_PASS guard-' + it, 'CANONICAL_CASE_RESET_PASS guard-' + it] }
                + 'CANONICAL_CLIENT_CLOSED_PASS').join('\n') + '\n'
        Map command = [name: 'connected-client-positive', exit: 0, expectedExit: 0, reaped: true, failureClass: null,
                marker: 'CANONICAL_CLIENT_CLOSED_PASS', log: log.absolutePath,
                sha256: java.security.MessageDigest.getInstance('SHA-256').digest(log.bytes).encodeHex().toString()]
        Map receipt = [mode: mode, submissionPreview: false, guardResults: roster.collectEntries { [(it): 'PASS'] },
                definitionProfiles: [[name: 'positive', state: 'PASS']], executedFmlContexts: 1, commands: [command]]
        WaveyCompositionGuards.verifyEvidence(receipt, roster, 'positive')
        assertThrows(IllegalStateException) { WaveyCompositionGuards.verifyEvidence(receipt + [definitionProfiles: receipt.definitionProfiles + [name: 'missing-world', state: 'PASS']], roster, 'positive') }
        assertThrows(IllegalStateException) { WaveyCompositionGuards.verifyEvidence(receipt + [executedFmlContexts: 0], roster, 'positive') }
        assertThrows(IllegalStateException) { WaveyCompositionGuards.verifyEvidence(receipt + [commands: [command, command]], roster, 'positive') }
    }

    @Test
    void legacyEnvironmentUsesTheUnchangedRealProcessWrapper() {
        def project = ProjectBuilder.builder().withProjectDir(temporary).build()
        def task = project.tasks.create('legacyProcess', VerifyWaveyLegacyCompositionTask)
        task.targetId.set('synthetic-unit')
        task.providerEnvironment.set([MOD_CLASSES: 'synthetic-mod-folder', MCP_MAPPINGS: 'synthetic-mappings'])
        Map receipt = [commands: []]
        task.runCompositionProcess(temporary, receipt, { }, 'provider-environment',
                ['/bin/sh', '-c', 'test "$MOD_CLASSES" = synthetic-mod-folder && test "$MCP_MAPPINGS" = synthetic-mappings && printf CANONICAL_ENV_PASS'], 0, 'CANONICAL_ENV_PASS')
        assertEquals(1, receipt.commands.size())
        assertEquals(0, receipt.commands[0].exit)
        assertTrue(receipt.commands[0].reaped)
        task.providerEnvironment.put('UNSAFE', 'synthetic')
        assertThrows(IllegalArgumentException) {
            task.runCompositionProcess(temporary, receipt, { }, 'unsafe-environment', ['/bin/sh', '-c', 'exit 99'])
        }
        assertFalse(new File(temporary, 'unsafe-environment.log').exists())
    }

    private Map remapProvider(File mapping) {
        [mainClass: 'cpw.mods.bootstraplauncher.BootstrapLauncher', args: [], jvmArgs: [], classpath: [],
         properties: ['mixin.env.remapRefMap': 'true', 'mixin.env.refMapRemappingFile': mapping.absolutePath,
                      'net.minecraftforge.gradle.GradleStart.srg.srg-mcp': mapping.absolutePath], environment: [:]]
    }

    @Test
    void nativeRemapPropertiesSurviveBothSanitizationStagesWithoutChangingLiteralPaths() {
        File mapping = new File(temporary, '  café native:map;srg  ')
        mapping.setText('CL: owner/Record owner/Record\nFD: owner/Entity/f_19825_ owner/Entity/position\nMD: owner/Player/m_8119_ ()V owner/Player/tick ()V\nMD: owner/Record/f_285580_ ()Ljava/util/Map; owner/Record/advances ()Ljava/util/Map;\nMD: owner/Record/<init> ()V owner/Record/<init> ()V\n', 'UTF-8')
        Map input = remapProvider(mapping)
        input.properties['mixin.env.disableRefMap'] = 'true'
        Map first = WaveyLegacyComposition.safeProvider(input)
        assertEquals(remapProvider(mapping).properties, first.properties)
        assertEquals(first, WaveyLegacyComposition.safeProvider(first))
        assertEquals(first, WaveyLegacyComposition.safeProvider(input, mapping))
        assertThrows(IllegalArgumentException) { WaveyLegacyComposition.safeProvider(input, new File(temporary, 'unrelated.srg')) }
        assertTrue(first.jvmArgs.isEmpty())
        remapProvider(mapping).properties.each { key, value ->
            assertThrows(IllegalArgumentException) { WaveyLegacyComposition.safeProvider(input + [jvmArgs: ['-D' + key + '=' + value]]) }
        }
        assertTrue(mapping.delete())
        assertThrows(IllegalArgumentException) { WaveyLegacyComposition.safeProvider(first) }
    }

    @ParameterizedTest
    @ValueSource(strings = ['missing', 'empty', 'tsrg2', 'csv', 'reverse', 'malformed', 'descriptor', 'owner'])
    void nativeRemapRejectsMissingOrNonSrgToNamedMappingBeforeBootstrap(String kind) {
        File mapping = new File(temporary, 'mapping.srg')
        Map bodies = [empty: '', tsrg2: 'tsrg2 left right\nowner/Entity owner/Entity\n', csv: 'searge,name,side,desc\nf_19825_,position,2,\n',
                reverse: 'FD: owner/Entity/position owner/Entity/f_19825_\n', malformed: 'FD: owner/Entity/f_19825_\n',
                descriptor: 'MD: owner/Player/m_8119_ ()V owner/Player/tick ()I\n', owner: 'FD: owner/Entity/f_19825_ owner/Screen/position\n']
        if (kind != 'missing') mapping.setText(bodies[kind], 'UTF-8')
        assertThrows(IllegalArgumentException) { WaveyLegacyComposition.safeProvider(remapProvider(mapping)) }
    }

    @Test
    void nativeRemapRejectsPartialTypedAndConflictingSettingsWithoutRepairingValues() {
        File mapping = new File(temporary, 'mapping.srg')
        mapping.text = 'FD: owner/Entity/f_19825_ owner/Entity/position\n'
        Map input = remapProvider(mapping)
        ['false', 'TRUE', ' true', true, null].each { value ->
            assertThrows(IllegalArgumentException) { WaveyLegacyComposition.safeProvider(input + [properties: input.properties + ['mixin.env.remapRefMap': value]]) }
        }
        input.properties.keySet().each { key ->
            Map partial = new LinkedHashMap(input.properties); partial.remove(key)
            assertThrows(IllegalArgumentException) { WaveyLegacyComposition.safeProvider(input + [properties: partial]) }
        }
        assertThrows(IllegalArgumentException) { WaveyLegacyComposition.safeProvider(input + [properties: input.properties + ['net.minecraftforge.gradle.GradleStart.srg.srg-mcp': mapping.absolutePath + '.other']]) }
        [new File('relative.srg'), 1, null, mapping.absolutePath + '\n'].each { value ->
            assertThrows(IllegalArgumentException) { WaveyLegacyComposition.safeProvider(input + [properties: input.properties + ['mixin.env.refMapRemappingFile': value]]) }
        }
    }

    @Test
    void forgeAssemblyRequiresTheMappingContractBeforeAnyCompileOrProcess() {
        def project = ProjectBuilder.builder().withProjectDir(temporary).build()
        def task = project.tasks.create('legacyMappingPrerequisite', VerifyWaveyLegacyCompositionTask)
        task.mode.set('rendered-buffer'); task.javaRelease.set(17); task.prepareOnly.set(true)
        File provider = new File(temporary, 'provider.json')
        task.loaderProgramArgs.set(provider)
        File work = new File(temporary, 'unreached-assembly')
        Map input = remapProvider(new File(temporary, 'missing.srg'))
        [input + [properties: [:]], input].each { invalid ->
            provider.text = groovy.json.JsonOutput.toJson(invalid)
            assertThrows(IllegalArgumentException) {
                WaveyLegacyComposition.verify(task, work, [], [], [], [:], { Object... args -> fail('No compile or process before native mapping validation') }, { })
            }
            assertFalse(work.exists())
        }
    }

    @Test
    void nativeMappingGenerationIsAnExplicitPreparationInputWithoutRunningTheProvider() {
        File repository = new File('../..').canonicalFile
        def project = ProjectBuilder.builder().withProjectDir(temporary).build()
        int effects = 0
        project.ext.forgeMixinRefmap = 'fixture.refmap.json'
        def resources = project.tasks.register('processForgeMixinRuntimeResources', org.gradle.language.jvm.tasks.ProcessResources) {
            destinationDir = new File(temporary, 'native-resources'); doLast { effects++ }
        }
        File mapping = new File(temporary, 'native-createSrgToMcp/output.srg')
        def nativeMapping = project.tasks.register('createSrgToMcp') { outputs.file(mapping); doLast { effects++ } }
        def nativePrepare = project.tasks.register('prepareRunWaveyVerification') { doLast { effects++ } }
        String convention = new File(repository, 'gradle/loader-conventions/forge.gradle').text
        int start = convention.indexOf("    tasks.register('prepareWaveyVerificationRun')")
        File script = new File(temporary, 'capture.gradle')
        script.text = convention.substring(start, convention.lastIndexOf("\n}\napply from:"))
        project.apply(from: script)
        def prepare = project.tasks.named('prepareWaveyVerificationRun').get()
        assertTrue(prepare.taskDependencies.getDependencies(prepare).containsAll([nativeMapping.get(), nativePrepare.get()]))
        assertTrue(prepare.inputs.files.files.contains(mapping))
        assertTrue(prepare.taskDependencies.getDependencies(prepare).contains(resources.get()))
        assertEquals(new File(resources.get().destinationDir, 'fixture.refmap.json'), prepare.generatedMixinRefmapFile.get().asFile)
        assertTrue(prepare.inputs.files.files.contains(prepare.generatedMixinRefmapFile.get().asFile))
        assertEquals(0, effects)
        assertFalse(mapping.exists())
    }

    @Test
    void providerRejectsCredentialsAndExpandsOnlyGeneratedPublicArgumentFiles() {
        File args = new File(temporary, 'program.args')
        args.text = '# main\ncpw.mods.bootstraplauncher.BootstrapLauncher\n--launchTarget\nforgeclientdev\n--gameDir\n.\n'
        File vm = new File(temporary, 'vm.args')
        vm.text = '--add-opens\njava.base/java.lang=ALL-UNNAMED\n-DlegacyClassPath.file=synthetic-classpath\n'
        Map input = [mainClass: 'net.neoforged.devlaunch.Main', args: ['@' + args], jvmArgs: ['@' + vm], classpath: [], properties: [:], environment: [:]]
        Map safe = WaveyLegacyComposition.safeProvider(input)
        assertEquals('cpw.mods.bootstraplauncher.BootstrapLauncher', safe.mainClass)
        assertEquals(['--launchTarget', 'forgeclientdev', '--gameDir', '.'], safe.args)
        ['--accessToken', '--username', '--userProperties'].each { option ->
            assertThrows(IllegalArgumentException) { WaveyLegacyComposition.safeProvider(input + [mainClass: safe.mainClass, args: [option, 'not-a-secret']]) }
        }
        assertThrows(IllegalArgumentException) { WaveyLegacyComposition.safeProvider(input + [jvmArgs: ['-DaccessToken=not-a-secret']]) }
    }
}

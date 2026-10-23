package com.naocraftlab.skins.buildlogic

import groovy.json.JsonSlurper
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import static org.junit.jupiter.api.Assertions.*

class WaveyCapesCompositionClasspathTest {
    @TempDir File temporary

    private File artifact(String name, String entry) {
        File file = new File(temporary, name)
        new ZipOutputStream(new FileOutputStream(file)).withCloseable { zip ->
            zip.putNextEntry(new ZipEntry(entry))
            zip.write(new byte[0])
            zip.closeEntry()
        }
        file
    }

    @Test
    void legacyFileUsesBootstrapReadAllLinesInsteadOfJvmClasspathEncoding() {
        File first = artifact('provider-first.jar', 'first/Api.class')
        File second = artifact('provider-second.jar', 'second/Api.class')
        File extra = artifact('runtime.jar', 'runtime/Api.class')
        File provider = new File(temporary, 'provider.txt')
        provider.setText([first.absolutePath, second.absolutePath].join('\n') + '\n', 'UTF-8')
        byte[] original = provider.bytes
        File result = new File(temporary, 'legacy.txt')
        WaveyLegacyComposition.writeLegacyClasspath(provider, result, [second, extra, first, extra])
        List<String> paths = java.nio.file.Files.readAllLines(result.toPath())
        assertEquals([first.absolutePath, second.absolutePath, extra.absolutePath], paths)
        assertTrue(paths.every { new File(it).isFile() })
        assertArrayEquals(original, java.nio.file.Files.readAllBytes(provider.toPath()))
    }

    @Test
    void legacyFilePreservesLiteralSpacesUnicodeAndPathSeparatorsInEntries() {
        File literal = artifact('  cape café:component;value.jar  ', 'literal/Api.class')
        File runtime = artifact('runtime with spaces.jar', 'runtime/Api.class')
        File provider = new File(temporary, 'provider.txt')
        provider.setText(literal.absolutePath + '\r\n', 'UTF-8')
        File result = new File(temporary, 'legacy.txt')
        WaveyLegacyComposition.writeLegacyClasspath(provider, result, [runtime, literal])
        assertEquals([literal.absolutePath, runtime.absolutePath], java.nio.file.Files.readAllLines(result.toPath()))
        assertTrue(java.nio.file.Files.readAllLines(result.toPath()).every { new File(it).isFile() })
    }

    @Test
    void legacyFileDoesNotRepairMalformedJoinedOrBlankProviderEntries() {
        File first = artifact('first.jar', 'first/Api.class')
        File second = artifact('second.jar', 'second/Api.class')
        String joined = first.absolutePath + File.pathSeparator + second.absolutePath
        File provider = new File(temporary, 'provider.txt')
        provider.setText(joined + '\n\nmissing.jar\n', 'UTF-8')
        File result = new File(temporary, 'legacy.txt')
        WaveyLegacyComposition.writeLegacyClasspath(provider, result, [second])
        assertEquals([joined, '', 'missing.jar', second.absolutePath], java.nio.file.Files.readAllLines(result.toPath()))
        assertFalse(new File(joined).exists())
    }

    @Test
    void emptyLegacyProviderKeepsOnlyOrderedRuntimeEntries() {
        File runtime = artifact('runtime.jar', 'runtime/Api.class')
        File provider = new File(temporary, 'provider.txt')
        provider.setText('', 'UTF-8')
        File result = new File(temporary, 'legacy.txt')
        WaveyLegacyComposition.writeLegacyClasspath(provider, result, [runtime, runtime])
        assertEquals([runtime.absolutePath], java.nio.file.Files.readAllLines(result.toPath()))
    }

    private File moduleArtifact(String name, Map<String, byte[]> entries) {
        File file = new File(temporary, name)
        WaveyLoaderComposition.archive(file, entries)
        file
    }

    private static java.lang.module.Configuration configuration(List<File> files, List<java.lang.module.Configuration> parents) {
        def finder = java.lang.module.ModuleFinder.of(files.collect { it.toPath() } as java.nio.file.Path[])
        java.lang.module.Configuration.resolveAndBind(finder, parents, java.lang.module.ModuleFinder.ofSystem(),
                finder.findAll().collect { it.descriptor().name() })
    }

    private static void collision(List<File> boot, List<File> game) {
        def parent = configuration(boot, [java.lang.module.Configuration.empty()])
        def service = configuration([], [parent]), plugin = configuration([], [parent])
        def failure = assertThrows(java.lang.module.ResolutionException) { configuration(game, [plugin, service]) }
        assertTrue(failure.message.contains('reads another module named nclskins'), failure.message)
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = ['properties', 'vm', 'precedence', 'absent', 'empty', 'csv', 'adapter', 'identity', 'generated',
            'missing-adapter', 'nested-missing', 'nested-corrupt', 'common-substitute', 'lost-zip', 'jar-only',
            'wrong-root', 'wrong-namespace', 'empty-maps', 'missing-generated', 'mismatched-generated', 'boot-collateral', 'legacy-claimed'])
    void canonicalLegacyAssemblyPartitionsGameArchivesBeforeBootstrapWithoutLoadingClasses(String carrier) {
        def project = ProjectBuilder.builder().withProjectDir(temporary).build()
        def task = project.tasks.create('legacyAssembly', VerifyWaveyLegacyCompositionTask)
        task.mode.set('rendered-buffer'); task.javaRelease.set(17)
        task.runtimeProfile.set('legacy-client-rendered-buffer'); task.prepareOnly.set(true)
        task.compilerExecutable.set(new File(System.getProperty('java.home'), 'bin/javac'))
        task.javaExecutable.set(new File(System.getProperty('java.home'), 'bin/java'))
        File nativeJar = moduleArtifact('host-input.jar', ['nativeinput/Api.class': new byte[0]])
        File nested = moduleArtifact('nested.jar', ['companion/Api.class': new byte[0]])
        Map waveyEntries = ['dev/tr7zw/waveycapes/render/CapeNodeCollector.class': new byte[0],
                'META-INF/mods.toml': 'modId="waveycapes"\nversion="1.11.3"\n'.bytes,
                'META-INF/jarjar/metadata.json': '{"jars":[]}'.bytes, 'META-INF/jarjar/companion.jar': nested.bytes]
        File wavey = moduleArtifact('waveycapes-named-runtime.jar', waveyEntries)
        File csv = moduleArtifact('native-mappings.zip', ['fields.csv': 'searge,name,side,desc\nf_1_,field,2,\nowner.Enum#PLAY,PLAY,note\n'.bytes,
                'methods.csv': 'searge,name,side,desc\nm_1_,method,2,\nf_2_,recordAccessor,2,\nowner.Record#<init>,<init>,note\n'.bytes])
        File extrasPayload = moduleArtifact('extras-payload.jar', ['injector/wrapoperation/Operation', 'injector/wrapoperation/WrapOperation',
                'injector/wrapmethod/WrapMethod', 'injector/ModifyReturnValue', 'sugar/Local', 'sugar/Share',
                'sugar/ref/LocalBooleanRef', 'MixinExtrasBootstrap'].collectEntries { [('com/llamalad7/mixinextras/' + it + '.class'): new byte[0]] })
        File adapter = moduleArtifact('mixinextras-forge-unit.jar', [
                'META-INF/MANIFEST.MF': 'Manifest-Version: 1.0\nFMLModType: GAMELIBRARY\nMixinConfigs: mixinextras.init.mixins.json\n\n'.bytes,
                'mixinextras.init.mixins.json': '{"plugin":"com.llamalad7.mixinextras.platform.forge.MixinExtrasConfigPlugin"}'.bytes,
                'META-INF/jarjar/metadata.json': '{"jars":[{"identifier":{"group":"com.github.LlamaLad7","artifact":"MixinExtras"},"path":"META-INF/jars/MixinExtras.jar"}]}'.bytes,
                'META-INF/jars/MixinExtras.jar': extrasPayload.bytes])
        task.namedWaveyRuntime.set(wavey); task.loaderRuntimeClasspath.from(nativeJar, csv, adapter)
        File own = new File(temporary, 'own'); own.mkdirs()
        ['META-INF/mods.toml': 'modId="nclskins"\nversion="1.3.0"\n',
         'nclskins.full.mixins.json': '{}', 'com/naocraftlab/skins/runtime/CapeProviderCoordinator.class': ''].each { name, text ->
            File file = new File(own, name); file.parentFile.mkdirs(); file.text = text
        }
        File providerCp = new File(temporary, 'provider-classpath.txt')
        providerCp.setText(nativeJar.absolutePath + '\n', 'UTF-8')
        Map props = ['legacyClassPath.file': providerCp.absolutePath, mergeModules: 'retained-one.jar,retained-two.jar']
        List vm = ['-p', nativeJar.absolutePath, '--add-modules', 'ALL-MODULE-PATH']
        if (carrier in ['properties', 'precedence']) props.ignoreList = 'asm,securejarhandler,inherited-ignore.jar'
        if (carrier in ['vm', 'precedence']) vm += ['-DignoreList=overridden.jar', '-DignoreList=asm,securejarhandler,vm-ignore.jar']
        if (carrier == 'empty') props.ignoreList = ',,'
        Map provider = [mainClass: 'cpw.mods.bootstraplauncher.BootstrapLauncher', args: ['--launchTarget', 'forgeclientdev', '--gameDir', '.'],
                jvmArgs: vm, properties: props, classpath: [nativeJar.absolutePath], environment: [MCP_MAPPINGS: 'retained-mappings']]
        File mapping = new File(temporary, 'native.srg')
        mapping.setText('FD: owner/Entity/f_19825_ owner/Entity/position\nMD: owner/Player/m_8119_ ()V owner/Player/tick ()V\n', 'UTF-8')
        Map remap = ['mixin.env.remapRefMap': 'true', 'mixin.env.refMapRemappingFile': mapping.absolutePath,
                'net.minecraftforge.gradle.GradleStart.srg.srg-mcp': mapping.absolutePath]
        props.putAll(remap)
        Map captured = WaveyLegacyComposition.safeProvider(provider)
        File providerFile = new File(temporary, 'provider.json'); providerFile.text = groovy.json.JsonOutput.toJson(captured)
        task.loaderProgramArgs.set(providerFile)
        byte[] providerBefore = providerFile.bytes
        List<File> sources = ['ImmediateTerminalBindings.java', 'ImmediatePreviewBindings.java'].collect { name ->
            File file = new File(temporary, name); file.text = ''; file
        }
        File generated = new File(temporary, 'generated/nclskins.unit.refmap.json'); generated.parentFile.mkdirs()
        generated.text = '{"mappings":{"com/naocraftlab/skins/Unit":{"field":"f_1_:I"}},"data":{}}'
        new File(own, 'nclskins.full.mixins.json').text = '{"refmap":"nclskins.unit.refmap.json"}'
        if (task.hasProperty('generatedMixinRefmap')) task.generatedMixinRefmap.set(generated)
        Map roleEntries = [boot: ['com/naocraftlab/skins/buildlogic/boot/LegacyClientLaunchHandler.class'],
                spi: ['com/naocraftlab/skins/buildlogic/processor/LegacyCapeTerminalPlugin.class'],
                game: ['com/naocraftlab/skins/runtime/WaveyProducerFixture.class', 'com/naocraftlab/skins/buildlogic/probe/wavey/LegacyCapeService.class']]
        List commands = []
        Closure compileOutputs = { String name, List argv, Object... ignored ->
            commands.add([name: name, argv: argv])
            assertTrue(name.startsWith('compile-') || name == 'legacy-terminal-contract')
            if (name.startsWith('compile-')) {
                String role = name.substring('compile-'.length())
                File output = argv[argv.indexOf('-d') + 1] as File
                (roleEntries[role] ?: []).each { entry ->
                    File file = new File(output, entry); file.parentFile.mkdirs(); file.bytes = new byte[0]
                }
            }
        }
        File work = new File(temporary, 'assembly'); work.mkdirs()
        Map receipt = [:]
        boolean negative = carrier in ['missing-adapter', 'nested-missing', 'nested-corrupt', 'common-substitute',
                'lost-zip', 'jar-only', 'wrong-root', 'wrong-namespace', 'empty-maps', 'missing-generated',
                'mismatched-generated', 'boot-collateral', 'legacy-claimed']
        if (carrier == 'missing-adapter') task.loaderRuntimeClasspath.setFrom(nativeJar, csv)
        if (carrier in ['nested-missing', 'nested-corrupt']) {
            Map<String, byte[]> entries = [:]
            WaveyLoaderComposition.merge(adapter, entries, { true })
            if (carrier == 'nested-missing') entries.remove('META-INF/jars/MixinExtras.jar')
            else entries['META-INF/jars/MixinExtras.jar'] = 'not-a-zip'.bytes
            WaveyLoaderComposition.archive(adapter, entries)
        }
        if (carrier == 'common-substitute') task.loaderRuntimeClasspath.from(extrasPayload)
        if (carrier in ['lost-zip', 'jar-only']) task.loaderRuntimeClasspath.setFrom(nativeJar, adapter)
        if (carrier in ['wrong-root', 'wrong-namespace', 'empty-maps']) {
            Map entries = carrier == 'wrong-root' ? ['nested/fields.csv': 'searge,name,side,desc\nf_1_,field,2,\n'.bytes]
                    : ['fields.csv': ('searge,name,side,desc\n' + (carrier == 'empty-maps' ? '' : 'field,f_1_,2,\n')).bytes,
                       'methods.csv': 'searge,name,side,desc\nm_1_,method,2,\n'.bytes]
            WaveyLoaderComposition.archive(csv, entries)
        }
        if (carrier == 'missing-generated') task.generatedMixinRefmap.set((File) null)
        if (carrier == 'mismatched-generated') new File(own, 'nclskins.full.mixins.json').text = '{"refmap":"different.refmap.json"}'
        if (carrier == 'boot-collateral') task.loaderRuntimeClasspath.from(moduleArtifact(adapter.name + '.neighbor.jar', ['nativekeep/Api.class': new byte[0]]))
        if (carrier == 'legacy-claimed') {
            captured.classpath += new File(work, 'runtime/mods/' + adapter.name).absolutePath
            providerFile.text = groovy.json.JsonOutput.toJson(captured)
        }
        if (negative) {
            assertThrows(IllegalArgumentException) {
                WaveyLegacyComposition.verify(task, work, [nativeJar], [own, wavey], sources, receipt, compileOutputs, { })
            }
            assertTrue(commands.isEmpty(), 'Executable resource/role prerequisites must fail before compile/process')
            return
        }
        WaveyLegacyComposition.verify(task, work, [nativeJar], [own, wavey], sources, receipt, compileOutputs, { })
        List<String> assembled = receipt.loaderInvocations[0].argv
        List<String> actualCp = assembled[assembled.indexOf('-cp') + 1].split(java.util.regex.Pattern.quote(File.pathSeparator)).toList()
        if (carrier == 'csv') assertTrue(actualCp.contains(csv.absolutePath), 'Native CSV ZIP must reach JVM system resources')
        if (carrier == 'identity') assertTrue(assembled[assembled.indexOf('--launchTarget') + 1].contains('dev'), 'Pinned classpath locator requires dev identity')
        if (carrier == 'adapter') {
            File staged = new File(work, 'runtime/mods/' + adapter.name)
            assertTrue(staged.isFile(), 'Supplied GAMELIBRARY must be a distinct normal candidate')
            assertArrayEquals(adapter.bytes, staged.bytes)
        }
        if (carrier == 'generated') new java.util.zip.ZipFile(new File(work, 'runtime/mods/nclskins-verification.jar')).withCloseable { zip ->
            assertNotNull(zip.getEntry(generated.name), 'Every declared NCL refmap must reach own GAME resource group')
        }
        File game = new File(work, 'runtime/mods/nclskins-verification.jar')
        File boot = new File(work, 'verification-boot.jar'), spi = new File(work, 'verification-terminals.jar')
        File stagedWavey = new File(work, 'runtime/mods/' + wavey.name)
        List<File> legacy = java.nio.file.Files.readAllLines(new File(work, 'legacy-classpath.txt').toPath()).collect { new File(it) }
        collision([boot, spi, game, wavey], [game])
        assertEquals(1, java.lang.module.ModuleFinder.of(game.toPath(), game.toPath()).findAll().size())
        List<String> argv = receipt.loaderInvocations[0].argv
        remap.each { key, value -> assertEquals(['-D' + key + '=' + value], argv.findAll { it.startsWith('-D' + key + '=') }) }
        List<String> ignoreArgs = argv.findAll { it.startsWith('-DignoreList=') }
        assertEquals(1, ignoreArgs.size(), 'Exactly one effective native ignoreList carrier')
        List<String> prefixes = ignoreArgs[0].substring('-DignoreList='.length()).split(',').toList()
        List<File> admitted = legacy.findAll { input -> !prefixes.any { input.name.startsWith(it) } }
        def parent = configuration(admitted, [java.lang.module.Configuration.empty()])
        def resolved = configuration([game, stagedWavey], [configuration([], [parent]), configuration([], [parent])])
        assertTrue(resolved.findModule('nclskins').isPresent())
        assertFalse(parent.findModule('nclskins').isPresent())
        collision(admitted + game, [game])
        assertTrue(prefixes.containsAll([game.name, wavey.name]))
        assertFalse(prefixes.contains(''))
        assertTrue(admitted.containsAll([nativeJar, boot, spi]))
        assertArrayEquals(wavey.bytes, stagedWavey.bytes)
        if (carrier in ['properties', 'precedence']) assertTrue(prefixes.contains('inherited-ignore.jar'))
        if (carrier == 'vm') assertTrue(prefixes.contains('vm-ignore.jar'))
        if (carrier == 'absent') assertTrue(prefixes.containsAll(['asm', 'securejarhandler']))
        assertFalse(prefixes.contains('overridden.jar'))
        assertTrue(argv.contains('-DmergeModules=retained-one.jar,retained-two.jar'))
        assertEquals(vm.take(4), argv.subList(1, 5))
        assertEquals((([nativeJar, adapter, wavey, csv, boot, spi])*.absolutePath).join(File.pathSeparator), argv[argv.indexOf('-cp') + 1])
        assertEquals([MCP_MAPPINGS: 'retained-mappings', MOD_CLASSES: 'nclskins%%' + game.absolutePath], receipt.loaderInvocations[0].environment)
        assertEquals(0, receipt.executedFmlContexts)
        assertArrayEquals(providerBefore, providerFile.bytes)
        List gameCompile = commands.find { it.name == 'compile-game' }.argv
        assertEquals(new File(work, 'spi').absolutePath + File.pathSeparator +
                ([nativeJar, adapter, wavey]*.absolutePath).join(File.pathSeparator), gameCompile[gameCompile.indexOf('-cp') + 1])
        new java.util.zip.ZipFile(boot).withCloseable { zip -> assertNotNull(zip.getEntry('META-INF/services/cpw.mods.modlauncher.api.ILaunchHandlerService')) }
        new java.util.zip.ZipFile(spi).withCloseable { zip -> assertNotNull(zip.getEntry('META-INF/services/cpw.mods.modlauncher.serviceapi.ILaunchPluginService')) }
        new java.util.zip.ZipFile(game).withCloseable { zip ->
            assertNotNull(zip.getEntry('com/naocraftlab/skins/runtime/WaveyProducerFixture.class'))
            assertNotNull(zip.getEntry('com/naocraftlab/skins/runtime/CapeProviderCoordinator.class'))
            assertNotNull(zip.getEntry('nclskins.full.mixins.json'))
            assertNotNull(zip.getEntry('META-INF/services/cpw.mods.modlauncher.api.ServiceRunner'))
        }
    }

    @Test
    void actualRegistrationSeparatesCompilerAbiFromProviderRuntimeAndResources() {
        File repository = new File('../..').canonicalFile
        Map catalog = new JsonSlurper().parse(new File(repository, 'gradle/targets.json')) as Map
        Map target = catalog.targets.find { it.id == 'neoforge-1.21.11' }
        File directory = new File(temporary, 'targets/1.21.11/neoforge'); directory.mkdirs()
        def project = ProjectBuilder.builder().withProjectDir(directory).build()
        project.pluginManager.apply('java')
        project.configurations.create('cpmCompositionRuntime')
        project.configurations.create('nclskinsSqliteClientRuntime')
        File compilerOnly = artifact('unrelated-optional.jar', 'optional/Api.class')
        File wavey = artifact('named-wavey.jar', 'dev/tr7zw/waveycapes/render/CapeNodeCollector.class')
        File companions = artifact('companions.jar', 'companion/Api.class')
        File resources = artifact('client-resources.jar', 'assets/.mcassetsroot')
        project.dependencies.add('compileOnly', project.files(compilerOnly, wavey, companions))
        project.dependencies.add('runtimeOnly', project.files(resources))
        project.ext.targetSpec = target
        project.ext.targetCatalog = catalog
        project.ext.wavey = catalog.optionalDependencies.waveycapes
        project.ext.pin = project.ext.wavey.artifacts[target.id]
        project.ext.publicAbi = project.configurations.create('waveyCapesPublicAbi')
        project.dependencies.add('waveyCapesPublicAbi', project.files(wavey))
        project.ext.compileAbi = project.files(companions)
        project.ext.nclskinsCatalogTools = CatalogTools
        def prepare = project.tasks.register('prepareWaveyVerificationRun') {
            ext.vmArgsFile = project.layout.buildDirectory.file('fixture/vm.args')
            ext.programArgsFile = project.layout.buildDirectory.file('fixture/program.args')
            doLast { fail('Classpath inspection must not execute provider preparation') }
        }
        def run = project.tasks.register('runWaveyVerification') {
            ext.classpathProvider = project.files(resources)
            doLast { fail('Classpath inspection must not execute the provider runtime') }
        }
        String convention = new File(repository, 'gradle/waveycapes-conventions.gradle').text
        File script = new File(directory, 'composition.gradle')
        script.text = convention.substring(convention.indexOf('String appearance ='))
        project.apply(from: script)
        def task = project.tasks.named('verifyWaveyCapesComposition').get()
        assertEquals('direct-client-buffered', task.runtimeProfile.get())
        assertTrue(task.taskDependencies.getDependencies(task).contains(prepare.get()))
        assertFalse(task.taskDependencies.getDependencies(task).contains(run.get()))
        assertEquals(prepare.get().vmArgsFile.get().asFile, task.loaderVmArgs.get().asFile)
        assertEquals(prepare.get().programArgsFile.get().asFile, task.loaderProgramArgs.get().asFile)
        assertEquals([resources] as Set, task.loaderRuntimeClasspath.files)
        assertTrue(task.resolutionClasspath.files.containsAll([compilerOnly, wavey, companions]))
        assertFalse(task.resolutionClasspath.files.contains(resources))
        assertTrue(task.executableClasspath.files.containsAll([wavey, companions, resources]))
        assertFalse(task.executableClasspath.files.contains(compilerOnly))
        assertTrue(task.resourceClasspath.files.contains(resources))
        assertFalse(task.resourceClasspath.files.contains(compilerOnly))
        assertFalse(prepare.get().state.executed)
        assertFalse(run.get().state.executed)
        assertFalse(new File(project.buildDir, 'fixture').exists())
    }
}

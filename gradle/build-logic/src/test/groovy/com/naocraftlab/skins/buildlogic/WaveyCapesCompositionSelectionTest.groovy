package com.naocraftlab.skins.buildlogic

import groovy.json.JsonSlurper
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import static org.junit.jupiter.api.Assertions.*

class WaveyCapesCompositionSelectionTest {
    @TempDir File temporary
    private final File repository = new File('../..').canonicalFile

    private static void registerLegacyProviders(def project) {
        ['prepareWaveyVerificationRun', 'prepareWaveyLegacyProvider'].each { name ->
            project.tasks.register(name) {
                ext.vmArgsFile = project.layout.buildDirectory.file('fixture/vm.args')
                ext.programArgsFile = project.layout.buildDirectory.file('fixture/program.args')
                ext.waveyRuntimeFile = project.layout.buildDirectory.file('fixture/wavey-runtime.jar')
            }
        }
        project.tasks.register('runWaveyVerification') {
            ext.classpathProvider = project.files(); ext.minecraftArtifacts = project.files(); ext.runtimeClasspathArtifacts = project.files()
        }
    }

    @Test
    void immediateCatalogTargetsSelectRealExecutableSourcesAndGuards() {
        Map expected = ['fabric-1.20.1': 'rendered-buffer', 'forge-1.20.1': 'rendered-buffer',
                'fabric-1.21.1': 'mesh', 'neoforge-1.21.1': 'mesh']
        Map catalog = new JsonSlurper().parse(new File(repository, 'gradle/targets.json')) as Map
        List<Map> immediateTargets = catalog.targets.findAll { it.capabilities.appearance in ['player-info-cache', 'skin-lookup'] }
        assertEquals(expected.keySet(), immediateTargets.collect { it.id } as Set)
        ['gradle', 'compat'].each { String name ->
            java.nio.file.Path link = new File(temporary, name).toPath()
            if (!java.nio.file.Files.exists(link)) java.nio.file.Files.createSymbolicLink(link, new File(repository, name).toPath())
        }
        String convention = new File(repository, 'gradle/waveycapes-conventions.gradle').text
        String registration = convention.substring(convention.indexOf('String appearance ='))
        immediateTargets.each { Map target ->
            File directory = new File(temporary, "targets/${target.minecraft.version}/${target.loader.id}")
            directory.mkdirs()
            def project = ProjectBuilder.builder().withProjectDir(directory).build()
            project.pluginManager.apply('java')
            project.configurations.create('cpmCompositionRuntime')
            project.configurations.create('nclskinsSqliteClientRuntime')
            project.ext.targetSpec = target
            project.ext.targetCatalog = catalog
            project.ext.wavey = catalog.optionalDependencies.waveycapes
            project.ext.pin = project.ext.wavey.artifacts[target.id]
            project.ext.publicAbi = project.configurations.create('waveyCapesPublicAbi')
            project.ext.compileAbi = project.files()
            project.ext.nclskinsCatalogTools = CatalogTools
            registerLegacyProviders(project)
            File script = new File(directory, 'composition.gradle'); script.text = registration
            project.apply(from: script)
            def task = project.tasks.findByName('verifyWaveyCapesComposition')
            assertNotNull(task, target.id.toString())
            assertEquals(expected[target.id], task.mode.get(), target.id.toString())
            assertEquals(target.java.release as int, task.javaRelease.get())
            assertEquals(target.id, task.targetId.get())
            assertTrue(project.tasks.check.taskDependencies.getDependencies(project.tasks.check).contains(task))
            assertEquals(['nclskins.cape-pass-immediate.mixins.json'] as Set, task.mixinConfigurations.files*.name as Set)
            Set<File> sources = task.probeSources.files
            assertTrue(sources.every { it.isFile() && !it.path.contains('.agents') })
            assertEquals(1, sources.count { it.name == 'ImmediateCapeProbe.java' })
            assertEquals(1, sources.count { it.name == 'BindImmediateCapeProbe.java' })
            assertEquals(1, sources.count { it.name == 'BindImmediatePreviewInputs.java' })
            assertEquals(1, sources.count { it.name == 'ImmediatePublicPreviewProbe.java' })
            assertEquals(1, sources.count { it.name == 'WeaveImmediateCapeProbe.java' })
            assertEquals(1, sources.count { it.name == 'ImmediateBuffers.java' && it.path.contains("/${expected[target.id]}/java/") })
            assertEquals(1, sources.count { it.name == 'WaveyProducerFixture.java' })
            assertTrue(task.serviceResources.get().asFile.isDirectory())
            assertFalse(sources.any { it.path.contains('waveyCombinedComposition') || it.path.contains('waveyBufferedComposition') })
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = ['fabric-26.1', 'neoforge-26.1', 'fabric-26.2', 'neoforge-26.2', 'fabric-26.3', 'neoforge-26.3'])
    void bakedScissorInputSelectsExactlyOneSharedAbiLeafForProbeAndGame(String id) {
        Map catalog = new JsonSlurper().parse(new File(repository, 'gradle/targets.json')) as Map
        Map spec = catalog.targets.find { it.id == id }
        ['gradle', 'compat'].each { name ->
            java.nio.file.Files.createSymbolicLink(new File(temporary, name).toPath(), new File(repository, name).toPath())
        }
        File directory = new File(temporary, spec.path.toString()); directory.mkdirs()
        def project = ProjectBuilder.builder().withProjectDir(directory).build()
        project.pluginManager.apply('java')
        project.configurations.create('cpmCompositionRuntime')
        project.configurations.create('nclskinsSqliteClientRuntime')
        project.ext.targetSpec = spec; project.ext.targetCatalog = catalog
        project.ext.wavey = catalog.optionalDependencies.waveycapes
        project.ext.pin = project.ext.wavey.artifacts[id]
        project.ext.publicAbi = project.configurations.create('waveyCapesPublicAbi')
        project.ext.compileAbi = project.files(); project.ext.nclskinsCatalogTools = CatalogTools
        registerLegacyProviders(project)
        String convention = new File(repository, 'gradle/waveycapes-conventions.gradle').text
        File script = new File(directory, 'composition.gradle')
        script.text = convention.substring(convention.indexOf('String appearance ='))
        project.apply(from: script)
        def task = project.tasks.named('verifyWaveyCapesComposition').get()
        String abi = id.endsWith('26.1') ? 'unbounded-scissor' : 'screen-bounded-scissor'
        Set<File> helpers = task.probeSources.files.findAll { it.name == 'BakedGuiInputs.java' }
        assertEquals(1, helpers.size())
        assertTrue(helpers.first().path.contains('/' + abi + '/java/'))
        if (id.startsWith('neoforge')) {
            def guards = project.tasks.named('verifyWaveyCapesGuards').get()
            assertEquals(helpers, guards.probeSources.files.findAll { it.name == 'BakedGuiInputs.java' } as Set)
            assertEquals('direct-client-' + task.mode.get(), task.runtimeProfile.get())
        } else assertEquals('isolated', task.runtimeProfile.get())
        assertEquals(org.gradle.api.artifacts.Configuration.State.UNRESOLVED, project.configurations.waveyCapesPublicAbi.state)
    }

    private static void assertNativeEnchantmentConsumer(String source) {
        String body = source.substring(source.indexOf('private static void nativeEnchantment()'), source.indexOf('private static void checkNativeEnchantment('))
        assertFalse(body.contains('nclskins.wavey.extraction'), 'Extraction must not skip the same-stack native consumer')
        assertEquals(1, body.count('Class.forName("com.naocraftlab.skins.buildlogic.probe.wavey.NativeElytraConsumptionProbe")'))
        assertEquals(1, body.count('.getMethod("consume", ItemStack.class, ItemStack.class).invoke(null, plain, enchanted)'))
    }

    @Test
    void realComponentEnchantmentCannotSkipItsSameStackNativeConsumer() {
        String source = new File(repository, 'gradle/build-logic/src/waveyLoaderComposition/game/java/com/naocraftlab/skins/buildlogic/probe/wavey/ClientCapeBatch.java').text
        assertNativeEnchantmentConsumer(source)
        assertThrows(AssertionError) { assertNativeEnchantmentConsumer(source.replace('.invoke(null, plain, enchanted)', '.invoke(null, plain, plain)')) }
        assertThrows(AssertionError) { assertNativeEnchantmentConsumer(source.replace('Class.forName("com.naocraftlab.skins.buildlogic.probe.wavey.NativeElytraConsumptionProbe")', 'Class.forName("missing.Consumer")')) }
        assertThrows(AssertionError) { assertNativeEnchantmentConsumer(source.replace('checkNativeEnchantment(plain, enchanted);', 'if (!Boolean.getBoolean("nclskins.wavey.extraction")) checkNativeEnchantment(plain, enchanted);')) }
    }

    private static void assertEquipmentRendererReadyBeforeSubmission(String consumer, String drain) {
        int open = consumer.indexOf('AutoCloseable renderer = NativeElytraDrain.open()')
        assertTrue(open >= 0 && open < consumer.indexOf('equipment.renderLayers('))
        String setup = drain.substring(drain.indexOf('public static AutoCloseable open()'), drain.indexOf('public static net.minecraft.client.resources.model.EquipmentClientInfo equipment('))
        assertTrue(setup.contains('CombinedCapeProbe.initializeRenderOptions()'))
        assertTrue(setup.contains('field.set(minecraft, previous)'))
    }

    @Test
    void combinedEquipmentSubmissionUsesExistingRenderOptionsBeforeNativeSubmit() {
        String consumer = new File(repository, 'gradle/build-logic/src/waveyPublicationComposition/java/com/naocraftlab/skins/buildlogic/probe/wavey/NativeElytraConsumptionProbe.java').text
        String drain = new File(repository, 'gradle/build-logic/src/waveyPublicationComposition/baked-combined/java/com/naocraftlab/skins/buildlogic/probe/wavey/NativeElytraDrain.java').text
        assertEquipmentRendererReadyBeforeSubmission(consumer, drain)
        assertThrows(AssertionError) { assertEquipmentRendererReadyBeforeSubmission(consumer.replace('AutoCloseable renderer = NativeElytraDrain.open()', 'AutoCloseable renderer = () -> {}'), drain) }
        assertThrows(AssertionError) { assertEquipmentRendererReadyBeforeSubmission(consumer, drain.replace('CombinedCapeProbe.initializeRenderOptions()', 'missingInitialization()')) }
    }

    @Test
    void everyCatalogTargetRegistersItsExecutableNativeFamily() {
        Map expected = ['fabric-1.20.1': 'rendered-buffer', 'forge-1.20.1': 'rendered-buffer',
                'fabric-1.21.1': 'mesh', 'neoforge-1.21.1': 'mesh',
                'fabric-1.21.11': 'buffered', 'neoforge-1.21.11': 'buffered',
                'fabric-26.1': 'buffered', 'neoforge-26.1': 'buffered',
                'fabric-26.2': 'prepared', 'neoforge-26.2': 'prepared',
                'fabric-26.3': 'combined', 'neoforge-26.3': 'combined']
        Map catalog = new JsonSlurper().parse(new File(repository, 'gradle/targets.json')) as Map
        assertEquals(expected.keySet(), catalog.targets.collect { it.id } as Set)
        String convention = new File(repository, 'gradle/waveycapes-conventions.gradle').text
        String registration = convention.substring(convention.indexOf('String appearance ='))
        ['gradle', 'compat'].each { String name ->
            java.nio.file.Files.createSymbolicLink(new File(temporary, name).toPath(), new File(repository, name).toPath())
        }
        catalog.targets.each { Map target ->
            File directory = new File(temporary, "targets/${target.minecraft.version}/${target.loader.id}"); directory.mkdirs()
            def project = ProjectBuilder.builder().withProjectDir(directory).build()
            project.pluginManager.apply('java')
            project.configurations.create('cpmCompositionRuntime')
            project.configurations.create('nclskinsSqliteClientRuntime')
            project.ext.targetSpec = target
            project.ext.targetCatalog = catalog
            project.ext.wavey = catalog.optionalDependencies.waveycapes
            project.ext.pin = project.ext.wavey.artifacts[target.id]
            project.ext.publicAbi = project.configurations.create('waveyCapesPublicAbi')
            project.ext.compileAbi = project.files()
            project.ext.nclskinsCatalogTools = CatalogTools
            registerLegacyProviders(project)
            File script = new File(directory, 'composition.gradle'); script.text = registration
            project.apply(from: script)
            def task = project.tasks.findByName('verifyWaveyCapesComposition')
            assertNotNull(task, target.id.toString())
            assertEquals(expected[target.id], task.mode.get(), target.id.toString())
            assertEquals(target.id, task.targetId.get())
            assertTrue(project.tasks.check.taskDependencies.getDependencies(project.tasks.check).contains(task))
            assertFalse(task.probeSources.files.any { it.path.contains('.agents') })
            assertNotNull(task.executableClasspath)
            assertNotNull(task.resourceClasspath)
            if (expected[target.id] == 'combined') {
                assertEquals(1, task.probeSources.files.count { it.name == 'CombinedCapeProbe.java' })
                assertEquals(1, task.probeSources.files.count { it.name == 'PublicationCapeCollector.java' })
            }
            if (!(expected[target.id] in ['rendered-buffer', 'mesh'])) {
                assertEquals(1, task.probeSources.files.count { it.name == 'CapePublicationProbe.java' })
                assertEquals(1, task.probeSources.files.count { it.name == 'PublicationCapeCollector.java' })
                assertTrue(task.mixinConfigurations.files.any { it.name == 'publication.mixins.json' })
                assertEquals(1, task.probeSources.files.count { it.name == 'NativeElytraConsumptionProbe.java' }, target.id.toString())
                if (!task.submissionPreview.get()) {
                    String drainAbi = expected[target.id].toString()
                    Set<File> drains = task.probeSources.files.findAll { it.name == 'NativeElytraDrain.java' }
                    assertEquals(1, drains.size(), target.id.toString())
                    assertTrue(drains.first().path.contains('/baked-' + drainAbi + '/java/'), target.id.toString())
                    if (target.loader.id == 'neoforge') {
                        def guards = project.tasks.named('verifyWaveyCapesGuards').get()
                        assertEquals(drains, guards.probeSources.files.findAll { it.name == 'NativeElytraDrain.java' } as Set)
                        assertEquals(1, guards.probeSources.files.count { it.name == 'NativeElytraConsumptionProbe.java' })
                    }
                    assertEquals(1, task.probeSources.files.count { it.name == 'ExtractionPublicationProbe.java' })
                    assertEquals(1, task.probeSources.files.count { it.name == 'ExtractionBakedPreviewProbe.java' })
                    assertEquals(1, task.probeSources.files.count { it.name == 'ExtractionBakedConsumer.java' })
                    assertEquals(1, task.probeSources.files.count { it.name == 'BindBakedPreviewInputs.java' })
                    String dispatcher = expected[target.id] == 'combined' ? 'avatar-state' : 'entity-state'
                    assertEquals(1, task.probeSources.files.count { it.name == 'PublicationDispatcher.java' && it.path.contains("/${dispatcher}/java/") })
                } else {
                    assertFalse(task.probeSources.files.any { it.name == 'ExtractionPublicationProbe.java' || it.name == 'PublicationDispatcher.java' })
                    assertEquals(1, task.probeSources.files.count { it.name == 'NativeEnchantedElytra.java' })
                    assertTrue(task.probeSources.files.any { it.name == 'PublicationCapeCollector.java' && it.path.contains('/unified/java/') })
                }
            }
            assertTrue(task.mixinConfigurations.files.any { it.name == (expected[target.id] in ['rendered-buffer', 'mesh']
                    ? 'nclskins.cape-pass-immediate.mixins.json' : expected[target.id] == 'buffered'
                    ? 'nclskins.cape-pass-buffered.mixins.json' : 'nclskins.cape-pass-prepared.mixins.json') })
        }
    }
}

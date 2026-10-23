package com.naocraftlab.skins.buildlogic

import groovy.json.JsonSlurper
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import static org.junit.jupiter.api.Assertions.*

class WaveyCapesIsolatedPreviewTest {
    @TempDir File temporary

    abstract static class AssemblyRecorder extends VerifyWaveyCapesCompositionTask {
        final List<Map> observed = []

        @Override
        void runCompositionProcess(File work, Map receipt, Closure save, String name, List command, int expected, String marker) {
            List<String> argv = command.collect { it.toString() }
            observed.add([name: name, argv: argv])
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource(['buffered,true', 'buffered,false', 'prepared,false', 'combined,false', 'mesh,false', 'rendered-buffer,false'])
    void canonicalActionPreparesPublicationBeforeItsFirstPreviewConsumerWithoutShadowingNegatives(String mode, boolean submission) {
        def project = ProjectBuilder.builder().withProjectDir(temporary).build()
        def task = project.tasks.create('assembly', AssemblyRecorder)
        File resources = new File(temporary, 'resources'); resources.mkdirs()
        File index = new File(temporary, 'owner-index.jar')
        new java.util.zip.ZipOutputStream(new FileOutputStream(index)).withCloseable { zip ->
            ['dev/tr7zw/waveycapes/render/CapeNodeCollector.class',
             'net/minecraft/client/renderer/RenderType.class',
             'net/minecraft/client/renderer/rendertype/RenderType.class'].each { entry ->
                zip.putNextEntry(new java.util.zip.ZipEntry(entry)); zip.closeEntry()
            }
        }
        File home = new File(System.getProperty('java.home'))
        task.mode.set(mode); task.submissionPreview.set(submission); task.targetId.set('assembly-unit')
        task.javaRelease.set(17); task.serviceResources.set(resources)
        task.compilerExecutable.set(new File(home, 'bin/javac')); task.javaExecutable.set(new File(home, 'bin/java'))
        task.executableClasspath.from(index)
        task.workDirectory.set(new File(temporary, 'assembled'))
        task.verifyComposition()
        List<Map> commands = task.observed
        File work = task.workDirectory.get().asFile
        Closure<List<File>> roots = { Map command ->
            command.argv[command.argv.indexOf('-cp') + 1].split(java.util.regex.Pattern.quote(File.pathSeparator)).collect { new File(it) }
        }
        Closure<Map> named = { String name -> commands.find { it.name == name } }
        Closure<List<File>> outputs = { List<String> names -> names.collect { new File(work, it) } }
        if (mode in ['mesh', 'rendered-buffer']) {
            assertNull(named('bind-publication'))
            assertEquals(outputs(['transformed', 'bound', 'fixtures']) + [index], roots(named('public-legacy-preview')))
            return
        }
        assertEquals(1, commands.count { it.name == 'bind-publication' })
        assertEquals(1, commands.count { it.name == 'weave-publication' })
        Map weave = named('weave-publication')
        assertTrue(commands.indexOf(named('bind-publication')) < commands.indexOf(weave))
        assertEquals(outputs(submission ? ['publication-raw', 'bound', 'fixtures'] :
                ['preview-raw', 'publication-raw', 'bound', 'fixtures']) + [index], roots(weave))
        assertTrue(weave.argv.contains(submission ? 'publication.mixins.json' : 'publication.mixins.json,preview-state.mixins.json'))
        assertEquals(!submission, weave.argv.contains('baked'))
        if (submission) {
            Map preview = named('preview-publication')
            assertTrue(commands.indexOf(weave) < commands.indexOf(preview), 'Both publication owners must be prepared before the first public preview')
            assertEquals(outputs(['publication-woven', 'transformed', 'publication-raw', 'bound', 'fixtures']) + [index], roots(preview))
            assertTrue(commands.indexOf(weave) < commands.indexOf(named('native-enchanted-elytra')))
        } else {
            assertNull(named('preview-publication'))
            assertEquals(outputs(mode == 'combined' ? ['state', 'transformed', 'geometry', 'bound', 'fixtures'] :
                    ['transformed', 'bound', 'fixtures']) + [index], roots(commands.find { it.argv.any { it.endsWith('.CombinedCapeProbe') || it.endsWith('.CapeLifetimeProbe') || it.endsWith('.BufferedCapeProbe') } }))
            assertEquals(outputs(['preview-raw', 'publication-raw', 'bound', 'fixtures']) + [index], roots(named('weave-missing-publication')))
        }
        List<File> nativeInputs = outputs(mode == 'combined' ? ['transformed', 'publication-raw', 'geometry', 'bound', 'fixtures'] :
                ['transformed', 'publication-raw', 'bound', 'fixtures']) + [index]
        ['world-publication-SMOOTH', 'world-publication-BLOCKY', 'wrong-world-raw'].each { name ->
            assertEquals(outputs(['publication-woven']) + nativeInputs, roots(named(name)))
        }
        assertEquals(outputs(['publication-missing']) + nativeInputs, roots(named('missing-world-publication')))
        assertFalse(roots(named('missing-world-publication')).contains(new File(work, 'publication-woven')))
        if (!submission) {
            assertEquals(outputs(['publication-woven', 'preview-missing']) + nativeInputs, roots(named('missing-extraction-publication')))
            assertFalse(roots(named('missing-extraction-publication')).contains(new File(work, 'preview-raw')))
            ['extraction-publication-SMOOTH', 'extraction-publication-BLOCKY', 'extraction-baked-public'].each { name ->
                assertEquals(outputs(['publication-woven', 'preview-raw']) + nativeInputs, roots(named(name)))
            }
        }
    }

    private static String combinedBakedSource() {
        new File('src/waveyPublicationComposition/baked-combined/java/com/naocraftlab/skins/buildlogic/probe/wavey/ExtractionBakedConsumer.java').text
    }

    private static void restoredRendererProtocol(String source) {
        assertTrue(source.contains('Object previousRenderer = rendererField.get(minecraft);'))
        assertTrue(source.contains('Object previousLighting = lightingField.get(previousRenderer);'))
        assertTrue(source.contains('lightingField.get(previousRenderer) != previousLighting'))
        assertTrue(source.indexOf('Object previousRenderer') < source.indexOf('initializeRenderOptions()'))
        assertTrue(source.contains('finally {\n            try {\n                rendererField.set(minecraft, previousRenderer);'))
        assertTrue(source.contains('finally {\n                CombinedCapeStaged.reset();'))
    }

    @Test
    void combinedBakedRendererRestoresTheExactAdmittedReceiverAcrossCleanupAndFalseFoil() {
        String source = combinedBakedSource()
        restoredRendererProtocol(source)
        String mutant = source.replace('rendererField.set(minecraft, previousRenderer);', '')
        assertNotEquals(source, mutant)
        assertThrows(org.opentest4j.AssertionFailedError) { restoredRendererProtocol(mutant) }
    }

    private static void combinedSingletonProtocol(String source) {
        assertTrue(source.contains('if (!(node instanceof ModelFeatureRenderer.Submit<?> submit))'))
        assertTrue(source.contains('acceptFeatureGroup(ModelFeatureRenderer.TYPE, List.of(submit), ordered);'))
        assertFalse(source.contains('Unexpected singleton'))
        combinedGroupProtocol(source)
    }

    @Test
    void combinedBakedSingletonPreservesTheNativeSubmitAndOrderingThroughTheSameGroupConsumer() {
        String source = combinedBakedSource()
        combinedSingletonProtocol(source)
        ['List.of(submit)', 'ordered);'].each { String input ->
            String mutant = source.replace('acceptFeatureGroup(ModelFeatureRenderer.TYPE, List.of(submit), ordered);',
                    input == 'List.of(submit)' ? 'acceptFeatureGroup(ModelFeatureRenderer.TYPE, List.of(), ordered);' :
                    'acceptFeatureGroup(ModelFeatureRenderer.TYPE, List.of(submit), false);')
            assertNotEquals(source, mutant)
            assertThrows(org.opentest4j.AssertionFailedError) { combinedSingletonProtocol(mutant) }
        }
    }

    private static void combinedGroupProtocol(String source) {
        assertTrue(source.contains('int groupIndex;'))
        assertTrue(source.contains('models.executeGroup(context, null, pass, groupIndex++, submits, ordered);'))
        assertTrue(source.contains('CombinedCapeHost.prepared.subList(prepared, CombinedCapeHost.prepared.size())'))
        assertTrue(source.contains('CombinedCapeHost.drawn.subList(drawn, CombinedCapeHost.drawn.size())'))
        assertTrue(source.contains('new IdentityHashMap<>()'))
        assertTrue(source.contains('identities.remove(value)'))
        assertTrue(source.contains('EXTRACTION_BAKED_GROUP_IDENTITY_CARDINALITY'))
    }

    private static void combinedEmptyGroupProtocol(String source) {
        combinedGroupProtocol(source)
        String group = source.substring(source.indexOf('public <T extends SubmitNode> void acceptFeatureGroup'),
                source.indexOf('storage.drainPhases'))
        int emptyBranch = group.indexOf('if (submits.isEmpty())')
        assertTrue(emptyBranch > group.indexOf('models.executeGroup(context, null, pass, groupIndex++, submits, ordered);'))
        assertTrue(group.contains('models.prepareGroup(context, submits, ordered);'))
        assertTrue(group.indexOf('models.prepareGroup(context, submits, ordered);') < emptyBranch)
        assertFalse(group.contains('return;'))
        assertTrue(group.contains('int groupInfos = CombinedCapeHost.infos.size();'))
        assertTrue(group.contains('int executedInfos = CombinedCapeHost.infos.size() - groupInfos;'))
        String empty = group.substring(emptyBranch, group.indexOf('} else {', emptyBranch))
        assertTrue(empty.contains('!preparedGroup.isEmpty() || !drawnGroup.isEmpty() || executedInfos != 0'))
        assertFalse(empty.contains('nonemptyGroups[0]++'))
        String nonempty = group.substring(group.indexOf('} else {', emptyBranch))
        assertTrue(nonempty.contains('preparedGroup.isEmpty() || identities.size() != preparedGroup.size() || drawnGroup.size() != preparedGroup.size()'))
        assertTrue(nonempty.contains('identities.remove(value)'))
        assertTrue(nonempty.contains('if (!identities.isEmpty())'))
        assertTrue(nonempty.contains('nonemptyGroups[0]++;'))
        assertTrue(source.contains('nonemptyGroups[0] < 2'))
        assertFalse(source.contains('groups[0] = groupIndex'))
        ['" group=" + groupIndex', '" submits=" + submits.size()', '" prepared=" + preparedGroup.size()',
         '" drawn=" + drawnGroup.size()', '" infos=" + executedInfos'].each { assertTrue(group.contains(it), it) }
    }

    @Test
    void combinedBakedEmptyNativeGroupsExecuteWithoutFulfillingTheMeaningfulGroupOracle() {
        String source = combinedBakedSource()
        combinedEmptyGroupProtocol(source)
        ['executedInfos != 0', 'nonemptyGroups[0]++;', 'models.prepareGroup(context, submits, ordered);',
         'preparedGroup.isEmpty() || identities.size() != preparedGroup.size()'].each { String required ->
            String mutant = source.replace(required, '')
            assertNotEquals(source, mutant)
            assertThrows(org.opentest4j.AssertionFailedError) { combinedEmptyGroupProtocol(mutant) }
        }
        String skipped = source.replace('models.prepareGroup(context, submits, ordered);',
                'if (submits.isEmpty()) return; models.prepareGroup(context, submits, ordered);')
        assertThrows(org.opentest4j.AssertionFailedError) { combinedEmptyGroupProtocol(skipped) }
    }

    @Test
    void combinedBakedEachAccumulatedNativeGroupHasItsOwnIndexAndIdentityRoster() {
        String source = combinedBakedSource()
        combinedGroupProtocol(source)
        String mutant = source.replace('groupIndex++', '0')
        assertNotEquals(source, mutant)
        assertThrows(org.opentest4j.AssertionFailedError) { combinedGroupProtocol(mutant) }
    }

    @Test
    void nativeEnchantmentDelegatesOnlyStackConstructionToTheSelectedGameInput() {
        File source = new File('src/waveyLoaderComposition/game/java/com/naocraftlab/skins/buildlogic/probe/wavey/ClientCapeBatch.java')
        def compiler = javax.tools.ToolProvider.systemJavaCompiler
        compiler.getStandardFileManager(null, null, null).withCloseable { files ->
            def parser = compiler.getTask(null, files, null, ['-proc:none'], null, files.getJavaFileObjects(source))
            def owner = parser.parse().iterator().next().typeDecls.find { it instanceof com.sun.source.tree.ClassTree }
            def nativeCase = owner.members.find { it instanceof com.sun.source.tree.MethodTree && it.name.toString() == 'nativeEnchantment' }
            def inputs = nativeCase.body.statements.findAll { it instanceof com.sun.source.tree.VariableTree && it.name.toString() in ['plain', 'enchanted'] }
            assertEquals(['NativeEnchantmentInput.elytra()', 'NativeEnchantmentInput.elytra()'], inputs.collect { it.initializer.toString() })
            String body = nativeCase.body.toString()
            assertTrue(body.contains('enchanted.enchant(Holder.direct(enchantment), 1)'))
            assertTrue(body.contains('NativeElytraConsumptionProbe'))
            assertTrue(body.contains('plain, enchanted'))
            assertFalse(body.contains('new ItemStack('))
            assertTrue(body.contains('checkNativeEnchantment(plain, enchanted)'))
            ['skipped-mutation', 'missing-enchantments', 'override-only', 'copy-mutation-isolated'].each {
                assertTrue(body.contains('"' + it + '"'), it)
            }
            def oracle = owner.members.find { it instanceof com.sun.source.tree.MethodTree && it.name.toString() == 'checkNativeEnchantment' }
            ['!plain.isEnchanted()', '!plain.hasFoil()', 'enchanted.isEnchanted()', 'enchanted.hasFoil()',
             'plain.get(DataComponents.ENCHANTMENT_GLINT_OVERRIDE) == null',
             'enchanted.get(DataComponents.ENCHANTMENT_GLINT_OVERRIDE) == null'].each {
                assertTrue(oracle.body.toString().contains(it), it)
            }
            def rejection = owner.members.find { it instanceof com.sun.source.tree.MethodTree && it.name.toString() == 'rejectNativeEnchantment' }
            assertTrue(rejection.body.toString().contains('checkNativeEnchantment(plain, candidate)'))
        }
    }

    @Test
    void structuralBufferedBakedFixtureOwnsNonNullFixedGlintPartition() {
        File source = new File('src/waveyPublicationComposition/baked-buffered/java/com/naocraftlab/skins/buildlogic/probe/wavey/ExtractionBakedConsumer.java')
        def compiler = javax.tools.ToolProvider.systemJavaCompiler
        compiler.getStandardFileManager(null, null, null).withCloseable { files ->
            def parser = compiler.getTask(null, files, null, ['-proc:none'], null, files.getJavaFileObjects(source))
            def owner = parser.parse().iterator().next().typeDecls.find { it instanceof com.sun.source.tree.ClassTree }
            def buffers = owner.members.find { it instanceof com.sun.source.tree.ClassTree && it.simpleName.toString() == 'Buffers' }
            def constructor = buffers.members.find { it instanceof com.sun.source.tree.MethodTree && it.name.toString() == '<init>' }
            assertEquals(['ByteBufferBuilder'], constructor.parameters.collect { it.type.toString() })
            assertTrue(constructor.body.toString().contains('Map.of(RenderTypes.entityGlint(), foilBuffer)'),
                    'Structural guard only: real producer/native drain is exercised separately')
            def consume = owner.members.find { it instanceof com.sun.source.tree.MethodTree && it.name.toString() == 'consume' }
            def owned = consume.body.statements.find { it instanceof com.sun.source.tree.TryTree }
            assertNotNull(owned)
            assertEquals(['ByteBufferBuilder'], owned.resources.collect { it.type.toString() })
            assertEquals(['foilBuffer'], owned.resources.collect { it.name.toString() })
            assertEquals(['new ByteBufferBuilder(256)'], owned.resources.collect { it.initializer.toString() })
            assertTrue(owned.block.toString().contains('new Buffers(foilBuffer)'))
            assertFalse(consume.body.toString().contains('.endBatch('), 'The genuine renderer alone owns native drain scheduling')
            def acquisition = buffers.members.find { it instanceof com.sun.source.tree.MethodTree && it.name.toString() == 'getBuffer' }
            assertTrue(acquisition.body.toString().contains('return super.getBuffer(type);'))
        }
    }

    @Test
    void independentPublicPreviewsFinishBeforeSecondaryProducerOwnershipStarts() {
        File source = new File('src/waveyBufferedComposition/java/com/naocraftlab/skins/buildlogic/probe/wavey/BufferedPreviewCapeProbe.java')
        def compiler = javax.tools.ToolProvider.systemJavaCompiler
        def diagnostics = new javax.tools.DiagnosticCollector<javax.tools.JavaFileObject>()
        compiler.getStandardFileManager(diagnostics, null, null).withCloseable { files ->
            def parser = compiler.getTask(null, files, diagnostics, ['-proc:none'], null,
                    files.getJavaFileObjects(source))
            def unit = parser.parse().iterator().next()
            assertFalse(diagnostics.diagnostics.any { it.kind == javax.tools.Diagnostic.Kind.ERROR })
            def owner = unit.typeDecls.find { it instanceof com.sun.source.tree.ClassTree }
            def runCase = owner.members.find { it instanceof com.sun.source.tree.MethodTree && it.name.toString() == 'runCase' }
            def clientScope = runCase.body.statements.find { it instanceof com.sun.source.tree.TryTree }
            assertEquals(['client'], clientScope.resources.collect { it.name.toString() },
                    'Independent public previews must not replace an already-started secondary producer')
            def statements = clientScope.block.statements
            def secondary = statements.find { it instanceof com.sun.source.tree.TryTree &&
                    it.resources.any { it instanceof com.sun.source.tree.VariableTree && it.name.toString() == 'producer' } }
            assertNotNull(secondary)
            assertEquals(['producer', 'registry', 'cache'], secondary.resources.collect { it.name.toString() })
            List<String> helperOwners = []
            statements.take(statements.indexOf(secondary)).each { statement ->
                if (statement instanceof com.sun.source.tree.TryTree) {
                    new com.sun.source.util.TreeScanner<Void, Void>() {
                        @Override
                        Void visitLiteral(com.sun.source.tree.LiteralTree literal, Void unused) {
                            if (literal.value in ['com.naocraftlab.skins.buildlogic.probe.wavey.BakedPublicPreviewProbe',
                                                  'com.naocraftlab.skins.buildlogic.probe.wavey.LivePublicPreviewProbe']) {
                                helperOwners.add(literal.value.toString())
                            }
                            return super.visitLiteral(literal, unused)
                        }
                    }.scan(statement.block, null)
                }
            }
            assertEquals(['com.naocraftlab.skins.buildlogic.probe.wavey.BakedPublicPreviewProbe',
                          'com.naocraftlab.skins.buildlogic.probe.wavey.LivePublicPreviewProbe'], helperOwners)
        }
    }

    @Test
    void compiledCanonicalActionRegistersTheActualNativeConsumerEntry() {
        def node = new org.objectweb.asm.tree.ClassNode()
        VerifyWaveyCapesCompositionTask.getResourceAsStream('VerifyWaveyCapesCompositionTask.class').withCloseable {
            new org.objectweb.asm.ClassReader(it).accept(node, 0)
        }
        def action = node.methods.find { it.name == 'verifyComposition' }
        Set literals = action.instructions.toArray().findAll { it instanceof org.objectweb.asm.tree.LdcInsnNode }*.cst as Set
        assertTrue(literals.containsAll(['native-enchanted-elytra', 'NativeEnchantedElytra', 'NATIVE_ELYTRA_CONSUMPTION_PASS',
                'public-legacy-preview', 'ImmediatePublicPreviewProbe', 'LEGACY_PUBLIC_PREVIEW_PASS',
                'extraction-baked-public', 'ExtractionBakedPreviewProbe', 'EXTRACTION_BAKED_PUBLIC_PASS',
                'BindImmediatePreviewInputs', 'BindBakedPreviewInputs']))
        List ordered = action.instructions.toArray().findAll { it instanceof org.objectweb.asm.tree.LdcInsnNode }*.cst
        assertTrue(ordered.indexOf('bind-preview-publication') < ordered.indexOf('weave-publication'))
    }

    @Test
    void exactPrepareOnlyCompilesTheRealIsolatedActionWithoutAnyCaseInvocation() {
        def project = ProjectBuilder.builder().withProjectDir(temporary).build()
        def task = project.tasks.create('isolatedPrepare', VerifyWaveyCapesCompositionTask)
        File source = new File(temporary, 'com/naocraftlab/skins/fixture/ForbiddenExecution.java')
        source.parentFile.mkdirs()
        source.text = 'package com.naocraftlab.skins.fixture; public final class ForbiddenExecution { public static void main(String[] args) { throw new AssertionError("CASE_EXECUTED"); } }'
        File resources = new File(temporary, 'resources'); resources.mkdirs()
        File home = new File(System.getProperty('java.home'))
        task.mode.set('buffered'); task.submissionPreview.set(true); task.targetId.set('synthetic-unit')
        task.javaRelease.set(17); task.prepareOnly.set(true)
        task.compilerExecutable.set(new File(home, 'bin/javac')); task.javaExecutable.set(new File(home, 'bin/java'))
        task.probeSources.from(source); task.serviceResources.set(resources)
        task.workDirectory.set(new File(temporary, 'prepared'))
        task.verifyComposition()
        Map receipt = new JsonSlurper().parse(task.workDirectory.file('result.json').get().asFile)
        assertEquals('PREPARED_NOT_STARTED', receipt.result)
        assertEquals(['compiler-version', 'java-version', 'compile'], receipt.commands*.name)
        assertTrue(receipt.commands.every { it.exit == 0 && it.reaped })
        assertEquals(0, receipt.executedNativeCases)
        assertEquals(0, receipt.executedFmlContexts)
        assertTrue(receipt.plannedNativeCases.contains('native-enchanted-elytra'))
        assertTrue(new File(task.workDirectory.get().asFile, 'fixtures/com/naocraftlab/skins/fixture/ForbiddenExecution.class').isFile())
        task.prepareOnly.set(false)
        task.workDirectory.set(new File(temporary, 'not-prepared'))
        def rejection = assertThrows(org.gradle.api.GradleException) { task.verifyComposition() }
        assertTrue(rejection.message.startsWith('Missing genuine native/public class'))
    }
}

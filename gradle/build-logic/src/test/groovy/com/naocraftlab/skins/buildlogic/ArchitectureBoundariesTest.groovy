package com.naocraftlab.skins.buildlogic

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.objectweb.asm.*
import java.nio.file.Path
import javax.tools.ToolProvider
import static org.junit.jupiter.api.Assertions.*

class ArchitectureBoundariesTest {
    @TempDir Path directory
    static final String SOURCE = 'com/naocraftlab/skins/fixture/UseCase'
    static final String TARGET = 'com/naocraftlab/skins/fixture/Storage'

    @Test
    void compiledFullyQualifiedConstructorBodyAndNestedGenericsAreVisible() {
        File storage = directory.resolve('Storage.java').toFile()
        storage.text = 'package com.naocraftlab.skins.fixture; public class Storage { public static void write() {} }'
        File useCase = directory.resolve('UseCase.java').toFile()
        useCase.text = '''package com.naocraftlab.skins.fixture;
public class UseCase {
    java.util.List<java.util.Map<String, com.naocraftlab.skins.fixture.Storage[]>> values;
    public UseCase() { new com.naocraftlab.skins.fixture.Storage(); }
    public void run() { com.naocraftlab.skins.fixture.Storage.write(); }
    public Runnable deferred() { return com.naocraftlab.skins.fixture.Storage::write; }
}'''
        assertEquals(0, ToolProvider.systemJavaCompiler.run(null, null, null,
                '-d', directory.toString(), storage.path, useCase.path))
        def scan = CompiledDependencies.read(directory.resolve(SOURCE + '.class').toFile().bytes)
        def locations = scan.edges().findAll { it.target() == TARGET }.collect { it.location() }
        ['signature', 'call <init>', 'call write', 'bootstrap argument'].each { fragment ->
            assertTrue(locations.any { it.contains(fragment) }, fragment)
        }
        assertTrue(scan.edges().every { it.source() == SOURCE })
        ['infrastructure', 'presentation', 'composition'].each { forbidden ->
            def failures = ArchitectureBoundaries.verify([directory.toFile()], [(SOURCE): role('application'), (TARGET): role(forbidden)])
            ['signature', 'call <init>', 'call write', 'bootstrap argument'].each { at ->
                assertTrue(failures.any { it.contains('application-to-' + forbidden) && it.contains(at) }, at)
            }
        }
        assertTrue(ArchitectureBoundaries.verify([directory.toFile()], [(SOURCE): role('composition'), (TARGET): role('infrastructure')]).empty)
        assertTrue(ArchitectureBoundaries.verify([directory.toFile()], [(SOURCE): role('application'), (TARGET): role('domain')]).empty)

    }

    @Test
    void signaturesAnnotationsExceptionsConstantsAndHandlesAreVisible() {
        ClassWriter writer = new ClassWriter(0)
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, SOURCE, '<T:L' + TARGET + ';>Ljava/lang/Object;',
                'java/lang/Object', [TARGET] as String[])
        writer.visitAnnotation('L' + TARGET + ';', false).visit('type', Type.getObjectType(TARGET))
        writer.visitField(0, 'field', 'L' + TARGET + ';', null, null).visitEnd()
        def method = writer.visitMethod(Opcodes.ACC_PUBLIC, 'run', '(L' + TARGET + ';)L' + TARGET + ';', null, [TARGET] as String[])
        method.visitAnnotation('L' + TARGET + ';', true).visitEnd()
        method.visitCode()
        method.visitLdcInsn(Type.getObjectType(TARGET))
        method.visitLdcInsn(new Handle(Opcodes.H_INVOKESTATIC, TARGET, 'run', '()V', false))
        method.visitTryCatchBlock(new Label(), new Label(), new Label(), TARGET)
        method.visitInsn(Opcodes.RETURN)
        method.visitMaxs(2, 2)
        method.visitEnd()
        writer.visitEnd()
        def edges = CompiledDependencies.read(writer.toByteArray()).edges().findAll { it.target() == TARGET }
        ['class signature', 'interface', 'class annotation', 'field field', 'throws', 'literal', 'catch', 'annotation'].each { at ->
            assertTrue(edges.any { it.location().contains(at) }, at)
        }
    }

    @Test
    void forbiddenDirectionsIncludeSideAndDirectEffects() {
        def application = role('application')
        ['infrastructure', 'presentation', 'composition'].each { layer ->
            assertEquals('application-to-' + layer, ArchitectureBoundaries.violation(application, role(layer), TARGET))
        }
        assertEquals('domain-to-application', ArchitectureBoundaries.violation(role('domain'), application, TARGET))
        assertEquals('presentation-to-infrastructure', ArchitectureBoundaries.violation(role('presentation'), role('infrastructure'), TARGET))
        ['java/nio/file/Files', 'java/net/http/HttpClient', 'java/sql/DriverManager',
         'java/net/DatagramSocket', 'java/net/MulticastSocket', 'java/nio/channels/AsynchronousFileChannel',
         'java/nio/channels/SocketChannel', 'java/nio/file/spi/FileSystemProvider', 'javax/net/ssl/SSLSocket',
         'java/util/zip/ZipFile', 'java/io/FileWriter', 'java/lang/ProcessBuilder', 'okhttp3/OkHttpClient'].each { target ->
            assertEquals('inner-to-concrete-io', ArchitectureBoundaries.violation(application, null, target))
        }
        assertEquals('common-to-native', ArchitectureBoundaries.violation(application, null, 'net/minecraft/world/entity/Entity'))
        assertEquals('server-to-client', ArchitectureBoundaries.violation(new ArchitectureBoundaries.Role('infrastructure', 'server', false), new ArchitectureBoundaries.Role('application', 'client', true), TARGET))
    }

    @Test
    void meaningfulOuterAndPureDependenciesRemainAllowed() {
        assertNull(ArchitectureBoundaries.violation(role('composition'), role('infrastructure'), TARGET))
        assertNull(ArchitectureBoundaries.violation(role('infrastructure'), role('application'), TARGET))
        assertNull(ArchitectureBoundaries.violation(role('application'), role('domain'), TARGET))
        assertNull(ArchitectureBoundaries.violation(role('application'), role('application'), TARGET))
        assertNull(ArchitectureBoundaries.violation(new ArchitectureBoundaries.Role('infrastructure', 'client', false), new ArchitectureBoundaries.Role('infrastructure', 'client', false), TARGET))
        ['java/nio/file/Path', 'java/time/Clock', 'java/util/concurrent/Executor', 'java/util/List'].each {
            assertNull(ArchitectureBoundaries.violation(role('application'), null, it))
        }
    }

    @Test
    void newProductionTypeFailsAndDiagnosticsIdentifyEdgeLocation() {
        ClassWriter writer = new ClassWriter(0)
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, SOURCE, null, 'java/lang/Object', null)
        writer.visitEnd()
        directory.resolve('UseCase.class').toFile().bytes = writer.toByteArray()
        assertTrue(ArchitectureBoundaries.verify([directory.toFile()]).any { it.contains(SOURCE) && it.contains('unclassified production type') })
        writer = new ClassWriter(0)
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, 'com/naocraftlab/skins/runtime/DefaultClientOperations', null, 'java/lang/Object', null)
        writer.visitField(0, 'storage', 'Lcom/naocraftlab/skins/core/storage/NclSkinsStorage;', null, null).visitEnd()
        writer.visitEnd()
        directory.resolve('UseCase.class').toFile().bytes = writer.toByteArray()
        def errors = ArchitectureBoundaries.verify([directory.toFile()])
        assertTrue(errors.any { it.contains('DefaultClientOperations -> com/naocraftlab/skins/core/storage/NclSkinsStorage [application-to-infrastructure] at field storage') })
    }

    @Test
    void isolatedGradleCheckUsesTrackedRulesWithoutAgentMaterials() {
        directory.resolve('settings.gradle').toFile().text = "rootProject.name = 'architecture-fixture'"
        directory.resolve('build.gradle').toFile().text = """
plugins {
    id 'java'
    id 'com.naocraftlab.skins.build-tools'
}
tasks.register('verifyArchitecture', nclskinsVerifyArchitectureTaskType) {
    classRoots.from(sourceSets.main.output.classesDirs)
    dependsOn tasks.named('classes')
}
tasks.named('check') { dependsOn tasks.named('verifyArchitecture') }
"""
        def source = directory.resolve('src/main/java/com/naocraftlab/skins/core/model/SkinVariant.java').toFile()
        source.parentFile.mkdirs()
        source.text = 'package com.naocraftlab.skins.core.model; public enum SkinVariant { CLASSIC, SLIM }'
        def runner = org.gradle.testkit.runner.GradleRunner.create().withProjectDir(directory.toFile())
                .withPluginClasspath().withArguments('check', '--offline', '--stacktrace')
        assertEquals(org.gradle.testkit.runner.TaskOutcome.SUCCESS, runner.build().task(':verifyArchitecture').outcome)
        source.text = 'package com.naocraftlab.skins.core.model; public enum SkinVariant { CLASSIC; java.nio.file.Files forbidden; }'
        def rejected = runner.buildAndFail()
        assertTrue(rejected.output.contains('SkinVariant -> java/nio/file/Files [inner-to-concrete-io] at field forbidden'))
        assertFalse(directory.resolve('openspec').toFile().exists())
        assertFalse(directory.resolve('.agents').toFile().exists())
    }

    @Test
    void presentationUsesInputContractsAndPureValuesRatherThanConcreteUseCases() {
        String source = 'com/naocraftlab/skins/runtime/ClientRuntime'
        ['DefaultClientOperations', 'ClientConfigurationService', 'PublicSkinImportService', 'AppearanceRefreshCoordinator', 'OptiFineAccountLink', 'PreviewAssetLoader', 'AccountReconciliationCoordinator', 'ServerAppearanceReadinessCoordinator', 'ClientProcessHost'].each { implementation ->
            String target = 'com/naocraftlab/skins/runtime/' + implementation
            ClassWriter writer = new ClassWriter(0)
            writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, source, null, 'java/lang/Object', null)
            writer.visitField(0, 'operations', 'L' + target + ';', null, null).visitEnd()
            def method = writer.visitMethod(Opcodes.ACC_PUBLIC, 'run', '()V', null, null)
            method.visitCode()
            method.visitTypeInsn(Opcodes.NEW, target)
            method.visitMethodInsn(Opcodes.INVOKESPECIAL, target, '<init>', '()V', false)
            method.visitMethodInsn(Opcodes.INVOKEVIRTUAL, target, 'execute', '()V', false)
            method.visitInsn(Opcodes.RETURN)
            method.visitMaxs(2, 1)
            method.visitEnd()
            writer.visitEnd()
            directory.resolve('Fixture.class').toFile().bytes = writer.toByteArray()
            def errors = ArchitectureBoundaries.verify([directory.toFile()])
            ['field operations', 'call <init>', 'call execute'].each { location ->
                assertTrue(errors.any { it.contains('presentation-to-application-implementation') && it.contains(location) })
            }
        }
        ['ClientOperations', 'ConfigurationUseCases', 'PublicSkinImports', 'AppearanceRefresh', 'RuntimeServices', 'CapeProjection'].each { contract ->
            ClassWriter writer = new ClassWriter(0)
            writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, source, null, 'java/lang/Object', null)
            writer.visitField(0, 'contract', 'Lcom/naocraftlab/skins/runtime/' + contract + ';', null, null).visitEnd()
            writer.visitEnd()
            directory.resolve('Fixture.class').toFile().bytes = writer.toByteArray()
            assertTrue(ArchitectureBoundaries.verify([directory.toFile()]).empty, contract)
        }
    }

    @Test
    void processExecutionCallsAndHandlesAreRejectedWithoutBanningPureRuntimeQueries() {
        def overloads = Runtime.declaredMethods.findAll { it.name == 'exec' }.collect { Type.getMethodDescriptor(it) }
        assertEquals(6, overloads.size())
        (overloads + ['()I']).each { descriptor ->
            String member = descriptor == '()I' ? 'availableProcessors' : 'exec'
            ClassWriter writer = new ClassWriter(0)
            writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, SOURCE, null, 'java/lang/Object', null)
            def method = writer.visitMethod(Opcodes.ACC_PUBLIC, 'run', '()V', null, null)
            method.visitCode()
            method.visitMethodInsn(Opcodes.INVOKEVIRTUAL, 'java/lang/Runtime', member, descriptor, false)
            method.visitLdcInsn(new Handle(Opcodes.H_INVOKEVIRTUAL, 'java/lang/Runtime', member, descriptor, false))
            method.visitInsn(Opcodes.RETURN)
            method.visitMaxs(5, 1)
            method.visitEnd()
            writer.visitEnd()
            directory.resolve('Fixture.class').toFile().bytes = writer.toByteArray()
            def scan = CompiledDependencies.read(writer.toByteArray())
            assertEquals(2, scan.edges().count { it.memberName() == member && it.memberDescriptor() == descriptor })
            ['domain', 'application', 'presentation'].each { layer ->
                def errors = ArchitectureBoundaries.verify([directory.toFile()], [(SOURCE): role(layer)])
                if (member == 'exec') {
                    assertTrue(errors.any { it.contains('inner-to-concrete-io') && it.contains('call exec') })
                    assertTrue(errors.any { it.contains('inner-to-concrete-io') && it.contains('literal') })
                } else assertTrue(errors.empty)
            }
            assertTrue(ArchitectureBoundaries.verify([directory.toFile()], [(SOURCE): role('infrastructure')]).empty)
        }
    }

    private static ArchitectureBoundaries.Role role(String layer) {
        new ArchitectureBoundaries.Role(layer, 'shared', true)
    }
}

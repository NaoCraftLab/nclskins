package com.naocraftlab.skins.buildlogic

import groovy.json.JsonSlurper
import org.gradle.api.GradleException
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.params.provider.ValueSource
import static org.junit.jupiter.api.Assertions.*

class WaveyCapesPrepareOnlyRegistrationTest {
    @TempDir File temporary
    private final File repository = new File('../..').canonicalFile
    private final Map effects = [prepareConfiguration: 0, runConfiguration: 0, preparation: 0, process: 0]

    private def fixture(boolean present, Object value) {
        Map catalog = new JsonSlurper().parse(new File(repository, 'gradle/targets.json')) as Map
        File directory = new File(temporary, 'targets/1.21.11/neoforge'); directory.mkdirs()
        def project = ProjectBuilder.builder().withProjectDir(directory).build()
        project.pluginManager.apply('java')
        project.configurations.create('cpmCompositionRuntime')
        project.configurations.create('nclskinsSqliteClientRuntime')
        project.ext.targetSpec = catalog.targets.find { it.id == 'neoforge-1.21.11' }
        project.ext.targetCatalog = catalog
        project.ext.nclskinsCatalogTools = CatalogTools
        project.ext.nclskinsWaveyCompositionProfile = 'direct-client-buffered'
        if (present) project.ext.nclskinsWaveyLoaderPrepareOnly = value
        project.tasks.register('prepareWaveyVerificationRun') {
            effects.prepareConfiguration++
            ext.vmArgsFile = project.layout.buildDirectory.file('fixture/vm.args')
            ext.programArgsFile = project.layout.buildDirectory.file('fixture/program.args')
            doLast { effects.preparation++ }
        }
        project.tasks.register('runWaveyVerification') {
            effects.runConfiguration++
            ext.classpathProvider = project.files()
            doLast { effects.process++ }
        }
        project
    }

    private void applyConvention(def project) {
        project.apply(from: new File(repository, 'gradle/waveycapes-conventions.gradle'))
    }

    private void accepted(boolean present, String value, boolean expected) {
        def project = fixture(present, value)
        applyConvention(project)
        def task = project.tasks.named('verifyWaveyCapesComposition').get()
        assertInstanceOf(VerifyWaveyCapesCompositionTask, task)
        assertEquals('direct-client-buffered', task.runtimeProfile.get())
        assertEquals(expected, task.prepareOnly.get())
        assertTrue(task.taskDependencies.getDependencies(task).contains(project.tasks.named('prepareWaveyVerificationRun').get()))
        assertEquals(0, effects.preparation)
        assertEquals(0, effects.process)
        assertFalse(new File(project.buildDir, 'fixture').exists())
    }

    @Test
    void absenceRegistersFalse() { accepted(false, null, false) }

    @Test
    void exactFalseRegistersFalse() { accepted(true, 'false', false) }

    @Test
    void exactTrueRegistersTrue() { accepted(true, 'true', true) }

    @ParameterizedTest
    @ValueSource(strings = ['', 'TRUE', 'tru', 'FALSE', 'True', 'False', ' true', 'true ', ' false', 'false ', ' ', '\t', '\n', '0', '1', 'null'])
    void invalidTokenRejectsDuringConventionApplicationWithoutEffects(String value) {
        rejected(value)
    }

    @ParameterizedTest
    @MethodSource('nonStringValues')
    void presentNonStringRejectsWithoutCoercion(Object value) {
        rejected(value)
    }

    static List<Object> nonStringValues() { [null, true, false, 0, 1, new StringBuilder('true')] }

    private void rejected(Object value) {
        def project = fixture(true, value)
        GradleException failure = assertThrows(GradleException) { applyConvention(project) }
        Throwable cause = failure
        while (cause.cause != null) cause = cause.cause
        assertEquals('nclskinsWaveyLoaderPrepareOnly must be exactly true or false', cause.message)
        assertFalse(project.tasks.names.contains('verifyWaveyCapesComposition'))
        assertEquals([prepareConfiguration: 0, runConfiguration: 0, preparation: 0, process: 0], effects)
        assertFalse(new File(project.buildDir, 'fixture').exists())
    }
}

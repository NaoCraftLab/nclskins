package com.naocraftlab.skins.buildlogic

import groovy.json.JsonOutput
import org.gradle.api.GradleException
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

import java.nio.file.Path
import java.security.MessageDigest

import static org.junit.jupiter.api.Assertions.*

final class ProviderRowClippingRegistrationTest {
    @TempDir Path directory

    @Test
    void actualRegistrationBindsEveryFamilyAndRejectsMissingOrStaleHost() {
        File repository = new File('../..').canonicalFile
        String conventions = new File(repository, 'gradle/target-conventions.gradle').text
        String registration = conventions.substring(conventions.indexOf('def providerFrameFamily ='),
                conventions.indexOf('def cpmIntegrationTask ='))
        String checkWiring = conventions.substring(conventions.indexOf("tasks.named('check').configure {"),
                conventions.indexOf('\npublishing {'))
        [
                ['immediate-resource-location-player-info', 'immediate',
                 'compat/gui-immediate/src/main/java/com/naocraftlab/skins/compat/gui/immediate/NclSkinsImmediateScreen.java',
                 'com/naocraftlab/skins/compat/gui/immediate/NclSkinsImmediateScreen.class'],
                ['identifier-submission', 'submission',
                 'compat/capabilities/gui/identifier-submission/src/main/java/com/naocraftlab/skins/compat/client/identifier/submission/NclSkinsScreen.java',
                 'com/naocraftlab/skins/compat/client/identifier/submission/NclSkinsScreen.class'],
                ['identifier-extraction-menu-tab-glfw', 'extraction',
                 'compat/capabilities/gui/extraction-shared/src/main/java/com/naocraftlab/skins/compat/client/identifier/extraction/NclSkinsScreen.java',
                 'com/naocraftlab/skins/compat/client/identifier/extraction/NclSkinsScreen.class']
        ].each { List<String> binding ->
            File root = directory.resolve(binding[1]).toFile().canonicalFile; root.mkdirs()
            def project = ProjectBuilder.builder().withProjectDir(root).build()
            project.pluginManager.apply('java')
            project.configurations.create('cpmCompositionRuntime')
            File script = new File(root, 'registration.gradle')
            script.text = '''import groovy.json.JsonOutput
import groovy.json.JsonSlurper
ext.repositoryRoot = layout.projectDirectory
ext.abiSourceSet = sourceSets.main
ext.spec = [capabilities: [gui: 'BINDING'], java: [release: 17]]
'''.replace('BINDING', binding[0]) + registration + '''
def capabilityAbiTask = tasks.register('verifyCapabilityAbi')
def modMenuAbiTask = null
def fancyMenuAbiTask = tasks.register('verifyFancyMenuAbi')
def cpmIntegrationTask = tasks.register('verifyCpmIntegration')
def isolationTask = tasks.register('verifyDedicatedServerIsolation')
tasks.register('verifyTargetCatalog')
''' + checkWiring
            File source = new File(root, binding[2]); source.parentFile.mkdirs(); source.text = 'producer-v1'
            File host = new File(root, 'build/classes/java/main/' + binding[3]); host.parentFile.mkdirs(); host.bytes = [1, 2, 3] as byte[]
            File stamp = new File(root, 'build/verification/provider-row-clipping-compiled.json'); stamp.parentFile.mkdirs()
            stamp.text = JsonOutput.toJson([source: hash(source), host: hash(host)])
            project.apply(from: script)
            def task = project.tasks.getByName('verifyProviderRowClipping')
            assertTrue(project.tasks.getByName('check').taskDependencies.getDependencies(project.tasks.getByName('check')).contains(task))
            assertTrue(task.taskDependencies.getDependencies(task).contains(project.tasks.getByName('compileProviderRowClippingProbe')))
            assertEquals('com.naocraftlab.skins.buildlogic.probe.ProviderRowClippingProbe', task.mainClass.get())
            task.actions.first().execute(task)
            assertEquals([host.absolutePath, binding[1], 'green', new File(root, 'build/verification/provider-row-clipping/result').absolutePath], task.args)
            host.bytes = [4, 5, 6] as byte[]
            assertEquals('Stale compiled provider frame input', assertThrows(GradleException) { task.actions.first().execute(task) }.message)
            host.delete()
            assertEquals('Expected exactly one current compiled provider frame host', assertThrows(GradleException) { task.actions.first().execute(task) }.message)
            host.bytes = [1, 2, 3] as byte[]
            source.text = 'producer-v2'
            assertEquals('Stale compiled provider frame input', assertThrows(GradleException) { task.actions.first().execute(task) }.message)
            stamp.text = JsonOutput.toJson([source: hash(source), host: hash(host)])
            task.actions.first().execute(task)
            assertEquals(binding[1], task.args[1])
        }
    }

    private static String hash(File file) {
        MessageDigest.getInstance('SHA-256').digest(file.bytes).encodeHex().toString()
    }
}

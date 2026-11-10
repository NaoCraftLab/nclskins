package com.naocraftlab.skins.buildlogic

import groovy.json.JsonSlurper
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.util.zip.ZipFile

import static org.junit.jupiter.api.Assertions.*

class AssemblyGraphTest {
    @TempDir File workspace
    private final File repository = new File('../..').canonicalFile

    private String between(String text, String start, String end) {
        int first = text.indexOf(start)
        int last = text.indexOf(end, first + start.length())
        assertTrue(first >= 0 && last > first, start)
        text.substring(first, last)
    }

    private def runWithEnvironment(File root, Map env, String... arguments) {
        GradleRunner.create().withProjectDir(root).withPluginClasspath()
                .withTestKitDir(new File(workspace, 'test-kit')).withEnvironment(env)
                .withArguments((arguments as List) + ['--console=plain', '--stacktrace']).build()
    }

    private def run(File root, String... arguments) {
        runWithEnvironment(root, new HashMap(System.getenv()), arguments)
    }

    @Test void rootDispatchesCatalogArchivesWithoutCleanOrTests() {
        String original = new File(repository, 'build.gradle').text
        Map catalog = CatalogTools.loadCatalog(repository)
        File root = new File(workspace, 'dispatch')
        root.mkdirs()
        new File(root, 'settings.gradle').text = "rootProject.name = 'assembly-dispatch'\n"
        File wrapper = new File(root, 'gradlew')
        wrapper.text = '#!/bin/sh\nexec "' + new File(org.gradle.internal.installation.CurrentGradleInstallation.get().gradleHome, 'bin/gradle').absolutePath + '" "$@"\n'
        wrapper.setExecutable(true)
        File gradleDir = new File(root, 'gradle')
        gradleDir.mkdirs()
        new File(gradleDir, 'targets.json').text = CatalogTools.json(catalog)
        new File(gradleDir, 'version.properties').text = 'modVersion=1.0.0\n'
        new File(gradleDir, 'plugin-version.properties').text = 'pluginVersion=1.0.0\npluginBuild=1\n'
        String setup = '''
import com.naocraftlab.skins.buildlogic.*
plugins { id 'com.naocraftlab.skins.build-tools' }
def requestedTargetId = providers.gradleProperty('targetId').get()
def targetIds = [requestedTargetId]
def buildWorkers = 1
def requestedFromRef = null
def requestedToRef = null
'''
        String configure = between(original, 'def configureTargetBuild =', 'targetCatalog.targets.findAll')
        String registrations = between(original, "tasks.register('buildTarget',", 'def compatibilityTargetIds =')
        String conventions = new File(repository, 'gradle/target-conventions.gradle').text
        String metadataRegistration = between(conventions, 'def generateTargetMetadata =', 'def generateBlockbenchResources =')
        new File(root, 'build.gradle').text = setup + configure + registrations + '''
tasks.withType(TargetBuildTask).configureEach {
    actions.clear()
    verifyArtifacts.set(false)
    collectArtifacts.set(false)
    doLast {
        def catalog = CatalogTools.loadJson(catalogFile.get().asFile.toPath())
        def target = CatalogTools.selectTarget(catalog, providers.gradleProperty('targetId').get())
        catalog.gradleFamilies[target.gradleFamily].wrapperPath = '.'
        runTarget(catalog, target)
    }
}
'''
        ['fabric-1.20.1', 'neoforge-26.2', 'forge-1.20.1', 'fabric-26.2'].each { String id ->
            Map target = CatalogTools.selectTarget(catalog, id)
            File project = new File(root, target.path.toString())
            project.mkdirs()
            new File(project, 'settings.gradle').text = "rootProject.name = 'archive-fixture'\n"
            String metadataSetup = ''
            String metadataTaskSetup = ''
            if (id == 'fabric-26.2') {
                File groovyClasses = new File(repository, 'gradle/build-logic/build/classes/groovy/main')
                File javaClasses = new File(repository, 'gradle/build-logic/build/classes/java/main')
                metadataSetup = """
buildscript { dependencies { classpath files('${groovyClasses.absolutePath}', '${javaClasses.absolutePath}') } }
"""
                metadataTaskSetup = """
def repositoryRoot = layout.projectDirectory.dir('../../../')
def currentTargetId = 'fabric-26.2'
def generatedMetadataDirectory = layout.buildDirectory.dir('generated/metadata')
def nclskinsGenerateMetadataTaskType = com.naocraftlab.skins.buildlogic.GenerateMetadataTask
def nclskinsMetadataRenderer = com.naocraftlab.skins.buildlogic.MetadataRenderer
${metadataRegistration}
sourceSets.main.resources.srcDir(generatedMetadataDirectory)
tasks.named('processResources').configure { dependsOn generateTargetMetadata }
tasks.named('sourcesJar').configure { dependsOn generateTargetMetadata }
"""
            }
            new File(project, 'build.gradle').text = metadataSetup + '''
plugins { id 'java' }
java { withSourcesJar() }
''' + metadataTaskSetup + '''
tasks.register('remapJar', Jar) { dependsOn jar; from sourceSets.main.output; archiveClassifier = 'remapped' }
tasks.register('remapSourcesJar', Jar) { dependsOn sourcesJar; from sourceSets.main.allSource; archiveClassifier = 'remapped-sources' }
tasks.register('jarJar', Jar) { dependsOn jar; from sourceSets.main.output; archiveClassifier = 'production'; finalizedBy 'reobfJarJar' }
tasks.register('reobfJarJar')
tasks.register('verifyTargetCatalog')
check.dependsOn verifyTargetCatalog
gradle.taskGraph.whenReady { graph -> file('executed-graph.json').text = groovy.json.JsonOutput.toJson(graph.allTasks*.name) }
'''
            Map nestedEnvironment = new HashMap(System.getenv())
            nestedEnvironment.NCLSKINS_SERVER_PLUGIN_BASELINE = '9.8.7'
            def result = runWithEnvironment(root, nestedEnvironment, 'buildTarget', '-PtargetId=' + id)
            assertEquals(TaskOutcome.SUCCESS, result.task(':buildTarget').outcome)
            List graph = new JsonSlurper().parse(new File(project, 'executed-graph.json')) as List
            assertFalse(graph.any { it in ['clean', 'build', 'check', 'test', 'verifyTargetCatalog'] }, "${id}: ${graph}")
            List expected = target.buildProfile == 'forgegradle-legacy'
                    ? ['jarJar', 'reobfJarJar', 'sourcesJar']
                    : target.artifact.remapJar ? ['remapJar', 'remapSourcesJar'] : ['jar', 'sourcesJar']
            assertTrue(graph.containsAll(expected), "${id}: ${graph}")
            if (id == 'fabric-26.2') {
                String path = 'nclskins-server-compatibility.json'
                File generated = new File(project, 'build/generated/metadata/' + path)
                assertEquals('9.8.7', new JsonSlurper().parse(generated).requiredServerPluginVersion)
                assertNotEquals('1.0.0', new JsonSlurper().parse(generated).requiredServerPluginVersion)
                ['archive-fixture.jar', 'archive-fixture-sources.jar'].each { String archive ->
                    new ZipFile(new File(project, 'build/libs/' + archive)).withCloseable { zip ->
                        assertEquals('9.8.7', new JsonSlurper().parse(zip.getInputStream(zip.getEntry(path))).requiredServerPluginVersion)
                    }
                }
            }
        }
    }

    @Test void pluginExportDoesNotPullModuleChecksButBuildStillRunsJvmTests() {
        File root = new File(workspace, 'plugin')
        root.mkdirs()
        List modules = ['server-plugin-common', 'server-plugin-bukkit', 'server-plugin-velocity', 'server-plugin-bungee', 'server-plugin']
        new File(root, 'settings.gradle').text = "rootProject.name = 'plugin-assembly'\ninclude " + modules.collect { "'${it}'" }.join(',') + '\n'
        String original = new File(repository, 'build.gradle').text
        new File(root, 'build.gradle').text = '''
import com.naocraftlab.skins.buildlogic.*
plugins { id 'com.naocraftlab.skins.build-tools' }
def requestedTargetId = 'server-plugin'
subprojects {
    apply plugin: 'java'
    java { withSourcesJar() }
    repositories { mavenCentral() }
    dependencies {
        testImplementation platform('org.junit:junit-bom:5.11.4')
        testImplementation 'org.junit.jupiter:junit-jupiter'
        testRuntimeOnly 'org.junit.platform:junit-platform-launcher'
    }
    tasks.withType(Test).configureEach { useJUnitPlatform() }
    tasks.register('shadowJar', Jar) { from sourceSets.main.output; archiveClassifier = 'all' }
    tasks.register('verifyServerPluginArtifact') { dependsOn tasks.named('shadowJar') }
}
''' + between(original, "tasks.register('buildServerPlugin')", "tasks.register('affectedTargets'") +
                between(original, "tasks.register('exportReleaseComponent',", "tasks.register('assembleRelease',") + '''
tasks.named('exportReleaseComponent') {
    actions.clear()
    planFile.set(layout.projectDirectory.file('fixture-plan.json'))
    doLast { println 'EXPORTED_FIXTURE_COMPONENT' }
}
'''
        new File(root, 'fixture-plan.json').text = '{}'
        modules.each { new File(root, it).mkdirs() }
        File source = new File(root, 'server-plugin/src/test/java/com/naocraftlab/skins/AssemblyTest.java')
        source.parentFile.mkdirs()
        source.text = '''package com.naocraftlab.skins;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class AssemblyTest { @Test void executes() { assertEquals(4, 2 + 2); } }
'''
        def assembly = run(root, 'exportReleaseComponent')
        assertNotNull(assembly.task(':server-plugin:shadowJar'))
        assertNotNull(assembly.task(':server-plugin:sourcesJar'))
        assertFalse(assembly.tasks.any { it.path.endsWith(':test') || it.path.endsWith(':check') || it.path.endsWith(':verifyServerPluginArtifact') }, assembly.tasks*.path.toString())
        def build = run(root, ':server-plugin:build')
        assertEquals(TaskOutcome.SUCCESS, build.task(':server-plugin:test').outcome)
        File report = new File(root, 'server-plugin/build/test-results/test/TEST-com.naocraftlab.skins.AssemblyTest.xml')
        assertTrue(report.text.contains('tests="1"'))
        assertTrue(report.text.contains('failures="0"'))
    }
}

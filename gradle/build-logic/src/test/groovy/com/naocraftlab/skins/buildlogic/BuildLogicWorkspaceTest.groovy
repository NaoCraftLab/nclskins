package com.naocraftlab.skins.buildlogic

import org.gradle.testfixtures.ProjectBuilder

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

import java.util.jar.JarFile

import static org.junit.jupiter.api.Assertions.*

class BuildLogicWorkspaceTest {
    @TempDir File workspace
    private final File repository = new File('../..').canonicalFile

    @Test
    void warmedTargetBuildPreservesRootPluginOutputsAndIncrementalHistory() {
        File root = new File(workspace, 'repository with spaces')
        ['gradlew', 'gradle/wrapper/gradle-wrapper.jar', 'gradle/wrapper/gradle-wrapper.properties',
         'gradle/build-logic/build.gradle', 'gradle/build-logic/settings.gradle'].each { String name ->
            File destination = new File(root, name)
            destination.parentFile.mkdirs()
            destination.bytes = new File(repository, name).bytes
        }
        String buildLogic = new File(root, 'gradle/build-logic/build.gradle').text
        int declarationEnd = buildLogic.indexOf('\ngroup =')
        assertTrue(declarationEnd > 0)
        new File(root, 'gradle/build-logic/build.gradle').text = buildLogic.substring(0, declarationEnd) + '''
gradlePlugin {
    plugins {
        tooling {
            id = 'com.naocraftlab.skins.build-tools'
            implementationClass = 'com.naocraftlab.skins.buildlogic.NclSkinsBuildToolsPlugin'
        }
    }
}
'''
        assertTrue(new File(root, 'gradlew').setExecutable(true))
        new File(root, 'gradle.properties').text = 'org.gradle.caching=true\norg.gradle.configuration-cache=false\n'
        new File(root, 'settings.gradle').text = "pluginManagement { includeBuild('gradle/build-logic') }\nrootProject.name = 'workspace-outer'\n"
        String applyPlugin = "plugins { id 'com.naocraftlab.skins.build-tools' }\n"
        new File(root, 'build.gradle').text = applyPlugin
        File targetDirectory = new File(root, 'targets/fixture')
        targetDirectory.mkdirs()
        new File(targetDirectory, 'settings.gradle').text = "pluginManagement { includeBuild('../../gradle/build-logic') }\nrootProject.name = 'workspace-child'\n"
        new File(targetDirectory, 'build.gradle').text = applyPlugin
        File source = new File(root, 'gradle/build-logic/src/main/groovy/com/naocraftlab/skins/buildlogic/NclSkinsBuildToolsPlugin.groovy')
        source.parentFile.mkdirs()
        source.text = '''package com.naocraftlab.skins.buildlogic
import org.gradle.api.Plugin
import org.gradle.api.Project
class NclSkinsBuildToolsPlugin implements Plugin<Project> {
    void apply(Project project) { project.extensions.extraProperties.set('workspaceFixture', true) }
}
'''
        def task = ProjectBuilder.builder().withProjectDir(root).build().tasks.create('target', TargetBuildTask)
        task.repositoryDirectory.set(root)
        task.targetTasks.set(['help'])
        task.collectArtifacts.set(false)
        Map catalog = [gradleFamilies: [modern: [wrapperPath: '.']]]
        Map target = [id: 'fixture-target', path: 'targets/fixture', gradleFamily: 'modern', java: [buildJdk: 17]]
        File rootClass = new File(root, 'gradle/build-logic/build/classes/groovy/main/com/naocraftlab/skins/buildlogic/NclSkinsBuildToolsPlugin.class')
        File targetClass = new File(root, 'gradle/build-logic/build/workspaces/fixture-target/classes/groovy/main/com/naocraftlab/skins/buildlogic/NclSkinsBuildToolsPlugin.class')
        Closure rootHelp = {
            List<String> output = []
            ProcessBuilder builder = new ProcessBuilder(new File(root, 'gradlew').absolutePath,
                    'help', '--no-daemon', '--console=plain', '--max-workers=1').directory(root)
                    .redirectErrorStream(true)
            TargetRuntime.configureEnvironment(builder, TargetRuntime.resolveJavaHome(17))
            ReleaseProcess.run(builder, 'fixture-root', 120) { output.add(it.toString()) }
            output.join('\n')
        }
        task.runTarget(catalog, target)
        assertTrue(targetClass.isFile(), 'Seed the existing target output before returning to root')
        source.text = source.text.replace('class NclSkinsBuildToolsPlugin implements Plugin<Project> {',
                "class NclSkinsBuildToolsPlugin implements Plugin<Project> {\n    static final String fixtureRevision = '${UUID.randomUUID()}'")
        rootHelp()

        assertTrue(rootClass.isFile())
        byte[] rootBytes = rootClass.bytes
        assertTrue(targetClass.isFile(), 'Both output owners must exist before the repeated target build')
        task.runTarget(catalog, target)
        assertTrue(rootClass.isFile(), 'Warmed target compilation deleted the root plugin implementation')
        assertArrayEquals(rootBytes, rootClass.bytes)
        assertTrue(targetClass.isFile())
        assertArrayEquals(rootBytes, targetClass.bytes)
        task.runTarget(catalog, target)
        task.runTarget(catalog, target + [id: 'second-target'])
        assertTrue(targetClass.isFile(), 'Another workspace must retain the previous target output')
        assertArrayEquals(rootBytes, rootClass.bytes)
        def repeated = rootHelp()
        assertTrue(repeated.contains('> Task :build-logic:compileGroovy UP-TO-DATE'))
        assertTrue(repeated.contains('> Task :build-logic:jar UP-TO-DATE'))
        assertArrayEquals(rootBytes, rootClass.bytes)
        new JarFile(new File(root, 'gradle/build-logic/build/libs/nclskins-build-logic.jar')).withCloseable { archive ->
            assertNotNull(archive.getEntry('com/naocraftlab/skins/buildlogic/NclSkinsBuildToolsPlugin.class'))
        }
    }
}

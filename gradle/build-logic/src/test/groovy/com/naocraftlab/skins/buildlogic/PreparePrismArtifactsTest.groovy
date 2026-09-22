package com.naocraftlab.skins.buildlogic

import groovy.json.JsonOutput
import org.gradle.api.tasks.Internal
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

import static org.junit.jupiter.api.Assertions.*

class PreparePrismArtifactsTest {
    @TempDir File root

    abstract static class RecordingPrepare extends PreparePrismArtifactsTask {
        @Internal int builds = 0
        @Internal boolean failBuild = false
        @Internal boolean mutateInputs = false
        @Override void buildFreshArtifacts() {
            builds++
            if (failBuild) throw new IllegalStateException('fixture build failed')
            if (mutateInputs) new File(repositoryDirectory.get().asFile, 'source').text = 'changed'
        }
    }

    @Test void reusesFullGateAndHonorsForceCleanFailureAndMutation() {
        assertEquals(0, new ProcessBuilder('git', 'init', '-q').directory(root).start().waitFor())
        new File(root, '.gitignore').text = 'build/\n.gradle/\n'
        new File(root, 'gradle').mkdirs()
        new File(root, 'gradle/version.properties').text = 'modVersion=1.0.0-alpha.1\n'
        Map catalog = [targets: [[id: 'example', path: 'target', artifact: [file: 'mod.jar']]]]
        File catalogFile = new File(root, 'gradle/targets.json')
        catalogFile.text = JsonOutput.toJson(catalog)
        File artifact = new File(root, 'target/build/libs/mod.jar')
        artifact.parentFile.mkdirs()
        artifact.text = 'verified'
        def project = ProjectBuilder.builder().withProjectDir(root).build()
        RecordingPrepare task = project.tasks.create('prepareFixture', RecordingPrepare)
        task.repositoryDirectory.set(root)
        task.catalogFile.set(catalogFile)
        task.snapshotProvider = { BuildReceipt.snapshot(root, catalog, [:]) }
        Map initial = task.snapshotProvider.call()
        BuildReceipt.publish(root, catalog, initial, initial, 'fullCheck')
        task.buildTargets()
        assertEquals(0, task.builds)
        assertEquals('fullCheck', BuildReceipt.status(root, catalog, initial).receipt.verificationLevel)
        task.forceBuild = true
        task.buildTargets()
        assertEquals(1, task.builds)
        assertEquals('incremental', BuildReceipt.status(root, catalog, initial).receipt.verificationLevel)
        task.cleanBuild = true
        task.buildTargets()
        assertEquals(2, task.builds)
        assertEquals('clean', BuildReceipt.status(root, catalog, initial).receipt.verificationLevel)
        task.failBuild = true
        assertThrows(IllegalStateException) { task.buildTargets() }
        assertFalse(BuildReceipt.location(root).exists())
        task.failBuild = false
        task.mutateInputs = true
        assertThrows(IllegalStateException) { task.buildTargets() }
        assertFalse(BuildReceipt.location(root).exists())
    }
}

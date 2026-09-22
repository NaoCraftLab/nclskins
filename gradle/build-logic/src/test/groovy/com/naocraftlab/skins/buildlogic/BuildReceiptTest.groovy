package com.naocraftlab.skins.buildlogic

import groovy.json.JsonOutput
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

import static org.junit.jupiter.api.Assertions.*

class BuildReceiptTest {
    @TempDir File root

    Map fixture() {
        assertEquals(0, new ProcessBuilder('git', 'init', '-q').directory(root).start().waitFor())
        new File(root, '.gitignore').text = 'build/\n'
        new File(root, 'gradle').mkdirs()
        new File(root, 'gradle/version.properties').text = 'modVersion=1.0.0-alpha.1\n'
        new File(root, 'source.java').text = 'original'
        new File(root, 'target/build/libs').mkdirs()
        new File(root, 'target/build/libs/mod.jar').text = 'artifact'
        [targets: [[id: 'example', path: 'target', artifact: [file: 'mod.jar']]]]
    }

    @Test void reusesReceiptAndIgnoresOnlyApprovedRootDocuments() {
        Map catalog = fixture()
        Map before = BuildReceipt.snapshot(root, catalog, [jdk: 'one'])
        BuildReceipt.publish(root, catalog, before, before, 'fullCheck')
        assertTrue(BuildReceipt.status(root, catalog, before).reusable)
        BuildReceipt.DOCUMENTS.each { new File(root, it).text = 'new documentation' }
        assertEquals(before, BuildReceipt.snapshot(root, catalog, [jdk: 'one']))
        new File(root, 'unknown.txt').text = 'new input'
        assertFalse(BuildReceipt.status(root, catalog, BuildReceipt.snapshot(root, catalog, [jdk: 'one'])).reusable)
    }

    @Test void rejectsChangedInputsToolchainsArtifactsAndIncompleteTargets() {
        Map catalog = fixture()
        Map before = BuildReceipt.snapshot(root, catalog, [jdk: 'one'])
        BuildReceipt.publish(root, catalog, before, before, 'incremental')
        assertEquals('incremental', BuildReceipt.status(root, catalog, before).receipt.verificationLevel)
        ['source.java', 'gradle/targets.json', 'gradle/wrapper.properties', 'resource.json'].each { String path ->
            File file = new File(root, path)
            boolean existed = file.exists()
            String old = existed ? file.text : ''
            file.text = 'changed'
            assertFalse(BuildReceipt.status(root, catalog, BuildReceipt.snapshot(root, catalog, [jdk: 'one'])).reusable)
            if (existed) file.text = old
            else file.delete()
        }
        assertFalse(BuildReceipt.status(root, catalog, before + [environment: [jdk: 'two']]).reusable)
        assertFalse(BuildReceipt.status(root, catalog, before + [checkout: '/other']).reusable)
        assertFalse(BuildReceipt.status(root, catalog, before + [targets: []]).reusable)
        new File(root, 'target/build/libs/mod.jar').text = 'tampered'
        assertFalse(BuildReceipt.status(root, catalog, before).reusable)
    }

    @Test void rejectsMissingCorruptAndMidBuildMutation() {
        Map catalog = fixture()
        Map before = BuildReceipt.snapshot(root, catalog, [:])
        assertFalse(BuildReceipt.status(root, catalog, before).reusable)
        BuildReceipt.location(root).parentFile.mkdirs()
        BuildReceipt.location(root).text = '{bad'
        assertFalse(BuildReceipt.status(root, catalog, before).reusable)
        BuildReceipt.invalidate(root)
        new File(root, 'source.java').text = 'changed while building'
        assertThrows(IllegalStateException) {
            BuildReceipt.publish(root, catalog, before, BuildReceipt.snapshot(root, catalog, [:]), 'fullCheck')
        }
        assertFalse(BuildReceipt.location(root).exists())
    }

    @Test void fingerprintsTrackedDeletionAndSymlinkContents() {
        fixture()
        assertEquals(0, new ProcessBuilder('git', 'add', 'source.java').directory(root).start().waitFor())
        String initial = BuildReceipt.fingerprint(root)
        new File(root, 'source.java').delete()
        assertNotEquals(initial, BuildReceipt.fingerprint(root))
        File outside = File.createTempFile('receipt', '.txt')
        try {
            outside.text = 'first'
            java.nio.file.Files.createSymbolicLink(new File(root, 'linked').toPath(), outside.toPath())
            String linked = BuildReceipt.fingerprint(root)
            outside.text = 'second'
            assertNotEquals(linked, BuildReceipt.fingerprint(root))
        } finally {
            outside.delete()
        }
    }
    @Test void toolchainIdentityIncludesActualFileContents() {
        File jdk = new File(root, 'jdk')
        ['release', 'bin/java', 'bin/javac', 'lib/modules'].each { String path ->
            File file = new File(jdk, path)
            file.parentFile.mkdirs()
            file.text = 'original'
        }
        Map before = BuildReceipt.javaIdentity(jdk)
        new File(jdk, 'lib/modules').text = 'modified compiler/runtime'
        assertNotEquals(before, BuildReceipt.javaIdentity(jdk))
        new File(jdk, 'bin/javac').delete()
        assertThrows(IllegalStateException) { BuildReceipt.javaIdentity(jdk) }
    }

    @Test void dependencyCacheTracksContentAndPresence() {
        File cache = new File(root, 'dependencies')
        cache.mkdirs()
        File dependency = new File(cache, 'library.jar')
        dependency.text = 'first'
        String initial = BuildReceipt.treeHash(cache)
        dependency.text = 'second'
        assertNotEquals(initial, BuildReceipt.treeHash(cache))
        dependency.text = 'first'
        assertEquals(initial, BuildReceipt.treeHash(cache))
        dependency.delete()
        assertNotEquals(initial, BuildReceipt.treeHash(cache))
    }

    @Test void requiresCurrentCompatibilityReportWhenCatalogDeclaresIt() {
        Map catalog = fixture()
        catalog.targets[0].compatibility = [minecraftVersions: ['example'], loaderVersions: [example: 'loader']]
        Map initial = BuildReceipt.snapshot(root, catalog, [:])
        assertThrows(IllegalStateException) { BuildReceipt.publish(root, catalog, initial, initial, 'incremental') }
        File artifact = new File(root, 'target/build/libs/mod.jar')
        File report = new File(root, 'build/compatibility-runs/example/verification.json')
        report.parentFile.mkdirs()
        report.text = JsonOutput.toJson([target: 'example', artifact: artifact.canonicalPath,
                sha256: BuildReceipt.hash(artifact), runtimes: [[minecraftVersion: 'example', loaderVersion: 'loader']]])
        BuildReceipt.publish(root, catalog, initial, initial, 'fullCheck')
        assertTrue(BuildReceipt.status(root, catalog, initial).reusable)
        report.delete()
        assertFalse(BuildReceipt.status(root, catalog, initial).reusable)
    }

}

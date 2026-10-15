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

    @Test void requiresNormalizedBaselineAtSnapshotPublishAndStatusBoundaries() {
        Map catalog = fixture()
        Map valid = BuildReceipt.snapshot(root, catalog, [requiredServerPluginVersion: '1.4.0'])
        BuildReceipt.publish(root, catalog, valid, valid, 'fullCheck')
        assertTrue(BuildReceipt.status(root, catalog, valid).reusable)
        assertEquals(2, BuildReceipt.status(root, catalog, valid).receipt.schemaVersion)
        [[:], [requiredServerPluginVersion: null], [requiredServerPluginVersion: ''],
         [requiredServerPluginVersion: ' 1.4.0 '], [requiredServerPluginVersion: 'v1.4.0'],
         [requiredServerPluginVersion: '1.4.0.1'], [requiredServerPluginVersion: 140]].each { Map environment ->
            assertFalse(BuildReceipt.status(root, catalog, valid + [environment: environment]).reusable)
        }
        Map missing = valid + [environment: [:]]
        Map record = new groovy.json.JsonSlurper().parse(BuildReceipt.location(root)) as Map
        record.environment = [:]
        BuildReceipt.location(root).text = JsonOutput.toJson(record)
        assertFalse(BuildReceipt.status(root, catalog, missing).reusable)
        BuildReceipt.invalidate(root)
        [[:], [requiredServerPluginVersion: null], [requiredServerPluginVersion: ''],
         [requiredServerPluginVersion: ' 1.4.0 '], [requiredServerPluginVersion: 'v1.4.0'],
         [requiredServerPluginVersion: '1.4.0.1'], [requiredServerPluginVersion: 140]].each { Map environment ->
            assertThrows(IllegalStateException) { BuildReceipt.snapshot(root, catalog, environment) }
            Map invalid = valid + [environment: environment]
            assertThrows(IllegalStateException) { BuildReceipt.publish(root, catalog, invalid, invalid, 'fullCheck') }
            assertThrows(IllegalStateException) { BuildReceipt.publish(root, catalog, valid, invalid, 'fullCheck') }
            assertFalse(BuildReceipt.location(root).exists())
        }
    }

    @Test void reusesReceiptAndIgnoresOnlyApprovedRootDocuments() {
        Map catalog = fixture()
        Map before = BuildReceipt.snapshot(root, catalog, [jdk: 'one', requiredServerPluginVersion: '1.4.0'])
        BuildReceipt.publish(root, catalog, before, before, 'fullCheck')
        assertTrue(BuildReceipt.status(root, catalog, before).reusable)
        BuildReceipt.DOCUMENTS.each { new File(root, it).text = 'new documentation' }
        assertEquals(before, BuildReceipt.snapshot(root, catalog, [jdk: 'one', requiredServerPluginVersion: '1.4.0']))
        new File(root, 'unknown.txt').text = 'new input'
        assertFalse(BuildReceipt.status(root, catalog, BuildReceipt.snapshot(root, catalog, [jdk: 'one', requiredServerPluginVersion: '1.4.0'])).reusable)
    }

    @Test void rejectsChangedInputsToolchainsArtifactsAndIncompleteTargets() {
        Map catalog = fixture()
        Map before = BuildReceipt.snapshot(root, catalog, [jdk: 'one', requiredServerPluginVersion: '1.4.0'])
        BuildReceipt.publish(root, catalog, before, before, 'incremental')
        assertEquals('incremental', BuildReceipt.status(root, catalog, before).receipt.verificationLevel)
        ['source.java', 'gradle/targets.json', 'gradle/wrapper.properties', 'resource.json'].each { String path ->
            File file = new File(root, path)
            boolean existed = file.exists()
            String old = existed ? file.text : ''
            file.text = 'changed'
            assertFalse(BuildReceipt.status(root, catalog, BuildReceipt.snapshot(root, catalog, [jdk: 'one', requiredServerPluginVersion: '1.4.0'])).reusable)
            if (existed) file.text = old
            else file.delete()
        }
        assertFalse(BuildReceipt.status(root, catalog, before + [environment: [jdk: 'two', requiredServerPluginVersion: '1.4.0']]).reusable)
        assertFalse(BuildReceipt.status(root, catalog, before + [checkout: '/other']).reusable)
        assertFalse(BuildReceipt.status(root, catalog, before + [targets: []]).reusable)
        new File(root, 'target/build/libs/mod.jar').text = 'tampered'
        assertFalse(BuildReceipt.status(root, catalog, before).reusable)
    }

    @Test void rejectsMissingCorruptAndMidBuildMutation() {
        Map catalog = fixture()
        Map before = BuildReceipt.snapshot(root, catalog, [requiredServerPluginVersion: '1.4.0'])
        assertFalse(BuildReceipt.status(root, catalog, before).reusable)
        BuildReceipt.location(root).parentFile.mkdirs()
        BuildReceipt.location(root).text = '{bad'
        assertFalse(BuildReceipt.status(root, catalog, before).reusable)
        BuildReceipt.invalidate(root)
        new File(root, 'source.java').text = 'changed while building'
        assertThrows(IllegalStateException) {
            BuildReceipt.publish(root, catalog, before, BuildReceipt.snapshot(root, catalog, [requiredServerPluginVersion: '1.4.0']), 'fullCheck')
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

    @Test void acceptsNewDependenciesAndSealsTheFinalCache() {
        Map catalog = fixture()
        Map before = BuildReceipt.snapshot(root, catalog, [jdk: 'one', requiredServerPluginVersion: '1.4.0', dependencyCache: [existing: 'original']])
        Map after = BuildReceipt.snapshot(root, catalog,
                [jdk: 'one', requiredServerPluginVersion: '1.4.0', dependencyCache: [existing: 'original', downloaded: 'new']])
        BuildReceipt.publish(root, catalog, before, after, 'fullCheck')
        assertTrue(BuildReceipt.status(root, catalog, after).reusable)
        assertFalse(BuildReceipt.status(root, catalog, before).reusable)
    }

    @Test void rejectsCacheMutationDeletionAndToolchainChangesDuringDownloads() {
        Map catalog = fixture()
        Map before = BuildReceipt.snapshot(root, catalog, [jdk: 'one', requiredServerPluginVersion: '1.4.0', dependencyCache: [existing: 'original']])
        [
                [jdk: 'one', requiredServerPluginVersion: '1.4.0', dependencyCache: [existing: 'changed', downloaded: 'new']],
                [jdk: 'one', requiredServerPluginVersion: '1.4.0', dependencyCache: [downloaded: 'new']],
                [jdk: 'two', requiredServerPluginVersion: '1.4.0', dependencyCache: [existing: 'original', downloaded: 'new']]
        ].each { Map environment ->
            assertThrows(IllegalStateException) {
                BuildReceipt.publish(root, catalog, before,
                        BuildReceipt.snapshot(root, catalog, environment), 'fullCheck')
            }
            assertFalse(BuildReceipt.location(root).exists())
        }
    }

    @Test void requiresCurrentCompatibilityReportWhenCatalogDeclaresIt() {
        Map catalog = fixture()
        catalog.targets[0].compatibility = [minecraftVersions: ['example'], loaderVersions: [example: 'loader']]
        Map initial = BuildReceipt.snapshot(root, catalog, [requiredServerPluginVersion: '1.4.0'])
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

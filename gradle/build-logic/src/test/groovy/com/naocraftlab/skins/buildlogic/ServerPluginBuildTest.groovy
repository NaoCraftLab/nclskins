package com.naocraftlab.skins.buildlogic

import org.junit.jupiter.api.Test

import java.nio.file.Files

import static org.junit.jupiter.api.Assertions.*

final class ServerPluginBuildTest {
    @Test
    void semanticScanIgnoresHiddenUpstreamResearchButChecksProduction() {
        File root = Files.createTempDirectory('semantic-research-').toFile()
        try {
            File research = new File(root, '.research/vendor/src/main/java/Test.java')
            research.parentFile.mkdirs()
            research.text = 'package example; final class Minecraft12111Leaf {}'
            List<String> errors = []
            SemanticVerifier.verifyVersionNamespaceScope(root.toPath(), [:], errors)
            assertTrue(errors.isEmpty())
            File production = new File(root, 'module/src/main/java/Test.java')
            production.parentFile.mkdirs()
            production.text = research.text
            SemanticVerifier.verifyVersionNamespaceScope(root.toPath(), [:], errors)
            assertFalse(errors.isEmpty())
        } finally { root.deleteDir() }
    }
    @Test
    void buildToolsRejectsVersionsOutsideTheCatalog() {
        Map catalog = CatalogTools.loadCatalog(new File('../..').canonicalFile)
        assertEquals(17, ServerPluginRuntimeSupport.buildToolsRuntime(catalog, '1.20.1').javaRelease)
        assertThrows(IllegalArgumentException) { ServerPluginRuntimeSupport.buildToolsRuntime(catalog, '26.3') }
        assertThrows(IllegalArgumentException) { ServerPluginRuntimeSupport.buildToolsRuntime(catalog, '26.2') }
    }

    @Test
    void compatibilityBuildsAdvanceSequentiallyAndFunctionalVersionsRestartAtOne() {
        Map remote = [modrinth: [[version_number: '1.0.0.1', version_type: 'release']], curseforge: []]
        PlanReleaseTask.requirePluginBuildAdvance([versionNumber: '1.0.0.2', channel: 'release'], remote)
        PlanReleaseTask.requirePluginBuildAdvance([versionNumber: '1.1.0.1', channel: 'release'], remote)
        assertThrows(IllegalStateException) {
            PlanReleaseTask.requirePluginBuildAdvance([versionNumber: '1.0.0.3', channel: 'release'], remote)
        }
        assertThrows(IllegalStateException) {
            PlanReleaseTask.requirePluginBuildAdvance([versionNumber: '1.1.0.2', channel: 'release'], remote)
        }
    }
    @Test
    void pluginBuildIsIndependentAndOrderedNumerically() {
        assertEquals('1.0.0.2', ServerPluginVersion.parse('pluginVersion=1.0.0\npluginBuild=2\n'))
        assertEquals('1.1.0.1-beta.2', ServerPluginVersion.parse('pluginVersion=1.1.0-beta.2\npluginBuild=1\n'))
        assertTrue(ServerPluginVersion.compare('1.0.0.10', '1.0.0.2') > 0)
        assertTrue(ServerPluginVersion.compare('1.1.0.1', '1.0.0.10') > 0)
        assertEquals(0, ServerPluginVersion.compare('1.0.0', '1.0.0.1'))
        assertThrows(IllegalArgumentException) { ServerPluginVersion.parse('pluginVersion=1.0.0\npluginBuild=0\n') }
    }

    @Test
    void changelogSelectsExactBuildWithOlderBuildPresent() {
        File file = Files.createTempFile('plugin-build-notes', '.md').toFile()
        try {
            file.text = '## 1.0.0.2\n\nNew compatibility\n\n## 1.0.0.1\n\nPrevious notes\n'
            assertEquals('New compatibility\n', ServerPluginChangelog.validate(file,
                    [currentVersion: '1.0.0', pluginVersion: '1.0.0.2', publish: true, reason: 'server-change']))
            assertNull(ServerPluginChangelog.validate(file,
                    [currentVersion: '1.1.0', pluginVersion: '1.0.0.2', publish: false, reason: 'unchanged']))
        } finally {
            file.delete()
        }
    }

    @Test
    void githubReplacementKeepsModsAndResumesAfterUploadOrDelete() {
        Map old = [id: 11, name: 'nclskins-plugin-1.0.0.1.jar', hash: 'a' * 64]
        Map plugin = [file: 'nclskins-plugin-1.0.0.2.jar', kind: 'server-plugin', sha256: 'b' * 64]
        Map mod = [file: 'mod.jar', kind: 'mod', sha256: 'c' * 64]
        Map manifest = [assets: [plugin, mod], pluginReplacement:
                [id: old.id, file: old.name, sha256: old.hash]]
        Map remoteMod = [id: 12, name: mod.file, hash: mod.sha256]
        Map uploaded = [id: 13, name: plugin.file, hash: plugin.sha256]
        Closure hash = { Map asset -> asset.hash }
        Map before = GithubReleaseSupport.plan(manifest, [old, remoteMod], hash)
        assertTrue(before.conflicts.isEmpty())
        assertEquals(['delete-previous-plugin', 'upload', 'keep'], before.actions*.action)
        Map partial = GithubReleaseSupport.plan(manifest, [old, remoteMod, uploaded], hash)
        assertTrue(partial.conflicts.isEmpty())
        assertEquals(1, partial.actions.count { it.action == 'delete-previous-plugin' })
        assertTrue(GithubReleaseSupport.plan(manifest, [remoteMod, uploaded], hash).actions.every { it.action == 'keep' })
        assertFalse(GithubReleaseSupport.plan(manifest, [old + [hash: 'd' * 64], remoteMod], hash).conflicts.isEmpty())
        assertFalse(GithubReleaseSupport.plan(manifest, [remoteMod], hash).conflicts.isEmpty())
        assertThrows(IllegalStateException) {
            PlanReleaseTask.previousPluginAsset([asset: plugin, versionNumber: '1.0.0.2'],
                    [[name: 'nclskins-plugin-1.0.0.3.jar']])
        }
    }
}

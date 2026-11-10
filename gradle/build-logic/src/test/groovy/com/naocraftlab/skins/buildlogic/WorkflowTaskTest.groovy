package com.naocraftlab.skins.buildlogic

import groovy.json.JsonOutput
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import static org.junit.jupiter.api.Assertions.*

final class WorkflowTaskTest {
    @TempDir File root

    @Test void pagesAdmissionRunsBeforeInventoryOrOutputMutation() {
        def project = ProjectBuilder.builder().withProjectDir(root).build()
        def task = project.tasks.create('site', GenerateUpdateCatalogTask)
        File catalog = new File(root, 'targets.json')
        catalog.text = '{"targets":[]}'
        File inventory = new File(root, 'inventory.json')
        inventory.text = '[]'
        File output = new File(root, 'build/site')
        output.mkdirs()
        new File(output, 'old').text = 'deployed predecessor'
        task.catalogFile.set(catalog)
        task.inventoryFile.set(inventory)
        task.outputDirectory.set(output)
        task.allowedOutputRoot.set(new File(root, 'build'))
        task.workflowSnapshot.set(true)
        task.sourceCommit.set('a' * 40)
        task.repositoryDirectory.set(root)
        task.eventName.set('workflow_dispatch')
        task.eventRef.set('refs/heads/other')
        int reads = 0
        task.inventoryTransport = { int page -> reads++; [status: 200, headers: [:], body: '[]'.bytes] }
        assertThrows(IllegalStateException) { task.generate() }
        assertEquals(0, reads)
        assertEquals('deployed predecessor', new File(output, 'old').text)
    }

    @Test void actionArtifactExtractionRefusesEscapesAndCollisions() {
        File destination = new File(root, 'bundle')
        assertThrows(IllegalStateException) {
            ReleaseDownload.unpackActionsArtifact(zip(['../escaped': 'unsafe']), destination)
        }
        assertFalse(new File(root, 'escaped').exists())
        ReleaseDownload.unpackActionsArtifact(zip(['release-plan.json': '{}', 'assets/example.jar': 'fixture']), destination)
        assertEquals('fixture', new File(destination, 'assets/example.jar').text)
        assertThrows(IllegalStateException) {
            ReleaseDownload.unpackActionsArtifact(zip(['/absolute': 'unsafe']), new File(root, 'bad'))
        }
    }

    @Test void livePagesSnapshotPreservesModBytesAcrossPluginOnlyAndFailsClosedAtBoundary() {
        def project = ProjectBuilder.builder().withProjectDir(root).build()
        File catalog = new File(root, 'targets.json')
        Map data = CatalogTools.loadCatalog(new File(System.getProperty('repositoryRoot', '../..')))
        CatalogTools.selectTarget(data, 'neoforge-26.3').releaseEligible = false
        catalog.text = CatalogTools.json(data)
        ReleaseSelection.git(root, ['init', '-q', '-b', 'main'])
        ReleaseSelection.git(root, ['add', '.'])
        ReleaseSelection.git(root, ['-c', 'user.name=Fixture', '-c', 'user.email=fixture@example.invalid',
                '-c', 'commit.gpgsign=false', 'commit', '-qm', 'catalog'])
        String sha = ReleaseSelection.git(root, ['rev-parse', 'HEAD']).trim()
        def task = project.tasks.create('liveCatalog', GenerateUpdateCatalogTask)
        File output = new File(root, 'build/site')
        task.catalogFile.set(catalog)
        task.outputDirectory.set(output)
        task.allowedOutputRoot.set(new File(root, 'build'))
        task.workflowSnapshot.set(true)
        task.repositoryDirectory.set(root)
        task.sourceCommit.set(sha)
        task.callerCatalogCommit.set(sha)
        task.branchTransport = { String branch -> sha }
        def asset = { String name -> [name: name, browser_download_url:
                'https://github.com/NaoCraftLab/nclskins/releases/download/1.0.0/' + name.replace('+', '%2B')] }
        String mod = CatalogTools.selectTarget(data, 'fabric-26.2').artifact.file.replace('{modVersion}', '1.0.0')
        String experimental = CatalogTools.selectTarget(data, 'neoforge-26.3').artifact.file.replace('{modVersion}', '1.0.0')
        Map release = [draft: false, tag_name: '1.0.0', html_url: 'https://github.com/NaoCraftLab/nclskins/releases/tag/1.0.0',
                assets: [asset(mod), asset(experimental), asset('nclskins-plugin-1.0.0.1.jar')]]
        List calls = []
        task.inventoryTransport = { int page -> calls.add(page); [status: 200, headers: [:], body: JsonOutput.toJson([release]).bytes] }
        task.generate()
        File common = new File(output, 'updates/v1/catalog.json')
        byte[] before = common.bytes
        assertFalse(common.text.contains('neoforge-26.3'))
        assertFalse(new File(output, 'updates/v1/native/neoforge-26.3.json').exists())
        release.assets[-1] = asset('nclskins-plugin-1.0.0.2.jar')
        task.generate()
        assertArrayEquals(before, common.bytes)
        assertEquals([1, 1], calls)
        task.inventoryTransport = { int page -> [status: 200, headers: [:], body: JsonOutput.toJson(
                page == 1 ? (1..100).collect { [id: it] } : [[id: 101]]).bytes] }
        assertThrows(IllegalArgumentException) { task.generate() }
        assertArrayEquals(before, common.bytes)
        task.sourceCommit.set('0' * 40)
        int rejectedReads = 0
        task.inventoryTransport = { int page -> rejectedReads++; throw new AssertionError('inventory must not run') }
        assertThrows(IllegalStateException) { task.generate() }
        assertEquals(0, rejectedReads)
        assertArrayEquals(before, common.bytes)
    }

    private static byte[] zip(Map<String, String> files) {
        def out = new ByteArrayOutputStream()
        new java.util.zip.ZipOutputStream(out).withCloseable { zip ->
            files.each { name, text -> zip.putNextEntry(new java.util.zip.ZipEntry(name)); zip.write(text.bytes); zip.closeEntry() }
        }
        out.toByteArray()
    }
}

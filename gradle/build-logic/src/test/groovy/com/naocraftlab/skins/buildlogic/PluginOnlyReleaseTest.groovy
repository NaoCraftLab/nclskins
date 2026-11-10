package com.naocraftlab.skins.buildlogic

import groovy.json.JsonSlurper
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Test

import java.nio.file.Files

import static org.junit.jupiter.api.Assertions.*

final class PluginOnlyReleaseTest {
    @Test
    void fullPluginIdentityPlansNewSourceAgainstHistoricalTagAndPreservesModBytes() {
        verifyPlugin()
    }

    @Test void workflowFreshPlanExportsFullPluginIdentityWithoutModBuild() { verifyPlugin('workflow-fresh') }
    @Test void workflowResumeDownloadsExactBoundBundleWithoutRebuild() { verifyPlugin('workflow-resume') }
    @Test void workflowResumeRejectsWrongProvenanceBeforeDownload() { verifyPlugin('workflow-wrong') }
    @Test void workflowResumeRejectsArtifactDigestBeforeExtraction() { verifyPlugin('workflow-digest') }
    @Test void localPreparationNeedsNoTagOrRemoteAdmission() { verifyPlugin('local') }
    @Test void rejectsSourceDriftBeforeMutation() { verifyPlugin('source') }
    @Test void removingPlanBindingCannotBypassReceiptChecks() { verifyPlugin('unpin') }
    @Test void productionPreparationStillRequiresHistoricalTag() { verifyPlugin('missing-tag') }
    @Test void rejectsUnapprovedSourceBranch() { verifyPlugin('branch') }
    @Test void rejectsTagDriftBeforeMutation() { verifyPlugin('tag') }
    @Test void rejectsHistoricalAssetDriftBeforeMutation() { verifyPlugin('mod') }
    @Test void rejectsDuplicateInventoryBeforeMutation() { verifyPlugin('duplicate') }
    @Test void replacesPreviousPluginOnlyAfterVerifiedUploadAndResumes() { verifyPlugin('replacement') }
    @Test void resumesUploadThatCompletedBeforeTransportFailure() { verifyPlugin('resume') }
    @Test void publishesPinnedPluginAfterAllowedBranchAdvances() { verifyPlugin('advanced') }
    @Test void resumesBundleThenPublishesAfterBranchAdvances() { verifyPlugin('advanced-resume') }
    @Test void rejectsBranchBehindPinnedSource() { verifyPlugin('behind') }
    @Test void rejectsForcePushedBranch() { verifyPlugin('diverged') }
    @Test void rejectsUnrelatedBranch() { verifyPlugin('unrelated') }
    @Test void rejectsComparisonWithWrongMergeBase() { verifyPlugin('wrong-base') }
    @Test void rejectsWrongRemoteBranchRef() { verifyPlugin('wrong-ref') }

    private void verifyPlugin(String fault = null) {
        File root = Files.createTempDirectory('plugin-only-release-').toFile()
        try {
            File source = fixture(root)
            def project = ProjectBuilder.builder().withProjectDir(root).build()
            def platforms = project.tasks.create('preflightReleasePlatforms', EmptyPlatforms)
            def github = project.tasks.create('preflightGithubRelease', HistoricalGithub)
            github.release.assets.first().name = AssembleReleaseTask.artifactName(CatalogTools.releaseTargets(CatalogTools.loadCatalog(source)).first(), '1.0.0')
            github.tagCommit = ReleaseSelection.git(source, ['rev-parse', 'refs/tags/1.0.0']).trim()
            github.sourceCommit = ReleaseSelection.git(source, ['rev-parse', 'HEAD']).trim()
            def planner = project.tasks.create('planFixture', PlanReleaseTask)
            planner.repositoryDirectory.set(source)
            planner.releaseTag.set('1.0.0.2')
            planner.outputDirectory.set(new File(root, 'plan'))
            if (fault == 'local') {
                planner.localPreparation.set(true)
                ReleaseSelection.git(source, ['tag', '-d', '1.0.0'])
                github.forbidRequests = true
            }
            if (fault == 'missing-tag') {
                ReleaseSelection.git(source, ['tag', '-d', '1.0.0'])
                assertThrows(IllegalStateException) { planner.planRelease() }
                assertTrue(github.mutations.isEmpty())
                return
            }
            if (fault == 'branch') {
                ReleaseSelection.git(source, ['checkout', '-qb', 'unapproved'])
                assertThrows(IllegalStateException) { planner.planRelease() }
                assertTrue(github.mutations.isEmpty())
                return
            }
            if (fault in ['replacement', 'resume']) github.release.assets.add([id: 'previous-plugin', name: 'nclskins-plugin-1.0.0.1.jar', sha256: 'b' * 64])
            if (fault == 'workflow-fresh') {
                ReleaseSelection.git(source, ['checkout', '--detach', github.sourceCommit])
                planner.workflowAdmission.set(true)
                planner.workflowEnvironment = { String key -> [GITHUB_REPOSITORY: 'NaoCraftLab/nclskins',
                        EVENT_NAME: 'workflow_dispatch', EVENT_REF: 'refs/heads/main', GITHUB_SHA: github.sourceCommit,
                        GITHUB_OUTPUT: new File(root, 'ci-output').path][key] }
                planner.workflowTransport = { String url, int limit ->
                    assertTrue(url.endsWith('/git/ref/heads/main'))
                    [status: 200, body: CatalogTools.json([object: [type: 'commit', sha: github.sourceCommit]]).bytes]
                }
            }
            planner.planRelease()
            if (fault == 'workflow-fresh') {
                assertTrue(new File(root, 'ci-output').text.contains('version=1.0.0.2\n'))
                assertTrue(new File(root, 'ci-output').text.contains('matrix={"include":[{"id":"server-plugin"}]}'))
            }
            Map plan = ReleasePlan.load(new File(root, 'plan/release-plan.json'))
            assertEquals('1.0.0.2', plan.requestedVersion)
            assertEquals('1.0.0', plan.version)
            if (fault == 'local') assertNull(plan.tagCommit)
            else assertNotEquals(plan.tagCommit, plan.sourceCommit)
            assertEquals([], plan.buildTargetIds)
            assertTrue(plan.buildPlugin)
            assertEquals(['server-plugin'], plan.components*.id)
            assertNull(plan.release.notes)
            assertEquals('Plugin fix\n', plan.pluginNotes)
            assertEquals(fault == 'local' ? 0 : 1, plan.preservedGithub.size())
            if (fault != 'local') assertEquals('historical-mod', plan.preservedGithub.first().id)
            def exporter = project.tasks.create('exportFixture', ExportReleaseComponentTask)
            exporter.repositoryDirectory.set(source)
            exporter.planFile.set(new File(root, 'plan/release-plan.json'))
            exporter.componentId.set('server-plugin')
            exporter.outputDirectory.set(new File(root, 'plan/assets'))
            plan.components.first().with { component ->
                [component.asset, component.sourcesAsset].each { asset ->
                    File file = new File(source, 'server-plugin/build/libs/' + asset.file)
                    file.parentFile.mkdirs()
                    file.text = 'plugin source ' + plan.sourceCommit + asset.kind
                }
            }
            exporter.exportComponent()
            def assembly = project.tasks.create('assembleFixture', AssemblePlannedReleaseTask)
            assembly.repositoryDirectory.set(source)
            assembly.planFile.set(new File(root, 'plan/release-plan.json'))
            assembly.componentDirectory.set(new File(root, 'plan/assets'))
            assembly.releaseRoot.set(new File(root, 'release'))
            assembly.assemble()
            File bundle = new File(root, 'release/1.0.0.2')
            Map manifest = PublicationSupport.loadManifest(bundle)
            assertEquals([], manifest.targets)
            assertEquals(2, manifest.assets.size())
            assertEquals(plan.sourceCommit, manifest.sourceCommit)
            assertEquals(plan.tagCommit, manifest.tagCommit)
            if (fault in ['workflow-resume', 'workflow-wrong', 'workflow-digest', 'advanced-resume']) {
                if (fault == 'advanced-resume') {
                    new File(source, 'advance.txt').text = 'later commit'
                    commit(source)
                    github.sourceCommit = ReleaseSelection.git(source, ['rev-parse', 'HEAD']).trim()
                    github.comparisonBase = plan.sourceCommit
                }
                def out = new ByteArrayOutputStream()
                new java.util.zip.ZipOutputStream(out).withCloseable { zip ->
                    bundle.eachFileRecurse { File file ->
                        if (file.isFile()) {
                            zip.putNextEntry(new java.util.zip.ZipEntry(bundle.toPath().relativize(file.toPath()).toString()))
                            zip.write(file.bytes)
                            zip.closeEntry()
                        }
                    }
                }
                byte[] archive = out.toByteArray()
                Map run = [id: 123, path: fault == 'workflow-wrong' ? 'wrong' : '.github/workflows/release.yml',
                        repository: [full_name: 'NaoCraftLab/nclskins'], event: 'workflow_dispatch',
                        head_sha: plan.sourceCommit, head_branch: 'main']
                Map artifact = [id: 456, name: 'release-bundle-1.0.0.2', expired: false,
                        size_in_bytes: archive.length, digest: 'sha256:' + (fault == 'workflow-digest' ? '0' * 64 : CatalogTools.sha256(archive)),
                        workflow_run: [id: 123, head_sha: plan.sourceCommit]]
                List reads = []
                planner.workflowAdmission.set(true)
                planner.resumeRun.set('123')
                planner.workflowEnvironment = { String key -> [GITHUB_REPOSITORY: 'NaoCraftLab/nclskins',
                        EVENT_NAME: 'workflow_dispatch', EVENT_REF: 'refs/heads/main', GITHUB_SHA: github.sourceCommit,
                        GITHUB_OUTPUT: new File(root, 'resume-output').path][key] }
                planner.workflowTransport = { String url, int limit ->
                    reads.add(url)
                    Object body = url.endsWith('/git/ref/heads/main') ? [object: [type: 'commit', sha: github.sourceCommit]] :
                            url.endsWith('/actions/runs/123') ? run :
                            url.endsWith('/artifacts?per_page=100&page=1') ? [total_count: 1, artifacts: [artifact]] : null
                    [status: 200, body: body == null ? archive : CatalogTools.json(body).bytes]
                }
                if (!(fault in ['workflow-resume', 'advanced-resume'])) {
                    assertThrows(IllegalStateException) { planner.planRelease() }
                    assertFalse(new File(source, 'release-bundle').exists())
                    if (fault == 'workflow-wrong') assertEquals(2, reads.size())
                    return
                }
                planner.planRelease()
                assertEquals(4, reads.size())
                assertTrue(new File(root, 'resume-output').text.contains('build=false\n'))
                assertTrue(new File(root, 'resume-output').text.contains('version=1.0.0.2\n'))
                assertEquals(manifest, PublicationSupport.loadManifest(new File(source, 'release-bundle')))
                assertTrue(github.mutations.isEmpty())
                if (fault == 'advanced-resume') {
                    github.bundleDirectory.set(new File(source, 'release-bundle'))
                    github.publish()
                    assertEquals(['POST'], github.mutations)
                }
                return
            }
            github.bundleDirectory.set(bundle)
            if (fault in ['advanced', 'behind', 'diverged', 'unrelated', 'source', 'wrong-base']) {
                new File(source, 'advance.txt').text = 'later commit'
                commit(source)
                github.sourceCommit = ReleaseSelection.git(source, ['rev-parse', 'HEAD']).trim()
                github.comparisonBase = plan.sourceCommit
                if (!(fault in ['advanced', 'wrong-base'])) github.comparisonStatus = fault == 'behind' ? 'behind' : 'diverged'
                if (fault == 'wrong-base') github.comparisonMergeBase = github.tagCommit
            }
            if (fault == 'wrong-ref') github.branchRef = 'refs/heads/other'
            if (fault == 'unpin') {
                manifest.remove('releasePlanDigest')
                new File(bundle, 'release-manifest.json').text = CatalogTools.json(manifest)
                assertThrows(IllegalStateException) { PublicationSupport.loadManifest(bundle) }
                assertTrue(github.mutations.isEmpty())
                return
            }
            if (fault == 'local') {
                assertThrows(IllegalStateException) { github.publish() }
                assertTrue(github.mutations.isEmpty())
                platforms.bundleDirectory.set(bundle)
                assertThrows(IllegalStateException) { platforms.publish() }
                return
            }
            if (fault in ['tag', 'mod', 'duplicate', 'source', 'behind', 'diverged', 'unrelated', 'wrong-base', 'wrong-ref']) {
                if (fault == 'tag') github.tagCommit = '0' * 40
                if (fault == 'mod') github.release.assets.first().sha256 = 'c' * 64
                if (fault == 'duplicate') github.release.assets.add(new LinkedHashMap(github.release.assets.first()))
                assertThrows(IllegalStateException) { github.publish() }
                assertTrue(github.mutations.isEmpty())
                return
            }
            if (fault == 'resume') {
                github.failAfterUpload = true
                assertThrows(IllegalStateException) { github.publish() }
                assertEquals(['POST'], github.mutations)
                assertTrue(github.release.assets.any { it.id == 'previous-plugin' })
            }
            github.publish()
            List<String> expected = fault in ['replacement', 'resume'] ? ['POST', 'DELETE'] : ['POST']
            assertEquals(expected, github.mutations)
            assertEquals('Historical body', github.release.body)
            github.publish()
            assertEquals(expected, github.mutations)
            File receipt = new File(bundle, 'receipts/server-plugin.receipt.json')
            Map changed = new JsonSlurper().parse(receipt) as Map
            changed.sourceCommit = plan.tagCommit
            receipt.text = CatalogTools.json(changed)
            assertThrows(IllegalStateException) { PublicationSupport.loadManifest(bundle) }
        } finally { root.deleteDir() }
    }

    @Test
    void validateCallerSerializesSelectedPluginNotesWithoutModNotes() {
        File root = Files.createTempDirectory('plugin-only-validation-').toFile()
        try {
            File source = fixture(root)
            def project = ProjectBuilder.builder().withProjectDir(root).build()
            def task = project.tasks.create('validateFixture', ValidateReleaseTask)
            task.repositoryDirectory.set(source)
            task.catalogFile.set(new File(source, 'gradle/targets.json'))
            task.versionFile.set(new File(source, 'gradle/version.properties'))
            task.changelogFile.set(new File(source, 'CHANGELOG.md'))
            task.pluginChangelogFile.set(new File(source, 'PLUGIN_CHANGELOG.md'))
            File state = new File(root, 'state.json')
            state.text = CatalogTools.json(ServerPluginReleaseState.compute(source, CatalogTools.loadCatalog(source), '1.0.0.2'))
            task.serverPluginStateFile.set(state)
            task.releaseTag.set('1.0.0.2')
            task.releaseRoot.set(new File(root, 'validation'))
            task.validateRelease()
            Map metadata = new JsonSlurper().parse(new File(root, 'validation/1.0.0.2/release-metadata.json')) as Map
            assertNull(metadata.releaseNotes)
            assertFalse(new File(root, 'validation/1.0.0.2/release-notes.md').exists())
            assertEquals('Plugin fix\n', new File(root, 'validation/1.0.0.2/server-plugin-release-notes.md').text)
        } finally { root.deleteDir() }
    }

    @Test
    void actualGradlePreTagPreparationUsesPairedArchiveAndReceiptOwners() {
        File root = Files.createTempDirectory('plugin-local-gradle-').toFile()
        try {
            File source = fixture(root)
            ReleaseSelection.git(source, ['tag', '-d', '1.0.0'])
            new File(source, 'settings.gradle').text = "rootProject.name = 'local-preparation'\n"
            new File(source, 'build.gradle').text = '''
import com.naocraftlab.skins.buildlogic.*
plugins { id 'com.naocraftlab.skins.build-tools' }
tasks.register('preflightReleasePlatforms', PublishPlatformsTask)
tasks.register('preflightGithubRelease', PublishGithubReleaseTask)
def state = tasks.register('stateFixture', ComputeServerPluginReleaseStateTask) {
    repositoryDirectory.set(layout.projectDirectory)
    catalogFile.set(layout.projectDirectory.file('gradle/targets.json'))
    releaseTag.set('1.0.0.2')
    stateFile.set(layout.buildDirectory.file('state.json'))
}
tasks.register('validateFixture', ValidateReleaseTask) {
    dependsOn(state)
    repositoryDirectory.set(layout.projectDirectory)
    catalogFile.set(layout.projectDirectory.file('gradle/targets.json'))
    versionFile.set(layout.projectDirectory.file('gradle/version.properties'))
    changelogFile.set(layout.projectDirectory.file('CHANGELOG.md'))
    pluginChangelogFile.set(layout.projectDirectory.file('PLUGIN_CHANGELOG.md'))
    serverPluginStateFile.set(state.flatMap { it.stateFile })
    releaseTag.set('1.0.0.2')
    releaseRoot.set(layout.buildDirectory.dir('validation'))
}
def plan = tasks.register('planFixture', PlanReleaseTask) {
    repositoryDirectory.set(layout.projectDirectory)
    releaseTag.set('1.0.0.2')
    localPreparation.set(true)
    outputDirectory.set(layout.buildDirectory.dir('plan'))
}
def production = tasks.register('fixtureProduction', Jar) {
    archiveFileName.set('nclskins-plugin-1.0.0.2.jar')
    destinationDirectory.set(layout.projectDirectory.dir('server-plugin/build/libs'))
    from('gradle/plugin-version.properties')
}
def sources = tasks.register('fixtureSources', Jar) {
    archiveFileName.set('nclskins-plugin-1.0.0.2-sources.jar')
    destinationDirectory.set(layout.projectDirectory.dir('server-plugin/build/libs'))
    from('gradle/plugin-version.properties')
    from('PLUGIN_CHANGELOG.md')
}
def export = tasks.register('exportFixture', ExportReleaseComponentTask) {
    dependsOn(plan, production, sources)
    repositoryDirectory.set(layout.projectDirectory)
    planFile.set(plan.map { it.outputDirectory.file('release-plan.json').get() })
    componentId.set('server-plugin')
    outputDirectory.set(layout.buildDirectory.dir('components'))
}
tasks.register('assembleFixture', AssemblePlannedReleaseTask) {
    dependsOn(export)
    repositoryDirectory.set(layout.projectDirectory)
    planFile.set(plan.map { it.outputDirectory.file('release-plan.json').get() })
    componentDirectory.set(export.flatMap { it.outputDirectory })
    releaseRoot.set(layout.buildDirectory.dir('release'))
}
'''
            Map environment = new HashMap(System.getenv())
            ['GITHUB_TOKEN', 'GITHUB_REPOSITORY', 'MODRINTH_TOKEN', 'CURSEFORGE_API_KEY', 'CURSEFORGE_UPLOAD_TOKEN'].each { environment.remove(it) }
            def result = org.gradle.testkit.runner.GradleRunner.create().withProjectDir(source).withPluginClasspath()
                    .withTestKitDir(new File(root, 'test-kit')).withEnvironment(environment)
                    .withArguments('validateFixture', 'assembleFixture', '--console=plain', '--stacktrace').build()
            ['planFixture', 'fixtureProduction', 'fixtureSources', 'exportFixture', 'assembleFixture'].each {
                assertEquals(org.gradle.testkit.runner.TaskOutcome.SUCCESS, result.task(':' + it).outcome)
            }
            Map manifest = PublicationSupport.loadManifest(new File(source, 'build/release/1.0.0.2'))
            assertEquals('local', manifest.mode)
            assertNull(manifest.tagCommit)
            assertEquals([], manifest.targets)
            assertEquals(2, manifest.assets.size())
            assertFalse(new File(source, '.agents').exists())
            assertFalse(new File(source, 'openspec').exists())
        } finally { root.deleteDir() }
    }

    private static File fixture(File root) {
        File source = new File(root, 'source')
        source.mkdirs()
        File repository = new File(System.getProperty('repositoryRoot', '../..')).canonicalFile
        ['gradle/targets.json', 'gradle/version.properties', 'gradle/plugin-version.properties'].each { String path ->
            File original = new File(repository, path)
            if (original.isFile()) {
                File destination = new File(source, path)
                destination.parentFile.mkdirs()
                Files.copy(original.toPath(), destination.toPath())
            }
        }
        new File(source, 'CHANGELOG.md').delete()
        new File(source, 'gradle/version.properties').text = 'modVersion=1.0.0\n'
        new File(source, 'gradle/plugin-version.properties').text = 'pluginVersion=1.0.0\npluginBuild=1\n'
        ReleaseSelection.git(source, ['init', '-q', '-b', 'main'])
        commit(source)
        ReleaseSelection.git(source, ['tag', '1.0.0'])
        new File(source, 'gradle/version.properties').text = 'modVersion=2.0.0\n'
        new File(source, 'gradle/plugin-version.properties').text = 'pluginVersion=1.0.0\npluginBuild=2\n'
        new File(source, 'PLUGIN_CHANGELOG.md').text = '## 1.0.0.2\n\nPlugin fix\n'
        commit(source)
        source
    }

    private static void commit(File source) {
        ReleaseSelection.git(source, ['add', '.'])
        ReleaseSelection.git(source, ['-c', 'user.name=Fixture', '-c', 'user.email=fixture@example.invalid', '-c', 'commit.gpgsign=false', 'commit', '-qm', 'fixture'])
    }

    abstract static class EmptyPlatforms extends PlannedReleaseIntegrationTest.FixturePlatforms {
        @Override Map fetchAll(Map manifest, List<Map> targets, String token, String key) {
            components = targets
            [modrinth: targets.collectEntries { [(it.id): []] }, curseforge: targets.collectEntries { [(it.id): []] }]
        }
    }

    abstract static class HistoricalGithub extends PublishGithubReleaseTask {
        boolean forbidRequests
        boolean failAfterUpload
        String tagCommit
        String sourceCommit
        String comparisonBase
        String comparisonStatus = 'ahead'
        String comparisonMergeBase
        String branchRef = 'refs/heads/main'
        List<String> mutations = []
        Map release = [id: 1, name: 'Historical release', body: 'Historical body', prerelease: false,
                assets: [[id: 'historical-mod', name: 'nclskins-fabric-1.20.1-1.0.0.jar', sha256: 'a' * 64]]]
        @Override String requireEnvironment(String name) { 'fixture-token' }
        @Override String requireRepository() { 'fixture/repository' }
        @Override Map findRelease(String api, String repo, String tag, String token) {
            assertFalse(forbidRequests)
            assertEquals('1.0.0', tag)
            release
        }
        @Override String remoteSha256(String api, String repo, Map asset, String token) { asset.sha256 }
        @Override HttpResult request(String method, String url, Map<String, String> headers, byte[] body,
                String contentType, Set<Integer> statuses, String secret, boolean acceptJson = true) {
            assertFalse(forbidRequests)
            if (method == 'GET') {
                if (url.contains('/compare/')) {
                    assertTrue(url.endsWith("/${comparisonBase}...${sourceCommit}"))
                    return new HttpResult(200, CatalogTools.json([status: comparisonStatus, base_commit: [sha: comparisonBase],
                            merge_base_commit: [sha: comparisonMergeBase ?: comparisonBase]]).bytes)
                }
                return new HttpResult(200, CatalogTools.json([ref: branchRef, object: [type: 'commit', sha: url.contains('/heads/') ? sourceCommit : tagCommit]]).bytes)
            }
            mutations.add(method)
            if (method == 'DELETE') {
                assertTrue(release.assets.any { it.name == 'nclskins-plugin-1.0.0.2.jar' })
                release.assets.removeAll { it.id == 'previous-plugin' }
                return new HttpResult(204, new byte[0])
            }
            assertEquals('POST', method)
            String name = URLDecoder.decode(url.substring(url.indexOf('?name=') + 6), 'UTF-8')
            Map asset = [id: name, name: name, sha256: CatalogTools.sha256(body)]
            release.assets.add(asset)
            if (failAfterUpload) {
                failAfterUpload = false
                throw new IllegalStateException('fixture lost upload response')
            }
            new HttpResult(201, CatalogTools.json(asset).bytes)
        }
    }
}

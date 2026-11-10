package com.naocraftlab.skins.buildlogic

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Test

import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipFile

import static org.junit.jupiter.api.Assertions.*

final class ReleaseLogicTest {
    private final File repository = new File(System.getProperty('repositoryRoot', '../..')).canonicalFile

    @Test
    void publisherAdmitsExactStableAndMatchingPrereleaseSnapshots() {
        withBranchFixture { File work, String source, String main ->
            def remote = { String branch -> branch == 'main' ? main : source }
            assertEquals(source, ReleaseProcess.admit(work, '1.4.0-beta.1', 'workflow_dispatch',
                    'refs/heads/release/1.4.0', source, false, remote).source)
            ReleaseSelection.git(work, ['tag', '1.4.0-beta.1'])
            assertEquals(main, ReleaseProcess.admit(work, '1.4.0-beta.1', 'push',
                    'refs/tags/1.4.0-beta.1', source, false, remote).catalog)
            ReleaseSelection.git(work, ['checkout', '--detach', main])
            ReleaseSelection.git(work, ['tag', '1.4.0'])
            assertEquals(main, ReleaseProcess.admit(work, '1.4.0', 'push',
                    'refs/tags/1.4.0', main, false, remote).source)
            assertEquals(main, ReleaseProcess.admit(work, '1.4.0', 'workflow_dispatch',
                    'refs/heads/main', main, false, remote).source)
        }
    }

    @Test
    void branchAdmissionRejectsMismatchesDriftAndUnrelatedResumeSources() {
        withBranchFixture { File work, String source, String main ->
            def remote = { String branch -> branch == 'main' ? main : source }
            ['1.4.0', '1.4.1-beta.1'].each { version ->
                assertThrows(IllegalStateException) {
                    ReleaseProcess.admit(work, version, 'workflow_dispatch', 'refs/heads/release/1.4.0', source, false, remote)
                }
            }
            assertThrows(IllegalArgumentException) {
                ReleaseProcess.admit(work, '1.4.0-beta.x', 'workflow_dispatch', 'refs/heads/release/1.4.0', source, false, remote)
            }
            ['refs/heads/other', 'refs/heads/main', 'refs/tags/1.4.0-beta.1'].each { ref ->
                assertThrows(IllegalStateException) {
                    ReleaseProcess.admit(work, '1.4.0-beta.1', 'workflow_dispatch', ref, source, false, remote)
                }
            }
            assertThrows(IllegalStateException) {
                ReleaseProcess.admit(work, '1.4.0-beta.1', 'workflow_dispatch', 'refs/heads/release/1.4.0', main, false, remote)
            }
            assertThrows(IllegalStateException) {
                ReleaseProcess.admit(work, '1.4.0-beta.1', 'workflow_dispatch', 'refs/heads/release/1.4.0', source, false, { 'f' * 40 })
            }
            ReleaseSelection.git(work, ['merge-base', '--is-ancestor', main, source])
            ReleaseSelection.git(work, ['checkout', '--orphan', 'unrelated'])
            ReleaseSelection.git(work, ['-c', 'user.name=Fixture', '-c', 'user.email=fixture@example.invalid',
                    '-c', 'commit.gpgsign=false', 'commit', '--allow-empty', '-qm', 'unrelated'])
            String unrelated = ReleaseSelection.git(work, ['rev-parse', 'HEAD']).trim()
            assertThrows(IllegalStateException) { ReleaseSelection.git(work, ['merge-base', '--is-ancestor', unrelated, source]) }
        }
    }

    private static void withBranchFixture(Closure body) {
        File work = Files.createTempDirectory('release-branch-').toFile()
        try {
            ReleaseSelection.git(work, ['init', '-q', '-b', 'main'])
            ReleaseSelection.git(work, ['-c', 'user.name=Fixture', '-c', 'user.email=fixture@example.invalid',
                    '-c', 'commit.gpgsign=false', 'commit', '--allow-empty', '-qm', 'main'])
            String main = ReleaseSelection.git(work, ['rev-parse', 'HEAD']).trim()
            ReleaseSelection.git(work, ['checkout', '-qb', 'release/1.4.0'])
            ReleaseSelection.git(work, ['-c', 'user.name=Fixture', '-c', 'user.email=fixture@example.invalid',
                    '-c', 'commit.gpgsign=false', 'commit', '--allow-empty', '-qm', 'release'])
            body(work, ReleaseSelection.git(work, ['rev-parse', 'HEAD']).trim(), main)
        } finally { work.deleteDir() }
    }

    @Test
    void currentDevelopmentVersionMatchesTheModChangelogSection() {
        File versionFile = new File(repository, 'gradle/version.properties')
        File changelog = new File(repository, 'CHANGELOG.md')
        String currentVersion = CatalogTools.loadVersion(versionFile.toPath())
        List<String> lines = changelog.readLines()
        String currentHeading = "## ${currentVersion}"

        assertEquals(currentHeading, lines.find { !it.isBlank() })
        assertEquals(1, lines.count { it == currentHeading })
        int nextVersion = lines.findIndexOf(1) { it.startsWith('## ') }
        assertTrue(nextVersion > 0)
        assertFalse(lines.any { it.startsWith('# ') })

        if (lines.subList(1, nextVersion).every { it.isBlank() }) {
            IllegalArgumentException incomplete = assertThrows(IllegalArgumentException) {
                ReleaseMetadata.validate(versionFile, changelog, currentVersion)
            }
            assertTrue(incomplete.message.contains(
                    "CHANGELOG.md section '## ${currentVersion}' must contain release notes"))
        } else {
            Map metadata = ReleaseMetadata.validate(versionFile, changelog, currentVersion)
            assertEquals(currentVersion, metadata.version)
            String expectedChannel = currentVersion.contains('-alpha.') ? 'alpha'
                    : currentVersion.contains('-beta.') ? 'beta' : 'release'
            assertEquals(expectedChannel, metadata.channel)
            assertEquals(expectedChannel != 'release', metadata.prerelease)
        }

        File pluginChangelog = new File(repository, 'PLUGIN_CHANGELOG.md')
        List<String> pluginLines = pluginChangelog.readLines()
        assertTrue(pluginChangelog.isFile())
        assertFalse(new File(repository, 'SERVER_CHANGELOG.md').exists())
        String pluginVersion = ServerPluginVersion.load(repository)
        assertEquals("## ${pluginVersion}".toString(), pluginLines.find { !it.isBlank() })
        assertFalse(pluginLines.any { it.startsWith('# ') })
        Map state = [currentVersion: currentVersion, pluginVersion: pluginVersion,
                     publish: false, reason: 'unchanged']
        assertNull(ServerPluginChangelog.validate(pluginChangelog, state))

    }

    @Test
    void releaseVersionOrderingAcceptsUnboundedCanonicalNumbersAndRejectsMalformedTokens() {
        assertTrue(ServerPluginReleaseState.compareVersions('2147483648.0.0', '2147483647.999.999') > 0)
        assertTrue(ServerPluginReleaseState.compareVersions('1.0.0-beta.2147483648', '1.0.0-beta.2147483647') > 0)
        assertTrue(ServerPluginReleaseState.compareVersions('1.2147483648.0', '1.2147483647.999') > 0)
        ['01.0.0', '1.00.0', '1.0.00', '1.0.0-beta.01', '1.0.0-beta.1x'].each { invalid ->
            assertThrows(IllegalArgumentException) {
                ServerPluginReleaseState.compareVersions(invalid, '1.0.0')
            }
        }
    }

    @Test
    void pluginChangelogAcceptsBuildSuffixBeforePrereleaseQualifier() {
        withPluginChangelogFixture(
                '## 1.0.0.2-beta.3\n\nPlugin release notes\n\n## 1.0.0.1-beta.2\n\nOlder notes\n') {
            File pluginChangelog ->
                assertEquals('Plugin release notes\n', ServerPluginChangelog.validate(pluginChangelog, [
                        currentVersion: '1.0.0-beta.3', publish: true, reason: 'server-change'
                ]))
        }
    }

    @Test
    void validateReleaseConsumesSealedStateAndSeparateMarketplaceProjects() {
        Path fixture = Files.createTempDirectory('nclskins-release-validation-')
        try {
            Map catalog = CatalogTools.materialize(CatalogTools.loadCatalog(repository)) as Map
            catalog.serverPlugin.platforms.modrinth.projectId = 'AbCd1234'
            catalog.serverPlugin.platforms.curseforge.projectId = 1234567
            File catalogFile = fixture.resolve('targets.json').toFile()
            catalogFile.text = JsonOutput.toJson(catalog)
            File gradleDirectory = fixture.resolve('gradle').toFile()
            gradleDirectory.mkdirs()
            new File(gradleDirectory, 'version.properties').text = 'modVersion=1.0.0-beta.3\n'
            new File(gradleDirectory, 'plugin-version.properties').text = 'pluginVersion=1.0.0-beta.3\npluginBuild=1\n'
            Map state = ServerPluginReleaseState.compute(fixture.toFile(), catalog, '1.0.0-beta.3')
            File stateFile = ServerPluginReleaseState.write(
                    fixture.resolve('server-state.json').toFile(), state)
            File versionFile = fixture.resolve('version.properties').toFile()
            versionFile.text = 'modVersion=1.0.0-beta.3\n'
            File changelogFile = fixture.resolve('CHANGELOG.md').toFile()
            changelogFile.text = '## 1.0.0-beta.3\n\nMod release notes\n'
            File pluginChangelogFile = fixture.resolve('PLUGIN_CHANGELOG.md').toFile()
            pluginChangelogFile.text = '## 1.0.0.1-beta.3\n\nPlugin release notes\n'

            ValidateReleaseTask task = ProjectBuilder.builder().build().tasks.create(
                    'validateFixtureRelease', ValidateReleaseTask)
            task.repositoryDirectory.set(fixture.toFile())
            task.catalogFile.set(catalogFile)
            task.versionFile.set(versionFile)
            task.changelogFile.set(changelogFile)
            task.pluginChangelogFile.set(pluginChangelogFile)
            task.serverPluginStateFile.set(stateFile)
            task.releaseTag.set('1.0.0-beta.3')
            task.releaseRoot.set(fixture.resolve('release').toFile())

            task.validateRelease()

            Map metadata = new JsonSlurper().parse(
                    fixture.resolve('release/1.0.0-beta.3/release-metadata.json').toFile()) as Map
            assertEquals('joint', metadata.serverPlugin.reason)
            assertTrue(metadata.serverPlugin.publish)
            assertEquals('1.0.0-beta.3', metadata.serverPlugin.activeVersion)
            assertFalse(metadata.serverPlugin.containsKey('sourceCommit'))
        } finally {
            fixture.toFile().deleteDir()
        }
    }

    @Test
    void releaseWorkflowPinsBuildsAndResumesWithoutCompiling() {
        String workflow = new File(repository, '.github/workflows/release.yml').text
        assertTrue(workflow.contains('ref: ${{ github.sha }}'))
        assertTrue(workflow.contains('ref: ${{ needs.plan.outputs.source }}'))
        assertTrue(workflow.contains('fail-fast: false'))
        assertTrue(workflow.contains("needs.plan.outputs.build == 'true'"))
        assertTrue(workflow.contains("needs.plan.outputs.resumed != 'true'"))
        assertTrue(workflow.contains("needs.bundle.result == 'success' || needs.plan.outputs.resumed == 'true'"))
        assertTrue(workflow.contains('retention-days: 90'))
        assertTrue(workflow.contains('resume_run:'))
        assertFalse(workflow.contains('fullCheck'))
        assertFalse(workflow.contains('  checks:'))
        String components = workflow.substring(workflow.indexOf('  components:'), workflow.indexOf('  bundle:'))
        assertTrue(components.contains('NCLSKINS_SERVER_PLUGIN_BASELINE: ${{ needs.plan.outputs.baseline }}'))
        assertFalse(workflow.contains('historical_ref'))
        assertTrue(workflow.indexOf('Final preflight without writes') <
                workflow.indexOf('Publish missing marketplace files'))
        assertTrue(workflow.indexOf('Publish missing marketplace files') <
                workflow.indexOf('Publish GitHub production JARs'))
    }

    @Test
    void releaseDoesNotCreateAReplacementCandidateQualityJob() {
        assertFalse(new File(repository, '.github/workflows/release-candidate.yml').exists())
        String workflow = new File(repository, '.github/workflows/release.yml').text
        assertFalse(workflow.contains('  checks:'))
        ['checkRelease', 'fullCheck', 'testAll', 'jq ', 'gh api', 'git fetch', '.agents/', 'openspec/'].each {
            assertFalse(workflow.contains(it), it)
        }
        assertTrue(workflow.contains('needs: [plan, components]'))
        assertTrue(workflow.contains('run: ./gradlew planRelease -PreleaseTag="$VERSION"'))
    }

    @Test
    void componentBuildsInstallAndExportEveryCatalogBuildJdkWithoutSecrets() {
        String workflow = new File(repository, '.github/workflows/release.yml').text
        String components = workflow.substring(workflow.indexOf('  components:'), workflow.indexOf('  bundle:'))
        Set<Integer> required = ([17] + CatalogTools.loadCatalog(repository).targets.collect { it.java.buildJdk as int }).toSet()
        required.each { version ->
            assertTrue(components.contains("java-version: '${version}'"))
            assertTrue(components.contains('NCLSKINS_JAVA' + version + ': ${{ steps.java' + version + '.outputs.path }}'))
        }
        assertFalse(components.contains('secrets.'))
        assertFalse(components.contains('contents: write'))
    }

    @Test
    void buildLogicFailuresExposeFullExceptionsAndStackTraces() {
        def project = ProjectBuilder.builder().build()
        project.plugins.apply('java')
        String build = new File(repository, 'gradle/build-logic/build.gradle').text
        int start = build.indexOf('tasks.withType(Test).configureEach {')
        int end = build.indexOf('\n}\n', start)
        assertTrue(start >= 0 && end > start)
        Binding binding = new Binding([
                tasks: project.tasks,
                file: { Object path -> project.file(path) }
        ])
        new GroovyShell(binding).evaluate('import org.gradle.api.tasks.testing.Test\n' +
                'import org.gradle.api.tasks.PathSensitivity\n' + build.substring(start, end + 3))
        def test = project.tasks.named('test', org.gradle.api.tasks.testing.Test).get()
        assertEquals('false', test.systemProperties['junit.platform.stacktrace.pruning.enabled'])
        def logging = test.testLogging
        assertEquals(org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL, logging.exceptionFormat)
        assertTrue(logging.showExceptions)
        assertTrue(logging.showCauses)
        assertTrue(logging.showStackTraces)
        assertTrue(logging.stackTraceFilters.isEmpty())
        assertFalse(logging.showStandardStreams)
    }

    @Test
    void releaseActionsRemainPinnedAndCheckoutNeverPersistsCredentials() {
        String workflow = new File(repository, '.github/workflows/release.yml').text
        workflow.readLines().findAll { it.trim().startsWith('uses:') && !it.contains('./.github/') }.each {
            assertTrue(it ==~ /.*@[0-9a-f]{40}(?:\s+#.*)?/, it)
        }
        assertEquals(workflow.count('uses: actions/checkout@'), workflow.count('persist-credentials: false'))
        assertTrue(workflow.contains('contents: read'))
    }

    @Test
    void pagesWorkflowIsPinnedBoundedCredentialIsolatedAndReusable() {
        String pages = new File(
                repository, '.github/workflows/publish-update-catalog.yml').text

        assertTrue(pages.contains('workflow_call:'))
        assertTrue(pages.contains('ref: ${{ inputs.catalog_source_commit || github.sha }}'))
        assertFalse(pages.contains("github.event_name == 'workflow_call'"))
        assertTrue(pages.contains('workflow_dispatch:'))
        assertTrue(pages.contains('group: github-pages-update-catalog'))
        assertTrue(pages.contains('cancel-in-progress: false'))
        assertTrue(pages.contains('name: github-pages'))
        assertTrue(pages.contains('contents: read'))
        assertTrue(pages.contains('pages: write'))
        assertTrue(pages.contains('id-token: write'))
        assertTrue(pages.contains("NCLSKINS_CATALOG_WORKFLOW: 'true'"))
        assertTrue(pages.contains('generateUpdateCatalog'))
        assertTrue(pages.contains('build/update-catalog/site'))
        assertTrue(pages.contains(
                'actions/configure-pages@45bfe0192ca1faeb007ade9deae92b16b8254a0d'))
        assertTrue(pages.contains(
                'actions/upload-pages-artifact@fc324d3547104276b827a68afc52ff2a11cc49c9'))
        assertTrue(pages.contains(
                'actions/deploy-pages@cd2ce8fcbc39b97be8ca5fce6e763baed58fa128'))
        pages.readLines().findAll { it.trim().startsWith('uses:') }.each { String line ->
            assertTrue(line ==~ /.*@[0-9a-f]{40}(?:\s+#.*)?/, line)
        }
        assertFalse(pages.contains('MODRINTH_TOKEN'))
        assertFalse(pages.contains('CURSEFORGE'))
        assertFalse(pages.contains('secrets: inherit'))
        assertFalse(pages.contains('openspec/'))
        assertFalse(pages.contains('.agents/'))
    }

    @Test
    void catalogPublicationUsesFinalRemoteAssetsAndPreservesReleaseFailureSemantics() {
        String releaseWorkflow = new File(
                repository, '.github/workflows/release.yml').text
        String pagesWorkflow = new File(
                repository, '.github/workflows/publish-update-catalog.yml').text

        assertTrue(releaseWorkflow.contains('''  update-catalog:
    name: Publish static update catalog
    needs: [plan, publish]
'''))
        assertTrue(releaseWorkflow.contains("needs.publish.result == 'success'"))
        assertTrue(releaseWorkflow.contains(
                'catalog_source_commit: ${{ needs.plan.outputs.catalog }}'))
        assertFalse(releaseWorkflow.contains('secrets: inherit'))

        assertTrue(pagesWorkflow.contains('workflow_dispatch:'))
        assertTrue(pagesWorkflow.contains("NCLSKINS_CATALOG_WORKFLOW: 'true'"))
        assertFalse(pagesWorkflow.contains('RELEASE_MODE'))
        assertFalse(pagesWorkflow.contains('selectedTargetIds'))
        assertFalse(pagesWorkflow.contains('release-manifest.json'))
        assertTrue(pagesWorkflow.contains('verifyUpdateCatalogDeployment'))
        assertTrue(pagesWorkflow.indexOf('Deploy GitHub Pages') <
                pagesWorkflow.indexOf('Verify deployed endpoint bytes'))

        String publicationTests = new File(repository,
                'gradle/build-logic/src/test/groovy/com/naocraftlab/skins/' +
                'buildlogic/PublicationLogicTest.groovy').text
        assertTrue(publicationTests.contains(
                'githubPlanPublishesOnlyProductionAndRejectsSourceOrUnknownAssets'))
        assertTrue(publicationTests.contains('conflict.conflicts.size()'))
        String catalogTests = new File(repository,
                'gradle/build-logic/src/test/groovy/com/naocraftlab/skins/' +
                'buildlogic/UpdateCatalogGeneratorTest.groovy').text
        assertTrue(catalogTests.contains(
                'currentCatalogWinsOverHistoricalTagCatalogAndTagMoveAloneChangesNothing'))
        assertTrue(catalogTests.contains(
                'backfillAddsOnlyExactAssociationToExistingRelease'))
        assertTrue(catalogTests.contains(
                'partialReleaseAssociatesOnlyExactPublishedTargetArtifact'))
    }

    @Test
    void githubReleaseBodyContainsOnlyChangelogSectionsForPublishedComponents() {
        Map manifest = [
                targets     : [[id: 'fabric-26.2']],
                releaseNotes: [text: 'Mod changes\n'],
                serverPlugin: [publish: true, publication: [releaseNotes: 'Plugin changes\n']]
        ]

        assertEquals('''## Mod Changelog

Mod changes

## Plugin Changelog

Plugin changes
''', PublishGithubReleaseTask.releaseBody(manifest))

        manifest.serverPlugin = [publish: false, activeVersion: '1.0.0-beta.2']
        assertEquals('''## Mod Changelog

Mod changes
''', PublishGithubReleaseTask.releaseBody(manifest))

        manifest.targets = []
        manifest.serverPlugin = [publish: true, publication: [releaseNotes: 'Plugin changes\n']]
        assertEquals('''## Plugin Changelog

Plugin changes
''', PublishGithubReleaseTask.releaseBody(manifest))
    }

    @Test
    void githubCompatibilityBuildPreservesOriginalBodyExactly() {
        String original = 'Custom intro\r\n\r\n## Mod Changelog\n\nMod notes\n\n## Plugin Changelog\n\nFull first-build notes\n'
        Map manifest = [existingRelease: [body: original], pluginReplacement: [file: 'nclskins-plugin-1.0.0.1.jar'],
                        serverPlugin: [publish: true, publication: [releaseNotes: 'Compatibility build notes']]]
        assertEquals(original, PublishGithubReleaseTask.releaseBody(manifest))
        manifest.remove('pluginReplacement')
        manifest.preservedGithub = [[file: 'nclskins-plugin-1.0.0.2.jar']]
        assertEquals(original, PublishGithubReleaseTask.releaseBody(manifest))
    }

    @Test
    void firstPluginAppendsNotesToExistingModRelease() {
        String original = 'Custom mod release notes\n'
        Map manifest = [existingRelease: [body: original], preservedGithub: [[file: 'mod.jar']],
                        serverPlugin: [publish: true, publication: [releaseNotes: 'Full plugin notes\n']]]
        assertEquals(original + '\n\n## Plugin Changelog\n\nFull plugin notes\n',
                PublishGithubReleaseTask.releaseBody(manifest))
        manifest.serverPlugin.publish = false
        assertEquals(original, PublishGithubReleaseTask.releaseBody(manifest))
    }

    @Test
    void githubReleaseBodyRejectsEmptyOrMissingComponentNotes() {
        assertThrows(IllegalStateException) {
            PublishGithubReleaseTask.releaseBody([
                    targets: [], serverPlugin: [publish: false]
            ])
        }
        assertThrows(IllegalStateException) {
            PublishGithubReleaseTask.releaseBody([
                    targets: [[id: 'paper-26.2']], releaseNotes: [text: '  '],
                    serverPlugin: [publish: false]
            ])
        }
        assertThrows(IllegalStateException) {
            PublishGithubReleaseTask.releaseBody([
                    targets: [], serverPlugin: [publish: true, publication: [releaseNotes: '  ']]
            ])
        }
    }

    @Test
    void githubReleaseBodyUsesExtractedCanonicalComponentBodies() {
        withReleaseFixture(
                '1.0.0',
                '## 1.0.0\n\n### Added\n\n- Mod change\n') {
            File versionFile, File changelogFile ->
                Map mod = ReleaseMetadata.validate(versionFile, changelogFile, '1.0.0')
                withPluginChangelogFixture(
                        '## 1.0.0\n\n### Changed\n\n- Plugin change\n') {
                    File pluginChangelog ->
                        String plugin = ServerPluginChangelog.validate(pluginChangelog, [
                                currentVersion: '1.0.0', publish: true, reason: 'server-change'
                        ])
                        String body = PublishGithubReleaseTask.releaseBody([
                                targets: [[id: 'fabric-26.2']],
                                releaseNotes: [text: mod.notes],
                                serverPlugin: [publish: true, publication: [releaseNotes: plugin]]
                        ])

                        assertTrue(body.contains('## Mod Changelog\n\n### Added'))
                        assertTrue(body.contains('## Plugin Changelog\n\n### Changed'))
                        assertFalse(body.contains('## 1.0.0'))
                        assertFalse(body.contains('# NCL Skins Plugin changelog'))
                }
        }
    }

    @Test
    void stableAlphaAndBetaTagsAreClassifiedStrictly() {
        ['1.0.0': false, '1.0.0-alpha.1': true, '1.1.0-beta.2': true].each {
            String version, boolean prerelease ->
                withReleaseFixture(version, "## ${version}\n\nNotes for ${version}\n") {
                    File versionFile, File changelogFile ->
                        Map metadata = ReleaseMetadata.validate(versionFile, changelogFile, version)
                        assertEquals(prerelease, metadata.prerelease)
                        assertEquals(prerelease ? (version.contains('-alpha.') ? 'alpha' : 'beta') : 'release',
                                metadata.channel)
                        assertEquals("Notes for ${version}\n".toString(), metadata.notes)
                }
        }
    }


    @Test
    void invalidTagsAreRejectedWithEnglishGuidance() {
        withReleaseFixture('1.0.0-alpha.1', '## 1.0.0-alpha.1\n\nNotes\n') {
            File versionFile, File changelogFile ->
                ['v1.0.0', '1.0.0-aplha.1', '01.0.0', '1.0.0-alpha.0'].each { String tag ->
                    IllegalArgumentException failure = assertThrows(IllegalArgumentException) {
                        ReleaseMetadata.validate(versionFile, changelogFile, tag)
                    }
                    assertTrue(failure.message.contains('Release tag'))
                    assertTrue(failure.message.contains('invalid'))
                }
        }
    }

    @Test
    void versionMismatchIsRejectedBeforeChangelogExtraction() {
        withReleaseFixture('1.0.0-alpha.1', '## 1.1.0-beta.2\n\nNotes\n') {
            File versionFile, File changelogFile ->
                IllegalArgumentException failure = assertThrows(IllegalArgumentException) {
                    ReleaseMetadata.validate(versionFile, changelogFile, '1.1.0-beta.2')
                }
                assertTrue(failure.message.contains("does not match modVersion '1.0.0-alpha.1'"))
        }
    }

    @Test
    void nextModBetaVersionIsPublishedAsPrereleaseMetadata() {
        String version = '1.2.0-beta.1'
        withReleaseFixture(version, "## ${version}\n\nRelease candidate notes\n") {
            File versionFile, File changelogFile ->
                Map metadata = ReleaseMetadata.validate(versionFile, changelogFile, version)
                assertEquals(version, metadata.version)
                assertEquals('beta', metadata.channel)
                assertTrue(metadata.prerelease)
                assertEquals('Release candidate notes\n', metadata.notes)
        }
    }

    @Test
    void changelogSectionMustExistExactlyOnceAndContainNotes() {
        ['## 1.0.0\n\nFirst\n\n## 1.0.0\n\nSecond\n': 'found 2',
         '## 1.0.0\n\n\n'                           : 'must contain release notes'].each {
            String changelog, String expected ->
                withReleaseFixture('1.0.0', changelog) { File versionFile, File changelogFile ->
                    IllegalArgumentException failure = assertThrows(IllegalArgumentException) {
                        ReleaseMetadata.validate(versionFile, changelogFile, '1.0.0')
                    }
                    assertTrue(failure.message.contains(expected), failure.message)
                }
        }
    }

    @Test
    void changelogMustBeVersionFirstAndHaveNoLevelOneHeading() {
        ['# Changelog\n\n## 1.0.0\n\nNotes\n',
         'Preamble\n\n## 1.0.0\n\nNotes\n',
         '## 0.9.0\n\nOlder\n\n## 1.0.0\n\nNotes\n',
         '## 1.0.0\n\nNotes\n\n# Appendix\n'].each { String changelog ->
            withReleaseFixture('1.0.0', changelog) { File versionFile, File changelogFile ->
                IllegalArgumentException failure = assertThrows(IllegalArgumentException) {
                    ReleaseMetadata.validate(versionFile, changelogFile, '1.0.0')
                }
                assertTrue(failure.message.contains(
                        "CHANGELOG.md must start with '## 1.0.0'"), failure.message)
            }
        }
    }

    @Test
    void pluginChangelogRejectsPreambleDuplicatesEmptyAndLegacyOnlyPath() {
        Map state = [currentVersion: '1.0.0', publish: true, reason: 'server-change']
        ['# NCL Skins Plugin changelog\n\n## 1.0.0\n\nNotes\n': 'must start',
         'Preamble\n\n## 1.0.0\n\nNotes\n'                    : 'must start',
         '## 1.0.0\n\nFirst\n\n## 1.0.0\n\nSecond\n'       : 'exactly one',
         '## 1.0.0\n\n\n'                                      : 'is empty',
         '## 1.0.0\n\nNotes\n\n# Appendix\n'                  : 'must start'].each {
            String changelog, String expected ->
                withPluginChangelogFixture(changelog) { File pluginChangelog ->
                    IllegalArgumentException failure = assertThrows(IllegalArgumentException) {
                        ServerPluginChangelog.validate(pluginChangelog, state)
                    }
                    assertTrue(failure.message.contains(expected), failure.message)
                }
        }

        Path fixture = Files.createTempDirectory('nclskins-plugin-changelog-legacy-')
        try {
            Files.writeString(
                    fixture.resolve('SERVER_CHANGELOG.md'), '## 1.0.0\n\nLegacy notes\n')
            IllegalArgumentException failure = assertThrows(IllegalArgumentException) {
                ServerPluginChangelog.validate(
                        fixture.resolve('PLUGIN_CHANGELOG.md').toFile(), state)
            }
            assertEquals('PLUGIN_CHANGELOG.md is missing', failure.message)
        } finally {
            fixture.toFile().deleteDir()
        }
    }

    @Test
    void unselectedPluginDoesNotReadOrValidateUnselectedNotes() {
        Map state = [currentVersion: '1.0.1', publish: false, reason: 'unchanged']
        withPluginChangelogFixture('## 1.0.0\n\nPrevious notes\n') { File pluginChangelog ->
            assertNull(ServerPluginChangelog.validate(pluginChangelog, state))
        }
        withPluginChangelogFixture('## 1.0.1\n\nUnselected notes\n') { File pluginChangelog ->
            assertNull(ServerPluginChangelog.validate(pluginChangelog, state))
            pluginChangelog.delete()
            assertNull(ServerPluginChangelog.validate(pluginChangelog, state))
        }
    }

    @Test
    void changelogExtractionStopsAtNextVersionAndPreservesMarkdown() {
        String expected = '### Added\n\n- **Feature**\n    - Detail\n'
        withReleaseFixture(
                '1.1.0',
                "## 1.1.0\n\n${expected}\n## 1.0.0\n\nOlder notes\n") {
            File versionFile, File changelogFile ->
                Map metadata = ReleaseMetadata.validate(versionFile, changelogFile, '1.1.0')
                assertEquals(expected, metadata.notes)

                Path output = Files.createTempDirectory('nclskins-release-metadata-')
                try {
                    File versionDirectory = ReleaseMetadata.write(output.toFile(), metadata)
                    assertEquals(expected, new File(versionDirectory, 'release-notes.md').text)
                    Map json = new JsonSlurper().parse(
                            new File(versionDirectory, 'release-metadata.json')) as Map
                    assertEquals(
                            [schemaVersion: 3, version: '1.1.0', channel: 'release', prerelease: false,
                             releaseNotes : 'release-notes.md'],
                            json)
                } finally {
                    output.toFile().deleteDir()
                }
        }
    }

    @Test
    void allTargetSourcesJarIsDeterministicAndKeepsRepositoryPaths() {
        Path fixture = Files.createTempDirectory('nclskins-release-sources-')
        try {
            Files.writeString(fixture.resolve('LICENSE'), 'license')
            Files.writeString(fixture.resolve('NOTICE'), 'notice')
            write(fixture, 'bundle-a/src/main/java/example/Same.java', 'class SameA {}')
            write(fixture, 'bundle-b/src/main/java/example/Same.java', 'class SameB {}')
            write(fixture, 'bundle-b/src/main/resources/asset.txt', 'asset')
            write(fixture, 'bundle-b/src/main/resources/private.pixel.json', '{}')
            write(fixture, 'bundle-b/src/main/resources/build/generated.txt', 'generated')
            write(fixture, 'bundle-b/src/main/resources/generated/Binding.java', 'generated')
            Map catalog = [sourceBundles: [
                    first : [java: ['bundle-a/src/main/java'], resources: []],
                    second: [java     : ['bundle-b/src/main/java'],
                             resources: ['bundle-b/src/main/resources']]
            ]]
            File first = fixture.resolve('first.jar').toFile()
            File second = fixture.resolve('second.jar').toFile()

            ReleaseBundle.createSourcesJar(fixture.toFile(), catalog, '1.0.0', first)
            ReleaseBundle.createSourcesJar(fixture.toFile(), catalog, '1.0.0', second)

            assertArrayEquals(first.bytes, second.bytes)
            new ZipFile(first).withCloseable { archive ->
                List<String> names = archive.entries().collect { it.name }
                assertEquals(
                        ['META-INF/MANIFEST.MF', 'META-INF/LICENSE', 'META-INF/NOTICE'],
                        names.take(3))
                List<String> sourceNames = new ArrayList<>(names.drop(3))
                List<String> sortedSourceNames = new ArrayList<>(sourceNames)
                sortedSourceNames.sort()
                assertEquals(sortedSourceNames, sourceNames)
                assertTrue(names.contains('META-INF/LICENSE'))
                assertTrue(names.contains('META-INF/NOTICE'))
                assertTrue(names.contains('bundle-a/src/main/java/example/Same.java'))
                assertTrue(names.contains('bundle-b/src/main/java/example/Same.java'))
                assertTrue(names.contains('bundle-b/src/main/resources/asset.txt'))
                assertFalse(names.any { it.contains('generated.txt') })
                assertFalse(names.any { it.contains('Binding.java') })
                assertFalse(names.any { it.endsWith('.pixel.json') })
            }
        } finally {
            fixture.toFile().deleteDir()
        }
    }

    private static void withReleaseFixture(
            String version,
            String changelog,
            Closure<?> assertion) {
        Path fixture = Files.createTempDirectory('nclskins-release-metadata-')
        try {
            File versionFile = fixture.resolve('version.properties').toFile()
            File changelogFile = fixture.resolve('CHANGELOG.md').toFile()
            versionFile.text = "modVersion=${version}\n"
            changelogFile.text = changelog
            assertion.call(versionFile, changelogFile)
        } finally {
            fixture.toFile().deleteDir()
        }
    }

    private static void withPluginChangelogFixture(
            String changelog,
            Closure<?> assertion) {
        Path fixture = Files.createTempDirectory('nclskins-plugin-changelog-')
        try {
            File changelogFile = fixture.resolve('PLUGIN_CHANGELOG.md').toFile()
            changelogFile.text = changelog
            assertion.call(changelogFile)
        } finally {
            fixture.toFile().deleteDir()
        }
    }

    private static void write(Path root, String relative, String value) {
        Path destination = root.resolve(relative)
        Files.createDirectories(destination.parent)
        Files.writeString(destination, value)
    }
}

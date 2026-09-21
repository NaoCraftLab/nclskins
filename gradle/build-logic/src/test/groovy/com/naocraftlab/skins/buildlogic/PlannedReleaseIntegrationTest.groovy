package com.naocraftlab.skins.buildlogic


import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Test

import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

import static org.junit.jupiter.api.Assertions.*

final class PlannedReleaseIntegrationTest {
    private final File repository = new File('../..').canonicalFile

    @Test
    void planRecoverAssembleAndRecheckNeoForgeOnlyWithoutHistoricalBuilds() {
        verifyPlan(false)
    }

    @Test
    void planNewPluginBuildPreservesModsAndReplacesOnlyOldPlugin() {
        verifyPlan(true)
    }

    @Test
    void freshBetaPlansExportsAndAssemblesEveryEligibleComponent() {
        verifyPlan(false, true)
    }

    @Test
    void compatibilityBuildPreservesBaselineButNewProtocolKeepsOldFunctionalVersion() {
        File fixture = Files.createTempDirectory('plugin-baseline-integration-').toFile()
        try {
            File source = fixtureRepository(new File(fixture, 'source'), false, false)
            new File(source, 'gradle/version.properties').text = 'modVersion=1.1.0-beta.1\n'
            new File(source, 'gradle/plugin-version.properties').text = 'pluginVersion=1.0.0\npluginBuild=2\n'
            Map catalog = CatalogTools.loadCatalog(source)
            catalog.serverPlugin.matrixId = 'fixture-compatibility-update'
            Map state = ServerPluginReleaseState.compute(source, catalog, '1.1.0-beta.1')
            assertTrue(state.publish)
            assertEquals('1.0.0.2', state.pluginVersion)
            assertEquals('1.0.0', state.activeVersion)
            catalog.serverPlugin.protocols = ['fixture-new-protocol']
            Map protocolUpdate = ServerPluginReleaseState.compute(source, catalog, '1.1.0-beta.1')
            assertTrue(protocolUpdate.publish)
            assertEquals('1.0.0.2', protocolUpdate.pluginVersion)
            assertEquals('1.0.0', protocolUpdate.activeVersion)
        } finally { fixture.deleteDir() }
    }

    private void verifyPlan(boolean replacePlugin, boolean fresh = false) {
        File fixture = Files.createTempDirectory('planned-release-integration-').toFile()
        try {
            File source = fixtureRepository(new File(fixture, 'source'), fresh, replacePlugin)
            def project = ProjectBuilder.builder().withProjectDir(fixture).build()
            FixturePlatforms platform = project.tasks.create('preflightReleasePlatforms', FixturePlatforms)
            platform.filesDirectory = new File(fixture, 'remote-files')
            platform.filesDirectory.mkdirs()
            platform.replacePlugin = replacePlugin
            platform.fresh = fresh
            platform.repository = source
            platform.version = CatalogTools.loadVersion(source)
            if (fresh) {
                File pluginVersion = new File(source, 'gradle/plugin-version.properties')
                String originalVersion = pluginVersion.text
                pluginVersion.text = 'pluginVersion=1.0.0\npluginBuild=2\n'
                assertTrue(assertThrows(IllegalStateException) {
                    ServerPluginReleaseState.compute(source, CatalogTools.loadCatalog(source), platform.version)
                }.message.contains('Plugin functional version must match release'))
                pluginVersion.text = originalVersion
            }
            platform.baseline = ServerPluginReleaseState.compute(source, CatalogTools.loadCatalog(source), platform.version).activeVersion
            FixtureGithub github = project.tasks.create('preflightGithubRelease', FixtureGithub)
            github.platform = platform
            github.commit = ReleaseSelection.git(source, ['rev-parse', "refs/tags/${platform.version}^{commit}"]).trim()
            FixturePlanner planner = project.tasks.create('planFixture', FixturePlanner)
            planner.replacePlugin = replacePlugin
            planner.fresh = fresh
            planner.filesDirectory = platform.filesDirectory
            planner.repositoryDirectory.set(source)
            planner.releaseTag.set(platform.version)
            File planDirectory = new File(fixture, 'plan')
            planner.outputDirectory.set(planDirectory)
            planner.planRelease()
            Map plan = ReleasePlan.load(new File(planDirectory, 'release-plan.json'))
            if (fresh) {
                assertEquals(CatalogTools.releaseTargets(CatalogTools.loadCatalog(source))*.id, plan.buildTargetIds)
                assertEquals(12, plan.buildTargetIds.size())
                assertTrue(plan.buildPlugin)
                assertTrue(plan.components.every { it.build && !it.preserve && it.channel == 'beta' })
                assertEquals('1.1.0-beta.1', plan.serverState.activeVersion)
                assertEquals('1.1.0.1-beta.1', plan.serverState.pluginVersion)
                assertTrue(plan.preservedGithub.isEmpty())
                assertNull(plan.pluginReplacement)
            } else {
                assertEquals(['neoforge-26.3'], plan.buildTargetIds)
                assertEquals(replacePlugin, plan.buildPlugin)
                assertEquals(replacePlugin ? 11 : 12, plan.components.count { it.preserve })
                assertEquals(replacePlugin ? 11 : 12, plan.preservedGithub.size())
                Map plugin = (plan.components as List<Map>).find { it.id == 'server-plugin' }
                assertEquals("${platform.version}.${replacePlugin ? 2 : 1}".toString(), plugin.versionNumber)
                assertEquals("${platform.version}.${replacePlugin ? 2 : 1}+universal".toString(), plugin.name)
                Map preservedPlugin = (plan.preservedGithub as List<Map>).find {
                    it.assetFile == "nclskins-plugin-${platform.version}.jar"
                }
                if (replacePlugin) {
                    assertNull(preservedPlugin)
                    assertEquals("nclskins-plugin-${platform.version}.1.jar".toString(), plan.pluginReplacement.file)
                } else {
                    assertEquals("nclskins-plugin-${platform.version}.1.jar".toString(), preservedPlugin.file)
                }
            }
            assertTrue(platform.requests.every { it.startsWith('GET ') })
            (plan.components as List<Map>).findAll { it.build }.each { Map component ->
                String componentPath = component.id == 'server-plugin' ? 'server-plugin' :
                        CatalogTools.selectTarget(CatalogTools.loadCatalog(source), component.id.toString()).path
                ['asset', 'sourcesAsset'].each { String key ->
                    File destination = new File(source, "${componentPath}/build/libs/${component[key].file}")
                    FixturePlatforms.writeJar(destination, destination.name, platform.baseline)
                }
                ExportReleaseComponentTask exporter = project.tasks.create(
                        "export-${component.id}", ExportReleaseComponentTask)
                exporter.repositoryDirectory.set(source)
                exporter.planFile.set(new File(planDirectory, 'release-plan.json'))
                exporter.componentId.set(component.id.toString())
                exporter.outputDirectory.set(new File(planDirectory, 'assets'))
                exporter.exportComponent()
            }
            AssemblePlannedReleaseTask assembly = project.tasks.create('assembleFixture', AssemblePlannedReleaseTask)
            assembly.repositoryDirectory.set(source)
            assembly.planFile.set(new File(planDirectory, 'release-plan.json'))
            assembly.componentDirectory.set(new File(planDirectory, 'assets'))
            assembly.releaseRoot.set(new File(fixture, 'release'))
            assembly.assemble()
            File bundle = new File(fixture, "release/${platform.version}")
            Map manifest = PublicationSupport.loadManifest(bundle)
            assertEquals(fresh ? 0 : replacePlugin ? 11 : 12, manifest.preservedTargetIds.size())
            assertEquals(12, manifest.targets.size())
            Map inventory = platform.fetchAll(manifest, PublishPlatformsTask.publicationTargets(manifest), 'fixture', 'fixture')
            Map states = platform.classifyPerTarget(PublishPlatformsTask.publicationTargets(manifest), inventory)
            PublishPlatformsTask.requirePreserved(manifest, states)
            ['modrinth', 'curseforge'].each { String name ->
                assertEquals(fresh ? plan.components*.id : replacePlugin ? ['neoforge-26.3', 'server-plugin'] : ['neoforge-26.3'], states[name].findAll { id, state -> state.action == 'upload' }.keySet() as List)
            }
            Map githubPlan = GithubReleaseSupport.plan(manifest, fresh ? [] : github.release().assets as List<Map>) { it.sha256 }
            assertTrue(githubPlan.conflicts.isEmpty())
            assertEquals(fresh ? 0 : replacePlugin ? 11 : 12, githubPlan.actions.count { it.action == 'keep' })
            assertEquals(fresh ? 13 : replacePlugin ? 2 : 1, githubPlan.actions.count { it.action == 'upload' })
            if (fresh) {
                assertTrue(manifest.prerelease)
                assertEquals('beta', manifest.channel)
                assertEquals(26, manifest.assets.size())
                assertEquals('nclskins-plugin-1.1.0.1-beta.1.jar', manifest.serverPlugin.artifact.file)
                assertEquals('1.1.0-beta.1', manifest.serverPlugin.activeVersion)
                PublishPlatformsTask.publicationTargets(manifest).each { Map target ->
                    Map targetManifest = PublishPlatformsTask.manifestForTarget(manifest, target)
                    assertEquals('beta', PublicationSupport.modrinthMetadata(targetManifest, target).version_type)
                    assertEquals('beta', target.channel)
                }
                assertTrue(githubPlan.actions.every { !it.file?.endsWith('-sources.jar') })
            }
            if (replacePlugin) {
                String originalBody = github.body
                assertEquals('Fixture plugin notes', manifest.serverPlugin.publication.releaseNotes.toString().trim())
                github.bundleDirectory.set(bundle)
                github.publish()
                assertEquals(['POST', 'POST', 'DELETE'], github.mutations)
                github.publish()
                assertEquals(['POST', 'POST', 'DELETE'], github.mutations)
                assertEquals(originalBody, github.body)
                assertFalse(github.release().assets.any { it.name == plan.pluginReplacement.file })
            }
            assembly.assemble()
            assertEquals(manifest, PublicationSupport.loadManifest(bundle))
            File tamper = new File(bundle, 'assets/' + manifest.targets.first().asset.file)
            Files.writeString(tamper.toPath(), 'tampered')
            assertThrows(IllegalStateException) { PublicationSupport.loadManifest(bundle) }
        } finally { fixture.deleteDir() }
    }

    private File fixtureRepository(File source, boolean fresh, boolean replacePlugin) {
        source.mkdirs()
        ReleaseSelection.git(repository, ['ls-files', '--cached', '--others', '--exclude-standard']).readLines().unique().each { String path ->
            File original = new File(repository, path)
            if (original.isFile()) {
                File destination = new File(source, path)
                destination.parentFile.mkdirs()
                Files.copy(original.toPath(), destination.toPath())
            }
        }
        String version = fresh ? '1.1.0-beta.1' : '1.0.0'
        String pluginVersion = ServerPluginVersion.parse("pluginVersion=${version}\npluginBuild=${replacePlugin ? 2 : 1}\n")
        new File(source, 'gradle/version.properties').text = "modVersion=${version}\n"
        new File(source, 'gradle/plugin-version.properties').text = "pluginVersion=${version}\npluginBuild=${replacePlugin ? 2 : 1}\n"
        new File(source, 'CHANGELOG.md').text = "## ${version}\n\nFixture mod notes\n"
        new File(source, 'PLUGIN_CHANGELOG.md').text = "## ${pluginVersion}\n\nFixture plugin notes\n"
        ReleaseSelection.git(source, ['init', '-q'])
        ReleaseSelection.git(source, ['add', '.'])
        ReleaseSelection.git(source, ['-c', 'user.name=Fixture', '-c', 'user.email=fixture@example.invalid',
                                      '-c', 'commit.gpgsign=false', 'commit', '-qm', 'Release fixture'])
        ReleaseSelection.git(source, ['tag', version])
        source
    }

    abstract static class FixturePlanner extends PlanReleaseTask {
        File filesDirectory
        boolean replacePlugin
        boolean fresh
        @Override Map pluginState(File repository, Map catalog, String version) {
            Map state = super.pluginState(repository, catalog, version)
            if (!fresh) state.pluginVersion = replacePlugin ? "${version}.2".toString() : version
            state
        }
        @Override String pluginReleaseNotes(File repository, Map state) {
            fresh ? super.pluginReleaseNotes(repository, state) : 'Fixture plugin notes\n'
        }

        @Override File download(String url, File destination, Map hashes) {
            File source = new File(filesDirectory, url.substring(url.lastIndexOf('/') + 1))
            assertEquals(hashes.sha512, ReleaseBundle.sha512(source))
            Files.copy(source.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
            destination
        }
    }

    abstract static class FixturePlatforms extends PublishPlatformsTask {
        File filesDirectory
        String version
        boolean replacePlugin
        String baseline
        boolean fresh
        File repository
        List<Map> components = []
        List<String> requests = []

        @Override String requireSecret(String name) { 'fixture-secret' }

        @Override Map<String, Map<String, List<Map>>> fetchAll(Map manifest, List<Map> targets, String token, String key) {
            components = targets
            Map result = [modrinth: [:], curseforge: [:]]
            targets.eachWithIndex { Map original, int index ->
                Map target = CatalogTools.materialize(original) as Map
                if (replacePlugin && target.id == 'server-plugin') {
                    target.versionNumber = "${version}.1".toString()
                    target.name = "${version}.1+universal".toString()
                    target.asset.file = "nclskins-plugin-${version}.jar".toString()
                    target.sourcesAsset.file = "nclskins-plugin-${version}-sources.jar".toString()
                    target.gameVersions = target.gameVersions.findAll { it != '26.3' }
                }
                if (fresh || target.id == 'neoforge-26.3') {
                    result.modrinth[target.id] = []
                    result.curseforge[target.id] = []
                    return
                }
                ['asset', 'sourcesAsset'].each { String field ->
                    Map expected = target[field] as Map
                    File file = new File(filesDirectory, expected.file.toString())
                    if (!file.exists()) writeJar(file, file.name, baseline)
                    target[field] = AssembleReleaseTask.assetMetadata(file, expected.kind.toString(), expected.target?.toString())
                }
                if (target.id == 'server-plugin' && target.versionNumber == version) {
                    target.versionNumber = "${target.versionNumber}.1"
                    target.name = "${target.versionNumber}+universal"
                }
                Map mr = PublicationSupport.modrinthMetadata(manifestForTarget(manifest, target), target)
                mr.id = "mr-${index}"
                mr.files = [[filename: target.asset.file, primary: true, file_type: null,
                             hashes: [sha1: target.asset.sha1, sha512: target.asset.sha512], url: "fixture/${target.asset.file}".toString()],
                            [filename: target.sourcesAsset.file, primary: false, file_type: 'sources-jar',
                             hashes: [sha1: target.sourcesAsset.sha1, sha512: target.sourcesAsset.sha512], url: "fixture/${target.sourcesAsset.file}".toString()]]
                if (target.id == 'server-plugin') mr.environment = 'unknown'
                Map cf = [id: index * 2 + 1, displayName: target.name, releaseType: target.channel,
                          gameVersions: PublicationSupport.curseForgeMetadata(manifestForTarget(manifest, target), target).gameVersionNames,
                          fileName: target.asset.file, hashes: [[algo: 1, value: target.asset.sha1]],
                          dependencies: target.dependencies.curseforge.collect { [modId: it.projectId, relationType: it.type == 'required' ? 3 : 2] }]
                Map sources = [id: index * 2 + 2, parentProjectFileId: cf.id,
                               fileName: target.sourcesAsset.file, hashes: [[algo: 1, value: target.sourcesAsset.sha1]]]
                result.modrinth[target.id] = [mr]
                result.curseforge[target.id] = target.id == 'server-plugin' ? [cf] : [cf, sources]
            }
            result
        }

        @Override HttpResult request(String method, String url, Map<String, String> headers, byte[] body,
                String contentType, Set<Integer> statuses, List<String> secrets) {
            requests.add("${method} ${url}".toString())
            Object response
            if (url.endsWith('/tag/game_version')) response = components.collectMany { it.gameVersions }.unique().collect { [version: it] }
            else if (url.endsWith('/tag/loader')) response = components.collectMany { PublicationSupport.desiredLoaders(it) as List }.unique().collect { [name: it] }
            else if (url.endsWith('/api/game/versions')) response = components.collectMany {
                PublicationSupport.curseForgeMetadata([releaseNotes: [text: '']], it).gameVersionNames
            }.unique().collect { [name: it] }
            else if (url.endsWith('/user')) response = [:]
            else throw new AssertionError("Unexpected request ${method} ${url}")
            new HttpResult(200, CatalogTools.json(response))
        }

        static void writeJar(File file, String marker, String baseline) {
            file.parentFile.mkdirs()
            new ZipOutputStream(Files.newOutputStream(file.toPath())).withCloseable { zip ->
                zip.putNextEntry(new ZipEntry('nclskins-server-compatibility.json'))
                zip.write(CatalogTools.json([requiredServerPluginVersion: baseline, marker: marker]).getBytes('UTF-8'))
                zip.closeEntry()
            }
        }
    }

    abstract static class FixtureGithub extends PublishGithubReleaseTask {
        FixturePlatforms platform
        String commit
        String body = 'Original release notes'
        List<String> mutations = []
        boolean initialized
        @Override String requireEnvironment(String name) { 'fixture-token' }
        @Override String requireRepository() { 'fixture/repository' }
        @Override Map findRelease(String api, String repo, String tag, String token) {
            if (platform.fresh) return null
            if (initialized) return release()
            initialized = true
            Map catalog = CatalogTools.loadCatalog(platform.repository)
            CatalogTools.releaseTargets(catalog).findAll { it.id != 'neoforge-26.3' }.each { Map target ->
                FixturePlatforms.writeJar(new File(platform.filesDirectory, AssembleReleaseTask.artifactName(target, platform.version)),
                        AssembleReleaseTask.artifactName(target, platform.version), platform.baseline)
            }
            String plugin = "nclskins-plugin-${platform.version}.jar"
            FixturePlatforms.writeJar(new File(platform.filesDirectory, plugin), plugin, platform.baseline)
            release()
        }
        Map release() {
            [id: 1, body: body, name: 'Existing release', prerelease: false,
             assets: platform.filesDirectory.listFiles().findAll { !it.name.endsWith('-sources.jar') }.collect {
                 String name = it.name == "nclskins-plugin-${platform.version}.jar"
                         ? "nclskins-plugin-${platform.version}.1.jar" : it.name
                 [id: name, name: name, sha256: ReleaseBundle.sha256(it)]
             }]
        }
        @Override String remoteSha256(String api, String repo, Map asset, String token) { asset.sha256 }
        @Override HttpResult request(String method, String url, Map<String, String> headers, byte[] body,
                String contentType, Set<Integer> statuses, String secret, boolean acceptJson = true) {
            if (method == 'GET') return new HttpResult(200, CatalogTools.json([object: [type: 'commit', sha: commit]]).getBytes('UTF-8'))
            mutations.add(method)
            if (method == 'PATCH') {
                this.body = new groovy.json.JsonSlurper().parse(body).body
                return new HttpResult(200, CatalogTools.json(release()).getBytes('UTF-8'))
            }
            if (method == 'POST') {
                String name = URLDecoder.decode(url.substring(url.indexOf('?name=') + 6), 'UTF-8')
                new File(platform.filesDirectory, name).bytes = body
                return new HttpResult(201, CatalogTools.json([id: name]).getBytes('UTF-8'))
            }
            if (method == 'DELETE') {
                assertTrue(new File(platform.filesDirectory, "nclskins-plugin-${platform.version}.2.jar").isFile())
                assertTrue(new File(platform.filesDirectory, "nclskins-plugin-${platform.version}.jar").delete())
                return new HttpResult(204, new byte[0])
            }
            throw new AssertionError("Unexpected request ${method} ${url}")
        }
    }
}

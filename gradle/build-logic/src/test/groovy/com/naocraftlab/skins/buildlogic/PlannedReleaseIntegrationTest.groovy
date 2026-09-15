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

    private void verifyPlan(boolean replacePlugin) {
        File fixture = Files.createTempDirectory('planned-release-integration-').toFile()
        try {
            def project = ProjectBuilder.builder().withProjectDir(fixture).build()
            FixturePlatforms platform = project.tasks.create('preflightReleasePlatforms', FixturePlatforms)
            platform.filesDirectory = new File(fixture, 'remote-files')
            platform.filesDirectory.mkdirs()
            platform.replacePlugin = replacePlugin
            platform.version = CatalogTools.loadVersion(repository)
            platform.baseline = ServerPluginReleaseState.compute(repository, CatalogTools.loadCatalog(repository), platform.version).activeVersion
            FixtureGithub github = project.tasks.create('preflightGithubRelease', FixtureGithub)
            github.platform = platform
            github.commit = ReleaseSelection.git(repository, ['rev-parse', "refs/tags/${platform.version}^{commit}"]).trim()
            FixturePlanner planner = project.tasks.create('planFixture', FixturePlanner)
            planner.replacePlugin = replacePlugin
            planner.filesDirectory = platform.filesDirectory
            planner.repositoryDirectory.set(repository)
            planner.releaseTag.set(platform.version)
            File planDirectory = new File(fixture, 'plan')
            planner.outputDirectory.set(planDirectory)
            planner.planRelease()
            Map plan = ReleasePlan.load(new File(planDirectory, 'release-plan.json'))
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
            assertTrue(platform.requests.every { it.startsWith('GET ') })
            (plan.components as List<Map>).findAll { it.build }.each { Map component ->
                List<Map> newAssets = []
                ['asset', 'sourcesAsset'].each { String key ->
                    Map expected = component[key] as Map
                    File destination = new File(planDirectory, "assets/${expected.file}")
                    FixturePlatforms.writeJar(destination, expected.file.toString(), platform.baseline)
                    newAssets.add(AssembleReleaseTask.assetMetadata(destination, expected.kind.toString(), expected.target?.toString()))
                }
                Files.writeString(new File(planDirectory, "assets/${component.id}.receipt.json").toPath(), CatalogTools.json(
                        [planDigest: plan.digest, sourceCommit: plan.sourceCommit, componentId: component.id, assets: newAssets]))
            }
            AssemblePlannedReleaseTask assembly = project.tasks.create('assembleFixture', AssemblePlannedReleaseTask)
            assembly.repositoryDirectory.set(repository)
            assembly.planFile.set(new File(planDirectory, 'release-plan.json'))
            assembly.componentDirectory.set(new File(planDirectory, 'assets'))
            assembly.releaseRoot.set(new File(fixture, 'release'))
            assembly.assemble()
            File bundle = new File(fixture, "release/${platform.version}")
            Map manifest = PublicationSupport.loadManifest(bundle)
            assertEquals(replacePlugin ? 11 : 12, manifest.preservedTargetIds.size())
            assertEquals(12, manifest.targets.size())
            Map inventory = platform.fetchAll(manifest, PublishPlatformsTask.publicationTargets(manifest), 'fixture', 'fixture')
            Map states = platform.classifyPerTarget(PublishPlatformsTask.publicationTargets(manifest), inventory)
            PublishPlatformsTask.requirePreserved(manifest, states)
            ['modrinth', 'curseforge'].each { String name ->
                assertEquals(replacePlugin ? ['neoforge-26.3', 'server-plugin'] : ['neoforge-26.3'], states[name].findAll { id, state -> state.action == 'upload' }.keySet() as List)
            }
            Map githubPlan = GithubReleaseSupport.plan(manifest, github.release().assets as List<Map>) { it.sha256 }
            assertTrue(githubPlan.conflicts.isEmpty())
            assertEquals(replacePlugin ? 11 : 12, githubPlan.actions.count { it.action == 'keep' })
            assertEquals(replacePlugin ? 2 : 1, githubPlan.actions.count { it.action == 'upload' })
            if (replacePlugin) {
                github.bundleDirectory.set(bundle)
                github.publish()
                assertEquals(['PATCH', 'POST', 'POST', 'DELETE'], github.mutations)
                github.publish()
                assertEquals(['PATCH', 'POST', 'POST', 'DELETE'], github.mutations)
                assertFalse(github.release().assets.any { it.name == plan.pluginReplacement.file })
            }
            assembly.assemble()
            assertEquals(manifest, PublicationSupport.loadManifest(bundle))
            File tamper = new File(bundle, 'assets/' + manifest.targets.first().asset.file)
            Files.writeString(tamper.toPath(), 'tampered')
            assertThrows(IllegalStateException) { PublicationSupport.loadManifest(bundle) }
        } finally { fixture.deleteDir() }
    }

    abstract static class FixturePlanner extends PlanReleaseTask {
        File filesDirectory
        boolean replacePlugin
        @Override Map pluginState(File repository, Map catalog, String version) {
            Map state = super.pluginState(repository, catalog, version)
            state.pluginVersion = replacePlugin ? "${version}.2".toString() : version
            state
        }
        @Override String pluginReleaseNotes(File repository, Map state) { 'Fixture plugin notes\n' }

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
                if (target.id == 'neoforge-26.3') {
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
            if (initialized) return release()
            initialized = true
            Map catalog = CatalogTools.loadCatalog(new File('../..').canonicalFile)
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

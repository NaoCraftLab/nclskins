package com.naocraftlab.skins.buildlogic

import com.sun.net.httpserver.HttpServer
import groovy.json.JsonOutput
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Test

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList

import static org.junit.jupiter.api.Assertions.*

final class PublicationLogicTest {
    private final File repository = new File('../..').canonicalFile
    private final Map catalog = CatalogTools.loadCatalog(repository)
    private final Map release = [version: '1.2.3-beta.4', channel: 'beta']

    @Test
    void releaseSelectionUsesCatalogOwnershipAndIgnoresNonProductionPaths() {
        assertEquals(['forge-1.20.1'], ReleaseSelection.selectFromPaths(
                repository, catalog, ['targets/1.20.1/forge/build.gradle',
                                      'gradle/version.properties', 'CHANGELOG.md',
                                      'pub/description.md', 'pub/gallery/editor.png'], true).targetIds)
        assertEquals(catalog.targets*.id, ReleaseSelection.selectFromPaths(
                repository, catalog, ['core/src/main/java/example/Shared.java'], true).targetIds)
        assertEquals(catalog.targets*.id, ReleaseSelection.selectFromPaths(
                repository, catalog, ['gradle/version.properties', 'CHANGELOG.md',
                                      'pub/description.md'], true).targetIds)
        assertEquals(catalog.targets*.id, ReleaseSelection.selectFromPaths(
                repository, catalog, [], false).targetIds)
        assertThrows(IllegalArgumentException) {
            ReleaseSelection.selectFromPaths(repository, catalog, ['unknown-runtime/Main.java'], true)
        }
    }

    @Test
    void currentReleaseTagsHaveStableNearestAncestor() {
        assertEquals('1.0.0-alpha.1',
                ReleaseSelection.nearestPreviousTag(repository, '1.0.0-alpha.2'))
    }

    @Test
    void publicationMetadataFollowsTargetLoaderCompatibilityAndDependencies() {
        Map fabric = desired('fabric-26.1')
        assertEquals(['26.1', '26.1.1', '26.1.2'], fabric.gameVersions)
        assertEquals('fabric', fabric.loader)
        assertEquals(25, fabric.javaRelease)
        assertEquals('1.2.3-beta.4+26.1-fabric', fabric.name)
        assertEquals('1.2.3-beta.4', fabric.versionNumber)
        assertEquals([
                [projectId: 'P7dR8mSH', type: 'required'],
                [projectId: '1eAoo2KR', type: 'optional'],
                [projectId: 'bTTf2DEw', type: 'optional']
        ] as Set, fabric.dependencies.modrinth as Set)

        Map neoForge = desired('neoforge-1.21.1')
        assertEquals(['1.21.1'], neoForge.gameVersions)
        assertEquals(21, neoForge.javaRelease)
        assertEquals([
                [projectId: '1eAoo2KR', type: 'optional'],
                [projectId: 'bTTf2DEw', type: 'optional']
        ] as Set, neoForge.dependencies.modrinth as Set)
        assertEquals([
                [projectId: 667299, slug: 'yacl', type: 'optional'],
                [projectId: 560832, slug: 'sqlite-jdbc', type: 'optional']
        ] as Set, neoForge.dependencies.curseforge as Set)
        assertFalse((fabric.dependencies.modrinth + neoForge.dependencies.modrinth).any {
            it.projectId in ['mOgUt4GM', 'sbpqhzIG']
        })
        assertFalse((fabric.dependencies.curseforge + neoForge.dependencies.curseforge).any {
            it.projectId in [308702, 1089803]
        })
    }

    @Test
    void emptyPartialCompleteAndConflictStatesAreIndependent() {
        Map target = desired('fabric-1.20.1')
        Map exactModrinth = exactModrinth(target)
        Map exactCurseForge = exactCurseForge(target)
        Map exactCurseForgeSources = exactCurseForgeSources(target)

        assertEquals('upload', PublicationSupport.classify('modrinth', target, []).action)
        assertEquals('upload', PublicationSupport.classify('curseforge', target, []).action)
        assertEquals('skip', PublicationSupport.classify('modrinth', target, [exactModrinth]).action)
        assertEquals('upload-sources', PublicationSupport.classify(
                'curseforge', target, [exactCurseForge]).action)
        assertEquals('skip', PublicationSupport.classify(
                'curseforge', target, [exactCurseForge, exactCurseForgeSources]).action)
        assertEquals('skip', PublicationSupport.classify('modrinth', target, [exactModrinth]).action)
        assertEquals('upload', PublicationSupport.classify('curseforge', target, []).action)

        Map conflict = CatalogTools.materialize(exactModrinth) as Map
        conflict.game_versions = ['1.21.1']
        assertEquals('conflict', PublicationSupport.classify('modrinth', target, [conflict]).action)
        assertEquals('conflict', PublicationSupport.classify(
                'modrinth', target, [exactModrinth, exactModrinth]).action)

        Map wrongJava = CatalogTools.materialize(exactCurseForge) as Map
        wrongJava.gameVersions = (target.gameVersions as List) + ['Fabric', 'Client', 'Server', 'Java 21']
        assertEquals('conflict', PublicationSupport.classify(
                'curseforge', target, [wrongJava, exactCurseForgeSources]).action)

        Map wrongSources = CatalogTools.materialize(exactCurseForgeSources) as Map
        wrongSources.hashes = [[algo: 1, value: 'different']]
        assertEquals('conflict', PublicationSupport.classify(
                'curseforge', target, [exactCurseForge, wrongSources]).action)

        Map wrongEnvironment = CatalogTools.materialize(exactModrinth) as Map
        wrongEnvironment.environment = 'client_and_server'
        assertEquals('conflict', PublicationSupport.classify(
                'modrinth', target, [wrongEnvironment]).action)
    }

    @Test
    void uploadPayloadsUseExactChannelsAndIntegerCurseForgeRelations() {
        Map target = desired('fabric-1.20.1')
        Map manifest = [
                releaseNotes: [text: 'Changes\n'],
                platforms: catalog.mod.platforms
        ]
        Map modrinth = PublicationSupport.modrinthMetadata(manifest, target)
        assertEquals('1.2.3-beta.4', modrinth.version_number)
        assertEquals(['fabric'], modrinth.loaders)
        assertEquals('beta', modrinth.version_type)
        assertEquals('client_only_server_optional', modrinth.environment)

        Map curseForge = PublicationSupport.curseForgeMetadata(manifest, target)
        assertEquals(['1.20.1', 'Fabric', 'Client', 'Server', 'Java 17'], curseForge.gameVersionNames)
        assertEquals('beta', curseForge.releaseType)
        assertTrue((curseForge.relations.projects as List).every { it.projectID instanceof Integer })
        String json = JsonOutput.toJson(curseForge)
        assertTrue(json.contains('"projectID":306612'))
        assertFalse(json.contains('"projectID":"'))

        Map curseForgeSources = PublicationSupport.curseForgeSourcesMetadata(target, '42')
        assertEquals(42L, curseForgeSources.parentFileID)
        assertEquals("${target.name} Sources", curseForgeSources.displayName)
        assertFalse(curseForgeSources.containsKey('gameVersionNames'))
        assertFalse(curseForgeSources.containsKey('relations'))

        assertEquals(['file', 'sources'], modrinth.file_parts)
        assertEquals([sources: 'sources-jar'], modrinth.file_types)

        byte[] multipart = PublicationSupport.multipart([
                [name: 'metadata', filename: null, contentType: 'application/json',
                 bytes: json.getBytes(StandardCharsets.UTF_8)],
                [name: 'file', filename: target.asset.file,
                 contentType: 'application/java-archive', bytes: 'jar'.bytes],
                [name: 'sources', filename: target.sourcesAsset.file,
                 contentType: 'application/java-archive', bytes: 'sources'.bytes]
        ], 'NclSkinsBoundary')
        String body = new String(multipart, StandardCharsets.UTF_8)
        assertTrue(body.contains('name="metadata"'))
        assertTrue(body.contains("filename=\"${target.asset.file}\""))
        assertTrue(body.contains('name="sources"'))
        assertTrue(body.contains("filename=\"${target.sourcesAsset.file}\""))
    }

    @Test
    void duplicateTargetContentsAndUnexpectedBackfillAssetsFail() {
        Map first = desired('fabric-1.20.1')
        Map second = desired('forge-1.20.1')
        second.asset.sha512 = first.asset.sha512
        assertThrows(IllegalStateException) {
            AssembleReleaseTask.requireUniqueArtifactContents([first, second])
        }

        File directory = Files.createTempDirectory('nclskins-backfill-assets-').toFile()
        try {
            new File(directory, "nclskins-${release.version}-sources.jar".toString()).bytes = 'sources'.bytes
            AssembleReleaseTask.validateExistingAssetSet(directory, catalog, release.version)
            new File(directory, 'unexpected.jar').bytes = 'x'.bytes
            assertThrows(IllegalStateException) {
                AssembleReleaseTask.validateExistingAssetSet(directory, catalog, release.version)
            }
        } finally {
            directory.deleteDir()
        }
    }

    @Test
    void verifiedManifestCoversNotesSourcesAndEveryTargetHash() {
        File bundle = Files.createTempDirectory('nclskins-release-bundle-').toFile()
        try {
            File assets = new File(bundle, 'assets')
            assertTrue(assets.mkdir())
            File mod = new File(assets, 'nclskins-1.2.3-beta.4-fabric-1.20.1.jar')
            File sources = new File(assets, 'nclskins-1.2.3-beta.4-sources.jar')
            File notes = new File(bundle, 'release-notes.md')
            Files.write(mod.toPath(), 'mod'.bytes)
            Files.write(sources.toPath(), 'sources'.bytes)
            Files.writeString(notes.toPath(), 'Changes\n')
            Map modAsset = asset(mod, 'mod', 'fabric-1.20.1')
            Map sourcesAsset = asset(sources, 'sources', null)
            Map target = AssembleReleaseTask.publicationTarget(
                    catalog, CatalogTools.selectTarget(catalog, 'fabric-1.20.1'), release,
                    modAsset, sourcesAsset)
            Map manifest = [
                    schemaVersion: 2, mode: 'tag', version: release.version, channel: release.channel,
                    prerelease: true, sourceCommit: 'abc', baseTag: '1.2.3-beta.3', targetCount: 1,
                    selectedTargetIds: ['fabric-1.20.1'], platforms: catalog.mod.platforms,
                    releaseNotes: [file: notes.name, sha256: ReleaseBundle.sha256(notes), text: 'Changes\n'],
                    targets: [target], assets: [modAsset, sourcesAsset]
            ]
            Files.writeString(new File(bundle, 'release-manifest.json').toPath(), JsonOutput.toJson(manifest))

            assertEquals(release.version, PublicationSupport.loadManifest(bundle).version)
            Files.writeString(sources.toPath(), 'tampered')
            assertThrows(IllegalStateException) { PublicationSupport.loadManifest(bundle) }
        } finally {
            bundle.deleteDir()
        }
    }

    @Test
    void githubPlanKeepsExactAssetsAddsMissingAndProtectsBackfillJars() {
        Map mod = [file: 'nclskins-1.2.3-fabric.jar', kind: 'mod', sha256: 'a' * 64]
        Map sources = [file: 'nclskins-1.2.3-sources.jar', kind: 'sources', sha256: 'b' * 64]
        Map manifest = [mode: 'backfill', assets: [mod, sources]]
        Closure<String> hash = { Map remote -> remote.sha256 }

        Map exact = GithubReleaseSupport.plan(manifest, [
                [id: 1, name: mod.file, sha256: mod.sha256],
                [id: 2, name: sources.file, sha256: sources.sha256]
        ], hash)
        assertTrue(exact.conflicts.isEmpty())
        assertEquals(['keep', 'keep'], exact.actions*.action)

        Map missing = GithubReleaseSupport.plan(manifest, [
                [id: 1, name: mod.file, sha256: mod.sha256]
        ], hash)
        assertEquals(['keep', 'upload'] as Set, missing.actions*.action as Set)

        Map conflict = GithubReleaseSupport.plan(manifest, [
                [id: 1, name: mod.file, sha256: 'c' * 64],
                [id: 2, name: sources.file, sha256: 'd' * 64],
                [id: 3, name: 'old-target.jar', sha256: 'e' * 64]
        ], hash)
        assertEquals(2, conflict.conflicts.size())
        assertEquals('conflict', conflict.actions.find { it.file == mod.file }.action)
        assertEquals('replace', conflict.actions.find { it.file == sources.file }.action)
        assertEquals('conflict', conflict.actions.find { it.file == 'old-target.jar' }.action)

        manifest.mode = 'tag'
        Map tagPlan = GithubReleaseSupport.plan(manifest, [
                [id: 1, name: mod.file, sha256: 'c' * 64],
                [id: 3, name: 'old-target.jar', sha256: 'e' * 64]
        ], hash)
        assertEquals('replace', tagPlan.actions.find { it.file == mod.file }.action)
        assertEquals('delete', tagPlan.actions.find { it.file == 'old-target.jar' }.action)
    }

    @Test
    void platformPublicationRerunUploadsOnlyEntriesStillMissing() {
        RecordingPublishTask task = ProjectBuilder.builder().build().tasks.create(
                'recordingPublication', RecordingPublishTask)
        Map target = desired('fabric-1.20.1')
        Map manifest = [targets: [target], releaseNotes: [text: 'Changes\n'],
                        platforms: catalog.mod.platforms]

        task.modrinth = []
        task.curseForge = []
        task.publishManifest(manifest, repository, 'modrinth-token', 'curse-key', 'curse-token')
        assertEquals(['modrinth', 'curseforge'], task.uploads)

        task.uploads.clear()
        task.modrinth = []
        task.curseForge = [exactCurseForge(target), exactCurseForgeSources(target)]
        task.publishManifest(manifest, repository, 'modrinth-token', 'curse-key', 'curse-token')
        assertEquals(['modrinth'], task.uploads)

        task.uploads.clear()
        task.modrinth = [exactModrinth(target)]
        task.curseForge = [exactCurseForge(target)]
        task.publishManifest(manifest, repository, 'modrinth-token', 'curse-key', 'curse-token')
        assertEquals(['curseforge-sources'], task.uploads)

        task.uploads.clear()
        task.modrinth = []
        task.curseForge = []
        task.failCurseForge = true
        assertThrows(IllegalStateException) {
            task.publishManifest(manifest, repository, 'modrinth-token', 'curse-key', 'curse-token')
        }
        assertEquals(['modrinth'], task.uploads)

        task.modrinth = [exactModrinth(target)]
        task.curseForge = []
        task.failCurseForge = false
        task.publishManifest(manifest, repository, 'modrinth-token', 'curse-key', 'curse-token')
        assertEquals(['modrinth', 'curseforge'], task.uploads)

        task.uploads.clear()
        task.curseForge = [exactCurseForge(target), exactCurseForgeSources(target)]
        task.publishManifest(manifest, repository, 'modrinth-token', 'curse-key', 'curse-token')
        assertTrue(task.uploads.isEmpty())

        Map conflicting = exactModrinth(target)
        conflicting.files = [[filename: target.asset.file, hashes: [sha512: 'different']]]
        task.modrinth = [conflicting]
        task.curseForge = []
        assertThrows(IllegalStateException) {
            task.publishManifest(manifest, repository, 'modrinth-token', 'curse-key', 'curse-token')
        }
        assertTrue(task.uploads.isEmpty())
    }

    @Test
    void fakeApisAuthenticateAndCurseForgePaginationIsComplete() {
        HttpServer server = HttpServer.create(new InetSocketAddress('127.0.0.1', 0), 0)
        List<String> authentication = new CopyOnWriteArrayList<>()
        server.createContext('/project/axxWLWkK/version') { exchange ->
            authentication.add(exchange.requestHeaders.getFirst('Authorization'))
            respond(exchange, JsonOutput.toJson([[id: 'm1']]))
        }
        server.createContext('/v1/mods/1637371/files') { exchange ->
            authentication.add(exchange.requestHeaders.getFirst('X-API-Key'))
            int index = ((exchange.requestURI.query =~ /(?:^|&)index=([0-9]+)/)[0][1]) as int
            Map page = index == 0
                    ? [data: [[id: 1], [id: 2]], pagination: [resultCount: 2, totalCount: 3]]
                    : [data: [[id: 3]], pagination: [resultCount: 1, totalCount: 3]]
            respond(exchange, JsonOutput.toJson(page))
        }
        server.start()
        try {
            LocalApiPublishTask task = ProjectBuilder.builder().build().tasks.create(
                    'localApiPublication', LocalApiPublishTask)
            task.base = "http://127.0.0.1:${server.address.port}"
            Map manifest = [platforms: catalog.mod.platforms]
            assertEquals(['m1'], task.fetchModrinth(manifest, 'mod-token')*.id)
            assertEquals([1, 2, 3], task.fetchCurseForge(manifest, 'curse-key')*.id)
            assertEquals(['mod-token', 'curse-key', 'curse-key'], authentication)
        } finally {
            server.stop(0)
        }
    }

    @Test
    void curseForgeUploadCreatesParentedSourcesAdditionalFile() {
        HttpServer server = HttpServer.create(new InetSocketAddress('127.0.0.1', 0), 0)
        List<String> authentication = new CopyOnWriteArrayList<>()
        List<String> requests = new CopyOnWriteArrayList<>()
        server.createContext('/api/projects/1637371/upload-file') { exchange ->
            authentication.add(exchange.requestHeaders.getFirst('X-Api-Token'))
            requests.add(new String(exchange.requestBody.readAllBytes(), StandardCharsets.UTF_8))
            respond(exchange, JsonOutput.toJson([id: 100 + requests.size()]))
        }
        server.start()
        File directory = Files.createTempDirectory('nclskins-curseforge-upload-').toFile()
        try {
            LocalApiPublishTask task = ProjectBuilder.builder().build().tasks.create(
                    'localCurseForgeUpload', LocalApiPublishTask)
            task.base = "http://127.0.0.1:${server.address.port}"
            Map target = desired('fabric-1.20.1')
            Map manifest = [releaseNotes: [text: 'Changes\n'], platforms: catalog.mod.platforms]
            File artifact = new File(directory, target.asset.file.toString())
            File sources = new File(directory, target.sourcesAsset.file.toString())
            Files.write(artifact.toPath(), 'jar'.bytes)
            Files.write(sources.toPath(), 'sources'.bytes)

            assertEquals('101', task.uploadCurseForge(
                    manifest, target, artifact, sources, 'curse-upload-token'))
            assertEquals(['curse-upload-token', 'curse-upload-token'], authentication)
            assertEquals(2, requests.size())
            assertTrue(requests[0].contains('"gameVersionNames":["1.20.1","Fabric","Client","Server","Java 17"]'))
            assertTrue(requests[0].contains("filename=\"${artifact.name}\""))
            assertTrue(requests[1].contains('"parentFileID":101'))
            assertFalse(requests[1].contains('"gameVersionNames"'))
            assertTrue(requests[1].contains("filename=\"${sources.name}\""))
        } finally {
            directory.deleteDir()
            server.stop(0)
        }
    }

    @Test
    void fakeApiResponseErrorsAreBoundedAndRedacted() {
        HttpServer server = HttpServer.create(new InetSocketAddress('127.0.0.1', 0), 0)
        String secret = 'super-secret-token'
        server.createContext('/failure') { exchange ->
            byte[] body = "failure ${secret}".getBytes(StandardCharsets.UTF_8)
            exchange.sendResponseHeaders(500, body.length)
            exchange.responseBody.withCloseable { it.write(body) }
        }
        server.createContext('/malformed') { exchange ->
            byte[] body = 'not-json'.getBytes(StandardCharsets.UTF_8)
            exchange.sendResponseHeaders(200, body.length)
            exchange.responseBody.withCloseable { it.write(body) }
        }
        server.start()
        try {
            PublishPlatformsTask task = ProjectBuilder.builder().build().tasks.create(
                    'publicationApiFixture', PublishPlatformsTask)
            String base = "http://127.0.0.1:${server.address.port}"
            IllegalStateException failure = assertThrows(IllegalStateException) {
                task.request('GET', "${base}/failure", [:], null, null,
                        [200] as Set<Integer>, [secret])
            }
            assertFalse(failure.message.contains(secret))
            assertTrue(failure.message.contains('[redacted]'))
            PublishPlatformsTask.HttpResult malformed = task.request(
                    'GET', "${base}/malformed", [:], null, null,
                    [200] as Set<Integer>, [])
            assertThrows(IllegalStateException) { task.json(malformed) }
        } finally {
            server.stop(0)
        }
    }

    @Test
    void publisherHttpClientsAreExecutionScopedForConfigurationCache() {
        ['PublishPlatformsTask.groovy', 'PublishGithubReleaseTask.groovy'].each { String name ->
            String source = new File(
                    repository,
                    "gradle/build-logic/src/main/groovy/com/naocraftlab/skins/buildlogic/${name}").text
            assertTrue(source.contains('private transient HttpClient client'))
            assertTrue(source.contains('private HttpClient httpClient()'))
            assertTrue(source.contains('response = httpClient().send('))
            assertFalse(source.contains('private final HttpClient client'))
        }
    }

    private Map desired(String targetId) {
        Map target = CatalogTools.selectTarget(catalog, targetId)
        String fileName = AssembleReleaseTask.artifactName(target, release.version)
        Map asset = [
                file: fileName, kind: 'mod', target: targetId, size: 3,
                sha1: "sha1-${targetId}", sha256: "sha256-${targetId}",
                sha512: "sha512-${targetId}"
        ]
        Map sourcesAsset = [
                file: "nclskins-${release.version}-sources.jar".toString(), kind: 'sources', size: 7,
                sha1: 'sources-sha1', sha256: 'sources-sha256', sha512: 'sources-sha512'
        ]
        AssembleReleaseTask.publicationTarget(catalog, target, release, asset, sourcesAsset)
    }

    private static Map asset(File file, String kind, String targetId) {
        Map result = [file: file.name, kind: kind, size: file.length(),
                      sha1: ReleaseBundle.sha1(file), sha256: ReleaseBundle.sha256(file),
                      sha512: ReleaseBundle.sha512(file)]
        if (targetId != null) result.target = targetId
        result
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, String json) {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8)
        exchange.responseHeaders.add('Content-Type', 'application/json')
        exchange.sendResponseHeaders(200, bytes.length)
        exchange.responseBody.withCloseable { it.write(bytes) }
    }

    private static Map exactModrinth(Map target) {
        [
                id: 'modrinth-id', name: target.name,
                version_number: target.versionNumber, version_type: target.channel,
                game_versions: target.gameVersions, loaders: [target.loader],
                environment: 'client_only_server_optional',
                dependencies: (target.dependencies.modrinth as List).collect {
                    [project_id: it.projectId, dependency_type: it.type]
                },
                files: [
                        [filename: target.asset.file, primary: true,
                         file_type: null, hashes: [sha512: target.asset.sha512]],
                        [filename: target.sourcesAsset.file, primary: false,
                         file_type: 'sources-jar', hashes: [sha512: target.sourcesAsset.sha512]]
                ]
        ]
    }

    private static Map exactCurseForge(Map target) {
        [
                id: 42, displayName: target.name, releaseType: 2,
                gameVersions: (target.gameVersions as List) +
                        [PublicationSupport.loaderDisplayName(target.loader), 'Client', 'Server',
                         "Java ${target.javaRelease}"],
                fileName: target.asset.file,
                dependencies: (target.dependencies.curseforge as List).collect {
                    [modId: it.projectId, relationType: it.type == 'required' ? 3 : 2]
                },
                hashes: [[algo: 1, value: target.asset.sha1]]
        ]
    }

    private static Map exactCurseForgeSources(Map target) {
        [
                id: 43, parentProjectFileId: 42, displayName: "${target.name} Sources",
                releaseType: 2, gameVersions: [], fileName: target.sourcesAsset.file,
                dependencies: [], hashes: [[algo: 1, value: target.sourcesAsset.sha1]]
        ]
    }

    abstract static class RecordingPublishTask extends PublishPlatformsTask {
        List<Map> modrinth = []
        List<Map> curseForge = []
        List<String> uploads = []
        boolean failCurseForge

        @Override
        List<Map> fetchModrinth(Map manifest, String token) { modrinth }

        @Override
        List<Map> fetchCurseForge(Map manifest, String token) { curseForge }

        @Override
        String uploadModrinth(Map manifest, Map target, File artifact, File sources, String token) {
            uploads.add('modrinth')
            'modrinth-upload'
        }

        @Override
        String uploadCurseForge(Map manifest, Map target, File artifact, File sources, String token) {
            if (failCurseForge) throw new IllegalStateException('simulated CurseForge outage')
            uploads.add('curseforge')
            'curseforge-upload'
        }

        @Override
        String uploadCurseForgeSources(
                Map manifest, Map target, File sources, String parentFileId, String token) {
            uploads.add('curseforge-sources')
            'curseforge-sources-upload'
        }
    }

    abstract static class LocalApiPublishTask extends PublishPlatformsTask {
        String base

        @Override
        String apiBase(String environmentName, String fallback) { base }
    }
}

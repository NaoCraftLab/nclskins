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
        List<String> releaseTargetIds = CatalogTools.releaseTargets(catalog)*.id
        assertEquals(['forge-1.20.1'], ReleaseSelection.selectFromPaths(
                repository, catalog, ['targets/1.20.1/forge/build.gradle',
                                      'gradle/version.properties', 'CHANGELOG.md', 'PLUGIN_CHANGELOG.md',
                                      'server-plugin/src/main/java/example/Plugin.java',
                                      'server-plugin/build.gradle',
                                      'server-plugin-adapters/legacy-authlib4/src/main/java/example/Adapter.java',
                                      'pub/description.md', 'pub/plugin-description.md',
                                      'pub/gallery/editor.png'], true).targetIds)
        assertEquals(releaseTargetIds, ReleaseSelection.selectFromPaths(
                repository, catalog, ['core/src/main/java/example/Shared.java'], true).targetIds)
        assertEquals(releaseTargetIds, ReleaseSelection.selectFromPaths(
                repository, catalog, ['gradle/version.properties', 'CHANGELOG.md', 'PLUGIN_CHANGELOG.md',
                                      'server-plugin/src/main/java/example/Plugin.java',
                                      'server-plugin/build.gradle',
                                      'pub/description.md'], true).targetIds)
        assertEquals(releaseTargetIds, ReleaseSelection.selectFromPaths(
                repository, catalog, [], false).targetIds)
        assertEquals([], CatalogTools.affectedResult(
                repository, catalog, ['SERVER_CHANGELOG.md']).targetIds)
        assertThrows(IllegalStateException) {
            ReleaseSelection.selectFromPaths(
                    repository, catalog, ['targets/26.3/fabric/build.gradle'], true)
        }
        assertFalse(releaseTargetIds.contains('fabric-26.3'))
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
    void tagSelectionUsesExactTagCommitAndNearestFirstParentTag() {
        File fixture = Files.createTempDirectory('nclskins-tag-selection-').toFile()
        try {
            ReleaseSelection.git(fixture, ['init', '--quiet'])
            ReleaseSelection.git(fixture, ['config', 'user.name', 'NCL Skins Test'])
            ReleaseSelection.git(fixture, ['config', 'user.email', 'test@invalid.example'])
            File catalogFile = new File(fixture, 'gradle/targets.json')
            assertTrue(catalogFile.parentFile.mkdirs())
            Files.writeString(catalogFile.toPath(), JsonOutput.prettyPrint(
                    JsonOutput.toJson(catalog)) + '\n')
            File marker = new File(fixture, 'targets/1.20.1/forge/marker.txt')
            assertTrue(marker.parentFile.mkdirs())
            Files.writeString(marker.toPath(), 'first\n')
            ReleaseSelection.git(fixture, ['add', '.'])
            ReleaseSelection.git(fixture, ['commit', '--quiet', '-m', 'first'])
            ReleaseSelection.git(fixture, ['tag', '1.0.0-alpha.1'])
            Files.writeString(marker.toPath(), 'second\n')
            ReleaseSelection.git(fixture, ['add', '.'])
            ReleaseSelection.git(fixture, ['commit', '--quiet', '-m', 'second'])
            ReleaseSelection.git(fixture, ['tag', '1.0.0-alpha.2'])

            Map selection = ReleaseSelection.selectTag(
                    fixture, catalog, '1.0.0-alpha.2')
            assertEquals(
                    ReleaseSelection.git(
                            fixture, ['rev-parse', '1.0.0-alpha.2^{commit}']).trim(),
                    selection.sourceCommit)
            assertEquals('1.0.0-alpha.1', selection.baseTag)
            assertEquals(['forge-1.20.1'], selection.targetIds)
        } finally {
            fixture.deleteDir()
        }
    }

    @Test
    void tagSelectionMapsRemovedLoaderSourcesThroughNearestTagCatalog() {
        File fixture = Files.createTempDirectory('nclskins-retired-source-selection-').toFile()
        try {
            ReleaseSelection.git(fixture, ['init', '--quiet'])
            ReleaseSelection.git(fixture, ['config', 'user.name', 'NCL Skins Test'])
            ReleaseSelection.git(fixture, ['config', 'user.email', 'test@invalid.example'])

            Map previousCatalog = CatalogTools.materialize(catalog) as Map
            Map previousBundles = previousCatalog.sourceBundles as Map
            Map previousBundle = new LinkedHashMap(
                    previousBundles['avatar-pip-submission-fabric'] as Map)
            previousBundle.java = ['loader/fabric/pip-1.21.11/src/main/java']
            previousBundle.resources = ['loader/fabric/pip-1.21.11/src/main/resources']
            previousBundles['avatar-pip-submission-fabric'] = previousBundle

            File catalogFile = new File(fixture, 'gradle/targets.json')
            assertTrue(catalogFile.parentFile.mkdirs())
            Files.writeString(catalogFile.toPath(), JsonOutput.prettyPrint(
                    JsonOutput.toJson(previousCatalog)) + '\n')
            File retired = new File(fixture,
                    'loader/fabric/pip-1.21.11/src/main/java/example/GuiRendererMixin.java')
            assertTrue(retired.parentFile.mkdirs())
            Files.writeString(retired.toPath(), 'final class GuiRendererMixin {}\n')
            ReleaseSelection.git(fixture, ['add', '.'])
            ReleaseSelection.git(fixture, ['commit', '--quiet', '-m', 'previous'])
            ReleaseSelection.git(fixture, ['tag', '1.0.0-beta.2'])

            Files.writeString(catalogFile.toPath(), JsonOutput.prettyPrint(
                    JsonOutput.toJson(catalog)) + '\n')
            assertTrue(retired.delete())
            File replacement = new File(fixture,
                    'loader/fabric/pip-submission/src/main/java/example/GuiRendererMixin.java')
            assertTrue(replacement.parentFile.mkdirs())
            Files.writeString(replacement.toPath(), 'final class GuiRendererMixin {}\n')
            ReleaseSelection.git(fixture, ['add', '-A'])
            ReleaseSelection.git(fixture, ['commit', '--quiet', '-m', 'current'])
            ReleaseSelection.git(fixture, ['tag', '1.0.0-beta.3'])

            Map loadedPreviousCatalog = ReleaseSelection.catalogAtRef(
                    fixture, '1.0.0-beta.2')
            Map previousTarget = CatalogTools.selectTarget(
                    loadedPreviousCatalog, 'fabric-1.21.11')
            assertEquals('avatar-pip-submission-fabric', previousTarget.capabilities.preview)
            assertEquals('avatar-pip-submission-fabric', CatalogTools.capabilityDeclaration(
                    loadedPreviousCatalog,
                    previousTarget.capabilities.preview.toString()).bundle)
            assertEquals(['loader/fabric/pip-1.21.11/src/main/java'],
                    ((loadedPreviousCatalog.sourceBundles as Map)
                            ['avatar-pip-submission-fabric'] as Map).java)
            assertTrue((CatalogTools.resolveTargetSources(
                    fixture, loadedPreviousCatalog, previousTarget).java as List)
                    .contains('loader/fabric/pip-1.21.11/src/main/java'))

            Map selection = ReleaseSelection.selectTag(
                    fixture, catalog, '1.0.0-beta.3')

            assertEquals(CatalogTools.releaseTargets(catalog)*.id, selection.targetIds)
            assertTrue((selection.reasons['fabric-1.21.11'] as List)
                    .contains('historical-source'))
        } finally {
            fixture.deleteDir()
        }
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
        assertEquals('skip', PublicationSupport.classify(
                'curseforge', target, [exactCurseForge, exactCurseForgeSources]).action)
        Map coreApiWithoutJava = CatalogTools.materialize(exactCurseForge) as Map
        coreApiWithoutJava.gameVersions = (coreApiWithoutJava.gameVersions as List).findAll {
            !(it.toString().toLowerCase(Locale.ROOT) ==~ /java\s+[0-9]+/)
        }
        assertEquals('skip', PublicationSupport.classify(
                'curseforge', target, [coreApiWithoutJava, exactCurseForgeSources]).action)
        Map rejectedSources = [
                id: 43, parentProjectFileId: 42, displayName: "${target.name} Sources",
                releaseType: 2, gameVersions: [], fileName: target.sourcesAsset.file,
                dependencies: [], hashes: [[algo: 1, value: 'rejected-duplicate']]
        ]
        assertEquals('conflict', PublicationSupport.classify(
                'curseforge', target, [exactCurseForge, rejectedSources]).action)
        assertEquals('skip', PublicationSupport.classify('modrinth', target, [exactModrinth]).action)
        assertEquals('upload', PublicationSupport.classify('curseforge', target, []).action)

        Map conflict = CatalogTools.materialize(exactModrinth) as Map
        conflict.game_versions = ['1.21.1']
        assertEquals('update-metadata',
                PublicationSupport.classify('modrinth', target, [conflict]).action)
        assertEquals('conflict', PublicationSupport.classify(
                'modrinth', target, [exactModrinth, exactModrinth]).action)

        Map wrongLoader = CatalogTools.materialize(exactModrinth) as Map
        wrongLoader.loaders = ['forge']
        assertEquals('conflict', PublicationSupport.classify(
                'modrinth', target, [wrongLoader]).action)

        Map wrongJava = CatalogTools.materialize(exactCurseForge) as Map
        wrongJava.gameVersions = (target.gameVersions as List) + ['Fabric', 'Client', 'Server', 'Java 21']
        assertEquals('skip', PublicationSupport.classify(
                'curseforge', target, [wrongJava, exactCurseForgeSources]).action)

        Map wrongEnvironment = CatalogTools.materialize(exactModrinth) as Map
        wrongEnvironment.environment = 'client_and_server'
        assertEquals('conflict', PublicationSupport.classify(
                'modrinth', target, [wrongEnvironment]).action)
    }

    @Test
    void curseForgeSourcesChildMustBeExactAndBelongToTheProductionParent() {
        Map target = desired('fabric-1.20.1')
        Map parent = exactCurseForge(target)
        Map sources = exactCurseForgeSources(target)

        assertEquals('upload-source', PublicationSupport.classify(
                'curseforge', target, [parent]).action)
        assertEquals('skip', PublicationSupport.classify(
                'curseforge', target, [parent, sources]).action)

        Map wrongParent = CatalogTools.materialize(sources) as Map
        wrongParent.parentProjectFileId = 999
        assertEquals('conflict', PublicationSupport.classify(
                'curseforge', target, [parent, wrongParent]).action)

        Map wrongHash = CatalogTools.materialize(sources) as Map
        wrongHash.hashes[0].value = 'different'
        assertEquals('conflict', PublicationSupport.classify(
                'curseforge', target, [parent, wrongHash]).action)

        Map wrongName = CatalogTools.materialize(sources) as Map
        wrongName.fileName = 'nclskins-wrong-sources.jar'
        assertEquals('conflict', PublicationSupport.classify(
                'curseforge', target, [parent, wrongName]).action)

        Map duplicate = CatalogTools.materialize(sources) as Map
        duplicate.id = 44
        assertEquals('conflict', PublicationSupport.classify(
                'curseforge', target, [parent, sources, duplicate]).action)
        assertEquals('conflict', PublicationSupport.classify(
                'curseforge', target, [sources]).action)
    }

    @Test
    void uploadPayloadsUseExactChannelsAndIntegerCurseForgeRelations() {
        Map target = desired('fabric-1.20.1')
        Map manifest = [
                releaseNotes: [text: 'Mod changes\n'],
                platforms: catalog.mod.platforms
        ]
        Map modrinth = PublicationSupport.modrinthMetadata(manifest, target)
        assertEquals('1.2.3-beta.4', modrinth.version_number)
        assertEquals(['fabric'], modrinth.loaders)
        assertEquals('beta', modrinth.version_type)
        assertEquals('client_only_server_optional', modrinth.environment)
        assertEquals('Mod changes\n', modrinth.changelog)

        Map curseForge = PublicationSupport.curseForgeMetadata(manifest, target)
        assertEquals(['1.20.1', 'Fabric', 'Client', 'Server', 'Java 17'], curseForge.gameVersionNames)
        assertEquals('beta', curseForge.releaseType)
        assertEquals('Mod changes\n', curseForge.changelog)
        assertTrue((curseForge.relations.projects as List).every { it.projectID instanceof Integer })
        String json = JsonOutput.toJson(curseForge)
        assertTrue(json.contains('"projectID":306612'))
        assertFalse(json.contains('"projectID":"'))

        Map curseForgeSources = PublicationSupport.curseForgeSourcesMetadata(
                manifest, target, '42')
        assertEquals(42L, curseForgeSources.parentFileID)
        assertEquals('Mod changes\n', curseForgeSources.changelog)
        assertEquals("${target.name} sources".toString(), curseForgeSources.displayName)
        assertFalse(curseForgeSources.containsKey('gameVersionNames'))
        assertFalse(curseForgeSources.containsKey('relations'))

        assertThrows(IllegalArgumentException) { desired('fabric-26.3') }

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
    void serverPluginUsesSeparateProjectsAndOneTruthfulUniversalUpload() {
        Map artifact = [file: 'nclskins-plugin-1.2.3-beta.4.jar', kind: 'server-plugin',
                        size: 3, sha1: 'a' * 40, sha256: 'b' * 64, sha512: 'c' * 128]
        Map sources = [file: 'nclskins-plugin-1.2.3-beta.4-sources.jar',
                       kind: 'server-plugin-sources', size: 4,
                       sha1: 'd' * 40, sha256: 'e' * 64, sha512: 'f' * 128]
        Map target = [
                id: 'server-plugin', kind: 'server-plugin',
                name: '1.2.3-beta.4+universal', versionNumber: '1.2.3-beta.4',
                channel: 'beta', environment: 'server', javaRelease: 17,
                javaReleases: [17, 21, 25],
                minecraftVersion: '1.20.1',
                gameVersions: [
                    '1.20.1', '1.21.1', '1.21.11', '26.1', '26.1.1', '26.1.2', '26.2'],
                loaders: ['bukkit', 'spigot', 'paper', 'purpur', 'folia',
                          'velocity', 'bungeecord'],
                dependencies: [modrinth: [], curseforge: []],
                platforms: [modrinth: [projectId: 'plugin-modrinth', slug: 'nclskins-plugin'],
                            curseforge: [projectId: 999, slug: 'nclskins-plugin']],
                releaseNotes: 'Plugin changes\n', asset: artifact, sourcesAsset: sources]
        Map rootManifest = [targets: [], releaseNotes: [text: 'Mod changes\n'],
                            platforms: catalog.mod.platforms,
                            serverPlugin: [publish: true, publication: target]]

        assertEquals([target], PublishPlatformsTask.publicationTargets(rootManifest))
        Map view = PublishPlatformsTask.manifestForTarget(rootManifest, target)
        assertEquals('plugin-modrinth', view.platforms.modrinth.projectId)
        assertEquals('Plugin changes\n', view.releaseNotes.text)

        Map modrinth = PublicationSupport.modrinthMetadata(view, target)
        assertEquals(['bukkit', 'spigot', 'paper', 'purpur', 'folia',
                      'velocity', 'bungeecord'] as Set,
                modrinth.loaders as Set)
        assertEquals(target.gameVersions, modrinth.game_versions)
        assertEquals('server_only', modrinth.environment)
        assertEquals('plugin-modrinth', modrinth.project_id)
        assertEquals('Plugin changes\n', modrinth.changelog)

        Map curseForge = PublicationSupport.curseForgeMetadata(view, target)
        assertEquals(target.gameVersions + ['Server', 'Java 17'],
                curseForge.gameVersionNames)
        assertFalse(curseForge.containsKey('relations'))
        assertEquals(999, view.platforms.curseforge.projectId)
        assertEquals('Plugin changes\n', curseForge.changelog)
        assertFalse(curseForge.gameVersionNames.any {
            it in ['Paper', 'Purpur', 'Velocity', 'BungeeCord', 'Folia']
        })
        Map curseForgeSources = PublicationSupport.curseForgeSourcesMetadata(
                view, target, '71')
        assertEquals(71L, curseForgeSources.parentFileID)
        assertEquals('Plugin changes\n', curseForgeSources.changelog)

        Map exactModrinth = [
                id: 'plugin-version', name: target.name,
                version_number: target.versionNumber, version_type: target.channel,
                game_versions: target.gameVersions, loaders: target.loaders,
                environment: 'server_only', dependencies: [],
                files: [[filename: artifact.file, primary: true, file_type: null,
                         hashes: [sha512: artifact.sha512]],
                        [filename: sources.file, primary: false, file_type: 'sources-jar',
                         hashes: [sha512: sources.sha512]]]]
        Map exactCurse = [id: 71, displayName: target.name, releaseType: 2,
                          gameVersions: curseForge.gameVersionNames, fileName: artifact.file,
                          dependencies: [], hashes: [[algo: 1, value: artifact.sha1]]]
        Map exactCurseSources = [
                id: 72, parentProjectFileId: 71,
                displayName: "${target.name} sources".toString(), releaseType: 2,
                gameVersions: [], fileName: sources.file, dependencies: [],
                hashes: [[algo: 1, value: sources.sha1]]]
        assertEquals('skip', PublicationSupport.classify(
                'modrinth', target, [exactModrinth]).action)
        assertEquals('skip', PublicationSupport.classify(
                'curseforge', target, [exactCurse, exactCurseSources]).action)

        Map staleModrinth = CatalogTools.materialize(exactModrinth) as Map
        staleModrinth.game_versions = target.gameVersions.dropRight(1)
        staleModrinth.loaders = ['paper', 'velocity', 'bungeecord']
        assertEquals('update-metadata', PublicationSupport.classify(
                'modrinth', target, [staleModrinth]).action)

        Map legacyModrinth = CatalogTools.materialize(exactModrinth) as Map
        legacyModrinth.name = 'NCL Skins Plugin 1.2.3-beta.4'
        legacyModrinth.files[0].filename = 'nclskins-server-1.2.3-beta.4.jar'
        legacyModrinth.files[1].filename = 'nclskins-server-1.2.3-beta.4-sources.jar'
        assertEquals('conflict', PublicationSupport.classify(
                'modrinth', target, [legacyModrinth]).action)

        Map staleCurseForge = CatalogTools.materialize(exactCurse) as Map
        staleCurseForge.gameVersions = curseForge.gameVersionNames.findAll { it != '26.2' }
        assertEquals('update-metadata', PublicationSupport.classify(
                'curseforge', target, [staleCurseForge, exactCurseSources]).action)

        staleModrinth.files[0].hashes.sha512 = 'different'
        assertEquals('conflict', PublicationSupport.classify(
                'modrinth', target, [staleModrinth]).action)

        RecordingPublishTask publisher = ProjectBuilder.builder().build().tasks.create(
                'recordingServerPluginPublication', RecordingPublishTask)
        publisher.publishManifest(
                rootManifest, repository, 'modrinth-token', 'curse-key', 'curse-token')
        assertEquals(['modrinth', 'curseforge', 'curseforge-sources'], publisher.uploads)
    }

    @Test
    void serverPluginLoaderMetadataCoversEverySupportedModrinthLabel() {
        assertEquals([
                'bukkit', 'spigot', 'paper', 'purpur', 'folia',
                'velocity', 'bungeecord'
        ], AssembleReleaseTask.serverPluginLoaders(catalog.serverPlugin.compatibility as Map))
        assertEquals([
                '1.20.1', '1.21.1', '1.21.11', '26.1', '26.1.1', '26.1.2', '26.2'
        ], AssembleReleaseTask.serverPluginGameVersions(catalog))
        assertEquals([17, 21, 25], AssembleReleaseTask.serverPluginJavaReleases(catalog))
    }

    @Test
    void compatibilityBackfillCannotAdvertiseMoreThanTheTaggedPluginJar() {
        File fixture = Files.createTempDirectory('nclskins-plugin-tag-catalog-').toFile()
        try {
            assertEquals('', ReleaseSelection.git(fixture, ['init', '--quiet']))
            ReleaseSelection.git(fixture, ['config', 'user.name', 'NCL Skins Test'])
            ReleaseSelection.git(fixture, ['config', 'user.email', 'test@invalid.example'])
            File gradleDirectory = new File(fixture, 'gradle')
            assertTrue(gradleDirectory.mkdir())
            Files.writeString(new File(gradleDirectory, 'targets.json').toPath(), JsonOutput.toJson([
                    serverPlugin: [compatibility: ['1.20.1': ['paper']]]
            ]))
            ReleaseSelection.git(fixture, ['add', 'gradle/targets.json'])
            ReleaseSelection.git(fixture, ['commit', '--quiet', '-m', 'fixture'])
            ReleaseSelection.git(fixture, ['tag', '1.2.3'])

            Map publicationCatalog = AssembleReleaseTask.serverPluginPublicationCatalog(
                    fixture, catalog, '1.2.3', 'backfill')
            assertEquals(['1.20.1': ['paper']], publicationCatalog.serverPlugin.compatibility)
            assertEquals(catalog.serverPlugin.platforms, publicationCatalog.serverPlugin.platforms)
            assertEquals(catalog.serverPlugin.compatibility,
                    AssembleReleaseTask.serverPluginPublicationCatalog(
                            fixture, catalog, '1.2.3', 'tag').serverPlugin.compatibility)
        } finally {
            fixture.deleteDir()
        }
    }

    @Test
    void compatibilityBackfillCannotBuildMissingHistoricalServerPlugin() {
        File assets = Files.createTempDirectory('nclskins-plugin-backfill-output-').toFile()
        File existing = Files.createTempDirectory('nclskins-plugin-backfill-existing-').toFile()
        try {
            Map state = [
                    publish: true, reason: 'server-change', activeVersion: release.version,
                    previousActiveVersion: '1.2.3-beta.3',
                    currentFingerprint: 'a' * 64, activeFingerprint: 'b' * 64,
                    protocolIds: catalog.serverPlugin.protocols,
                    matrixId: catalog.serverPlugin.matrixId
            ]
            IllegalStateException failure = assertThrows(IllegalStateException) {
                AssembleReleaseTask.serverPluginPublication(
                        repository, catalog, release, state, 'Plugin changes\n',
                        assets, existing, false)
            }
            assertTrue(failure.message.contains(
                    'Current-main compatibility backfill cannot create historical server plugin'))
            assertTrue(failure.message.contains('use reconcile-tag'))
        } finally {
            assets.deleteDir()
            existing.deleteDir()
        }
    }

    @Test
    void everyReleaseTargetUsesTheSourcesJarBuiltBesideItsProductionArtifact() {
        List<Map> targets = CatalogTools.releaseTargets(catalog)
        assertTrue(targets.size() > 1)
        assertEquals(targets.size(), targets.collect { Map target ->
            AssembleReleaseTask.sourceArtifactName(target, release.version)
        }.toSet().size())
        targets.each { Map target ->
            assertEquals(
                    AssembleReleaseTask.artifactName(target, release.version)
                            .replace('.jar', '-sources.jar'),
                    AssembleReleaseTask.sourceArtifactName(target, release.version))
        }
    }

    @Test
    void targetProductionAndSourcesAlwaysComeFromOneOrigin() {
        File existing = Files.createTempDirectory('nclskins-target-pair-existing-').toFile()
        File built = Files.createTempDirectory('nclskins-target-pair-built-').toFile()
        String productionName = 'nclskins-1.2.3+1.20.1-fabric.jar'
        String sourcesName = 'nclskins-1.2.3+1.20.1-fabric-sources.jar'
        try {
            File builtProduction = new File(built, productionName)
            File builtSources = new File(built, sourcesName)
            builtProduction.bytes = 'built-production'.bytes
            builtSources.bytes = 'built-sources'.bytes
            Map builtPair = AssembleReleaseTask.targetArtifactPair(
                    existing, built, productionName, sourcesName, 'fabric-1.20.1')
            assertEquals(builtProduction, builtPair.production)
            assertEquals(builtSources, builtPair.sources)

            File existingProduction = new File(existing, productionName)
            existingProduction.bytes = 'existing-production'.bytes
            assertThrows(IllegalStateException) {
                AssembleReleaseTask.targetArtifactPair(
                        existing, built, productionName, sourcesName, 'fabric-1.20.1')
            }
            File existingSources = new File(existing, sourcesName)
            existingSources.bytes = 'existing-sources'.bytes
            Map existingPair = AssembleReleaseTask.targetArtifactPair(
                    existing, built, productionName, sourcesName, 'fabric-1.20.1')
            assertEquals(existingProduction, existingPair.production)
            assertEquals(existingSources, existingPair.sources)

            assertTrue(existingProduction.delete())
            assertThrows(IllegalStateException) {
                AssembleReleaseTask.targetArtifactPair(
                        existing, built, productionName, sourcesName, 'fabric-1.20.1')
            }
        } finally {
            existing.deleteDir()
            built.deleteDir()
        }
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
            Map target = CatalogTools.selectTarget(catalog, 'fabric-1.20.1')
            new File(directory, AssembleReleaseTask.sourceArtifactName(
                    target, release.version)).bytes = 'sources'.bytes
            AssembleReleaseTask.validateExistingAssetSet(directory, catalog, release.version)
            new File(directory,
                    "nclskins-${release.version}+26.3-fabric.jar".toString()).bytes = 'x'.bytes
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
            File sources = new File(assets, 'nclskins-1.2.3-beta.4-fabric-1.20.1-sources.jar')
            File notes = new File(bundle, 'release-notes.md')
            Files.write(mod.toPath(), 'mod'.bytes)
            Files.write(sources.toPath(), 'sources'.bytes)
            Files.writeString(notes.toPath(), 'Changes\n')
            Map modAsset = asset(mod, 'mod', 'fabric-1.20.1')
            Map sourcesAsset = asset(sources, 'mod-sources', 'fabric-1.20.1')
            Map target = AssembleReleaseTask.publicationTarget(
                    catalog, CatalogTools.selectTarget(catalog, 'fabric-1.20.1'), release,
                    modAsset, sourcesAsset)
            Map manifest = [
                    schemaVersion: 4, mode: 'tag', version: release.version, channel: release.channel,
                    prerelease: true, sourceCommit: 'abc', baseTag: '1.2.3-beta.3', targetCount: 1,
                    selectedTargetIds: ['fabric-1.20.1'], platforms: catalog.mod.platforms,
                    releaseNotes: [file: notes.name, sha256: ReleaseBundle.sha256(notes), text: 'Changes\n'],
                    serverPlugin: [publish: false, reason: 'unchanged',
                                   activeVersion: '1.0.0', previousActiveVersion: '1.0.0',
                                   currentFingerprint: 'a' * 64, activeFingerprint: 'a' * 64,
                                   protocolIds: ['command-v1'], matrixId: 'official-v1',
                                   artifact: null, sourcesArtifact: null, publication: null,
                                   publications: [:]],
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
        Map sources = [file: 'nclskins-1.2.3-sources.jar', kind: 'mod-sources', sha256: 'b' * 64]
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
        assertEquals(3, conflict.conflicts.size())
        assertEquals('conflict', conflict.actions.find { it.file == mod.file }.action)
        assertEquals('conflict', conflict.actions.find { it.file == sources.file }.action)
        assertEquals('conflict', conflict.actions.find { it.file == 'old-target.jar' }.action)

        manifest.mode = 'tag'
        Map tagPlan = GithubReleaseSupport.plan(manifest, [
                [id: 1, name: mod.file, sha256: 'c' * 64],
                [id: 3, name: 'old-target.jar', sha256: 'e' * 64]
        ], hash)
        assertEquals('conflict', tagPlan.actions.find { it.file == mod.file }.action)
        assertEquals('conflict', tagPlan.actions.find { it.file == 'old-target.jar' }.action)
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
        assertEquals(['modrinth', 'curseforge', 'curseforge-sources'], task.uploads)

        task.uploads.clear()
        task.modrinth = []
        task.curseForge = [exactCurseForge(target)]
        task.publishManifest(manifest, repository, 'modrinth-token', 'curse-key', 'curse-token')
        assertEquals(['modrinth', 'curseforge-sources'], task.uploads)

        task.uploads.clear()
        task.modrinth = [exactModrinth(target)]
        task.curseForge = [exactCurseForge(target), exactCurseForgeSources(target)]
        task.publishManifest(manifest, repository, 'modrinth-token', 'curse-key', 'curse-token')
        assertTrue(task.uploads.isEmpty())

        task.uploads.clear()
        task.modrinth = []
        task.curseForge = []
        task.failCurseForge = true
        assertThrows(IllegalStateException) {
            task.publishManifest(manifest, repository, 'modrinth-token', 'curse-key', 'curse-token')
        }
        assertEquals(['modrinth'], task.uploads)

        task.uploads.clear()
        task.modrinth = [exactModrinth(target)]
        task.curseForge = []
        task.failCurseForge = false
        task.publishManifest(manifest, repository, 'modrinth-token', 'curse-key', 'curse-token')
        assertEquals(['curseforge', 'curseforge-sources'], task.uploads)

        task.uploads.clear()
        task.curseForge = [exactCurseForge(target), exactCurseForgeSources(target)]
        task.publishManifest(manifest, repository, 'modrinth-token', 'curse-key', 'curse-token')
        assertTrue(task.uploads.isEmpty())

        Map staleGames = exactModrinth(target)
        staleGames.game_versions = ['1.19.4']
        task.modrinth = [staleGames]
        task.publishManifest(manifest, repository, 'modrinth-token', 'curse-key', 'curse-token')
        assertEquals(['modrinth-metadata'], task.uploads)

        task.uploads.clear()
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
    void curseForgeUploadSendsProductionThenSourcesChild() {
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
                    manifest, target, artifact, 'curse-upload-token'))
            assertEquals('102', task.uploadCurseForgeSources(
                    manifest, target, sources, '101', 'curse-upload-token'))
            assertEquals(['curse-upload-token', 'curse-upload-token'], authentication)
            assertEquals(2, requests.size())
            assertTrue(requests[0].contains('"gameVersionNames":["1.20.1","Fabric","Client","Server","Java 17"]'))
            assertTrue(requests[0].contains("filename=\"${artifact.name}\""))
            assertFalse(requests[0].contains(target.sourcesAsset.file.toString()))
            assertTrue(requests[1].contains('"parentFileID":101'))
            assertFalse(requests[1].contains('gameVersionNames'))
            assertFalse(requests[1].contains('relations'))
            assertTrue(requests[1].contains("filename=\"${sources.name}\""))
        } finally {
            directory.deleteDir()
            server.stop(0)
        }
    }

    @Test
    void pluginCompatibilityMetadataUpdatesDoNotReplacePublishedFiles() {
        HttpServer server = HttpServer.create(new InetSocketAddress('127.0.0.1', 0), 0)
        List<String> requests = new CopyOnWriteArrayList<>()
        server.createContext('/version/plugin-version') { exchange ->
            requests.add("${exchange.requestMethod} ${exchange.requestHeaders.getFirst('Authorization')} " +
                    new String(exchange.requestBody.readAllBytes(), StandardCharsets.UTF_8))
            exchange.sendResponseHeaders(204, -1)
            exchange.close()
        }
        server.createContext('/api/projects/999/update-file') { exchange ->
            requests.add("${exchange.requestMethod} ${exchange.requestHeaders.getFirst('X-Api-Token')} " +
                    new String(exchange.requestBody.readAllBytes(), StandardCharsets.UTF_8))
            respond(exchange, JsonOutput.toJson([id: 71]))
        }
        server.start()
        try {
            LocalApiPublishTask task = ProjectBuilder.builder().build().tasks.create(
                    'localMetadataUpdate', LocalApiPublishTask)
            task.base = "http://127.0.0.1:${server.address.port}"
            Map target = [
                    id: 'server-plugin', kind: 'server-plugin',
                    gameVersions: ['1.20.1', '26.1', '26.2'],
                    loaders: ['bukkit', 'paper', 'folia', 'velocity', 'bungeecord'],
                    javaRelease: 17, javaReleases: [17, 21, 25],
                    dependencies: [modrinth: [], curseforge: []]
            ]
            Map manifest = [
                    releaseNotes: [text: 'Plugin changes\n'],
                    platforms: [modrinth: [projectId: 'plugin-modrinth'],
                                curseforge: [projectId: 999]]
            ]

            assertEquals('plugin-version', task.updateModrinthMetadata(
                    target, 'plugin-version', 'mod-token'))
            assertEquals('71', task.updateCurseForgeMetadata(
                    manifest, target, '71', 'curse-token'))
            assertEquals(2, requests.size())
            assertTrue(requests[0].startsWith('PATCH mod-token '))
            assertTrue(requests[0].contains('"game_versions":["1.20.1","26.1","26.2"]'))
            assertTrue(requests[0].contains(
                    '"loaders":["bukkit","paper","folia","velocity","bungeecord"]'))
            assertTrue(requests[1].startsWith('POST curse-token '))
            assertTrue(requests[1].contains('"fileID":71'))
            assertTrue(requests[1].contains(
                    '"gameVersionNames":["1.20.1","26.1","26.2","Server","Java 17"]'))
            assertFalse(requests.join('\n').contains('filename='))
        } finally {
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
                file: fileName.replace('.jar', '-sources.jar'), kind: 'mod-sources', target: targetId,
                size: 7, sha1: "sources-sha1-${targetId}",
                sha256: "sources-sha256-${targetId}", sha512: "sources-sha512-${targetId}"
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

    private static Map exactCurseForgeSources(Map target, int parentId = 42) {
        [
                id: parentId + 1, parentProjectFileId: parentId,
                displayName: "${target.name} sources".toString(), releaseType: 2,
                gameVersions: [], fileName: target.sourcesAsset.file,
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
        String uploadCurseForge(Map manifest, Map target, File artifact, String token) {
            if (failCurseForge) throw new IllegalStateException('simulated CurseForge outage')
            uploads.add('curseforge')
            '42'
        }

        @Override
        String uploadCurseForgeSources(
                Map manifest, Map target, File sources, String parentFileId, String token) {
            uploads.add('curseforge-sources')
            '43'
        }

        @Override
        String updateModrinthMetadata(Map target, String remoteId, String token) {
            uploads.add('modrinth-metadata')
            remoteId
        }

        @Override
        String updateCurseForgeMetadata(
                Map manifest, Map target, String remoteId, String token) {
            uploads.add('curseforge-metadata')
            remoteId
        }
    }

    abstract static class LocalApiPublishTask extends PublishPlatformsTask {
        String base

        @Override
        String apiBase(String environmentName, String fallback) { base }
    }
}

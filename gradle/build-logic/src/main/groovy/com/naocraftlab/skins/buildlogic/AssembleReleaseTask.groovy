package com.naocraftlab.skins.buildlogic

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.*

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.util.zip.ZipFile

abstract class AssembleReleaseTask extends DefaultTask {
    @Internal
    abstract DirectoryProperty getRepositoryDirectory()

    @InputFile
    abstract RegularFileProperty getCatalogFile()

    @InputFile
    abstract RegularFileProperty getVersionFile()

    @InputFile
    abstract RegularFileProperty getChangelogFile()

    @InputFile
    abstract RegularFileProperty getServerChangelogFile()

    @InputFile
    abstract RegularFileProperty getServerPluginStateFile()

    @Input
    abstract Property<String> getReleaseTag()

    @Input
    abstract Property<String> getReleaseMode()

    @Optional
    @InputDirectory
    abstract DirectoryProperty getExistingAssetsDirectory()

    @OutputDirectory
    abstract DirectoryProperty getReleaseRoot()

    @TaskAction
    void assembleRelease() {
        File repository = repositoryDirectory.get().asFile
        String mode = releaseMode.get()
        if (!(mode in ['tag', 'backfill', 'reconcile-tag'])) {
            throw new IllegalArgumentException(
                    "releaseMode must be tag, backfill, or reconcile-tag, got '${mode}'")
        }
        Map metadata = ReleaseMetadata.validate(
                versionFile.get().asFile,
                changelogFile.get().asFile,
                releaseTag.get())
        Map catalog = CatalogTools.loadCatalog(repository)
        CatalogTools.validate(repository, catalog)
        Map sealedState = CatalogTools.materialize(
                new JsonSlurper().parse(serverPluginStateFile.get().asFile)) as Map
        Map recomputedState = ServerPluginReleaseState.compute(
                repository, catalog, metadata.version.toString())
        if (sealedState != recomputedState || sealedState.sealed != true) {
            throw new IllegalStateException(
                    'Server plugin release state differs from independent assembleRelease computation')
        }
        String serverNotes = ServerPluginChangelog.validate(
                serverChangelogFile.get().asFile, recomputedState)
        metadata.serverPlugin = new LinkedHashMap(recomputedState) + [notes: serverNotes]

        Map selection
        if (mode == 'tag') {
            selection = ReleaseSelection.selectTag(repository, catalog, metadata.version.toString())
        } else {
            selection = [
                    sourceCommit: ReleaseSelection.git(repository, ['rev-parse', 'HEAD']).trim(),
                    baseTag: null,
                    paths: [],
                    targetIds: CatalogTools.releaseTargets(catalog).collect { it.id.toString() },
                    reasons: [:]
            ]
        }

        File existingDirectory = mode == 'tag' ? null : requiredExistingAssetsDirectory()
        validateExistingAssetSet(existingDirectory, catalog, metadata.version.toString())

        File versionDirectory = ReleaseMetadata.write(releaseRoot.get().asFile, metadata)
        File assetsDirectory = new File(versionDirectory, 'assets')
        if (assetsDirectory.exists() && !assetsDirectory.deleteDir()) {
            throw new IllegalStateException("Could not clean release asset directory: ${assetsDirectory}")
        }
        Files.createDirectories(assetsDirectory.toPath())

        Set<String> selectedIds = selection.targetIds as Set<String>
        List<Map> publicationTargets = []
        List<Map> assets = []
        String sourcesName = "nclskins-${metadata.version}-sources.jar"
        File sources = ReleaseBundle.createSourcesJar(
                repository, catalog, metadata.version.toString(), new File(assetsDirectory, sourcesName))
        Map sourcesAsset = assetMetadata(sources, 'mod-sources', null)
        CatalogTools.releaseTargets(catalog)
                .findAll { selectedIds.contains(it.id.toString()) }
                .each { Map target ->
            String name = artifactName(target, metadata.version.toString())
            File built = new File(repository, "${target.path}/build/libs/${name}")
            File existing = existingDirectory == null ? null : new File(existingDirectory, name)
            File source = existing != null && existing.isFile() ? existing : built
            if (!Files.isRegularFile(source.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalStateException("Missing production artifact for ${target.id}: ${source}")
            }
            File destination = new File(assetsDirectory, name)
            Files.copy(source.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
            requireServerPluginBaseline(destination, recomputedState.activeVersion.toString())
            Map asset = assetMetadata(destination, 'mod', target.id.toString())
            assets.add(asset)
            publicationTargets.add(publicationTarget(catalog, target, metadata, asset, sourcesAsset))
        }
        requireUniqueArtifactContents(publicationTargets)

        assets.add(sourcesAsset)

        Map serverPublication = serverPluginPublication(
                repository, serverPluginPublicationCatalog(
                        repository, catalog, metadata.version.toString(), mode),
                metadata, recomputedState, serverNotes,
                assetsDirectory, existingDirectory, mode != 'backfill')
        if (serverPublication.publish == true) {
            assets.add(serverPublication.artifact)
            assets.add(serverPublication.sourcesArtifact)
        }

        File notes = new File(versionDirectory, 'release-notes.md')
        Map manifest = [
                schemaVersion: 3,
                mode         : mode,
                version      : metadata.version,
                channel      : metadata.channel,
                prerelease   : metadata.prerelease,
                sourceCommit : selection.sourceCommit,
                baseTag      : selection.baseTag,
                targetCount  : publicationTargets.size(),
                selectedTargetIds: publicationTargets.collect { it.id },
                selection    : [paths: selection.paths, reasons: selection.reasons],
                platforms    : catalog.mod.platforms,
                releaseNotes : [
                        file  : 'release-notes.md',
                        sha256: ReleaseBundle.sha256(notes),
                        text  : metadata.notes
                ],
                targets      : publicationTargets,
                serverPlugin : serverPublication,
                assets       : assets
        ]
        Files.writeString(
                new File(versionDirectory, 'release-manifest.json').toPath(),
                JsonOutput.prettyPrint(JsonOutput.toJson(manifest)) + '\n',
                StandardCharsets.UTF_8)
        logger.lifecycle("Release bundle ${metadata.version} (${mode}) contains " +
                "${publicationTargets.size()} mod target artifacts, one mod sources JAR, " +
                "serverPlugin=${serverPublication.publish ? 'published' : 'unchanged'}")
    }

    File requiredExistingAssetsDirectory() {
        if (!existingAssetsDirectory.isPresent()) {
            throw new IllegalArgumentException(
                    'backfill/reconcile-tag requires -PexistingReleaseAssets=<directory>')
        }
        File directory = existingAssetsDirectory.get().asFile
        if (!directory.isDirectory() || Files.isSymbolicLink(directory.toPath())) {
            throw new IllegalArgumentException("existing release asset directory is missing or unsafe: ${directory}")
        }
        directory
    }

    static void validateExistingAssetSet(File directory, Map catalog, String version) {
        if (directory == null) return
        Set<String> allowed = CatalogTools.releaseTargets(catalog)
                .collect { artifactName(it as Map, version) } as Set<String>
        allowed.add("nclskins-${version}-sources.jar".toString())
        allowed.add("nclskins-server-${version}.jar".toString())
        allowed.add("nclskins-server-${version}-sources.jar".toString())
        directory.eachFile { File file ->
            if (!file.isFile() || Files.isSymbolicLink(file.toPath()) || !allowed.contains(file.name)) {
                throw new IllegalStateException("Unexpected existing GitHub release asset: ${file.name}")
            }
        }
    }

    static Map publicationTarget(Map catalog, Map target, Map release, Map asset, Map sourcesAsset) {
        if (target.releaseEligible != true) {
            throw new IllegalArgumentException(
                    "${target.id}: non-release-eligible target cannot enter publication metadata")
        }
        String loader = target.loader.id.toString()
        List<String> gameVersions = target.compatibility instanceof Map
                ? (target.compatibility.minecraftVersions as List).collect { it.toString() }
                : [target.minecraft.version.toString()]
        List<Map> modrinthDependencies = []
        List<Map> curseForgeDependencies = []
        (catalog.publicationDependencies as Map).values().findAll { Object raw ->
            (raw as Map).loaders.contains(loader)
        }.each { Object raw ->
            Map dependency = raw as Map
            modrinthDependencies.add([
                    projectId: dependency.platforms.modrinth.projectId,
                    type: dependency.type
            ])
            curseForgeDependencies.add([
                    projectId: dependency.platforms.curseforge.projectId,
                    slug: dependency.platforms.curseforge.slug,
                    type: dependency.type
            ])
        }
        [
                id           : target.id,
                name         : PublicationSupport.publicationName(
                        release.version.toString(), target.minecraft.version.toString(), loader),
                versionNumber: release.version,
                channel      : release.channel,
                loader       : loader,
                javaRelease  : target.java.release,
                minecraftVersion: target.minecraft.version,
                gameVersions : gameVersions,
                dependencies : [modrinth: modrinthDependencies, curseforge: curseForgeDependencies],
                asset        : asset,
                sourcesAsset : sourcesAsset
        ]
    }

    static void requireUniqueArtifactContents(List<Map> targets) {
        List<List<Map>> duplicates = targets.groupBy { it.asset.sha512 }.values().findAll { it.size() > 1 }
        if (!duplicates.isEmpty()) {
            String description = duplicates.collect { List<Map> group -> group.collect { it.id }.join(', ') }.join('; ')
            throw new IllegalStateException("Different publication targets have identical artifacts: ${description}")
        }
    }

    static String artifactName(Map target, String version) {
        target.artifact.file.toString().replace('{modVersion}', version)
    }

    static Map assetMetadata(File file, String kind, String targetId) {
        Map result = [
                file  : file.name,
                kind  : kind,
                size  : file.length(),
                sha1  : ReleaseBundle.sha1(file),
                sha256: ReleaseBundle.sha256(file),
                sha512: ReleaseBundle.sha512(file)
        ]
        if (targetId != null) result.target = targetId
        result
    }

    static void requireServerPluginBaseline(File artifact, String expectedVersion) {
        new ZipFile(artifact).withCloseable { ZipFile zip ->
            def entry = zip.getEntry('nclskins-server-compatibility.json')
            if (entry == null) {
                throw new IllegalStateException(
                        "${artifact.name} has no nclskins-server-compatibility.json")
            }
            Map compatibility = new JsonSlurper().parse(zip.getInputStream(entry)) as Map
            if (compatibility.requiredServerPluginVersion != expectedVersion) {
                throw new IllegalStateException(
                        "${artifact.name} requires server plugin " +
                        "${compatibility.requiredServerPluginVersion}, expected active ${expectedVersion}")
            }
        }
    }

    static Map serverPluginPublication(
            File repository,
            Map catalog,
            Map release,
            Map state,
            String notes,
            File assetsDirectory,
            File existingDirectory,
            boolean allowBuild) {
        Map base = [
                publish              : state.publish,
                reason               : state.reason,
                activeVersion        : state.activeVersion,
                previousActiveVersion: state.previousActiveVersion,
                currentFingerprint   : state.currentFingerprint,
                activeFingerprint    : state.activeFingerprint,
                protocolIds          : state.protocolIds,
                matrixId             : state.matrixId,
                artifact             : null,
                sourcesArtifact      : null,
                publication          : null,
                publications         : [:]
        ]
        if (state.publish != true) return base
        ['modrinth', 'curseforge'].each { String platform ->
            if (catalog.serverPlugin.platforms[platform].projectId == null) {
                throw new IllegalStateException(
                        "NCL Skins Plugin ${platform} projectId is required for publication")
            }
        }
        String version = release.version.toString()
        String jarName = catalog.serverPlugin.artifact.toString()
                .replace('{pluginVersion}', version)
        String sourcesName = catalog.serverPlugin.sourcesArtifact.toString()
                .replace('{pluginVersion}', version)
        File jar = copyServerArtifact(existingDirectory, assetsDirectory,
                jarName, new File(repository, "server-plugin/build/libs/${jarName}"), allowBuild)
        File sources = copyServerArtifact(existingDirectory, assetsDirectory,
                sourcesName, new File(repository, "server-plugin/build/libs/${sourcesName}"), allowBuild)
        Map artifact = assetMetadata(jar, 'server-plugin', null)
        Map sourcesArtifact = assetMetadata(sources, 'server-plugin-sources', null)
        List<String> games = (catalog.serverPlugin.compatibility as Map).keySet() as List<String>
        Map publication = [
                id              : 'server-plugin',
                kind            : 'server-plugin',
                name            : "NCL Skins Plugin ${version}".toString(),
                versionNumber   : version,
                channel         : release.channel,
                minecraftVersion: games.first(),
                gameVersions    : games,
                loaders         : serverPluginLoaders(catalog.serverPlugin.compatibility as Map),
                javaRelease     : catalog.serverPlugin.javaRelease,
                javaReleases    : serverPluginJavaReleases(catalog),
                environment     : 'server',
                dependencies    : [modrinth: [], curseforge: []],
                platforms       : catalog.serverPlugin.platforms,
                releaseNotes    : notes,
                asset           : artifact,
                sourcesAsset    : sourcesArtifact
        ]
        base + [artifact: artifact, sourcesArtifact: sourcesArtifact, publication: publication]
    }

    static Map serverPluginPublicationCatalog(
            File repository, Map currentCatalog, String version, String mode) {
        if (mode != 'backfill') return currentCatalog
        Object parsed = new JsonSlurper().parseText(ReleaseSelection.git(
                repository, ['show', "${version}:gradle/targets.json"]))
        if (!(parsed instanceof Map) || !((parsed as Map).serverPlugin instanceof Map)) {
            throw new IllegalStateException(
                    "Release tag ${version} has no server plugin compatibility declaration")
        }
        Map result = CatalogTools.materialize(currentCatalog) as Map
        Map taggedPlugin = (parsed as Map).serverPlugin as Map
        result.serverPlugin.compatibility = CatalogTools.materialize(taggedPlugin.compatibility)
        result
    }

    static List<String> serverPluginLoaders(Map compatibility) {
        Set<String> declared = compatibility.values()
                .findAll { it instanceof List }
                .collectMany { List values -> values.collect { it.toString() } } as Set<String>
        if (declared.remove('craftbukkit')) declared.add('bukkit')
        declared.addAll(['velocity', 'bungeecord'])
        List<String> order = [
                'bukkit', 'spigot', 'paper', 'purpur', 'folia', 'velocity', 'bungeecord']
        List<String> unknown = declared.findAll { !order.contains(it) }.sort()
        if (!unknown.isEmpty()) {
            throw new IllegalStateException(
                    "No Modrinth loader mapping for server plugin platforms: ${unknown}")
        }
        order.findAll { declared.contains(it) }
    }

    static List<Integer> serverPluginJavaReleases(Map catalog) {
        Set<Integer> releases = [(catalog.serverPlugin.javaRelease as Number).intValue()] as Set<Integer>
        (catalog.serverPluginRuntimes as List).findAll { it instanceof Map }.each { Object raw ->
            Object value = (raw as Map).javaRelease
            if (value instanceof Number) releases.add((value as Number).intValue())
        }
        releases.sort()
    }

    private static File copyServerArtifact(
            File existingDirectory, File assetsDirectory, String name, File built, boolean allowBuild) {
        File existing = existingDirectory == null ? null : new File(existingDirectory, name)
        File source = existing != null && existing.isFile() ? existing : allowBuild ? built : null
        if (source == null) {
            throw new IllegalStateException(
                    "Current-main compatibility backfill cannot create historical server plugin ${name}; " +
                    'use reconcile-tag')
        }
        if (!Files.isRegularFile(source.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalStateException("Missing server plugin artifact: ${source}")
        }
        File destination = new File(assetsDirectory, name)
        Files.copy(source.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
        destination
    }
}

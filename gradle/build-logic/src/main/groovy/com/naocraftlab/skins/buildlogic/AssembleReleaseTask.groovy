package com.naocraftlab.skins.buildlogic

import groovy.json.JsonOutput
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.*

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption

abstract class AssembleReleaseTask extends DefaultTask {
    @Internal
    abstract DirectoryProperty getRepositoryDirectory()

    @InputFile
    abstract RegularFileProperty getCatalogFile()

    @InputFile
    abstract RegularFileProperty getVersionFile()

    @InputFile
    abstract RegularFileProperty getChangelogFile()

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
        if (!(mode in ['tag', 'backfill'])) {
            throw new IllegalArgumentException("releaseMode must be tag or backfill, got '${mode}'")
        }
        Map metadata = ReleaseMetadata.validate(
                versionFile.get().asFile,
                changelogFile.get().asFile,
                releaseTag.get())
        Map catalog = CatalogTools.loadCatalog(repository)
        CatalogTools.validate(repository, catalog)

        Map selection
        if (mode == 'tag') {
            selection = ReleaseSelection.selectTag(repository, catalog, metadata.version.toString())
        } else {
            selection = [
                    sourceCommit: ReleaseSelection.git(repository, ['rev-parse', 'HEAD']).trim(),
                    baseTag: null,
                    paths: [],
                    targetIds: (catalog.targets as List).collect { it.id.toString() },
                    reasons: [:]
            ]
        }

        File existingDirectory = mode == 'backfill' ? requiredExistingAssetsDirectory() : null
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
        Map sourcesAsset = assetMetadata(sources, 'sources', null)
        (catalog.targets as List<Map>).findAll { selectedIds.contains(it.id.toString()) }.each { Map target ->
            String name = artifactName(target, metadata.version.toString())
            File built = new File(repository, "${target.path}/build/libs/${name}")
            File existing = existingDirectory == null ? null : new File(existingDirectory, name)
            File source = existing != null && existing.isFile() ? existing : built
            if (!Files.isRegularFile(source.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalStateException("Missing production artifact for ${target.id}: ${source}")
            }
            File destination = new File(assetsDirectory, name)
            Files.copy(source.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
            Map asset = assetMetadata(destination, 'mod', target.id.toString())
            assets.add(asset)
            publicationTargets.add(publicationTarget(catalog, target, metadata, asset, sourcesAsset))
        }
        requireUniqueArtifactContents(publicationTargets)

        assets.add(sourcesAsset)

        File notes = new File(versionDirectory, 'release-notes.md')
        Map manifest = [
                schemaVersion: 2,
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
                assets       : assets
        ]
        Files.writeString(
                new File(versionDirectory, 'release-manifest.json').toPath(),
                JsonOutput.prettyPrint(JsonOutput.toJson(manifest)) + '\n',
                StandardCharsets.UTF_8)
        logger.lifecycle("Release bundle ${metadata.version} (${mode}) contains " +
                "${publicationTargets.size()} target artifacts and one sources JAR")
    }

    File requiredExistingAssetsDirectory() {
        if (!existingAssetsDirectory.isPresent()) {
            throw new IllegalArgumentException('backfill requires -PexistingReleaseAssets=<directory>')
        }
        File directory = existingAssetsDirectory.get().asFile
        if (!directory.isDirectory() || Files.isSymbolicLink(directory.toPath())) {
            throw new IllegalArgumentException("existing release asset directory is missing or unsafe: ${directory}")
        }
        directory
    }

    static void validateExistingAssetSet(File directory, Map catalog, String version) {
        if (directory == null) return
        Set<String> allowed = (catalog.targets as List).collect { artifactName(it as Map, version) } as Set<String>
        allowed.add("nclskins-${version}-sources.jar".toString())
        directory.eachFile { File file ->
            if (!file.isFile() || Files.isSymbolicLink(file.toPath()) || !allowed.contains(file.name)) {
                throw new IllegalStateException("Unexpected existing GitHub release asset: ${file.name}")
            }
        }
    }

    static Map publicationTarget(Map catalog, Map target, Map release, Map asset, Map sourcesAsset) {
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
}

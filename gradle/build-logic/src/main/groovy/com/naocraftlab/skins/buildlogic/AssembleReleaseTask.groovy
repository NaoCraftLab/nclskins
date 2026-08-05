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

    @OutputDirectory
    abstract DirectoryProperty getReleaseRoot()

    @TaskAction
    void assembleRelease() {
        File repository = repositoryDirectory.get().asFile
        Map metadata = ReleaseMetadata.validate(
                versionFile.get().asFile,
                changelogFile.get().asFile,
                releaseTag.get())
        Map catalog = CatalogTools.loadCatalog(repository)
        CatalogTools.validate(repository, catalog)
        File versionDirectory = ReleaseMetadata.write(releaseRoot.get().asFile, metadata)
        File assetsDirectory = new File(versionDirectory, 'assets')
        if (assetsDirectory.exists() && !assetsDirectory.deleteDir()) {
            throw new IllegalStateException("Could not clean release asset directory: ${assetsDirectory}")
        }
        Files.createDirectories(assetsDirectory.toPath())

        Set<String> names = [] as Set
        List<Map> assets = []
        (catalog.targets as List<Map>).each { Map target ->
            String name = target.artifact.file.toString()
                    .replace('{modVersion}', metadata.version.toString())
            if (!names.add(name)) {
                throw new IllegalStateException("Duplicate release artifact filename: ${name}")
            }
            File source = new File(repository, "${target.path}/build/libs/${name}")
            if (!Files.isRegularFile(source.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalStateException("Missing production artifact for ${target.id}: ${source}")
            }
            File destination = new File(assetsDirectory, name)
            Files.copy(source.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
            assets.add(AssembleReleaseTask.assetMetadata(
                    destination, 'mod', target.id.toString()))
        }

        String sourcesName = "nclskins-${metadata.version}-sources.jar"
        if (!names.add(sourcesName)) {
            throw new IllegalStateException("Duplicate release artifact filename: ${sourcesName}")
        }
        File sources = ReleaseBundle.createSourcesJar(
                repository,
                catalog,
                metadata.version.toString(),
                new File(assetsDirectory, sourcesName))
        assets.add(assetMetadata(sources, 'sources', null))

        int expectedCount = (catalog.targets as List).size() + 1
        if (assets.size() != expectedCount) {
            throw new IllegalStateException(
                    "Release bundle must contain ${expectedCount} assets; found ${assets.size()}")
        }
        Map manifest = [
                schemaVersion: 1,
                version      : metadata.version,
                prerelease   : metadata.prerelease,
                targetCount  : (catalog.targets as List).size(),
                releaseNotes : [
                        file  : 'release-notes.md',
                        sha256: ReleaseBundle.sha256(new File(versionDirectory, 'release-notes.md'))
                ],
                assets       : assets
        ]
        Files.writeString(
                new File(versionDirectory, 'release-manifest.json').toPath(),
                JsonOutput.prettyPrint(JsonOutput.toJson(manifest)) + '\n',
                StandardCharsets.UTF_8)
        logger.lifecycle(
                "Release bundle ${metadata.version} contains ${assets.size()} verified assets")
    }

    static Map assetMetadata(File file, String kind, String targetId) {
        Map result = [
                file  : file.name,
                kind  : kind,
                size  : file.length(),
                sha256: ReleaseBundle.sha256(file)
        ]
        if (targetId != null) result.target = targetId
        result
    }
}

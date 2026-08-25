package com.naocraftlab.skins.buildlogic

import groovy.json.JsonSlurper
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.*

abstract class ValidateReleaseTask extends DefaultTask {
    @Internal
    abstract DirectoryProperty getRepositoryDirectory()

    @InputFile
    abstract RegularFileProperty getCatalogFile()

    @InputFile
    abstract RegularFileProperty getVersionFile()

    @InputFile
    abstract RegularFileProperty getChangelogFile()

    @InputFile
    abstract RegularFileProperty getPluginChangelogFile()

    @InputFile
    abstract RegularFileProperty getServerPluginStateFile()

    @Input
    abstract Property<String> getReleaseTag()

    @OutputDirectory
    abstract DirectoryProperty getReleaseRoot()

    @TaskAction
    void validateRelease() {
        Map metadata = ReleaseMetadata.validate(
                versionFile.get().asFile,
                changelogFile.get().asFile,
                releaseTag.get())
        Map state = new JsonSlurper().parse(serverPluginStateFile.get().asFile) as Map
        if (state.sealed != true || state.currentVersion != metadata.version) {
            throw new IllegalArgumentException('Server plugin release state is not sealed for this release')
        }
        Map catalog = CatalogTools.loadJson(catalogFile.get().asFile)
        CatalogTools.validate(repositoryDirectory.get().asFile, catalog)
        if (state.publish == true) {
            ['modrinth', 'curseforge'].each { String platform ->
                if (catalog.serverPlugin.platforms[platform].projectId == null) {
                    throw new IllegalArgumentException(
                            "NCL Skins Plugin ${platform} projectId is required for publication")
                }
            }
        }
        String pluginNotes = ServerPluginChangelog.validate(
                pluginChangelogFile.get().asFile, state)
        metadata.serverPlugin = state + [notes: pluginNotes]
        ReleaseMetadata.write(releaseRoot.get().asFile, metadata)
        logger.lifecycle(
                "Release ${metadata.version} is valid (${metadata.prerelease ? 'prerelease' : 'stable'})")
    }
}

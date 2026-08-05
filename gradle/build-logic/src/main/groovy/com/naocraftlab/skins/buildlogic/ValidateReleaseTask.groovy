package com.naocraftlab.skins.buildlogic

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction

abstract class ValidateReleaseTask extends DefaultTask {
    @InputFile
    abstract RegularFileProperty getVersionFile()

    @InputFile
    abstract RegularFileProperty getChangelogFile()

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
        ReleaseMetadata.write(releaseRoot.get().asFile, metadata)
        logger.lifecycle(
                "Release ${metadata.version} is valid (${metadata.prerelease ? 'prerelease' : 'stable'})")
    }
}

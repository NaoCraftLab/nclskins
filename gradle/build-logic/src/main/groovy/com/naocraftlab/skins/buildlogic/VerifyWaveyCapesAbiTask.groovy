package com.naocraftlab.skins.buildlogic

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.TaskAction

abstract class VerifyWaveyCapesAbiTask extends DefaultTask {
    @InputFile
    abstract RegularFileProperty getCatalogFile()

    @InputDirectory
    abstract DirectoryProperty getArtifactsDirectory()

    @TaskAction
    void verify() {
        Map catalog = CatalogTools.loadJson(catalogFile.get().asFile.toPath())
        WaveyCapesAbiVerifier.verify(catalog.optionalDependencies.waveycapes as Map,
                artifactsDirectory.get().asFile)
        logger.lifecycle('Pinned WaveyCapes artifact and texture ABI verification passed for 12 targets')
    }
}

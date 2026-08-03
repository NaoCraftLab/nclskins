package com.naocraftlab.skins.buildlogic

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction

abstract class VerifyCatalogTask extends DefaultTask {
    @Internal
    abstract DirectoryProperty getRepositoryDirectory()

    @InputFile
    abstract RegularFileProperty getCatalogFile()

    @InputFile
    abstract RegularFileProperty getVersionFile()

    @InputFile
    abstract RegularFileProperty getAbiFile()

    @TaskAction
    void verify() {
        Map catalog = CatalogTools.loadJson(catalogFile.get().asFile.toPath())
        String version = CatalogTools.loadVersion(versionFile.get().asFile.toPath())
        CatalogTools.validate(repositoryDirectory.get().asFile, catalog)
        logger.lifecycle("Validated ${catalog.targets.size()} NCL Skins targets")
    }
}

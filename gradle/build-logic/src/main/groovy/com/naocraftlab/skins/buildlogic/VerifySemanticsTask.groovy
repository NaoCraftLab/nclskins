package com.naocraftlab.skins.buildlogic

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction

abstract class VerifySemanticsTask extends DefaultTask {
    @Internal
    abstract DirectoryProperty getRepositoryDirectory()

    @InputFile
    abstract RegularFileProperty getCatalogFile()

    @InputFile
    abstract RegularFileProperty getAbiFile()

    @InputFile
    abstract RegularFileProperty getCoverageFile()

    @TaskAction
    void verify() {
        File root = repositoryDirectory.get().asFile
        Map catalog = CatalogTools.loadJson(catalogFile.get().asFile.toPath())
        Map abi = CatalogTools.loadJson(abiFile.get().asFile.toPath())
        Map coverage = CatalogTools.loadJson(coverageFile.get().asFile.toPath())
        List<String> errors = SemanticVerifier.verify(root.toPath(), catalog, abi, coverage)
        if (!errors.isEmpty()) {
            throw new IllegalStateException('Capability semantic coverage verification failed:\n- ' + errors.join('\n- '))
        }
        logger.lifecycle("Capability semantic coverage passed: ${coverage.implementations.size()} native leaves map exactly to ${coverage.sharedSuites.size()} shared behavioral suites")
    }
}

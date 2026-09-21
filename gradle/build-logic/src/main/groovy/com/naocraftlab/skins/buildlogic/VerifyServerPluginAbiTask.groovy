package com.naocraftlab.skins.buildlogic

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction

abstract class VerifyServerPluginAbiTask extends DefaultTask {
    @Internal
    abstract DirectoryProperty getRepositoryDirectory()

    @InputFile
    abstract RegularFileProperty getCatalogFile()

    @InputFile
    abstract RegularFileProperty getDeclarationFile()

    @TaskAction
    void verify() {
        File root = repositoryDirectory.get().asFile
        Map catalog = CatalogTools.loadJson(catalogFile.get().asFile.toPath())
        Map declaration = CatalogTools.loadJson(declarationFile.get().asFile.toPath())
        List<String> errors = ServerPluginAbiVerifier.verify(root.toPath(), catalog, declaration)
        if (!errors.isEmpty()) {
            throw new IllegalStateException('Server plugin ABI verification failed:\n- ' + errors.join('\n- '))
        }
        logger.lifecycle('Server plugin ABI declaration covers every exact catalogued identity; native startup remains a separate acceptance gate')
    }
}

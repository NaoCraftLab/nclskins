package com.naocraftlab.skins.buildlogic

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction

abstract class VerifyArtifactsTask extends DefaultTask {
    @Internal
    abstract DirectoryProperty getRepositoryDirectory()

    @InputFile
    abstract RegularFileProperty getCatalogFile()

    @InputFile
    abstract RegularFileProperty getVersionFile()

    @Internal
    abstract Property<String> getTargetId()

    @TaskAction
    void verify() {
        File root = repositoryDirectory.get().asFile
        Map catalog = CatalogTools.loadJson(catalogFile.get().asFile.toPath())
        String version = CatalogTools.loadVersion(versionFile.get().asFile.toPath())
        List<Map> targets = targetId.orNull == null ? catalog.targets as List<Map> : [CatalogTools.selectTarget(catalog, targetId.get())]
        List<String> errors = []
        targets.each { Map target ->
            ArtifactVerifier.verify(root, catalog, target, version, errors)
            ArtifactVerifier.verifyCompatibilityReport(root, target, version, errors)
        }
        if (!errors.isEmpty()) throw new IllegalStateException('Artifact verification failed:\n- ' + errors.join('\n- '))
        logger.lifecycle("Catalog-driven artifact verification passed for ${targetId.orNull ?: 'all catalog targets'}")
    }
}

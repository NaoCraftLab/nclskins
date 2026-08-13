package com.naocraftlab.skins.buildlogic

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction

abstract class CompatibilityRunTask extends DefaultTask {
    @Internal
    abstract DirectoryProperty getRepositoryDirectory()

    @InputFile
    abstract RegularFileProperty getCatalogFile()

    @Input
    abstract Property<String> getTargetId()

    @Input
    abstract Property<String> getMinecraftVersion()

    @Input
    abstract Property<String> getRunKind()

    @Input
    abstract Property<Boolean> getDryRun()

    @TaskAction
    void runCompatibility() {
        File root = repositoryDirectory.get().asFile
        Map catalog = CatalogTools.loadJson(catalogFile.get().asFile.toPath())
        Map target = CatalogTools.selectTarget(catalog, targetId.get())
        CompatibilityHarness.run(
                root,
                catalog,
                target,
                CatalogTools.loadVersion(root),
                minecraftVersion.get(),
                runKind.get(),
                dryRun.get())
    }
}

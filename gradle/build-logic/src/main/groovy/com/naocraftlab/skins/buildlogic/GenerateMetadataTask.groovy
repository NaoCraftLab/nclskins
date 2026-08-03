package com.naocraftlab.skins.buildlogic

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import java.nio.file.Files
import java.util.Comparator

abstract class GenerateMetadataTask extends DefaultTask {
    @InputFile
    abstract RegularFileProperty getCatalogFile()

    @InputFile
    abstract RegularFileProperty getVersionFile()

    @Input
    abstract Property<String> getTargetId()

    @OutputDirectory
    abstract DirectoryProperty getOutputDirectory()

    @TaskAction
    void generate() {
        Map catalog = CatalogTools.loadJson(catalogFile.get().asFile.toPath())
        String version = CatalogTools.loadVersion(versionFile.get().asFile.toPath())
        Map target = CatalogTools.selectTarget(catalog, targetId.get())
        File output = outputDirectory.get().asFile
        if (output.exists()) {
            Files.walk(output.toPath()).sorted(Comparator.reverseOrder()).withCloseable { stream -> stream.forEach { Files.delete(it) } }
        }
        MetadataRenderer.render(catalog, target, version).each { String path, String content ->
            File destination = new File(output, path)
            destination.parentFile.mkdirs()
            destination.setText(content, 'UTF-8')
        }
    }
}

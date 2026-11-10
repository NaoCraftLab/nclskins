package com.naocraftlab.skins.buildlogic

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import java.nio.file.Files
import java.util.Comparator

abstract class GenerateMetadataTask extends DefaultTask {
    @InputFile
    abstract RegularFileProperty getCatalogFile()

    @InputFile
    abstract RegularFileProperty getVersionFile()

    @Internal
    abstract RegularFileProperty getPluginVersionFile()

    @Input
    String getConfiguredPluginBaseline() {
        ServerPluginVersion.parts(ServerPluginVersion.parse(pluginVersionFile.get().asFile.text)).base.toString()
    }

    GenerateMetadataTask() {
        pluginVersionFile.convention(project.layout.file(versionFile.map {
            new File(it.asFile.parentFile, 'plugin-version.properties')
        }))
    }

    @Input
    abstract Property<String> getTargetId()

    @Input
    abstract Property<String> getRequiredServerPluginVersion()

    @OutputDirectory
    abstract DirectoryProperty getOutputDirectory()

    @TaskAction
    void generate() {
        Map catalog = CatalogTools.loadJson(catalogFile.get().asFile.toPath())
        String version = CatalogTools.loadVersion(versionFile.get().asFile.toPath())
        Map target = CatalogTools.selectTarget(catalog, targetId.get())
        String baseline = MetadataRenderer.effectiveRequiredServerPluginVersion(
                configuredPluginBaseline,
                requiredServerPluginVersion.get())
        Map<String, String> resources = MetadataRenderer.render(catalog, target, version, baseline)
        File output = outputDirectory.get().asFile
        if (output.exists()) {
            Files.walk(output.toPath()).sorted(Comparator.reverseOrder()).withCloseable { stream -> stream.forEach { Files.delete(it) } }
        }
        resources.each { String path, String content ->
            File destination = new File(output, path)
            destination.parentFile.mkdirs()
            destination.setText(content, 'UTF-8')
        }
    }
}

package com.naocraftlab.skins.buildlogic

import groovy.xml.XmlSlurper
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction

import java.nio.charset.StandardCharsets
import java.nio.file.Files

abstract class GenerateIdeaRunConfigurationsTask extends DefaultTask {
    @InputFile
    abstract RegularFileProperty getCatalogFile()

    @OutputDirectory
    abstract DirectoryProperty getOutputDirectory()

    @TaskAction
    void generate() {
        Map catalog = CatalogTools.loadJson(catalogFile.get().asFile.toPath())
        File output = outputDirectory.get().asFile
        output.mkdirs()
        Set<String> names = [] as Set
        catalog.targets.each { Map target ->
            IdeaRunConfigurations.RUN_KINDS.each { String runKind ->
                String name = IdeaRunConfigurations.configurationName(target, runKind)
                if (!names.add(name)) throw new IllegalStateException("Duplicate IDEA run configuration ${name}")
                File destination = new File(output, IdeaRunConfigurations.fileName(target, runKind))
                String previousName = IdeaRunConfigurations.previousConfigurationName(target, runKind)
                String previousFileName = IdeaRunConfigurations.previousFileName(target, runKind)
                if (previousFileName != null) {
                    File previousDestination = new File(output, previousFileName)
                    if (previousDestination.isFile()) {
                        String existingPreviousName
                        try { existingPreviousName = new XmlSlurper().parse(previousDestination).configuration.@name.toString() }
                        catch (Exception error) { throw new IllegalStateException("Cannot parse existing IDEA run configuration ${previousDestination}", error) }
                        if (existingPreviousName == previousName) Files.delete(previousDestination.toPath())
                    }
                }
                if (destination.isFile()) {
                    String existingName
                    try { existingName = new XmlSlurper().parse(destination).configuration.@name.toString() }
                    catch (Exception error) { throw new IllegalStateException("Cannot parse existing IDEA run configuration ${destination}", error) }
                    if (existingName != name && existingName != previousName) throw new IllegalStateException("IDEA run configuration path is occupied by ${existingName}: ${destination}")
                }
                String content = IdeaRunConfigurations.render(target, runKind)
                if (!destination.isFile() || destination.getText('UTF-8') != content) Files.writeString(destination.toPath(), content, StandardCharsets.UTF_8)
            }
        }
        logger.lifecycle("Generated ${names.size()} Gradle IDEA run configurations in ${output}")
    }
}

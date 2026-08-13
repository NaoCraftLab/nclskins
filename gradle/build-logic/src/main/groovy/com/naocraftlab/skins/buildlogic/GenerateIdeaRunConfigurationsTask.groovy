package com.naocraftlab.skins.buildlogic

import groovy.xml.XmlSlurper
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction

import java.nio.charset.StandardCharsets
import java.nio.file.Files

abstract class GenerateIdeaRunConfigurationsTask extends DefaultTask {
    @InputFile
    abstract RegularFileProperty getCatalogFile()

    @OutputDirectory
    abstract DirectoryProperty getOutputDirectory()

    @Internal
    abstract RegularFileProperty getWorkspaceFile()

    @TaskAction
    void generate() {
        Map catalog = CatalogTools.loadJson(catalogFile.get().asFile.toPath())
        File output = outputDirectory.get().asFile
        output.mkdirs()
        Set<String> names = [] as Set
        Set<String> generatedFiles = [] as Set
        Set<String> existingManagedNames = existingManagedNames(output)
        IdeaRunConfigurations.orderedModRuntimes(catalog).each { Map runtime ->
            Map target = runtime.target as Map
            String minecraftVersion = runtime.minecraftVersion.toString()
            IdeaRunConfigurations.RUN_KINDS.each { String runKind ->
                String name = IdeaRunConfigurations.configurationName(
                        target, minecraftVersion, runKind)
                if (!names.add(name)) throw new IllegalStateException("Duplicate IDEA run configuration ${name}")
                File destination = new File(output, IdeaRunConfigurations.fileName(
                        target, minecraftVersion, runKind))
                generatedFiles.add(destination.name)
                List<String> previousNames = runtime.baseline
                        ? IdeaRunConfigurations.previousConfigurationNames(target, runKind) : []
                List<String> previousFiles = runtime.baseline
                        ? IdeaRunConfigurations.previousFileNames(target, runKind) : []
                previousFiles.eachWithIndex {
                        String previousFileName, int index ->
                    File previousDestination = new File(output, previousFileName)
                    if (previousDestination.isFile()) {
                        String existingPreviousName
                        try { existingPreviousName = new XmlSlurper().parse(previousDestination).configuration.@name.toString() }
                        catch (Exception error) { throw new IllegalStateException("Cannot parse existing IDEA run configuration ${previousDestination}", error) }
                        if (existingPreviousName == previousNames[index]) Files.delete(previousDestination.toPath())
                    }
                }
                if (destination.isFile()) {
                    String existingName
                    try { existingName = new XmlSlurper().parse(destination).configuration.@name.toString() }
                    catch (Exception error) { throw new IllegalStateException("Cannot parse existing IDEA run configuration ${destination}", error) }
                    if (existingName != name && !previousNames.contains(existingName)) throw new IllegalStateException("IDEA run configuration path is occupied by ${existingName}: ${destination}")
                }
                String content = IdeaRunConfigurations.render(
                        target, minecraftVersion, runKind)
                if (!destination.isFile() || destination.getText('UTF-8') != content) Files.writeString(destination.toPath(), content, StandardCharsets.UTF_8)
            }
        }
        IdeaRunConfigurations.orderedTopologies(catalog).each { Map topology ->
            String name = ServerPluginRuntimeSupport.configurationName(topology)
            if (!names.add(name)) throw new IllegalStateException("Duplicate IDEA run configuration ${name}")
            File destination = new File(output, ServerPluginRuntimeSupport.configurationFileName(topology))
            generatedFiles.add(destination.name)
            if (destination.isFile() && !destination.getText('UTF-8').contains(IdeaRunConfigurations.GENERATED_MARKER)) {
                throw new IllegalStateException("IDEA run configuration path is user-owned: ${destination}")
            }
            String content = IdeaRunConfigurations.renderServerPlugin(topology)
            if (!destination.isFile() || destination.getText('UTF-8') != content) {
                Files.writeString(destination.toPath(), content, StandardCharsets.UTF_8)
            }
        }
        output.listFiles({ File file -> file.name.endsWith('.xml') } as FileFilter)?.each { File file ->
            if (!generatedFiles.contains(file.name) &&
                    file.getText('UTF-8').contains(IdeaRunConfigurations.GENERATED_MARKER)) {
                Files.delete(file.toPath())
            }
        }
        updateWorkspace(workspaceFile.get().asFile, existingManagedNames,
                IdeaRunConfigurations.orderedConfigurationNames(catalog),
                IdeaRunConfigurations.previousToCurrentNames(catalog))
        logger.lifecycle("Generated ${names.size()} Gradle IDEA run configurations in ${output}")
    }

    private static Set<String> existingManagedNames(File output) {
        Set<String> result = [] as Set
        output.listFiles({ File file -> file.name.endsWith('.xml') } as FileFilter)?.each { File file ->
            if (file.getText('UTF-8').contains(IdeaRunConfigurations.GENERATED_MARKER)) {
                try { result.add(new XmlSlurper().parse(file).configuration.@name.toString()) }
                catch (Exception error) { throw new IllegalStateException("Cannot parse managed IDEA run configuration ${file}", error) }
            }
        }
        result
    }

    static void updateWorkspace(File workspace, Set<String> oldManagedNames,
                                List<String> orderedNames, Map<String, String> renamed) {
        if (!workspace.isFile()) return
        String text = workspace.getText('UTF-8')
        int componentStart = text.indexOf('<component name="RunManager"')
        if (componentStart < 0) return
        int componentEnd = text.indexOf('</component>', componentStart)
        if (componentEnd < 0) throw new IllegalStateException("Malformed RunManager in ${workspace}")
        componentEnd += '</component>'.length()
        String component = text.substring(componentStart, componentEnd)
        def matcher = component =~ /(?s)<list>\s*(?:<item itemvalue="[^"]*" \/>\s*)*<\/list>/
        if (!matcher.find()) throw new IllegalStateException("RunManager list is missing in ${workspace}")
        String list = matcher.group()
        List<String> userItems = []
        def itemMatcher = list =~ /<item itemvalue="([^"]*)" \/>/
        Set<String> managedValues = (oldManagedNames + orderedNames + renamed.keySet()).collect {
            "Gradle.${it}".toString()
        } as Set
        while (itemMatcher.find()) {
            String value = itemMatcher.group(1)
            if (!managedValues.contains(value)) userItems.add("      <item itemvalue=\"${value}\" />")
        }
        List<String> lines = ['<list>']
        lines.addAll(userItems)
        lines.addAll(orderedNames.collect { "      <item itemvalue=\"Gradle.${it}\" />".toString() })
        lines.add('    </list>')
        String replacement = lines.join(System.lineSeparator())
        String updatedComponent = component.substring(0, matcher.start()) + replacement +
                component.substring(matcher.end())
        renamed.each { String oldName, String newName ->
            updatedComponent = updatedComponent.replace(
                    "selected=\"Gradle.${oldName}\"", "selected=\"Gradle.${newName}\"")
        }
        String updated = text.substring(0, componentStart) + updatedComponent + text.substring(componentEnd)
        if (updated != text) Files.writeString(workspace.toPath(), updated, StandardCharsets.UTF_8)
    }
}

package com.naocraftlab.skins.buildlogic

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction

abstract class AffectedTargetsTask extends DefaultTask {
    @Internal
    abstract DirectoryProperty getRepositoryDirectory()

    @InputFile
    abstract RegularFileProperty getCatalogFile()

    @Internal
    abstract Property<String> getFromRef()

    @Internal
    abstract Property<String> getToRef()

    @TaskAction
    void report() {
        Map catalog = CatalogTools.loadJson(catalogFile.get().asFile.toPath())
        File repository = repositoryDirectory.get().asFile
        String from = fromRef.orNull
        List<String> paths = changedPaths(from, toRef.orNull)
        List<Map> historicalCatalogs = from == null
                ? [] : [ReleaseSelection.catalogAtRef(repository, from)]
        Map result = CatalogTools.affectedResult(
                repository, catalog, paths, historicalCatalogs)
        println CatalogTools.json(result)
    }

    List<String> changedPaths(String from, String to) {
        List<String> arguments
        if (from != null) {
            arguments = ['git', 'diff', '--name-only', '--no-renames', from]
            if (to != null) {
                arguments.add(to)
            }
        } else {
            arguments = ['git', 'status', '--short', '--untracked-files=all']
        }
        Process process = new ProcessBuilder(arguments).directory(repositoryDirectory.get().asFile).start()
        String output = process.inputStream.getText('UTF-8')
        String error = process.errorStream.getText('UTF-8')
        if (process.waitFor() != 0) {
            throw new IllegalStateException(error.trim())
        }
        if (from == null) {
            return output.readLines().collect { String line -> line.length() > 3 ? line.substring(3) : '' }
                .findAll { !it.isBlank() }
        }
        output.readLines().findAll { !it.isBlank() }
    }
}

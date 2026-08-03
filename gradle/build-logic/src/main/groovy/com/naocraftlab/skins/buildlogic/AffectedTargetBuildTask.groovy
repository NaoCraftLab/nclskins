package com.naocraftlab.skins.buildlogic

import org.gradle.api.provider.Property
import org.gradle.api.tasks.Internal

abstract class AffectedTargetBuildTask extends TargetBuildTask {
    @Internal
    abstract Property<String> getFromRef()

    @Internal
    abstract Property<String> getToRef()

    @Override
    List<String> selectedTargetIds(Map catalog) {
        List<String> paths = changedPaths(fromRef.orNull, toRef.orNull)
        Map classified = CatalogTools.affectedResult(repositoryDirectory.get().asFile, catalog, paths)
        classified.targetIds as List<String>
    }

    List<String> changedPaths(String from, String to) {
        List<String> arguments = from == null
            ? ['git', 'status', '--short', '--untracked-files=all']
            : ['git', 'diff', '--name-only', '--no-renames', from] + (to == null ? [] : [to])
        Process process = new ProcessBuilder(arguments).directory(repositoryDirectory.get().asFile).start()
        String output = process.inputStream.getText('UTF-8')
        String error = process.errorStream.getText('UTF-8')
        if (process.waitFor() != 0) throw new IllegalStateException(error.trim())
        from == null
            ? output.readLines().collect { it.length() > 3 ? it.substring(3) : '' }.findAll { !it.isBlank() }
            : output.readLines().findAll { !it.isBlank() }
    }
}

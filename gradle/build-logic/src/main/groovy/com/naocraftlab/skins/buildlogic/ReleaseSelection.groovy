package com.naocraftlab.skins.buildlogic

import groovy.json.JsonSlurper

final class ReleaseSelection {
    static Map selectTag(File repository, Map catalog, String releaseTag) {
        String sourceCommit = git(repository, ['rev-parse', "${releaseTag}^{commit}"]).trim()
        String baseTag = nearestPreviousTag(repository, releaseTag)
        List<String> paths = baseTag == null
                ? [] : git(repository, ['diff', '--name-only', '--no-renames', baseTag, releaseTag])
                    .readLines().findAll { !it.isBlank() }
        List<Map> historicalCatalogs = baseTag == null
                ? [] : [catalogAtRef(repository, baseTag)]
        Map affected = selectFromPaths(
                repository, catalog, paths, baseTag != null, historicalCatalogs)
        [sourceCommit: sourceCommit, baseTag: baseTag, paths: paths,
         targetIds: affected.targetIds, reasons: affected.reasons]
    }

    static Map selectFromPaths(
            File repository,
            Map catalog,
            Collection<String> rawPaths,
            boolean hasBaseTag,
            Collection<Map> historicalCatalogs = []) {
        List<String> productionPaths = rawPaths.findAll {
            it != 'gradle/version.properties'
        } as List<String>
        Map result = CatalogTools.affectedResult(
                repository, catalog, productionPaths, historicalCatalogs)
        List<String> allTargets = CatalogTools.releaseTargets(catalog).collect { it.id.toString() }
        if (!hasBaseTag || (result.targetIds as List).isEmpty()) {
            return [targetIds: allTargets, reasons: result.reasons]
        }
        List<String> selected = (result.targetIds as List).findAll { it in allTargets }
        if (selected.isEmpty()) {
            throw new IllegalStateException(
                    'Release diff affects only non-release-eligible targets')
        }
        [targetIds: selected, reasons: result.reasons]
    }

    static Map catalogAtRef(File repository, String ref) {
        Object parsed = new JsonSlurper().parseText(
                git(repository, ['show', "${ref}:gradle/targets.json"]))
        if (!(parsed instanceof Map)) {
            throw new IllegalStateException("target catalog at ${ref} is not an object")
        }
        CatalogTools.materialize(parsed) as Map
    }

    static String nearestPreviousTag(File repository, String releaseTag) {
        List<String> commits = git(repository, ['rev-list', '--first-parent', "${releaseTag}^{commit}"])
                .readLines().findAll { !it.isBlank() }
        for (String commit : commits.drop(1)) {
            List<String> tags = git(repository, ['tag', '--points-at', commit])
                    .readLines()
                    .findAll { String tag ->
                        tag != releaseTag && CatalogTools.VERSION_PATTERN.matcher(tag).matches()
                    }
                    .sort()
            if (!tags.isEmpty()) return tags.last()
        }
        null
    }

    static String git(File repository, List<String> arguments) {
        List<String> command = (['git'] + arguments).collect { it.toString() }
        Process process = new ProcessBuilder(command)
                .directory(repository)
                .start()
        String output = process.inputStream.getText('UTF-8')
        String error = process.errorStream.getText('UTF-8')
        if (process.waitFor() != 0) {
            throw new IllegalStateException("git ${arguments.join(' ')} failed: ${error.trim()}")
        }
        output
    }

    private ReleaseSelection() {}
}

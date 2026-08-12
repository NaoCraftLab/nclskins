package com.naocraftlab.skins.buildlogic

final class ReleaseSelection {
    static Map selectTag(File repository, Map catalog, String releaseTag) {
        String sourceCommit = git(repository, ['rev-parse', "${releaseTag}^{commit}"]).trim()
        String baseTag = nearestPreviousTag(repository, releaseTag)
        List<String> paths = baseTag == null
                ? [] : git(repository, ['diff', '--name-only', '--no-renames', baseTag, releaseTag])
                    .readLines().findAll { !it.isBlank() }
        Map affected = selectFromPaths(repository, catalog, paths, baseTag != null)
        [sourceCommit: sourceCommit, baseTag: baseTag, paths: paths,
         targetIds: affected.targetIds, reasons: affected.reasons]
    }

    static Map selectFromPaths(
            File repository,
            Map catalog,
            Collection<String> rawPaths,
            boolean hasBaseTag) {
        List<String> productionPaths = rawPaths.findAll {
            it != 'gradle/version.properties'
        } as List<String>
        Map result = CatalogTools.affectedResult(repository, catalog, productionPaths)
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

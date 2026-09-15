package com.naocraftlab.skins.buildlogic

final class ServerPluginVersion {
    static String load(File repository) {
        File file = new File(repository, 'gradle/plugin-version.properties')
        if (!file.isFile()) throw new IllegalArgumentException('gradle/plugin-version.properties is missing')
        parse(file.text)
    }

    static String parse(String text) {
        List<String> lines = text.readLines().collect { it.trim() }.findAll { it && !it.startsWith('#') }
        if (lines.size() != 2 || !lines[0].startsWith('pluginVersion=') ||
                !lines[1].startsWith('pluginBuild=')) {
            throw new IllegalArgumentException('Plugin version requires exactly pluginVersion and pluginBuild')
        }
        String base = lines[0].substring('pluginVersion='.length())
        String build = lines[1].substring('pluginBuild='.length())
        if (!CatalogTools.VERSION_PATTERN.matcher(base).matches() || !(build ==~ /[1-9][0-9]*/)) {
            throw new IllegalArgumentException('Invalid plugin version or build number')
        }
        int suffix = base.indexOf('-')
        suffix < 0 ? "${base}.${build}".toString() :
                "${base.substring(0, suffix)}.${build}${base.substring(suffix)}".toString()
    }

    static String atRef(File repository, String ref, String fallback) {
        String path = 'gradle/plugin-version.properties'
        String listing = ReleaseSelection.git(repository, ['ls-tree', '--name-only', ref, '--', path]).trim()
        listing.isEmpty() ? fallback : parse(ReleaseSelection.git(repository, ['show', "${ref}:${path}"]))
    }

    static Map parts(String version) {
        def matcher = version =~ /^(\d+\.\d+\.\d+)(?:\.([1-9][0-9]*))?(-(alpha|beta)\.\d+)?$/
        if (!matcher.matches()) throw new IllegalArgumentException("Invalid plugin version ${version}")
        [base: matcher.group(1) + (matcher.group(3) ?: ''),
         build: new BigInteger(matcher.group(2) ?: '1')]
    }

    static int compare(String left, String right) {
        Map a = parts(left)
        Map b = parts(right)
        int base = ServerPluginReleaseState.compareVersions(a.base.toString(), b.base.toString())
        base != 0 ? base : a.build <=> b.build
    }

    private ServerPluginVersion() {}
}

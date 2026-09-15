package com.naocraftlab.skins.buildlogic

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.regex.Pattern


final class ServerPluginChangelog {
    static String validate(File changelog, Map state) {
        if (!changelog.isFile()) {
            throw new IllegalArgumentException('PLUGIN_CHANGELOG.md is missing')
        }
        String version = state.currentVersion.toString()
        List<String> lines = Files.readAllLines(changelog.toPath(), StandardCharsets.UTF_8)
        Pattern headingPattern = headingPattern(version)
        requireVersionFirstFormat(lines, null)
        List<Integer> matches = []
        lines.eachWithIndex { String line, int index ->
            if (headingPattern.matcher(line).matches()) matches.add(index)
        }
        if (state.publish == true) {
            if (matches.size() != 1) {
                throw new IllegalArgumentException(
                        "PLUGIN_CHANGELOG.md must contain exactly one section for '${version}' when " +
                                "server plugin publication is ${state.reason}; found ${matches.size()}")
            }
            String heading = lines[matches.first()]
            requireVersionFirstFormat(lines, heading)
            int start = matches.first() + 1
            int end = lines.size()
            for (int index = start; index < lines.size(); index++) {
                if (lines[index].startsWith('## ')) {
                    end = index
                    break
                }
            }
            List<String> body = new ArrayList<>(lines.subList(start, end))
            while (!body.isEmpty() && body.first().isBlank()) body.remove(0)
            while (!body.isEmpty() && body.last().isBlank()) body.remove(body.size() - 1)
            if (body.isEmpty()) {
                throw new IllegalArgumentException("PLUGIN_CHANGELOG.md section '${heading}' is empty")
            }
            String notes = body.join('\n') + '\n'
            if (state.reason == 'stable-promotion') {
                String normalized = notes.toLowerCase(Locale.ROOT)
                if (!normalized.contains('stable') ||
                        !(normalized.contains('without behavior change') ||
                                normalized.contains('no behavior change'))) {
                    throw new IllegalArgumentException(
                            'stable-promotion server notes must state stable publication without behavior change')
                }
            }
            return notes
        }
        if (!matches.isEmpty()) {
            throw new IllegalArgumentException(
                    "PLUGIN_CHANGELOG.md must not contain a '${version}' section when server plugin is unchanged")
        }
        null
    }

    private static Pattern headingPattern(String version) {
        def matcher = version =~ /^(\d+\.\d+\.\d+)(-(?:alpha|beta)\.\d+)?$/
        if (!matcher.matches()) throw new IllegalArgumentException("Invalid plugin release version ${version}")
        String base = Pattern.quote(matcher.group(1))
        String qualifier = Pattern.quote(matcher.group(2) ?: '')
        Pattern.compile("^## ${base}(?:\\.[1-9][0-9]*)?${qualifier}\$")
    }

    private static void requireVersionFirstFormat(List<String> lines, String expectedHeading) {
        String first = lines.find { !it.isBlank() }
        boolean versionHeading = first != null && first.startsWith('## ') &&
                first.substring(3) ==~ /\d+\.\d+\.\d+(?:\.[1-9][0-9]*)?(?:-(?:alpha|beta)\.\d+)?/
        if (!versionHeading || (expectedHeading != null && first != expectedHeading) ||
                lines.any { it.startsWith('# ') }) {
            String expected = expectedHeading == null ? 'a version heading' : "'${expectedHeading}'"
            throw new IllegalArgumentException(
                    "PLUGIN_CHANGELOG.md must start with ${expected} and must not contain a level-one heading")
        }
    }

    private ServerPluginChangelog() {
    }
}

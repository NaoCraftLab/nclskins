package com.naocraftlab.skins.buildlogic

import java.nio.charset.StandardCharsets
import java.nio.file.Files


final class ServerPluginChangelog {
    static String validate(File changelog, Map state) {
        if (!changelog.isFile()) {
            throw new IllegalArgumentException('SERVER_CHANGELOG.md is missing')
        }
        String version = state.currentVersion.toString()
        List<String> lines = Files.readAllLines(changelog.toPath(), StandardCharsets.UTF_8)
        String heading = "## ${version}"
        List<Integer> matches = []
        lines.eachWithIndex { String line, int index ->
            if (line == heading) matches.add(index)
        }
        if (state.publish == true) {
            if (matches.size() != 1) {
                throw new IllegalArgumentException(
                        "SERVER_CHANGELOG.md must contain exactly one '${heading}' section when " +
                                "server plugin publication is ${state.reason}; found ${matches.size()}")
            }
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
                throw new IllegalArgumentException("SERVER_CHANGELOG.md section '${heading}' is empty")
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
                    "SERVER_CHANGELOG.md must not contain '${heading}' when server plugin is unchanged")
        }
        null
    }

    private ServerPluginChangelog() {
    }
}

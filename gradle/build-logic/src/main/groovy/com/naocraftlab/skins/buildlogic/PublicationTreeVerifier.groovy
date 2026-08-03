package com.naocraftlab.skins.buildlogic

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.regex.Pattern

final class PublicationTreeVerifier {
    static final Set<String> CODE_SUFFIXES = ['.java', '.groovy', '.gradle'] as Set
    static final List<String> PRIVATE_TOOL_REFERENCES = ['py' + 'thon3', 'do' + 'cs/', 'scr' + 'ipts/']
    static final Pattern SECRET = Pattern.compile('(?i)(Bearer\\s+[A-Za-z0-9._~+/=-]{20,}|eyJ[A-Za-z0-9_-]{20,}\\.[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}|refresh' + '_token|launcher' + '_accounts\\.json|login' + '\\.microsoftonline\\.com)')

    static List<String> verify(Path root) {
        List<String> errors = []
        publicFiles(root).each { Path file ->
            String relative = root.relativize(file).toString().replace(File.separatorChar, '/' as char)
            if (relative.endsWith('.pixel.json')) errors.add("${relative}: pixel-grid agent data is public")
            if (relative.endsWith('.py') || (relative.endsWith('.sh') && relative != 'gradlew')) errors.add("${relative}: Python/shell tooling is public")
            if (!isText(file)) return
            String text = Files.readString(file, StandardCharsets.UTF_8)
            if (SECRET.matcher(text).find()) errors.add("${relative}: credential or launcher-auth pattern found")
            String home = System.getProperty('user.home').replace(File.separatorChar, '/' as char)
            if (text.replace('\\', '/').contains(home + '/')) errors.add("${relative}: local machine path found")
            if (CODE_SUFFIXES.any { relative.endsWith(it) }) {
                if (containsComment(text, relative.endsWith('.groovy') || relative.endsWith('.gradle'))) errors.add("${relative}: source comment found")
                String normalized = text.replace('\\', '/')
                if (containsPrivateToolReference(normalized)) errors.add("${relative}: public code references private tooling")
            }
        }
        errors
    }

    static List<Path> publicFiles(Path root) {
        Set<Path> files = [] as Set
        Process process = new ProcessBuilder('git', 'ls-files', '--cached', '--others', '--exclude-standard').directory(root.toFile()).start()
        String output = process.inputStream.getText('UTF-8')
        String error = process.errorStream.getText('UTF-8')
        if (process.waitFor() != 0) throw new IllegalStateException(error.trim())
        output.readLines().findAll { !it.isBlank() }.each { String relative ->
            Path file = root.resolve(relative).normalize()
            if (Files.isRegularFile(file)) files.add(file)
        }
        Path buildLogic = root.resolve('gradle/build-logic')
        if (Files.isDirectory(buildLogic)) {
            Files.walk(buildLogic).withCloseable { stream ->
                stream.filter { Path path ->
                    String relative = root.relativize(path).toString().replace(File.separatorChar, '/' as char)
                    Files.isRegularFile(path) && !relative.startsWith('gradle/build-logic/build/') && !relative.contains('/.gradle/')
                }.forEach { files.add(it) }
            }
        }
        files as List<Path>
    }

    static boolean isText(Path file) {
        String name = file.fileName.toString()
        if (name.endsWith('.jar') || name.endsWith('.png') || name.endsWith('.class')) return false
        byte[] sample = Files.readAllBytes(file)
        int limit = Math.min(sample.length, 4096)
        for (int index = 0; index < limit; index++) {
            if (sample[index] == 0) return false
        }
        true
    }

    static boolean containsComment(String text, boolean groovy) {
        String state = 'code'
        boolean escaped = false
        char previous = (char) 0
        for (int index = 0; index < text.length(); index++) {
            char character = text.charAt(index)
            char following = index + 1 < text.length() ? text.charAt(index + 1) : (char) 0
            if (state == 'string' || state == 'character' || state == 'slashy') {
                if (escaped) escaped = false
                else if (character == '\\') escaped = true
                else if ((state == 'string' && character == '"') || (state == 'character' && character == '\'') || (state == 'slashy' && character == '/')) state = 'code'
                continue
            }
            if (state == 'text') {
                if (character == '"' && following == '"' && index + 2 < text.length() && text.charAt(index + 2) == '"') { state = 'code'; index += 2 }
                continue
            }
            if (state == 'dollar') {
                if (character == '/' && following == '$') { state = 'code'; index++ }
                continue
            }
            if (character == '"' && following == '"' && index + 2 < text.length() && text.charAt(index + 2) == '"') { state = 'text'; index += 2; continue }
            if (character == '"') { state = 'string'; escaped = false; continue }
            if (character == '\'') { state = 'character'; escaped = false; continue }
            if (groovy && character == '$' && following == '/') { state = 'dollar'; index++; continue }
            if (character == '/' && (following == '/' || following == '*')) return true
            if (groovy && character == '/' && regexCanStart(previous)) { state = 'slashy'; escaped = false; continue }
            if (!Character.isWhitespace(character) || character == '\n') previous = character
        }
        false
    }

    static boolean containsPrivateToolReference(String text) {
        PRIVATE_TOOL_REFERENCES.any { text.replace('\\', '/').contains(it) }
    }

    static boolean regexCanStart(char previous) {
        previous == (char) 0 || "=(:,[{!&|?;+-*%^~<>\n".indexOf((int) previous) >= 0
    }

    private PublicationTreeVerifier() {}
}

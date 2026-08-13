package com.naocraftlab.skins.buildlogic

import groovy.json.JsonOutput
import groovy.json.JsonSlurper

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest


final class ServerPluginFingerprint {
    static String current(File repositoryRoot, Map catalog) {
        Map plugin = catalog.serverPlugin as Map
        Map<String, byte[]> inputs = new TreeMap<>()
        Map productionInputs = plugin.productionInputs as Map
        ((productionInputs.server as List) + (productionInputs.shared as List)).each { Object raw ->
            collect(repositoryRoot, raw.toString(), inputs)
        }
        (productionInputs.server as List).each { Object raw ->
            File declared = new File(repositoryRoot, raw.toString())
            File module = declared.isDirectory() && declared.name in ['main', 'server-plugin-adapters']
                    ? declared.parentFile?.parentFile
                    : declared
            if (declared.name == 'server-plugin-adapters') module = declared
            File build = module == null ? null : new File(module, 'build.gradle')
            if (build?.isFile()) {
                inputs[relative(repositoryRoot, build)] = build.bytes
            }
        }
        Map normalizedPlugin = CatalogTools.materialize(plugin) as Map
        normalizedPlugin.remove('platforms')
        normalizedPlugin.remove('productionInputs')
        inputs['gradle/targets.json#serverPlugin'] = canonical(normalizedPlugin)

        digest(inputs)
    }

    static String atRef(File repositoryRoot, String ref) {
        Map catalog = new JsonSlurper().parseText(ReleaseSelection.git(
                repositoryRoot, ['show', "${ref}:gradle/targets.json"])) as Map
        if (!(catalog.serverPlugin instanceof Map)) {
            return null
        }
        Map plugin = catalog.serverPlugin as Map
        Map<String, byte[]> inputs = new TreeMap<>()
        Map productionInputs = plugin.productionInputs as Map
        ((productionInputs.server as List) + (productionInputs.shared as List)).each { Object raw ->
            collectAtRef(repositoryRoot, ref, raw.toString(), inputs)
        }
        (productionInputs.server as List).each { Object raw ->
            String path = raw.toString()
            List<String> segments = path.split('/') as List<String>
            String module = segments.size() >= 2 && segments[-2..-1] == ['src', 'main']
                    ? segments[0..-3].join('/') : path
            collectAtRef(repositoryRoot, ref, module + '/build.gradle', inputs)
        }
        Map normalizedPlugin = CatalogTools.materialize(plugin) as Map
        normalizedPlugin.remove('platforms')
        normalizedPlugin.remove('productionInputs')
        inputs['gradle/targets.json#serverPlugin'] = canonical(normalizedPlugin)
        digest(inputs)
    }

    private static String digest(Map<String, byte[]> inputs) {
        MessageDigest digest = MessageDigest.getInstance('SHA-256')
        inputs.each { String path, byte[] content ->
            byte[] name = path.getBytes(StandardCharsets.UTF_8)
            digest.update(intBytes(name.length))
            digest.update(name)
            digest.update(intBytes(content.length))
            digest.update(content)
        }
        digest.digest().encodeHex().toString()
    }

    private static void collectAtRef(
            File repositoryRoot,
            String ref,
            String path,
            Map<String, byte[]> inputs) {
        String listing = ReleaseSelection.git(
                repositoryRoot, ['ls-tree', '-r', '--name-only', ref, '--', path])
        listing.readLines().findAll { !it.isBlank() }.each { String entry ->
            inputs[entry] = normalizedContent(
                    entry, gitBytes(repositoryRoot, ['show', "${ref}:${entry}"]))
        }
    }

    private static byte[] gitBytes(File repositoryRoot, List<String> arguments) {
        Process process = new ProcessBuilder((['git'] + arguments).collect { it.toString() })
                .directory(repositoryRoot)
                .start()
        byte[] output = process.inputStream.readAllBytes()
        String error = process.errorStream.getText('UTF-8')
        if (process.waitFor() != 0) {
            throw new IllegalStateException("git ${arguments.join(' ')} failed: ${error.trim()}")
        }
        output
    }

    private static void collect(
            File repositoryRoot,
            String rawPath,
            Map<String, byte[]> inputs) {
        File file = new File(repositoryRoot, rawPath)
        if (!file.exists()) {
            return
        }
        if (file.isFile()) {
            inputs[rawPath] = normalizedContent(rawPath, file.bytes)
            return
        }
        Files.walk(file.toPath()).withCloseable { stream ->
            stream.filter { Files.isRegularFile(it) }.forEach { Path path ->
                File child = path.toFile()
                String relative = relative(repositoryRoot, child)
                if (!relative.contains('/build/') && !relative.endsWith('.class')) {
                    inputs[relative] = normalizedContent(relative, child.bytes)
                }
            }
        }
    }

    private static byte[] normalizedContent(String path, byte[] bytes) {
        if (path.endsWith('assets/nclskins/lang/en_us.json')) {
            Map parsed = new JsonSlurper().parse(bytes) as Map
            Map server = new TreeMap()
            parsed.each { Object key, Object value ->
                if (key.toString().startsWith('nclskins.config.server.')) {
                    server[key.toString()] = value
                }
            }
            return canonical(server)
        }
        if (path.endsWith('plugin.yml') || path.endsWith('bungee.yml') ||
                path.endsWith('velocity-plugin.json') ||
                path.endsWith('nclskins-server-plugin.json')) {
            String normalized = new String(bytes, StandardCharsets.UTF_8)
                    .replaceAll(/(?m)^(version:\s*['"]?)[^'"\r\n]+/, '$1@VERSION@')
                    .replaceAll(/("version"\s*:\s*")[^"]+/, '$1@VERSION@')
                    .replace('@NCLSKINS_SERVER_FINGERPRINT@', '@FINGERPRINT@')
            return normalized.getBytes(StandardCharsets.UTF_8)
        }
        bytes
    }

    private static byte[] canonical(Object value) {
        JsonOutput.toJson(sort(value)).getBytes(StandardCharsets.UTF_8)
    }

    private static Object sort(Object value) {
        if (value instanceof Map) {
            Map sorted = new TreeMap()
            (value as Map).each { Object key, Object nested ->
                sorted[key.toString()] = sort(nested)
            }
            return sorted
        }
        if (value instanceof List) {
            return (value as List).collect { sort(it) }
        }
        value
    }

    private static byte[] intBytes(int value) {
        java.nio.ByteBuffer.allocate(Integer.BYTES).putInt(value).array()
    }

    private static String relative(File root, File file) {
        root.toPath().toAbsolutePath().normalize()
                .relativize(file.toPath().toAbsolutePath().normalize())
                .toString().replace(File.separatorChar, '/' as char)
    }

    private ServerPluginFingerprint() {
    }
}

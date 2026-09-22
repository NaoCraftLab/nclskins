package com.naocraftlab.skins.buildlogic

import groovy.json.JsonOutput
import groovy.json.JsonSlurper

import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

class BuildReceipt {
    static final int SCHEMA = 1
    static final Set<String> DOCUMENTS = ['README.md', 'CHANGELOG.md', 'PLUGIN_CHANGELOG.md'] as Set

    static File location(File root) {
        new File(root, 'build/verification/prism-build.json')
    }

    static String hash(File file) {
        MessageDigest digest = MessageDigest.getInstance('SHA-256')
        file.withInputStream { input ->
            byte[] buffer = new byte[65536]
            int count
            while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count)
        }
        digest.digest().encodeHex().toString()
    }

    static String fingerprint(File root) {
        Process process = new ProcessBuilder('git', 'ls-files', '--cached', '--others', '--exclude-standard', '-z')
                .directory(root).redirectError(ProcessBuilder.Redirect.INHERIT).start()
        byte[] output = process.inputStream.readAllBytes()
        if (process.waitFor() != 0) throw new IllegalStateException('Cannot enumerate build inputs')
        MessageDigest digest = MessageDigest.getInstance('SHA-256')
        new String(output, 'UTF-8').split('\u0000').toList().unique().sort().each { String path ->
            if (!path || DOCUMENTS.contains(path)) return
            File file = new File(root, path)
            List identity = [path, file.exists(), file.canExecute()]
            if (Files.isSymbolicLink(file.toPath())) identity.add(Files.readSymbolicLink(file.toPath()).toString())
            if (file.isFile()) identity.add(hash(file))
            else if (file.exists()) throw new IllegalStateException('Unsupported non-file build input: ' + path)
            digest.update(JsonOutput.toJson(identity).getBytes('UTF-8'))
            digest.update((byte) 0)
        }
        digest.digest().encodeHex().toString()
    }

    static Map snapshot(File root, Map catalog, Map environment) {
        [schemaVersion: SCHEMA, checkout: root.canonicalPath,
         modVersion: CatalogTools.loadVersion(root), targets: catalog.targets.collect { it.id.toString() }.sort(),
         inputsFingerprint: fingerprint(root), environment: environment]
    }

    static List<Map> artifacts(File root, Map catalog) {
        String version = CatalogTools.loadVersion(root)
        catalog.targets.collect { Map target ->
            String path = "${target.path}/build/libs/${target.artifact.file.toString().replace('{modVersion}', version)}"
            File artifact = new File(root, path)
            if (!artifact.isFile()) throw new IllegalStateException('Missing artifact: ' + path)
            List<String> errors = []
            ArtifactVerifier.verifyCompatibilityReport(root, target, version, errors)
            if (!errors.isEmpty()) throw new IllegalStateException(errors.join('\n'))
            [target: target.id.toString(), path: path, sha256: hash(artifact)]
        }.sort { it.target }
    }

    static Map status(File root, Map catalog, Map current) {
        File receipt = location(root)
        if (!receipt.isFile()) return [reusable: false, reason: 'missing-receipt']
        try {
            def record = new JsonSlurper().parse(receipt)
            if (!(record instanceof Map)) return [reusable: false, reason: 'invalid-receipt']
            for (String key : current.keySet()) {
                if (record[key] != current[key]) return [reusable: false, reason: 'mismatch-' + key]
            }
            if (!(record.verificationLevel in ['fullCheck', 'incremental', 'clean'])) {
                return [reusable: false, reason: 'invalid-verification-level']
            }
            if (record.artifacts != artifacts(root, catalog)) return [reusable: false, reason: 'mismatch-artifacts']
            [reusable: true, reason: 'current', receipt: record]
        } catch (Exception ignored) {
            [reusable: false, reason: 'invalid-receipt-or-artifacts']
        }
    }

    static void invalidate(File root) {
        Files.deleteIfExists(location(root).toPath())
    }

    static void publish(File root, Map catalog, Map before, Map after, String level) {
        if (before != after) throw new IllegalStateException('Build inputs changed during verification')
        if (!(level in ['fullCheck', 'incremental', 'clean'])) throw new IllegalArgumentException('Invalid verification level')
        Map record = new LinkedHashMap(before)
        record.verificationLevel = level
        record.artifacts = artifacts(root, catalog)
        File destination = location(root)
        destination.parentFile.mkdirs()
        def temporary = Files.createTempFile(destination.parentFile.toPath(), 'receipt-', '.tmp')
        try {
            Files.writeString(temporary, JsonOutput.prettyPrint(JsonOutput.toJson(record)) + '\n')
            Files.move(temporary, destination.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    static String treeHash(File directory) {
        MessageDigest digest = MessageDigest.getInstance('SHA-256')
        List<File> files = []
        if (directory.isDirectory()) directory.eachFileRecurse { if (it.isFile()) files.add(it) }
        files.sort { it.path }.each { File file ->
            digest.update(directory.toPath().relativize(file.toPath()).toString().getBytes('UTF-8'))
            digest.update((byte) 0)
            digest.update(hash(file).getBytes('UTF-8'))
            digest.update((byte) 0)
        }
        digest.digest().encodeHex().toString()
    }

    static Map javaIdentity(File home) {
        File canonical = home.canonicalFile
        Map files = [:]
        ['release', 'bin/java', 'bin/javac', 'lib/modules'].each { String path ->
            File file = new File(canonical, path)
            if (!file.isFile()) throw new IllegalStateException('Incomplete JDK: ' + canonical)
            files[path] = hash(file)
        }
        [home: canonical.path, files: files]
    }
}

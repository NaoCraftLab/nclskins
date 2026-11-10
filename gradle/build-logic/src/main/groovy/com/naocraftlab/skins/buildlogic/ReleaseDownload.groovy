package com.naocraftlab.skins.buildlogic

import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.time.Duration

final class ReleaseDownload {
    static void unpackActionsArtifact(byte[] archive, File destination) {
        if (archive.length > 268435456 || destination.exists()) throw new IllegalStateException('Unsafe or oversized resume archive destination')
        Set<String> names = [] as Set
        long total = 0
        int entries = 0
        java.util.zip.ZipInputStream zip = new java.util.zip.ZipInputStream(new ByteArrayInputStream(archive))
        try {
            zip.withCloseable { stream ->
                java.util.zip.ZipEntry entry
                while ((entry = stream.nextEntry) != null) {
                    String name = entry.name
                    if (++entries > 1024 || !name || name.contains('\\') || name.startsWith('/') ||
                            name.split('/').any { it in ['.', '..', ''] } || !names.add(name) ||
                            !(name ==~ /[A-Za-z0-9._+\/-]+/)) throw new IllegalStateException('Unsafe resume archive path')
                    def path = destination.toPath().resolve(name).normalize()
                    if (!path.startsWith(destination.toPath())) throw new IllegalStateException('Resume archive escapes destination')
                    if (entry.directory) { Files.createDirectories(path); continue }
                    Files.createDirectories(path.parent)
                    Files.newOutputStream(path, java.nio.file.StandardOpenOption.CREATE_NEW).withCloseable { output ->
                        byte[] buffer = new byte[65536]
                        int count
                        while ((count = stream.read(buffer)) != -1) {
                            total += count
                            if (total > 536870912) throw new IllegalStateException('Resume archive exceeds expanded byte limit')
                            output.write(buffer, 0, count)
                        }
                    }
                }
            }
            if (entries == 0) throw new IllegalStateException('Empty resume archive')
        } catch (Exception error) {
            destination.deleteDir()
            throw error
        }
    }

    static File fetch(String url, File destination, Map hashes) {
        URI uri = URI.create(url)
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20))
                .followRedirects(HttpClient.Redirect.NEVER).build()
        for (int redirect = 0; redirect < 6; redirect++) {
            if (uri.scheme != 'https' || uri.userInfo != null || uri.port != -1 ||
                    !(uri.host in ['github.com', 'release-assets.githubusercontent.com',
                                   'objects.githubusercontent.com', 'cdn.modrinth.com'] ||
                      uri.host?.endsWith('.forgecdn.net'))) {
                throw new IllegalStateException('Untrusted release download URL')
            }
            def response = ReleaseHttp.send(client, HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(90))
                    .GET().build(), HttpResponse.BodyHandlers.ofByteArray())
            if (response.statusCode() in [301, 302, 303, 307, 308]) {
                uri = uri.resolve(response.headers().firstValue('location').orElseThrow())
                continue
            }
            if (response.statusCode() != 200) {
                throw new IllegalStateException("Release download returned HTTP ${response.statusCode()}")
            }
            if (hashes.isEmpty()) throw new IllegalStateException('Release download has no expected hash')
            hashes.each { String algorithm, Object expected ->
                String actual = java.security.MessageDigest.getInstance(algorithm.toUpperCase(Locale.ROOT)
                        .replace('SHA', 'SHA-')).digest(response.body()).encodeHex().toString()
                if (actual != expected) throw new IllegalStateException("${destination.name}: ${algorithm} mismatch")
            }
            Files.createDirectories(destination.toPath().parent)
            Files.write(destination.toPath(), response.body())
            return destination
        }
        throw new IllegalStateException('Too many release download redirects')
    }
}

package com.naocraftlab.skins.buildlogic

import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.time.Duration

final class ReleaseDownload {
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

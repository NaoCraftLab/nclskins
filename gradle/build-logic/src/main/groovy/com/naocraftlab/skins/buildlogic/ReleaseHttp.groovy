package com.naocraftlab.skins.buildlogic

import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

final class ReleaseHttp {
    static Map readGithub(String url, String token, int maxBytes = 2097152) {
        URI uri = URI.create(url)
        HttpClient client = HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(20))
                .followRedirects(HttpClient.Redirect.NEVER).build()
        for (int redirect = 0; redirect < 6; redirect++) {
            boolean api = uri.host == 'api.github.com'
            if (uri.scheme != 'https' || uri.userInfo != null || uri.port != -1 ||
                    !(api || uri.host == 'github.com' || uri.host == 'productionresultssa0.blob.core.windows.net' ||
                            uri.host?.endsWith('.actions.githubusercontent.com') || uri.host?.endsWith('.blob.core.windows.net'))) {
                throw new IllegalStateException('Untrusted GitHub read URL')
            }
            HttpRequest.Builder builder = HttpRequest.newBuilder(uri).timeout(java.time.Duration.ofSeconds(90))
                    .header('Accept', 'application/vnd.github+json').header('X-GitHub-Api-Version', '2022-11-28').GET()
            if (api && token) builder.header('Authorization', "Bearer ${token}")
            def response = send(client, builder.build(), HttpResponse.BodyHandlers.ofInputStream())
            Map headers = response.headers().map().collectEntries { name, values -> [(name): values.join(',')] }
            byte[] bytes
            response.body().withCloseable { input -> bytes = input.readNBytes(maxBytes + 1) }
            if (bytes.length > maxBytes) throw new IllegalStateException('GitHub response exceeds byte limit')
            if (response.statusCode() in [301, 302, 303, 307, 308]) {
                String location = headers.location
                if (!location) throw new IllegalStateException('Missing GitHub redirect location')
                uri = uri.resolve(location)
                continue
            }
            if (response.statusCode() != 200) throw new IllegalStateException("GitHub read returned HTTP ${response.statusCode()}")
            return [status: response.statusCode(), headers: headers, body: bytes]
        }
        throw new IllegalStateException('Too many GitHub redirects')
    }

    static <T> HttpResponse<T> send(HttpClient client, HttpRequest request, HttpResponse.BodyHandler<T> handler) {
        int attempts = request.method() == 'GET' ? 3 : 1
        for (int attempt = 1; attempt <= attempts; attempt++) {
            try {
                HttpResponse<T> response = client.send(request, handler)
                if (!(response.statusCode() in [429, 500, 502, 503, 504]) || attempt == attempts) return response
                if (response.body() instanceof Closeable) (response.body() as Closeable).close()
            } catch (IOException error) {
                if (attempt == attempts) throw error
            }
            Thread.sleep(attempt * 1000L)
        }
        throw new IllegalStateException('HTTP attempts exhausted')
    }
}

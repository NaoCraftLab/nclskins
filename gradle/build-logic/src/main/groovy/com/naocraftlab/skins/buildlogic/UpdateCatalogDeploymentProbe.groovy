package com.naocraftlab.skins.buildlogic


import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

final class UpdateCatalogDeploymentProbe {
    static final URI BASE_URI = URI.create('https://naocraftlab.github.io/nclskins/')
    static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5)
    static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10)
    static final int MAX_BODY_BYTES = 512 * 1024
    static final int ATTEMPTS = 6

    interface Fetcher {
        ProbeResponse fetch(URI uri) throws IOException, InterruptedException
    }

    interface Sleeper {
        void pause() throws InterruptedException
    }

    static final class ProbeResponse {
        final int status
        final byte[] body

        ProbeResponse(int status, byte[] body) {
            this.status = status
            this.body = body.clone()
        }
    }

    static void verify(
            Map<String, byte[]> expected,
            Fetcher fetcher,
            Sleeper sleeper,
            int attempts = ATTEMPTS) {
        if (expected.isEmpty() || expected.size() > 64 || attempts < 1 || attempts > ATTEMPTS ||
                expected.any { String path, byte[] bytes ->
                    !(path ==~ /updates\/v1\/(?:catalog|native\/[a-z0-9.-]+)\.json/) ||
                            bytes == null || bytes.length > MAX_BODY_BYTES
                }) {
            throw new IllegalArgumentException('invalid deployed update catalog expectation')
        }
        Map<String, String> failures = [:]
        for (int attempt = 1; attempt <= attempts; attempt++) {
            failures.clear()
            expected.toSorted().each { String path, byte[] bytes ->
                URI uri = BASE_URI.resolve(path + '?nclskins_probe=' + attempt + '-' +
                        CatalogTools.sha256(bytes).substring(0, 12))
                try {
                    ProbeResponse response = fetcher.fetch(uri)
                    if (response.status == 404) {
                        failures[path] = 'missing'
                    } else if (response.status != 200) {
                        failures[path] = "http-${response.status}".toString()
                    } else if (!java.security.MessageDigest.isEqual(bytes, response.body)) {
                        failures[path] = 'stale'
                    }
                } catch (InterruptedException failure) {
                    Thread.currentThread().interrupt()
                    throw new IllegalStateException('update catalog deployment probe interrupted')
                } catch (IOException failure) {
                    failures[path] = 'unavailable'
                }
            }
            if (failures.isEmpty()) return
            if (attempt < attempts) {
                try {
                    sleeper.pause()
                } catch (InterruptedException failure) {
                    Thread.currentThread().interrupt()
                    throw new IllegalStateException('update catalog deployment probe interrupted')
                }
            }
        }
        throw new IllegalStateException('deployed update catalog differs: ' +
                failures.toSorted().collect { String path, String reason ->
                    "${path}=${reason}".toString()
                }.join(', '))
    }

    static Fetcher jdkFetcher() {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build()
        return { URI uri ->
            if (uri.scheme != 'https' || uri.host != BASE_URI.host ||
                    !uri.path.startsWith('/nclskins/updates/v1/')) {
                throw new IOException('invalid probe endpoint')
            }
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .timeout(REQUEST_TIMEOUT)
                    .GET()
                    .build()
            HttpResponse<InputStream> response = client.send(
                    request, HttpResponse.BodyHandlers.ofInputStream())
            byte[] body
            response.body().withCloseable { InputStream input ->
                body = readBounded(input)
            }
            new ProbeResponse(response.statusCode(), body)
        } as Fetcher
    }

    private static byte[] readBounded(InputStream input) {
        ByteArrayOutputStream output = new ByteArrayOutputStream()
        byte[] buffer = new byte[8192]
        int remaining = MAX_BODY_BYTES + 1
        while (remaining > 0) {
            int count = input.read(buffer, 0, Math.min(buffer.length, remaining))
            if (count < 0) break
            if (count == 0) {
                int value = input.read()
                if (value < 0) break
                output.write(value)
                remaining--
                continue
            }
            output.write(buffer, 0, count)
            remaining -= count
        }
        if (output.size() > MAX_BODY_BYTES) {
            throw new IOException('probe response exceeds body limit')
        }
        output.toByteArray()
    }

    private UpdateCatalogDeploymentProbe() {}
}

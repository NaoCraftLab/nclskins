package com.naocraftlab.skins.runtime.update;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.OptionalLong;

final class JdkUpdateHttpBoundary implements UpdateHttpBoundary {
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);

    private final HttpClient client;

    JdkUpdateHttpBoundary() {
        this(configuredHttpClient());
    }

    JdkUpdateHttpBoundary(HttpClient client) {
        this.client = java.util.Objects.requireNonNull(client, "client");
    }

    static HttpClient configuredHttpClient() {
        return HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    static HttpRequest request(URI uri, Duration timeout) {
        return HttpRequest.newBuilder(uri)
                .timeout(timeout)
                .GET()
                .build();
    }

    @Override
    public UpdateHttpResponse get(URI uri, Duration timeout)
            throws IOException, InterruptedException {
        HttpResponse<InputStream> response = client.send(
                request(uri, timeout), HttpResponse.BodyHandlers.ofInputStream());
        OptionalLong contentLength = response.headers().firstValueAsLong("content-length");
        return new UpdateHttpResponse(response.statusCode(), contentLength, response.body());
    }
}

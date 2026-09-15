package com.naocraftlab.skins.buildlogic

import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

final class ReleaseHttp {
    static <T> HttpResponse<T> send(HttpClient client, HttpRequest request, HttpResponse.BodyHandler<T> handler) {
        int attempts = request.method() == 'GET' ? 3 : 1
        for (int attempt = 1; attempt <= attempts; attempt++) {
            try {
                HttpResponse<T> response = client.send(request, handler)
                if (!(response.statusCode() in [429, 500, 502, 503, 504]) || attempt == attempts) return response
            } catch (IOException error) {
                if (attempt == attempts) throw error
            }
            Thread.sleep(attempt * 1000L)
        }
        throw new IllegalStateException('HTTP attempts exhausted')
    }
}

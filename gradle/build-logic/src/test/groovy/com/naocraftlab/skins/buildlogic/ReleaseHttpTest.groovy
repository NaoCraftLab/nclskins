package com.naocraftlab.skins.buildlogic

import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.Test

import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.concurrent.atomic.AtomicInteger

import static org.junit.jupiter.api.Assertions.assertEquals

final class ReleaseHttpTest {
    @Test
    void readsRetryButAmbiguousWritesNeverRepeat() {
        HttpServer server = HttpServer.create(new InetSocketAddress('127.0.0.1', 0), 0)
        AtomicInteger reads = new AtomicInteger()
        AtomicInteger writes = new AtomicInteger()
        server.createContext('/') { exchange ->
            int status = exchange.requestMethod == 'GET' ? (reads.incrementAndGet() < 3 ? 503 : 200) : 503
            if (exchange.requestMethod != 'GET') writes.incrementAndGet()
            exchange.sendResponseHeaders(status, -1)
            exchange.close()
        }
        server.start()
        try {
            URI uri = URI.create("http://127.0.0.1:${server.address.port}/")
            HttpClient client = HttpClient.newHttpClient()
            assertEquals(200, ReleaseHttp.send(client, HttpRequest.newBuilder(uri).GET().build(), HttpResponse.BodyHandlers.discarding()).statusCode())
            assertEquals(3, reads.get())
            assertEquals(503, ReleaseHttp.send(client, HttpRequest.newBuilder(uri).POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.discarding()).statusCode())
            assertEquals(1, writes.get())
        } finally { server.stop(0) }
    }
}

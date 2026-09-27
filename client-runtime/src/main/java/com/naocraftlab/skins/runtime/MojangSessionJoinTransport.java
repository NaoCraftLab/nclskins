package com.naocraftlab.skins.runtime;

import com.google.gson.JsonObject;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

public final class MojangSessionJoinTransport {
    private static final URI JOIN_ENDPOINT = URI.create("https://sessionserver.mojang.com/session/minecraft/join");
    public static OptiFineAccountLink.JoinTransport create() {
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER).build();
        return (profileId, accessToken, serverId) -> {
            JsonObject body = new JsonObject();
            body.addProperty("accessToken", accessToken);
            body.addProperty("selectedProfile", profileId.toString().replace("-", ""));
            body.addProperty("serverId", serverId);
            HttpRequest request = HttpRequest.newBuilder(JOIN_ENDPOINT)
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                    .build();
            HttpResponse<Void> response = client.send(request, HttpResponse.BodyHandlers.discarding());
            if (response.statusCode() != 204) throw new IOException("join rejected");
        };
    }

    private MojangSessionJoinTransport() {}
}

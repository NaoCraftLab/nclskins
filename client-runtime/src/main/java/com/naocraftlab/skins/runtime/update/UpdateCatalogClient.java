package com.naocraftlab.skins.runtime.update;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

public final class UpdateCatalogClient {
    static final URI CATALOG_URI = URI.create(
            "https://naocraftlab.github.io/nclskins/updates/v1/catalog.json");
    static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);
    static final int MAX_BODY_BYTES = 512 * 1024;

    private final UpdateHttpBoundary http;
    private final UpdateCatalogParser parser;
    private final UpdateSelector selector;

    UpdateCatalogClient(
            UpdateHttpBoundary http,
            UpdateCatalogParser parser,
            UpdateSelector selector) {
        this.http = Objects.requireNonNull(http, "http");
        this.parser = Objects.requireNonNull(parser, "parser");
        this.selector = Objects.requireNonNull(selector, "selector");
    }

    public static UpdateCatalogClient create() {
        return new UpdateCatalogClient(
                new JdkUpdateHttpBoundary(), new UpdateCatalogParser(), new UpdateSelector());
    }

    public Optional<UpdateCandidate> check(
            String targetId,
            String currentVersion,
            UpdateChannel allowedChannel) {
        Objects.requireNonNull(targetId, "targetId");
        Objects.requireNonNull(currentVersion, "currentVersion");
        Objects.requireNonNull(allowedChannel, "allowedChannel");
        try (UpdateHttpResponse response = http.get(CATALOG_URI, REQUEST_TIMEOUT)) {
            if (response.status() != 200
                    || response.contentLength().isPresent()
                    && response.contentLength().getAsLong() > MAX_BODY_BYTES) {
                return Optional.empty();
            }
            byte[] body = readBounded(response.body());
            String document = decodeUtf8(body);
            return selector.select(parser.parse(document), targetId, currentVersion, allowedChannel);
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } catch (IOException | IllegalArgumentException failure) {
            return Optional.empty();
        }
    }

    private static byte[] readBounded(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int remaining = MAX_BODY_BYTES + 1;
        while (remaining > 0) {
            int count = input.read(buffer, 0, Math.min(buffer.length, remaining));
            if (count < 0) {
                break;
            }
            if (count == 0) {
                int value = input.read();
                if (value < 0) {
                    break;
                }
                output.write(value);
                remaining--;
                continue;
            }
            output.write(buffer, 0, count);
            remaining -= count;
        }
        if (output.size() > MAX_BODY_BYTES) {
            throw new IOException("Update catalog exceeds body limit");
        }
        return output.toByteArray();
    }

    private static String decodeUtf8(byte[] bytes) throws CharacterCodingException {
        return StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString();
    }
}

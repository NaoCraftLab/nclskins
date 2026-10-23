package com.naocraftlab.skins.runtime;

import com.naocraftlab.skins.core.png.PngValidationException;
import com.naocraftlab.skins.core.png.CapeAssetDecoder;
import com.naocraftlab.skins.core.png.PreparedCapeAsset;
import com.naocraftlab.skins.core.api.PublicHttpsImageFetcher;

import java.io.IOException;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

public final class OptifineCapeReader implements CapeObservationReader {
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);
    private final Transport transport;
    private final CapeAssetDecoder decoder;
    private final Path scratch;
    private final ProviderCooldown cooldown;
    private final AtomicLong cooldownEpoch = new AtomicLong();

    public OptifineCapeReader(Path scratch) {
        this(new HttpTransport(), new CapeAssetDecoder(), scratch, Clock.systemUTC());
    }

    OptifineCapeReader(Transport transport, Path scratch, Clock clock) {
        this(transport, new CapeAssetDecoder(), scratch, clock);
    }

    OptifineCapeReader(Transport transport, CapeAssetDecoder decoder,
            Path scratch, Clock clock) {
        this.transport = Objects.requireNonNull(transport, "transport");
        this.decoder = Objects.requireNonNull(decoder, "decoder");
        this.scratch = Objects.requireNonNull(scratch, "scratch");
        this.cooldown = new ProviderCooldown(clock);
    }

    public Optional<Duration> cooldownRemaining() {
        return cooldown.remaining();
    }

    public long accountEpoch() {
        return cooldownEpoch.get();
    }

    public synchronized void accountChanged() {
        cooldownEpoch.incrementAndGet();
    }

    public Outcome read(String canonicalName) {
        return read(canonicalName, accountEpoch());
    }

    Outcome read(String canonicalName, long epoch) {
        if (canonicalName == null || !canonicalName.matches("[A-Za-z0-9_]{1,64}")) {
            return Outcome.failure(Failure.INVALID_NAME);
        }
        if (cooldownEpoch.get() != epoch || cooldown.remaining().isPresent()) {
            return Outcome.failure(Failure.RATE_LIMITED);
        }
        Path encoded = null;
        Path backing = null;
        boolean retainBacking = false;
        try {
            Files.createDirectories(scratch);
            encoded = Files.createTempFile(scratch, "optifine-cape-", ".encoded");
            Files.delete(encoded);
            URI uri = URI.create("https://optifine.net/capes/" + canonicalName + ".png");
            FileResponse response = transport.getToFile(uri, REQUEST_TIMEOUT,
                    CapeAssetDecoder.MAX_ENCODED_BYTES, encoded);
            if (cooldownEpoch.get() != epoch) return Outcome.failure(Failure.RATE_LIMITED);
            if (cooldownEpoch.get() == epoch) cooldown.observe(response.status(), response.headers());
            if (response.status() == 429) return Outcome.failure(Failure.RATE_LIMITED);
            if (response.status() == 404) return Outcome.absent();
            if (response.status() < 200 || response.status() >= 300) return Outcome.failure(Failure.HTTP);
            if (!Files.exists(encoded)) return Outcome.failure(Failure.INVALID_PNG);
            if (response.bytes() > CapeAssetDecoder.MAX_ENCODED_BYTES
                    || Files.size(encoded) > CapeAssetDecoder.MAX_ENCODED_BYTES) {
                return Outcome.failure(Failure.OVERSIZED);
            }
            backing = Files.createTempFile(scratch, "optifine-cape-", ".frames");
            Files.delete(backing);
            PreparedCapeAsset asset = CapePreparationQueue.decode(decoder::prepare, encoded, backing);
            if (cooldownEpoch.get() != epoch) return Outcome.failure(Failure.RATE_LIMITED);
            retainBacking = true;
            return Outcome.present(asset);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return Outcome.failure(Failure.INTERRUPTED);
        } catch (OversizedResponseException | PublicHttpsImageFetcher.OversizedImageException oversized) {
            return Outcome.failure(Failure.OVERSIZED);
        } catch (PngValidationException malformed) {
            return Outcome.failure(Failure.INVALID_PNG);
        } catch (IOException failed) {
            return Outcome.failure(Failure.NETWORK);
        } finally {
            if (encoded != null) try { Files.deleteIfExists(encoded); } catch (IOException ignored) {}
            if (!retainBacking && backing != null) try { Files.deleteIfExists(backing); } catch (IOException ignored) {}
        }
    }

    public CapeObservationReader.Result observe(UUID account, String canonicalName, long epoch) {
        var result = read(canonicalName, epoch);
        return new CapeObservationReader.Result(CapeObservationReader.Kind.valueOf(result.kind().name()),
                null, result.asset(), result.failure() == Failure.RATE_LIMITED);
    }

    @Override public void discard(PreparedCapeAsset asset) throws IOException {
        Files.deleteIfExists(asset.backing());
    }

    public enum Failure {
        INVALID_NAME, HTTP, RATE_LIMITED, OVERSIZED, INVALID_PNG, NETWORK, INTERRUPTED
    }

    public record Outcome(Kind kind, PreparedCapeAsset asset, Failure failure) {
        public Outcome {
            Objects.requireNonNull(kind, "kind");
            if (kind == Kind.PRESENT && asset == null
                    || kind != Kind.PRESENT && asset != null
                    || kind == Kind.FAILURE && failure == null || kind != Kind.FAILURE && failure != null) {
                throw new IllegalArgumentException("Invalid OptiFine outcome");
            }
        }

        public static Outcome present(PreparedCapeAsset asset) {
            return new Outcome(Kind.PRESENT, Objects.requireNonNull(asset, "asset"), null);
        }

        public static Outcome absent() {
            return new Outcome(Kind.ABSENT, null, null);
        }

        public static Outcome failure(Failure failure) {
            return new Outcome(Kind.FAILURE, null, failure);
        }
    }

    public enum Kind {
        PRESENT, ABSENT, FAILURE
    }

    interface Transport {
        FileResponse getToFile(URI uri, Duration timeout, long maxBytes, Path destination)
                throws IOException, InterruptedException;

        default Response getSkinMcCapeToFile(UUID profileId, URI canonical, URI wire,
                long freshness, Duration timeout, long maxBytes, Path destination)
                throws IOException, InterruptedException {
            SkinMcFreshnessQuery.requireExact(profileId, canonical, wire, freshness);
            FileResponse response = getToFile(wire, timeout, maxBytes, destination);
            if (response.status() == 200 && Files.exists(destination)) {
                if (Files.size(destination) > maxBytes) throw new OversizedResponseException();
                try (var input = Files.newInputStream(destination)) {
                    byte[] prefix = input.readNBytes(8);
                    if (imageSignature(prefix)) {
                        return new Response(response.status(), new byte[0], response.headers());
                    }
                }
                if (Files.size(destination) > SkinMcCapeReader.MAX_JSON_BYTES) {
                    throw new OversizedResponseException();
                }
                byte[] json = Files.readAllBytes(destination);
                Files.delete(destination);
                return new Response(response.status(), json, response.headers());
            }
            return new Response(response.status(), new byte[0], response.headers());
        }
    }

    record FileResponse(int status, long bytes, Map<String, List<String>> headers) {}

    record Response(int status, byte[] bytes, Map<String, List<String>> headers) {
        Response {
            Objects.requireNonNull(bytes, "bytes");
            headers = Map.copyOf(Objects.requireNonNull(headers, "headers"));
        }

        Response(int status, byte[] bytes) {
            this(status, bytes, Map.of());
        }
    }

    static final class HttpTransport implements Transport {
        private final PublicHttpsImageFetcher fileFetcher = new PublicHttpsImageFetcher(CONNECT_TIMEOUT);
        private final HttpClient client = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();

        @Override
        public Response getSkinMcCapeToFile(UUID profileId, URI canonical, URI wire,
                long freshness, Duration timeout, long maxBytes, Path destination)
                throws IOException, InterruptedException {
            SkinMcFreshnessQuery.requireExact(profileId, canonical, wire, freshness);
            HttpRequest request = HttpRequest.newBuilder(wire).GET().timeout(timeout).build();
            CompletableFuture<HttpResponse<byte[]>> response = client.sendAsync(request,
                    info -> info.statusCode() == 200
                            ? skinMcFileBody(maxBytes, destination) : new IgnoredBody());
            try {
                HttpResponse<byte[]> completed = response.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
                return new Response(completed.statusCode(), completed.body(), completed.headers().map());
            } catch (TimeoutException timeoutFailure) {
                response.cancel(true);
                throw new IOException("SkinMC request timed out", timeoutFailure);
            } catch (InterruptedException interrupted) {
                response.cancel(true);
                throw interrupted;
            } catch (ExecutionException failed) {
                if (failed.getCause() instanceof OversizedResponseException oversized) throw oversized;
                throw new IOException("SkinMC request failed", failed.getCause());
            }
        }

        @Override
        public FileResponse getToFile(URI uri, Duration timeout, long maxBytes, Path destination)
                throws IOException {
            PublicHttpsImageFetcher.FileResponse response = fileFetcher.getToFile(
                    uri, timeout, maxBytes, destination);
            return new FileResponse(response.status(), response.bytes(), response.headers());
        }

        static HttpResponse.BodySubscriber<byte[]> skinMcFileBody(long maxBytes, Path destination) {
            return new StreamingSkinMcBody(maxBytes, destination);
        }
    }

    private static final class StreamingSkinMcBody implements HttpResponse.BodySubscriber<byte[]> {
        private static final int CHUNK_BYTES = 64 * 1024;
        private final long maxBytes;
        private final Path destination;
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private final ByteArrayOutputStream prefix = new ByteArrayOutputStream(8);
        private final ByteArrayOutputStream json = new ByteArrayOutputStream();
        private Flow.Subscription subscription;
        private OutputStream image;
        private boolean classified;
        private long count;

        private StreamingSkinMcBody(long maxBytes, Path destination) {
            this.maxBytes = maxBytes;
            this.destination = destination;
        }

        @Override public CompletionStage<byte[]> getBody() { return result; }

        @Override public void onSubscribe(Flow.Subscription next) {
            subscription = next;
            next.request(1);
        }

        @Override public void onNext(List<ByteBuffer> items) {
            try {
                for (ByteBuffer item : items) {
                    while (item.hasRemaining()) {
                        if (!classified) {
                            int needed = Math.min(8 - prefix.size(), item.remaining());
                            byte[] bytes = new byte[needed];
                            item.get(bytes);
                            prefix.writeBytes(bytes);
                            if (prefix.size() == 8) classify();
                            continue;
                        }
                        int length = Math.min(CHUNK_BYTES, item.remaining());
                        long limit = image == null ? SkinMcCapeReader.MAX_JSON_BYTES : maxBytes;
                        if (length > limit - count) throw new OversizedResponseException();
                        byte[] bytes = new byte[length];
                        item.get(bytes);
                        if (image == null) json.writeBytes(bytes);
                        else image.write(bytes);
                        count += length;
                    }
                }
                subscription.request(1);
            } catch (IOException failure) {
                subscription.cancel();
                closeImage();
                result.completeExceptionally(failure);
            }
        }

        private void classify() throws IOException {
            byte[] bytes = prefix.toByteArray();
            classified = true;
            if (imageSignature(bytes)) {
                if (bytes.length > maxBytes) throw new OversizedResponseException();
                image = Files.newOutputStream(destination, StandardOpenOption.CREATE_NEW,
                        StandardOpenOption.WRITE);
                image.write(bytes);
            } else {
                json.writeBytes(bytes);
            }
            count = bytes.length;
        }

        @Override public void onError(Throwable failure) {
            closeImage();
            result.completeExceptionally(failure);
        }

        @Override public void onComplete() {
            closeImage();
            result.complete(classified ? json.toByteArray() : prefix.toByteArray());
        }

        private void closeImage() {
            if (image != null) {
                try { image.close(); } catch (IOException ignored) {}
            }
        }
    }

    private static final class IgnoredBody implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> result = CompletableFuture.completedFuture(new byte[0]);

        @Override
        public CompletionStage<byte[]> getBody() {
            return result;
        }

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            subscription.cancel();
        }

        @Override
        public void onNext(List<ByteBuffer> ignored) {}

        @Override
        public void onError(Throwable ignored) {}

        @Override
        public void onComplete() {}
    }

    private static boolean imageSignature(byte[] prefix) {
        return prefix.length >= 8
                && (prefix[0] == (byte) 0x89 && prefix[1] == 'P' && prefix[2] == 'N'
                        && prefix[3] == 'G' && prefix[4] == 13 && prefix[5] == 10
                        && prefix[6] == 26 && prefix[7] == 10
                    || prefix[0] == (byte) 0xff && prefix[1] == (byte) 0xd8
                        && prefix[2] == (byte) 0xff
                    || prefix[0] == 'G' && prefix[1] == 'I' && prefix[2] == 'F'
                        && prefix[3] == '8' && (prefix[4] == '7' || prefix[4] == '9')
                        && prefix[5] == 'a');
    }

    static final class OversizedResponseException extends IOException {
        private static final long serialVersionUID = 1L;
    }
}

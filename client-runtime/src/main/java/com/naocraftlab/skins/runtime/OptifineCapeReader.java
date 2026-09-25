package com.naocraftlab.skins.runtime;

import com.naocraftlab.skins.core.png.PngValidationException;
import com.naocraftlab.skins.core.png.PngValidator;

import java.io.IOException;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
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

public final class OptifineCapeReader {
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);
    private final Transport transport;
    private final PngValidator validator;
    private final ProviderCooldown cooldown;
    private final AtomicLong cooldownEpoch = new AtomicLong();

    public OptifineCapeReader() {
        this(new HttpTransport(), new PngValidator());
    }

    OptifineCapeReader(Transport transport, PngValidator validator) {
        this(transport, validator, Clock.systemUTC());
    }

    OptifineCapeReader(Transport transport, PngValidator validator, Clock clock) {
        this.transport = Objects.requireNonNull(transport, "transport");
        this.validator = Objects.requireNonNull(validator, "validator");
        this.cooldown = new ProviderCooldown(clock);
    }

    public Optional<Duration> cooldownRemaining() {
        return cooldown.remaining();
    }

    long accountEpoch() {
        return cooldownEpoch.get();
    }

    synchronized void accountChanged() {
        cooldownEpoch.incrementAndGet();
    }

    private synchronized void observeCooldown(long epoch, Response response) {
        if (cooldownEpoch.get() == epoch) cooldown.observe(response.status(), response.headers());
    }

    public Outcome read(String canonicalName) {
        return read(canonicalName, accountEpoch());
    }

    Outcome read(String canonicalName, long epoch) {
        if (canonicalName == null || !canonicalName.matches("[A-Za-z0-9_]{1,64}")) {
            return Outcome.failure(Failure.INVALID_NAME);
        }
        if (cooldownEpoch.get() != epoch) return Outcome.failure(Failure.RATE_LIMITED);
        if (cooldown.remaining().isPresent()) return Outcome.failure(Failure.RATE_LIMITED);
        URI uri = URI.create("https://optifine.net/capes/" + canonicalName + ".png");
        try {
            Response response = transport.get(uri, REQUEST_TIMEOUT, validator.maxBytes());
            observeCooldown(epoch, response);
            if (response.status() == 429) return Outcome.failure(Failure.RATE_LIMITED);
            if (response.status() == 404) {
                return Outcome.absent();
            }
            if (response.status() < 200 || response.status() >= 300) {
                return Outcome.failure(Failure.HTTP);
            }
            if (response.bytes().length > validator.maxBytes()) {
                return Outcome.failure(Failure.OVERSIZED);
            }
            return Outcome.present(validator.projectImportedCape(response.bytes()));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return Outcome.failure(Failure.INTERRUPTED);
        } catch (OversizedResponseException oversized) {
            return Outcome.failure(Failure.OVERSIZED);
        } catch (PngValidationException malformed) {
            return Outcome.failure(Failure.INVALID_PNG);
        } catch (IOException failed) {
            return Outcome.failure(Failure.NETWORK);
        }
    }

    public enum Failure {
        INVALID_NAME, HTTP, RATE_LIMITED, OVERSIZED, INVALID_PNG, NETWORK, INTERRUPTED
    }

    public record Outcome(Kind kind, PngValidator.CapePng cape, Failure failure) {
        public Outcome {
            Objects.requireNonNull(kind, "kind");
            if (kind == Kind.PRESENT && cape == null || kind != Kind.PRESENT && cape != null
                    || kind == Kind.FAILURE && failure == null || kind != Kind.FAILURE && failure != null) {
                throw new IllegalArgumentException("Invalid OptiFine outcome");
            }
        }

        public static Outcome present(PngValidator.CapePng cape) {
            return new Outcome(Kind.PRESENT, Objects.requireNonNull(cape, "cape"), null);
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
        Response get(URI uri, Duration timeout, int maxBytes) throws IOException, InterruptedException;

        default Response getSkinMcCape(UUID profileId, URI canonical, URI wire, long freshness,
                Duration timeout, int maxBytes) throws IOException, InterruptedException {
            SkinMcFreshnessQuery.requireExact(profileId, canonical, wire, freshness);
            return get(wire, timeout, maxBytes);
        }
    }

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
        private final HttpClient client = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();

        @Override
        public Response getSkinMcCape(UUID profileId, URI canonical, URI wire, long freshness,
                Duration timeout, int maxBytes) throws IOException, InterruptedException {
            SkinMcFreshnessQuery.requireExact(profileId, canonical, wire, freshness);
            return request(wire, timeout, maxBytes, true);
        }

        @Override
        public Response get(URI uri, Duration timeout, int maxBytes) throws IOException, InterruptedException {
            return request(uri, timeout, maxBytes, false);
        }

        static HttpResponse.BodySubscriber<byte[]> skinMcBody(int maxBytes) {
            return new BoundedBody(maxBytes, true);
        }

        private Response request(URI uri, Duration timeout, int maxBytes, boolean skinMc)
                throws IOException, InterruptedException {
            HttpRequest request = HttpRequest.newBuilder(uri).GET().timeout(timeout).build();
            CompletableFuture<HttpResponse<byte[]>> response = client.sendAsync(request,
                    info -> info.statusCode() >= 200 && info.statusCode() < 300
                            ? skinMc ? skinMcBody(maxBytes) : new BoundedBody(maxBytes, false)
                            : new IgnoredBody());
            try {
                HttpResponse<byte[]> completed = response.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
                return new Response(completed.statusCode(), completed.body(), completed.headers().map());
            } catch (TimeoutException timeoutFailure) {
                response.cancel(true);
                throw new IOException("OptiFine request timed out", timeoutFailure);
            } catch (InterruptedException interrupted) {
                response.cancel(true);
                throw interrupted;
            } catch (ExecutionException failed) {
                if (failed.getCause() instanceof OversizedResponseException oversized) throw oversized;
                throw new IOException("OptiFine request failed", failed.getCause());
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

    private static final class BoundedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final int maxBytes;
        private final boolean skinMc;
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private Flow.Subscription subscription;

        private BoundedBody(int maxBytes, boolean skinMc) {
            this.maxBytes = maxBytes;
            this.skinMc = skinMc;
        }

        @Override
        public CompletionStage<byte[]> getBody() {
            return result;
        }

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
            subscription.request(1);
        }

        @Override
        public void onNext(List<ByteBuffer> items) {
            for (ByteBuffer item : items) {
                if (skinMc && bytes.size() < 8) {
                    int prefix = Math.min(8 - bytes.size(), item.remaining());
                    byte[] initial = new byte[prefix];
                    item.get(initial);
                    bytes.writeBytes(initial);
                }
                int limit = skinMc && bytes.size() >= 8 && !imageSignature(bytes.toByteArray())
                        ? SkinMcCapeReader.MAX_JSON_BYTES : maxBytes;
                if (item.remaining() > limit - bytes.size()) {
                    subscription.cancel();
                    result.completeExceptionally(new OversizedResponseException());
                    return;
                }
                byte[] chunk = new byte[item.remaining()];
                item.get(chunk);
                bytes.writeBytes(chunk);
            }
            subscription.request(1);
        }

        private static boolean imageSignature(byte[] prefix) {
            return prefix.length >= 8
                    && (prefix[0] == (byte) 0x89 && prefix[1] == 'P' && prefix[2] == 'N'
                            && prefix[3] == 'G' && prefix[4] == 13 && prefix[5] == 10
                            && prefix[6] == 26 && prefix[7] == 10
                        || prefix[0] == (byte) 0xff && prefix[1] == (byte) 0xd8
                            && prefix[2] == (byte) 0xff);
        }

        @Override
        public void onError(Throwable failure) {
            result.completeExceptionally(failure);
        }

        @Override
        public void onComplete() {
            result.complete(bytes.toByteArray());
        }
    }

    static final class OversizedResponseException extends IOException {
        private static final long serialVersionUID = 1L;
    }
}

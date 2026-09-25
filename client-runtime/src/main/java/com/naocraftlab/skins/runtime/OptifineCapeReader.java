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
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public final class OptifineCapeReader {
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);
    private final Transport transport;
    private final PngValidator validator;

    public OptifineCapeReader() {
        this(new HttpTransport(), new PngValidator());
    }

    OptifineCapeReader(Transport transport, PngValidator validator) {
        this.transport = Objects.requireNonNull(transport, "transport");
        this.validator = Objects.requireNonNull(validator, "validator");
    }

    public Outcome read(String canonicalName) {
        if (canonicalName == null || !canonicalName.matches("[A-Za-z0-9_]{1,64}")) {
            return Outcome.failure(Failure.INVALID_NAME);
        }
        URI uri = URI.create("https://optifine.net/capes/" + canonicalName + ".png");
        try {
            Response response = transport.get(uri, REQUEST_TIMEOUT, validator.maxBytes());
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
        INVALID_NAME, HTTP, OVERSIZED, INVALID_PNG, NETWORK, INTERRUPTED
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
    }

    record Response(int status, byte[] bytes) {
        Response {
            Objects.requireNonNull(bytes, "bytes");
        }
    }

    static final class HttpTransport implements Transport {
        private final HttpClient client = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();

        @Override
        public Response get(URI uri, Duration timeout, int maxBytes) throws IOException, InterruptedException {
            HttpRequest request = HttpRequest.newBuilder(uri).GET().timeout(timeout).build();
            CompletableFuture<HttpResponse<byte[]>> response = client.sendAsync(request,
                    info -> info.statusCode() >= 200 && info.statusCode() < 300
                            ? new BoundedBody(maxBytes) : new IgnoredBody());
            try {
                HttpResponse<byte[]> completed = response.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
                return new Response(completed.statusCode(), completed.body());
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
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private Flow.Subscription subscription;

        private BoundedBody(int maxBytes) {
            this.maxBytes = maxBytes;
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
                if (item.remaining() > maxBytes - bytes.size()) {
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

        @Override
        public void onError(Throwable failure) {
            result.completeExceptionally(failure);
        }

        @Override
        public void onComplete() {
            result.complete(bytes.toByteArray());
        }
    }

    private static final class OversizedResponseException extends IOException {
        private static final long serialVersionUID = 1L;
    }
}

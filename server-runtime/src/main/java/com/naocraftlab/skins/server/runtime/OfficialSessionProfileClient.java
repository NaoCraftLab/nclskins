package com.naocraftlab.skins.server.runtime;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.naocraftlab.skins.server.ServerPlayerIdentity;
import com.naocraftlab.skins.server.SignedTexturesProperty;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;


public final class OfficialSessionProfileClient {
    private static final URI PRODUCTION_ENDPOINT = URI.create(
            "https://sessionserver.mojang.com/session/minecraft/profile/");
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration FALLBACK_THROTTLE = Duration.ofSeconds(60);
    private static final int MAX_RESPONSE_BYTES = 65_536;
    private static final String TEXTURES = "textures";

    private final HttpClient httpClient;
    private final URI endpoint;
    private final Clock clock;
    private final Duration requestTimeout;
    private final int maxResponseBytes;

    public OfficialSessionProfileClient() {
        this(
                HttpClient.newBuilder()
                        .connectTimeout(CONNECT_TIMEOUT)
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .build(),
                PRODUCTION_ENDPOINT,
                Clock.systemUTC(),
                REQUEST_TIMEOUT,
                MAX_RESPONSE_BYTES);
    }

    OfficialSessionProfileClient(
            HttpClient httpClient,
            URI endpoint,
            Clock clock,
            Duration requestTimeout,
            int maxResponseBytes) {
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
        this.endpoint = requireEndpoint(endpoint);
        this.clock = Objects.requireNonNull(clock, "clock");
        this.requestTimeout = requirePositive(requestTimeout, "requestTimeout");
        if (maxResponseBytes <= 0 || maxResponseBytes == Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Response bound must be positive");
        }
        this.maxResponseBytes = maxResponseBytes;
    }


    public CompletionStage<Result> fetchAsync(ServerPlayerIdentity expectedIdentity) {
        Objects.requireNonNull(expectedIdentity, "expectedIdentity");
        HttpRequest request = HttpRequest.newBuilder(profileUri(expectedIdentity))
                .timeout(requestTimeout)
                .header("Accept", "application/json")
                .GET()
                .build();
        final CompletableFuture<HttpResponse<byte[]>> exchange;
        try {
            exchange = httpClient.sendAsync(
                    request,
                    ignored -> new BoundedBodySubscriber(maxResponseBytes));
        } catch (RuntimeException failure) {
            return CompletableFuture.completedFuture(Result.transientFailure());
        }
        CompletableFuture<Result> result = new CompletableFuture<>();
        exchange.whenComplete((response, failure) -> {
            if (failure != null) {
                result.complete(causedBy(failure, BodyTooLargeException.class)
                        ? Result.rejected()
                        : Result.transientFailure());
                return;
            }
            result.complete(mapResponse(expectedIdentity, response));
        });
        result.whenComplete((ignored, ignoredFailure) -> {
            if (result.isCancelled()) {
                exchange.cancel(true);
            }
        });
        return result;
    }


    public Result fetch(ServerPlayerIdentity expectedIdentity) {
        CompletionStage<Result> stage = fetchAsync(expectedIdentity);
        try {
            return stage.toCompletableFuture().get(
                    saturatedNanos(requestTimeout), TimeUnit.NANOSECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return Result.transientFailure();
        } catch (TimeoutException timeout) {
            stage.toCompletableFuture().cancel(true);
            return Result.transientFailure();
        } catch (ExecutionException failure) {
            return causedBy(failure, BodyTooLargeException.class)
                    ? Result.rejected()
                    : Result.transientFailure();
        }
    }

    private Result mapResponse(
            ServerPlayerIdentity expectedIdentity,
            HttpResponse<byte[]> response) {
        int status = response.statusCode();
        if (status == 200) {
            return parseProfile(expectedIdentity, response.body());
        }
        if (status == 429) {
            return Result.throttled(parseRetryAfter(response));
        }
        if (status == 408 || status >= 500 && status <= 599) {
            return Result.transientFailure();
        }
        return Result.rejected();
    }

    private Result parseProfile(ServerPlayerIdentity expectedIdentity, byte[] body) {
        if (body.length > maxResponseBytes) {
            return Result.rejected();
        }
        try {
            String json = decodeUtf8(body);
            JsonElement parsed = JsonParser.parseString(json);
            if (!parsed.isJsonObject()) {
                return Result.rejected();
            }
            JsonObject object = parsed.getAsJsonObject();
            String expectedId = undashed(expectedIdentity);
            String profileId = requiredString(object, "id");
            String profileName = requiredString(object, "name");
            if (!expectedId.equals(profileId.toLowerCase(Locale.ROOT))
                    || !expectedIdentity.profileName().equals(profileName)) {
                return Result.rejected();
            }

            Optional<SignedTexturesProperty> textures = parseTextures(object);
            if (textures == null) {
                return Result.rejected();
            }
            return Result.resolved(new FetchedProfile(expectedIdentity, textures));
        } catch (RuntimeException | CharacterCodingException invalidProfile) {
            return Result.rejected();
        }
    }


    private static Optional<SignedTexturesProperty> parseTextures(JsonObject object) {
        JsonElement propertiesElement = object.get("properties");
        if (propertiesElement == null) {
            return Optional.empty();
        }
        if (!propertiesElement.isJsonArray()) {
            return null;
        }
        JsonArray properties = propertiesElement.getAsJsonArray();
        SignedTexturesProperty textures = null;
        for (JsonElement element : properties) {
            if (!element.isJsonObject()) {
                return null;
            }
            JsonObject property = element.getAsJsonObject();
            String name = requiredString(property, "name");
            if (!TEXTURES.equals(name)) {
                continue;
            }
            if (textures != null) {
                return null;
            }
            textures = new SignedTexturesProperty(
                    requiredString(property, "value"),
                    requiredString(property, "signature"));
        }
        return Optional.ofNullable(textures);
    }

    private Duration parseRetryAfter(HttpResponse<?> response) {
        Optional<String> header = response.headers().firstValue("Retry-After");
        if (header.isEmpty()) {
            return FALLBACK_THROTTLE;
        }
        String value = header.orElseThrow().trim();
        try {
            long seconds = Long.parseLong(value);
            if (seconds < 0L) {
                return FALLBACK_THROTTLE;
            }
            return Duration.ofSeconds(seconds);
        } catch (NumberFormatException | ArithmeticException notDeltaSeconds) {
            try {
                Instant retryAt = ZonedDateTime.parse(
                        value,
                        DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
                Duration delay = Duration.between(clock.instant(), retryAt);
                return delay.isNegative() ? Duration.ZERO : delay;
            } catch (DateTimeParseException | ArithmeticException invalidDate) {
                return FALLBACK_THROTTLE;
            }
        }
    }

    private URI profileUri(ServerPlayerIdentity identity) {
        return endpoint.resolve(undashed(identity) + "?unsigned=false");
    }

    private static String undashed(ServerPlayerIdentity identity) {
        return identity.profileId().toString().replace("-", "").toLowerCase(Locale.ROOT);
    }

    private static String requiredString(JsonObject object, String member) {
        JsonElement value = object.get(member);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            throw new IllegalArgumentException("Required profile member is absent or invalid");
        }
        String result = value.getAsString();
        if (result.isBlank()) {
            throw new IllegalArgumentException("Required profile member is blank");
        }
        return result;
    }

    private static String decodeUtf8(byte[] body) throws CharacterCodingException {
        return StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(body))
                .toString();
    }

    private static URI requireEndpoint(URI endpoint) {
        Objects.requireNonNull(endpoint, "endpoint");
        if (!endpoint.isAbsolute() || !endpoint.toString().endsWith("/")) {
            throw new IllegalArgumentException("Profile endpoint must be an absolute directory URI");
        }
        return endpoint;
    }

    private static Duration requirePositive(Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException("Timeout must be positive");
        }
        return value;
    }

    private static boolean causedBy(Throwable failure, Class<? extends Throwable> type) {
        Throwable current = failure;
        while (current != null) {
            if (type.isInstance(current)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static long saturatedNanos(Duration duration) {
        try {
            return duration.toNanos();
        } catch (ArithmeticException overflow) {
            return Long.MAX_VALUE;
        }
    }


    public static final class FetchedProfile {
        private final ServerPlayerIdentity identity;
        private final Optional<SignedTexturesProperty> textures;

        private FetchedProfile(
                ServerPlayerIdentity identity,
                Optional<SignedTexturesProperty> textures) {
            this.identity = Objects.requireNonNull(identity, "identity");
            this.textures = Objects.requireNonNull(textures, "textures");
        }

        public ServerPlayerIdentity identity() {
            return identity;
        }

        public Optional<SignedTexturesProperty> textures() {
            return textures;
        }

        @Override
        public String toString() {
            return "FetchedProfile[redacted]";
        }
    }


    public static final class Result {
        private final Status status;
        private final FetchedProfile profile;
        private final Duration retryAfter;

        private Result(Status status, FetchedProfile profile, Duration retryAfter) {
            this.status = Objects.requireNonNull(status, "status");
            this.profile = profile;
            this.retryAfter = retryAfter;
        }

        private static Result resolved(FetchedProfile profile) {
            return new Result(Status.RESOLVED, Objects.requireNonNull(profile, "profile"), null);
        }

        private static Result transientFailure() {
            return new Result(Status.TRANSIENT_FAILURE, null, null);
        }

        private static Result throttled(Duration retryAfter) {
            return new Result(
                    Status.THROTTLED,
                    null,
                    Objects.requireNonNull(retryAfter, "retryAfter"));
        }

        private static Result rejected() {
            return new Result(Status.REJECTED, null, null);
        }

        public Status status() {
            return status;
        }

        public Optional<FetchedProfile> profile() {
            return Optional.ofNullable(profile);
        }

        public Optional<Duration> retryAfter() {
            return Optional.ofNullable(retryAfter);
        }

        @Override
        public String toString() {
            return "OfficialSessionProfileResult[status=" + status + ']';
        }

        public enum Status {
            RESOLVED,
            TRANSIENT_FAILURE,
            THROTTLED,
            REJECTED
        }
    }


    private static final class BoundedBodySubscriber
            implements HttpResponse.BodySubscriber<byte[]> {
        private final int limit;
        private final ByteArrayOutputStream output;
        private final CompletableFuture<byte[]> body = new CompletableFuture<>();
        private Flow.Subscription subscription;
        private int size;

        private BoundedBodySubscriber(int limit) {
            this.limit = limit;
            output = new ByteArrayOutputStream(Math.min(limit, 8_192));
        }

        @Override
        public CompletionStage<byte[]> getBody() {
            return body;
        }

        @Override
        public void onSubscribe(Flow.Subscription candidate) {
            Objects.requireNonNull(candidate, "subscription");
            if (subscription != null) {
                candidate.cancel();
                return;
            }
            subscription = candidate;
            candidate.request(1L);
        }

        @Override
        public void onNext(List<ByteBuffer> buffers) {
            if (body.isDone()) {
                return;
            }
            try {
                for (ByteBuffer buffer : buffers) {
                    int remaining = buffer.remaining();
                    if (remaining > limit - size) {
                        throw new BodyTooLargeException();
                    }
                    byte[] chunk = new byte[remaining];
                    buffer.get(chunk);
                    output.writeBytes(chunk);
                    size += remaining;
                }
                subscription.request(1L);
            } catch (RuntimeException failure) {
                subscription.cancel();
                body.completeExceptionally(failure);
            }
        }

        @Override
        public void onError(Throwable failure) {
            body.completeExceptionally(failure);
        }

        @Override
        public void onComplete() {
            body.complete(output.toByteArray());
        }
    }

    private static final class BodyTooLargeException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        private BodyTooLargeException() {
            super("Profile response exceeds size limit");
        }
    }
}

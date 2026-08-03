package com.naocraftlab.skins.core.api;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.naocraftlab.skins.core.model.RemoteAssetState;
import com.naocraftlab.skins.core.model.RemoteCape;
import com.naocraftlab.skins.core.model.RemoteProfile;
import com.naocraftlab.skins.core.model.RemoteSkin;
import com.naocraftlab.skins.core.model.SkinVariant;
import com.naocraftlab.skins.core.png.PngValidationException;
import com.naocraftlab.skins.core.png.PngValidator;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;


public final class MinecraftProfileApi implements ProfileApi {
    private static final String PROFILE_PATH = "/minecraft/profile";
    private static final String SKINS_PATH = "/minecraft/profile/skins";
    private static final String ACTIVE_SKIN_PATH = "/minecraft/profile/skins/active";
    private static final String ACTIVE_CAPE_PATH = "/minecraft/profile/capes/active";
    private static final int MAX_RESPONSE_BYTES = 2 * 1024 * 1024;
    private static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofSeconds(20);
    private static final Duration MAX_AUTOMATIC_WAIT = Duration.ofSeconds(30);
    private static final String UNKNOWN_PROFILE_ACTION_SHAPE = "UNKNOWN_PROFILE_ACTION_SHAPE";

    private final HttpClient httpClient;
    private final URI serviceBase;
    private final GetRetryPolicy retryPolicy;
    private final Duration requestTimeout;
    private final PngValidator pngValidator;
    private final Sleeper sleeper;
    private final RateLimitGate rateLimitGate;

    public MinecraftProfileApi() {
        this(
                HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(10))
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .build(),
                MinecraftServiceUriPolicy.PROFILE_SERVICE,
                GetRetryPolicy.defaults(),
                DEFAULT_REQUEST_TIMEOUT,
                new PngValidator(),
                duration -> Thread.sleep(duration.toMillis()),
                Clock.systemUTC(),
                false);
    }

    MinecraftProfileApi(
            HttpClient httpClient,
            URI serviceBase,
            GetRetryPolicy retryPolicy,
            Duration requestTimeout,
            PngValidator pngValidator,
            Sleeper sleeper,
            Clock clock,
            boolean allowLoopbackTestEndpoint) {
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
        this.serviceBase = normalizeBase(serviceBase, allowLoopbackTestEndpoint);
        this.retryPolicy = Objects.requireNonNull(retryPolicy, "retryPolicy");
        this.requestTimeout = Objects.requireNonNull(requestTimeout, "requestTimeout");
        this.pngValidator = Objects.requireNonNull(pngValidator, "pngValidator");
        this.sleeper = Objects.requireNonNull(sleeper, "sleeper");
        this.rateLimitGate = new RateLimitGate(Objects.requireNonNull(clock, "clock"));
        if (requestTimeout.isZero() || requestTimeout.isNegative()) {
            throw new IllegalArgumentException("requestTimeout must be positive");
        }
    }

    @Override
    public RemoteProfile getProfile(String accessToken) throws ProfileApiException {
        InternalResponse response = executeGet(PROFILE_PATH, accessToken);
        return decodeProfile(response.body());
    }


    public RemoteProfile getProfileOnce(String accessToken, Duration timeout)
            throws ProfileApiException {
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
        rejectDuringRateLimitCooldown();
        HttpRequest request = authenticatedRequest(PROFILE_PATH, accessToken, timeout)
                .header("Content-Type", "application/json")
                .GET()
                .build();
        final InternalResponse response;
        try {
            response = send(request, false);
        } catch (TransportFailure failure) {
            throw failure.apiException();
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw statusFailure(response, false);
        }
        return decodeProfile(response.body());
    }

    @Override
    public void uploadSkin(String accessToken, SkinVariant variant, byte[] pngBytes) throws ProfileApiException {
        Objects.requireNonNull(variant, "variant");
        Objects.requireNonNull(pngBytes, "pngBytes");
        try {
            pngValidator.validate(pngBytes);
        } catch (PngValidationException exception) {
            throw new ProfileApiException(
                    ApiFailureKind.INVALID_RESPONSE,
                    "The selected skin PNG is invalid.",
                    null,
                    null,
                    false);
        }
        String boundary = "nclskins-" + UUID.randomUUID();
        byte[] body = multipartSkin(boundary, variant, pngBytes);
        HttpRequest request = authenticatedRequest(SKINS_PATH, accessToken)
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();
        executeMutation(request);
    }

    @Override
    public void resetSkin(String accessToken) throws ProfileApiException {
        HttpRequest request = authenticatedRequest(ACTIVE_SKIN_PATH, accessToken)
                .DELETE()
                .build();
        executeMutation(request);
    }

    @Override
    public void activateCape(String accessToken, String capeId) throws ProfileApiException {
        Objects.requireNonNull(capeId, "capeId");
        String normalized = capeId.trim();
        if (normalized.isEmpty() || normalized.length() > 256) {
            throw new IllegalArgumentException("capeId is invalid");
        }
        JsonObject json = new JsonObject();
        json.addProperty("capeId", normalized);
        HttpRequest request = authenticatedRequest(ACTIVE_CAPE_PATH, accessToken)
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(json.toString(), StandardCharsets.UTF_8))
                .build();
        executeMutation(request);
    }

    @Override
    public void deactivateCape(String accessToken) throws ProfileApiException {
        HttpRequest request = authenticatedRequest(ACTIVE_CAPE_PATH, accessToken)
                .DELETE()
                .build();
        executeMutation(request);
    }

    private InternalResponse executeGet(String path, String accessToken) throws ProfileApiException {
        HttpRequest request = authenticatedRequest(path, accessToken)
                .header("Content-Type", "application/json")
                .GET()
                .build();
        ProfileApiException lastFailure = null;
        for (int attempt = 1; attempt <= retryPolicy.maxAttempts(); attempt++) {
            try {
                rejectDuringRateLimitCooldown();
                InternalResponse response = send(request, false);
                if (response.statusCode() >= 200 && response.statusCode() < 300) {
                    return response;
                }
                ProfileApiException failure = statusFailure(response, false);
                lastFailure = failure;
                if (response.statusCode() == 429) {


                    throw failure;
                }
                if (!retryableGetStatus(response.statusCode()) || attempt == retryPolicy.maxAttempts()) {
                    throw failure;
                }
                sleepBounded(retryPolicy.exponentialBackoff(attempt));
            } catch (TransportFailure failure) {
                lastFailure = failure.apiException();
                if (attempt == retryPolicy.maxAttempts()) {
                    throw lastFailure;
                }
                sleepBounded(retryPolicy.exponentialBackoff(attempt));
            }
        }
        throw Objects.requireNonNull(lastFailure, "lastFailure");
    }

    private void executeMutation(HttpRequest request) throws ProfileApiException {
        rejectDuringRateLimitCooldown();
        final InternalResponse response;
        try {
            response = send(request, true);
        } catch (TransportFailure failure) {
            throw failure.apiException();
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw statusFailure(response, true);
        }
    }

    private InternalResponse send(HttpRequest request, boolean mutation) throws TransportFailure, ProfileApiException {
        try {
            HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (!sameAuthorityAndScheme(request.uri(), response.uri())) {
                closeQuietly(response.body());
                throw new ProfileApiException(
                        ApiFailureKind.REDIRECT_REJECTED,
                        "A cross-host service response was rejected.",
                        response.statusCode(),
                        null,
                        mutation);
            }
            byte[] body;
            try (InputStream stream = response.body()) {
                body = stream.readNBytes(MAX_RESPONSE_BYTES + 1);
            }
            if (body.length > MAX_RESPONSE_BYTES) {
                throw new ProfileApiException(
                        ApiFailureKind.INVALID_RESPONSE,
                        "Minecraft service response exceeds the size limit.",
                        response.statusCode(),
                        null,
                        mutation);
            }
            return new InternalResponse(response.statusCode(), response.headers(), body);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new TransportFailure(networkFailure(mutation));
        } catch (IOException exception) {
            throw new TransportFailure(networkFailure(mutation));
        }
    }

    private HttpRequest.Builder authenticatedRequest(String path, String accessToken) throws ProfileApiException {
        return authenticatedRequest(path, accessToken, requestTimeout);
    }

    private HttpRequest.Builder authenticatedRequest(
            String path,
            String accessToken,
            Duration timeout) throws ProfileApiException {
        if (accessToken == null || accessToken.isBlank()) {
            throw new ProfileApiException(
                    ApiFailureKind.INVALID_SESSION,
                    "The running Minecraft session has no access token.",
                    null,
                    null,
                    false);
        }
        URI endpoint = serviceBase.resolve(path);
        if (!sameAuthorityAndScheme(serviceBase, endpoint) || !isKnownPath(endpoint.getPath())) {
            throw new IllegalStateException("Attempted to construct a non-allowlisted Minecraft service endpoint");
        }
        return HttpRequest.newBuilder(endpoint)
                .timeout(timeout)
                .header("Accept", "application/json")
                .header("Authorization", "Bearer " + accessToken)
                .header("User-Agent", "NCL-Skin/0.1");
    }

    private RemoteProfile decodeProfile(byte[] body) throws ProfileApiException {
        final JsonElement document;
        try {
            document = JsonParser.parseString(new String(body, StandardCharsets.UTF_8));
        } catch (JsonParseException exception) {
            throw invalidProfileResponse(ResponseSchemaCode.JSON_DOCUMENT);
        }
        if (!document.isJsonObject()) {
            throw invalidProfileResponse(ResponseSchemaCode.PROFILE_ROOT);
        }
        JsonObject root = document.getAsJsonObject();

        final UUID id;
        try {
            id = parseMinecraftUuid(requiredProfileString(root, "id"));
        } catch (JsonParseException | IllegalArgumentException | IllegalStateException exception) {
            throw invalidProfileResponse(ResponseSchemaCode.PROFILE_ID);
        }

        final String name;
        try {
            name = requiredProfileString(root, "name").trim();
            if (name.isEmpty() || name.length() > 64) {
                throw new JsonParseException("Invalid profile name");
            }
        } catch (JsonParseException | IllegalArgumentException | IllegalStateException exception) {
            throw invalidProfileResponse(ResponseSchemaCode.PROFILE_NAME);
        }

        List<RemoteSkin> skins = new ArrayList<>();
        for (JsonElement element : optionalProfileArray(root, "skins")) {
            RemoteSkin skin = decodeSkinEntry(element);
            if (skin != null) {
                skins.add(skin);
            }
        }

        List<RemoteCape> capes = new ArrayList<>();
        for (JsonElement element : optionalProfileArray(root, "capes")) {
            RemoteCape cape = decodeCapeEntry(element);
            if (cape != null) {
                capes.add(cape);
            }
        }

        Set<String> profileActions = decodeProfileActions(root);
        try {
            return new RemoteProfile(id, name, skins, capes, profileActions);
        } catch (IllegalArgumentException | IllegalStateException | NullPointerException exception) {
            throw invalidProfileResponse(ResponseSchemaCode.PROFILE_MODEL);
        }
    }

    private static RemoteSkin decodeSkinEntry(JsonElement element) {
        try {
            if (!element.isJsonObject()) {
                return null;
            }
            JsonObject skin = element.getAsJsonObject();
            return new RemoteSkin(
                    requiredProfileString(skin, "id"),
                    requiredAssetState(skin),
                    trustedTextureUri(requiredProfileString(skin, "url")),
                    SkinVariant.fromApiValue(requiredProfileString(skin, "variant")),
                    optionalProfileString(skin, "alias"));
        } catch (JsonParseException | IllegalArgumentException | IllegalStateException exception) {


            return null;
        }
    }

    private static RemoteCape decodeCapeEntry(JsonElement element) {
        try {
            if (!element.isJsonObject()) {
                return null;
            }
            JsonObject cape = element.getAsJsonObject();
            return new RemoteCape(
                    requiredProfileString(cape, "id"),
                    requiredAssetState(cape),
                    trustedTextureUri(requiredProfileString(cape, "url")),
                    optionalProfileString(cape, "alias"));
        } catch (JsonParseException | IllegalArgumentException | IllegalStateException exception) {
            return null;
        }
    }

    private static RemoteAssetState requiredAssetState(JsonObject asset) {
        String value = requiredProfileString(asset, "state");
        if ("ACTIVE".equalsIgnoreCase(value)) {
            return RemoteAssetState.ACTIVE;
        }
        if ("INACTIVE".equalsIgnoreCase(value)) {
            return RemoteAssetState.INACTIVE;
        }
        throw new JsonParseException("Unknown asset state");
    }

    private static JsonArray optionalProfileArray(JsonObject root, String member) {
        JsonElement value = root.get(member);
        if (value == null || value.isJsonNull() || !value.isJsonArray()) {


            return new JsonArray();
        }
        return value.getAsJsonArray();
    }

    private static Set<String> decodeProfileActions(JsonObject root) throws ProfileApiException {
        Set<String> result = new HashSet<>();
        JsonElement actions = root.get("profileActions");
        if (actions == null || actions.isJsonNull()) {
            return result;
        }
        if (actions.isJsonObject()) {


            if (actions.getAsJsonObject().size() > 0) {
                result.add(UNKNOWN_PROFILE_ACTION_SHAPE);
            }
            return result;
        }
        if (!actions.isJsonArray()) {
            throw invalidProfileResponse(ResponseSchemaCode.PROFILE_ACTIONS);
        }
        for (JsonElement action : actions.getAsJsonArray()) {
            if (!action.isJsonPrimitive() || !action.getAsJsonPrimitive().isString()) {
                throw invalidProfileResponse(ResponseSchemaCode.PROFILE_ACTION);
            }
            result.add(action.getAsString());
        }
        return result;
    }

    private ProfileApiException statusFailure(InternalResponse response, boolean mutation) {
        int status = response.statusCode();
        if (status == 401) {
            return new ProfileApiException(
                    ApiFailureKind.SESSION_EXPIRED,
                    "Minecraft session expired. Restart the game through a licensed launcher.",
                    status,
                    null,
                    false);
        }
        if (status == 403) {
            return new ProfileApiException(
                    ApiFailureKind.FORBIDDEN,
                    "Minecraft profile service denied this operation.",
                    status,
                    null,
                    false);
        }
        if (status == 404) {
            return new ProfileApiException(
                    ApiFailureKind.NOT_FOUND,
                    "Minecraft profile was not found.",
                    status,
                    null,
                    false);
        }
        if (status == 429) {
            Duration cooldown = rateLimitGate.remember(
                    response.headers().firstValue("Retry-After").orElse(null));
            return new ProfileApiException(
                    ApiFailureKind.RATE_LIMITED,
                    "Minecraft profile service rate limit was reached.",
                    status,
                    cooldown,
                    false);
        }
        if (status >= 500) {
            return new ProfileApiException(
                    ApiFailureKind.SERVER_ERROR,
                    "Minecraft profile service is temporarily unavailable.",
                    status,
                    null,
                    mutation);
        }
        if (status >= 300 && status < 400) {
            return new ProfileApiException(
                    ApiFailureKind.REDIRECT_REJECTED,
                    "Minecraft profile service redirect was rejected.",
                    status,
                    null,
                    false);
        }
        return new ProfileApiException(
                ApiFailureKind.INVALID_RESPONSE,
                "Minecraft profile service returned an unexpected status.",
                status,
                null,
                false);
    }


    @Override
    public Optional<Duration> rateLimitRemaining() {
        return rateLimitGate.remaining();
    }

    private void rejectDuringRateLimitCooldown() throws ProfileApiException {
        Optional<Duration> remaining = rateLimitRemaining();
        if (remaining.isPresent()) {
            throw new ProfileApiException(
                    ApiFailureKind.RATE_LIMITED,
                    "Minecraft profile service rate-limit cooldown is still active.",
                    429,
                    remaining.orElseThrow(),
                    false);
        }
    }

    private void sleepBounded(Duration duration) throws ProfileApiException {
        try {
            sleeper.sleep(duration.compareTo(MAX_AUTOMATIC_WAIT) > 0 ? MAX_AUTOMATIC_WAIT : duration);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw networkFailure(false);
        }
    }

    private static boolean retryableGetStatus(int status) {
        return status == 429 || status >= 500;
    }

    private static ProfileApiException networkFailure(boolean mutation) {
        return new ProfileApiException(
                ApiFailureKind.NETWORK,
                mutation
                        ? "Network state is unknown after the Minecraft profile mutation request."
                        : "Minecraft profile service could not be reached.",
                null,
                null,
                mutation);
    }

    private static ProfileApiException invalidProfileResponse(ResponseSchemaCode schemaCode) {
        return new ProfileApiException(
                ApiFailureKind.INVALID_RESPONSE,
                "Minecraft profile response is invalid.",
                null,
                null,
                false,
                schemaCode);
    }

    private static byte[] multipartSkin(String boundary, SkinVariant variant, byte[] pngBytes) {
        ByteArrayOutputStream output = new ByteArrayOutputStream(pngBytes.length + 512);
        writeUtf8(output, "--" + boundary + "\r\n");
        writeUtf8(output, "Content-Disposition: form-data; name=\"variant\"\r\n\r\n");
        writeUtf8(output, variant.apiValue() + "\r\n");
        writeUtf8(output, "--" + boundary + "\r\n");
        writeUtf8(output, "Content-Disposition: form-data; name=\"file\"; filename=\"skin.png\"\r\n");
        writeUtf8(output, "Content-Type: image/png\r\n\r\n");
        output.writeBytes(pngBytes);
        writeUtf8(output, "\r\n--" + boundary + "--\r\n");
        return output.toByteArray();
    }

    private static void writeUtf8(ByteArrayOutputStream output, String value) {
        output.writeBytes(value.getBytes(StandardCharsets.UTF_8));
    }

    private static URI trustedTextureUri(String value) {
        URI uri = URI.create(value);
        if (!MinecraftServiceUriPolicy.isAllowedTextureUri(uri)) {
            throw new IllegalArgumentException("Texture URI is not allowlisted");
        }
        if ("http".equalsIgnoreCase(uri.getScheme())) {
            StringBuilder normalized = new StringBuilder("https://")
                    .append(MinecraftServiceUriPolicy.TEXTURE_HOST)
                    .append(uri.getRawPath());
            if (uri.getRawQuery() != null) {
                normalized.append('?').append(uri.getRawQuery());
            }
            uri = URI.create(normalized.toString());
            if (!MinecraftServiceUriPolicy.isAllowedTextureUri(uri)) {
                throw new IllegalArgumentException("Normalized texture URI is not allowlisted");
            }
        }
        return uri;
    }

    private static UUID parseMinecraftUuid(String value) {
        String normalized = value.replace("-", "");
        if (!normalized.matches("[0-9a-fA-F]{32}")) {
            throw new IllegalArgumentException("Minecraft UUID is invalid");
        }
        String dashed = normalized.substring(0, 8)
                + "-"
                + normalized.substring(8, 12)
                + "-"
                + normalized.substring(12, 16)
                + "-"
                + normalized.substring(16, 20)
                + "-"
                + normalized.substring(20);
        return UUID.fromString(dashed);
    }

    private static JsonArray requiredArray(JsonObject object, String member) {
        JsonElement value = object.get(member);
        if (value == null || !value.isJsonArray()) {
            throw new JsonParseException("Missing array");
        }
        return value.getAsJsonArray();
    }

    private static String requiredString(JsonObject object, String member) {
        JsonElement value = object.get(member);
        if (value == null || value.isJsonNull() || !value.isJsonPrimitive()) {
            throw new JsonParseException("Missing string");
        }
        return value.getAsString();
    }

    private static String requiredProfileString(JsonObject object, String member) {
        JsonElement value = object.get(member);
        if (value == null
                || value.isJsonNull()
                || !value.isJsonPrimitive()
                || !value.getAsJsonPrimitive().isString()) {
            throw new JsonParseException("Missing profile string");
        }
        return value.getAsString();
    }

    private static String optionalProfileString(JsonObject object, String member) {
        JsonElement value = object.get(member);
        if (value == null
                || value.isJsonNull()
                || !value.isJsonPrimitive()
                || !value.getAsJsonPrimitive().isString()) {
            return null;
        }
        return value.getAsString();
    }

    private static String optionalString(JsonObject object, String member) {
        JsonElement value = object.get(member);
        return value == null || value.isJsonNull() ? null : value.getAsString();
    }

    private static boolean sameAuthorityAndScheme(URI expected, URI actual) {
        return expected.getScheme().equalsIgnoreCase(actual.getScheme())
                && expected.getHost().equalsIgnoreCase(actual.getHost())
                && effectivePort(expected) == effectivePort(actual);
    }

    private static int effectivePort(URI uri) {
        if (uri.getPort() != -1) {
            return uri.getPort();
        }
        return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }

    private static boolean isKnownPath(String path) {
        return PROFILE_PATH.equals(path)
                || SKINS_PATH.equals(path)
                || ACTIVE_SKIN_PATH.equals(path)
                || ACTIVE_CAPE_PATH.equals(path);
    }

    private static URI normalizeBase(URI base, boolean allowLoopbackTestEndpoint) {
        Objects.requireNonNull(base, "base");
        URI normalized = base.resolve("/");
        if ("https".equalsIgnoreCase(normalized.getScheme())
                && "api.minecraftservices.com".equalsIgnoreCase(normalized.getHost())
                && effectivePort(normalized) == 443
                && normalized.getUserInfo() == null) {
            return normalized;
        }
        boolean loopback = "http".equalsIgnoreCase(normalized.getScheme())
                && ("127.0.0.1".equals(normalized.getHost()) || "localhost".equalsIgnoreCase(normalized.getHost()))
                && normalized.getPort() > 0;
        if (!allowLoopbackTestEndpoint || !loopback) {
            throw new IllegalArgumentException("Minecraft profile service base URI is not allowlisted");
        }
        return normalized;
    }

    private static void closeQuietly(InputStream stream) {
        try {
            stream.close();
        } catch (IOException ignored) {

        }
    }

    @FunctionalInterface
    interface Sleeper {
        void sleep(Duration duration) throws InterruptedException;
    }

    private record InternalResponse(int statusCode, java.net.http.HttpHeaders headers, byte[] body) {}

    private static final class TransportFailure extends Exception {
        private static final long serialVersionUID = 1L;

        private final ProfileApiException apiException;

        private TransportFailure(ProfileApiException apiException) {
            this.apiException = apiException;
        }

        private ProfileApiException apiException() {
            return apiException;
        }
    }
}

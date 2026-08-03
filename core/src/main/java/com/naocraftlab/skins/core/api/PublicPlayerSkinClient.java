package com.naocraftlab.skins.core.api;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.naocraftlab.skins.client.SignedTextureVerifier;
import com.naocraftlab.skins.core.model.SkinVariant;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Clock;
import java.util.Base64;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;


public final class PublicPlayerSkinClient {
    private static final URI LOOKUP = URI.create("https://api.minecraftservices.com");
    private static final URI SESSION = URI.create("https://sessionserver.mojang.com");
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_]{1,16}");
    private static final Pattern COMPACT_UUID = Pattern.compile("[0-9a-fA-F]{32}");
    private static final Pattern TEXTURE_PATH = Pattern.compile("/texture/[0-9a-fA-F]{64}");
    private static final int MAX_JSON_BYTES = 64 * 1024;
    private static final java.util.List<String> DEFAULT_SKINS = java.util.List.of(
            "alex", "ari", "efe", "kai", "makena", "noor", "steve", "sunny", "zuri");
    private static final Duration TIMEOUT = Duration.ofSeconds(20);

    private final HttpClient http;
    private final SignedTextureVerifier verifier;
    private final URI lookupBase;
    private final URI sessionBase;
    private final RateLimitGate rateLimitGate;

    public PublicPlayerSkinClient(SignedTextureVerifier verifier) {
        this(HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(10))
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .build(),
                verifier,
                LOOKUP,
                SESSION,
                Clock.systemUTC());
    }

    PublicPlayerSkinClient(
            HttpClient http,
            SignedTextureVerifier verifier,
            URI lookupBase,
            URI sessionBase) {
        this(http, verifier, lookupBase, sessionBase, Clock.systemUTC());
    }

    PublicPlayerSkinClient(
            HttpClient http,
            SignedTextureVerifier verifier,
            URI lookupBase,
            URI sessionBase,
            Clock clock) {
        this.http = Objects.requireNonNull(http, "http");
        this.verifier = Objects.requireNonNull(verifier, "verifier");
        this.lookupBase = Objects.requireNonNull(lookupBase, "lookupBase");
        this.sessionBase = Objects.requireNonNull(sessionBase, "sessionBase");
        this.rateLimitGate = new RateLimitGate(Objects.requireNonNull(clock, "clock"));
    }

    public Result lookup(String input) throws PublicSkinImportException {
        String identifier = Objects.requireNonNull(input, "input").trim();
        rejectDuringRateLimitCooldown();
        String compactId;
        if (NAME.matcher(identifier).matches()) {
            String path = "/minecraft/profile/lookup/name/"
                    + URLEncoder.encode(identifier, StandardCharsets.UTF_8);
            JsonObject identity = getJson(lookupBase.resolve(path), 16 * 1024);
            compactId = requiredString(identity, "id");
            requiredString(identity, "name");
        } else {
            String compact = identifier.replace("-", "");
            if (!COMPACT_UUID.matcher(compact).matches()) {
                throw failure(PublicSkinImportException.Code.INVALID_IDENTIFIER, "Player name or UUID is invalid.");
            }
            compactId = compact.toLowerCase(Locale.ROOT);
        }
        UUID profileId = parseUuid(compactId);

        JsonObject profile = getJson(
                sessionBase.resolve("/session/minecraft/profile/" + compactId + "?unsigned=false"),
                MAX_JSON_BYTES);
        if (!profileId.equals(parseUuid(requiredString(profile, "id")))) {
            throw failure(PublicSkinImportException.Code.PROFILE_REJECTED, "Public profile identity was rejected.");
        }
        String canonicalName = requiredString(profile, "name");
        JsonArray properties = profile.has("properties") && profile.get("properties").isJsonArray()
                ? profile.getAsJsonArray("properties")
                : new JsonArray();
        for (var element : properties) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject property = element.getAsJsonObject();
            if (!"textures".equals(optionalString(property, "name").orElse(null))) {
                continue;
            }
            String value = requiredString(property, "value");
            String signature = requiredString(property, "signature");
            String verified = verifier.verify(value, signature).filter(value::equals).orElseThrow(
                    () -> failure(PublicSkinImportException.Code.PROFILE_REJECTED, "Public profile signature was rejected."));
            return parseTextures(profileId, canonicalName, verified);
        }
        return defaultResult(profileId, canonicalName);
    }

    private Result parseTextures(UUID expectedId, String canonicalName, String payload)
            throws PublicSkinImportException {
        try {
            byte[] decoded = Base64.getDecoder().decode(payload);
            if (decoded.length > MAX_JSON_BYTES) {
                throw failure(PublicSkinImportException.Code.PROFILE_REJECTED, "Public profile payload was rejected.");
            }
            JsonObject root = JsonParser.parseString(new String(decoded, StandardCharsets.UTF_8)).getAsJsonObject();
            if (!expectedId.equals(parseUuid(requiredString(root, "profileId")))) {
                throw failure(PublicSkinImportException.Code.PROFILE_REJECTED, "Public profile identity was rejected.");
            }
            String payloadName = requiredString(root, "profileName");
            if (!payloadName.equals(canonicalName)) {
                throw failure(PublicSkinImportException.Code.PROFILE_REJECTED, "Public profile identity was rejected.");
            }
            JsonObject textures = root.getAsJsonObject("textures");
            if (textures == null || !textures.has("SKIN")) {
                return defaultResult(expectedId, canonicalName);
            }
            if (!java.util.Set.of("SKIN", "CAPE", "ELYTRA").containsAll(textures.keySet())) {
                throw failure(PublicSkinImportException.Code.PROFILE_REJECTED, "Public skin texture was rejected.");
            }
            JsonObject skin = textures.getAsJsonObject("SKIN");
            URI uri = URI.create(requiredString(skin, "url"));
            if (!MinecraftServiceUriPolicy.isAllowedTextureUri(uri)
                    || uri.getRawQuery() != null
                    || uri.getRawPath().indexOf('%') >= 0
                    || !TEXTURE_PATH.matcher(uri.getRawPath()).matches()) {
                throw failure(PublicSkinImportException.Code.PROFILE_REJECTED, "Public skin texture was rejected.");
            }
            SkinVariant variant = SkinVariant.CLASSIC;
            JsonObject metadata = skin.getAsJsonObject("metadata");
            if (metadata != null && "slim".equals(optionalString(metadata, "model").orElse(null))) {
                variant = SkinVariant.SLIM;
            }
            return new Result(expectedId, canonicalName, Optional.of(toHttps(uri)), variant, Optional.empty());
        } catch (IllegalArgumentException | IllegalStateException exception) {
            throw failure(PublicSkinImportException.Code.PROFILE_REJECTED, "Public profile payload was rejected.");
        }
    }

    private JsonObject getJson(URI uri, int maxBytes) throws PublicSkinImportException {
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(TIMEOUT)
                .header("Accept", "application/json")
                .header("User-Agent", "NCL-Skin/0.1")
                .GET()
                .build();
        try {
            HttpResponse<InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream body = response.body()) {
                if (response.statusCode() == 404 || response.statusCode() == 204) {
                    throw failure(PublicSkinImportException.Code.PROFILE_NOT_FOUND, "Player profile was not found.");
                }
                if (response.statusCode() == 429) {
                    rateLimitGate.remember(response.headers().firstValue("Retry-After").orElse(null));
                    throw failure(PublicSkinImportException.Code.RATE_LIMITED, "Public profile service is rate limited.");
                }
                if (response.statusCode() != 200) {
                    throw failure(PublicSkinImportException.Code.SERVICE_UNAVAILABLE, "Public profile service is unavailable.");
                }
                byte[] bytes = body.readNBytes(maxBytes + 1);
                if (bytes.length > maxBytes) {
                    throw failure(PublicSkinImportException.Code.PROFILE_REJECTED, "Public profile response was rejected.");
                }
                return JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
            }
        } catch (PublicSkinImportException exception) {
            throw exception;
        } catch (IOException exception) {
            throw failure(PublicSkinImportException.Code.NETWORK_FAILURE, "Public profile could not be loaded.");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw failure(PublicSkinImportException.Code.NETWORK_FAILURE, "Public profile request was interrupted.");
        } catch (RuntimeException exception) {
            throw failure(PublicSkinImportException.Code.PROFILE_REJECTED, "Public profile response was rejected.");
        }
    }

    private void rejectDuringRateLimitCooldown() throws PublicSkinImportException {
        if (rateLimitGate.remaining().isPresent()) {
            throw failure(
                    PublicSkinImportException.Code.RATE_LIMITED,
                    "Public profile service rate-limit cooldown is still active.");
        }
    }

    private static UUID parseUuid(String value) throws PublicSkinImportException {
        String compact = value.replace("-", "");
        if (!COMPACT_UUID.matcher(compact).matches()) {
            throw failure(PublicSkinImportException.Code.PROFILE_REJECTED, "Public profile identity was rejected.");
        }
        return UUID.fromString(compact.substring(0, 8) + '-' + compact.substring(8, 12) + '-'
                + compact.substring(12, 16) + '-' + compact.substring(16, 20) + '-' + compact.substring(20));
    }

    private static String requiredString(JsonObject object, String key) throws PublicSkinImportException {
        return optionalString(object, key).filter(value -> !value.isBlank()).orElseThrow(
                () -> failure(PublicSkinImportException.Code.PROFILE_REJECTED, "Public profile response was rejected."));
    }

    private static Optional<String> optionalString(JsonObject object, String key) {
        if (!object.has(key) || !object.get(key).isJsonPrimitive()
                || !object.getAsJsonPrimitive(key).isString()) {
            return Optional.empty();
        }
        return Optional.of(object.get(key).getAsString());
    }

    private static URI toHttps(URI uri) {
        return "https".equalsIgnoreCase(uri.getScheme()) ? uri : URI.create("https://" + uri.getRawAuthority() + uri.getRawPath());
    }

    private static Result defaultResult(UUID profileId, String canonicalName) {
        int index = Math.floorMod(profileId.hashCode(), 18);
        SkinVariant variant = index < 9 ? SkinVariant.SLIM : SkinVariant.CLASSIC;
        return new Result(
                profileId,
                canonicalName,
                Optional.empty(),
                variant,
                Optional.of(DEFAULT_SKINS.get(index % 9)));
    }

    private static PublicSkinImportException failure(PublicSkinImportException.Code code, String message) {
        return new PublicSkinImportException(code, message);
    }

    public record Result(
            UUID profileId,
            String canonicalName,
            Optional<URI> textureUri,
            SkinVariant variant,
            Optional<String> defaultSkinId) {
        public Result {
            Objects.requireNonNull(profileId, "profileId");
            Objects.requireNonNull(canonicalName, "canonicalName");
            textureUri = Objects.requireNonNull(textureUri, "textureUri");
            Objects.requireNonNull(variant, "variant");
            defaultSkinId = Objects.requireNonNull(defaultSkinId, "defaultSkinId");
            if (textureUri.isPresent() == defaultSkinId.isPresent()) {
                throw new IllegalArgumentException("result must be custom or default");
            }
        }
    }
}

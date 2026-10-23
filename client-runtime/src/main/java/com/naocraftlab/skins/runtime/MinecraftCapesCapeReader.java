package com.naocraftlab.skins.runtime;

import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.naocraftlab.skins.core.api.PublicHttpsImageFetcher;
import com.naocraftlab.skins.core.png.CapeAssetDecoder;
import com.naocraftlab.skins.core.png.PngValidationException;
import com.naocraftlab.skins.core.png.PreparedCapeAsset;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.InputStream;
import java.io.Reader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.charset.CodingErrorAction;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

public final class MinecraftCapesCapeReader implements CapeObservationReader {
    static final int MAX_PROFILE_BYTES = 16 * 1024;
    static final long MAX_PROFILE_WIRE_BYTES = 4L * ((CapeAssetDecoder.MAX_ENCODED_BYTES + 2L) / 3L)
            + MAX_PROFILE_BYTES;
    private static final Duration PROFILE_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration IMAGE_TIMEOUT = Duration.ofSeconds(120);
    private static final Duration CHAIN_TIMEOUT = Duration.ofSeconds(130);
    private static final int MAX_DEPTH = 12;
    private static final int MAX_NODES = 128;
    private static final String QUERY = "nclskins_refresh=";
    private final Transport transport;
    private final CapeAssetDecoder decoder;
    private final ProviderCooldown cooldown;
    private final LongSupplier nanoTime;
    private final SecureRandom random;
    private final Path scratch;
    private final AtomicLong accountEpoch = new AtomicLong();

    public MinecraftCapesCapeReader(Path scratch) {
        this(new SafeTransport(), new CapeAssetDecoder(), Clock.systemUTC(),
                System::nanoTime, new SecureRandom(), scratch);
    }

    MinecraftCapesCapeReader(Transport transport, CapeAssetDecoder decoder, Clock clock,
            LongSupplier nanoTime, SecureRandom random, Path scratch) {
        this.transport = Objects.requireNonNull(transport);
        this.decoder = Objects.requireNonNull(decoder);
        this.cooldown = new ProviderCooldown(Objects.requireNonNull(clock));
        this.nanoTime = Objects.requireNonNull(nanoTime);
        this.random = Objects.requireNonNull(random);
        this.scratch = Objects.requireNonNull(scratch);
    }

    public Optional<Duration> cooldownRemaining() { return cooldown.remaining(); }
    public long accountEpoch() { return accountEpoch.get(); }
    public void accountChanged() { accountEpoch.incrementAndGet(); }

    @Override public CapeObservationReader.Result observe(UUID account, String canonicalName,
            long epoch) {
        Outcome outcome = read(account, epoch);
        return new CapeObservationReader.Result(
                CapeObservationReader.Kind.valueOf(outcome.kind().name()), null,
                outcome.asset(), outcome.glint(), outcome.rateLimited());
    }

    @Override public void discard(PreparedCapeAsset asset) throws IOException {
        Files.deleteIfExists(asset.backing());
    }

    public Outcome read(UUID profileId, long epoch) {
        if (profileId == null) return Outcome.failure(false);
        if (accountEpoch.get() != epoch || cooldown.remaining().isPresent()) return Outcome.failure(true);
        long started = nanoTime.getAsLong();
        Path encoded = null;
        Path backing = null;
        boolean retainBacking = false;
        try {
            String compactId = profileId.toString().replace("-", "");
            URI profile = URI.create("https://api.minecraftcapes.net/profile/" + compactId);
            ProfileResponse first = transport.profile(fresh(profile), PROFILE_TIMEOUT,
                    MAX_PROFILE_WIRE_BYTES);
            if (accountEpoch.get() != epoch) return Outcome.failure(false);
            observe(epoch, first.status(), first.headers());
            if (first.status() == 429) return Outcome.failure(true);
            if (first.status() == 204 || first.status() == 404) return Outcome.absent();
            if (first.status() != 200) return Outcome.failure(false);
            Profile selected = Objects.requireNonNull(first.profile());
            if (selected.image() == null) return Outcome.absent();
            if (accountEpoch.get() != epoch || cooldown.remaining().isPresent()) return Outcome.failure(true);
            Duration remaining = remaining(started);
            if (remaining == null) return Outcome.failure(false);
            Duration timeout = remaining.compareTo(IMAGE_TIMEOUT) < 0 ? remaining : IMAGE_TIMEOUT;
            URI image = allowedImage(profileId, selected.image());
            Files.createDirectories(scratch);
            encoded = Files.createTempFile(scratch, "mc-cape-", ".encoded");
            Files.delete(encoded);
            Response second = transport.image(fresh(image), timeout, CapeAssetDecoder.MAX_ENCODED_BYTES, encoded);
            if (accountEpoch.get() != epoch) return Outcome.failure(false);
            observe(epoch, second.status(), second.headers());
            if (second.status() == 429) return Outcome.failure(true);
            if (second.status() != 200 || second.body().length != 0
                    || Files.size(encoded) > CapeAssetDecoder.MAX_ENCODED_BYTES) return Outcome.failure(false);
            if (!selectedSignature(encoded, selected.animated())) return Outcome.failure(false);
            if (remaining(started) == null) return Outcome.failure(false);
            backing = Files.createTempFile(scratch, "mc-cape-", ".frames");
            Files.delete(backing);
            PreparedCapeAsset asset = CapePreparationQueue.decode(decoder::prepare, encoded, backing);
            if (remaining(started) == null || accountEpoch.get() != epoch) return Outcome.failure(false);
            retainBacking = true;
            return Outcome.present(asset, selected.glint());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return Outcome.failure(false);
        } catch (IOException | PngValidationException | IllegalArgumentException invalid) {
            return Outcome.failure(false);
        } finally {
            if (encoded != null) try { Files.deleteIfExists(encoded); } catch (IOException ignored) {}
            if (!retainBacking && backing != null) try { Files.deleteIfExists(backing); } catch (IOException ignored) {}
        }
    }

    private void observe(long epoch, int status, Map<String, List<String>> headers) {
        if (accountEpoch.get() == epoch) cooldown.observe(status, headers);
    }

    private Duration remaining(long started) {
        long elapsed = nanoTime.getAsLong() - started;
        long left = CHAIN_TIMEOUT.toNanos() - elapsed;
        return left > 0 ? Duration.ofNanos(left) : null;
    }

    private URI fresh(URI uri) {
        byte[] nonce = new byte[16];
        random.nextBytes(nonce);
        return URI.create(uri.toASCIIString() + "?" + QUERY + HexFormat.of().formatHex(nonce));
    }

    static URI allowedImage(UUID profileId, URI image) {
        Objects.requireNonNull(image);
        String value = image.toString();
        String path = image.getRawPath();
        if (!value.equals(image.toASCIIString()) || value.length() > 2048
                || !"https".equals(image.getScheme()) || image.getRawUserInfo() != null
                || image.getRawFragment() != null || image.getRawQuery() != null
                || image.getPort() != -1 || image.getRawAuthority() == null
                || !image.getRawAuthority().equals(image.getHost()) || path == null) {
            throw new IllegalArgumentException("Invalid cape image URI");
        }
        String host = image.getHost();
        String uuid = profileId.toString().replace("-", "");
        boolean texturePath = path.matches("/capes/[0-9a-fA-F]{16,128}")
                || path.matches("/(sprite|animated)/[0-9a-fA-F]{64}");
        boolean apiPath = path.equals("/profile/" + uuid + "/cape");
        if (!("textures.minecraftcapes.net".equals(host) && texturePath
                || "api.minecraftcapes.net".equals(host) && apiPath)) {
            throw new IllegalArgumentException("Invalid cape image URI");
        }
        return image;
    }

    private static boolean selectedSignature(Path file, boolean animated) throws IOException {
        byte[] signature;
        try (var input = Files.newInputStream(file)) { signature = input.readNBytes(8); }
        return animated ? signature.length >= 6 && signature[0] == 'G' && signature[1] == 'I'
                && signature[2] == 'F' && signature[3] == '8'
                && (signature[4] == '7' || signature[4] == '9') && signature[5] == 'a'
                : signature.length == 8 && signature[0] == (byte) 0x89 && signature[1] == 'P'
                && signature[2] == 'N' && signature[3] == 'G' && signature[4] == 13
                && signature[5] == 10 && signature[6] == 26 && signature[7] == 10;
    }

    static Profile parse(byte[] body) throws IOException {
        return parse(new ByteArrayInputStream(body));
    }

    static Profile parse(InputStream body) throws IOException {
        MetadataReader limited = new MetadataReader(new InputStreamReader(body,
                StandardCharsets.UTF_8.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)));
        JsonReader json = new JsonReader(limited);
        try {
            if (json.peek() != JsonToken.BEGIN_OBJECT) throw new IOException("Invalid profile JSON");
            Scan scan = new Scan();
            scan.value(json, limited, 0, true);
            if (json.peek() != JsonToken.END_DOCUMENT) throw new IOException("Invalid profile JSON");
            String image = scan.animated != null ? scan.animated : scan.staticCape;
            return new Profile(image == null ? null : URI.create(image),
                    scan.animated != null, scan.glint);
        } catch (IllegalArgumentException | IllegalStateException invalid) {
            throw new IOException("Invalid profile JSON", invalid);
        }
    }

    record Profile(URI image, boolean animated, boolean glint) {}

    private static final class Scan {
        int nodes;
        int metadataBytes;
        String animated;
        String staticCape;
        boolean glint;
        boolean seenAnimated;
        boolean seenStatic;
        boolean seenGlint;

        void value(JsonReader json, MetadataReader limited, int depth, boolean root) throws IOException {
            if (++nodes > MAX_NODES || depth > MAX_DEPTH) throw new IOException("Profile JSON budget exceeded");
            JsonToken token = json.peek();
            if (token == JsonToken.BEGIN_OBJECT) {
                json.beginObject();
                while (json.hasNext()) {
                    String key = limited.name(json);
                    count(key);
                    if (root && (key.equals("animated_cape_url") || key.equals("cape_url")
                            || key.equals("capeGlint"))) {
                        semantic(json, limited, key);
                    } else if (root && key.equals("textures") && json.peek() == JsonToken.BEGIN_OBJECT) {
                        textures(json, limited, depth + 1);
                    } else value(json, limited, depth + 1, false);
                }
                json.endObject();
            } else if (token == JsonToken.BEGIN_ARRAY) {
                json.beginArray();
                while (json.hasNext()) value(json, limited, depth + 1, false);
                json.endArray();
            } else if (token == JsonToken.STRING || token == JsonToken.NUMBER) {
                count(limited.string(json));
            } else if (token == JsonToken.BOOLEAN) {
                json.nextBoolean();
                countBytes(5);
            } else if (token == JsonToken.NULL) {
                json.nextNull();
                countBytes(4);
            }
            else throw new IOException("Invalid profile JSON");
        }

        void textures(JsonReader json, MetadataReader limited, int depth) throws IOException {
            if (++nodes > MAX_NODES || depth > MAX_DEPTH) throw new IOException("Profile JSON budget exceeded");
            json.beginObject();
            while (json.hasNext()) {
                String key = limited.name(json);
                count(key);
                if (key.equals("cape")) {
                    if (++nodes > MAX_NODES) throw new IOException("Profile JSON budget exceeded");
                    JsonToken token = json.peek();
                    if (token == JsonToken.NULL) {
                        json.nextNull();
                        countBytes(4);
                    } else if (token == JsonToken.STRING) {
                        limited.legacy = true;
                        try { json.skipValue(); }
                        finally { limited.legacy = false; }
                    } else throw new IOException("Invalid legacy cape");
                } else value(json, limited, depth + 1, false);
            }
            json.endObject();
        }

        void count(String value) throws IOException {
            if (value.length() > 2048) throw new IOException("Profile string too long");
            countBytes(value.getBytes(StandardCharsets.UTF_8).length);
        }

        void countBytes(int bytes) throws IOException {
            metadataBytes += bytes;
            if (metadataBytes > MAX_PROFILE_BYTES) throw new IOException("Profile metadata budget exceeded");
        }

        void semantic(JsonReader json, MetadataReader limited, String key) throws IOException {
            if (++nodes > MAX_NODES) throw new IOException("Profile JSON budget exceeded");
            if (key.equals("capeGlint")) {
                if (seenGlint || json.peek() != JsonToken.BOOLEAN) throw new IOException("Invalid glint");
                seenGlint = true;
                glint = json.nextBoolean();
                countBytes(glint ? 4 : 5);
                return;
            }
            if (key.equals("animated_cape_url")) {
                if (seenAnimated) throw new IOException("Duplicate animated cape");
                seenAnimated = true;
                animated = url(json, limited);
            } else {
                if (seenStatic) throw new IOException("Duplicate cape");
                seenStatic = true;
                staticCape = url(json, limited);
            }
        }

        String url(JsonReader json, MetadataReader limited) throws IOException {
            if (json.peek() == JsonToken.NULL) {
                json.nextNull();
                countBytes(4);
                return null;
            }
            if (json.peek() != JsonToken.STRING) throw new IOException("Invalid cape URL");
            String value = limited.string(json);
            if (value.isEmpty() || value.length() > 2048) throw new IOException("Invalid cape URL");
            count(value);
            return value;
        }
    }

    private static final class MetadataReader extends Reader {
        private static final int MAX_OTHER_CHARS = 7 * MAX_PROFILE_BYTES;
        private static final int MAX_TOKEN_CHARS = 6 * 2048 + 1024;
        private final Reader source;
        private int otherChars;
        private int tokenChars;
        private boolean token;
        private boolean legacy;

        private MetadataReader(Reader source) { this.source = source; }

        String name(JsonReader json) throws IOException {
            token = true;
            tokenChars = 0;
            try { return json.nextName(); }
            finally { token = false; }
        }

        String string(JsonReader json) throws IOException {
            token = true;
            tokenChars = 0;
            try { return json.nextString(); }
            finally { token = false; }
        }

        @Override public int read(char[] target, int offset, int length) throws IOException {
            int read = source.read(target, offset, length);
            if (read > 0 && !legacy) {
                otherChars += read;
                if (otherChars > MAX_OTHER_CHARS) throw new IOException("Profile metadata input too large");
                if (token) {
                    tokenChars += read;
                    if (tokenChars > MAX_TOKEN_CHARS) throw new IOException("Profile token too large");
                }
            }
            return read;
        }

        @Override public void close() throws IOException { source.close(); }
    }

    interface Transport {
        ProfileResponse profile(URI uri, Duration timeout, long maxBytes)
                throws IOException, InterruptedException;
        Response image(URI uri, Duration timeout, long maxBytes, Path destination)
                throws IOException, InterruptedException;
    }

    record ProfileResponse(int status, Profile profile, Map<String, List<String>> headers) {
        ProfileResponse { headers = Map.copyOf(headers); }
    }

    record Response(int status, byte[] body, Map<String, List<String>> headers) {
        Response { body = Objects.requireNonNull(body).clone(); headers = Map.copyOf(headers); }
        @Override public byte[] body() { return body.clone(); }
    }

    private static final class SafeTransport implements Transport {
        private final PublicHttpsImageFetcher fetcher = new PublicHttpsImageFetcher(Duration.ofSeconds(5));
        public ProfileResponse profile(URI uri, Duration timeout, long maxBytes) throws IOException {
            PublicHttpsImageFetcher.StreamResponse<Profile> result = fetcher.getStreaming(
                    uri, timeout, maxBytes, MinecraftCapesCapeReader::parse);
            return new ProfileResponse(result.status(), result.body(), result.headers());
        }
        public Response image(URI uri, Duration timeout, long maxBytes, Path destination) throws IOException {
            PublicHttpsImageFetcher.FileResponse result = fetcher.getToFile(uri, timeout, maxBytes, destination);
            return new Response(result.status(), new byte[0], result.headers());
        }
    }

    public enum Kind { PRESENT, ABSENT, FAILURE }
    public record Outcome(Kind kind, PreparedCapeAsset asset, boolean glint, boolean rateLimited) {
        static Outcome present(PreparedCapeAsset asset, boolean glint) {
            return new Outcome(Kind.PRESENT, asset, glint, false);
        }
        static Outcome absent() { return new Outcome(Kind.ABSENT, null, false, false); }
        static Outcome failure(boolean rateLimited) { return new Outcome(Kind.FAILURE, null, false, rateLimited); }
    }
}

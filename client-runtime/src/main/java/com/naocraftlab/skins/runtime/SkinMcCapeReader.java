package com.naocraftlab.skins.runtime;

import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.naocraftlab.skins.core.api.PublicHttpsImageFetcher;
import com.naocraftlab.skins.core.png.PngValidationException;
import com.naocraftlab.skins.core.png.CapeAssetDecoder;
import com.naocraftlab.skins.core.png.PreparedCapeAsset;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

public final class SkinMcCapeReader implements CapeObservationReader {
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration CHAIN_TIMEOUT = Duration.ofSeconds(20);
    static final int MAX_JSON_BYTES = 2048;
    private static final int MAX_JSON_DEPTH = 12;
    private static final int MAX_JSON_NODES = 128;
    private static final int MAX_JSON_ALIASES = 16;
    private final OptifineCapeReader.Transport transport;
    private final ImageTransport imageTransport;
    private final CapeAssetDecoder decoder;
    private final Path scratch;
    private final ProviderCooldown cooldown;
    private final SkinMcFreshnessQuery freshness;
    private final LongSupplier nanoTime;
    private final AtomicLong cooldownEpoch = new AtomicLong();

    public SkinMcCapeReader(Path scratch) {
        this(new OptifineCapeReader.HttpTransport(), new SafeImageTransport(),
                new CapeAssetDecoder(), Objects.requireNonNull(scratch, "scratch"),
                Clock.systemUTC(), System::nanoTime);
    }

    SkinMcCapeReader(OptifineCapeReader.Transport transport, Path scratch, Clock clock) {
        this(transport, new SafeImageTransport(), new CapeAssetDecoder(), scratch,
                clock, System::nanoTime);
    }

    SkinMcCapeReader(OptifineCapeReader.Transport transport, ImageTransport imageTransport,
            Path scratch, Clock clock, LongSupplier nanoTime) {
        this(transport, imageTransport, new CapeAssetDecoder(), scratch, clock, nanoTime);
    }

    SkinMcCapeReader(OptifineCapeReader.Transport transport, ImageTransport imageTransport,
            CapeAssetDecoder decoder, Path scratch,
            Clock clock, LongSupplier nanoTime) {
        this.transport = Objects.requireNonNull(transport, "transport");
        this.imageTransport = Objects.requireNonNull(imageTransport, "imageTransport");
        this.decoder = Objects.requireNonNull(decoder, "decoder");
        this.scratch = Objects.requireNonNull(scratch, "scratch");
        this.cooldown = new ProviderCooldown(clock);
        this.freshness = new SkinMcFreshnessQuery(clock);
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
    }

    public Optional<Duration> cooldownRemaining() { return cooldown.remaining(); }

    public long accountEpoch() { return cooldownEpoch.get(); }

    public synchronized void accountChanged() { cooldownEpoch.incrementAndGet(); }

    private synchronized void observeCooldown(long epoch, OptifineCapeReader.Response response) {
        if (cooldownEpoch.get() == epoch) cooldown.observe(response.status(), response.headers());
    }

    public Outcome read(UUID profileId) { return read(profileId, accountEpoch()); }

    Outcome read(UUID profileId, long epoch) {
        if (profileId == null) return Outcome.failure(Failure.INVALID_ID);
        if (cooldownEpoch.get() != epoch || cooldown.remaining().isPresent()) {
            return Outcome.failure(Failure.RATE_LIMITED);
        }
        long started = nanoTime.getAsLong();
        Path encoded = null;
        Path backing = null;
        boolean retainBacking = false;
        try {
            Files.createDirectories(scratch);
            encoded = Files.createTempFile(scratch, "skinmc-cape-", ".encoded");
            Files.delete(encoded);
            URI canonical = SkinMcFreshnessQuery.canonical(profileId);
            long nonce = freshness.next();
            URI wire = SkinMcFreshnessQuery.wire(profileId, canonical, nonce);
            OptifineCapeReader.Response first = transport.getSkinMcCapeToFile(profileId,
                    canonical, wire, nonce, REQUEST_TIMEOUT, CapeAssetDecoder.MAX_ENCODED_BYTES, encoded);
            if (cooldownEpoch.get() != epoch) return Outcome.failure(Failure.RATE_LIMITED);
            observeCooldown(epoch, first);
            if (first.status() == 429) return Outcome.failure(Failure.RATE_LIMITED);
            if (first.status() == 204 || first.status() == 404) return Outcome.absent();
            if (first.status() != 200) return Outcome.failure(Failure.HTTP);
            byte[] body = first.bytes();
            if (Files.exists(encoded) || imageSignature(body)) {
                if (!Files.exists(encoded)) Files.write(encoded, body);
                if (Files.size(encoded) > CapeAssetDecoder.MAX_ENCODED_BYTES) {
                    return Outcome.failure(Failure.OVERSIZED);
                }
                if (remaining(started).isEmpty()) return Outcome.failure(Failure.NETWORK);
                backing = Files.createTempFile(scratch, "skinmc-cape-", ".frames");
                Files.delete(backing);
                PreparedCapeAsset asset = CapePreparationQueue.decode(decoder::prepare, encoded, backing);
                if (remaining(started).isEmpty() || cooldownEpoch.get() != epoch) {
                    return Outcome.failure(Failure.NETWORK);
                }
                retainBacking = true;
                return Outcome.present(asset);
            }
            if (body.length == 0 || body.length > MAX_JSON_BYTES) {
                return Outcome.failure(Failure.OVERSIZED);
            }
            if (!jsonCandidate(body)) return Outcome.failure(Failure.INVALID_IMAGE);
            URI image = imageUri(body);
            if (image == null) return Outcome.failure(Failure.INVALID_JSON);
            Optional<Duration> remaining = remaining(started);
            if (remaining.isEmpty()) return Outcome.failure(Failure.NETWORK);
            Duration hopTimeout = remaining.get().compareTo(REQUEST_TIMEOUT) < 0
                    ? remaining.get() : REQUEST_TIMEOUT;
            long aggregateLeft = CapeAssetDecoder.MAX_ENCODED_BYTES + MAX_JSON_BYTES - body.length;
            if (cooldownEpoch.get() != epoch || cooldown.remaining().isPresent()) {
                return Outcome.failure(Failure.RATE_LIMITED);
            }
            OptifineCapeReader.FileResponse second = imageTransport.getToFile(image, hopTimeout,
                    Math.min(CapeAssetDecoder.MAX_ENCODED_BYTES, aggregateLeft), encoded);
            if (cooldownEpoch.get() != epoch) return Outcome.failure(Failure.RATE_LIMITED);
            observeCooldown(epoch, new OptifineCapeReader.Response(second.status(),
                    new byte[0], second.headers()));
            if (second.status() == 429) return Outcome.failure(Failure.RATE_LIMITED);
            if (second.status() != 200) return Outcome.failure(Failure.HTTP);
            if (!Files.exists(encoded)) return Outcome.failure(Failure.INVALID_IMAGE);
            if (second.bytes() > aggregateLeft || second.bytes() > CapeAssetDecoder.MAX_ENCODED_BYTES
                    || Files.size(encoded) > aggregateLeft
                    || Files.size(encoded) > CapeAssetDecoder.MAX_ENCODED_BYTES) {
                return Outcome.failure(Failure.OVERSIZED);
            }
            if (remaining(started).isEmpty()) return Outcome.failure(Failure.NETWORK);
            if (!imageSignature(encoded)) return Outcome.failure(Failure.INVALID_IMAGE);
            backing = Files.createTempFile(scratch, "skinmc-cape-", ".frames");
            Files.delete(backing);
            PreparedCapeAsset asset = CapePreparationQueue.decode(decoder::prepare, encoded, backing);
            if (remaining(started).isEmpty() || cooldownEpoch.get() != epoch) {
                return Outcome.failure(Failure.NETWORK);
            }
            retainBacking = true;
            return Outcome.present(asset);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return Outcome.failure(Failure.INTERRUPTED);
        } catch (OptifineCapeReader.OversizedResponseException
                | PublicHttpsImageFetcher.OversizedImageException oversized) {
            return Outcome.failure(Failure.OVERSIZED);
        } catch (PublicHttpsImageFetcher.UnsafeImageUriException invalid) {
            return Outcome.failure(Failure.UNVERIFIED_IMAGE_HOST);
        } catch (PngValidationException invalid) {
            return Outcome.failure(Failure.INVALID_IMAGE);
        } catch (IOException failed) {
            return Outcome.failure(Failure.NETWORK);
        } finally {
            if (encoded != null) try { Files.deleteIfExists(encoded); } catch (IOException ignored) {}
            if (!retainBacking && backing != null) try { Files.deleteIfExists(backing); } catch (IOException ignored) {}
        }
    }

    private Optional<Duration> remaining(long started) {
        long elapsed = nanoTime.getAsLong() - started;
        long left = CHAIN_TIMEOUT.toNanos() - elapsed;
        return left > 0 ? Optional.of(Duration.ofNanos(left)) : Optional.empty();
    }

    private static boolean imageSignature(byte[] bytes) {
        return bytes.length >= 8 && bytes[0] == (byte) 0x89 && bytes[1] == 'P'
                && bytes[2] == 'N' && bytes[3] == 'G' && bytes[4] == 13
                && bytes[5] == 10 && bytes[6] == 26 && bytes[7] == 10
                || bytes.length >= 3 && bytes[0] == (byte) 0xff
                && bytes[1] == (byte) 0xd8 && bytes[2] == (byte) 0xff
                || bytes.length >= 6 && bytes[0] == 'G' && bytes[1] == 'I'
                && bytes[2] == 'F' && bytes[3] == '8'
                && (bytes[4] == '7' || bytes[4] == '9') && bytes[5] == 'a';
    }

    private static boolean imageSignature(Path path) throws IOException {
        try (var input = Files.newInputStream(path)) {
            return imageSignature(input.readNBytes(8));
        }
    }

    private static boolean jsonCandidate(byte[] body) {
        for (byte value : body) {
            if (value == ' ' || value == '\n' || value == '\r' || value == '\t') continue;
            return value == '{' || value == '[';
        }
        return false;
    }

    private static URI imageUri(byte[] body) {
        try (JsonReader reader = new JsonReader(new InputStreamReader(
                new ByteArrayInputStream(body), StandardCharsets.UTF_8))) {
            JsonScan scan = new JsonScan();
            scan.value(reader, 0);
            if (reader.peek() != JsonToken.END_DOCUMENT || scan.image == null) return null;
            return URI.create(scan.image);
        } catch (IOException | IllegalArgumentException | IllegalStateException invalid) {
            return null;
        }
    }

    private static final class JsonScan {
        private int nodes;
        private int aliases;
        private String image;

        private void value(JsonReader reader, int depth) throws IOException {
            if (++nodes > MAX_JSON_NODES || depth > MAX_JSON_DEPTH) {
                throw new IOException("JSON envelope is too complex");
            }
            JsonToken type = reader.peek();
            if (type == JsonToken.BEGIN_OBJECT) {
                reader.beginObject();
                while (reader.hasNext()) {
                    String key = reader.nextName();
                    if (alias(key)) {
                        if (++aliases > MAX_JSON_ALIASES || reader.peek() != JsonToken.STRING) {
                            throw new IOException("JSON image alias is invalid");
                        }
                        String candidate = reader.nextString();
                        if (image != null && !image.equals(candidate)) {
                            throw new IOException("JSON image aliases conflict");
                        }
                        image = candidate;
                        if (++nodes > MAX_JSON_NODES) throw new IOException("JSON envelope is too complex");
                    } else {
                        value(reader, depth + 1);
                    }
                }
                reader.endObject();
            } else if (type == JsonToken.BEGIN_ARRAY) {
                reader.beginArray();
                while (reader.hasNext()) value(reader, depth + 1);
                reader.endArray();
            } else if (type == JsonToken.STRING) {
                reader.nextString();
            } else if (type == JsonToken.NUMBER) {
                reader.nextString();
            } else if (type == JsonToken.BOOLEAN) {
                reader.nextBoolean();
            } else if (type == JsonToken.NULL) {
                reader.nextNull();
            } else {
                throw new IOException("JSON envelope is invalid");
            }
        }

        private static boolean alias(String key) {
            return key.equals("url") || key.equals("texture")
                    || key.equals("capeUrl") || key.equals("cape_url");
        }
    }

    @FunctionalInterface
    interface ImageTransport {
        OptifineCapeReader.FileResponse getToFile(URI uri, Duration timeout,
                long maxBytes, Path destination) throws IOException, InterruptedException;
    }

    private static final class SafeImageTransport implements ImageTransport {
        private final PublicHttpsImageFetcher fetcher = new PublicHttpsImageFetcher();

        @Override
        public OptifineCapeReader.FileResponse getToFile(URI uri, Duration timeout,
                long maxBytes, Path destination) throws IOException {
            PublicHttpsImageFetcher.FileResponse response = fetcher.getToFile(
                    uri, timeout, maxBytes, destination);
            return new OptifineCapeReader.FileResponse(response.status(), response.bytes(),
                    response.headers());
        }
    }

    public CapeObservationReader.Result observe(UUID account, String canonicalName, long epoch) {
        var result = read(account, epoch);
        return new CapeObservationReader.Result(CapeObservationReader.Kind.valueOf(result.kind().name()),
                null, result.asset(), result.failure() == Failure.RATE_LIMITED);
    }

    @Override public void discard(PreparedCapeAsset asset) throws IOException {
        Files.deleteIfExists(asset.backing());
    }

    public enum Failure {
        INVALID_ID, HTTP, RATE_LIMITED, OVERSIZED, INVALID_IMAGE, INVALID_JSON,
        UNVERIFIED_IMAGE_HOST, UNSUPPORTED_MEDIA, NETWORK, INTERRUPTED
    }

    public enum Kind { PRESENT, ABSENT, FAILURE }

    public record Outcome(Kind kind, PreparedCapeAsset asset,
            Failure failure) {
        public Outcome {
            Objects.requireNonNull(kind, "kind");
            if (kind == Kind.PRESENT && asset == null
                    || kind != Kind.PRESENT && asset != null
                    || kind == Kind.FAILURE && failure == null || kind != Kind.FAILURE && failure != null) {
                throw new IllegalArgumentException("Invalid SkinMC outcome");
            }
        }

        public static Outcome present(PreparedCapeAsset asset) {
            return new Outcome(Kind.PRESENT, Objects.requireNonNull(asset, "asset"), null);
        }

        public static Outcome absent() { return new Outcome(Kind.ABSENT, null, null); }

        public static Outcome failure(Failure failure) {
            return new Outcome(Kind.FAILURE, null, Objects.requireNonNull(failure, "failure"));
        }
    }
}

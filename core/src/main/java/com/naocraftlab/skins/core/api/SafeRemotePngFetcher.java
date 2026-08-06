package com.naocraftlab.skins.core.api;

import com.naocraftlab.skins.core.png.NormalizedSkin;
import com.naocraftlab.skins.core.png.PngValidationException;
import com.naocraftlab.skins.core.png.PngValidator;

import java.io.IOException;
import java.net.IDN;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;


public final class SafeRemotePngFetcher {
    private static final Duration TIMEOUT = Duration.ofSeconds(20);
    private static final int MAX_REDIRECTS = 5;
    private static final int DNS_THREADS = 4;
    private static final int DNS_QUEUE_CAPACITY = 32;
    private static final Set<Integer> FOLLOWED_REDIRECTS = Set.of(301, 302, 303, 307, 308);
    private static final AtomicInteger DNS_THREAD_SEQUENCE = new AtomicInteger();
    private static final ExecutorService DNS_EXECUTOR = new ThreadPoolExecutor(
            DNS_THREADS,
            DNS_THREADS,
            0L,
            TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(DNS_QUEUE_CAPACITY),
            task -> {
                Thread thread = new Thread(
                        task,
                        "nclskins-dns-resolver-" + DNS_THREAD_SEQUENCE.incrementAndGet());
                thread.setDaemon(true);
                thread.setContextClassLoader(null);
                return thread;
            },
            new ThreadPoolExecutor.AbortPolicy());
    private static final DeadlineHostResolver SYSTEM_RESOLVER = asynchronousResolver(
            InetAddress::getAllByName, DNS_EXECUTOR);

    private final PinnedHttpsTransport transport;
    private final PngValidator validator;
    private final DeadlineHostResolver resolver;
    private final LongSupplier nanoTime;
    private final Duration timeout;

    public SafeRemotePngFetcher() {
        this(new PinnedHttpsTransport(),
                new PngValidator(),
                SYSTEM_RESOLVER,
                System::nanoTime,
                TIMEOUT);
    }

    SafeRemotePngFetcher(
            PinnedHttpsTransport transport, PngValidator validator, HostResolver resolver) {
        this(transport, validator, resolver, System::nanoTime);
    }

    SafeRemotePngFetcher(
            PinnedHttpsTransport transport,
            PngValidator validator,
            HostResolver resolver,
            LongSupplier nanoTime) {
        this(
                transport,
                validator,
                synchronousResolver(resolver),
                nanoTime,
                TIMEOUT);
    }

    SafeRemotePngFetcher(
            PinnedHttpsTransport transport,
            PngValidator validator,
            DeadlineHostResolver resolver,
            LongSupplier nanoTime,
            Duration timeout) {
        this.transport = Objects.requireNonNull(transport, "transport");
        this.validator = Objects.requireNonNull(validator, "validator");
        this.resolver = Objects.requireNonNull(resolver, "resolver");
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
        this.timeout = Objects.requireNonNull(timeout, "timeout");
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
    }

    public ValidatedUri validate(String input) throws PublicSkinImportException {
        long deadline = saturatedAdd(nanoTime.getAsLong(), timeout.toNanos());
        return validateBeforeDeadline(input, deadline);
    }

    private ValidatedUri validateParsed(String input, long deadline)
            throws PublicSkinImportException {
        final URI uri;
        try {
            uri = URI.create(Objects.requireNonNull(input, "input").trim());
        } catch (IllegalArgumentException exception) {
            throw unsafe();
        }
        String scheme = uri.getScheme() == null
                ? ""
                : uri.getScheme().toLowerCase(Locale.ROOT);
        boolean validPort = "https".equals(scheme)
                ? uri.getPort() == -1 || uri.getPort() == 443
                : "http".equals(scheme) && (uri.getPort() == -1 || uri.getPort() == 80);
        if (!validPort
                || uri.getHost() == null
                || uri.getHost().isBlank()
                || uri.getUserInfo() != null
                || uri.getFragment() != null
                || uri.getRawPath() == null
                || uri.toASCIIString().length() > 2048) {
            throw unsafe();
        }
        String rawHost = uri.getHost();
        String asciiHost;
        if (rawHost.startsWith("[") && rawHost.endsWith("]")) {
            asciiHost = rawHost.substring(1, rawHost.length() - 1).toLowerCase(Locale.ROOT);
            if (asciiHost.indexOf('%') >= 0) {
                throw unsafe();
            }
        } else {
            try {
                asciiHost = IDN.toASCII(rawHost, IDN.USE_STD3_ASCII_RULES).toLowerCase(Locale.ROOT);
            } catch (IllegalArgumentException exception) {
                throw unsafe();
            }
        }
        if (asciiHost.equals("localhost")
                || asciiHost.endsWith(".localhost")
                || asciiHost.endsWith(".")
                || ambiguousNumericHost(asciiHost)) {
            throw unsafe();
        }
        final InetAddress[] addresses;
        try {
            addresses = resolver.resolve(asciiHost, remaining(deadline));
        } catch (IOException exception) {
            throw new PublicSkinImportException(
                    PublicSkinImportException.Code.NETWORK_FAILURE,
                    "Remote PNG host could not be resolved.");
        }
        if (addresses.length == 0) {
            throw unsafe();
        }
        for (InetAddress address : addresses) {
            if (!isPublic(address)) {
                throw unsafe();
            }
        }
        return new ValidatedUri(uri, asciiHost, List.of(addresses));
    }

    public byte[] fetch(String input) throws PublicSkinImportException {
        return fetchSkin(input).pngBytes();
    }

    public NormalizedSkin fetchSkin(String input) throws PublicSkinImportException {
        long deadline = saturatedAdd(nanoTime.getAsLong(), timeout.toNanos());
        ValidatedUri validated = validateBeforeDeadline(input, deadline);
        Set<String> visited = new HashSet<>();
        visited.add(loopKey(validated));
        boolean reachedHttps = isHttps(validated.uri());
        int redirects = 0;
        try {
            while (true) {
                PinnedHttpsTransport.Response response = transport.get(
                        validated.uri(),
                        validated.asciiHost(),
                        validated.addresses(),
                        remaining(deadline),
                        validator.maxBytes());
                if (!validated.uri().equals(response.uri())) {
                    throw networkFailure();
                }
                int status = response.statusCode();
                if (status >= 300 && status < 400) {
                    if (!FOLLOWED_REDIRECTS.contains(status) || redirects >= MAX_REDIRECTS) {
                        throw redirectRejected();
                    }
                    URI target = redirectTarget(validated.uri(), response);
                    if (reachedHttps && "http".equalsIgnoreCase(target.getScheme())) {
                        throw redirectRejected();
                    }
                    try {
                        validated = validateBeforeDeadline(target.toASCIIString(), deadline);
                    } catch (PublicSkinImportException exception) {
                        if (exception.code() == PublicSkinImportException.Code.UNSAFE_URL) {
                            throw redirectRejected();
                        }
                        throw exception;
                    }
                    if (!visited.add(loopKey(validated))) {
                        throw redirectRejected();
                    }
                    reachedHttps |= isHttps(validated.uri());
                    redirects++;
                    continue;
                }
                if (status != 200) {
                    throw statusFailure(status);
                }
                String encoding = response.firstHeader("Content-Encoding");
                String contentType = response.firstHeader("Content-Type")
                        .toLowerCase(Locale.ROOT);
                if ((!encoding.isEmpty() && !"identity".equalsIgnoreCase(encoding))
                        || contentType.startsWith("text/") || contentType.contains("html")) {
                    throw new PublicSkinImportException(
                            PublicSkinImportException.Code.INVALID_PNG,
                            "Remote response is not a PNG.");
                }
                try {
                    NormalizedSkin normalized = validator.normalizeSkinWithVariant(response.body());
                    ensureBeforeDeadline(deadline);
                    return normalized;
                } catch (PngValidationException exception) {
                    throw new PublicSkinImportException(
                            PublicSkinImportException.Code.INVALID_PNG,
                            "Remote response is not a supported skin PNG.");
                }
            }
        } catch (PublicSkinImportException exception) {
            throw exception;
        } catch (PinnedHttpsTransport.BodyTooLargeException exception) {
            throw new PublicSkinImportException(
                    PublicSkinImportException.Code.OVERSIZED,
                    "Remote skin file exceeds the size limit.");
        } catch (IOException exception) {
            throw networkFailure();
        }
    }

    private ValidatedUri validateBeforeDeadline(String input, long deadline)
            throws PublicSkinImportException {
        ensureBeforeDeadline(deadline);
        ValidatedUri validated = validateParsed(input, deadline);
        ensureBeforeDeadline(deadline);
        return validated;
    }

    private static DeadlineHostResolver synchronousResolver(HostResolver resolver) {
        Objects.requireNonNull(resolver, "resolver");
        return (host, ignoredTimeout) -> resolver.resolve(host);
    }

    static DeadlineHostResolver asynchronousResolver(
            HostResolver resolver, ExecutorService executor) {
        Objects.requireNonNull(resolver, "resolver");
        Objects.requireNonNull(executor, "executor");
        return (host, timeout) -> {
            Objects.requireNonNull(timeout, "timeout");
            if (timeout.isZero() || timeout.isNegative()) {
                throw new IOException("DNS lookup timed out");
            }
            final Future<InetAddress[]> future;
            try {
                future = executor.submit(() -> resolver.resolve(host));
            } catch (RejectedExecutionException rejected) {
                throw new IOException("DNS resolver is unavailable", rejected);
            }
            try {
                return future.get(timeout.toNanos(), TimeUnit.NANOSECONDS);
            } catch (TimeoutException timedOut) {
                future.cancel(true);
                throw new IOException("DNS lookup timed out", timedOut);
            } catch (InterruptedException interrupted) {
                future.cancel(true);
                Thread.currentThread().interrupt();
                throw new IOException("DNS lookup was interrupted", interrupted);
            } catch (ExecutionException failed) {
                Throwable cause = failed.getCause();
                if (cause instanceof IOException ioException) {
                    throw ioException;
                }
                if (cause instanceof Error error) {
                    throw error;
                }
                throw new IOException("DNS lookup failed", cause);
            }
        };
    }

    private Duration remaining(long deadline) throws PublicSkinImportException {
        long remaining = deadline - nanoTime.getAsLong();
        if (remaining <= 0) {
            throw networkFailure();
        }
        return Duration.ofNanos(remaining);
    }

    private void ensureBeforeDeadline(long deadline) throws PublicSkinImportException {
        if (deadline - nanoTime.getAsLong() <= 0) {
            throw networkFailure();
        }
    }

    private static URI redirectTarget(URI source, PinnedHttpsTransport.Response response)
            throws PublicSkinImportException {
        List<String> locations = response.headers().getOrDefault("location", List.of());
        if (locations.size() != 1 || locations.get(0).isBlank()) {
            throw redirectRejected();
        }
        try {
            return source.resolve(URI.create(locations.get(0)));
        } catch (IllegalArgumentException exception) {
            throw redirectRejected();
        }
    }

    private static String loopKey(ValidatedUri validated) {
        URI normalized = validated.uri().normalize();
        String path = normalized.getRawPath();
        if (path == null || path.isEmpty()) {
            path = "/";
        }
        int port = normalized.getPort();
        if (port == -1) {
            port = isHttps(normalized) ? 443 : 80;
        }
        String query = normalized.getRawQuery();
        return normalized.getScheme().toLowerCase(Locale.ROOT)
                + "://" + validated.asciiHost() + ':' + port + path
                + (query == null ? "" : '?' + query);
    }

    private static boolean isHttps(URI uri) {
        return "https".equalsIgnoreCase(uri.getScheme());
    }

    private static long saturatedAdd(long left, long right) {
        return right > 0 && left > Long.MAX_VALUE - right ? Long.MAX_VALUE : left + right;
    }

    static boolean isPublic(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()) {
            return false;
        }
        byte[] bytes = address.getAddress();
        if (address instanceof Inet4Address) {
            int first = Byte.toUnsignedInt(bytes[0]);
            int second = Byte.toUnsignedInt(bytes[1]);
            int third = Byte.toUnsignedInt(bytes[2]);
            return first != 0
                    && (first != 100 || second < 64 || second > 127)
                    && !(first == 192 && second == 0 && (third == 0 || third == 2))
                    && !(first == 192 && second == 88 && third == 99)
                    && !(first == 198 && (second == 18 || second == 19))
                    && !(first == 198 && second == 51 && third == 100)
                    && !(first == 203 && second == 0 && third == 113)
                    && first < 224;
        }
        if (address instanceof Inet6Address) {
            int first = Byte.toUnsignedInt(bytes[0]);
            int second = Byte.toUnsignedInt(bytes[1]);
            int third = Byte.toUnsignedInt(bytes[2]);
            int fourth = Byte.toUnsignedInt(bytes[3]);
            return (first & 0xe0) == 0x20
                    && !(first == 0x20 && second == 0x01 && (third & 0xfe) == 0)
                    && !(first == 0x20 && second == 0x01 && third == 0x0d && fourth == 0xb8)
                    && !(first == 0x20 && second == 0x02)
                    && !(first == 0x3f && second == 0xfe);
        }
        return false;
    }

    private static boolean ambiguousNumericHost(String host) {
        boolean decimalOrDots = !host.isEmpty();
        for (int index = 0; index < host.length(); index++) {
            char value = host.charAt(index);
            if (value != '.' && (value < '0' || value > '9')) {
                decimalOrDots = false;
                break;
            }
        }
        if (!decimalOrDots) {
            return host.matches("(?i)0x[0-9a-f]+");
        }
        String[] octets = host.split("\\.", -1);
        if (octets.length != 4) {
            return true;
        }
        for (String octet : octets) {
            if (octet.isEmpty()
                    || octet.length() > 1 && octet.charAt(0) == '0'
                    || octet.length() > 3) {
                return true;
            }
            try {
                if (Integer.parseInt(octet) > 255) {
                    return true;
                }
            } catch (NumberFormatException invalid) {
                return true;
            }
        }
        return false;
    }

    private static PublicSkinImportException redirectRejected() {
        return new PublicSkinImportException(
                PublicSkinImportException.Code.REDIRECT_REJECTED,
                "Remote skin file redirect was rejected.");
    }

    private static PublicSkinImportException networkFailure() {
        return new PublicSkinImportException(
                PublicSkinImportException.Code.NETWORK_FAILURE,
                "Remote skin file could not be downloaded.");
    }

    private static PublicSkinImportException statusFailure(int status) {
        if (status == 401 || status == 403) {
            return new PublicSkinImportException(
                    PublicSkinImportException.Code.SITE_BLOCKED,
                    "Remote skin file host rejected automatic downloading.");
        }
        if (status == 429) {
            return new PublicSkinImportException(
                    PublicSkinImportException.Code.RATE_LIMITED,
                    "Remote skin file host rate-limited the request.");
        }
        if (status >= 500 && status < 600) {
            return new PublicSkinImportException(
                    PublicSkinImportException.Code.SERVICE_UNAVAILABLE,
                    "Remote skin file host is unavailable.");
        }
        return networkFailure();
    }

    private static PublicSkinImportException unsafe() {
        return new PublicSkinImportException(
                PublicSkinImportException.Code.UNSAFE_URL,
                "Only a public HTTP or HTTPS skin file URL is allowed.");
    }

    @FunctionalInterface
    interface HostResolver {
        InetAddress[] resolve(String host) throws IOException;
    }

    @FunctionalInterface
    interface DeadlineHostResolver {
        InetAddress[] resolve(String host, Duration timeout) throws IOException;
    }

    public record ValidatedUri(URI uri, String asciiHost, List<InetAddress> addresses) {
        public ValidatedUri {
            Objects.requireNonNull(uri, "uri");
            Objects.requireNonNull(asciiHost, "asciiHost");
            addresses = List.copyOf(Objects.requireNonNull(addresses, "addresses"));
            if (addresses.isEmpty()) {
                throw new IllegalArgumentException("validated addresses must not be empty");
            }
        }
    }
}

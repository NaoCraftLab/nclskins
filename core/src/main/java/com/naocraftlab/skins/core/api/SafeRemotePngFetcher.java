package com.naocraftlab.skins.core.api;

import com.naocraftlab.skins.core.png.PngValidationException;
import com.naocraftlab.skins.core.png.PngValidator;
import java.io.IOException;
import java.net.IDN;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.time.Duration;
import java.util.Locale;
import java.util.List;
import java.util.Objects;


public final class SafeRemotePngFetcher {
    private static final Duration TIMEOUT = Duration.ofSeconds(20);
    private final PinnedHttpsTransport transport;
    private final PngValidator validator;
    private final HostResolver resolver;

    public SafeRemotePngFetcher() {
        this(new PinnedHttpsTransport(),
                new PngValidator(),
                InetAddress::getAllByName);
    }

    SafeRemotePngFetcher(
            PinnedHttpsTransport transport, PngValidator validator, HostResolver resolver) {
        this.transport = Objects.requireNonNull(transport, "transport");
        this.validator = Objects.requireNonNull(validator, "validator");
        this.resolver = Objects.requireNonNull(resolver, "resolver");
    }

    public ValidatedUri validate(String input) throws PublicSkinImportException {
        final URI uri;
        try {
            uri = URI.create(Objects.requireNonNull(input, "input").trim());
        } catch (IllegalArgumentException exception) {
            throw unsafe();
        }
        if (!"https".equalsIgnoreCase(uri.getScheme())
                || uri.getHost() == null
                || uri.getHost().isBlank()
                || uri.getPort() != -1 && uri.getPort() != 443
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
            addresses = resolver.resolve(asciiHost);
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
        ValidatedUri validated = validate(input);
        try {
            PinnedHttpsTransport.Response response = transport.get(
                    validated.uri(),
                    validated.asciiHost(),
                    validated.addresses(),
                    TIMEOUT,
                    validator.maxBytes());
            if (response.statusCode() >= 300 && response.statusCode() < 400) {
                throw new PublicSkinImportException(
                        PublicSkinImportException.Code.REDIRECT_REJECTED,
                        "Remote PNG redirect was rejected.");
            }
            if (response.statusCode() != 200 || !sameOrigin(validated.uri(), response.uri())) {
                throw new PublicSkinImportException(
                        PublicSkinImportException.Code.NETWORK_FAILURE,
                        "Remote PNG could not be downloaded.");
            }
            String encoding = response.firstHeader("Content-Encoding");
            String contentType = response.firstHeader("Content-Type").toLowerCase(Locale.ROOT);
            if ((!encoding.isEmpty() && !"identity".equalsIgnoreCase(encoding))
                    || contentType.startsWith("text/") || contentType.contains("html")) {
                throw new PublicSkinImportException(
                        PublicSkinImportException.Code.INVALID_PNG,
                        "Remote response is not a PNG.");
            }
            try {
                return validator.normalizeSkin(response.body());
            } catch (PngValidationException exception) {
                throw new PublicSkinImportException(
                        PublicSkinImportException.Code.INVALID_PNG,
                        "Remote response is not a supported skin PNG.");
            }
        } catch (PublicSkinImportException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new PublicSkinImportException(
                    PublicSkinImportException.Code.NETWORK_FAILURE,
                    "Remote PNG could not be downloaded.");
        }
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

    private static boolean sameOrigin(URI expected, URI actual) {
        return expected.getScheme().equalsIgnoreCase(actual.getScheme())
                && expected.getHost().equalsIgnoreCase(actual.getHost())
                && (actual.getPort() == -1 || actual.getPort() == 443);
    }

    private static PublicSkinImportException unsafe() {
        return new PublicSkinImportException(
                PublicSkinImportException.Code.UNSAFE_URL,
                "Only a direct public HTTPS skin URL is allowed.");
    }

    @FunctionalInterface
    interface HostResolver {
        InetAddress[] resolve(String host) throws IOException;
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

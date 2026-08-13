package com.naocraftlab.skins.core.config;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;


public final class ServerConfigurationRepository {
    public static final String FILE_NAME = "nclskins-server.json5";
    public static final long MAX_DOCUMENT_BYTES = 256L * 1024L;

    public static final String ENABLED_DESCRIPTION =
            "nclskins.config.server.realtime_refresh.enabled.description";
    public static final String TRUSTED_PROXY_DESCRIPTION =
            "nclskins.config.server.realtime_refresh.trusted_proxy_forwarding.description";
    public static final String MAX_CONCURRENT_DESCRIPTION =
            "nclskins.config.server.realtime_refresh.max_concurrent_lookups.description";
    public static final String LOOKUP_RATE_DESCRIPTION =
            "nclskins.config.server.realtime_refresh.lookup_rate_per_second.description";
    public static final String LOOKUP_BURST_DESCRIPTION =
            "nclskins.config.server.realtime_refresh.lookup_burst.description";

    private static final Pattern SCALAR_ASSIGNMENT = Pattern.compile(
            "(?s)(?:\\\"(?<quotedKey>[A-Za-z][A-Za-z0-9]*)\\\"|(?<plainKey>[A-Za-z][A-Za-z0-9]*))"
                    + "\\s*:\\s*(?<value>true|false|[+-]?(?:(?:[0-9]+(?:\\.[0-9]*)?)|(?:\\.[0-9]+))(?:[eE][+-]?[0-9]+)?)");

    private final Path configurationDirectory;
    private final Function<String, String> descriptions;

    public ServerConfigurationRepository(
            Path configurationDirectory,
            Function<String, String> descriptions) {
        this.configurationDirectory = Objects.requireNonNull(
                configurationDirectory, "configurationDirectory");
        this.descriptions = Objects.requireNonNull(descriptions, "descriptions");
        for (String key : List.of(
                ENABLED_DESCRIPTION,
                TRUSTED_PROXY_DESCRIPTION,
                MAX_CONCURRENT_DESCRIPTION,
                LOOKUP_RATE_DESCRIPTION,
                LOOKUP_BURST_DESCRIPTION)) {
            String description = descriptions.apply(key);
            if (description == null || description.isBlank()) {
                throw new IllegalArgumentException("Missing English configuration description " + key);
            }
        }
    }

    public static ServerConfigurationRepository bundled(
            Path configurationDirectory,
            ClassLoader classLoader) {
        Objects.requireNonNull(classLoader, "classLoader");
        String resource = "assets/nclskins/lang/en_us.json";
        try (InputStream stream = classLoader.getResourceAsStream(resource)) {
            if (stream == null) {
                throw new ConfigurationException("Missing bundled English localization " + resource);
            }
            JsonElement parsed = JsonParser.parseReader(
                    new InputStreamReader(stream, StandardCharsets.UTF_8));
            if (!parsed.isJsonObject()) {
                throw new ConfigurationException("Bundled English localization is not an object");
            }
            JsonObject root = parsed.getAsJsonObject();
            return new ServerConfigurationRepository(configurationDirectory, key -> {
                JsonElement value = root.get(key);
                return value != null && value.isJsonPrimitive()
                        && value.getAsJsonPrimitive().isString()
                        ? value.getAsString()
                        : null;
            });
        } catch (IOException failure) {
            throw new ConfigurationException(
                    "Unable to read bundled English server configuration descriptions", failure);
        }
    }

    public synchronized ServerConfiguration load() {
        Path file = configurationDirectory.resolve(FILE_NAME);
        String existing = read(file);
        ServerConfiguration configuration = existing == null
                ? ServerConfiguration.defaults()
                : parse(existing);
        rewriteIfNeeded(file, existing, canonical(configuration));
        return configuration;
    }

    public synchronized void save(ServerConfiguration configuration) {
        rewrite(configurationDirectory.resolve(FILE_NAME), canonical(
                Objects.requireNonNull(configuration, "configuration")));
    }

    public String canonical(ServerConfiguration configuration) {
        ServerConfiguration.RealtimeRefresh refresh = Objects.requireNonNull(
                configuration, "configuration").realtimeRefresh();
        StringBuilder out = new StringBuilder(1_024);
        out.append("{\n");
        out.append("  \"realtimeRefresh\": {\n");
        appendComment(out, ENABLED_DESCRIPTION);
        out.append("    \"enabled\": ").append(refresh.enabled()).append(",\n\n");
        appendComment(out, TRUSTED_PROXY_DESCRIPTION);
        out.append("    \"trustedProxyForwarding\": ")
                .append(refresh.trustedProxyForwarding()).append(",\n\n");
        appendComment(out, MAX_CONCURRENT_DESCRIPTION);
        out.append("    \"maxConcurrentLookups\": ")
                .append(refresh.maxConcurrentLookups()).append(",\n\n");
        appendComment(out, LOOKUP_RATE_DESCRIPTION);
        out.append("    \"lookupRatePerSecond\": ")
                .append(Double.toString(refresh.lookupRatePerSecond())).append(",\n\n");
        appendComment(out, LOOKUP_BURST_DESCRIPTION);
        out.append("    \"lookupBurst\": ").append(refresh.lookupBurst()).append("\n");
        out.append("  }\n");
        out.append("}\n");
        return out.toString();
    }

    private ServerConfiguration parse(String document) {
        ServerConfiguration.RealtimeRefresh defaults =
                ServerConfiguration.defaults().realtimeRefresh();
        ScalarScanner scanner = new ScalarScanner(document);
        JsonObject root = parseObject(document).orElse(null);
        return new ServerConfiguration(new ServerConfiguration.RealtimeRefresh(
                booleanValue(root, scanner, defaults.enabled(), "enabled"),
                booleanValue(root, scanner, defaults.trustedProxyForwarding(),
                        "trustedProxyForwarding"),
                positiveIntegerValue(root, scanner, defaults.maxConcurrentLookups(),
                        "maxConcurrentLookups"),
                positiveDoubleValue(root, scanner, defaults.lookupRatePerSecond(),
                        "lookupRatePerSecond"),
                positiveIntegerValue(root, scanner, defaults.lookupBurst(), "lookupBurst")));
    }

    private String read(Path file) {
        try {
            if (!Files.exists(file)) {
                return null;
            }
            if (Files.size(file) > MAX_DOCUMENT_BYTES) {
                return "";
            }
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new ConfigurationException("Unable to read " + file.toAbsolutePath(), failure);
        }
    }

    private static Optional<JsonObject> parseObject(String document) {
        try {
            JsonElement parsed = JsonParser.parseString(document);
            return parsed.isJsonObject() ? Optional.of(parsed.getAsJsonObject()) : Optional.empty();
        } catch (RuntimeException malformed) {
            return Optional.empty();
        }
    }

    private static boolean booleanValue(
            JsonObject root, ScalarScanner scanner, boolean fallback, String field) {
        if (scanner.count(field) != 1) {
            return fallback;
        }
        JsonElement value = nested(root, field);
        if (value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean()) {
            return value.getAsBoolean();
        }
        return scanner.single(field)
                .filter(raw -> raw.equals("true") || raw.equals("false"))
                .map(Boolean::parseBoolean)
                .orElse(fallback);
    }

    private static int positiveIntegerValue(
            JsonObject root, ScalarScanner scanner, int fallback, String field) {
        if (scanner.count(field) != 1) {
            return fallback;
        }
        JsonElement value = nested(root, field);
        Integer parsed;
        try {
            parsed = value != null && value.isJsonPrimitive()
                    && value.getAsJsonPrimitive().isNumber()
                    ? value.getAsInt()
                    : scanner.single(field).map(Integer::valueOf).orElse(null);
        } catch (RuntimeException invalid) {
            parsed = null;
        }
        return parsed != null && parsed > 0 ? parsed : fallback;
    }

    private static double positiveDoubleValue(
            JsonObject root, ScalarScanner scanner, double fallback, String field) {
        if (scanner.count(field) != 1) {
            return fallback;
        }
        JsonElement value = nested(root, field);
        Double parsed;
        try {
            parsed = value != null && value.isJsonPrimitive()
                    && value.getAsJsonPrimitive().isNumber()
                    ? value.getAsDouble()
                    : scanner.single(field).map(Double::valueOf).orElse(null);
        } catch (RuntimeException invalid) {
            parsed = null;
        }
        return parsed != null && Double.isFinite(parsed) && parsed > 0.0d ? parsed : fallback;
    }

    private static JsonElement nested(JsonObject root, String field) {
        if (root == null) {
            return null;
        }
        JsonElement refresh = root.get("realtimeRefresh");
        return refresh != null && refresh.isJsonObject()
                ? refresh.getAsJsonObject().get(field)
                : null;
    }

    private void appendComment(StringBuilder out, String key) {
        String prefix = "    // ";
        for (String line : descriptions.apply(key).split("\\R", -1)) {
            out.append(prefix).append(line).append('\n');
        }
    }

    private void rewriteIfNeeded(Path file, String existing, String canonical) {
        if (!canonical.equals(existing)) {
            rewrite(file, canonical);
        }
    }

    private void rewrite(Path file, String canonical) {
        Path temporary = null;
        try {
            Files.createDirectories(configurationDirectory);
            temporary = Files.createTempFile(
                    configurationDirectory, file.getFileName().toString() + '.', ".tmp");
            Files.writeString(
                    temporary,
                    canonical,
                    StandardCharsets.UTF_8,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE);
            try {
                Files.move(
                        temporary,
                        file,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException failure) {
            throw new ConfigurationException("Unable to write " + file.toAbsolutePath(), failure);
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException ignored) {
                }
            }
        }
    }

    private static final class ScalarScanner {
        private final List<Scalar> values;

        private ScalarScanner(String document) {
            values = scan(stripComments(Objects.requireNonNull(document, "document")));
        }

        int count(String key) {
            int count = 0;
            for (Scalar scalar : values) {
                if (scalar.key().equals(key)) {
                    count++;
                }
            }
            return count;
        }

        Optional<String> single(String key) {
            String result = null;
            for (Scalar scalar : values) {
                if (!scalar.key().equals(key)) {
                    continue;
                }
                if (result != null) {
                    return Optional.empty();
                }
                result = scalar.value();
            }
            return Optional.ofNullable(result);
        }

        private static List<Scalar> scan(String document) {
            List<Scalar> values = new ArrayList<>();
            Matcher matcher = SCALAR_ASSIGNMENT.matcher(document);
            while (matcher.find()) {
                String key = matcher.group("quotedKey");
                values.add(new Scalar(
                        key == null ? matcher.group("plainKey") : key,
                        matcher.group("value")));
            }
            return List.copyOf(values);
        }

        private static String stripComments(String source) {
            StringBuilder out = new StringBuilder(source.length());
            boolean string = false;
            boolean escaped = false;
            for (int index = 0; index < source.length(); index++) {
                char current = source.charAt(index);
                if (string) {
                    out.append(current);
                    if (escaped) {
                        escaped = false;
                    } else if (current == '\\') {
                        escaped = true;
                    } else if (current == '"') {
                        string = false;
                    }
                    continue;
                }
                if (current == '"') {
                    string = true;
                    out.append(current);
                    continue;
                }
                if (current == '/' && index + 1 < source.length()) {
                    char next = source.charAt(index + 1);
                    if (next == '/') {
                        out.append("  ");
                        index += 2;
                        while (index < source.length() && source.charAt(index) != '\n') {
                            out.append(' ');
                            index++;
                        }
                        if (index < source.length()) {
                            out.append('\n');
                        }
                        continue;
                    }
                    if (next == '*') {
                        out.append("  ");
                        index += 2;
                        while (index + 1 < source.length()
                                && !(source.charAt(index) == '*'
                                && source.charAt(index + 1) == '/')) {
                            out.append(source.charAt(index) == '\n' ? '\n' : ' ');
                            index++;
                        }
                        if (index + 1 < source.length()) {
                            out.append("  ");
                            index++;
                        }
                        continue;
                    }
                }
                out.append(current);
            }
            return out.toString();
        }
    }

    private record Scalar(String key, String value) {
    }
}

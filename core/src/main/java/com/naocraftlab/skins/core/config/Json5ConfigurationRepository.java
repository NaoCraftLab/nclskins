package com.naocraftlab.skins.core.config;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;


public final class Json5ConfigurationRepository {
    public static final String CLIENT_FILE_NAME = "nclskins-client.json5";
    public static final String SERVER_FILE_NAME = ServerConfigurationRepository.FILE_NAME;

    private static final long MAX_DOCUMENT_BYTES = 256L * 1024L;
    private static final Pattern SCALAR_ASSIGNMENT = Pattern.compile(
            "(?s)(?:\\\"(?<quotedKey>[A-Za-z][A-Za-z0-9]*)\\\"|(?<plainKey>[A-Za-z][A-Za-z0-9]*))"
                    + "\\s*:\\s*(?<value>true|false|[+-]?(?:(?:[0-9]+(?:\\.[0-9]*)?)|(?:\\.[0-9]+))(?:[eE][+-]?[0-9]+)?|\\\"(?:\\\\.|[^\\\"\\\\])*\\\")");

    private final Path configurationDirectory;
    private final ConfigurationDescriptions descriptions;

    public Json5ConfigurationRepository(
            Path configurationDirectory,
            ConfigurationDescriptions descriptions) {
        this.configurationDirectory = Objects.requireNonNull(
                configurationDirectory, "configurationDirectory");
        this.descriptions = Objects.requireNonNull(descriptions, "descriptions");
    }

    public static Json5ConfigurationRepository bundled(Path configurationDirectory) {
        return new Json5ConfigurationRepository(
                configurationDirectory,
                ConfigurationDescriptions.loadEnglish(
                        Json5ConfigurationRepository.class.getClassLoader()));
    }

    public synchronized ClientConfiguration loadClient() {
        Path file = configurationDirectory.resolve(CLIENT_FILE_NAME);
        String existing = read(file);
        ClientConfiguration configuration = existing == null
                ? ClientConfiguration.defaults()
                : parseClient(existing);
        rewriteIfNeeded(file, existing, writeClient(configuration));
        return configuration;
    }

    public synchronized ServerConfiguration loadServer() {
        return serverRepository().load();
    }

    public synchronized void saveClient(ClientConfiguration configuration) {
        rewrite(configurationDirectory.resolve(CLIENT_FILE_NAME), writeClient(
                Objects.requireNonNull(configuration, "configuration")));
    }

    public synchronized void saveServer(ServerConfiguration configuration) {
        serverRepository().save(configuration);
    }

    public String canonicalClient(ClientConfiguration configuration) {
        return writeClient(Objects.requireNonNull(configuration, "configuration"));
    }

    public String canonicalServer(ServerConfiguration configuration) {
        return serverRepository().canonical(configuration);
    }

    private ServerConfigurationRepository serverRepository() {
        return new ServerConfigurationRepository(configurationDirectory, descriptions::get);
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

    private ClientConfiguration parseClient(String document) {
        ClientConfiguration defaults = ClientConfiguration.defaults();
        ScalarScanner scanner = new ScalarScanner(document);
        JsonObject root = parseObject(document).orElse(null);

        boolean titleScreen = booleanValue(
                root,
                scanner,
                defaults.menuPreview().titleScreen(),
                "titleScreen",
                "menuPreview");
        boolean pauseMenu = booleanValue(
                root,
                scanner,
                defaults.menuPreview().pauseMenu(),
                "pauseMenu",
                "menuPreview");
        String dataDirectory = stringValue(
                root,
                scanner,
                defaults.storage().dataDirectory(),
                "dataDirectory",
                "storage");
        if (!ClientConfiguration.validDataDirectory(dataDirectory)) {
            dataDirectory = defaults.storage().dataDirectory();
        }
        return new ClientConfiguration(
                new ClientConfiguration.MenuPreview(titleScreen, pauseMenu),
                new ClientConfiguration.Storage(dataDirectory));
    }

    private static Optional<JsonObject> parseObject(String document) {
        try {
            JsonElement parsed = JsonParser.parseString(document);
            return parsed.isJsonObject()
                    ? Optional.of(parsed.getAsJsonObject())
                    : Optional.empty();
        } catch (RuntimeException malformed) {
            return Optional.empty();
        }
    }

    private static boolean booleanValue(
            JsonObject root,
            ScalarScanner scanner,
            boolean fallback,
            String field,
            String... parents) {
        if (scanner.count(field) != 1) {
            return fallback;
        }
        JsonElement value = nested(root, field, parents);
        if (value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean()) {
            return value.getAsBoolean();
        }
        return scanner.single(field)
                .filter(raw -> raw.equals("true") || raw.equals("false"))
                .map(Boolean::parseBoolean)
                .orElse(fallback);
    }

    private static String stringValue(
            JsonObject root,
            ScalarScanner scanner,
            String fallback,
            String field,
            String... parents) {
        if (scanner.count(field) != 1) {
            return fallback;
        }
        JsonElement value = nested(root, field, parents);
        if (value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) {
            return value.getAsString();
        }
        return scanner.single(field).flatMap(Json5ConfigurationRepository::decodeString)
                .orElse(fallback);
    }

    private static JsonElement nested(JsonObject root, String field, String... parents) {
        if (root == null) {
            return null;
        }
        JsonObject current = root;
        for (String parent : parents) {
            JsonElement child = current.get(parent);
            if (child == null || !child.isJsonObject()) {
                return null;
            }
            current = child.getAsJsonObject();
        }
        return current.get(field);
    }

    private static Optional<String> decodeString(String raw) {
        if (!raw.startsWith("\"") || !raw.endsWith("\"")) {
            return Optional.empty();
        }
        try {
            JsonElement parsed = JsonParser.parseString(raw);
            return parsed.isJsonPrimitive() && parsed.getAsJsonPrimitive().isString()
                    ? Optional.of(parsed.getAsString())
                    : Optional.empty();
        } catch (RuntimeException invalid) {
            return Optional.empty();
        }
    }

    private String writeClient(ClientConfiguration configuration) {
        StringBuilder out = new StringBuilder(768);
        out.append("{\n");
        out.append("  \"menuPreview\": {\n");
        appendComment(out, 4, ConfigurationDescriptions.CLIENT_TITLE_SCREEN);
        out.append("    \"titleScreen\": ").append(configuration.menuPreview().titleScreen())
                .append(",\n\n");
        appendComment(out, 4, ConfigurationDescriptions.CLIENT_PAUSE_MENU);
        out.append("    \"pauseMenu\": ").append(configuration.menuPreview().pauseMenu())
                .append("\n");
        out.append("  },\n\n");
        out.append("  \"storage\": {\n");
        appendComment(out, 4, ConfigurationDescriptions.CLIENT_DATA_DIRECTORY);
        out.append("    \"dataDirectory\": ")
                .append(quote(configuration.storage().dataDirectory())).append("\n");
        out.append("  }\n");
        out.append("}\n");
        return out.toString();
    }

    private void appendComment(StringBuilder out, int indentation, String descriptionKey) {
        String prefix = " ".repeat(indentation) + "// ";
        for (String line : descriptions.get(descriptionKey).split("\\R", -1)) {
            out.append(prefix).append(line).append('\n');
        }
    }

    private static String quote(String value) {
        StringBuilder out = new StringBuilder(value.length() + 2).append('"');
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (character < 0x20) {
                        out.append(String.format(java.util.Locale.ROOT, "\\u%04x", (int) character));
                    } else {
                        out.append(character);
                    }
                }
            }
        }
        return out.append('"').toString();
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
                    configurationDirectory,
                    file.getFileName().toString() + '.',
                    ".tmp");
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
                if (key == null) {
                    key = matcher.group("plainKey");
                }
                values.add(new Scalar(key, matcher.group("value")));
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
                                && !(source.charAt(index) == '*' && source.charAt(index + 1) == '/')) {
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

package com.naocraftlab.skins.runtime.update;

import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import java.io.IOException;
import java.io.StringReader;
import java.net.URI;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

public final class UpdateCatalogParser {
    private static final int MAX_RELEASES = 100;
    private static final int MAX_TARGETS = 64;
    private static final int MAX_VERSIONS_PER_TARGET = 100;
    private static final int MAX_STRING_LENGTH = 512;
    private static final Pattern TARGET_ID = Pattern.compile(
            "^(fabric|forge|neoforge)-([0-9]+(?:\\.[0-9]+){1,2})$");
    private static final Set<String> LOADERS = Set.of("fabric", "forge", "neoforge");

    public UpdateCatalog parse(String document) {
        if (document == null) {
            throw invalid();
        }
        try {
            JsonReader reader = new JsonReader(new StringReader(document));
            UpdateCatalog result = readRoot(reader);
            if (reader.peek() != JsonToken.END_DOCUMENT) {
                throw invalid();
            }
            return result;
        } catch (IOException | RuntimeException failure) {
            throw invalid();
        }
    }

    private static UpdateCatalog readRoot(JsonReader reader) throws IOException {
        reader.beginObject();
        Set<String> fields = new HashSet<>();
        Integer schemaVersion = null;
        String project = null;
        Map<NclVersion, UpdateCatalog.Release> releases = null;
        Map<String, UpdateCatalog.Target> targets = null;
        while (reader.hasNext()) {
            String name = uniqueName(reader, fields);
            switch (name) {
                case "schemaVersion" -> {
                    String raw = reader.nextString();
                    if (!"1".equals(raw)) {
                        throw invalid();
                    }
                    schemaVersion = 1;
                }
                case "project" -> project = boundedString(reader);
                case "releases" -> releases = readReleases(reader);
                case "targets" -> targets = readTargets(reader);
                default -> throw invalid();
            }
        }
        reader.endObject();
        if (!fields.equals(Set.of("schemaVersion", "project", "releases", "targets"))
                || schemaVersion == null || !"nclskins".equals(project)
                || releases == null || targets == null) {
            throw invalid();
        }
        validateReferences(releases, targets);
        return new UpdateCatalog(releases, targets);
    }

    private static Map<NclVersion, UpdateCatalog.Release> readReleases(JsonReader reader)
            throws IOException {
        Map<NclVersion, UpdateCatalog.Release> releases = new LinkedHashMap<>();
        Set<String> names = new HashSet<>();
        reader.beginObject();
        while (reader.hasNext()) {
            String rawVersion = uniqueName(reader, names);
            if (releases.size() >= MAX_RELEASES) {
                throw invalid();
            }
            NclVersion version = NclVersion.parse(rawVersion);
            UpdateCatalog.Release release = readRelease(reader, version);
            if (releases.put(version, release) != null) {
                throw invalid();
            }
        }
        reader.endObject();
        return releases;
    }

    private static UpdateCatalog.Release readRelease(JsonReader reader, NclVersion version)
            throws IOException {
        Set<String> fields = new HashSet<>();
        String channel = null;
        String url = null;
        reader.beginObject();
        while (reader.hasNext()) {
            String name = uniqueName(reader, fields);
            switch (name) {
                case "channel" -> channel = boundedString(reader);
                case "url" -> url = boundedString(reader);
                default -> throw invalid();
            }
        }
        reader.endObject();
        if (!fields.equals(Set.of("channel", "url"))
                || !version.channel().catalogName().equals(channel)) {
            throw invalid();
        }
        String expected = "https://github.com/NaoCraftLab/nclskins/releases/tag/" + version;
        if (!expected.equals(url)) {
            throw invalid();
        }
        return new UpdateCatalog.Release(version.channel(), URI.create(url));
    }

    private static Map<String, UpdateCatalog.Target> readTargets(JsonReader reader)
            throws IOException {
        Map<String, UpdateCatalog.Target> targets = new LinkedHashMap<>();
        Set<String> names = new HashSet<>();
        reader.beginObject();
        while (reader.hasNext()) {
            String targetId = uniqueName(reader, names);
            if (targets.size() >= MAX_TARGETS || !TARGET_ID.matcher(targetId).matches()) {
                throw invalid();
            }
            targets.put(targetId, readTarget(reader, targetId));
        }
        reader.endObject();
        return targets;
    }

    private static UpdateCatalog.Target readTarget(JsonReader reader, String targetId)
            throws IOException {
        Set<String> fields = new HashSet<>();
        String loader = null;
        String minecraftVersion = null;
        List<NclVersion> versions = null;
        reader.beginObject();
        while (reader.hasNext()) {
            String name = uniqueName(reader, fields);
            switch (name) {
                case "loader" -> loader = boundedString(reader);
                case "minecraftVersion" -> minecraftVersion = boundedString(reader);
                case "versions" -> versions = readVersions(reader);
                default -> throw invalid();
            }
        }
        reader.endObject();
        if (!fields.equals(Set.of("loader", "minecraftVersion", "versions"))
                || !LOADERS.contains(loader)
                || !targetId.equals(loader + "-" + minecraftVersion)
                || versions == null) {
            throw invalid();
        }
        return new UpdateCatalog.Target(loader, minecraftVersion, versions);
    }

    private static List<NclVersion> readVersions(JsonReader reader) throws IOException {
        java.util.ArrayList<String> values = new java.util.ArrayList<>();
        reader.beginArray();
        while (reader.hasNext()) {
            if (values.size() >= MAX_VERSIONS_PER_TARGET) {
                throw invalid();
            }
            values.add(boundedString(reader));
        }
        reader.endArray();
        return NclVersion.parseUnique(values);
    }

    private static void validateReferences(
            Map<NclVersion, UpdateCatalog.Release> releases,
            Map<String, UpdateCatalog.Target> targets) {
        Set<NclVersion> referenced = new HashSet<>();
        for (UpdateCatalog.Target target : targets.values()) {
            for (NclVersion version : target.versions()) {
                if (!releases.containsKey(version)) {
                    throw invalid();
                }
                referenced.add(version);
            }
        }
        if (!referenced.equals(releases.keySet())) {
            throw invalid();
        }
    }

    private static String uniqueName(JsonReader reader, Set<String> names) throws IOException {
        String name = bounded(reader.nextName());
        if (!names.add(name)) {
            throw invalid();
        }
        return name;
    }

    private static String boundedString(JsonReader reader) throws IOException {
        if (reader.peek() != JsonToken.STRING) {
            throw invalid();
        }
        return bounded(reader.nextString());
    }

    private static String bounded(String value) {
        if (value.isEmpty() || value.length() > MAX_STRING_LENGTH) {
            throw invalid();
        }
        return value;
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("Invalid NCL update catalog");
    }
}

package com.naocraftlab.skins.server.plugin.common;

import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;


public record SemanticVersion(
        int major,
        int minor,
        int patch,
        PreRelease preRelease,
        int preReleaseNumber) implements Comparable<SemanticVersion> {
    private static final Pattern PATTERN = Pattern.compile(
            "^(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)"
                    + "(?:-(alpha|beta)\\.([1-9][0-9]*))?$");

    public SemanticVersion {
        Objects.requireNonNull(preRelease, "preRelease");
        if (major < 0 || minor < 0 || patch < 0 ||
                (preRelease == PreRelease.STABLE && preReleaseNumber != 0) ||
                (preRelease != PreRelease.STABLE && preReleaseNumber <= 0)) {
            throw new IllegalArgumentException("Invalid semantic version components");
        }
    }

    public static SemanticVersion parse(String value) {
        Matcher matcher = PATTERN.matcher(Objects.requireNonNull(value, "value"));
        if (!matcher.matches()) {
            throw new IllegalArgumentException("Unsupported semantic version: " + value);
        }
        String qualifier = matcher.group(4);
        PreRelease preRelease = qualifier == null
                ? PreRelease.STABLE
                : PreRelease.valueOf(qualifier.toUpperCase(java.util.Locale.ROOT));
        return new SemanticVersion(
                Integer.parseInt(matcher.group(1)),
                Integer.parseInt(matcher.group(2)),
                Integer.parseInt(matcher.group(3)),
                preRelease,
                matcher.group(5) == null ? 0 : Integer.parseInt(matcher.group(5)));
    }

    public boolean isStable() {
        return preRelease == PreRelease.STABLE;
    }

    @Override
    public int compareTo(SemanticVersion other) {
        int result = Integer.compare(major, other.major);
        if (result == 0) {
            result = Integer.compare(minor, other.minor);
        }
        if (result == 0) {
            result = Integer.compare(patch, other.patch);
        }
        if (result == 0) {
            result = Integer.compare(preRelease.rank, other.preRelease.rank);
        }
        if (result == 0 && preRelease != PreRelease.STABLE) {
            result = Integer.compare(preReleaseNumber, other.preReleaseNumber);
        }
        return result;
    }

    @Override
    public String toString() {
        String base = major + "." + minor + "." + patch;
        return preRelease == PreRelease.STABLE
                ? base
                : base + "-" + preRelease.name().toLowerCase(java.util.Locale.ROOT)
                + "." + preReleaseNumber;
    }

    public enum PreRelease {
        ALPHA(0),
        BETA(1),
        STABLE(2);

        private final int rank;

        PreRelease(int rank) {
            this.rank = rank;
        }
    }
}

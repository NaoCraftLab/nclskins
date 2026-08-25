package com.naocraftlab.skins.runtime.update;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class NclVersion implements Comparable<NclVersion> {
    private static final String NUMBER = "(?:0|[1-9][0-9]*)";
    private static final Pattern PATTERN = Pattern.compile(
            "^(" + NUMBER + ")\\.(" + NUMBER + ")\\.(" + NUMBER + ")"
                    + "(?:-(alpha|beta)\\.([1-9][0-9]*))?$");

    private final String value;
    private final BigInteger major;
    private final BigInteger minor;
    private final BigInteger patch;
    private final UpdateChannel channel;
    private final BigInteger prereleaseNumber;

    private NclVersion(
            String value,
            BigInteger major,
            BigInteger minor,
            BigInteger patch,
            UpdateChannel channel,
            BigInteger prereleaseNumber) {
        this.value = value;
        this.major = major;
        this.minor = minor;
        this.patch = patch;
        this.channel = channel;
        this.prereleaseNumber = prereleaseNumber;
    }

    public static NclVersion parse(String value) {
        Objects.requireNonNull(value, "value");
        Matcher matcher = PATTERN.matcher(value);
        if (!matcher.matches()) {
            throw new IllegalArgumentException("Unsupported NCL version");
        }
        String qualifier = matcher.group(4);
        UpdateChannel channel = qualifier == null
                ? UpdateChannel.RELEASE
                : UpdateChannel.valueOf(qualifier.toUpperCase(java.util.Locale.ROOT));
        return new NclVersion(
                value,
                new BigInteger(matcher.group(1)),
                new BigInteger(matcher.group(2)),
                new BigInteger(matcher.group(3)),
                channel,
                qualifier == null ? null : new BigInteger(matcher.group(5)));
    }

    public static List<NclVersion> parseUnique(Collection<String> values) {
        Objects.requireNonNull(values, "values");
        List<NclVersion> result = new ArrayList<>(values.size());
        Set<NclVersion> unique = new HashSet<>();
        for (String value : values) {
            NclVersion parsed = parse(value);
            if (!unique.add(parsed)) {
                throw new IllegalArgumentException("Duplicate NCL version");
            }
            result.add(parsed);
        }
        return List.copyOf(result);
    }

    public UpdateChannel channel() {
        return channel;
    }

    public boolean isNewerThan(NclVersion current) {
        return compareTo(Objects.requireNonNull(current, "current")) > 0;
    }

    @Override
    public int compareTo(NclVersion other) {
        Objects.requireNonNull(other, "other");
        int result = major.compareTo(other.major);
        if (result != 0) {
            return result;
        }
        result = minor.compareTo(other.minor);
        if (result != 0) {
            return result;
        }
        result = patch.compareTo(other.patch);
        if (result != 0) {
            return result;
        }
        result = Integer.compare(channel.precedence(), other.channel.precedence());
        if (result != 0 || channel == UpdateChannel.RELEASE) {
            return result;
        }
        return prereleaseNumber.compareTo(other.prereleaseNumber);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof NclVersion version && value.equals(version.value);
    }

    @Override
    public int hashCode() {
        return value.hashCode();
    }

    @Override
    public String toString() {
        return value;
    }
}

package com.naocraftlab.skins.client;

public final class CpmPublicationName {
    public static final int MAX_LENGTH = 1024 * 1024;
    public static final String FALLBACK = "NCL-Skins-model";

    private CpmPublicationName() { }

    public static String fromBasename(String name) {
        if (name == null) return FALLBACK;
        int end = Math.min(name.length(), MAX_LENGTH);
        if (end < name.length() && end > 0 && Character.isHighSurrogate(name.charAt(end - 1))
                && Character.isLowSurrogate(name.charAt(end))) end--;
        StringBuilder result = new StringBuilder(end);
        for (int index = 0; index < end; index++) {
            char value = name.charAt(index);
            result.append(value < 32 || value == 127 ? ' ' : value);
        }
        return result.toString().isBlank() ? FALLBACK : result.toString();
    }

    public static String fromUrl(String input) {
        try {
            String path = java.net.URI.create(input).getRawPath();
            if (path == null) return FALLBACK;
            String segment = path.substring(path.lastIndexOf('/') + 1);
            String decoded = java.net.URLDecoder.decode(segment.replace("+", "%2B"), java.nio.charset.StandardCharsets.UTF_8);
            return fromBasename(decoded.substring(Math.max(decoded.lastIndexOf('/'), decoded.lastIndexOf('\\')) + 1));
        } catch (IllegalArgumentException invalid) {
            return FALLBACK;
        }
    }

    public static boolean valid(String name) {
        if (name == null || name.isBlank() || name.length() > MAX_LENGTH) return false;
        for (int index = 0; index < name.length(); index++) {
            char value = name.charAt(index);
            if (value < 32 || value == 127) return false;
        }
        return true;
    }
}

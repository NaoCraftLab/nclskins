package com.naocraftlab.skins.runtime;


final class UntrustedDisplayName {
    private static final int MAX_CODE_POINTS = 128;

    private UntrustedDisplayName() {}

    static String sanitize(String value, String fallback) {
        StringBuilder result = new StringBuilder();
        boolean pendingSpace = false;
        if (value != null) {
            for (int offset = 0; offset < value.length() && result.codePointCount(0, result.length()) < MAX_CODE_POINTS; ) {
                int codePoint = value.codePointAt(offset);
                offset += Character.charCount(codePoint);
                if (unsafe(codePoint) || Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint)) {
                    pendingSpace = result.length() > 0;
                    continue;
                }
                if (pendingSpace) {
                    result.append(' ');
                    pendingSpace = false;
                }
                result.appendCodePoint(codePoint);
            }
        }
        String sanitized = result.toString().trim();
        return sanitized.isEmpty() ? fallback : sanitized;
    }

    static String sanitizePngFileName(String value) {
        String candidate = value == null ? "" : value.trim();
        if (candidate.toLowerCase(java.util.Locale.ROOT).endsWith(".png")) {
            candidate = candidate.substring(0, candidate.length() - 4);
        }
        return sanitize(candidate, "Imported skin") + ".png";
    }

    private static boolean unsafe(int codePoint) {
        return Character.isISOControl(codePoint)
                || codePoint >= Character.MIN_SURROGATE && codePoint <= Character.MAX_SURROGATE
                || codePoint == 0x061c
                || codePoint == 0x200e
                || codePoint == 0x200f
                || codePoint >= 0x202a && codePoint <= 0x202e
                || codePoint >= 0x2066 && codePoint <= 0x2069;
    }
}

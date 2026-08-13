package com.naocraftlab.skins.server.plugin.common;

public final class BungeeGuardCompatibility {
    private static final SemanticVersion MINIMUM = SemanticVersion.parse("1.4.0");

    private BungeeGuardCompatibility() {
    }

    public static boolean isSupportedVersion(String version) {
        if ("1.4-SNAPSHOT".equals(version)) {
            return true;
        }
        try {
            return SemanticVersion.parse(version).compareTo(MINIMUM) >= 0;
        } catch (IllegalArgumentException invalidVersion) {
            return false;
        }
    }
}

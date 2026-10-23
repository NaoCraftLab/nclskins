package com.naocraftlab.skins.compat.waveycapes;

public final class WaveyCapesIntegration {
    private static boolean attempted;
    private static boolean closed;
    private static AutoCloseable registration;

    public static void install(String version) {
        if (closed || attempted || !supported(version)) return;
        attempted = true;
        try {
            registration = WaveyCapesSupport.register();
        } catch (RuntimeException | LinkageError incompatible) {
            registration = null;
        }
    }

    static boolean supported(String version) {
        if (version == null) return false;
        String core = "(0|[1-9][0-9]*)";
        var match = java.util.regex.Pattern.compile(core + "\\." + core + "\\." + core
                + "(?:-([0-9A-Za-z.-]+))?(?:\\+[0-9A-Za-z.-]+)?").matcher(version);
        if (!match.matches()) return false;
        try {
            int major = Integer.parseInt(match.group(1));
            int minor = Integer.parseInt(match.group(2));
            int patch = Integer.parseInt(match.group(3));
            return major > 1 || major == 1 && (minor > 11 || minor == 11
                    && (patch > 3 || patch == 3 && match.group(4) == null));
        } catch (NumberFormatException malformed) {
            return false;
        }
    }

    public static void close() {
        if (closed) return;
        closed = true;
        AutoCloseable current = registration;
        registration = null;
        if (current == null) return;
        try { current.close(); }
        catch (RuntimeException | LinkageError incompatible) { }
        catch (Exception impossible) { throw new IllegalStateException(impossible); }
    }

    private WaveyCapesIntegration() {}
}

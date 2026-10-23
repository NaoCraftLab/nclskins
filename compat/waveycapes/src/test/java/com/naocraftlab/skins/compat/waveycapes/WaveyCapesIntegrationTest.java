package com.naocraftlab.skins.compat.waveycapes;

import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WaveyCapesIntegrationTest {
    @Test
    void absentAndOldVersionsDoNotResolveTheTypedBoundary() throws Exception {
        for (String version : new String[]{null, "1.11.1", "1.11.2", "1.11.3-beta", "malformed"}) {
            Isolated isolated = isolated();
            isolated.install.invoke(null, version);
            assertEquals(0, isolated.loads.get(), "Old/absent path must not load foreign signatures");
        }
    }

    @Test
    void supportedButMissingAbiDegradesLocallyAndIsNotRepeated() throws Exception {
        Isolated isolated = isolated();
        isolated.install.invoke(null, "1.11.3");
        assertEquals(1, isolated.loads.get());
        isolated.install.invoke(null, "1.11.3");
        isolated.close.invoke(null);
        isolated.close.invoke(null);
        isolated.install.invoke(null, "1.12.0");
        assertEquals(1, isolated.loads.get());
    }

    @Test
    void terminalCloseRejectsLateActivationBeforeTypedLookup() throws Exception {
        Isolated isolated = isolated();
        isolated.close.invoke(null);
        isolated.install.invoke(null, "1.11.3");
        assertEquals(0, isolated.loads.get());
    }

    @Test
    void minimumIsNotAnExactVersionAllowlist() {
        assertTrue(WaveyCapesIntegration.supported("1.11.3"));
        assertTrue(WaveyCapesIntegration.supported("1.11.3+build.1"));
        assertTrue(WaveyCapesIntegration.supported("1.11.4-beta"));
        assertTrue(WaveyCapesIntegration.supported("1.12.0"));
        assertTrue(WaveyCapesIntegration.supported("2.0.0"));
        assertFalse(WaveyCapesIntegration.supported("1.11.2"));
        assertFalse(WaveyCapesIntegration.supported("1.11.3-"));
        assertFalse(WaveyCapesIntegration.supported("1.11.3+"));
        assertFalse(WaveyCapesIntegration.supported("1.011.3"));
    }

    private static Isolated isolated() throws Exception {
        String name = WaveyCapesIntegration.class.getName();
        byte[] bytes;
        try (var input = WaveyCapesIntegration.class.getResourceAsStream("WaveyCapesIntegration.class")) {
            bytes = input.readAllBytes();
        }
        AtomicInteger loads = new AtomicInteger();
        ClassLoader loader = new ClassLoader(ClassLoader.getPlatformClassLoader()) {
            @Override protected Class<?> findClass(String candidate) throws ClassNotFoundException {
                if (candidate.equals(name)) return defineClass(candidate, bytes, 0, bytes.length);
                if (candidate.equals("com.naocraftlab.skins.compat.waveycapes.WaveyCapesSupport")
                        || candidate.startsWith("dev.tr7zw.")) loads.incrementAndGet();
                throw new ClassNotFoundException(candidate);
            }
        };
        Class<?> integration = loader.loadClass(name);
        return new Isolated(integration.getMethod("install", String.class), integration.getMethod("close"), loads);
    }

    private record Isolated(Method install, Method close, AtomicInteger loads) {}
}

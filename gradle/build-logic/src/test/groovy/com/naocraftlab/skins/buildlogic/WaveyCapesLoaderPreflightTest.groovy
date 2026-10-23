package com.naocraftlab.skins.buildlogic

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.util.jar.*
import static org.junit.jupiter.api.Assertions.*

class WaveyCapesLoaderPreflightTest {
    @TempDir File temporary
    private File jar(String name, Map<String, byte[]> entries) {
        File output = new File(temporary, name)
        new JarOutputStream(new FileOutputStream(output)).withCloseable { jar ->
            entries.each { path, bytes -> jar.putNextEntry(new JarEntry(path)); jar.write(bytes); jar.closeEntry() }
        }
        output
    }
    @Test void recursiveGraphRejectsGraphicsListenersBootCollisionsAndNativeShadows() {
        File nativeJar = jar('native.jar', ['net/minecraft/Native.class': [0] as byte[]])
        assertEquals(1, WaveyLoaderComposition.preflight([nativeJar]).nativeClassCount)
        File duplicate = jar('duplicate.jar', ['net/minecraft/Native.class': [0] as byte[]])
        assertThrows(IllegalArgumentException) { WaveyLoaderComposition.preflight([nativeJar, duplicate]) }
        File boot = jar('boot.jar', ['com/naocraftlab/skins/buildlogic/boot/Hidden.class': [0] as byte[]])
        assertThrows(IllegalArgumentException) { WaveyLoaderComposition.preflight([boot]) }
        ['net.neoforged.fml.startup.GraphicsBootstrapper', 'org.junit.platform.launcher.TestExecutionListener'].each { service ->
            File nested = jar('nested.jar', [("META-INF/services/${service}".toString()): 'Unsafe'.bytes])
            File outer = jar('outer.jar', ['META-INF/jarjar/nested.jar': nested.bytes])
            assertThrows(IllegalArgumentException) { WaveyLoaderComposition.preflight([outer]) }
        }
        File listener = jar('junit-fml-10.jar', [:])
        assertThrows(IllegalArgumentException) { WaveyLoaderComposition.preflight([listener]) }
    }
}

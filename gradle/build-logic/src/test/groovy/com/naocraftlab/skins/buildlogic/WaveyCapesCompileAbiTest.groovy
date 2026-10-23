package com.naocraftlab.skins.buildlogic

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.security.MessageDigest
import java.util.zip.*
import static org.junit.jupiter.api.Assertions.*

class WaveyCapesCompileAbiTest {
    @TempDir Path directory

    @Test
    void extractsOnlyCompanionClassesAndRejectsUnverifiedInputs() {
        File input = directory.resolve('public.jar').toFile()
        input.bytes = zip([
                'dev/tr7zw/waveycapes/Outer.class': [7] as byte[],
                'META-INF/jars/TRansition-fixture.jar': zip([
                        'dev/tr7zw/transition/Fixture.class': [1, 2] as byte[],
                        'fabric.mod.json': '{}'.bytes]),
                'META-INF/jarjar/TRender-fixture.jar': zip([
                        'dev/tr7zw/trender/Fixture.class': [3, 4] as byte[]]),
                'META-INF/jars/unrelated.jar': zip(['other/Foreign.class': [5] as byte[]])])
        File output = directory.resolve('nested.jar').toFile()
        String hash = digest(input.bytes)
        def type = null
        try { type = Class.forName('com.naocraftlab.skins.buildlogic.WaveyCapesCompileAbi') }
        catch (ClassNotFoundException ignored) { }
        assertNotNull(type, 'typed compile ABI extraction is not implemented')
        type.extract(input, hash, output)
        new ZipFile(output).withCloseable { archive ->
            assertEquals(['dev/tr7zw/transition/Fixture.class', 'dev/tr7zw/trender/Fixture.class'] as Set,
                    archive.entries().collect { it.name } as Set)
            assertArrayEquals([1, 2] as byte[], archive.getInputStream(archive.getEntry('dev/tr7zw/transition/Fixture.class')).bytes)
        }
        assertThrows(IllegalStateException) { type.extract(input, '0' * 128, output) }
        input.bytes = zip(['META-INF/jars/TRansition-fixture.jar': zip(['dev/tr7zw/transition/Fixture.class': [1] as byte[]])])
        assertThrows(IllegalStateException) { type.extract(input, digest(input.bytes), output) }
    }

    private static String digest(byte[] bytes) {
        MessageDigest.getInstance('SHA-512').digest(bytes).encodeHex().toString()
    }

    private static byte[] zip(Map<String, byte[]> entries) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream()
        new ZipOutputStream(bytes).withCloseable { archive ->
            entries.each { String name, byte[] value ->
                archive.putNextEntry(new ZipEntry(name))
                archive.write(value)
                archive.closeEntry()
            }
        }
        bytes.toByteArray()
    }
}

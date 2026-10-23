package com.naocraftlab.skins.buildlogic

import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

final class WaveyCapesCompileAbi {
    static void extract(File input, String expectedSha512, File output) {
        if (MessageDigest.getInstance('SHA-512').digest(input.bytes).encodeHex().toString() != expectedSha512) {
            throw new IllegalStateException('WaveyCapes compile ABI public artifact SHA-512 mismatch')
        }
        Map<String, byte[]> classes = new TreeMap<>()
        new ZipFile(input).withCloseable { archive ->
            ['TRansition-', 'TRender-'].each { String prefix ->
                def nested = archive.entries().findAll {
                    it.name.startsWith('META-INF/') && it.name.tokenize('/').last().startsWith(prefix)
                            && it.name.endsWith('.jar')
                }
                if (nested.size() != 1) throw new IllegalStateException('Expected one bundled ' + prefix + ' ABI')
                new ZipInputStream(archive.getInputStream(nested.first())).withCloseable { jar ->
                    def entry
                    while ((entry = jar.nextEntry) != null) {
                        if (!entry.directory && entry.name.startsWith('dev/tr7zw/') && entry.name.endsWith('.class')) {
                            if (classes.put(entry.name, jar.readAllBytes()) != null) {
                                throw new IllegalStateException('Duplicate bundled WaveyCapes ABI class')
                            }
                        }
                    }
                }
            }
        }
        if (classes.empty) throw new IllegalStateException('Empty bundled WaveyCapes ABI')
        output.parentFile.mkdirs()
        new ZipOutputStream(new FileOutputStream(output)).withCloseable { jar ->
            classes.each { String name, byte[] bytes ->
                ZipEntry entry = new ZipEntry(name)
                entry.time = 0
                jar.putNextEntry(entry)
                jar.write(bytes)
                jar.closeEntry()
            }
        }
    }
}

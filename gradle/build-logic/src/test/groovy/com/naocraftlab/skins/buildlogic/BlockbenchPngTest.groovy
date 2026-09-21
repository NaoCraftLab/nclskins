package com.naocraftlab.skins.buildlogic

import groovy.json.JsonOutput
import org.gradle.api.GradleException
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Test

import java.nio.file.Files
import java.nio.file.Path

import static org.junit.jupiter.api.Assertions.*

final class BlockbenchPngTest {
    private final File repository = new File('../..').canonicalFile

    @Test
    void decodesTheSavedCompositeWithoutReadingLayers() {
        Path directory = Files.createTempDirectory('nclskins-blockbench-composite-')
        try {
            byte[] png = BlockbenchPng.decode(new File(
                    repository, 'compat/resources/canonical/src/main/blockbench/icon.bbmodel'))
            File source = directory.resolve('composite.bbmodel').toFile()
            Files.writeString(source.toPath(), JsonOutput.toJson(model(png)))
            assertArrayEquals(png, BlockbenchPng.decode(source))
        } finally {
            directory.toFile().deleteDir()
        }
    }

    @Test
    void rejectsUnsupportedOrAmbiguousModels() {
        Path directory = Files.createTempDirectory('nclskins-blockbench-invalid-')
        try {
            byte[] png = BlockbenchPng.decode(new File(
                    repository, 'compat/resources/canonical/src/main/blockbench/icon.bbmodel'))
            Map valid = model(png)
            [
                [name: 'unsupported', change: { Map it -> it.meta.format_version = '6.0' }],
                [name: 'multiple', change: { Map it -> it.textures << it.textures.first() }],
                [name: 'external', change: { Map it -> it.textures.first().source = 'https://example.invalid/icon.png' }],
                [name: 'dimension', change: { Map it -> it.resolution.width = 1 }]
            ].each { Map fixture ->
                Map invalid = new LinkedHashMap(valid)
                invalid.meta = new LinkedHashMap(valid.meta as Map)
                invalid.resolution = new LinkedHashMap(valid.resolution as Map)
                invalid.textures = (valid.textures as List).collect { new LinkedHashMap(it as Map) }
                fixture.change(invalid)
                File source = directory.resolve("${fixture.name}.bbmodel").toFile()
                Files.writeString(source.toPath(), JsonOutput.toJson(invalid))
                GradleException error = assertThrows(GradleException) { BlockbenchPng.decode(source) }
                assertTrue(error.message.contains(source.toString()))
            }
        } finally {
            directory.toFile().deleteDir()
        }
    }

    @Test
    void generatesRelativePngsAndSynchronizesDeletedSources() {
        Path directory = Files.createTempDirectory('nclskins-blockbench-task-')
        try {
            Path source = directory.resolve('source')
            Path output = directory.resolve('output')
            Files.createDirectories(source.resolve('assets/nclskins'))
            byte[] png = BlockbenchPng.decode(new File(
                    repository, 'compat/resources/canonical/src/main/blockbench/icon.bbmodel'))
            File input = source.resolve('assets/nclskins/icon.bbmodel').toFile()
            Files.writeString(input.toPath(), JsonOutput.toJson(model(png)))
            def project = ProjectBuilder.builder().withProjectDir(directory.toFile()).build()
            BlockbenchPng.GenerateTask task = project.tasks.create('generate', BlockbenchPng.GenerateTask)
            task.sourceDirectory.set(source.toFile())
            task.outputDirectory.set(output.toFile())
            task.generate()
            assertArrayEquals(png, Files.readAllBytes(output.resolve('assets/nclskins/icon.png')))
            assertFalse(Files.exists(output.resolve('assets/nclskins/icon.bbmodel')))
            assertTrue(input.delete())
            task.generate()
            assertFalse(Files.exists(output.resolve('assets/nclskins/icon.png')))
            BlockbenchPng.GenerateTask unsafe = project.tasks.create('unsafe', BlockbenchPng.GenerateTask)
            unsafe.sourceDirectory.set(source.toFile())
            unsafe.outputDirectory.set(source.resolve('generated').toFile())
            assertThrows(GradleException) { unsafe.generate() }
        } finally {
            directory.toFile().deleteDir()
        }
    }

    private static Map model(byte[] png) {
        int width = ((png[16] & 0xff) << 24) | ((png[17] & 0xff) << 16) | ((png[18] & 0xff) << 8) | (png[19] & 0xff)
        int height = ((png[20] & 0xff) << 24) | ((png[21] & 0xff) << 16) | ((png[22] & 0xff) << 8) | (png[23] & 0xff)
        [
            meta      : [format_version: '5.0', model_format: 'image'],
            resolution: [width: width, height: height],
            textures  : [[internal: true, width: width, height: height,
                          source: 'data:image/png;base64,' + Base64.encoder.encodeToString(png)]],
            layers    : [[name: 'visible'], [name: 'hidden', visibility: false]]
        ]
    }
}

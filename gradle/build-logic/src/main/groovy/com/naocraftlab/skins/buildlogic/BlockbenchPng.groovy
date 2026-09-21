package com.naocraftlab.skins.buildlogic

import groovy.json.JsonSlurper
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.*

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

final class BlockbenchPng {
    private static final byte[] PNG_SIGNATURE = [0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a] as byte[]

    static byte[] decode(File source) {
        Object raw
        try {
            raw = new JsonSlurper().parse(source)
        } catch (Exception error) {
            throw invalid(source, 'invalid JSON')
        }
        if (!(raw instanceof Map)) throw invalid(source, 'root must be an object')
        Map model = raw as Map
        Map meta = model.meta instanceof Map ? model.meta as Map : [:]
        if (meta.format_version != '5.0' || meta.model_format != 'image') {
            throw invalid(source, 'must use Blockbench image format version 5.0')
        }
        if (!(model.textures instanceof List) || (model.textures as List).size() != 1) {
            throw invalid(source, 'must contain exactly one texture')
        }
        Map texture = (model.textures as List).first() instanceof Map ? (model.textures as List).first() as Map : [:]
        if (texture.internal != true || !(texture.source instanceof String)) {
            throw invalid(source, 'must contain one internal embedded texture')
        }
        String data = texture.source as String
        if (!data.startsWith('data:image/png;base64,')) throw invalid(source, 'texture source must be embedded PNG data')
        byte[] bytes
        try {
            bytes = Base64.decoder.decode(data.substring('data:image/png;base64,'.length()))
        } catch (IllegalArgumentException error) {
            throw invalid(source, 'texture source is not valid Base64')
        }
        if (bytes.length < 24 || !Arrays.equals(bytes[0..7] as byte[], PNG_SIGNATURE) ||
                new String(bytes, 12, 4, 'ISO-8859-1') != 'IHDR') {
            throw invalid(source, 'texture source is not a PNG')
        }
        int width = readInt(bytes, 16)
        int height = readInt(bytes, 20)
        Map resolution = model.resolution instanceof Map ? model.resolution as Map : [:]
        if (width <= 0 || height <= 0 || texture.width != width || texture.height != height ||
                resolution.width != width || resolution.height != height) {
            throw invalid(source, 'canvas, texture and PNG dimensions must agree')
        }
        bytes
    }

    private static int readInt(byte[] bytes, int offset) {
        ((bytes[offset] & 0xff) << 24) | ((bytes[offset + 1] & 0xff) << 16) |
                ((bytes[offset + 2] & 0xff) << 8) | (bytes[offset + 3] & 0xff)
    }

    private static GradleException invalid(File source, String message) {
        new GradleException("Invalid Blockbench resource ${source}: ${message}")
    }

    static class GenerateTask extends DefaultTask {
        @InputDirectory
        @PathSensitive(PathSensitivity.RELATIVE)
        final DirectoryProperty sourceDirectory = project.objects.directoryProperty()

        @OutputDirectory
        final DirectoryProperty outputDirectory = project.objects.directoryProperty()

        @TaskAction
        void generate() {
            Path sourceRoot = sourceDirectory.get().asFile.toPath().toRealPath()
            Path outputRoot = outputDirectory.get().asFile.canonicalFile.toPath()
            if (outputRoot.startsWith(sourceRoot)) {
                throw new GradleException("Blockbench output must not be inside authored source: ${outputRoot}")
            }
            File staging = new File(temporaryDir, 'resources')
            if (staging.exists() && !staging.deleteDir()) {
                throw new GradleException("Cannot clear Blockbench staging output: ${staging}")
            }
            staging.mkdirs()
            Path stagingRoot = staging.toPath()
            Files.walk(sourceRoot).withCloseable { stream ->
                stream.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith('.bbmodel') }.forEach { Path input ->
                    Path relative = sourceRoot.relativize(input)
                    Path output = stagingRoot.resolve(relative.toString().replaceFirst('\\.bbmodel$', '.png')).normalize()
                    if (!output.startsWith(stagingRoot)) throw new GradleException("Unsafe Blockbench path: ${input}")
                    Files.createDirectories(output.parent)
                    Files.write(output, decode(input.toFile()))
                }
            }
            File output = outputRoot.toFile()
            if (output.exists() && !output.deleteDir()) {
                throw new GradleException("Cannot clear Blockbench output: ${output}")
            }
            Files.createDirectories(outputRoot.parent)
            Files.move(stagingRoot, outputRoot, StandardCopyOption.REPLACE_EXISTING)
        }
    }
}

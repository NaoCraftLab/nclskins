package com.naocraftlab.skins.buildlogic

import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Test

import java.nio.file.Files

import static org.junit.jupiter.api.Assertions.*

final class PreviewLoadingResourceTest {
    private final File repository = new File('../..').canonicalFile
    private static final String RESOURCE = 'assets/nclskins/textures/gui/icons/status/preview_loading'

    @Test
    void canonicalStripPreservesEveryClockwiseFrameAndNearestNeighbourPixel() {
        File source = new File(repository, "compat/resources/canonical/src/main/blockbench/${RESOURCE}.bbmodel")
        assertTrue(source.isFile(), 'canonical preview loading resource must exist')
        byte[] png = BlockbenchPng.decode(source)
        def image = javax.imageio.ImageIO.read(new ByteArrayInputStream(png))
        assertEquals(32, image.width)
        assertEquals(256, image.height)
        int[] coverage = [16, 14, 12, 10, 8, 6, 4, 2]
        int[][] bayer = [[0, 8, 2, 10], [12, 4, 14, 6], [3, 11, 1, 9], [15, 7, 13, 5]]
        int[][] squares = [[0, 0], [12, 0], [24, 0], [24, 12], [24, 24], [12, 24], [0, 24], [0, 12]]
        for (int frame = 0; frame < 8; frame++) {
            for (int y = 0; y < 32; y++) {
                for (int x = 0; x < 32; x++) {
                    int expected = 0
                    for (int square = 0; square < squares.length; square++) {
                        int[] origin = squares[square]
                        if (x >= origin[0] && x < origin[0] + 8 && y >= origin[1] && y < origin[1] + 8) {
                            int tail = Math.floorMod(frame - square, 8)
                            int rank = bayer[(y - origin[1]).intdiv(2)][(x - origin[0]).intdiv(2)]
                            expected = rank < coverage[tail] ? -1 : 0
                        }
                    }
                    assertEquals(expected, image.getRGB(x, frame * 32 + y), "frame=${frame} x=${x} y=${y}")
                    assertEquals(image.getRGB(x.intdiv(2) * 2, frame * 32 + y.intdiv(2) * 2),
                            image.getRGB(x, frame * 32 + y), '16px sampling must preserve each 2x2 block')
                }
            }
        }
        def layer = new groovy.json.JsonSlurper().parse(source).textures[0].layers[0].data_url
        assertTrue(layer.startsWith('data:image/png;base64,'))
        assertArrayEquals(png, Base64.decoder.decode(layer.substring('data:image/png;base64,'.length())))
    }

    @Test
    void generationSynchronizesTheSingleSemanticStripWithoutDraftInputs() {
        File source = new File(repository, "compat/resources/canonical/src/main/blockbench/${RESOURCE}.bbmodel")
        assertTrue(source.isFile(), 'canonical preview loading resource must exist')
        def directory = Files.createTempDirectory('nclskins-preview-loading-resource-')
        try {
            def input = directory.resolve('source')
            def authored = input.resolve(RESOURCE + '.bbmodel')
            Files.createDirectories(authored.parent)
            Files.copy(source.toPath(), authored)
            def project = ProjectBuilder.builder().withProjectDir(directory.toFile()).build()
            def task = project.tasks.create('generatePreviewLoading', BlockbenchPng.GenerateTask)
            def output = directory.resolve('output')
            task.sourceDirectory.set(input.toFile())
            task.outputDirectory.set(output.toFile())
            task.generate()
            def generated = output.resolve(RESOURCE + '.png')
            byte[] expected = BlockbenchPng.decode(source)
            assertArrayEquals(expected, Files.readAllBytes(generated))
            Files.writeString(output.resolve('obsolete.pixel.json'), 'obsolete')
            task.generate()
            assertArrayEquals(expected, Files.readAllBytes(generated))
            assertFalse(Files.exists(output.resolve('obsolete.pixel.json')))
            Files.walk(output).withCloseable { paths ->
                assertEquals([generated], paths.filter { Files.isRegularFile(it) }.toList())
            }
            Files.delete(authored)
            task.generate()
            assertFalse(Files.exists(generated))
        } finally {
            directory.toFile().deleteDir()
        }
    }
}

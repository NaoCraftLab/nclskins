package com.naocraftlab.skins.core.png;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.io.ByteArrayInputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class CpmCarrierPreservationTest {
    @Test
    void modernAdmissionPreservesTransparentCarrierBytes() throws Exception {
        PngValidator png = new PngValidator();
        byte[] a = fixture("classic-a");
        byte[] b = fixture("classic-b");
        assertArrayEquals(a, png.projectImport(a).pngBytes());
        assertArrayEquals(b, png.projectStoredRender(b).pngBytes());
        assertNotEquals(png.rawSkinPixelSha256(a), png.rawSkinPixelSha256(b));
        assertNotEquals(png.renderSha256(a), png.renderSha256(b));
        var raster = ImageIO.read(new ByteArrayInputStream(png.projectImport(a).pngBytes()));
        assertEquals(0, raster.getRGB(0, 0) >>> 24);
        assertEquals(83, raster.getRGB(0, 0) & 255);
    }

    private static byte[] fixture(String name) throws Exception {
        try (var input = CpmCarrierPreservationTest.class.getResourceAsStream("/cpm/" + name + ".png")) {
            return java.util.Objects.requireNonNull(input).readAllBytes();
        }
    }

    @Test
    void identityIgnoresEncodingAndUnusedTransparentTailButDistinguishesRepairAndVariant() throws Exception {
        PngValidator png = new PngValidator();
        byte[] a = fixture("classic-a");
        var raster = ImageIO.read(new ByteArrayInputStream(a));
        var output = new java.io.ByteArrayOutputStream();
        ImageIO.write(raster, "png", output);
        assertEquals(png.renderSha256(a), png.renderSha256(output.toByteArray()));
        raster.setRGB(7, 0, 0x00123456);
        output.reset();
        ImageIO.write(raster, "png", output);
        assertEquals(png.renderSha256(a), png.renderSha256(output.toByteArray()));
        assertNotEquals(png.renderSha256(a), png.renderSha256(fixture("slim-a")));
        assertNotEquals(png.renderSha256(a), png.renderSha256(fixture("classic-bad-checksum")));
        assertNotEquals(png.renderSha256(a), png.renderSha256(com.naocraftlab.skins.core.test.TestPng.create(64, 64)));
    }
}

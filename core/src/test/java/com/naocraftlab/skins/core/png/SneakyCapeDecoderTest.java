package com.naocraftlab.skins.core.png;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SneakyCapeDecoderTest {
    private static final int[] MARKERS = {0xfffff42f, 0xffffffff, 0xff9c59d1, 0xff292929};
    private static final int[][] RECTANGLES = {
            {56,16,1,1,8,16}, {62,0,9,1,2,8}, {60,0,9,9,2,8},
            {56,32,12,1,8,16}, {58,0,20,1,2,8}, {56,0,20,9,2,8},
            {39,0,0,1,1,8}, {38,0,0,9,1,8}, {37,0,11,1,1,8}, {36,0,11,9,1,8},
            {0,48,1,0,4,1}, {12,48,5,0,6,1}, {0,49,11,0,4,1}, {12,49,15,0,6,1},
            {0,0,36,2,8,8}, {24,0,44,2,2,8}, {26,0,36,10,10,8},
            {44,48,36,18,8,4}, {62,48,44,18,2,4},
            {18,48,34,2,2,4}, {28,48,34,6,2,4}, {30,48,34,10,2,4},
            {32,48,22,10,1,4}, {33,48,22,14,1,4}, {34,48,22,18,1,4},
            {0,50,30,0,4,2}, {12,50,34,0,6,2}
    };

    @Test
    void exactRectanglesPreserveAlphaAndLeaveUnusedAtlasTransparent() throws Exception {
        BufferedImage skin = markedSkin();
        for (int index = 0; index < RECTANGLES.length; index++) {
            int[] rectangle = RECTANGLES[index];
            int argb = ((index % 3 + 1) * 0x40 << 24) | (index + 1) * 0x010101;
            for (int y = 0; y < rectangle[5]; y++) {
                for (int x = 0; x < rectangle[4]; x++) {
                    skin.setRGB(rectangle[0] + x, rectangle[1] + y, argb);
                }
            }
        }
        BufferedImage cape = ImageIO.read(new ByteArrayInputStream(
                new SneakyCapeDecoder().decode(png(skin)).orElseThrow().bytes()));
        assertEquals(64, cape.getWidth());
        assertEquals(32, cape.getHeight());
        for (int[] rectangle : RECTANGLES) {
            for (int y = 0; y < rectangle[5]; y++) {
                for (int x = 0; x < rectangle[4]; x++) {
                    assertEquals(skin.getRGB(rectangle[0] + x, rectangle[1] + y),
                            cape.getRGB(rectangle[2] + x, rectangle[3] + y));
                }
            }
        }
        assertEquals(0, cape.getRGB(63, 31));
        assertEquals(MARKERS[0], skin.getRGB(60, 48));
        assertTrue(new SneakyCapeDecoder().decode(png(skin)).orElseThrow().hasElytra());
    }

    @Test
    void markerWithoutPayloadStillProvidesAnAtlas() throws Exception {
        var cape = new SneakyCapeDecoder().decode(png(markedSkin())).orElseThrow();
        assertFalse(cape.hasElytra());
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(cape.bytes()));
        assertEquals(0, image.getRGB(1, 1));
    }

    @Test
    void sourceAlphaAtVanillaForcedRegionsRemainsExact() throws Exception {
        BufferedImage skin = markedSkin();
        skin.setRGB(56, 16, 0x40112233);
        skin.setRGB(0, 0, 0x00000000);
        BufferedImage cape = ImageIO.read(new ByteArrayInputStream(
                new SneakyCapeDecoder().decode(png(skin)).orElseThrow().bytes()));
        assertEquals(0x40112233, cape.getRGB(1, 1));
        assertEquals(0x00000000, cape.getRGB(36, 2));
    }

    @Test
    void rejectsEveryIncompleteMarkerAndOtherDimensions() throws Exception {
        SneakyCapeDecoder decoder = new SneakyCapeDecoder();
        for (int index = 0; index < MARKERS.length; index++) {
            BufferedImage skin = markedSkin();
            skin.setRGB(60, 48 + index, MARKERS[index] ^ 0x01000000);
            assertTrue(decoder.decode(png(skin)).isEmpty());
        }
        assertTrue(decoder.decode(png(new BufferedImage(64, 32, BufferedImage.TYPE_INT_ARGB))).isEmpty());
        assertTrue(decoder.decode(png(new BufferedImage(128, 128, BufferedImage.TYPE_INT_ARGB))).isEmpty());
    }

    private static BufferedImage markedSkin() {
        BufferedImage skin = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
        for (int index = 0; index < MARKERS.length; index++) skin.setRGB(60, 48 + index, MARKERS[index]);
        return skin;
    }

    private static byte[] png(BufferedImage image) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(image, "png", output);
        return output.toByteArray();
    }
}

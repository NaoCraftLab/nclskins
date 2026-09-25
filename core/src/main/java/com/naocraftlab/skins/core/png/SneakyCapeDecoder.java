package com.naocraftlab.skins.core.png;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Optional;

public final class SneakyCapeDecoder {
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

    public boolean hasMarker(BufferedImage skin) {
        if (skin == null || skin.getWidth() != 64 || skin.getHeight() != 64) return false;
        for (int index = 0; index < MARKERS.length; index++) {
            if (skin.getRGB(60, 48 + index) != MARKERS[index]) return false;
        }
        return true;
    }

    public Optional<BufferedImage> extract(BufferedImage skin) {
        if (!hasMarker(skin)) return Optional.empty();
        BufferedImage cape = new BufferedImage(64, 32, BufferedImage.TYPE_INT_ARGB);
        for (int[] rectangle : RECTANGLES) {
            int[] pixels = skin.getRGB(rectangle[0], rectangle[1], rectangle[4], rectangle[5],
                    null, 0, rectangle[4]);
            cape.setRGB(rectangle[2], rectangle[3], rectangle[4], rectangle[5],
                    pixels, 0, rectangle[4]);
        }
        return Optional.of(cape);
    }

    public Optional<PngValidator.CapePng> decode(byte[] skinBytes) throws PngValidationException {
        if (skinBytes.length >= 24) {
            ByteBuffer header = ByteBuffer.wrap(skinBytes);
            if (header.getInt(16) != 64 || header.getInt(20) != 64) return Optional.empty();
        }
        PngValidator validator = new PngValidator();
        PngInfo info = validator.validate(skinBytes);
        if (info.width() != 64 || info.height() != 64) return Optional.empty();
        try {
            BufferedImage skin = ImageIO.read(new ByteArrayInputStream(skinBytes));
            return decode(skin);
        } catch (IOException exception) {
            throw new IllegalStateException("Validated skin could not be decoded", exception);
        }
    }

    public Optional<PngValidator.CapePng> decode(BufferedImage skin) throws PngValidationException {
        Optional<BufferedImage> cape = extract(skin);
        if (cape.isEmpty()) return Optional.empty();
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            if (!ImageIO.write(cape.orElseThrow(), "png", output)) {
                throw new IllegalStateException("PNG encoder is unavailable");
            }
            return Optional.of(new PngValidator().projectCanonicalCape(output.toByteArray()));
        } catch (IOException exception) {
            throw new IllegalStateException("Atlas could not be encoded", exception);
        }
    }
}

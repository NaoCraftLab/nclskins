package com.naocraftlab.skins.core.test;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import javax.imageio.ImageIO;

public final class TestPng {
    private TestPng() {}

    public static byte[] create(int width, int height) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setColor(new Color(0x31, 0x86, 0xd8, 0xff));
            graphics.fillRect(0, 0, width, height);
            graphics.setColor(new Color(0xff, 0x90, 0x30, 0xff));
            graphics.fillRect(0, 0, Math.max(1, width / 2), Math.max(1, height / 2));
        } finally {
            graphics.dispose();
        }
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            if (!ImageIO.write(image, "png", output)) {
                throw new IllegalStateException("PNG writer is unavailable");
            }
            return output.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException("Could not build test PNG", exception);
        }
    }
}

package com.naocraftlab.skins.runtime;

import com.naocraftlab.skins.core.png.PngValidator;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class OptifineCapeReaderTest {
    @Test
    void publicReadUsesOnlyCanonicalNameAndNormalizesValidPng() throws Exception {
        byte[] png = capePng();
        AtomicReference<URI> requested = new AtomicReference<>();
        var reader = new OptifineCapeReader((uri, timeout, maxBytes) -> {
            requested.set(uri);
            assertEquals(Duration.ofSeconds(10), timeout);
            assertEquals(PngValidator.DEFAULT_MAX_BYTES, maxBytes);
            return new OptifineCapeReader.Response(200, png);
        }, new PngValidator());
        var result = reader.read("Player_123");
        assertEquals(OptifineCapeReader.Kind.PRESENT, result.kind());
        assertEquals(URI.create("https://optifine.net/capes/Player_123.png"), requested.get());
        assertArrayEquals(new PngValidator().projectImportedCape(png).bytes(), result.cape().bytes());
        assertNull(result.failure());
    }

    @Test
    void absentAndFailuresRemainDistinct() throws Exception {
        byte[] png = capePng();
        assertEquals(OptifineCapeReader.Kind.ABSENT, reader(404, new byte[0]).read("Player").kind());
        for (int status : new int[] {301, 302, 401, 403, 500}) {
            assertEquals(OptifineCapeReader.Failure.HTTP, reader(status, png).read("Player").failure());
        }
        assertEquals(OptifineCapeReader.Failure.INVALID_PNG,
                reader(200, new byte[] {1, 2, 3}).read("Player").failure());
        assertEquals(OptifineCapeReader.Failure.OVERSIZED,
                reader(200, new byte[PngValidator.DEFAULT_MAX_BYTES + 1]).read("Player").failure());
        assertEquals(OptifineCapeReader.Failure.NETWORK,
                new OptifineCapeReader((uri, timeout, maxBytes) -> {
                    throw new IOException("timeout");
                }, new PngValidator()).read("Player").failure());
        assertEquals(OptifineCapeReader.Failure.INVALID_NAME,
                reader(200, png).read("bad/name").failure());
    }

    @Test
    void optifineDimensionsArePaddedBeforePublication() throws Exception {
        BufferedImage image = new BufferedImage(46, 22, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(45, 21, 0x80112233);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(image, "png", output);
        var result = reader(200, output.toByteArray()).read("Player");
        assertEquals(OptifineCapeReader.Kind.PRESENT, result.kind());
        BufferedImage normalized = ImageIO.read(new java.io.ByteArrayInputStream(result.cape().bytes()));
        assertEquals(64, normalized.getWidth());
        assertEquals(32, normalized.getHeight());
        assertEquals(0x80112233, normalized.getRGB(45, 21));
        assertEquals(0, normalized.getRGB(46, 21));
    }

    @Test
    void doubleResolutionCapesRetainPixelsAlphaAndOnePixelElytra() throws Exception {
        for (int width : new int[] {92, 128}) {
            BufferedImage original = new BufferedImage(width, width == 92 ? 44 : 64,
                    BufferedImage.TYPE_INT_ARGB);
            original.setRGB(3, 7, 0x80112233);
            original.setRGB(4, 7, 0x44224466);
            original.setRGB(48, 0, 0x01010203);
            original.setRGB(width - 1, original.getHeight() - 1, 0x7f556677);
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            ImageIO.write(original, "png", output);

            var result = reader(200, output.toByteArray()).read("Player");
            assertEquals(OptifineCapeReader.Kind.PRESENT, result.kind());
            assertEquals(true, result.cape().hasElytra());
            BufferedImage canonical = ImageIO.read(new ByteArrayInputStream(result.cape().bytes()));
            assertEquals(128, canonical.getWidth());
            assertEquals(64, canonical.getHeight());
            assertEquals(0x80112233, canonical.getRGB(3, 7));
            assertEquals(0x44224466, canonical.getRGB(4, 7));
            assertEquals(0x01010203, canonical.getRGB(48, 0));
            assertEquals(0x7f556677, canonical.getRGB(width - 1, original.getHeight() - 1));
            if (width == 92) assertEquals(0, canonical.getRGB(92, 43));
        }
    }

    @Test
    void nonSuccessStatusDoesNotWaitForOrBufferItsBody() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/cape", exchange -> {
            exchange.sendResponseHeaders(404, 0);
            try {
                Thread.sleep(1000);
                exchange.getResponseBody().write(new byte[4096]);
            } catch (IOException | InterruptedException ignored) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        server.start();
        try {
            URI uri = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/cape");
            var result = new OptifineCapeReader.HttpTransport().get(uri, Duration.ofMillis(500), 16);
            assertEquals(404, result.status());
            assertEquals(0, result.bytes().length);
        } finally {
            server.stop(0);
        }
    }

    private static OptifineCapeReader reader(int status, byte[] body) {
        return new OptifineCapeReader((uri, timeout, maxBytes) ->
                new OptifineCapeReader.Response(status, body), new PngValidator());
    }

    private static byte[] capePng() throws IOException {
        BufferedImage image = new BufferedImage(64, 32, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(0, 0, 0x7f123456);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(image, "png", output);
        return output.toByteArray();
    }
}

package com.naocraftlab.skins.core.png;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.nio.file.Files;
import static org.junit.jupiter.api.Assertions.*;

class GalleryCapeComparisonTest {
    @TempDir Path directory;
    private int sequence;

    private PreparedCapeAsset prepare(BufferedImage image) throws Exception {
        String name = "synthetic-" + sequence++;
        Path png = directory.resolve(name + ".png");
        ImageIO.write(image, "png", png.toFile());
        return new CapeAssetDecoder().prepare(png, directory.resolve(name + ".frames"));
    }

    @Test void unusedCanvasDoesNotSplitStaticOrFullAnimatedTimeline() throws Exception {
        for (int frames : new int[]{1, 3}) {
            BufferedImage a = new BufferedImage(64, 32 * frames, BufferedImage.TYPE_INT_ARGB);
            a.setRGB(2, 3, 0xff123456);
            PreparedCapeAsset left = prepare(a);
            a.setRGB(60, 32 * frames - 1, 0xffabcdef);
            PreparedCapeAsset right = prepare(a);
            assertNotEquals(left.visualSha256(), right.visualSha256());
            assertEquals(GalleryCapeComparison.key(left), GalleryCapeComparison.key(right));
            if (frames == 1) assertEquals(GalleryCapeComparison.key(left),
                    GalleryCapeComparison.key(Files.readAllBytes(right.backing().resolveSibling("synthetic-1.png"))));
        }
    }

    @Test void everyCapeAndWingFaceAndLaterFramesRemainSignificant() throws Exception {
        for (int[] point : new int[][]{{1,0},{20,0},{0,1},{21,16},{24,0},{43,1},{22,2},{45,21}}) {
            BufferedImage a = new BufferedImage(64, 96, BufferedImage.TYPE_INT_ARGB);
            PreparedCapeAsset left = prepare(a);
            a.setRGB(point[0], point[1] + 64, 0xff123456);
            assertNotEquals(GalleryCapeComparison.key(left), GalleryCapeComparison.key(prepare(a)));
        }
    }

    @Test void fullTimelineComparisonStreamsRowsWithoutMaterializingFrames() throws Exception {
        PreparedCapeAsset asset = prepare(new BufferedImage(64, 32 * 64, BufferedImage.TYPE_INT_ARGB));
        var rows = new java.util.concurrent.atomic.AtomicInteger();
        var frames = new java.util.HashSet<Integer>();
        try (var source = asset.openBackingLease()) {
            var streamed = new PreparedCapeAsset.BackingLease() {
                public void readRow(int frame, int row, byte[] rgba) throws java.io.IOException {
                    assertEquals(64 * 4, rgba.length);
                    rows.incrementAndGet(); frames.add(frame);
                    source.readRow(frame, row, rgba);
                }
                public byte[] readFrame(int index) { throw new AssertionError("Full frame allocation"); }
                public PreparedCapeAsset.Thumbnail readThumbnail(int index) { throw new AssertionError("Poster shortcut"); }
                public byte[] framePng(int index) { throw new AssertionError("PNG materialization"); }
                public void verifyAll() throws java.io.IOException { source.verifyAll(); }
                public void close() {}
            };
            assertEquals(GalleryCapeComparison.key(asset), GalleryCapeComparison.key(asset, streamed));
        }
        assertEquals(64 * 32, rows.get());
        assertEquals(64, frames.size());
    }

    @Test void originalResolutionAndVisibleAlphaRemainSignificant() throws Exception {
        PreparedCapeAsset small = prepare(new BufferedImage(64, 32, BufferedImage.TYPE_INT_ARGB));
        PreparedCapeAsset large = prepare(new BufferedImage(128, 64, BufferedImage.TYPE_INT_ARGB));
        assertNotEquals(GalleryCapeComparison.key(small), GalleryCapeComparison.key(large));
        BufferedImage image = new BufferedImage(64, 32, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(2, 3, 0x00123456);
        assertEquals(GalleryCapeComparison.key(small), GalleryCapeComparison.key(prepare(image)));
        image.setRGB(2, 3, 0x01123456);
        assertNotEquals(GalleryCapeComparison.key(small), GalleryCapeComparison.key(prepare(image)));
    }
}

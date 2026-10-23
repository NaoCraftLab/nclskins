package com.naocraftlab.skins.core.png;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class PreparedCapeThumbnailTest {
    @TempDir Path directory;

    @Test
    void maximumSheetColdPreviewReadsOnlyFirstFrameAndTailIsVerifiedLater() throws Exception {
        int width = 2048, height = 1024;
        long frameBytes = (long) width * height * 4;
        String zeroFrame = zeroHash(frameBytes);
        var metadata = new PreparedCapeAsset.Metadata(width, height,
                java.util.Collections.nCopies(64, zeroFrame),
                java.util.Collections.nCopies(64, 100), 0,
                "0".repeat(64), false, "0".repeat(64));
        AtomicLong readBytes = new AtomicLong();
        var thumbnail = CapeAssetDecoder.thumbnail(metadata, 0, (position, row) -> {
            readBytes.addAndGet(row.length);
            Arrays.fill(row, (byte) 0);
        });
        assertEquals(frameBytes, readBytes.get());
        assertEquals(512, thumbnail.width());
        assertEquals(256, thumbnail.height());
        assertEquals(512 * 256 * 4, thumbnail.rgba8().length);

        Path backing = directory.resolve("max.frames");
        try (RandomAccessFile file = new RandomAccessFile(backing.toFile(), "rw")) {
            file.setLength(frameBytes * 64);
            file.seek(frameBytes + 3);
            file.write(1);
        }
        PreparedCapeAsset asset = CapeAssetDecoder.restoreThumbnail(metadata.descriptorBytes(), backing);
        try (var lease = asset.openThumbnailLease()) {
            assertArrayEquals(thumbnail.rgba8(), lease.readThumbnail(0).rgba8());
            assertThrows(IOException.class, lease::verifyAll);
            assertThrows(IOException.class, () -> lease.readFrame(0));
        }
    }

    @Test
    void thumbnailKeepsNearestRgbaAndRejectsCorruptOrTruncatedBacking() throws Exception {
        int width = 1024, height = 512;
        byte[] frame = new byte[width * height * 4];
        for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) {
            int at = (y * width + x) * 4;
            frame[at] = (byte) x;
            frame[at + 1] = (byte) y;
            frame[at + 2] = (byte) (x + y);
            frame[at + 3] = (byte) ((x + y) & 255);
        }
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(frame));
        var metadata = new PreparedCapeAsset.Metadata(width, height, List.of(hash),
                List.of(100), 0, "0".repeat(64), false, hash);
        Path backing = directory.resolve("rgba.frames");
        Files.write(backing, frame);
        PreparedCapeAsset asset = CapeAssetDecoder.restoreThumbnail(metadata.descriptorBytes(), backing);
        try (var lease = asset.openThumbnailLease()) {
            var thumb = lease.readThumbnail(0);
            assertEquals(512, thumb.width());
            assertEquals(256, thumb.height());
            int target = (19 * 512 + 37) * 4;
            int source = (38 * width + 74) * 4;
            assertArrayEquals(Arrays.copyOfRange(frame, source, source + 4),
                    Arrays.copyOfRange(thumb.rgba8(), target, target + 4));
        }
        frame[3] ^= 1;
        Files.write(backing, frame);
        try (var lease = asset.openThumbnailLease()) {
            assertThrows(IOException.class, () -> lease.readThumbnail(0));
        }
        Files.write(backing, new byte[1]);
        assertThrows(IOException.class, asset::openThumbnailLease);
        byte[] descriptor = metadata.descriptorBytes();
        descriptor[0] = 0;
        assertThrows(IOException.class, () -> CapeAssetDecoder.restoreThumbnail(descriptor, backing));
    }

    private static String zeroHash(long bytes) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] block = new byte[64 * 1024];
        for (long remaining = bytes; remaining > 0; remaining -= Math.min(remaining, block.length)) {
            digest.update(block, 0, (int) Math.min(remaining, block.length));
        }
        return HexFormat.of().formatHex(digest.digest());
    }
}

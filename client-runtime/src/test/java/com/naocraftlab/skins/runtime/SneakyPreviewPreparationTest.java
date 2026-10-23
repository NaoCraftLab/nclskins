package com.naocraftlab.skins.runtime;

import com.naocraftlab.skins.client.ClientExecutor;
import com.naocraftlab.skins.diagnostics.DiagnosticSinks;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SneakyPreviewPreparationTest {
    private static final ClientExecutor CLIENT = new ClientExecutor() {
        public boolean isClientThread() { return true; }
        public void execute(Runnable action) { action.run(); }
    };

    @Test
    void retainedDemandIsBoundedAndLatestDemandSurvivesWithoutSecondExecutor() throws Exception {
        List<Runnable> jobs = new ArrayList<>();
        PreviewAssetLoader loader = new PreviewAssetLoader(CLIENT, jobs::add, DiagnosticSinks.discarding());
        List<PreviewAssets.SneakyLease> leases = new ArrayList<>();
        for (int index = 0; index < 40; index++) {
            var lease = loader.prepareSneaky(skin(index));
            leases.add(lease);
            if (index == 4) lease.result().whenComplete((result, failure) -> lease.close());
        }
        assertEquals(4, jobs.size());
        assertEquals(PreviewAssets.SneakyStatus.FAILURE, leases.get(4).result().join().status());
        while (!jobs.isEmpty()) jobs.remove(0).run();
        assertEquals(PreviewAssets.SneakyStatus.VALUE, leases.get(39).result().join().status());
        loader.close();
    }

    @Test
    void confirmedResultsAreCachedButFailuresRetryAndLeasesCancelIndependently() throws Exception {
        List<Runnable> jobs = new ArrayList<>();
        PreviewAssetLoader loader = new PreviewAssetLoader(CLIENT, jobs::add, DiagnosticSinks.discarding());
        byte[] source = skin(0);
        var first = loader.prepareSneaky(source);
        var second = loader.prepareSneaky(source);
        assertEquals(1, jobs.size());
        first.close();
        jobs.remove(0).run();
        assertEquals(PreviewAssets.SneakyStatus.FAILURE, first.result().join().status());
        assertEquals(PreviewAssets.SneakyStatus.VALUE, second.result().join().status());
        var cached = loader.prepareSneaky(source);
        assertEquals(0, jobs.size());
        assertEquals(PreviewAssets.SneakyStatus.VALUE, cached.result().join().status());
        byte[] mutable = cached.result().join().atlas();
        mutable[0] = 0;
        assertNotEquals(0, cached.result().join().atlas()[0]);
        source[0] = 0;
        var invalid = loader.prepareSneaky(source);
        jobs.remove(0).run();
        assertEquals(PreviewAssets.SneakyStatus.FAILURE, invalid.result().join().status());
        var retry = loader.prepareSneaky(source);
        assertEquals(1, jobs.size());
        loader.close();
        assertEquals(PreviewAssets.SneakyStatus.FAILURE, retry.result().join().status());
        jobs.remove(0).run();
        assertThrows(IllegalStateException.class, () -> loader.prepareSneaky(new byte[]{1}));
    }

    @Test
    void terminalCloseAllowsReentrantLeaseRelease() throws Exception {
        List<Runnable> jobs = new ArrayList<>();
        PreviewAssetLoader loader = new PreviewAssetLoader(CLIENT, jobs::add, DiagnosticSinks.discarding());
        var lease = loader.prepareSneaky(skin(1));
        lease.result().whenComplete((result, failure) -> lease.close());
        assertDoesNotThrow(loader::close);
        jobs.remove(0).run();
        assertEquals(PreviewAssets.SneakyStatus.FAILURE, lease.result().join().status());
    }

    @Test
    void confirmedAbsenceIsNegativeCachedAndOldestValueIsEvicted() throws Exception {
        List<Runnable> jobs = new ArrayList<>();
        PreviewAssetLoader loader = new PreviewAssetLoader(CLIENT, jobs::add, DiagnosticSinks.discarding());
        BufferedImage ordinary = ImageIO.read(new java.io.ByteArrayInputStream(skin(0)));
        ordinary.setRGB(60, 48, 0);
        ByteArrayOutputStream encoded = new ByteArrayOutputStream();
        ImageIO.write(ordinary, "png", encoded);
        byte[] absence = encoded.toByteArray();
        var missing = loader.prepareSneaky(absence);
        jobs.remove(0).run();
        assertEquals(PreviewAssets.SneakyStatus.ABSENCE, missing.result().join().status());
        assertEquals(PreviewAssets.SneakyStatus.ABSENCE, loader.prepareSneaky(absence).result().join().status());
        assertTrue(jobs.isEmpty());
        for (int index = 0; index < 33; index++) {
            var lease = loader.prepareSneaky(skin(index));
            jobs.remove(0).run();
            assertEquals(PreviewAssets.SneakyStatus.VALUE, lease.result().join().status());
        }
        loader.prepareSneaky(skin(32));
        assertTrue(jobs.isEmpty());
        loader.prepareSneaky(skin(0));
        assertEquals(1, jobs.size());
        loader.close();
        jobs.remove(0).run();
    }

    @Test
    void scopeInvalidationDropsCacheAndRejectsLatePreparation() throws Exception {
        List<Runnable> jobs = new ArrayList<>();
        PreviewAssetLoader loader = new PreviewAssetLoader(CLIENT, jobs::add, DiagnosticSinks.discarding());
        byte[] source = skin(0);
        var pending = loader.prepareSneaky(source);
        loader.invalidateSneaky();
        assertEquals(PreviewAssets.SneakyStatus.FAILURE, pending.result().join().status());
        jobs.remove(0).run();
        var fresh = loader.prepareSneaky(source);
        assertEquals(1, jobs.size());
        jobs.remove(0).run();
        assertEquals(PreviewAssets.SneakyStatus.VALUE, fresh.result().join().status());
        loader.invalidateSneaky();
        loader.prepareSneaky(source);
        assertEquals(1, jobs.size());
        loader.close();
        jobs.remove(0).run();
    }

    @Test
    void hostileDimensionsChunksAndInflatedPixelsFailWithoutNegativeCaching() throws Exception {
        byte[] valid = skin(0);
        byte[] dimensions = java.util.Arrays.copyOfRange(valid, 16, 29);
        java.nio.ByteBuffer.wrap(dimensions).putInt(4096).putInt(4096);
        byte[] oversized = replaceChunk(valid, "IHDR", dimensions);
        ByteArrayOutputStream hostile = new ByteArrayOutputStream();
        hostile.write(valid, 0, valid.length - 12);
        hostile.write(chunk("ABCD", new byte[]{1}));
        hostile.write(chunk("IEND", new byte[0]));
        ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        try (var deflater = new java.util.zip.DeflaterOutputStream(compressed)) {
            deflater.write(new byte[64 * (1 + 64 * 4) + 1]);
        }
        byte[] bomb = replaceChunk(valid, "IDAT", compressed.toByteArray());
        List<Runnable> jobs = new ArrayList<>();
        try (PreviewAssetLoader loader = new PreviewAssetLoader(CLIENT, jobs::add, DiagnosticSinks.discarding())) {
            for (byte[] bytes : List.of(new byte[0], java.util.Arrays.copyOf(valid, 24), oversized,
                    hostile.toByteArray(), bomb)) {
                var failed = loader.prepareSneaky(bytes);
                jobs.remove(0).run();
                assertEquals(PreviewAssets.SneakyStatus.FAILURE, failed.result().join().status());
                assertNull(failed.result().join().atlas());
                var retry = loader.prepareSneaky(bytes);
                assertEquals(1, jobs.size());
                jobs.remove(0).run();
                assertEquals(PreviewAssets.SneakyStatus.FAILURE, retry.result().join().status());
            }
            var safe = loader.prepareSneaky(valid);
            jobs.remove(0).run();
            assertEquals(PreviewAssets.SneakyStatus.VALUE, safe.result().join().status());
        }
    }

    @Test
    void canonicalAtlasCacheHasExactAccountingAndEntryLimitDominatesByteBudget() throws Exception {
        List<Runnable> jobs = new ArrayList<>();
        try (PreviewAssetLoader loader = new PreviewAssetLoader(CLIENT, jobs::add, DiagnosticSinks.discarding())) {
            var cacheField = PreviewAssetLoader.class.getDeclaredField("sneakyCache");
            var bytesField = PreviewAssetLoader.class.getDeclaredField("sneakyCacheBytes");
            cacheField.setAccessible(true);
            bytesField.setAccessible(true);
            long canonicalRgbaBytes = 64L * 32 * 4;
            long filteredBytes = canonicalRgbaBytes + 32;
            long encodedEnvelope = filteredBytes + (filteredBytes / 8) + 1024;
            assertTrue(32 * encodedEnvelope < 2L * 1024 * 1024);
            for (int index = 0; index < 40; index++) {
                BufferedImage image = ImageIO.read(new java.io.ByteArrayInputStream(skin(index)));
                var random = new java.util.Random(index);
                for (int y = 0; y < 64; y++) for (int x = 0; x < 64; x++) {
                    if (x != 60 || y < 48 || y > 51) image.setRGB(x, y, random.nextInt());
                }
                ByteArrayOutputStream encoded = new ByteArrayOutputStream();
                ImageIO.write(image, "png", encoded);
                var lease = loader.prepareSneaky(encoded.toByteArray());
                jobs.remove(0).run();
                var value = lease.result().join();
                assertEquals(PreviewAssets.SneakyStatus.VALUE, value.status());
                var atlas = ImageIO.read(new java.io.ByteArrayInputStream(value.atlas()));
                assertEquals(64, atlas.getWidth());
                assertEquals(32, atlas.getHeight());
                assertTrue(value.atlas().length <= encodedEnvelope);
                var cache = (java.util.Map<?, ?>) cacheField.get(loader);
                assertEquals(Math.min(index + 1, 32), cache.size());
                long actual = cache.values().stream().map(PreviewAssets.SneakyResult.class::cast)
                        .mapToLong(result -> result.atlas() == null ? 0 : result.atlas().length).sum();
                assertEquals(actual, bytesField.getLong(loader));
                assertTrue(actual <= 32 * encodedEnvelope);
                assertTrue(actual < 2L * 1024 * 1024);
                lease.close();
            }
            loader.invalidateSneaky();
            assertEquals(0, ((java.util.Map<?, ?>) cacheField.get(loader)).size());
            assertEquals(0, bytesField.getLong(loader));
        }
    }

    @Test
    void deferredPublicationAndLateReleaseCannotRetireReplacementSharedLeases() throws Exception {
        List<Runnable> jobs = new ArrayList<>(), publications = new ArrayList<>();
        ClientExecutor deferred = new ClientExecutor() {
            public boolean isClientThread() { return false; }
            public void execute(Runnable action) { publications.add(action); }
        };
        try (PreviewAssetLoader loader = new PreviewAssetLoader(deferred, jobs::add, DiagnosticSinks.discarding())) {
            byte[] source = skin(1);
            var old = loader.prepareSneaky(source);
            jobs.remove(0).run();
            assertFalse(old.result().isDone());
            loader.invalidateSneaky();
            var reopened = loader.prepareSneaky(source);
            var shared = loader.prepareSneaky(source);
            old.close();
            publications.remove(0).run();
            assertEquals(PreviewAssets.SneakyStatus.FAILURE, old.result().join().status());
            assertFalse(reopened.result().isDone());
            jobs.remove(0).run();
            reopened.close();
            publications.remove(0).run();
            assertEquals(PreviewAssets.SneakyStatus.FAILURE, reopened.result().join().status());
            assertEquals(PreviewAssets.SneakyStatus.VALUE, shared.result().join().status());
            old.close();
            reopened.close();
            assertEquals(PreviewAssets.SneakyStatus.VALUE, loader.prepareSneaky(source).result().join().status());
            assertTrue(jobs.isEmpty());
        }
    }

    @Test
    void terminalOwnerResetRejectsDeferredResultWithoutClosingAnotherProcessOwner() throws Exception {
        List<Runnable> jobs = new ArrayList<>(), publications = new ArrayList<>();
        ClientExecutor deferred = new ClientExecutor() {
            public boolean isClientThread() { return false; }
            public void execute(Runnable action) { publications.add(action); }
        };
        PreviewAssetLoader retired = new PreviewAssetLoader(deferred, jobs::add, DiagnosticSinks.discarding());
        byte[] source = skin(2);
        var pending = retired.prepareSneaky(source);
        jobs.remove(0).run();
        retired.close();
        try (PreviewAssetLoader current = new PreviewAssetLoader(CLIENT, jobs::add, DiagnosticSinks.discarding())) {
            var fresh = current.prepareSneaky(source);
            publications.remove(0).run();
            pending.close();
            assertEquals(PreviewAssets.SneakyStatus.FAILURE, pending.result().join().status());
            jobs.remove(0).run();
            assertEquals(PreviewAssets.SneakyStatus.VALUE, fresh.result().join().status());
            assertThrows(IllegalStateException.class, () -> retired.prepareSneaky(source));
        }
    }

    private static byte[] replaceChunk(byte[] png, String type, byte[] replacement) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.write(png, 0, 8);
        for (int offset = 8; offset < png.length;) {
            int length = java.nio.ByteBuffer.wrap(png, offset, 4).getInt();
            String current = new String(png, offset + 4, 4, java.nio.charset.StandardCharsets.US_ASCII);
            if (current.equals(type)) output.write(chunk(type, replacement));
            else output.write(png, offset, length + 12);
            offset += length + 12;
        }
        return output.toByteArray();
    }

    private static byte[] chunk(String type, byte[] bytes) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        var data = new java.io.DataOutputStream(output);
        byte[] name = type.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        data.writeInt(bytes.length);
        data.write(name);
        data.write(bytes);
        var crc = new java.util.zip.CRC32();
        crc.update(name);
        crc.update(bytes);
        data.writeInt((int) crc.getValue());
        return output.toByteArray();
    }

    static byte[] skin(int pixel) throws Exception {
        BufferedImage image = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
        int[] marker = {0xfffff42f, 0xffffffff, 0xff9c59d1, 0xff292929};
        for (int index = 0; index < marker.length; index++) image.setRGB(60, 48 + index, marker[index]);
        image.setRGB(56, 16, pixel);
        ByteArrayOutputStream encoded = new ByteArrayOutputStream();
        ImageIO.write(image, "png", encoded);
        return encoded.toByteArray();
    }
}

package com.naocraftlab.skins.runtime;

import com.naocraftlab.skins.core.png.CapeAssetDecoder;
import com.naocraftlab.skins.core.png.PngValidator;
import com.naocraftlab.skins.core.storage.NclSkinsStorage;
import com.naocraftlab.skins.core.storage.ProviderTextureStorageAdapter;
import com.naocraftlab.skins.core.storage.TextureCache;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ObservedCapeCacheBudgetTest {
    private static final long SCRATCH_MAX = 2L * 1024 * 1024 * 1024;

    @Test
    @SuppressWarnings("try")
    void samePhysicalCacheCountsOnceAndEvictsOnlyUnpinnedBacking(@TempDir Path root)
            throws Exception {
        NclSkinsStorage first = new NclSkinsStorage(root, new PngValidator(), Clock.systemUTC());
        first.initialize();
        var store1 = new ProviderTextureStorageAdapter(first, new TextureCache(first));
        String red = store1.storeObservedPreparedCape(prepare(root, "red", 0xffff0000));
        String blue = store1.storeObservedPreparedCape(prepare(root, "blue", 0xff0000ff));
        NclSkinsStorage second = new NclSkinsStorage(root, new PngValidator(), Clock.systemUTC());
        second.initialize();
        var store2 = new ProviderTextureStorageAdapter(second, new TextureCache(second));
        assertTrue(CapePreparationQueue.reconcileObservedCache(store1));
        long afterFirst = CapePreparationQueue.reservedScratchBytes();
        assertTrue(CapePreparationQueue.reconcileObservedCache(store2));
        assertEquals(afterFirst, CapePreparationQueue.reservedScratchBytes(),
                "the second adapter must reuse the same physical cache reservation");
        Path cache = first.layout().textureCache();
        long redBytes = Files.size(cache.resolve(red + ".frames"))
                + Files.size(cache.resolve(red + ".cape"));
        long blueBytes = Files.size(cache.resolve(blue + ".frames"))
                + Files.size(cache.resolve(blue + ".cape"));
        assertEquals(redBytes + blueBytes,
                store2.listObservedPreparedCapes().stream().mapToLong(item -> item.storedBytes()).sum());

        var pinned = second.readObservedPreparedCape(red).orElseThrow();
        try (var reader = pinned.openBackingLease()) {
            assertNull(CapePreparationQueue.tryReserveScratch(SCRATCH_MAX),
                    "pinned bytes must prevent an admission requiring the whole budget");
            assertTrue(Files.exists(cache.resolve(red + ".frames")),
                    "a live reader must retain its old valid frame");
            assertFalse(Files.exists(cache.resolve(blue + ".frames")),
                    "unreferenced LRU backing may be reclaimed for new demand");
            assertNotNull(reader.readFrame(0));
        }
        assertTrue(second.removeObservedPreparedCape(red));
    }

    private static com.naocraftlab.skins.core.png.PreparedCapeAsset prepare(
            Path root, String name, int color) throws Exception {
        BufferedImage image = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 64; y++) for (int x = 0; x < 64; x++) image.setRGB(x, y, color);
        Path encoded = root.resolve(name + ".png");
        assertTrue(ImageIO.write(image, "png", encoded.toFile()));
        return new CapeAssetDecoder().prepare(encoded, root.resolve(name + ".frames"));
    }
}

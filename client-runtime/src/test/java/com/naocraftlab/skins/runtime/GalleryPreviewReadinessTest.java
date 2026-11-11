package com.naocraftlab.skins.runtime;

import com.naocraftlab.skins.client.ClientExecutor;
import com.naocraftlab.skins.core.png.CapeAssetDecoder;
import com.naocraftlab.skins.core.png.FrameReadFixture;
import com.naocraftlab.skins.core.png.PreparedCapeAsset;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Optional;
import javax.imageio.ImageIO;

import static org.junit.jupiter.api.Assertions.*;

final class GalleryPreviewReadinessTest {
    @TempDir Path directory;
    private final ArrayDeque<Runnable> jobs = new ArrayDeque<>();
    private final ClientExecutor client = new ClientExecutor() {
        public boolean isClientThread() { return true; }
        public void execute(Runnable action) { action.run(); }
    };

    private PreparedCapeAsset asset() throws Exception {
        var image = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(1, 1, 0xff123456);
        image.setRGB(1, 33, 0xff654321);
        var path = directory.resolve("synthetic.png");
        ImageIO.write(image, "png", path.toFile());
        return new CapeAssetDecoder().prepare(path, directory.resolve("synthetic.frames"));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void ordinaryAndThumbnailFrameZeroDoNotScanComparisonTimelineOnOpenOrReopen(boolean thumbnail) throws Exception {
        var fixture = new FrameReadFixture(asset());
        for (int opening = 0; opening < 2; opening++) {
            var owner = new CatalogRead.PreparedCapeLease() {
                public PreparedCapeAsset asset() { return fixture.asset; }
                public void close() {}
            };
            try (var source = PreparedCapePreview.open(owner, jobs::add, client, () -> 0L, thumbnail)) {
                assertEquals(0, fixture.rowReads.get(), "Opening a material source must not scan Gallery comparison rows");
                var frame = source.nextFrame(-1);
                assertFalse(frame.isDone());
                jobs.remove().run();
                try (var ready = frame.join().orElseThrow()) {
                    assertEquals(0, ready.index());
                    assertEquals(64 * 32 * 4, ready.rgba8().length);
                    assertEquals(Optional.of(fixture.asset.visualSha256()), source.visualSha256());
                }
                assertEquals(0, fixture.rowReads.get());
            }
        }
        assertEquals(2, fixture.opens.get());
    }
}

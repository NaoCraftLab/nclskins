package com.naocraftlab.skins.core.png;

import com.naocraftlab.skins.core.model.SkinVariant;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CpmCarrierReaderTest {
    private final CpmCarrierReader reader = new CpmCarrierReader();

    @Test
    void supportsVariantsLinksAndUnknownFramedBlocks() throws Exception {
        for (String name : new String[]{"classic-a", "slim-a", "linked", "unknown-type", "transition"}) {
            var report = reader.read(image(name));
            assertEquals(CpmCarrierReader.Status.VALID, report.status(), name);
            assertEquals(name.equals("slim-a") || name.equals("transition") ? SkinVariant.SLIM : SkinVariant.CLASSIC,
                    report.variant(), name);
        }
    }

    @Test
    void distinguishesConsumedChannelsFromReservedPixels() throws Exception {
        var report = reader.read(image("classic-a"));
        assertEquals(0x00ffffff, report.channelMask(0, 0));
        assertEquals(0, report.channelMask(7, 0));
        assertEquals(0, report.channelMask(8, 0));
        assertThrows(IndexOutOfBoundsException.class, () -> report.channelMask(64, 0));
    }

    @Test
    void strongCandidatesRejectChecksumLengthsTruncationAndVarints() throws Exception {
        for (String name : new String[]{"classic-bad-checksum", "oversized-length", "oversized-varint", "missing-end"}) {
            var report = reader.read(image(name));
            assertEquals(CpmCarrierReader.Status.MALFORMED, report.status(), name);
            assertEquals(0x00ffffff, report.channelMask(7, 0));
            assertEquals(-1, report.channelMask(32, 0));
        }
    }

    @Test
    void unknownChannelsAndSingleMagicDoNotClaimSupportOrDamage() throws Exception {
        assertEquals(CpmCarrierReader.Status.UNKNOWN, reader.read(image("unknown-channel")).status());
        BufferedImage image = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
        assertEquals(CpmCarrierReader.Status.ABSENT, reader.read(image).status());
        image.setRGB(0, 0, 83);
        assertEquals(CpmCarrierReader.Status.UNKNOWN, reader.read(image).status());
        assertThrows(IllegalArgumentException.class, () -> reader.read(new BufferedImage(64, 32, BufferedImage.TYPE_INT_ARGB)));
    }

    private static BufferedImage image(String name) throws Exception {
        try (var input = CpmCarrierReaderTest.class.getResourceAsStream("/cpm/" + name + ".png")) {
            return ImageIO.read(java.util.Objects.requireNonNull(input));
        }
    }
}

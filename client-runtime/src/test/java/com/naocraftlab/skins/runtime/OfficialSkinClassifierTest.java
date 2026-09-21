package com.naocraftlab.skins.runtime;

import com.naocraftlab.skins.client.SkinCatalogSource;
import com.naocraftlab.skins.client.SkinModel;
import com.naocraftlab.skins.core.model.AccountDefaultSkin;
import com.naocraftlab.skins.core.model.SkinVariant;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class OfficialSkinClassifierTest {
    @Test
    void comparesOnlyAssignedCharacterAndModelForEverySlot() throws Exception {
        AtomicInteger reads = new AtomicInteger();
        SkinCatalogSource source = (collection, name, model) -> {
            assertEquals("minecraft", collection);
            reads.incrementAndGet();
            return texture(name, model);
        };
        OfficialSkinClassifier classifier = new OfficialSkinClassifier(source);
        for (int slot = 0; slot < 18; slot++) {
            UUID id = new UUID(0, slot);
            AccountDefaultSkin selected = AccountDefaultSkin.forProfile(id);
            SkinModel model = selected.variant() == SkinVariant.SLIM ? SkinModel.SLIM : SkinModel.CLASSIC;
            byte[] png = texture(selected.skinId(), model);
            assertEquals(OfficialSkinClassifier.Result.DEFAULT, classifier.classify(id, selected.variant(), png));
            assertEquals(OfficialSkinClassifier.Result.CUSTOM, classifier.classify(id,
                    selected.variant() == SkinVariant.SLIM ? SkinVariant.CLASSIC : SkinVariant.SLIM, png));
            String other = selected.skinId().equals("alex") ? "steve" : "alex";
            assertEquals(OfficialSkinClassifier.Result.CUSTOM,
                    classifier.classify(id, selected.variant(), texture(other, model)));
        }
        assertEquals(18, reads.get());
    }

    @Test
    void ignoresTransparentRgbButDetectsVisibleChanges() throws Exception {
        byte[] reference = image(0x00334455, 0xff123456);
        OfficialSkinClassifier classifier = new OfficialSkinClassifier((collection, name, model) -> reference);
        assertEquals(OfficialSkinClassifier.Result.DEFAULT,
                classifier.classify(new UUID(0, 0), SkinVariant.SLIM, image(0x00abcdef, 0xff123456)));
        assertEquals(OfficialSkinClassifier.Result.CUSTOM,
                classifier.classify(new UUID(0, 0), SkinVariant.SLIM, image(0x00334455, 0xff123457)));
    }

    @Test
    void unavailableOrCorruptReferenceNeverMeansDefaultAndCanRecover() throws Exception {
        AtomicInteger reads = new AtomicInteger();
        byte[] reference = texture("alex", SkinModel.SLIM);
        OfficialSkinClassifier classifier = new OfficialSkinClassifier((collection, name, model) -> {
            if (reads.getAndIncrement() == 0) throw new IOException("unavailable");
            return reference;
        });
        assertEquals(OfficialSkinClassifier.Result.UNKNOWN,
                classifier.classify(new UUID(0, 0), SkinVariant.SLIM, reference));
        assertEquals(OfficialSkinClassifier.Result.DEFAULT,
                classifier.classify(new UUID(0, 0), SkinVariant.SLIM, reference));
        assertEquals(OfficialSkinClassifier.Result.UNKNOWN,
                classifier.classify(new UUID(0, 0), SkinVariant.SLIM, new byte[4]));
        assertEquals(OfficialSkinClassifier.Result.UNKNOWN,
                new OfficialSkinClassifier((collection, name, model) -> new byte[4])
                        .classify(new UUID(0, 0), SkinVariant.SLIM, reference));
    }

    private static byte[] texture(String name, SkinModel model) throws IOException {
        return image(0, 0xff000000 | ((name.hashCode() ^ model.ordinal()) & 0xffffff));
    }

    private static byte[] image(int transparent, int opaque) throws IOException {
        BufferedImage image = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 64; y++) {
            for (int x = 0; x < 64; x++) image.setRGB(x, y, opaque);
        }
        image.setRGB(55, 55, transparent);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(image, "png", output);
        return output.toByteArray();
    }
}

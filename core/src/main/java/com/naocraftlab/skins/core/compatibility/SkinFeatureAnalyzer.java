package com.naocraftlab.skins.core.compatibility;

import java.awt.image.BufferedImage;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;


public final class SkinFeatureAnalyzer {
    private static final int EARS_V0_MAGIC = 0x3f23d8;
    private static final int EARS_V1_MAGIC = 0xea2501;
    private static final Set<Integer> EARS_MAGIC_COLORS = Set.of(
            0x3f23d8, 0x23d848, 0xd82350, 0xb923d8, 0x23d8c6,
            0xd87823, 0xd823b7, 0xd823ff, 0xfefdf2, 0x5e605a);
    private static final int[][] EXPRESSIVE_LAYOUTS = {
            {4, 0, 4, 2}, {4, 2, 4, 2}, {4, 4, 4, 2}, {4, 6, 4, 2},
            {24, 2, 4, 3}, {24, 5, 4, 3}, {28, 2, 4, 3}, {28, 5, 4, 3},
            {60, 0, 4, 2}, {60, 2, 4, 2}, {60, 4, 4, 2}, {60, 6, 4, 2}
    };
    private static final int ALFALFA_MAGIC = 0xea1fa1fa;
    private static final int[][] ALFALFA_REGIONS = {
            {8, 0, 24, 8}, {0, 8, 8, 16}, {16, 8, 32, 16},
            {4, 16, 12, 20}, {20, 16, 36, 20}, {44, 16, 52, 20},
            {0, 20, 56, 32}, {20, 48, 28, 52}, {36, 48, 44, 52},
            {16, 52, 48, 64}
    };

    public SkinFeatureEvidence analyze(BufferedImage image) {
        Objects.requireNonNull(image, "image");
        if (image.getWidth() != 64 || image.getHeight() != 64) {
            throw new IllegalArgumentException("feature analysis requires a canonical 64x64 raster");
        }
        List<SkinFeature> features = new ArrayList<>();
        List<SkinConflictReason> conflicts = new ArrayList<>();

        analyzeEars(image, features, conflicts);
        analyzeExpressive(image, features, conflicts);
        return new SkinFeatureEvidence(features, conflicts);
    }

    private static void analyzeEars(
            BufferedImage image,
            List<SkinFeature> features,
            List<SkinConflictReason> conflicts) {
        int first = rgb(image.getRGB(0, 32));
        if (first == EARS_V0_MAGIC) {
            if (validEarsV0(image) && validOptionalAlfalfa(image)) {
                features.add(SkinFeature.EARS);
            } else {
                conflicts.add(SkinConflictReason.MALFORMED_EARS_DATA);
            }
            return;
        }
        if (first == EARS_V1_MAGIC) {
            if (validEarsV1(image) && validOptionalAlfalfa(image)) {
                features.add(SkinFeature.EARS);
            } else {
                conflicts.add(SkinConflictReason.MALFORMED_EARS_DATA);
            }
            return;
        }
        for (int y = 32; y < 36; y++) {
            for (int x = 0; x < 4; x++) {
                if (EARS_MAGIC_COLORS.contains(rgb(image.getRGB(x, y)))) {
                    conflicts.add(SkinConflictReason.MALFORMED_EARS_DATA);
                    return;
                }
            }
        }
    }

    private static boolean validEarsV0(BufferedImage image) {
        return isEarsMagic(image, 1)
                && isEarsMagic(image, 2)
                && isEarsMagic(image, 3)
                && isEarsMagic(image, 4)
                && isEarsMagic(image, 8)
                && isEarsMagic(image, 9)
                && isEarsMagic(image, 10);
    }

    private static boolean validEarsV1(BufferedImage image) {
        int versionCarrier = image.getRGB(1, 32);
        int version = (versionCarrier >>> 16) & 0xff;
        return alpha(versionCarrier) == 0xff && version <= 1;
    }

    private static boolean validOptionalAlfalfa(BufferedImage image) {
        BigInteger encoded = BigInteger.ZERO;
        int read = 0;
        for (int[] region : ALFALFA_REGIONS) {
            for (int x = region[0]; x < region[2]; x++) {
                for (int y = region[1]; y < region[3]; y++) {
                    int alpha = alpha(image.getRGB(x, y));
                    if (alpha == 0) {
                        continue;
                    }
                    int value = 0x7f - (alpha & 0x7f);
                    encoded = encoded.or(BigInteger.valueOf(value).shiftLeft(read * 7));
                    read++;
                }
            }
        }
        if (encoded.signum() == 0) {
            return true;
        }
        return validAlfalfaSequence(encoded.toByteArray());
    }

    private static boolean validAlfalfaSequence(byte[] bytes) {
        if (bytes.length < 7 || bytes[0] != 0) {
            return false;
        }
        int magic = (Byte.toUnsignedInt(bytes[1]) << 24)
                | (Byte.toUnsignedInt(bytes[2]) << 16)
                | (Byte.toUnsignedInt(bytes[3]) << 8)
                | Byte.toUnsignedInt(bytes[4]);
        if (magic != ALFALFA_MAGIC || Byte.toUnsignedInt(bytes[5]) != 1) {
            return false;
        }
        int index = 6;
        int entries = 0;
        while (index < bytes.length && entries <= 64) {
            int first = Byte.toUnsignedInt(bytes[index++]);
            if (first == 0) {
                return index == bytes.length;
            }
            if (first >= 64) {
                boolean terminated = (first & 0x80) != 0;
                while (!terminated && index < bytes.length) {
                    terminated = (Byte.toUnsignedInt(bytes[index++]) & 0x80) != 0;
                }
                if (!terminated) {
                    return false;
                }
            }
            int length;
            do {
                if (index >= bytes.length) {
                    return false;
                }
                length = Byte.toUnsignedInt(bytes[index++]);
                if (length > bytes.length - index) {
                    return false;
                }
                index += length;
            } while (length == 255);
            entries++;
        }
        return false;
    }

    private static boolean isEarsMagic(BufferedImage image, int index) {
        int x = index % 4;
        int y = 32 + index / 4;
        return EARS_MAGIC_COLORS.contains(rgb(image.getRGB(x, y)));
    }

    private static void analyzeExpressive(
            BufferedImage image,
            List<SkinFeature> features,
            List<SkinConflictReason> conflicts) {
        int completeLayouts = 0;
        for (int[] layout : EXPRESSIVE_LAYOUTS) {
            if (opaqueRectangle(image, layout)) {
                completeLayouts++;
            }
        }
        if (completeLayouts == 1) {
            features.add(SkinFeature.FRESH_MOVES);
            features.add(SkinFeature.JUST_EXPRESSIONS);
        } else if (completeLayouts > 1) {
            conflicts.add(SkinConflictReason.MALFORMED_EXPRESSIVE_DATA);
        }
    }

    private static boolean opaqueRectangle(BufferedImage image, int[] layout) {
        for (int y = layout[1]; y < layout[1] + layout[3]; y++) {
            for (int x = layout[0]; x < layout[0] + layout[2]; x++) {
                if (alpha(image.getRGB(x, y)) != 0xff) {
                    return false;
                }
            }
        }
        return true;
    }

    private static int rgb(int argb) {
        return argb & 0x00ffffff;
    }

    private static int alpha(int argb) {
        return argb >>> 24;
    }
}

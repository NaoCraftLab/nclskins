package com.naocraftlab.skins.core.png;

import com.naocraftlab.skins.core.model.SkinVariant;
import java.awt.image.BufferedImage;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Objects;

public final class CpmCarrierReader {
    public enum Status { ABSENT, UNKNOWN, VALID, MALFORMED }

    private static final int[][] REGIONS = {
            {0, 0, 8, 8, 7}, {24, 0, 32, 8, 7}, {32, 0, 40, 8, 3}, {56, 0, 64, 8, 3},
            {0, 16, 4, 20, 7}, {12, 16, 20, 20, 7}, {36, 16, 44, 20, 7},
            {50, 16, 52, 20, 5}, {52, 16, 64, 20, 7}, {54, 20, 56, 32, 5}, {56, 20, 64, 32, 7},
            {0, 32, 4, 36, 3}, {12, 32, 20, 36, 3}, {36, 32, 44, 36, 3},
            {50, 32, 52, 36, 1}, {52, 32, 64, 36, 3}, {54, 36, 56, 48, 1}, {56, 36, 64, 48, 3},
            {0, 48, 4, 52, 3}, {12, 48, 16, 52, 3}, {16, 48, 20, 52, 7},
            {28, 48, 36, 52, 7}, {42, 48, 44, 52, 5}, {44, 48, 48, 52, 7},
            {48, 48, 52, 52, 3}, {58, 48, 60, 52, 1}, {60, 48, 64, 52, 3},
            {46, 52, 48, 64, 5}, {62, 52, 64, 64, 1}
    };
    private static final int[] MASK = mask();

    public Result read(BufferedImage image) {
        Objects.requireNonNull(image, "image");
        if (image.getWidth() != 64 || image.getHeight() != 64) {
            throw new IllegalArgumentException("carrier requires a canonical raster");
        }
        Result classic = read(image, SkinVariant.CLASSIC);
        Result slim = read(image, SkinVariant.SLIM);
        if (classic.status == Status.VALID && slim.status == Status.VALID) {
            if (classic.variant == slim.variant && Arrays.equals(classic.footprint, slim.footprint)) return classic;
            return new Result(Status.UNKNOWN, SkinVariant.CLASSIC, new int[4096]);
        }
        if (classic.status == Status.VALID) return classic;
        if (slim.status == Status.VALID) return slim;
        if (classic.status == Status.MALFORMED) return classic;
        if (slim.status == Status.MALFORMED) return slim;
        return classic;
    }

    public Result read(BufferedImage image, SkinVariant initialVariant) {
        Objects.requireNonNull(image, "image");
        Objects.requireNonNull(initialVariant, "initialVariant");
        if (image.getWidth() != 64 || image.getHeight() != 64) {
            throw new IllegalArgumentException("carrier requires a canonical raster");
        }
        Cursor cursor = new Cursor(image, initialVariant);
        if (cursor.read(false) != 0x53) return new Result(Status.ABSENT, initialVariant, new int[4096]);
        boolean supported = false;
        boolean unknownSemantics = false;
        while (cursor.count < 3360) {
            int type = cursor.read(true);
            long length = cursor.length();
            if (type < 0 || length < 0 || length > 3360 - cursor.count) break;
            if (type == 11) {
                if (length == 0) break;
                int skinType = cursor.read(true);
                if (skinType == 0 || skinType == 1) supported = true;
                else unknownSemantics = true;
                for (long i = 1; i < length; i++) if (cursor.read(true) < 0) return malformed(cursor, supported, unknownSemantics);
                if (skinType == 0 || skinType == 1) cursor.variant = skinType == 0 ? SkinVariant.SLIM : SkinVariant.CLASSIC;
            } else {
                for (long i = 0; i < length; i++) if (cursor.read(true) < 0) return malformed(cursor, supported, unknownSemantics);
            }
            if (type == 0) {
                int checksum = cursor.sum;
                int high = cursor.read(false);
                int low = cursor.read(false);
                if (high < 0 || low < 0 || checksum != (high << 8 | low)) break;
                return new Result(supported && !unknownSemantics ? Status.VALID : Status.UNKNOWN,
                        cursor.variant, cursor.footprint);
            }
        }
        return malformed(cursor, supported, unknownSemantics);
    }

    private static Result malformed(Cursor cursor, boolean supported, boolean unknown) {
        if (!supported || unknown) return new Result(Status.UNKNOWN, cursor.variant, new int[4096]);
        int[] conservative = new int[4096];
        for (int i = 0; i < MASK.length; i++) if ((MASK[i] & 3) != 0) conservative[i] = (MASK[i] & 4) != 0 ? 0x00ffffff : -1;
        return new Result(Status.MALFORMED, cursor.variant, conservative);
    }

    public static final class Result {
        private final Status status;
        private final SkinVariant variant;
        private final int[] footprint;

        private Result(Status status, SkinVariant variant, int[] footprint) {
            this.status = status;
            this.variant = variant;
            this.footprint = footprint.clone();
        }

        public Status status() { return status; }
        public SkinVariant variant() { return variant; }
        public int channelMask(int x, int y) {
            Objects.checkIndex(x, 64);
            Objects.checkIndex(y, 64);
            return footprint[y * 64 + x];
        }

        void appendIdentity(MessageDigest digest, BufferedImage image) {
            if (status != Status.VALID && status != Status.MALFORMED) return;
            digest.update(new byte[]{0x43, 0x50, 0x4d, 1, (byte) status.ordinal(), (byte) variant.ordinal()});
            for (int i = 0; i < footprint.length; i++) {
                int channels = footprint[i];
                int value = image.getRGB(i % 64, i / 64);
                for (int shift = 0; shift < 32; shift += 8) {
                    if ((channels >>> shift & 255) != 0) digest.update((byte) (value >>> shift));
                }
            }
        }
    }

    private static final class Cursor {
        private final BufferedImage image;
        private final int[] footprint = new int[4096];
        private SkinVariant variant;
        private int position = -1;
        private int channel = 4;
        private int count;
        private int sum;

        private Cursor(BufferedImage image, SkinVariant variant) { this.image = image; this.variant = variant; }

        private int read(boolean checksum) {
            if (count >= 3360) return -1;
            if (channel >= 4) {
                int selection = variant == SkinVariant.SLIM ? 1 : 2;
                do { position++; } while (position < 4096 && (MASK[position] & selection) == 0);
                if (position >= 4096) return -1;
                channel = 0;
            }
            int shift = channel++ * 8;
            if ((MASK[position] & 4) != 0 && channel > 2) channel++;
            int value = image.getRGB(position % 64, position / 64) >>> shift & 255;
            footprint[position] |= 255 << shift;
            count++;
            if (checksum) sum = (sum + value) & 65535;
            return value;
        }

        private long length() {
            long value = 0;
            for (int shift = 0; shift < 35; shift += 7) {
                int next = read(true);
                if (next < 0) return -1;
                value |= (long) (next & 127) << shift;
                if ((next & 128) == 0) return value <= Integer.MAX_VALUE ? value : -1;
            }
            return -1;
        }
    }

    private static int[] mask() {
        int[] mask = new int[4096];
        for (int[] region : REGIONS) for (int y = region[1]; y < region[3]; y++) for (int x = region[0]; x < region[2]; x++) mask[y * 64 + x] = region[4];
        return mask;
    }
}

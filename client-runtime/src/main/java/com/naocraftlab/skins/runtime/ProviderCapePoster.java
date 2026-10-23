package com.naocraftlab.skins.runtime;

import com.naocraftlab.skins.core.png.PreparedCapeAsset;
import java.util.Objects;

public final class ProviderCapePoster implements AutoCloseable {
    private final int width;
    private final int height;
    private final byte[] rgba8;
    private final String sha256;
    private final CapePreparationQueue.Reservation reservation;

    ProviderCapePoster(PreparedCapeAsset.Thumbnail thumbnail,
            CapePreparationQueue.Reservation reservation) {
        this(thumbnail.width(), thumbnail.height(), thumbnail.rgba8(),
                thumbnail.sha256(), reservation);
    }

    ProviderCapePoster(int width, int height, byte[] rgba8, String sha256,
            CapePreparationQueue.Reservation reservation) {
        if (width <= 0 || height <= 0 || width > 512 || height > 256
                || (long) width * height * 4 != rgba8.length) {
            throw new IllegalArgumentException("Invalid provider cape poster");
        }
        this.width = width;
        this.height = height;
        this.rgba8 = Objects.requireNonNull(rgba8, "rgba8");
        this.sha256 = Objects.requireNonNull(sha256, "sha256");
        this.reservation = Objects.requireNonNull(reservation, "reservation");
    }

    public int width() { return width; }
    public int height() { return height; }
    byte[] rgba8() { return rgba8; }
    byte[] copyRgba8() { return rgba8.clone(); }
    public String sha256() { return sha256; }

    @Override public void close() { reservation.close(); }
}

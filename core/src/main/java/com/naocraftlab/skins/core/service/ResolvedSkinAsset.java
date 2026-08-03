package com.naocraftlab.skins.core.service;

import com.naocraftlab.skins.core.model.SkinVariant;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;


public final class ResolvedSkinAsset {
    private final UUID assetId;
    private final String sha256;
    private final SkinVariant variant;
    private final byte[] pngBytes;

    public ResolvedSkinAsset(UUID assetId, String sha256, SkinVariant variant, byte[] pngBytes) {
        this.assetId = Objects.requireNonNull(assetId, "assetId");
        this.sha256 = Objects.requireNonNull(sha256, "sha256");
        this.variant = Objects.requireNonNull(variant, "variant");
        this.pngBytes = Objects.requireNonNull(pngBytes, "pngBytes").clone();
        if (!sha256.matches("[0-9a-f]{64}") || !sha256.equals(hash(this.pngBytes))) {
            throw new IllegalArgumentException("Resolved skin bytes do not match sha256");
        }
    }

    public UUID assetId() {
        return assetId;
    }

    public String sha256() {
        return sha256;
    }

    public SkinVariant variant() {
        return variant;
    }

    public byte[] pngBytes() {
        return pngBytes.clone();
    }

    @Override
    public String toString() {
        return "ResolvedSkinAsset[assetId=" + assetId + ", sha256=" + sha256 + ", variant=" + variant + "]";
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof ResolvedSkinAsset that)) {
            return false;
        }
        return assetId.equals(that.assetId)
                && sha256.equals(that.sha256)
                && variant == that.variant
                && Arrays.equals(pngBytes, that.pngBytes);
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(assetId, sha256, variant);
        return 31 * result + Arrays.hashCode(pngBytes);
    }

    private static String hash(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("JVM does not provide SHA-256", exception);
        }
    }
}

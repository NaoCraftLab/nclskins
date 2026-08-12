package com.naocraftlab.skins.server;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;


public final class TextureAppearance {
    private static final TextureAppearance ACCOUNT_DEFAULT =
            new TextureAppearance(Kind.ACCOUNT_DEFAULT, null, null);
    private static final TextureAppearance UNKNOWN =
            new TextureAppearance(Kind.UNKNOWN, null, null);

    private final Kind kind;
    private final byte[] digest;
    private final Long verifiedSourceTimestamp;

    private TextureAppearance(Kind kind, byte[] digest, Long verifiedSourceTimestamp) {
        this.kind = Objects.requireNonNull(kind, "kind");
        this.digest = digest == null ? null : digest.clone();
        this.verifiedSourceTimestamp = verifiedSourceTimestamp;
    }

    public static TextureAppearance accountDefault() {
        return ACCOUNT_DEFAULT;
    }


    public static TextureAppearance unknown() {
        return UNKNOWN;
    }


    public static TextureAppearance verified(
            Optional<String> skinTextureIdentity,
            Optional<SkinModel> model,
            Optional<String> capeTextureIdentity,
            Optional<String> elytraTextureIdentity) {
        Objects.requireNonNull(skinTextureIdentity, "skinTextureIdentity");
        Objects.requireNonNull(model, "model");
        Objects.requireNonNull(capeTextureIdentity, "capeTextureIdentity");
        Objects.requireNonNull(elytraTextureIdentity, "elytraTextureIdentity");
        String skin = skinTextureIdentity
                .map(TextureAppearance::requireTextureIdentity)
                .orElse(null);
        SkinModel skinModel = model.orElse(null);
        if ((skin == null) != (skinModel == null)) {
            throw new IllegalArgumentException("Skin identity and model must be present together");
        }
        String cape = capeTextureIdentity
                .map(TextureAppearance::requireTextureIdentity)
                .orElse(null);
        String elytra = elytraTextureIdentity
                .map(TextureAppearance::requireTextureIdentity)
                .orElse(null);
        if (skin == null && cape == null && elytra == null) {
            return accountDefault();
        }
        try {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            updateLengthPrefixed(sha256, "nclskins-verified-appearance-v1");
            updateOptional(sha256, skin);
            updateOptional(sha256, skinModel == null ? null : skinModel.name());
            updateOptional(sha256, cape);
            updateOptional(sha256, elytra);
            return new TextureAppearance(Kind.VERIFIED, sha256.digest(), null);
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError("Required SHA-256 implementation is unavailable", impossible);
        }
    }

    public boolean isAccountDefault() {
        return kind == Kind.ACCOUNT_DEFAULT;
    }

    public boolean isUnknown() {
        return kind == Kind.UNKNOWN;
    }

    public boolean isVerified() {
        return kind == Kind.VERIFIED;
    }


    public TextureAppearance withVerifiedSourceTimestamp(long timestamp) {
        if (isUnknown() || timestamp < 0L) {
            throw new IllegalArgumentException(
                    "Only verified official appearances may carry a source timestamp");
        }
        return new TextureAppearance(kind, digest, timestamp);
    }


    public OptionalLong verifiedSourceTimestamp() {
        return verifiedSourceTimestamp == null
                ? OptionalLong.empty()
                : OptionalLong.of(verifiedSourceTimestamp);
    }

    @Override
    public boolean equals(Object candidate) {
        if (this == candidate) {
            return true;
        }
        if (!(candidate instanceof TextureAppearance other)) {
            return false;
        }
        if (kind != other.kind) {
            return false;
        }
        if (kind != Kind.VERIFIED) {
            return true;
        }
        return MessageDigest.isEqual(digest, other.digest);
    }

    @Override
    public int hashCode() {
        return 31 * kind.hashCode() + Arrays.hashCode(digest);
    }


    @Override
    public String toString() {
        return switch (kind) {
            case ACCOUNT_DEFAULT -> "TextureAppearance[account-default]";
            case VERIFIED -> "TextureAppearance[verified]";
            case UNKNOWN -> "TextureAppearance[unknown]";
        };
    }

    private static void updateLengthPrefixed(MessageDigest digest, String input) {
        byte[] bytes = input.getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
        digest.update(bytes);
    }

    private static void updateOptional(MessageDigest digest, String value) {
        digest.update((byte) (value == null ? 0 : 1));
        if (value != null) {
            updateLengthPrefixed(digest, value);
        }
    }

    private static String requireTextureIdentity(String identity) {
        Objects.requireNonNull(identity, "textureIdentity");
        if (identity.length() != 64) {
            throw new IllegalArgumentException("Texture identity must be a canonical hash");
        }
        for (int index = 0; index < identity.length(); index++) {
            char character = identity.charAt(index);
            if (!(character >= '0' && character <= '9'
                    || character >= 'a' && character <= 'f'
                    || character >= 'A' && character <= 'F')) {
                throw new IllegalArgumentException("Texture identity must be a canonical hash");
            }
        }
        return identity.toLowerCase(Locale.ROOT);
    }

    public enum SkinModel {
        CLASSIC,
        SLIM
    }

    private enum Kind {
        ACCOUNT_DEFAULT,
        VERIFIED,
        UNKNOWN
    }
}

package com.naocraftlab.skins.client;

import java.net.URI;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;


public final class ExpectedAppearance {
    private static final String TEXTURE_HOST = "textures.minecraft.net";

    private final UUID profileId;
    private final Optional<URI> skinTexture;
    private final Optional<String> localSkinSha256;
    private final Optional<SkinModel> skinModel;
    private final Optional<URI> capeTexture;
    private final Optional<String> localCapeCacheKey;

    public ExpectedAppearance(
            UUID profileId,
            Optional<URI> skinTexture,
            Optional<String> localSkinSha256,
            Optional<SkinModel> skinModel,
            Optional<URI> capeTexture) {
        this(profileId, skinTexture, localSkinSha256, skinModel, capeTexture, Optional.empty());
    }

    public ExpectedAppearance(
            UUID profileId,
            Optional<URI> skinTexture,
            Optional<String> localSkinSha256,
            Optional<SkinModel> skinModel,
            Optional<URI> capeTexture,
            Optional<String> localCapeCacheKey) {
        this.profileId = Objects.requireNonNull(profileId, "profileId");
        this.skinTexture = normalize(Objects.requireNonNull(skinTexture, "skinTexture"));
        this.localSkinSha256 = Objects.requireNonNull(localSkinSha256, "localSkinSha256");
        this.skinModel = Objects.requireNonNull(skinModel, "skinModel");
        this.capeTexture = normalize(Objects.requireNonNull(capeTexture, "capeTexture"));
        this.localCapeCacheKey = Objects.requireNonNull(localCapeCacheKey, "localCapeCacheKey");
        if (this.skinTexture.isPresent() && this.localSkinSha256.isPresent()) {
            throw new IllegalArgumentException("Skin cannot be both remote and local");
        }
        if (this.localSkinSha256.isPresent()
                && !this.localSkinSha256.orElseThrow().matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Local skin hash is invalid");
        }
        if ((this.skinTexture.isPresent() || this.localSkinSha256.isPresent())
                != this.skinModel.isPresent()) {
            throw new IllegalArgumentException(
                    "Skin texture and model must either both be present or both be absent");
        }
        if (this.capeTexture.isPresent() && this.localCapeCacheKey.isPresent()) {
            throw new IllegalArgumentException("Cape cannot be both remote and local");
        }
        if (this.localCapeCacheKey.isPresent()
                && !this.localCapeCacheKey.orElseThrow().matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Local cape cache key is invalid");
        }
    }

    public ExpectedAppearance(
            UUID profileId,
            Optional<URI> skinTexture,
            Optional<SkinModel> skinModel,
            Optional<URI> capeTexture) {
        this(profileId, skinTexture, Optional.empty(), skinModel, capeTexture);
    }

    public UUID profileId() {
        return profileId;
    }

    public Optional<URI> skinTexture() {
        return skinTexture;
    }

    public Optional<String> localSkinSha256() {
        return localSkinSha256;
    }

    public Optional<SkinModel> skinModel() {
        return skinModel;
    }

    public Optional<URI> capeTexture() {
        return capeTexture;
    }

    public Optional<String> localCapeCacheKey() {
        return localCapeCacheKey;
    }


    public boolean vanillaReset() {
        return skinTexture.isEmpty()
                && localSkinSha256.isEmpty()
                && skinModel.isEmpty()
                && capeTexture.isEmpty()
                && localCapeCacheKey.isEmpty();
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof ExpectedAppearance that)) {
            return false;
        }
        return profileId.equals(that.profileId)
                && skinTexture.equals(that.skinTexture)
                && localSkinSha256.equals(that.localSkinSha256)
                && skinModel.equals(that.skinModel)
                && capeTexture.equals(that.capeTexture)
                && localCapeCacheKey.equals(that.localCapeCacheKey);
    }

    @Override
    public int hashCode() {
        return Objects.hash(profileId, skinTexture, localSkinSha256, skinModel, capeTexture, localCapeCacheKey);
    }

    @Override
    public String toString() {
        return "ExpectedAppearance[profileId=" + profileId
                + ", skinTexture=" + skinTexture
                + ", localSkinSha256=" + localSkinSha256
                + ", skinModel=" + skinModel
                + ", capeTexture=" + capeTexture
                + ", localCapeCacheKey=" + localCapeCacheKey + ']';
    }

    private static Optional<URI> normalize(Optional<URI> texture) {
        return texture.map(ExpectedAppearance::normalizeTextureUri);
    }

    private static URI normalizeTextureUri(URI uri) {
        Objects.requireNonNull(uri, "texture URI");
        String scheme = uri.getScheme();
        int port = uri.getPort();
        boolean allowedPort = port == -1
                || "http".equalsIgnoreCase(scheme) && port == 80
                || "https".equalsIgnoreCase(scheme) && port == 443;
        if (!("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))
                || !TEXTURE_HOST.equalsIgnoreCase(uri.getHost())
                || uri.getUserInfo() != null
                || uri.getFragment() != null
                || !allowedPort
                || uri.getRawPath() == null
                || !uri.getRawPath().startsWith("/texture/")) {
            throw new IllegalArgumentException("Expected an allowlisted Minecraft texture URI");
        }
        StringBuilder canonical = new StringBuilder("https://")
                .append(TEXTURE_HOST)
                .append(uri.getRawPath());
        if (uri.getRawQuery() != null) {
            canonical.append('?').append(uri.getRawQuery());
        }
        return URI.create(canonical.toString());
    }
}

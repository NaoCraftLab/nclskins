package com.naocraftlab.skins.core.service;

import com.naocraftlab.skins.core.api.MinecraftServiceUriPolicy;
import com.naocraftlab.skins.core.model.RemoteCape;
import com.naocraftlab.skins.core.model.RemoteProfile;
import com.naocraftlab.skins.core.model.RemoteSkin;
import com.naocraftlab.skins.core.model.SkinVariant;
import java.net.URI;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;


public record AppliedAppearance(
        UUID profileId,
        Optional<URI> skinTexture,
        Optional<String> localSkinSha256,
        Optional<SkinVariant> skinVariant,
        Optional<URI> capeTexture,
        Optional<String> localCapeCacheKey) {
    public AppliedAppearance {
        Objects.requireNonNull(profileId, "profileId");
        skinTexture = normalizeTexture(Objects.requireNonNull(skinTexture, "skinTexture"));
        localSkinSha256 = Objects.requireNonNull(localSkinSha256, "localSkinSha256");
        skinVariant = Objects.requireNonNull(skinVariant, "skinVariant");
        capeTexture = normalizeTexture(Objects.requireNonNull(capeTexture, "capeTexture"));
        localCapeCacheKey = Objects.requireNonNull(localCapeCacheKey, "localCapeCacheKey");
        if (skinTexture.isPresent() && localSkinSha256.isPresent()) {
            throw new IllegalArgumentException("Skin cannot be both remote and local");
        }
        if (localSkinSha256.isPresent()
                && !localSkinSha256.orElseThrow().matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("localSkinSha256 is invalid");
        }
        boolean hasSkin = skinTexture.isPresent() || localSkinSha256.isPresent();
        if (hasSkin != skinVariant.isPresent()) {
            throw new IllegalArgumentException("Skin source and variant must either both be present or both be absent");
        }
        if (capeTexture.isPresent() && localCapeCacheKey.isPresent()) {
            throw new IllegalArgumentException("Cape cannot be both remote and local");
        }
        if (localCapeCacheKey.isPresent()
                && !localCapeCacheKey.orElseThrow().matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("localCapeCacheKey is invalid");
        }
    }

    public AppliedAppearance(
            UUID profileId,
            Optional<URI> skinTexture,
            Optional<String> localSkinSha256,
            Optional<SkinVariant> skinVariant,
            Optional<URI> capeTexture) {
        this(profileId, skinTexture, localSkinSha256, skinVariant, capeTexture, Optional.empty());
    }

    public static AppliedAppearance fromProfile(RemoteProfile profile) {
        Objects.requireNonNull(profile, "profile");
        Optional<RemoteSkin> skin = profile.activeSkin();
        return new AppliedAppearance(
                profile.id(),
                skin.map(RemoteSkin::textureUri),
                Optional.empty(),
                skin.map(RemoteSkin::variant),
                profile.activeCape().map(RemoteCape::textureUri),
                Optional.empty());
    }

    public static AppliedAppearance localSkin(
            UUID profileId,
            String sha256,
            SkinVariant variant,
            Optional<URI> capeTexture) {
        return localSkin(profileId, sha256, variant, capeTexture, Optional.empty());
    }

    public static AppliedAppearance localSkin(
            UUID profileId,
            String sha256,
            SkinVariant variant,
            Optional<URI> capeTexture,
            Optional<String> localCapeCacheKey) {
        return new AppliedAppearance(
                profileId,
                Optional.empty(),
                Optional.of(Objects.requireNonNull(sha256, "sha256")),
                Optional.of(Objects.requireNonNull(variant, "variant")),
                capeTexture,
                localCapeCacheKey);
    }

    public static AppliedAppearance accountDefault(UUID profileId, Optional<URI> capeTexture) {
        return accountDefault(profileId, capeTexture, Optional.empty());
    }

    public static AppliedAppearance accountDefault(
            UUID profileId,
            Optional<URI> capeTexture,
            Optional<String> localCapeCacheKey) {
        return new AppliedAppearance(
                profileId,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                capeTexture,
                localCapeCacheKey);
    }

    public boolean usesAccountDefaultSkin() {
        return skinTexture.isEmpty() && localSkinSha256.isEmpty();
    }

    private static Optional<URI> normalizeTexture(Optional<URI> texture) {
        return texture.map(uri -> {
            if (!MinecraftServiceUriPolicy.isAllowedTextureUri(uri)) {
                throw new IllegalArgumentException("Texture URI is not allowlisted");
            }
            return uri;
        });
    }
}

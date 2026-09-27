package com.naocraftlab.skins.core.api;

import com.naocraftlab.skins.core.importing.PublicProfileLookup;
import com.naocraftlab.skins.core.importing.PublicSkinImageSource;
import com.naocraftlab.skins.core.png.NormalizedSkin;
import com.naocraftlab.skins.core.png.PngValidationException;
import com.naocraftlab.skins.core.png.PngValidator;
import com.naocraftlab.skins.core.storage.TextureCache;
import com.naocraftlab.skins.core.storage.TextureCacheException;
import java.util.Objects;

public final class PublicSkinImageAdapter implements PublicSkinImageSource {
    private final TextureCache textures;
    private final SafeRemotePngFetcher remote;
    private final PngValidator validator;

    public PublicSkinImageAdapter(TextureCache textures) {
        this(textures, new SafeRemotePngFetcher(), new PngValidator());
    }

    PublicSkinImageAdapter(TextureCache textures, SafeRemotePngFetcher remote, PngValidator validator) {
        this.textures = Objects.requireNonNull(textures, "textures");
        this.remote = Objects.requireNonNull(remote, "remote");
        this.validator = Objects.requireNonNull(validator, "validator");
    }

    @Override
    public NormalizedSkin loadVerified(PublicProfileLookup.VerifiedTexture texture) throws Exception {
        if (!(texture instanceof PublicPlayerSkinClient.VerifiedPublicTexture verified)) {
            throw new PublicSkinImportException(PublicSkinImportException.Code.PROFILE_REJECTED,
                    "Public player skin texture was rejected.");
        }
        try {
            return new com.naocraftlab.skins.core.png.PngFileReader(validator).normalizeSkinWithVariant(textures.get(verified.uri()).path());
        } catch (TextureCacheException failure) {
            throw playerTextureFailure(failure);
        } catch (PngValidationException failure) {
            throw new PublicSkinImportException(failure.reason() == PngValidationException.Reason.OVERSIZED
                    ? PublicSkinImportException.Code.OVERSIZED : PublicSkinImportException.Code.PROFILE_REJECTED,
                    "Public player skin texture was rejected.");
        }
    }

    @Override
    public NormalizedSkin loadUntrusted(String url) throws Exception {
        return remote.fetchSkin(url);
    }

    static PublicSkinImportException playerTextureFailure(TextureCacheException failure) {
        Objects.requireNonNull(failure, "failure");
        PublicSkinImportException.Code code = switch (failure.code()) {
            case NETWORK_FAILURE, HTTP_FAILURE -> PublicSkinImportException.Code.NETWORK_FAILURE;
            case OVERSIZED -> PublicSkinImportException.Code.OVERSIZED;
            case HOST_NOT_ALLOWLISTED, REDIRECT_REJECTED, INVALID_TEXTURE -> PublicSkinImportException.Code.PROFILE_REJECTED;
        };
        return new PublicSkinImportException(code, "Public player skin texture was rejected.");
    }

}

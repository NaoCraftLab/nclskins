package com.naocraftlab.skins.core.api;

import com.naocraftlab.skins.core.importing.PublicProfileLookup;
import com.naocraftlab.skins.core.importing.PublicSkinImageSource;
import com.naocraftlab.skins.core.png.NormalizedSkin;
import com.naocraftlab.skins.core.png.PngValidationException;
import com.naocraftlab.skins.core.png.PngValidator;
import com.naocraftlab.skins.core.storage.TextureCache;
import com.naocraftlab.skins.core.storage.TextureCacheException;
import java.util.Objects;

public final class PublicSkinImageAdapter implements PublicSkinImageSource, PublicSkinImageSource.DependencySource {
    private final TextureCache textures;
    private final SafeRemotePngFetcher remote;
    private final PngValidator validator;
    private final PublicHttpsImageFetcher dependencies = new PublicHttpsImageFetcher();

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

    @Override
    public Acquired acquireFile(java.nio.file.Path path) throws Exception {
        return acquireLocal(path);
    }

    @Override
    public Acquired acquireUrl(String url) throws Exception {
        return preflight(remote.fetchInput(url));
    }

    @Override public byte[] acquireDependency(java.net.URI uri, java.time.Duration timeout, int maximum) throws java.io.IOException {
        return acquireDependency(dependencies, uri, timeout, maximum);
    }

    static byte[] acquireDependency(PublicHttpsImageFetcher fetcher, java.net.URI uri,
            java.time.Duration timeout, int maximum) throws java.io.IOException {
        try {
            var response = fetcher.get(uri, timeout, maximum);
            if (response.status() != 200) throw new PublicSkinImportException(switch (response.status()) {
                case 401, 403 -> PublicSkinImportException.Code.SITE_BLOCKED;
                case 429 -> PublicSkinImportException.Code.RATE_LIMITED;
                case 301, 302, 303, 307, 308 -> PublicSkinImportException.Code.REDIRECT_REJECTED;
                default -> PublicSkinImportException.Code.SERVICE_UNAVAILABLE;
            }, "Dependency service rejected request");
            return response.bytes();
        } catch (PublicHttpsImageFetcher.UnsafeImageUriException failure) {
            throw new PublicSkinImportException(PublicSkinImportException.Code.UNSAFE_URL, "Unsafe dependency destination");
        } catch (PublicHttpsImageFetcher.OversizedImageException failure) {
            throw new PublicSkinImportException(PublicSkinImportException.Code.OVERSIZED, "Dependency operation limit");
        } catch (PublicSkinImportException failure) {
            throw failure;
        } catch (java.io.IOException failure) {
            throw new PublicSkinImportException(PublicSkinImportException.Code.NETWORK_FAILURE, "Dependency request failed");
        }
    }

    public static Acquired acquireLocal(java.nio.file.Path path) throws Exception {
        try {
            return preflight(com.naocraftlab.skins.client.FrozenImportInput.capture(path));
        } catch (com.naocraftlab.skins.client.FrozenImportInput.Rejected invalid) {
            throw new PngValidationException(invalid.reason() == com.naocraftlab.skins.client.FrozenImportInput.Rejection.LIMIT
                    ? PngValidationException.Reason.OVERSIZED : PngValidationException.Reason.BAD_SIGNATURE, "Unsupported input format");
        }
    }

    private static Acquired preflight(com.naocraftlab.skins.client.ImportInput frozen) throws Exception {
        boolean retained = false;
        try {
            try (var input = frozen.open()) {
                if (frozen.kind() == com.naocraftlab.skins.client.ImportInput.Kind.PNG) {
                    return new PngInput(new com.naocraftlab.skins.core.png.PngFileReader(new PngValidator()).projectImport(input));
                }

            }
            retained = true;
            return new ModelInput(frozen);
        } finally { if (!retained) frozen.close(); }
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

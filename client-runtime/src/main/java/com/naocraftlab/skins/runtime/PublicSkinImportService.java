package com.naocraftlab.skins.runtime;

import com.naocraftlab.skins.client.MinecraftSkinCatalog;
import com.naocraftlab.skins.client.SignedTextureVerifier;
import com.naocraftlab.skins.client.SkinModel;
import com.naocraftlab.skins.core.api.PublicPlayerSkinClient;
import com.naocraftlab.skins.core.api.PublicSkinImportException;
import com.naocraftlab.skins.core.api.SafeRemotePngFetcher;
import com.naocraftlab.skins.core.model.PersonalSkinSource;
import com.naocraftlab.skins.core.model.SkinVariant;
import com.naocraftlab.skins.core.png.NormalizedSkin;
import com.naocraftlab.skins.core.png.PngValidationException;
import com.naocraftlab.skins.core.png.PngValidator;
import com.naocraftlab.skins.core.storage.TextureCache;
import com.naocraftlab.skins.core.storage.TextureCacheException;

import java.util.Locale;
import java.util.Objects;


final class PublicSkinImportService {
    private final TextureCache textures;
    private final CatalogVariantLoader catalog;
    private final SafeRemotePngFetcher remotePng;
    private final PngValidator pngValidator;
    private PublicPlayerSkinClient publicPlayers;

    PublicSkinImportService(TextureCache textures, CatalogVariantLoader catalog) {
        this(textures, catalog, new SafeRemotePngFetcher(), new PngValidator());
    }

    PublicSkinImportService(
            TextureCache textures,
            CatalogVariantLoader catalog,
            SafeRemotePngFetcher remotePng,
            PngValidator pngValidator) {
        this.textures = Objects.requireNonNull(textures, "textures");
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.remotePng = Objects.requireNonNull(remotePng, "remotePng");
        this.pngValidator = Objects.requireNonNull(pngValidator, "pngValidator");
    }

    void enablePlayerLookup(SignedTextureVerifier verifier) {
        publicPlayers = new PublicPlayerSkinClient(Objects.requireNonNull(verifier, "verifier"));
    }

    ClientOperations.ImportDraft loadPlayer(String playerNameOrUuid) throws Exception {
        PublicPlayerSkinClient players = publicPlayers;
        if (players == null) {
            throw new UnsupportedOperationException("Public player skin lookup is unavailable");
        }
        return loadResolvedPlayer(players.lookup(playerNameOrUuid));
    }

    ClientOperations.ImportDraft loadResolvedPlayer(PublicPlayerSkinClient.Result result) throws Exception {
        Objects.requireNonNull(result, "result");
        NormalizedSkin skin;
        if (result.textureUri().isPresent()) {
            try {
                skin = pngValidator.normalizeSkinWithVariant(
                        textures.get(result.textureUri().orElseThrow()).path());
            } catch (TextureCacheException failure) {
                throw playerTextureFailure(failure);
            } catch (PngValidationException failure) {
                throw playerTextureFailure(failure);
            }
        } else {
            try {
                byte[] png = catalog.load(
                        MinecraftSkinCatalog.COLLECTION_ID,
                        result.defaultSkinId().orElseThrow(),
                        result.variant() == SkinVariant.SLIM ? SkinModel.SLIM : SkinModel.CLASSIC);
                skin = pngValidator.normalizeSkinWithVariant(png);
            } catch (PngValidationException failure) {
                throw playerTextureFailure(failure);
            }
        }
        return new ClientOperations.ImportDraft(
                result.canonicalName(), skin.detectedVariant(), skin.pngBytes(), PersonalSkinSource.PLAYER_NAME);
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

    static PublicSkinImportException playerTextureFailure(PngValidationException failure) {
        Objects.requireNonNull(failure, "failure");
        PublicSkinImportException.Code code = failure.reason() == PngValidationException.Reason.OVERSIZED
                ? PublicSkinImportException.Code.OVERSIZED
                : PublicSkinImportException.Code.PROFILE_REJECTED;
        return new PublicSkinImportException(code, "Public player skin texture was rejected.");
    }

    ClientOperations.ImportDraft loadUrl(String url) throws Exception {
        NormalizedSkin skin = remotePng.fetchSkin(url);
        String fallback = "Imported URL skin";
        String name = fallback;
        try {
            String path = java.net.URI.create(url.trim()).getPath();
            if (path != null && !path.isBlank()) {
                String candidate = path.substring(path.lastIndexOf('/') + 1);
                if (candidate.toLowerCase(Locale.ROOT).endsWith(".png")) {
                    candidate = candidate.substring(0, candidate.length() - 4);
                }
                name = UntrustedDisplayName.sanitize(candidate, fallback);
            }
        } catch (IllegalArgumentException ignored) {

        }
        return new ClientOperations.ImportDraft(
                name, skin.detectedVariant(), skin.pngBytes(), PersonalSkinSource.URL);
    }

    @FunctionalInterface
    interface CatalogVariantLoader {
        byte[] load(String collectionId, String skinId, SkinModel model) throws Exception;
    }
}

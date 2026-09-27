package com.naocraftlab.skins.runtime;

import com.naocraftlab.skins.client.MinecraftSkinCatalog;
import com.naocraftlab.skins.client.SkinModel;
import com.naocraftlab.skins.core.api.PublicSkinImportException;
import com.naocraftlab.skins.core.model.PersonalSkinSource;
import com.naocraftlab.skins.core.model.SkinVariant;
import com.naocraftlab.skins.core.png.NormalizedSkin;
import com.naocraftlab.skins.core.png.PngValidationException;
import com.naocraftlab.skins.core.png.PngValidator;
import com.naocraftlab.skins.core.importing.PublicProfileLookup;
import com.naocraftlab.skins.core.importing.PublicSkinImageSource;

import java.util.Optional;
import java.util.Locale;
import java.util.Objects;


public final class PublicSkinImportService implements PublicSkinImports {
    private final Optional<PublicProfileLookup> players;
    private final PublicSkinImageSource images;
    private final CatalogVariantLoader catalog;
    private final PngValidator pngValidator = new PngValidator();

    public PublicSkinImportService(Optional<PublicProfileLookup> players, PublicSkinImageSource images,
            CatalogVariantLoader catalog) {
        this.players = Objects.requireNonNull(players, "players");
        this.images = Objects.requireNonNull(images, "images");
        this.catalog = Objects.requireNonNull(catalog, "catalog");
    }

    @Override
    public ImportOperations.ImportDraft loadPlayer(String playerNameOrUuid) throws Exception {
        PublicProfileLookup lookup = players.orElseThrow(() ->
                new UnsupportedOperationException("Public player skin lookup is unavailable"));
        return loadResolvedPlayer(lookup.lookup(playerNameOrUuid));
    }

    private ImportOperations.ImportDraft loadResolvedPlayer(PublicProfileLookup.Observation result) throws Exception {
        Objects.requireNonNull(result, "result");
        NormalizedSkin skin;
        if (result.texture().isPresent()) {
            skin = images.loadVerified(result.texture().orElseThrow());
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
        return new ImportOperations.ImportDraft(
                result.canonicalName(), skin.detectedVariant(), skin.pngBytes(), PersonalSkinSource.PLAYER_NAME);
    }

    static PublicSkinImportException playerTextureFailure(PngValidationException failure) {
        Objects.requireNonNull(failure, "failure");
        PublicSkinImportException.Code code = failure.reason() == PngValidationException.Reason.OVERSIZED
                ? PublicSkinImportException.Code.OVERSIZED
                : PublicSkinImportException.Code.PROFILE_REJECTED;
        return new PublicSkinImportException(code, "Public player skin texture was rejected.");
    }

    @Override
    public ImportOperations.ImportDraft loadUrl(String url) throws Exception {
        NormalizedSkin skin = images.loadUntrusted(url);
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
        return new ImportOperations.ImportDraft(
                name, skin.detectedVariant(), skin.pngBytes(), PersonalSkinSource.URL);
    }

    @FunctionalInterface
    public interface CatalogVariantLoader {
        byte[] load(String collectionId, String skinId, SkinModel model) throws Exception;
    }
}

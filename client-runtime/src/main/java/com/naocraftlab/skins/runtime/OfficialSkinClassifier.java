package com.naocraftlab.skins.runtime;

import com.naocraftlab.skins.client.MinecraftSkinCatalog;
import com.naocraftlab.skins.client.SkinCatalogSource;
import com.naocraftlab.skins.client.SkinModel;
import com.naocraftlab.skins.core.model.AccountDefaultSkin;
import com.naocraftlab.skins.core.model.SkinVariant;
import com.naocraftlab.skins.core.png.PngValidationException;
import com.naocraftlab.skins.core.png.PngValidator;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

final class OfficialSkinClassifier {
    enum Result { DEFAULT, CUSTOM, UNKNOWN }

    private final SkinCatalogSource source;
    private final PngValidator png = new PngValidator();
    private final Map<AccountDefaultSkin, String> references = new HashMap<>();
    private long generation = Long.MIN_VALUE;

    OfficialSkinClassifier(SkinCatalogSource source) {
        this.source = Objects.requireNonNull(source, "source");
    }

    synchronized Result classify(UUID profileId, SkinVariant variant, byte[] bytes) {
        try {
            String actual = png.renderSha256(bytes);
            AccountDefaultSkin selected = AccountDefaultSkin.forProfile(profileId);
            if (selected.variant() != variant) {
                return Result.CUSTOM;
            }
            long currentGeneration = source.generation();
            if (generation != currentGeneration) {
                references.clear();
                generation = currentGeneration;
            }
            String reference = references.get(selected);
            if (reference == null) {
                reference = png.renderSha256(source.load(MinecraftSkinCatalog.COLLECTION_ID,
                        selected.skinId(), variant == SkinVariant.SLIM ? SkinModel.SLIM : SkinModel.CLASSIC));
                references.put(selected, reference);
            }
            return actual.equals(reference) ? Result.DEFAULT : Result.CUSTOM;
        } catch (IOException | PngValidationException | RuntimeException unavailable) {
            return Result.UNKNOWN;
        }
    }
}

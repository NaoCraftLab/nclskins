package com.naocraftlab.skins.core.service;

import com.naocraftlab.skins.core.model.AppearancePreset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;


public final class PresetGalleryOrder {
    private static final Comparator<AppearancePreset> NEWEST_FIRST =
            Comparator.comparing(AppearancePreset::createdAt)
                    .reversed()
                    .thenComparing(AppearancePreset::id);

    private PresetGalleryOrder() {}


    public static List<AppearancePreset> arrange(List<AppearancePreset> presets) {
        Objects.requireNonNull(presets, "presets");
        List<AppearancePreset> ordered = new ArrayList<>(presets);
        ordered.sort(NEWEST_FIRST);
        return List.copyOf(ordered);
    }
}

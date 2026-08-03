package com.naocraftlab.skins.core.service;

import com.naocraftlab.skins.core.model.AppearancePreset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;


public final class PresetGalleryOrder {
    private static final Comparator<AppearancePreset> NEWEST_FIRST =
            Comparator.comparing(AppearancePreset::createdAt)
                    .reversed()
                    .thenComparing(AppearancePreset::id);

    private PresetGalleryOrder() {}


    public static List<AppearancePreset> arrange(
            List<AppearancePreset> presets,
            UUID activePresetId) {
        Objects.requireNonNull(presets, "presets");
        List<AppearancePreset> ordered = new ArrayList<>(presets);
        ordered.sort(NEWEST_FIRST);
        if (activePresetId == null) {
            return List.copyOf(ordered);
        }

        for (int index = 0; index < ordered.size(); index++) {
            if (ordered.get(index).id().equals(activePresetId)) {
                AppearancePreset active = ordered.remove(index);
                ordered.add(0, active);
                break;
            }
        }
        return List.copyOf(ordered);
    }
}

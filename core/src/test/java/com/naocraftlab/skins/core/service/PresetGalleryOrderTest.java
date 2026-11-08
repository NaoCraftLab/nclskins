package com.naocraftlab.skins.core.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.naocraftlab.skins.core.model.AppearancePreset;
import com.naocraftlab.skins.core.model.SkinReference;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PresetGalleryOrderTest {
    private static final UUID OLDEST_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID MIDDLE_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID NEWEST_ID = UUID.fromString("00000000-0000-0000-0000-000000000003");

    @Test
    void newestFirstOrderIsIndependentOfInputOrder() {
        List<AppearancePreset> arranged = PresetGalleryOrder.arrange(
                List.of(
                        preset(NEWEST_ID, "2026-01-03T00:00:00Z"),
                        preset(OLDEST_ID, "2026-01-01T00:00:00Z"),
                        preset(MIDDLE_ID, "2026-01-02T00:00:00Z")));

        assertEquals(List.of(NEWEST_ID, MIDDLE_ID, OLDEST_ID), ids(arranged));
    }

    @Test
    void oldestFirstInputIsSortedNewestFirst() {
        List<AppearancePreset> arranged = PresetGalleryOrder.arrange(
                List.of(
                        preset(OLDEST_ID, "2026-01-01T00:00:00Z"),
                        preset(NEWEST_ID, "2026-01-03T00:00:00Z"),
                        preset(MIDDLE_ID, "2026-01-02T00:00:00Z")));

        assertEquals(List.of(NEWEST_ID, MIDDLE_ID, OLDEST_ID), ids(arranged));
    }

    @Test
    void equalCreationTimesUseUuidTieBreak() {
        List<AppearancePreset> arranged = PresetGalleryOrder.arrange(
                List.of(
                        preset(MIDDLE_ID, "2026-01-03T00:00:00Z"),
                        preset(OLDEST_ID, "2026-01-03T00:00:00Z"),
                        preset(NEWEST_ID, "2026-01-03T00:00:00Z")));

        assertEquals(List.of(OLDEST_ID, MIDDLE_ID, NEWEST_ID), ids(arranged));
    }

    private static AppearancePreset preset(UUID id, String createdAt) {
        Instant created = Instant.parse(createdAt);
        return new AppearancePreset(id, id.toString(), SkinReference.accountDefault(), null, created, created);
    }

    private static List<UUID> ids(List<AppearancePreset> presets) {
        return presets.stream().map(AppearancePreset::id).toList();
    }
}

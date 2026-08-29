package com.naocraftlab.skins.runtime;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class GuiIconTest {
    @Test
    void registrySealsSemanticPathsAndBaseCanvases() {
        Map<String, Integer> expected = Map.ofEntries(
                Map.entry("action/edit", 16),
                Map.entry("action/duplicate", 16),
                Map.entry("action/delete", 16),
                Map.entry("action/select_folder", 16),
                Map.entry("action/collapse_all", 16),
                Map.entry("action/expand_all", 16),
                Map.entry("action/add_look", 32),
                Map.entry("appearance/back/cape", 16),
                Map.entry("appearance/back/elytra", 16),
                Map.entry("appearance/back/none", 32),
                Map.entry("appearance/outer_layer/head/on", 16),
                Map.entry("appearance/outer_layer/head/off", 16),
                Map.entry("appearance/outer_layer/body/all_on", 16),
                Map.entry("appearance/outer_layer/body/all_off", 16),
                Map.entry("appearance/outer_layer/body/both_arms_off", 16),
                Map.entry("appearance/outer_layer/body/left_arm_off", 16),
                Map.entry("appearance/outer_layer/body/right_arm_off", 16),
                Map.entry("appearance/outer_layer/body/only_arms_on", 16),
                Map.entry("appearance/outer_layer/body/only_left_arm", 16),
                Map.entry("appearance/outer_layer/body/only_right_arm", 16),
                Map.entry("appearance/outer_layer/legs/all_on", 16),
                Map.entry("appearance/outer_layer/legs/all_off", 16),
                Map.entry("appearance/outer_layer/legs/left_off", 16),
                Map.entry("appearance/outer_layer/legs/right_off", 16),
                Map.entry("status/compatibility/extended", 16),
                Map.entry("status/compatibility/incompatible", 16));

        Map<String, Integer> actual = java.util.Arrays.stream(GuiIcon.values())
                .collect(Collectors.toUnmodifiableMap(GuiIcon::semanticPath, GuiIcon::baseCanvas));

        assertEquals(expected, actual);
        assertEquals(24, actual.values().stream().filter(size -> size == 16).count());
        assertEquals(2, actual.values().stream().filter(size -> size == 32).count());
        for (GuiIcon icon : GuiIcon.values()) {
            assertEquals("textures/gui/icons/" + icon.semanticPath() + ".png", icon.resourcePath());
        }
    }
}

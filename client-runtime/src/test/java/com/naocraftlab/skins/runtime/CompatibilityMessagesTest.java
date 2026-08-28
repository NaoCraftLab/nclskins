package com.naocraftlab.skins.runtime;

import com.naocraftlab.skins.core.compatibility.SkinCompatibility;
import com.naocraftlab.skins.core.compatibility.SkinCompatibilityStatus;
import com.naocraftlab.skins.core.compatibility.SkinConflictReason;
import com.naocraftlab.skins.core.compatibility.SkinFeature;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;


final class CompatibilityMessagesTest {
    @Test
    void supportedFeaturesUseHeadingAndBulletsWithoutEmptyConflictSection() {
        UiMessage message = CompatibilityMessages.accessibleLabel(new SkinCompatibility(
                SkinCompatibilityStatus.EXTENDED,
                List.of(SkinFeature.EARS, SkinFeature.FRESH_MOVES),
                List.of()));

        assertEquals("nclskins.compatibility.tooltip.3", message.key());
        assertEquals(List.of(
                UiMessage.info("nclskins.compatibility.tooltip.supports"),
                featureItem("ears"),
                featureItem("fresh_moves")), message.arguments());
    }

    @Test
    void conflictsDoNotAddEmptySupportedSection() {
        UiMessage message = CompatibilityMessages.accessibleLabel(new SkinCompatibility(
                SkinCompatibilityStatus.INCOMPATIBLE,
                List.of(),
                List.of(SkinConflictReason.MALFORMED_EXPRESSIVE_DATA)));

        assertEquals("nclskins.compatibility.tooltip.1", message.key());
        assertEquals(List.of(
                UiMessage.info("nclskins.compatibility.reason.malformed_expressive_data")),
                message.arguments());
    }

    @Test
    void conflictsComeFirstAndAreSeparatedFromSupportedFeaturesByBlankLine() {
        UiMessage message = CompatibilityMessages.accessibleLabel(new SkinCompatibility(
                SkinCompatibilityStatus.INCOMPATIBLE,
                List.of(SkinFeature.EARS),
                List.of(SkinConflictReason.MALFORMED_EXPRESSIVE_DATA)));

        assertEquals("nclskins.compatibility.tooltip.4", message.key());
        assertEquals(List.of(
                UiMessage.info("nclskins.compatibility.reason.malformed_expressive_data"),
                UiMessage.info("nclskins.compatibility.tooltip.blank"),
                UiMessage.info("nclskins.compatibility.tooltip.supports"),
                featureItem("ears")), message.arguments());
    }

    private static UiMessage featureItem(String feature) {
        return UiMessage.info(
                "nclskins.compatibility.tooltip.feature_item",
                UiMessage.info("nclskins.compatibility.feature." + feature));
    }
}

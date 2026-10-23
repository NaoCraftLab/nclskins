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
    void onlyCpmExpressiveReasonsCollapseAtTheirFirstPosition() {
        for (SkinConflictReason first : List.of(SkinConflictReason.CPM_FRESH_MOVES_OVERLAP,
                SkinConflictReason.CPM_JUST_EXPRESSIONS_OVERLAP)) {
            SkinConflictReason second = first == SkinConflictReason.CPM_FRESH_MOVES_OVERLAP
                    ? SkinConflictReason.CPM_JUST_EXPRESSIONS_OVERLAP : SkinConflictReason.CPM_FRESH_MOVES_OVERLAP;
            var single = CompatibilityMessages.accessibleLabel(new SkinCompatibility(SkinCompatibilityStatus.INCOMPATIBLE,
                    List.of(SkinFeature.CUSTOMIZABLE_PLAYER_MODELS), List.of(first)));
            var combined = CompatibilityMessages.accessibleLabel(new SkinCompatibility(SkinCompatibilityStatus.INCOMPATIBLE,
                    List.of(SkinFeature.CUSTOMIZABLE_PLAYER_MODELS), List.of(first, second)));
            assertEquals(single, combined);
            var mixed = CompatibilityMessages.accessibleLabel(new SkinCompatibility(SkinCompatibilityStatus.INCOMPATIBLE,
                    List.of(), List.of(SkinConflictReason.MALFORMED_EARS_DATA, first,
                            SkinConflictReason.SNEAKY_JUST_EXPRESSIONS_OVERLAP, second)));
            assertEquals(3, mixed.arguments().size());
            assertEquals(UiMessage.info("nclskins.compatibility.reason.malformed_ears_data"), mixed.arguments().get(0));
            assertEquals(single.arguments().get(0), mixed.arguments().get(1));
            assertEquals(UiMessage.info("nclskins.compatibility.reason.sneaky_just_expressions_overlap"), mixed.arguments().get(2));
        }
    }

    @Test void statusesUseSharedFunctionalIcons() {
        for (boolean incompatible : new boolean[]{false, true}) {
            var compatibility = new SkinCompatibility(
                    incompatible ? SkinCompatibilityStatus.INCOMPATIBLE : SkinCompatibilityStatus.EXTENDED,
                    List.of(SkinFeature.EARS),
                    incompatible ? List.of(SkinConflictReason.MALFORMED_EARS_DATA) : List.of());
            var icon = CompatibilityMessages.icon(compatibility);
            assertEquals("status/compatibility/" + (incompatible ? "incompatible" : "extended"), icon.semanticPath());
            assertEquals(16, icon.baseCanvas());
        }
    }

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

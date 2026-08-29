package com.naocraftlab.skins.runtime;

import com.naocraftlab.skins.core.compatibility.SkinCompatibility;
import com.naocraftlab.skins.core.compatibility.SkinCompatibilityStatus;
import com.naocraftlab.skins.core.compatibility.SkinConflictReason;
import com.naocraftlab.skins.core.compatibility.SkinFeature;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;


final class CompatibilityMessages {
    private CompatibilityMessages() {}

    static UiMessage accessibleLabel(SkinCompatibility compatibility) {
        Objects.requireNonNull(compatibility, "compatibility");
        List<Object> lines = new ArrayList<>();
        compatibility.activeConflicts().stream()
                .map(CompatibilityMessages::reason)
                .forEach(lines::add);
        if (!compatibility.supportedFeatures().isEmpty()) {
            if (!compatibility.activeConflicts().isEmpty()) {
                lines.add(UiMessage.info("nclskins.compatibility.tooltip.blank"));
            }
            lines.add(UiMessage.info("nclskins.compatibility.tooltip.supports"));
            compatibility.supportedFeatures().stream()
                    .map(CompatibilityMessages::feature)
                    .map(feature -> UiMessage.info(
                            "nclskins.compatibility.tooltip.feature_item", feature))
                    .forEach(lines::add);
        }
        if (lines.isEmpty()) {
            throw new IllegalArgumentException("compatibility indicator requires content");
        }
        return UiMessage.info(
                "nclskins.compatibility.tooltip." + lines.size(), lines.toArray());
    }

    static GuiIcon icon(SkinCompatibility compatibility) {
        return compatibility.status() == SkinCompatibilityStatus.INCOMPATIBLE
                ? GuiIcon.STATUS_COMPATIBILITY_INCOMPATIBLE
                : GuiIcon.STATUS_COMPATIBILITY_EXTENDED;
    }

    private static UiMessage feature(SkinFeature feature) {
        return UiMessage.info("nclskins.compatibility.feature." + switch (feature) {
            case EARS -> "ears";
            case FRESH_MOVES -> "fresh_moves";
            case JUST_EXPRESSIONS -> "just_expressions";
        });
    }

    private static UiMessage reason(SkinConflictReason reason) {
        return UiMessage.info("nclskins.compatibility.reason." + switch (reason) {
            case MALFORMED_EARS_DATA -> "malformed_ears_data";
            case MALFORMED_EXPRESSIVE_DATA -> "malformed_expressive_data";
            case MISSING_EXPRESSIVE_RUNTIME -> "missing_expressive_runtime";
        });
    }
}
